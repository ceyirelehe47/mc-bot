package io.github.zoyluo.aibot.external;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Protocol-independent, single-body coordinator. HTTP threads may only admit work/read caches. */
public final class BridgeKernel {
    public static final Set<String> OPERATIONS=Set.of("goto","gather","craft","smelt","eat","set_base","deposit","say");
    private static final Set<String> TERMINAL=Set.of("completed","failed","cancelled","outcome_unknown");
    private static final int MAX_EXECUTIONS=1024, MAX_CONTROLS=2048;
    private static final long LEASE_MS=30000, OBSERVATION_MAX_AGE_MS=5000;
    private final BridgeJournal journal;
    private final BodyBackend backend;
    private final LongSupplier mono;
    private final Map<String,Execution> byId=new LinkedHashMap<>();
    private final Map<String,Execution> byRequest=new HashMap<>();
    private final Map<String,Control> controls=new LinkedHashMap<>();
    private final Deque<Control> controlQueue=new ArrayDeque<>();
    public final String runtimeEpoch=UUID.randomUUID().toString();
    private String token, owner, leaseEpoch;
    private long expiresAt, leaseCounter;
    private String bodyId="", observation="{}", fault="";
    private long observedAt=-1;
    private boolean ready, needsReconcile, pauseRequested, stopped;
    private Execution active;

    private static final class Execution {
        String id, request, fingerprint, owner, operation, arguments, admittingLeaseEpoch, bodyId, state="accepted", reason="";
        double progress;
        BodyBackend.Handle handle;
        Map<String,Object> wire() {
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("execution_id",id); out.put("request_id",request); out.put("body_id",bodyId); out.put("operation",operation);
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
        this(journal,backend,()->System.nanoTime()/1_000_000L);
    }
    public BridgeKernel(BridgeJournal journal,BodyBackend backend,LongSupplier monotonicMillis) {
        this.journal=journal; this.backend=backend; this.mono=monotonicMillis;
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
                    e.operation=m.get("operation"); e.arguments=m.get("arguments"); e.bodyId=m.getOrDefault("body_id", "");
                    byId.put(e.id,e); byRequest.put(e.request,e);
                }
                e.state=m.get("state"); e.reason=m.getOrDefault("reason","");
                e.progress=Double.parseDouble(m.getOrDefault("progress","0"));
            } else if("body_binding".equals(m.get("kind"))) {
                bodyId=m.getOrDefault("body_id", "");
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
        out.put("body_ready",ready && fresh()); out.put("needs_reconcile",needsReconcile);
        out.put("control_active",token!=null && mono.getAsLong()<expiresAt);
        out.put("fault",fault); out.put("stopped",stopped);
        out.put("active_execution",active==null?null:active.wire());
        out.put("operations",OPERATIONS.stream().sorted().toList());
        return out;
    }
    public synchronized Map<String,Object> observe() {
        requireReady(); needsReconcile=false;
        return Map.of("runtime_epoch",runtimeEpoch,"body_id",bodyId,"observation",new JsonOutput.Raw(observation),
                "snapshot_age_ms",Math.max(0,mono.getAsLong()-observedAt),"execution",active==null?Map.of():active.wire());
    }
    public synchronized Map<String,Object> execution(String id) {
        Execution e=byId.get(id); if(e==null) throw new BridgeFault(404,"execution_not_found"); return e.wire();
    }
    public synchronized Map<String,Object> requestStatus(String id) {
        Execution e=byRequest.get(id); if(e!=null) return e.wire();
        Control c=controls.get(id); if(c!=null) return c.wire();
        throw new BridgeFault(404,"request_not_found");
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
        requireReady();
        if(needsReconcile) throw new BridgeFault(409,"observe_required_before_new_work");
        if(active!=null) throw new BridgeFault(409,"execution_in_progress");
        if(byId.size()>=MAX_EXECUTIONS) throw new BridgeFault(503,"execution_ledger_capacity_exhausted");
        Execution e=new Execution(); e.id=UUID.randomUUID().toString(); e.request=request; e.owner=owner;
        e.operation=operation; e.arguments=arguments; e.fingerprint=fp; e.admittingLeaseEpoch=leaseEpoch; e.bodyId=bodyId;
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
            fields.put("operation",e.operation); fields.put("arguments",e.arguments); fields.put("body_id",e.bodyId);
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
            String id=backend.bodyId();
            if(!bodyId.isEmpty() && !id.isEmpty() && !bodyId.equals(id)) {
                if(active!=null) transition(active,"outcome_unknown",active.progress,"body_identity_changed");
                token=null; owner=null; pauseRequested=true; needsReconcile=true;
                event("body_changed","",Map.of("previous",bodyId,"current",id));
            }
            if(!id.isEmpty() && !bodyId.equals(id)) {
                journal.append(Map.of("kind","body_binding","body_id",id,"payload",JsonOutput.encode(Map.of("body_id",id))));
                bodyId=id;
            }
            if(!nowReady) {
                ready=false;
                if(active!=null) { transition(active,"outcome_unknown",active.progress,"body_unavailable"); needsReconcile=true; }
                return;
            }
            ready=true; observation=backend.observeJson(); observedAt=mono.getAsLong();
            if(pauseRequested) { backend.pause(); pauseRequested=false; }
            while(!controlQueue.isEmpty()) applyControl(controlQueue.remove());
            Execution e=active;
            if(e==null) return;
            if(e.handle==null) {
                if(token==null || !Objects.equals(e.owner,owner) || !Objects.equals(e.admittingLeaseEpoch,leaseEpoch)) { transition(e,"cancelled",0,"lease_lost_before_dispatch"); return; }
                if(e.state.equals("paused")) return;
                try { e.handle=backend.start(e.operation,e.arguments); }
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
