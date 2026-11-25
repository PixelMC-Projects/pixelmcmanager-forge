package com.pixelmc.pixelmcmanager.audit;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AuditService {
    private static final Gson GSON = new Gson();
    private static final int RECENT_CACHE_SIZE = 500;
    private static final String DATA_DIR = "pixelmcmanager";
    private static final String AUDIT_FILE = "audit.jsonl";

    private final Logger logger;
    private final Deque<AuditEntry> recentEntries = new ArrayDeque<>();
    private Path auditFile;

    public AuditService(Logger logger) {
        this.logger = logger;
    }

    public synchronized void load(MinecraftServer server) {
        auditFile = server.getWorldPath(LevelResource.ROOT).resolve(DATA_DIR).resolve(AUDIT_FILE);
        recentEntries.clear();
        if (Files.notExists(auditFile)) {
            return;
        }

        try (var lines = Files.lines(auditFile, StandardCharsets.UTF_8)) {
            lines.forEach(
                    line -> {
                        try {
                            AuditEntry entry = GSON.fromJson(line, AuditEntry.class);
                            if (entry != null) {
                                addToCache(entry);
                            }
                        } catch (JsonSyntaxException exception) {
                            logger.warn(
                                    "Ignoring invalid PixelMC Manager audit log line in {}.",
                                    auditFile);
                        }
                    });
        } catch (IOException exception) {
            logger.error("Failed to load PixelMC Manager audit log from {}.", auditFile, exception);
        }
    }

    public synchronized void record(
            CommandSourceStack source, String action, String args, String detail) {
        if (auditFile == null) {
            auditFile =
                    source.getServer()
                            .getWorldPath(LevelResource.ROOT)
                            .resolve(DATA_DIR)
                            .resolve(AUDIT_FILE);
        }

        AuditEntry entry = createEntry(source, action, args, detail);
        addToCache(entry);
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(
                    auditFile,
                    GSON.toJson(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            logger.error("Failed to append PixelMC Manager audit log to {}.", auditFile, exception);
        }
    }

    public synchronized List<AuditEntry> getRecent(int count) {
        int limit = Math.min(count, recentEntries.size());
        List<AuditEntry> entries = new ArrayList<>(limit);
        var iterator = recentEntries.descendingIterator();
        while (iterator.hasNext() && entries.size() < limit) {
            entries.add(iterator.next());
        }
        return entries;
    }

    public void flush() {
        // Audit records are appended immediately; this method documents that no buffered writes
        // remain.
    }

    private AuditEntry createEntry(
            CommandSourceStack source, String action, String args, String detail) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return new AuditEntry(
                    System.currentTimeMillis(),
                    "PLAYER",
                    player.getGameProfile().getName(),
                    player.getUUID().toString(),
                    action,
                    args == null ? "" : args,
                    "success",
                    detail == null ? "" : detail);
        }

        return new AuditEntry(
                System.currentTimeMillis(),
                "CONSOLE",
                "Console",
                "",
                action,
                args == null ? "" : args,
                "success",
                detail == null ? "" : detail);
    }

    private void addToCache(AuditEntry entry) {
        recentEntries.addLast(entry);
        while (recentEntries.size() > RECENT_CACHE_SIZE) {
            recentEntries.removeFirst();
        }
    }
}
