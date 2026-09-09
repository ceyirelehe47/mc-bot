package io.github.zoyluo.aibot.external;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real HTTP/kernel/journal, fake Minecraft backend. Never represents an in-game acceptance test. */
public final class FakeBridgeServer {
    static final class Fake implements BodyBackend {
        volatile int starts; String state="completed"; int ticks;
        void thread(){if(!Thread.currentThread().getName().equals("fake-minecraft-thread"))throw new IllegalStateException("off_game_thread");}
        public boolean ready(){thread();return true;} public String bodyId(){thread();return "fake-body";}
        public String observeJson(){thread();return JsonOutput.encode(Map.of("health",20,"physical_starts",starts));}
        public Handle start(String op,String json){thread();starts++;state="running";ticks=0;
            return ()->{thread();if(state.equals("running") && ++ticks>=60)state="completed";return new Snapshot(state,.5,"fake_backend_not_minecraft");};}
        public void pause(){thread();if(state.equals("running"))state="paused";}
        public void resume(){thread();state="running";}public void cancel(String reason){thread();state="cancelled";}
    }
    public static void main(String[] args)throws Exception {
        BridgeJournal journal=new BridgeJournal(Path.of(args[0]),System::currentTimeMillis);
        Fake backend=new Fake();BridgeKernel kernel=new BridgeKernel(journal,backend);
        ScheduledExecutorService tick=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"fake-minecraft-thread"));
        tick.submit(kernel::tick).get();
        BridgeHttpServer http=new BridgeHttpServer(kernel,0,args[1]);http.start();
        tick.scheduleAtFixedRate(kernel::tick,10,20,TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{http.close();tick.shutdownNow();try{journal.close();}catch(Exception ignored){}}));
        System.out.println("READY "+http.port());System.out.flush();
        new CountDownLatch(1).await();
    }
}
