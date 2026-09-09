package io.github.zoyluo.aibot.external;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free executable regression suite, intentionally separate from Minecraft GameTests. */
public final class BridgeCoreTest {
    static int passed;
    static final class FakeBackend implements BodyBackend {
        boolean alive=true; String id="body-1"; String state="running"; int starts,pauses,resumes,cancels; boolean failStart;
        public boolean ready(){return alive;} public String bodyId(){return id;}
        public String observeJson(){return "{\"health\":20,\"inventory\":{}}";}
        public Handle start(String op,String args){ starts++; if(failStart)throw new IllegalStateException(); state="running"; return ()->new Snapshot(state,.5,""); }
        public void pause(){pauses++;if(state.equals("running"))state="paused";}
        public void resume(){resumes++;state="running";}
        public void cancel(String reason){cancels++;state="cancelled";}
    }
    record Fixture(Path file,BridgeJournal journal,FakeBackend body,BridgeKernel kernel,AtomicLong time) implements AutoCloseable {
        public void close()throws Exception{journal.close();}
    }
    static Fixture fixture()throws Exception {
        Path file=Files.createTempDirectory("aibot-core-").resolve("test.journal");
        AtomicLong time=new AtomicLong(1000); FakeBackend body=new FakeBackend();
        BridgeJournal j=new BridgeJournal(file,time::get); BridgeKernel k=new BridgeKernel(j,body,time::get); k.tick();
        return new Fixture(file,j,body,k,time);
    }
    static String claim(Fixture f){return (String)f.kernel.claim("test-session").get("token");}
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static void fault(int status,String code,Runnable fn){try{fn.run();throw new AssertionError("expected "+code);}catch(BridgeFault e){check(e.status==status && e.code.startsWith(code),code);}}
    public static void main(String[] args)throws Exception {
        check(JsonOutput.encode(Map.of("x","\"\\\n中文😀")).equals("{\"x\":\"\\\"\\\\\\n中文\\ud83d\\ude00\"}"),"JSON escaping");
        try(Fixture f=fixture()) {
            String t=claim(f);
            check(f.kernel.status().get("control_active").equals(true),"lease active");
            fault(409,"body_controlled",()->f.kernel.claim("test-session"));
            check(f.kernel.claim("test-session",t).get("token").equals(t),"only existing token holder may repeat claim for same owner");
            fault(409,"body_controlled",()->f.kernel.claim("other-session"));
            fault(409,"control_lease_invalid",()->f.kernel.submit("wrong","r1","gather","{}"));
            var r=f.kernel.submit(t,"r1","gather","{}"); String id=(String)r.get("execution_id");
            check(f.body.starts==0,"HTTP admission cannot run world action");
            check(r.get("state").equals("accepted"),"accepted distinct from completed");
            check(f.kernel.submit(t,"r1","gather","{}").get("execution_id").equals(id),"idempotent duplicate returns original receipt");
            fault(409,"idempotency_conflict",()->f.kernel.submit(t,"r1","gather","{\"count\":2}"));
            fault(409,"execution_in_progress",()->f.kernel.submit(t,"r2","gather","{}"));
            f.kernel.tick(); check(f.body.starts==1,"one physical dispatch");
            check(f.kernel.execution(id).get("state").equals("running"),"running state");
            f.kernel.control(t,"pause-1",id,"pause"); check(f.body.pauses==0,"controls are queued not run in HTTP thread");
            f.kernel.tick();check(f.kernel.execution(id).get("state").equals("paused"),"pause preserves execution");
            f.kernel.control(t,"resume-1",id,"resume");f.kernel.tick();check(f.body.resumes==1,"resume existing task");
            f.body.state="completed"; f.kernel.tick();check(f.kernel.execution(id).get("state").equals("completed"),"terminal completion");
            f.body.state="failed";f.kernel.tick();check(f.kernel.execution(id).get("state").equals("completed"),"terminal state monotonic");
            check(f.kernel.submit(t,"r1","gather","{}").get("state").equals("completed"),"retry after completion does not repeat world action");
            check(f.body.starts==1,"completed retry no re-execution");
            var r2=f.kernel.submit(t,"r2","gather","{}");f.kernel.tick();
            f.kernel.control(t,"old-cancel",id,"cancel");f.kernel.tick();
            check(f.kernel.execution((String)r2.get("execution_id")).get("state").equals("running"),"old cancel cannot cancel replacement execution");
            f.kernel.release(t); f.kernel.tick();check(f.body.state.equals("paused"),"release pauses body without enabling internal brain");
            fault(409,"control_lease_invalid",()->f.kernel.renew(t));
        }
        try(Fixture f=fixture()) {
            String t=claim(f);var e=f.kernel.submit(t,"expire-before-start","gather","{}");
            f.time.addAndGet(31000);f.kernel.tick();
            check(f.body.starts==0,"expired queued command never runs");
            check(f.kernel.execution((String)e.get("execution_id")).get("state").equals("cancelled"),"expired accepted receipt settles cancelled");
        }
        try(Fixture f=fixture()) {
            String t=claim(f); var e=f.kernel.submit(t,"c1","gather","{}");f.kernel.tick();
            f.time.addAndGet(31000);f.kernel.tick();check(f.body.state.equals("paused"),"lease expiry pauses normal work");
            fault(409,"control_lease_invalid",()->f.kernel.control(t,"p","resume","resume"));
            f.kernel.claim("new-session");check(f.body.starts==1,"new controller does not automatically restart work");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);var e=f.kernel.submit(t,"dies","gather","{}");f.kernel.tick();
            f.kernel.publish("death",Map.of("cause","explosion"));
            check(f.kernel.execution((String)e.get("execution_id")).get("state").equals("failed"),"death attributed to current execution");
            f.kernel.tick();fault(409,"observe_required",()->f.kernel.submit(t,"after-death","gather","{}"));
            f.kernel.observe();f.kernel.submit(t,"after-death","gather","{}");check(true,"fresh observe enables deliberate replan after death");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);f.body.failStart=true;var e=f.kernel.submit(t,"start-fails","gather","{}");f.kernel.tick();
            check(f.kernel.execution((String)e.get("execution_id")).get("state").equals("outcome_unknown"),"start exception not silently retried");
            check(f.body.cancels==1,"partial start cleanup attempted");
            fault(409,"observe_required",()->f.kernel.submit(t,"next","gather","{}"));
        }
        try(Fixture f=fixture()) {
            String t=claim(f);String id=(String)f.kernel.submit(t,"early","gather","{}").get("execution_id");
            f.kernel.control(t,"early-pause",id,"pause");f.kernel.tick();
            check(f.body.starts==0 && f.kernel.execution(id).get("state").equals("paused"),"pause before dispatch never starts or cancels work");
            f.kernel.control(t,"early-resume",id,"resume");f.kernel.tick();
            check(f.body.starts==1 && f.kernel.execution(id).get("state").equals("running"),"resume after early pause dispatches once");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);String id=(String)f.kernel.submit(t,"same-owner-old-epoch","gather","{}").get("execution_id");
            // A same-owner reconnect must still establish a NEW fenced lease epoch.
            f.kernel.release(t);f.kernel.claim("test-session");f.kernel.tick();
            check(f.body.starts==0 && f.kernel.execution(id).get("state").equals("cancelled"),"same owner reacquire does not revive old-lease accepted command");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);String id=(String)f.kernel.submit(t,"shutdown","gather","{}").get("execution_id");f.kernel.tick();f.kernel.shutdown();
            check(f.kernel.execution(id).get("state").equals("outcome_unknown"),"shutdown records unknown before unloading external work");
            check(f.body.pauses==1 && f.body.cancels==1,"shutdown pauses and cancels external work before upstream snapshot");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);String id=(String)f.kernel.submit(t,"changed-body","gather","{}").get("execution_id");f.kernel.tick();
            f.body.id="replacement-body";f.kernel.tick();
            check(f.kernel.execution(id).get("state").equals("outcome_unknown"),"body identity change interrupts execution as unknown");
            check(f.kernel.status().get("control_active").equals(false),"body identity change revokes controller");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);String id=(String)f.kernel.submit(t,"stale-control","gather","{}").get("execution_id");f.kernel.tick();
            f.kernel.control(t,"stale-resume",id,"resume");f.kernel.release(t);f.kernel.claim("test-session");f.kernel.tick();
            check(f.kernel.requestStatus("stale-resume").get("state").equals("rejected"),"queued old-token control rejected after same owner reacquires");
            check(f.body.state.equals("paused"),"rejected stale resume leaves physical task paused");
        }
        try(Fixture f=fixture()) {
            String t=claim(f);var workers=Executors.newFixedThreadPool(8);List<Future<Map<String,Object>>> responses=new ArrayList<>();
            for(int i=0;i<24;i++)responses.add(workers.submit(()->f.kernel.submit(t,"parallel-id","gather","{}")));
            Set<Object> ids=new HashSet<>();for(var response:responses)ids.add(response.get().get("execution_id"));workers.shutdownNow();
            check(ids.size()==1 && f.body.starts==0,"concurrent admission converges to one durable receipt");
            f.kernel.tick();check(f.body.starts==1,"concurrent duplicate admission dispatches exactly one action");
        }
        Path restartFile;String restartId;
        try(Fixture f=fixture()){
            restartFile=f.file; String t=claim(f); restartId=(String)f.kernel.submit(t,"restart-key","gather","{}").get("execution_id");f.kernel.tick();
        }
        AtomicLong time=new AtomicLong(1);
        try(BridgeJournal j=new BridgeJournal(restartFile,time::get)){
            FakeBackend b=new FakeBackend();BridgeKernel k=new BridgeKernel(j,b,time::get);k.tick();
            check(k.execution(restartId).get("state").equals("outcome_unknown"),"restart invalidates in-flight execution");
            String token=(String)k.claim("test-session").get("token");
            check(k.submit(token,"restart-key","gather","{}").get("execution_id").equals(restartId),"idempotency survives process restart");
            check(b.starts==0,"restart never replays physical actions");
            fault(409,"observe_required",()->k.submit(token,"new-key","gather","{}"));
        }
        try(Fixture f=fixture()){
            long before=f.journal.lastSequence();f.kernel.publish("player_message",Map.of("text","hi"));
            var events=f.journal.readAfter(f.journal.epoch,before,128);
            check(((List<?>)events.get("events")).size()==1,"cursor event read");
            check(f.journal.readAfter("bad-epoch",before,128).get("gap").equals(true),"epoch mismatch signals gap");
            check(f.journal.readAfter(f.journal.epoch,999999,128).get("gap").equals(true),"future cursor signals gap");
            var executor=Executors.newSingleThreadExecutor();
            var future=executor.submit(()->f.journal.waitAfter(f.journal.epoch,f.journal.lastSequence(),2000));
            Thread.sleep(50);f.kernel.publish("death",Map.of());
            check(!((List<?>)future.get(1,TimeUnit.SECONDS).get("events")).isEmpty(),"long polling awakened without model loop");executor.shutdownNow();
        }
        Path tail=Files.createTempDirectory("aibot-tail-").resolve("journal");
        long good;
        try(BridgeJournal j=new BridgeJournal(tail,()->1)){j.append(Map.of("kind","test"));good=Files.size(tail);}
        Files.write(tail,new byte[]{0,0},StandardOpenOption.APPEND);
        try(BridgeJournal j=new BridgeJournal(tail,()->1)){check(j.lastSequence()==1 && Files.size(tail)==good,"incomplete crash tail truncated to last verified frame");}
        byte[] corrupt=Files.readAllBytes(tail);corrupt[corrupt.length-1]^=1;Files.write(tail,corrupt);
        try{new BridgeJournal(tail,()->1);throw new AssertionError("corrupt accepted");}catch(IOException expected){check(expected.getMessage().contains("checksum"),"corrupt complete frame fails closed");}
        try(Fixture f=fixture()){
            try{new BridgeJournal(f.file,()->1);throw new AssertionError("second writer");}catch(java.nio.channels.OverlappingFileLockException|IOException e){check(true,"second writer rejected");}
            f.time.addAndGet(6000);fault(503,"body_not_ready",()->f.kernel.observe());
        }
        System.out.println("TOTAL "+passed+" checks passed");
    }
}
