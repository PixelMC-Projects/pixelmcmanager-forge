package com.pixelmc.pixelmcmanager.restart;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Native directory notifications with low-frequency metadata-only fallback. */
public final class ModDirectoryMonitor implements AutoCloseable {
    public record View(boolean ready, boolean changed, boolean stable, long revision, String error) {}
    private final Path directory;
    private volatile int recheckSeconds;
    private volatile boolean closed;
    private volatile View view = new View(false, false, false, 0, "初始化中");
    private WatchService watcher;
    private Thread thread;
    public ModDirectoryMonitor(Path directory, int recheckSeconds) {
        this.directory = directory;
        this.recheckSeconds = recheckSeconds;
    }
    public void start() {
        thread = new Thread(this::run, "PixelMCManager-ModEvents");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }
    public View view() { return view; }
    public void setRecheckSeconds(int seconds) { recheckSeconds = seconds; }

    private void run() {
        Map<String, ModDirectorySnapshot.Entry> baseline = null, current = new TreeMap<>();
        Set<String> pending = new HashSet<>(), touched = new HashSet<>();
        long revision = 0, lastEvent = 0, nextCheck = 0, nextValidation = 0;
        boolean pendingValidation = false, overflowed = false;
        try {
            try {
                watcher = FileSystems.getDefault().newWatchService();
                directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            } catch (IOException | UnsupportedOperationException e) {
                if (watcher != null) watcher.close();
                watcher = null;
            }
            while (!closed) {
                long now = System.nanoTime();
                boolean refresh = baseline == null || now >= nextCheck;
                if (watcher != null) {
                    WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS);
                    if (key != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                refresh = true;
                                overflowed = true;
                                revision++;
                                pendingValidation = true;
                                lastEvent = System.nanoTime();
                            } else if (event.context() instanceof Path path && ModDirectorySnapshot.isJar(path.toString())) {
                                String name = path.getFileName().toString();
                                pending.add(name);
                                if (baseline != null && baseline.containsKey(name)) touched.add(name);
                                revision++;
                                pendingValidation = true;
                                lastEvent = System.nanoTime();
                            }
                        }
                        if (!key.reset()) throw new IOException("mods目录通知已失效");
                    }
                } else Thread.sleep(500);
                now = System.nanoTime();
                if (refresh) {
                    Map<String, ModDirectorySnapshot.Entry> observed = ModDirectorySnapshot.capture(directory).entries();
                    if (baseline == null) { baseline = observed; current.putAll(observed); }
                    else if (!observed.equals(current)) {
                        for (var entry : observed.entrySet())
                            if (!entry.getValue().equals(current.get(entry.getKey()))) pending.add(entry.getKey());
                        current.clear(); current.putAll(observed);
                        pendingValidation = true;
                        lastEvent = now;
                        revision++;
                    }
                    nextCheck = now + recheckSeconds * 1_000_000_000L;
                }
                if (pendingValidation && now - lastEvent >= 1_000_000_000L && now >= nextValidation) {
                    try {
                        for (String name : pending) {
                            Path path = directory.resolve(name);
                            if (Files.exists(path)) {
                                ModDirectorySnapshot.validateChangedJar(path);
                                current.put(name, ModDirectorySnapshot.stat(path));
                            } else current.remove(name);
                        }
                        touched.retainAll(current.keySet());
                        pending.clear();
                        pendingValidation = false;
                    } catch (IOException e) {
                        nextValidation = now + 2_000_000_000L;
                        view = new View(true, true, false, revision, "变更JAR未写完或不可读，暂停自动重启");
                        continue;
                    }
                }
                boolean changed = baseline != null && (!baseline.equals(current) || !touched.isEmpty() || overflowed);
                view = new View(baseline != null, changed, !pendingValidation, revision,
                        watcher == null ? "文件通知不可用，仅使用低频元数据兜底" : "");
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // Normal close; the game thread never waits for this worker.
        } catch (IOException | RuntimeException e) {
            view = new View(baseline != null, false, false, revision + 1, "目录监听失败，已暂停自动重启，请检查mods目录权限");
        } finally {
            if (watcher != null) try { watcher.close(); } catch (IOException ignored) { }
        }
    }
    @Override public void close() {
        closed = true;
        if (thread != null) thread.interrupt();
        if (watcher != null) try { watcher.close(); } catch (IOException ignored) { }
    }
}
