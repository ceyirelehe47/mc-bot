package io.github.zoyluo.aibot.external;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot;
import io.github.zoyluo.aibot.external.cognition.EvidenceRef;

/** Protocol-independent, single-body coordinator. HTTP threads may only admit work/read caches. */
public final class BridgeKernel {
    public static final Set<String> OPERATIONS=Set.of("goto","gather","craft","eat","container_transfer","set_base","deposit","say","register_home","capture_home","repair_home","register_farm","tend_farm","mine_opportunity","place","move_items");
    private static final Set<String> TERMINAL=Set.of("completed","failed","cancelled","outcome_unknown");
    private static final int MAX_EXECUTIONS=1024, MAX_CONTROLS=2048;
    private static final long LEASE_MS=30000, OBSERVATION_MAX_AGE_MS=5000;
    private final BridgeJournal journal;
    private final BodyBackend backend;
    private final TaskGraphStore graphs;
    private final LongSupplier mono;
    private final Map<String,Execution> byId=new LinkedHashMap<>();
    private final Map<String,Execution> byRequest=new HashMap<>();
    private final Map<String,Control> controls=new LinkedHashMap<>();
    private final Deque<Control> controlQueue=new ArrayDeque<>();
    public final String runtimeEpoch=UUID.randomUUID().toString();
    private String token, owner, leaseEpoch;
    private long expiresAt, leaseCounter;
    private String bodyId="", backendKind="", bodyInstanceId="", bodySessionEpoch="", observation="{}", fault="";
    private long observedAt=-1;
    private boolean ready, needsReconcile, pauseRequested, stopped;
    private boolean graphStartupDoneAudit;
    private Execution active;
    // MC-2A0 只读认知查询缓存:server 线程 tick 构建,HTTP 线程只读。
    private CognitiveSnapshot.Snapshot cognitive;
    private long cognitiveTick=-1, lastServerTick=-1;
    private String cognitiveFault="";
    // MC-2A0.1:inspect materialize 与 inspect-local 共用同一条 server 线程查询队列和
    // 同一份"每 tick 至多执行一个"预算(PERF-1);deadline 过期的查询先回收再谈执行(PERF-2)。
    private final ArrayDeque<LocalQuery> localQueries=new ArrayDeque<>();
    private static final int MAX_QUEUED_QUERIES=4;
    private static final long QUERY_DEADLINE_MS=5000;
    private record LocalQuery(String kind,String ref,int radius,String detail,
                              CompletableFuture<String> future,long deadline) {
        static LocalQuery local(int radius,String detail,CompletableFuture<String> future,long deadline) {
            return new LocalQuery("local","",radius,detail,future,deadline);
        }
        static LocalQuery inspect(String ref,String detail,CompletableFuture<String> future,long deadline) {
            return new LocalQuery("inspect",ref,0,detail,future,deadline);
        }
    }

    private static final class Execution {
        String id, request, fingerprint, owner, operation, arguments, admittingLeaseEpoch;
        String bodyId, backendKind, bodyInstanceId, bodySessionEpoch;
        String state="accepted", reason="";
        double progress;
        BodyBackend.Handle handle;
        Map<String,Object> wire() {
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("execution_id",id); out.put("request_id",request);
            out.put("body_id",bodyId); out.put("backend_kind",backendKind);
            out.put("body_instance_id",bodyInstanceId);
            out.put("body_session_epoch",bodySessionEpoch);
            out.put("operation",operation);
            out.put("state",state); out.put("progress",progress); out.put("reason",reason);
            out.put("terminal",TERMINAL.contains(state));
            out.put("success_scope","bounded_body_operation_not_user_goal");
            return out;
        }
    }
    private static final class Control {
        String request, fingerprint, executionId, action, leaseToken, state="accepted", reason="";
        Map<String,Object> wire() {
            return Map.of("request_id",request,"execution_id",executionId,"action",action,"state",state,"reason",reason);
        }
    }
    public BridgeKernel(BridgeJournal journal, BodyBackend backend) {
        this(journal,backend,()->System.nanoTime()/1_000_000L,TaskGraphStore.memory());
    }
    public BridgeKernel(BridgeJournal journal,BodyBackend backend,LongSupplier monotonicMillis) {
        this(journal,backend,monotonicMillis,TaskGraphStore.memory());
    }
    public BridgeKernel(BridgeJournal journal,BodyBackend backend,TaskGraphStore graphs) {
        this(journal,backend,()->System.nanoTime()/1_000_000L,graphs);
    }
    BridgeKernel(BridgeJournal journal,BodyBackend backend,LongSupplier monotonicMillis,TaskGraphStore graphs) {
        this.journal=journal; this.backend=backend; this.mono=monotonicMillis; this.graphs=Objects.requireNonNull(graphs);
        recover();
        event("runtime_started","",Map.of("runtime_epoch",runtimeEpoch));
    }
    private void recover() {
        for(var f:journal.replay()) {
            Map<String,String> m=f.fields();
            if("execution".equals(m.get("kind"))) {
                Execution e=byId.get(m.get("execution_id"));
                if(e==null) {
                    if(!m.containsKey("request_id")) throw new BridgeFault(503,"journal_missing_execution_acceptance");
                    e=new Execution(); e.id=m.get("execution_id"); e.request=m.get("request_id");
                    e.fingerprint=m.get("fingerprint"); e.owner=m.get("owner");
                    e.operation=m.get("operation"); e.arguments=m.get("arguments");
                    e.bodyId=m.getOrDefault("body_id", "");
                    e.backendKind=m.getOrDefault("backend_kind", "");
                    e.bodyInstanceId=m.getOrDefault("body_instance_id", "");
                    e.bodySessionEpoch=m.getOrDefault("body_session_epoch", "");
                    byId.put(e.id,e); byRequest.put(e.request,e);
                }
                e.state=m.get("state"); e.reason=m.getOrDefault("reason","");
                e.progress=Double.parseDouble(m.getOrDefault("progress","0"));
            } else if("body_binding".equals(m.get("kind"))) {
                bodyId=m.getOrDefault("body_id", "");
                backendKind=m.getOrDefault("backend_kind", "");
                bodyInstanceId=m.getOrDefault("body_instance_id", "");
                bodySessionEpoch=m.getOrDefault("body_session_epoch", "");
            } else if("control".equals(m.get("kind"))) {
                Control c=controls.computeIfAbsent(m.get("request_id"),k->new Control());
                c.request=m.get("request_id"); c.fingerprint=m.get("fingerprint");
                c.executionId=m.get("execution_id"); c.action=m.get("action");
                c.state=m.get("state"); c.reason=m.getOrDefault("reason","");
            }
        }
        for(Execution e:byId.values()) if(!TERMINAL.contains(e.state)) {
            transition(e,"outcome_unknown",e.progress,"runtime_restarted_observe_before_new_work"); needsReconcile=true;
        }
        for(Control c:controls.values()) if("accepted".equals(c.state)) {
            c.state="rejected"; c.reason="runtime_restarted_control_not_replayed"; persistControl(c);
        }
    }
    private void requireHealthy() {
        if(stopped) throw new BridgeFault(503,"bridge_stopped");
        if(!fault.isEmpty()) throw new BridgeFault(503,"bridge_failed_closed:"+fault);
    }
    private boolean fresh() { return observedAt>=0 && mono.getAsLong()-observedAt<=OBSERVATION_MAX_AGE_MS; }
    private void requireReady() {
        requireHealthy(); if(!ready || !fresh()) throw new BridgeFault(503,"body_not_ready_or_server_tick_stale");
    }
    private void expire() {
        if(token!=null && mono.getAsLong()>=expiresAt) {
            token=null; owner=null; pauseRequested=true;
            event("control_lost","",Map.of("reason","lease_expired"));
        }
    }
    private void requireLease(String supplied) {
        requireHealthy(); expire();
        if(token==null || supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BridgeFault(409,"control_lease_invalid");
    }
    private static void identifier(String id) {
        if(id==null || !id.matches("[A-Za-z0-9:._-]{1,160}")) throw new BridgeFault(400,"invalid_identifier");
    }
    private Map<String,Object> currentBindingWire() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("body_id",bodyId); out.put("backend_kind",backendKind);
        out.put("body_instance_id",bodyInstanceId);
        out.put("body_session_epoch",bodySessionEpoch);
        return out;
    }
    private void invalidateReadPlane(String reason) {
        cognitive=null;
        cognitiveTick=-1;
        cognitiveFault=reason+"_rebuild_required";
        while(!localQueries.isEmpty()) {
            LocalQuery query=localQueries.poll();
            if(!query.future().isDone())
                query.future().completeExceptionally(
                        new BridgeFault(409,reason+"_query_cancelled"));
        }
    }
    private void applyBinding(BodyBackend.Binding next) {
        boolean logicalChanged=!bodyId.isEmpty() && !bodyId.equals(next.bodyId());
        boolean legacyBinding=!bodyId.isEmpty() && bodySessionEpoch.isEmpty();
        boolean sessionChanged=!logicalChanged && !bodyId.isEmpty()
                && (legacyBinding || !backendKind.equals(next.backendKind())
                || !bodyInstanceId.equals(next.instanceId())
                || !bodySessionEpoch.equals(next.sessionEpoch()));
        if(logicalChanged || sessionChanged) {
            String reason=logicalChanged?"body_identity_changed":"body_session_changed";
            // Never allow a view or queued EvidenceRef admitted under the old physical session
            // to execute against the replacement body.
            invalidateReadPlane(reason);
            if(active!=null) transition(active,"outcome_unknown",active.progress,reason);
            token=null; owner=null; pauseRequested=true; needsReconcile=true;
            event(logicalChanged?"body_changed":"body_session_changed","",Map.of(
                    "previous",currentBindingWire(),"current",next.wire()));
        }
        boolean changed=!bodyId.equals(next.bodyId())
                || !backendKind.equals(next.backendKind())
                || !bodyInstanceId.equals(next.instanceId())
                || !bodySessionEpoch.equals(next.sessionEpoch());
        if(!changed)return;
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("kind","body_binding"); fields.put("body_id",next.bodyId());
        fields.put("backend_kind",next.backendKind());
        fields.put("body_instance_id",next.instanceId());
        fields.put("body_session_epoch",next.sessionEpoch());
        fields.put("payload",JsonOutput.encode(next.wire()));
        journal.append(fields);
        bodyId=next.bodyId(); backendKind=next.backendKind();
        bodyInstanceId=next.instanceId(); bodySessionEpoch=next.sessionEpoch();
    }
    public synchronized Map<String,Object> claim(String newOwner) { return claim(newOwner,null); }
    public synchronized Map<String,Object> claim(String newOwner,String existingToken) {
        identifier(newOwner); requireReady(); expire();
        if(token!=null && (!Objects.equals(owner,newOwner) || existingToken==null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),existingToken.getBytes(StandardCharsets.UTF_8))))
            throw new BridgeFault(409,"body_controlled_by_another_session_or_process");
        if(token==null) {
            byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
            String newToken=HexFormat.of().formatHex(secret);
            String nextEpoch=runtimeEpoch+":"+(++leaseCounter);
            event("control_acquired","",Map.of("owner",newOwner,"lease_epoch",nextEpoch));
            token=newToken; owner=newOwner; leaseEpoch=nextEpoch;
        }
        expiresAt=mono.getAsLong()+LEASE_MS;
        return Map.of("token",token,"owner",owner,"lease_epoch",leaseEpoch,"expires_in_ms",LEASE_MS,
                "event_epoch",journal.epoch,"event_sequence",journal.lastSequence());
    }
    public synchronized Map<String,Object> renew(String supplied) {
        requireLease(supplied); expiresAt=mono.getAsLong()+LEASE_MS;
        return Map.of("expires_in_ms",LEASE_MS,"lease_epoch",leaseEpoch);
    }
    public synchronized Map<String,Object> release(String supplied) {
        requireLease(supplied);
        event("control_lost","",Map.of("reason","released"));
        token=null; owner=null; pauseRequested=true;
        return Map.of("released",true,"body_policy","pause_normal_work_keep_safety_no_internal_fallback");
    }
    public synchronized Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("protocol_version",1); out.put("runtime_epoch",runtimeEpoch); out.put("event_epoch",journal.epoch);
        out.put("event_sequence",journal.lastSequence()); out.put("body_id",bodyId);
        out.put("backend_kind",backendKind); out.put("body_instance_id",bodyInstanceId);
        out.put("body_session_epoch",bodySessionEpoch);
        out.put("body_ready",ready && fresh()); out.put("needs_reconcile",needsReconcile);
        out.put("control_active",token!=null && mono.getAsLong()<expiresAt);
        out.put("fault",fault); out.put("stopped",stopped);
        out.put("active_execution",active==null?null:active.wire());
        out.put("operations",backend.supportedOperations().stream().sorted().toList());
        return out;
    }
    public synchronized Map<String,Object> observe() {
        requireReady(); needsReconcile=false;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("runtime_epoch",runtimeEpoch); out.put("body_id",bodyId);
        out.put("backend_kind",backendKind); out.put("body_instance_id",bodyInstanceId);
        out.put("body_session_epoch",bodySessionEpoch);
        out.put("observation",new JsonOutput.Raw(observation));
        out.put("snapshot_age_ms",Math.max(0,mono.getAsLong()-observedAt));
        out.put("execution",active==null?Map.of():active.wire());
        return out;
    }
    // ---- MC-2A0 read-only cognitive queries ----
    // 语义与 observe 的关键差异:只读新鲜度要求,不清 needsReconcile、不要求/消耗 lease、
    // 不产生 execution receipt、不被 body-busy 拒绝、绝不 pause/cancel/replace 执行所有权。

    public synchronized Map<String,Object> view() {
        requireReady();
        if(cognitive==null) throw new BridgeFault(503,"cognitive_view_unavailable:"+cognitiveFault);
        byte[] sceneBytes=cognitive.sceneJson().getBytes(StandardCharsets.UTF_8);
        if(sceneBytes.length>CognitiveSnapshot.VIEW_MAX_BYTES) throw new BridgeFault(500,"cognitive_view_exceeds_hard_limit");
        Map<String,Object> meta=new LinkedHashMap<>();
        meta.put("generated_server_tick",lastServerTick);
        meta.put("generated_game_time",cognitive.gameTime());
        meta.put("scene_hash","sha256:"+cognitive.sceneHash());
        meta.put("encoded_bytes",(long)sceneBytes.length);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema","mc.cognitive_view.v0");
        out.put("meta",meta);
        out.put("scene",new JsonOutput.Raw(cognitive.sceneJson()));
        return out;
    }
    /**
     * MC-2A0.1 inspect 异步化:fail-fast 校验(ref 解析/descriptor 命中/detail 白名单)同步完成,
     * materialize 本体进 server 线程查询队列(LAZY-3),HTTP 线程无锁等待 future。
     * 语义不变:不清 needsReconcile、不占 execution slot、busy-safe、read-only。
     */
    public synchronized CompletableFuture<String> submitInspectQuery(String ref,String detail) {
        requireReady();
        if(ref==null || ref.isBlank()) throw new BridgeFault(400,"invalid_evidence_ref");
        io.github.zoyluo.aibot.external.cognition.EvidenceRef.Parsed parsed= // malformed 一律 400 fail-closed
                io.github.zoyluo.aibot.external.cognition.EvidenceRef.parse(ref);
        if(cognitive==null) throw new BridgeFault(503,"cognitive_view_unavailable:"+cognitiveFault);
        CognitiveSnapshot.EvidenceDescriptor descriptor=cognitive.inspectIndex().get(ref);
        // foreign world / dimension policy / kind mismatch / unknown object 统一 fail-closed
        if(descriptor==null) throw new BridgeFault(404,"evidence_ref_not_in_current_view:foreign_or_unknown_or_kind_mismatch");
        String level=detail==null||detail.isBlank()?"summary":detail;
        java.util.Set<String> allowed=CognitiveSnapshot.INSPECT_DETAILS.get(descriptor.kind());
        if(allowed==null || !allowed.contains(level)) throw new BridgeFault(400,"unsupported_detail_level:"+level);
        if(localQueries.size()>=MAX_QUEUED_QUERIES) throw new BridgeFault(429,"too_many_local_queries");
        CompletableFuture<String> future=new CompletableFuture<>();
        localQueries.add(LocalQuery.inspect(ref,level,future,mono.getAsLong()+QUERY_DEADLINE_MS));
        return future;
    }
    /** 当前认知快照的构建时刻 game time(materialize freshness 与 view 卡同源,FRESH-1)。 */
    public synchronized long cognitiveGameTime() {
        return cognitive==null ? -1L : cognitive.gameTime();
    }
    /**
     * 校验并入队 server 线程局部查询,立即返回 future。
     * HTTP 线程必须无锁等待:kernel 全方法 synchronized,持锁等待 tick() 会死锁。
     */
    public synchronized CompletableFuture<String> submitLocalQuery(int radius,String detail) {
        requireReady();
        if(radius<1 || radius>16) throw new BridgeFault(400,"radius_out_of_range_1_16");
        String level=detail==null||detail.isBlank()?"summary":detail;
        if(!CognitiveSnapshot.LOCAL_DETAILS.contains(level)) throw new BridgeFault(400,"invalid_detail");
        if(localQueries.size()>=MAX_QUEUED_QUERIES) throw new BridgeFault(429,"too_many_local_queries");
        CompletableFuture<String> future=new CompletableFuture<>();
        localQueries.add(LocalQuery.local(radius,level,future,mono.getAsLong()+QUERY_DEADLINE_MS));
        return future;
    }
    public synchronized Map<String,Object> execution(String id) {
        Execution e=byId.get(id); if(e==null) throw new BridgeFault(404,"execution_not_found"); return e.wire();
    }
    public synchronized Map<String,Object> requestStatus(String id) {
        Execution e=byRequest.get(id); if(e!=null) return e.wire();
        Control c=controls.get(id); if(c!=null) return c.wire();
        throw new BridgeFault(404,"request_not_found");
    }
    // ---- MC-2A Graph Core v0: explicit plan -> durable fragment -> explicit dispatch ----
    public synchronized Map<String,Object> graphList() {
        requireHealthy(); return graphs.list();
    }
    public synchronized Map<String,Object> graphInspect(String graphId) {
        requireHealthy(); return graphs.inspect(graphId);
    }
    public synchronized Map<String,Object> graphPlanOpportunity(String supplied,String planKey,String evidenceRef) {
        requireLease(supplied); requireReady(); identifier(planKey);
        if(evidenceRef==null || evidenceRef.isBlank()) throw new BridgeFault(400,"invalid_evidence_ref");
        EvidenceRef.Parsed parsed=EvidenceRef.parse(evidenceRef);
        if(!"opportunity".equals(parsed.kind())) throw new BridgeFault(400,"graph_plan_requires_opportunity_ref");
        if(cognitive==null) throw new BridgeFault(503,"cognitive_view_unavailable:"+cognitiveFault);
        CognitiveSnapshot.EvidenceDescriptor descriptor=cognitive.inspectIndex().get(evidenceRef);
        if(descriptor==null) throw new BridgeFault(404,"evidence_ref_not_in_current_view");
        return graphs.planOpportunity(planKey,new TaskGraphStore.SpatialRef(
                parsed.worldId(),parsed.dimension(),parsed.objectId()));
    }
    public synchronized Map<String,Object> graphRunNext(String supplied,String callerRequest,String graphId) {
        requireLease(supplied); identifier(callerRequest); requireReady();
        if(needsReconcile) throw new BridgeFault(409,"observe_required_before_new_work");
        Optional<String> already=graphs.runningExecution(graphId);
        if(already.isPresent()) return Map.of("graph",graphs.inspect(graphId),"execution",execution(already.get()));
        if(active!=null) throw new BridgeFault(409,"execution_in_progress");
        TaskGraphStore.Dispatch d=graphs.prepareDispatch(graphId);
        try {
            Map<String,Object> execution=submit(supplied,d.requestId(),d.operation(),d.arguments());
            String executionId=String.valueOf(execution.get("execution_id"));
            graphs.attachExecution(d,executionId);
            return Map.of("graph",graphs.inspect(graphId),"execution",execution);
        } catch(RuntimeException failure) {
            graphs.rejectPreparedDispatch(d,failure instanceof BridgeFault f?f.code:failure.getClass().getSimpleName());
            throw failure;
        }
    }
    public synchronized Map<String,Object> graphCancel(String supplied,String graphId,String reason) {
        requireLease(supplied);
        Optional<String> running=graphs.runningExecution(graphId);
        if(running.isPresent()) {
            Execution e=byId.get(running.get());
            if(e!=null && !TERMINAL.contains(e.state))
                throw new BridgeFault(409,"graph_has_running_execution_cancel_execution_first");
        }
        return graphs.cancel(graphId,reason==null?"external_replan":reason);
    }
    /**
     * Durable semantic terminal receipt.  Unlike publish(), callers need a success bit because a
     * proven inventory gain must not become a physical Task success until the receipt itself has
     * crossed BridgeJournal's fsync boundary.
     */
    public synchronized boolean recordOpportunityResolution(String kind,String opportunityId,
                                                            String worldId,String dimension,
                                                            Map<String,Object> payload) {
        if(!Set.of("resource_opportunity_consumed","resource_opportunity_stale").contains(kind)) return false;
        if(stopped || !fault.isEmpty()) return false;
        try {
            Map<String,String> fields=new LinkedHashMap<>();
            fields.put("kind",kind);
            fields.put("execution_id",active==null?"":active.id);
            fields.put("opportunity_id",opportunityId);
            fields.put("world_id",worldId);
            fields.put("dimension",dimension);
            fields.put("payload",JsonOutput.encode(payload));
            journal.append(fields); // append() fsyncs before returning
            return true;
        } catch(RuntimeException failure) {
            fault="event_persistence_failed";
            pauseRequested=true;
            token=null;
            return false;
        }
    }
    static Optional<BodyBackend.GraphPostconditionResult> durableOpportunityResolution(
            BridgeJournal journal,TaskGraphStore.SpatialRef ref) {
        List<BridgeJournal.Frame> frames=journal.replay();
        for(int i=frames.size()-1;i>=0;i--) {
            Map<String,String> f=frames.get(i).fields();
            String kind=f.get("kind");
            if(!Set.of("resource_opportunity_consumed","resource_opportunity_stale").contains(kind)) continue;
            if(!ref.objectId().equals(f.get("opportunity_id"))
                    || !ref.worldId().equals(f.get("world_id"))
                    || !ref.dimensionId().equals(f.get("dimension"))) continue;
            return Optional.of("resource_opportunity_consumed".equals(kind)
                    ? BodyBackend.GraphPostconditionResult.satisfied("durable_inventory_gain_receipt")
                    : BodyBackend.GraphPostconditionResult.terminalUnsatisfied("durable_stale_or_loss_receipt"));
        }
        return Optional.empty();
    }
    private BodyBackend.GraphPostconditionResult verifyGraphPostcondition(TaskGraphStore.Postcondition pc) {
        if("OPPORTUNITY_RESOLVED".equals(pc.kind())) {
            Optional<BodyBackend.GraphPostconditionResult> durable=durableOpportunityResolution(journal,pc.subject());
            if(durable.isPresent()) return durable.get();
        }
        return backend.verifyGraphPostcondition(pc);
    }
    private static String fingerprint(String type,String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((type+"\n"+payload).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public synchronized Map<String,Object> submit(String supplied,String request,String operation,String arguments) {
        requireLease(supplied); identifier(request);
        if(!OPERATIONS.contains(operation)) throw new BridgeFault(400,"unsupported_operation");
        if(arguments==null || arguments.getBytes(StandardCharsets.UTF_8).length>16384) throw new BridgeFault(413,"arguments_too_large");
        String fp=fingerprint(operation,arguments);
        Execution previous=byRequest.get(request);
        if(previous!=null) {
            if(!previous.fingerprint.equals(fp) || !previous.owner.equals(owner) || !previous.bodyId.equals(bodyId)) throw new BridgeFault(409,"idempotency_conflict");
            return previous.wire();
        }
        if(controls.containsKey(request)) throw new BridgeFault(409,"idempotency_conflict");
        if(!backend.supportedOperations().contains(operation))
            throw new BridgeFault(409,"operation_not_supported_by_backend:"+operation);
        requireReady();
        if(needsReconcile) throw new BridgeFault(409,"observe_required_before_new_work");
        if(active!=null) throw new BridgeFault(409,"execution_in_progress");
        if(byId.size()>=MAX_EXECUTIONS) {
            // 长会话游玩:终态执行的历史已持久在 journal,内存索引淘汰最老的终态条目。
            // 全部非终态(不可能:单执行槽)才真正耗尽。
            java.util.Map.Entry<String,Execution> oldest=null;
            for(java.util.Map.Entry<String,Execution> entry:byId.entrySet()) {
                if(TERMINAL.contains(entry.getValue().state)
                        &&(oldest==null||entry.getValue().id.compareTo(oldest.getValue().id)<0))
                    oldest=entry;
            }
            if(oldest==null)
                throw new BridgeFault(503,"execution_ledger_capacity_exhausted");
            byRequest.remove(oldest.getValue().request);
            byId.remove(oldest.getKey());
        }
        Execution e=new Execution(); e.id=UUID.randomUUID().toString(); e.request=request; e.owner=owner;
        e.operation=operation; e.arguments=arguments; e.fingerprint=fp;
        e.admittingLeaseEpoch=leaseEpoch; e.bodyId=bodyId; e.backendKind=backendKind;
        e.bodyInstanceId=bodyInstanceId; e.bodySessionEpoch=bodySessionEpoch;
        persistExecution(e,true); // durable acceptance before any world mutation
        byId.put(e.id,e); byRequest.put(request,e); active=e;
        return e.wire();
    }
    public synchronized Map<String,Object> control(String supplied,String request,String executionId,String action) {
        requireLease(supplied); identifier(request);
        if(!Set.of("pause","resume","cancel").contains(action)) throw new BridgeFault(400,"invalid_control");
        String fp=fingerprint(action,executionId);
        Control prior=controls.get(request);
        if(prior!=null) { if(!prior.fingerprint.equals(fp)) throw new BridgeFault(409,"idempotency_conflict"); return prior.wire(); }
        if(byRequest.containsKey(request)) throw new BridgeFault(409,"idempotency_conflict");
        Execution e=byId.get(executionId); if(e==null) throw new BridgeFault(404,"execution_not_found");
        if(TERMINAL.contains(e.state)) return Map.of("state","already_terminal","execution",e.wire());
        if(active!=e) throw new BridgeFault(409,"execution_not_current");
        if(controls.size()>=MAX_CONTROLS || controlQueue.size()>=32) throw new BridgeFault(429,"control_queue_full");
        Control c=new Control(); c.request=request; c.executionId=executionId; c.action=action; c.fingerprint=fp; c.leaseToken=token;
        persistControl(c); controls.put(request,c); controlQueue.add(c); return c.wire();
    }
    private void persistControl(Control c) {
        journal.append(Map.of("kind","control","request_id",c.request,"fingerprint",c.fingerprint,
                "execution_id",c.executionId,"action",c.action,"state",c.state,"reason",c.reason));
    }
    private void persistExecution(Execution e,boolean first) {
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("kind","execution"); fields.put("execution_id",e.id); fields.put("state",e.state);
        fields.put("reason",bound(e.reason,2048)); fields.put("progress",Double.toString(e.progress));
        if(first) {
            fields.put("request_id",e.request); fields.put("fingerprint",e.fingerprint); fields.put("owner",e.owner);
            fields.put("operation",e.operation); fields.put("arguments",e.arguments);
            fields.put("body_id",e.bodyId); fields.put("backend_kind",e.backendKind);
            fields.put("body_instance_id",e.bodyInstanceId);
            fields.put("body_session_epoch",e.bodySessionEpoch);
        }
        fields.put("payload",JsonOutput.encode(e.wire())); journal.append(fields);
    }
    private void transition(Execution e,String state,double progress,String reason) {
        if(TERMINAL.contains(e.state)) return;
        String oldState=e.state, oldReason=e.reason; double oldProgress=e.progress;
        e.state=state; e.progress=progress; e.reason=bound(reason,2048);
        try { persistExecution(e,false); }
        catch(RuntimeException ex) { e.state=oldState; e.progress=oldProgress; e.reason=oldReason; throw ex; }
        if(TERMINAL.contains(state) && active==e) active=null;
        if(TERMINAL.contains(state))
            graphs.executionTerminal(e.id,state,this::verifyGraphPostcondition);
    }
    private void event(String kind,String executionId,Map<String,Object> payload) {
        journal.append(Map.of("kind",kind,"execution_id",executionId,"payload",JsonOutput.encode(payload)));
    }
    private static String bound(String s,int max) { if(s==null)return ""; return s.length()<=max?s:s.substring(0,max); }

    /** Called after existing TaskManager and safety ticks, never by HTTP worker threads. */
    public synchronized void tick() {
        if(stopped) return;
        if(!fault.isEmpty()) { try { backend.pause(); } catch(RuntimeException ignored) {} return; }
        try {
            expire();
            boolean nowReady=backend.ready();
            if(nowReady)applyBinding(backend.binding());
            if(!nowReady) {
                boolean wasReady=ready;
                ready=false;
                if(wasReady) {
                    // Physical carrier loss is the same authority boundary as an in-process
                    // replacement. Never retain old visual evidence, leases, or mutation intent.
                    invalidateReadPlane("body_session_changed");
                    if(active!=null)
                        transition(active,"outcome_unknown",active.progress,"body_session_changed");
                    token=null; owner=null; pauseRequested=true; needsReconcile=true;
                    event("body_session_changed","",Map.of(
                            "reason","physical_body_unavailable",
                            "previous",currentBindingWire()));
                }
                return;
            }
            ready=true; observation=backend.observeJson(); observedAt=mono.getAsLong();
            lastServerTick=backend.serverTick();
            // One-time migration audit: Graph Core v0 used registry absence as success proof.
            // Revalidate old DONE nodes against durable receipts before trusting them.
            if(!graphStartupDoneAudit) {
                graphs.auditCompletedPostconditions(this::verifyGraphPostcondition);
                graphStartupDoneAudit=true;
            }
            // Reconcile-only: may prove DONE or a terminal loss; never re-dispatches mutation.
            graphs.reconcileSuspended(this::verifyGraphPostcondition);
            // 认知快照独立刷新:构建失败只令 view/inspect 503,绝不拖垮 observe/execution 主链。
            if(cognitive==null || lastServerTick-cognitiveTick>=5 || lastServerTick<cognitiveTick) {
                try { cognitive=backend.cognitiveSnapshot(journal); cognitiveTick=lastServerTick; cognitiveFault=""; }
                catch(RuntimeException viewFailure) {
                    cognitive=null;
                    cognitiveFault=viewFailure instanceof BridgeFault f ? f.code : viewFailure.getClass().getSimpleName();
                }
            }
            if(pauseRequested) { backend.pause(); pauseRequested=false; }
            while(!controlQueue.isEmpty()) applyControl(controlQueue.remove());
            // MC-2A0.1 认知查询调度(PERF-1/PERF-2):deadline 已过的排队查询先 fail 回收——
            // 客户端早已超时放弃,server 绝不继续积压执行;随后每 tick 至多执行一个
            // expensive 查询(materialize 或 inspect-local),剩余留队,绝不清空 burst。
            long now=mono.getAsLong();
            while(!localQueries.isEmpty() && localQueries.peek().deadline()<=now) {
                LocalQuery expired=localQueries.poll();
                if(!expired.future().isDone())
                    expired.future().completeExceptionally(new BridgeFault(503,"query_deadline_expired"));
            }
            if(!localQueries.isEmpty()) {
                LocalQuery query=localQueries.poll();
                try {
                    String json="inspect".equals(query.kind())
                            ? backend.materializeEvidence(query.ref(),query.detail(),cognitiveGameTime())
                            : backend.inspectLocalJson(query.radius(),query.detail());
                    query.future().complete(json);
                } catch(RuntimeException queryFailure) { query.future().completeExceptionally(queryFailure); }
            }
            Execution e=active;
            if(e==null) return;
            if(e.handle==null) {
                if(token==null || !Objects.equals(e.owner,owner) || !Objects.equals(e.admittingLeaseEpoch,leaseEpoch)) { transition(e,"cancelled",0,"lease_lost_before_dispatch"); return; }
                if(e.state.equals("paused")) return;
                try { e.handle=backend.start(e.id,e.operation,e.arguments); }
                catch(BridgeFault validationFailure) { transition(e,"failed",0,validationFailure.code); return; }
                catch(RuntimeException startFailure) {
                    try { backend.cancel("start_failure"); } catch(RuntimeException cleanup) { startFailure.addSuppressed(cleanup); }
                    transition(e,"outcome_unknown",0,"backend_start_exception:"+startFailure.getClass().getSimpleName()); needsReconcile=true; return;
                }
            }
            BodyBackend.Snapshot snap=e.handle.snapshot();
            if(!e.state.equals(snap.state()) || (TERMINAL.contains(snap.state()) && !TERMINAL.contains(e.state))) {
                transition(e,snap.state(),snap.progress(),snap.reason());
                if("outcome_unknown".equals(snap.state())) needsReconcile=true;
            } else { e.progress=snap.progress(); e.reason=bound(snap.reason(),2048); } // progress is polled, not journal spam
        } catch(RuntimeException failure) {
            fault=failure instanceof BridgeFault f?f.code:failure.getClass().getSimpleName();
            token=null; owner=null; needsReconcile=true;
            try { backend.pause(); } catch(RuntimeException ignored) {}
        }
    }
    private void applyControl(Control c) {
        try {
            requireLease(c.leaseToken);
            Execution e=byId.get(c.executionId);
            if(e==null || e!=active || TERMINAL.contains(e.state)) { c.state="applied"; c.reason="already_terminal_or_not_current"; persistControl(c); return; }
            switch(c.action) {
                case "pause" -> {
                    if(e.handle==null) { // accepted but not dispatched: don't accidentally start paused work
                        transition(e,"paused",0,"paused_before_dispatch");
                    } else backend.pause();
                }
                case "resume" -> {
                    if(e.handle==null) transition(e,"accepted",0,"resumed_before_dispatch");
                    else backend.resume();
                }
                case "cancel" -> { if(e.handle!=null)backend.cancel("external_cancel"); transition(e,"cancelled",e.progress,"external_cancel"); }
                default -> throw new BridgeFault(400,"invalid_control");
            }
            c.state="applied"; c.reason="";
        } catch(BridgeFault f) { c.state="rejected"; c.reason=f.code; }
        catch(RuntimeException ex) {
            c.state="outcome_unknown"; c.reason="backend_control_exception"; needsReconcile=true;
            try { backend.pause(); } catch(RuntimeException ignored) {}
            if(active!=null) transition(active,"outcome_unknown",active.progress,"control_failed_reconcile_required");
        }
        persistControl(c);
    }
    public synchronized void publish(String kind,Map<String,Object> payload) {
        if(stopped || !fault.isEmpty()) return;
        try {
            event(kind,active==null?"":active.id,payload);
            if("death".equals(kind)) {
                if(active!=null) transition(active,"failed",active.progress,"body_died");
                needsReconcile=true; ready=false;
            }
        } catch(RuntimeException ex) { fault="event_persistence_failed"; pauseRequested=true; token=null; }
    }
    public synchronized void shutdown() {
        if(stopped) return;
        try {
            backend.pause();
            if(active!=null) {
                Execution e=active;
                transition(e,"outcome_unknown",e.progress,"server_stopping_no_automatic_replay");
                backend.cancel("external_runtime_unload");
            }
            event("runtime_stopped","",Map.of("runtime_epoch",runtimeEpoch));
        } finally { stopped=true; ready=false; token=null; owner=null; }
    }
    public BridgeJournal journal() { return journal; }
}
