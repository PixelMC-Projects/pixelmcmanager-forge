package com.pixelmc.pixelmcmanager.compat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LifecycleShutdownTest {
    public static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    public static final String other = "not an executor";
    public static void main(String[] args) throws Exception {
        if (BackgroundExecutorShutdown.closeMca(new ClassLoader(null) {})) throw new AssertionError("Absent MCA must be optional");
        if (BackgroundExecutorShutdown.closeField(LifecycleShutdownTest.class, "other")) throw new AssertionError("Only executor fields are closed");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(), queuedRan = new AtomicBoolean();
        try {
            executorService.submit(() -> {started.countDown();try {release.await();} catch (InterruptedException e) {interrupted.set(true);}});
            if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
            executorService.submit(() -> queuedRan.set(true));
            long at = System.nanoTime();
            if (!BackgroundExecutorShutdown.closeField(LifecycleShutdownTest.class, "executorService")) throw new AssertionError("Shutdown not requested");
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)>500) throw new AssertionError("Shutdown must not wait on worker tasks");
            if (!executorService.isShutdown() || interrupted.get()) throw new AssertionError("Tasks must not be interrupted");
            release.countDown();
            if (!executorService.awaitTermination(2,TimeUnit.SECONDS) || !queuedRan.get() || interrupted.get()) throw new AssertionError("Queued work must drain normally");
            BackgroundExecutorShutdown.closeField(LifecycleShutdownTest.class, "executorService");
            System.out.println("Lifecycle shutdown checks passed: 7 (optional, typed, nonblocking, orderly, draining, terminated, idempotent)");
        } finally {release.countDown();executorService.shutdownNow();}
    }
}
