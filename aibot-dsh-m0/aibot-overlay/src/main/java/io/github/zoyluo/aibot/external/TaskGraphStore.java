package io.github.zoyluo.aibot.external;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * MC-2A Graph Core v0.
 *
 * <p>Durable operational DAG state only. This is deliberately not a planner and owns no Minecraft
 * mutation authority. A node may start physical work only when BridgeKernel explicitly dispatches
 * it through the existing execution ledger.</p>
 */
public final class TaskGraphStore {
    private static final byte[] MAGIC="AIBODYGR1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION=1, MAX_GRAPHS=128, MAX_NODES=64, MAX_DEPS=16;
    private static final long MAX_BYTES=2L*1024*1024;

    public enum NodeState {
        PENDING, BLOCKED, READY, RUNNING, SUSPENDED, DONE, FAILED, CANCELLED, STALE
    }
    public record SpatialRef(String worldId,String dimensionId,String objectId) {
        public SpatialRef {
            worldId=boundedId(worldId,"world_id",160);
            dimensionId=boundedId(dimensionId,"dimension_id",160);
            objectId=boundedId(objectId,"object_id",160);
        }
        String claimKey(String kind) { return kind+"|"+worldId+"|"+dimensionId+"|"+objectId; }
        Map<String,Object> wire() {
            return Map.of("world_id",worldId,"dimension_id",dimensionId,"object_id",objectId);
        }
    }
    public record Postcondition(String kind,SpatialRef subject) {
        public Postcondition {
            if(!Set.of("OPPORTUNITY_RESOLVED").contains(kind)) throw new IllegalArgumentException("unsupported_graph_postcondition");
            Objects.requireNonNull(subject);
        }
    }
    public record NodeSpec(String id,Set<String> dependencies,String operation,String arguments,
                           SpatialRef subject,Postcondition postcondition,String resourceClaim) {
        public NodeSpec {
            id=boundedId(id,"node_id",96);
            dependencies=dependencies==null?Set.of():Set.copyOf(dependencies);
            if(dependencies.size()>MAX_DEPS) throw new IllegalArgumentException("too_many_dependencies");
            if(operation==null || !BridgeKernel.OPERATIONS.contains(operation)) throw new IllegalArgumentException("unsupported_graph_operation");
            if(arguments==null || arguments.getBytes(StandardCharsets.UTF_8).length>16384) throw new IllegalArgumentException("graph_arguments_too_large");
            Objects.requireNonNull(subject); Objects.requireNonNull(postcondition);
            resourceClaim=boundedId(resourceClaim,"resource_claim",400);
        }
    }
    public record Dispatch(String graphId,String nodeId,String requestId,String operation,String arguments) {}
    private static final class Node {
        String id,operation,arguments,resourceClaim,executionId="",dispatchRequest="",reason="";
        SpatialRef subject; Postcondition postcondition;
        LinkedHashSet<String> dependencies=new LinkedHashSet<>();
        NodeState state=NodeState.PENDING;
        int attempt;
    }
    private static final class Graph {
        String id,producerKey,planKey,producerKind;
        long createdAt,updatedAt;
        LinkedHashMap<String,Node> nodes=new LinkedHashMap<>();
    }

    private final Path path;
    private final LongSupplier clock;
    private final LinkedHashMap<String,Graph> graphs=new LinkedHashMap<>();
    private final HashMap<String,String> producerIndex=new HashMap<>();
    private final HashMap<String,String> claims=new HashMap<>();

    public TaskGraphStore(Path path,LongSupplier clock) {
        this.path=path==null?null:path.toAbsolutePath();
        this.clock=Objects.requireNonNull(clock);
        load();
    }
    public static TaskGraphStore memory() { return new TaskGraphStore(null,System::currentTimeMillis); }

    /** Explicit LLM/user plan decision -> one finite operational fragment. No autonomous goal creation. */
    public synchronized Map<String,Object> planOpportunity(String planKey,SpatialRef ref) {
        planKey=boundedId(planKey,"plan_key",120);
        String producerKey="opportunity|"+planKey+"|"+ref.worldId()+"|"+ref.dimensionId()+"|"+ref.objectId();
        String existing=producerIndex.get(producerKey);
        if(existing!=null) return inspect(existing);

        String claim=ref.claimKey("opportunity");
        String claimant=claims.get(claim);
        if(claimant!=null && graphOwnsLiveClaim(graphs.get(claimant),claim))
            throw new BridgeFault(409,"graph_resource_claim_conflict:"+claimant);

        String graphId="graph-"+hash(producerKey).substring(0,24);
        String nodeId="n-mine-"+hash(ref.objectId()).substring(0,12);
        NodeSpec spec=new NodeSpec(nodeId,Set.of(),"mine_opportunity",
                JsonOutput.encode(Map.of("id",ref.objectId())),ref,
                new Postcondition("OPPORTUNITY_RESOLVED",ref),claim);
        Graph g=createFragment(graphId,producerKey,planKey,"OPPORTUNITY_EXPLICIT",List.of(spec));
        save();
        return graphWire(g,true);
    }

    /** Generic finite-DAG seam for future deterministic producers. Not exposed as an LLM tool. */
    private synchronized Graph createFragment(String graphId,String producerKey,String planKey,String producerKind,
                                      List<NodeSpec> specs) {
        if(graphs.size()>=MAX_GRAPHS) throw new BridgeFault(503,"task_graph_capacity_exhausted");
        if(specs==null || specs.isEmpty() || specs.size()>MAX_NODES) throw new BridgeFault(400,"invalid_graph_fragment_size");
        if(graphs.containsKey(graphId)) throw new BridgeFault(409,"graph_id_conflict");
        Graph g=new Graph(); g.id=boundedId(graphId,"graph_id",96);
        g.producerKey=boundedId(producerKey,"producer_key",400);
        g.planKey=boundedId(planKey,"plan_key",120);
        g.producerKind=boundedId(producerKind,"producer_kind",80);
        g.createdAt=g.updatedAt=clock.getAsLong();
        for(NodeSpec spec:specs) {
            if(g.nodes.containsKey(spec.id())) throw new BridgeFault(400,"duplicate_graph_node");
            Node n=new Node(); n.id=spec.id(); n.dependencies.addAll(spec.dependencies());
            n.operation=spec.operation(); n.arguments=spec.arguments(); n.subject=spec.subject();
            n.postcondition=spec.postcondition(); n.resourceClaim=spec.resourceClaim();
            g.nodes.put(n.id,n);
        }
        validateDag(g);
        for(Node n:g.nodes.values()) {
            String owner=claims.get(n.resourceClaim);
            if(owner!=null && graphOwnsLiveClaim(graphs.get(owner),n.resourceClaim))
                throw new BridgeFault(409,"graph_resource_claim_conflict:"+owner);
        }
        refreshReadiness(g);
        graphs.put(g.id,g); producerIndex.put(g.producerKey,g.id);
        for(Node n:g.nodes.values()) if(isClaimHolding(n.state)) claims.put(n.resourceClaim,g.id);
        return g;
    }

    public synchronized Map<String,Object> list() {
        ArrayList<Map<String,Object>> items=new ArrayList<>();
        for(Graph g:graphs.values()) items.add(graphWire(g,false));
        return Map.of("schema","mc.task_graph.v0","graphs",items,"count",items.size());
    }
    public synchronized Map<String,Object> inspect(String graphId) {
        Graph g=graphs.get(graphId);
        if(g==null) throw new BridgeFault(404,"graph_not_found");
        return graphWire(g,true);
    }
    public synchronized Optional<String> runningExecution(String graphId) {
        Graph g=requireGraph(graphId);
        return g.nodes.values().stream()
                .filter(n->n.state==NodeState.RUNNING && !n.executionId.isBlank())
                .map(n->n.executionId).findFirst();
    }

    /**
     * Durable RUNNING admission comes before BridgeKernel physical submission. If known admission
     * fails before an execution receipt exists, rejectPreparedDispatch rolls back to READY.
     */
    public synchronized Dispatch prepareDispatch(String graphId) {
        Graph g=requireGraph(graphId);
        for(Node n:g.nodes.values()) {
            if(n.state==NodeState.RUNNING) throw new BridgeFault(409,"graph_node_already_running");
            if(n.state==NodeState.SUSPENDED) throw new BridgeFault(409,"graph_suspended_reconcile_or_cancel");
        }
        refreshReadiness(g);
        Node chosen=g.nodes.values().stream().filter(n->n.state==NodeState.READY)
                .min(Comparator.comparing(n->n.id)).orElse(null);
        if(chosen==null) throw new BridgeFault(409,"graph_has_no_ready_node");
        chosen.state=NodeState.RUNNING; chosen.reason="dispatch_admitted";
        chosen.attempt++;
        chosen.dispatchRequest="graphd-"+hash(g.id+"|"+chosen.id+"|"+chosen.attempt).substring(0,40);
        g.updatedAt=clock.getAsLong(); save();
        return new Dispatch(g.id,chosen.id,chosen.dispatchRequest,chosen.operation,chosen.arguments);
    }
    public synchronized void attachExecution(Dispatch d,String executionId) {
        Graph g=requireGraph(d.graphId()); Node n=requireNode(g,d.nodeId());
        if(n.state!=NodeState.RUNNING || !n.dispatchRequest.equals(d.requestId()))
            throw new BridgeFault(503,"graph_dispatch_state_mismatch");
        n.executionId=boundedId(executionId,"execution_id",160);
        n.reason="execution_attached"; g.updatedAt=clock.getAsLong(); save();
    }
    public synchronized void rejectPreparedDispatch(Dispatch d,String reason) {
        Graph g=graphs.get(d.graphId()); if(g==null)return;
        Node n=g.nodes.get(d.nodeId()); if(n==null)return;
        if(n.state==NodeState.RUNNING && n.executionId.isBlank() && n.dispatchRequest.equals(d.requestId())) {
            n.state=NodeState.READY; n.reason="dispatch_rejected:"+bound(reason,160);
            n.dispatchRequest=""; g.updatedAt=clock.getAsLong(); save();
        }
    }

    public synchronized void executionTerminal(String executionId,String state,
            Function<Postcondition,BodyBackend.GraphPostconditionResult> verifier) {
        for(Graph g:graphs.values()) for(Node n:g.nodes.values()) {
            if(!executionId.equals(n.executionId)) continue;
            if(n.state!=NodeState.RUNNING && n.state!=NodeState.SUSPENDED) return;
            switch(state) {
                case "completed" -> {
                    BodyBackend.GraphPostconditionResult v=verifier.apply(n.postcondition);
                    if(v.state()==BodyBackend.GraphPostconditionState.SATISFIED) {
                        n.state=NodeState.DONE; n.reason="postcondition_satisfied:"+bound(v.reason(),160);
                    } else if(v.state()==BodyBackend.GraphPostconditionState.UNSATISFIED) {
                        n.state=NodeState.STALE; n.reason="postcondition_unsatisfied:"+bound(v.reason(),160);
                    } else {
                        n.state=NodeState.SUSPENDED; n.reason="postcondition_unknown:"+bound(v.reason(),160);
                    }
                }
                case "failed" -> { n.state=NodeState.FAILED; n.reason="execution_failed"; }
                case "cancelled" -> { n.state=NodeState.CANCELLED; n.reason="execution_cancelled"; }
                case "outcome_unknown" -> { n.state=NodeState.SUSPENDED; n.reason="execution_outcome_unknown_no_replay"; }
                default -> { return; }
            }
            g.updatedAt=clock.getAsLong(); refreshReadiness(g); rebuildClaims(); save(); return;
        }
    }

    /** Restart / stale-evidence reconcile never dispatches. It can only prove a suspended node DONE. */
    public synchronized void reconcileSuspended(Function<Postcondition,BodyBackend.GraphPostconditionResult> verifier) {
        boolean dirty=false;
        for(Graph g:graphs.values()) for(Node n:g.nodes.values()) if(n.state==NodeState.SUSPENDED) {
            BodyBackend.GraphPostconditionResult v=verifier.apply(n.postcondition);
            if(v.state()==BodyBackend.GraphPostconditionState.SATISFIED) {
                n.state=NodeState.DONE; n.reason="reconciled_postcondition_satisfied:"+bound(v.reason(),160);
                g.updatedAt=clock.getAsLong(); refreshReadiness(g); dirty=true;
            }
        }
        if(dirty) { rebuildClaims(); save(); }
    }

    public synchronized Map<String,Object> cancel(String graphId,String reason) {
        Graph g=requireGraph(graphId);
        for(Node n:g.nodes.values()) if(n.state==NodeState.RUNNING)
            throw new BridgeFault(409,"graph_has_running_execution_cancel_execution_first");
        for(Node n:g.nodes.values()) if(isClaimHolding(n.state)) {
            n.state=NodeState.CANCELLED; n.reason="graph_cancelled:"+bound(reason,160);
        }
        g.updatedAt=clock.getAsLong(); rebuildClaims(); save();
        return graphWire(g,true);
    }

    private void refreshReadiness(Graph g) {
        for(Node n:g.nodes.values()) if(n.state==NodeState.PENDING || n.state==NodeState.READY || n.state==NodeState.BLOCKED) {
            boolean terminalBlock=false, allDone=true;
            for(String depId:n.dependencies) {
                Node dep=g.nodes.get(depId);
                if(dep==null) throw new BridgeFault(503,"graph_dependency_missing");
                if(Set.of(NodeState.FAILED,NodeState.CANCELLED,NodeState.STALE).contains(dep.state)) terminalBlock=true;
                if(dep.state!=NodeState.DONE) allDone=false;
            }
            if(terminalBlock) { n.state=NodeState.BLOCKED; n.reason="dependency_terminal"; }
            else if(allDone) { n.state=NodeState.READY; n.reason="dependencies_satisfied"; }
            else { n.state=NodeState.PENDING; n.reason="waiting_dependencies"; }
        }
    }
    private void validateDag(Graph g) {
        for(Node n:g.nodes.values()) for(String dep:n.dependencies)
            if(!g.nodes.containsKey(dep) || dep.equals(n.id)) throw new BridgeFault(400,"invalid_graph_dependency");
        HashMap<String,Integer> marks=new HashMap<>();
        for(String id:g.nodes.keySet()) visit(g,id,marks);
    }
    private static void visit(Graph g,String id,Map<String,Integer> marks) {
        int mark=marks.getOrDefault(id,0);
        if(mark==1) throw new BridgeFault(400,"graph_cycle");
        if(mark==2)return;
        marks.put(id,1);
        for(String dep:g.nodes.get(id).dependencies) visit(g,dep,marks);
        marks.put(id,2);
    }
    private boolean graphOwnsLiveClaim(Graph g,String claim) {
        return g!=null && g.nodes.values().stream().anyMatch(n->claim.equals(n.resourceClaim)&&isClaimHolding(n.state));
    }
    private static boolean isClaimHolding(NodeState s) {
        return Set.of(NodeState.PENDING,NodeState.BLOCKED,NodeState.READY,NodeState.RUNNING,NodeState.SUSPENDED).contains(s);
    }
    private void rebuildClaims() {
        claims.clear();
        for(Graph g:graphs.values()) for(Node n:g.nodes.values()) if(isClaimHolding(n.state)) {
            String prior=claims.putIfAbsent(n.resourceClaim,g.id);
            if(prior!=null && !prior.equals(g.id)) throw new BridgeFault(503,"graph_claim_collision_persisted");
        }
    }

    private Graph requireGraph(String id) {
        Graph g=graphs.get(id); if(g==null)throw new BridgeFault(404,"graph_not_found"); return g;
    }
    private static Node requireNode(Graph g,String id) {
        Node n=g.nodes.get(id); if(n==null)throw new BridgeFault(404,"graph_node_not_found"); return n;
    }
    private Map<String,Object> graphWire(Graph g,boolean includeNodes) {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("graph_id",g.id); out.put("producer_kind",g.producerKind); out.put("producer_key",g.producerKey);
        out.put("plan_key",g.planKey); out.put("state",graphState(g));
        out.put("created_at_ms",g.createdAt); out.put("updated_at_ms",g.updatedAt);
        if(includeNodes) {
            ArrayList<Map<String,Object>> nodes=new ArrayList<>();
            for(Node n:g.nodes.values()) {
                LinkedHashMap<String,Object> w=new LinkedHashMap<>();
                w.put("node_id",n.id); w.put("state",n.state.name()); w.put("dependencies",List.copyOf(n.dependencies));
                w.put("operation",n.operation); w.put("subject_ref",n.subject.wire());
                w.put("postcondition",n.postcondition.kind()); w.put("resource_claim",n.resourceClaim);
                w.put("execution_id",n.executionId); w.put("dispatch_request_id",n.dispatchRequest);
                w.put("attempt",n.attempt); w.put("reason",n.reason); nodes.add(w);
            }
            out.put("nodes",nodes);
        }
        return out;
    }
    private static String graphState(Graph g) {
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.RUNNING))return"RUNNING";
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.SUSPENDED))return"SUSPENDED";
        if(g.nodes.values().stream().allMatch(n->n.state==NodeState.DONE))return"DONE";
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.FAILED))return"FAILED";
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.STALE))return"STALE";
        if(g.nodes.values().stream().allMatch(n->n.state==NodeState.CANCELLED))return"CANCELLED";
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.READY))return"READY";
        if(g.nodes.values().stream().anyMatch(n->n.state==NodeState.BLOCKED))return"BLOCKED";
        return"PENDING";
    }

    private void load() {
        if(path==null || !Files.exists(path))return;
        try {
            if(Files.size(path)>MAX_BYTES)throw new IOException("graph_store_size_limit");
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                byte[] magic=in.readNBytes(MAGIC.length); if(!Arrays.equals(magic,MAGIC))throw new IOException("bad_magic");
                if(in.readInt()!=VERSION)throw new IOException("bad_version");
                int count=in.readInt(); if(count<0||count>MAX_GRAPHS)throw new IOException("bad_graph_count");
                for(int gi=0;gi<count;gi++) {
                    Graph g=new Graph(); g.id=read(in,96);g.producerKey=read(in,400);g.planKey=read(in,120);g.producerKind=read(in,80);
                    g.createdAt=in.readLong();g.updatedAt=in.readLong();
                    int nc=in.readInt();if(nc<1||nc>MAX_NODES)throw new IOException("bad_node_count");
                    for(int ni=0;ni<nc;ni++) {
                        Node n=new Node();n.id=read(in,96);n.state=NodeState.valueOf(read(in,32));
                        n.operation=read(in,80);n.arguments=read(in,16384);n.resourceClaim=read(in,400);
                        n.subject=new SpatialRef(read(in,160),read(in,160),read(in,160));
                        n.postcondition=new Postcondition(read(in,80),n.subject);
                        n.executionId=read(in,160);n.dispatchRequest=read(in,160);n.reason=read(in,512);n.attempt=in.readInt();
                        int dc=in.readInt();if(dc<0||dc>MAX_DEPS)throw new IOException("bad_dep_count");
                        for(int di=0;di<dc;di++)n.dependencies.add(read(in,96));
                        if(g.nodes.put(n.id,n)!=null)throw new IOException("duplicate_node");
                    }
                    validateDag(g);
                    if(graphs.put(g.id,g)!=null)throw new IOException("duplicate_graph");
                    if(producerIndex.put(g.producerKey,g.id)!=null)throw new IOException("duplicate_producer");
                }
                if(in.read()!=-1)throw new IOException("trailing_bytes");
            }
            rebuildClaims();
            boolean dirty=false;
            for(Graph g:graphs.values())for(Node n:g.nodes.values())if(n.state==NodeState.RUNNING){
                n.state=NodeState.SUSPENDED;n.reason="restart_revalidation_required_no_replay";g.updatedAt=clock.getAsLong();dirty=true;
            }
            if(dirty){rebuildClaims();save();}
        } catch(Exception e) {
            graphs.clear();producerIndex.clear();claims.clear();
            if(e instanceof BridgeFault f)throw f;
            throw new BridgeFault(503,"task_graph_store_invalid");
        }
    }
    private void save() {
        if(path==null)return;
        try {
            Files.createDirectories(path.getParent());
            Path temp=path.resolveSibling(path.getFileName()+".tmp");
            try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)))) {
                d.write(MAGIC);d.writeInt(VERSION);d.writeInt(graphs.size());
                for(Graph g:graphs.values()) {
                    write(d,g.id,96);write(d,g.producerKey,400);write(d,g.planKey,120);write(d,g.producerKind,80);
                    d.writeLong(g.createdAt);d.writeLong(g.updatedAt);d.writeInt(g.nodes.size());
                    for(Node n:g.nodes.values()) {
                        write(d,n.id,96);write(d,n.state.name(),32);write(d,n.operation,80);write(d,n.arguments,16384);
                        write(d,n.resourceClaim,400);write(d,n.subject.worldId(),160);write(d,n.subject.dimensionId(),160);
                        write(d,n.subject.objectId(),160);write(d,n.postcondition.kind(),80);write(d,n.executionId,160);
                        write(d,n.dispatchRequest,160);write(d,n.reason,512);d.writeInt(n.attempt);
                        d.writeInt(n.dependencies.size());for(String dep:n.dependencies)write(d,dep,96);
                    }
                }
            }
            if(Files.size(temp)>MAX_BYTES){Files.deleteIfExists(temp);throw new IOException("graph_store_size_limit");}
            try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE)){ch.force(true);}
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        } catch(IOException e){throw new BridgeFault(503,"task_graph_store_io_failure");}
    }
    private static void write(DataOutputStream d,String value,int max)throws IOException{
        byte[] b=value.getBytes(StandardCharsets.UTF_8);if(b.length>max)throw new IOException("string_too_large");d.writeInt(b.length);d.write(b);
    }
    private static String read(DataInputStream in,int max)throws IOException{
        int n=in.readInt();if(n<0||n>max)throw new IOException("bad_string");return new String(in.readNBytes(n),StandardCharsets.UTF_8);
    }
    private static String boundedId(String value,String what,int max){
        if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<0x20))
            throw new IllegalArgumentException("invalid_"+what);
        return value;
    }
    private static String bound(String s,int max){return s==null?"":s.length()<=max?s:s.substring(0,max);}
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
