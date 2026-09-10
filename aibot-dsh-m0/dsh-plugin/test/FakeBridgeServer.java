package io.github.zoyluo.aibot.external;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R2.1 test rebuild of the R2 interop stub: boots the PRODUCTION BridgeJournal + BridgeKernel +
 * BridgeHttpServer against a fake in-memory BodyBackend. Prints "READY <port>" and stays alive.
 * Args: <journal-path> <bearer-token>.
 */
public final class FakeBridgeServer {
    public static void main(String[] args) throws Exception {
        Path journalPath = Path.of(args[0]);
        String token = args[1];
        AtomicInteger physicalStarts = new AtomicInteger();
        AtomicReference<BodyBackend.Handle> active = new AtomicReference<>();

        BodyBackend fake = new BodyBackend() {
            final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
            final String fakeScene = "{\"world\":{\"dimension\":\"minecraft:overworld\",\"world_id\":\"fake-world-1\"},\"self\":{\"name\":\"FakeBot\"}}";
            final String fakeHash = sha256Hex(fakeScene);
            final String fakeRef = "mc://fake-world-1/minecraft%3Aoverworld/structure/fake_home";
            @Override public boolean ready() { return true; }
            @Override public String bodyId() { return "fake-body-1"; }
            @Override public long serverTick() { return ticks.incrementAndGet(); }
            @Override public String observeJson() {
                return "{\"body_id\":\"fake-body-1\",\"physical_starts\":" + physicalStarts.get() + "}";
            }
            @Override public io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot.Snapshot cognitiveSnapshot(BridgeJournal journal) {
                java.util.Map<String,String> details = new java.util.LinkedHashMap<>();
                details.put("summary", "{\"object_id\":\"fake_home\",\"kind\":\"structure\",\"summary\":{\"baseline_cells\":9}}");
                details.put("baseline", "{\"object_id\":\"fake_home\",\"baseline_cells\":9}");
                java.util.Map<String,java.util.Map<String,String>> index = new java.util.LinkedHashMap<>();
                index.put(fakeRef, details);
                return new io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot.Snapshot(fakeScene, fakeHash, 12345L, index);
            }
            @Override public String inspectLocalJson(int radius, String detail) {
                int effective = Math.min(radius, 8);
                return "{\"schema\":\"mc.local_view.v0\",\"radius_effective\":" + effective + ",\"detail\":\"" + detail + "\"}";
            }
            @Override public Handle start(String operation, String argumentsJson) {
                physicalStarts.incrementAndGet();
                FakeHandle handle = new FakeHandle();
                active.set(handle);
                return handle;
            }
            @Override public void pause() {
                BodyBackend.Handle handle = active.get();
                if (handle instanceof FakeHandle fakeHandle) fakeHandle.paused.set(true);
            }
            @Override public void resume() {
                BodyBackend.Handle handle = active.get();
                if (handle instanceof FakeHandle fakeHandle) {
                    fakeHandle.paused.set(false);
                    fakeHandle.finish.set(true);
                }
            }
            @Override public void cancel(String reason) {
                BodyBackend.Handle handle = active.get();
                if (handle instanceof FakeHandle fakeHandle) fakeHandle.cancelled.set(true);
            }
        };

        BridgeJournal journal = new BridgeJournal(journalPath, System::currentTimeMillis);
        BridgeKernel kernel = new BridgeKernel(journal, fake);
        BridgeHttpServer http = new BridgeHttpServer(kernel, 0, token);
        http.start();
        Thread ticker = new Thread(null, () -> {
            while (true) {
                try { kernel.tick(); Thread.sleep(5L); }
                catch (InterruptedException interrupted) { return; }
                catch (RuntimeException runtime) { runtime.printStackTrace(); }
            }
        }, "fake-kernel-tick", 0L);
        ticker.start();
        System.out.println("READY " + http.port());
        System.out.flush();
        Thread.currentThread().join();
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class FakeHandle implements BodyBackend.Handle {
        final java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean finish = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public BodyBackend.Snapshot snapshot() {
            // Auto-complete after ~30 kernel ticks so plugin transports see terminal wakes without
            // an explicit resume; pause still holds priority once applied.
            if (calls.incrementAndGet() > 30 && !paused.get()) finish.set(true);
            if (cancelled.get()) return new BodyBackend.Snapshot("cancelled", 1, "fake_cancelled");
            if (paused.get()) return new BodyBackend.Snapshot("paused", 0.5, "fake_paused");
            if (finish.get()) return new BodyBackend.Snapshot("completed", 1, "fake_world_completed");
            return new BodyBackend.Snapshot("running", 0.1, "");
        }
    }
}
