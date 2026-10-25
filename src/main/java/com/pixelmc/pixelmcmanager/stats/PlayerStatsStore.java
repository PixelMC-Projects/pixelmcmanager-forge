package com.pixelmc.pixelmcmanager.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlayerStatsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DATA_DIR = "pixelmcmanager";
    private static final String LEGACY_DATA_DIR = "pixelmcwelcome";
    private static final Type FILE_TYPE = new TypeToken<StatsFile>() {
    }.getType();

    private final Logger logger;
    private final Map<UUID, PlayerStats> players = new LinkedHashMap<>();
    private final Map<UUID, Long> lastAccountedMillis = new HashMap<>();
    private Path dataFile;
    private boolean loaded = false;

    public PlayerStatsStore(Logger logger) {
        this.logger = logger;
    }

    public synchronized void load(MinecraftServer server) {
        // LevelResource.ROOT resolves inside the active save root, the directory that owns level.dat.
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path nextDataFile = worldRoot.resolve(DATA_DIR).resolve("player_stats.json");
        Path legacyDataFile = worldRoot.resolve(LEGACY_DATA_DIR).resolve("player_stats.json");
        Map<UUID, PlayerStats> loadedPlayers = new LinkedHashMap<>();

        try {
            dataFile = nextDataFile;
            Files.createDirectories(nextDataFile.getParent());
            migrateLegacyStatsIfNeeded(legacyDataFile);
            if (Files.notExists(nextDataFile)) {
                players.clear();
                lastAccountedMillis.clear();
                loaded = true;
                save();
                return;
            }

            String json = Files.readString(nextDataFile, StandardCharsets.UTF_8);
            StatsFile statsFile = GSON.fromJson(json, FILE_TYPE);
            if (statsFile == null) {
                throw new JsonParseException("Stats file is empty.");
            }
            if (statsFile.players != null) {
                for (Map.Entry<String, PlayerStats> entry : statsFile.players.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        PlayerStats stats = entry.getValue() == null ? new PlayerStats() : entry.getValue();
                        loadedPlayers.put(uuid, stats);
                    } catch (IllegalArgumentException ignored) {
                        logger.warn("Ignoring invalid player UUID in PixelMC Manager stats: {}", entry.getKey());
                    }
                }
            }
            players.clear();
            players.putAll(loadedPlayers);
            lastAccountedMillis.clear();
            loaded = true;
        } catch (JsonParseException exception) {
            dataFile = nextDataFile;
            logger.error("PixelMC Manager player stats file {} is invalid JSON.", dataFile, exception);
            backupBrokenStatsFile();
            players.clear();
            lastAccountedMillis.clear();
            loaded = true;
        } catch (IOException | RuntimeException exception) {
            dataFile = nextDataFile;
            loaded = false;
            logger.error("Failed to load PixelMC Manager player stats from {}. Existing file was left untouched.", dataFile, exception);
        }
    }

    public synchronized boolean ensureLoaded(MinecraftServer server) {
        if (!loaded) {
            load(server);
        }
        return loaded;
    }

    public synchronized PlayerLoginSnapshot recordLogin(ServerPlayer player, long nowMillis) {
        UUID uuid = player.getUUID();
        PlayerStats stats = players.computeIfAbsent(uuid, ignored -> {
            PlayerStats created = new PlayerStats();
            created.firstJoinEpochMillis = nowMillis;
            return created;
        });

        boolean firstJoin = stats.joinCount == 0;
        long previousLastJoin = stats.lastJoinEpochMillis;
        if (stats.firstJoinEpochMillis <= 0L) {
            stats.firstJoinEpochMillis = nowMillis;
        }
        stats.name = player.getGameProfile().getName();
        stats.joinCount++;
        stats.lastJoinEpochMillis = nowMillis;
        lastAccountedMillis.put(uuid, nowMillis);
        return new PlayerLoginSnapshot(stats.copy(), firstJoin, previousLastJoin);
    }

    public synchronized void recordLogout(ServerPlayer player, long nowMillis) {
        settleSession(player.getUUID(), nowMillis);
    }

    public synchronized PlayerStats getStats(UUID uuid) {
        PlayerStats stats = players.get(uuid);
        return stats == null ? null : stats.copy();
    }

    public synchronized List<PlayerStatsEntry> listAll() {
        return players.entrySet().stream()
                .map(entry -> new PlayerStatsEntry(entry.getKey(), entry.getValue().copy()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public synchronized Optional<PlayerStatsEntry> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return players.entrySet().stream()
                .filter(entry -> entry.getValue().name != null && entry.getValue().name.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .map(entry -> new PlayerStatsEntry(entry.getKey(), entry.getValue().copy()));
    }

    public synchronized Collection<String> listKnownPlayerNames() {
        Map<String, String> namesByLowercase = new LinkedHashMap<>();
        players.values().stream()
                .map(stats -> stats.name)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .forEach(name -> namesByLowercase.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        return List.copyOf(namesByLowercase.values());
    }

    public synchronized boolean hasOnlineSessions() {
        return !lastAccountedMillis.isEmpty();
    }

    public synchronized long uncheckpointedMillis(UUID id, long now) {
        Long at = lastAccountedMillis.get(id);
        return at == null ? 0 : Math.max(0, now - at);
    }

    public synchronized void checkpointOnlinePlayers(MinecraftServer server, long nowMillis, boolean saveAfterCheckpoint) {
        if (!ensureLoaded(server)) {
            return;
        }
        for (UUID uuid : lastAccountedMillis.keySet().toArray(UUID[]::new)) {
            if (server.getPlayerList().getPlayer(uuid) != null) {
                accountOnlineTime(uuid, nowMillis, false);
            } else {
                settleSession(uuid, nowMillis);
            }
        }
        if (saveAfterCheckpoint) {
            save();
        }
    }

    public synchronized void settleAll(long nowMillis) {
        for (UUID uuid : lastAccountedMillis.keySet().toArray(UUID[]::new)) {
            settleSession(uuid, nowMillis);
        }
    }

    private void settleSession(UUID uuid, long nowMillis) {
        accountOnlineTime(uuid, nowMillis, true);
    }

    private void accountOnlineTime(UUID uuid, long nowMillis, boolean removeSession) {
        Long accountedAt = lastAccountedMillis.get(uuid);
        PlayerStats stats = players.get(uuid);
        if (accountedAt != null && stats != null) {
            stats.totalOnlineMillis += Math.max(0L, nowMillis - accountedAt);
            if (removeSession) {
                lastAccountedMillis.remove(uuid);
            } else {
                lastAccountedMillis.put(uuid, nowMillis);
            }
        } else if (removeSession) {
            lastAccountedMillis.remove(uuid);
        }
    }

    public synchronized void save() {
        if (!loaded || dataFile == null) {
            return;
        }

        try {
            Files.createDirectories(dataFile.getParent());
            StatsFile statsFile = new StatsFile();
            statsFile.players = new LinkedHashMap<>();
            for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
                statsFile.players.put(entry.getKey().toString(), entry.getValue());
            }

            Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            byte[] bytes = GSON.toJson(statsFile).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                // Force temp file contents to disk before replace. Directory fsync is platform dependent on Windows, so atomic replace is still the main protection.
                channel.force(true);
            }
            try {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.error("Failed to save PixelMC Manager player stats to {}.", dataFile, exception);
        }
    }

    private void backupBrokenStatsFile() {
        if (dataFile == null || Files.notExists(dataFile)) {
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = dataFile.resolveSibling("player_stats.json.broken-" + timestamp);
        try {
            Files.move(dataFile, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("Backed up broken PixelMC Manager stats file to {}.", backup);
        } catch (IOException exception) {
            logger.error("Failed to back up broken PixelMC Manager stats file {}.", dataFile, exception);
        }
    }

    private void migrateLegacyStatsIfNeeded(Path legacyDataFile) throws IOException {
        if (Files.notExists(dataFile) && Files.exists(legacyDataFile)) {
            Files.copy(legacyDataFile, dataFile, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Copied legacy pixelmcwelcome stats from {} to {}.", legacyDataFile, dataFile);
        }
    }

    private static final class StatsFile {
        int version = 1;
        Map<String, PlayerStats> players = new LinkedHashMap<>();
    }
}
