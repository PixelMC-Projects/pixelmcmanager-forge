package com.pixelmc.pixelmcmanager.restart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RestartLogicTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        checks++;
    }
    private static void jar(Path path, String value) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            ZipEntry entry = new ZipEntry("mod.txt"); entry.setTime(0);
            zip.putNextEntry(entry); zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        if (!condition.getAsBoolean()) throw new AssertionError("Directory event was not processed");
    }
    public static void main(String[] args) throws Exception {
        IdleRestartState state = new IdleRestartState();
        check(!state.ready(0, 0, false, false, true), "No unsolicited empty restart");
        state.arm();
        check(!state.ready(0, 0, false, false, true), "Start empty clock on scheduling");
        check(!state.ready(14_999_000_000L, 0, false, false, true), "Not before 15 seconds");
        check(state.ready(15_000_000_000L, 0, false, false, true), "Ready at 15 real seconds");
        state.playerJoined();
        check(!state.ready(16_000_000_000L, 0, false, false, true), "Join/logout between ticks resets clock");
        check(!state.ready(30_000_000_000L, 1, false, false, true), "Online player prevents restart");
        check(!state.ready(31_000_000_000L, 0, false, false, true), "Fresh clock after last logout");
        check(!state.ready(46_000_000_000L, 0, false, true, true), "Explicit stop plan has priority");
        check(!state.ready(47_000_000_000L, 0, false, false, false), "No backend means no shutdown");
        check(state.cancel() && !state.manual(), "Manual cancellation");
        check(!state.ready(50_000_000_000L, 0, true, false, true), "Auto pending starts timer");
        check(!state.ready(60_000_000_000L, 0, false, false, true), "Reverted mods or disabled auto cancels automatic timer");
        check(!state.ready(70_000_000_000L, 0, true, false, true), "New automatic timer");
        check(state.ready(85_000_000_000L, 0, true, false, true), "Automatic empty restart");
        state.markDispatched();
        check(!state.ready(86_000_000_000L, 0, true, false, true), "Dispatch only once");
        boolean rejected = false;
        try { state.cancel(); } catch (IllegalStateException e) { rejected = true; }
        check(rejected, "Cannot cancel already handed off restart");
        state.fail("backend unavailable");
        check(!state.ready(100_000_000_000L, 0, true, false, true), "Failure pauses automatic retries");
        state.arm();
        check(state.failure().isEmpty(), "Explicit scheduling can retry after repair");

        Path directory = Files.createTempDirectory("pixelmc-restart-test-");
        try {
            Path mods = Files.createDirectory(directory.resolve("mods"));
            Path mod = mods.resolve("one.jar"); jar(mod, "AAAA");
            ModDirectorySnapshot first = ModDirectorySnapshot.capture(mods);
            check(first.equals(ModDirectorySnapshot.capture(mods)), "Unchanged metadata snapshot");
            jar(mods.resolve("ignored.jar.incoming"), "partial");
            check(first.equals(ModDirectorySnapshot.capture(mods)), "Ignore unfinished upload filenames");
            jar(mods.resolve("two.jar"), "added");
            ModDirectorySnapshot added = ModDirectorySnapshot.capture(mods);
            check(!added.equals(first), "Detect additions without content reads");
            Files.delete(mod);
            check(!added.equals(ModDirectorySnapshot.capture(mods)), "Detect removals");
            Files.writeString(mods.resolve("partial.jar"), "incomplete");
            check(ModDirectorySnapshot.capture(mods).entries().containsKey("partial.jar"), "Inventory only stats metadata");
            boolean partialRejected = false;
            try { ModDirectorySnapshot.validateChangedJar(mods.resolve("partial.jar")); } catch (java.io.IOException e) { partialRejected = true; }
            check(partialRejected, "Incomplete JAR suspends automatic trigger");

            Path watched = Files.createDirectory(directory.resolve("watched"));
            Path tracked = watched.resolve("tracked.jar"); jar(tracked, "AAAA");
            try (ModDirectoryMonitor monitor = new ModDirectoryMonitor(watched, 60)) {
                monitor.start(); await(() -> monitor.view().ready());
                check(!monitor.view().changed(), "Initial inventory is metadata-only baseline");
                jar(watched.resolve("ignored.jar.incoming"), "temporary"); Thread.sleep(700);
                check(!monitor.view().changed(), "Temporary upload does not trigger notifications");
                FileTime originalTime = Files.getLastModifiedTime(tracked);
                jar(tracked, "BBBB"); Files.setLastModifiedTime(tracked, originalTime);
                await(() -> monitor.view().changed() && monitor.view().stable());
                check(monitor.view().changed(), "Native events detect same-size same-mtime rewrite without hashing");
                Files.writeString(watched.resolve("broken.jar"), "partial");
                await(() -> !monitor.view().stable() && !monitor.view().error().isEmpty());
                check(!monitor.view().stable(), "Unfinished changed JAR prevents restart");
                jar(watched.resolve("broken.jar"), "complete");
                await(() -> monitor.view().stable());
                check(monitor.view().error().isEmpty(), "Completing changed JAR restores readiness");
            }

            Path ipc = Files.createDirectory(directory.resolve("ipc"));
            RestartBridgeClient client = new RestartBridgeClient(ipc);
            long now = System.currentTimeMillis();
            check(!client.read(now).ready(), "No host adapter is not a restart backend");
            Files.writeString(ipc.resolve("bridge.json"), "{\"protocol\":1,\"backend\":\"mcsm\",\"bridgeId\":\"test\",\"expiresAt\":" + (now + 6000) + "}");
            check(client.read(now).ready(), "Detect live MCSM adapter");
            client.request(UUID.randomUUID().toString(), "test", 15000, now);
            check(Files.readString(ipc.resolve("request.json")).contains("restart"), "Submit explicit restart rather than stop");
            check(Files.readString(ipc.resolve("game.json")).contains("\"locked\":true"), "Lock join before external handoff");
            check(!client.read(now + 7000).ready(), "Expired host heartbeat is unavailable");
            Files.writeString(ipc.resolve("bridge.json"), "{\"protocol\":1,\"backend\":\"standalone\",\"bridgeId\":\"test\",\"expiresAt\":" + (now + 6000) + "}");
            check(client.read(now).ready(), "Detect ordinary startup adapter");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Restart logic checks passed: " + checks);
    }
}
