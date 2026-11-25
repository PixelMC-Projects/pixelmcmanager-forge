package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.JSON;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/**
 * Loopback-only scoped API tokens for narrow operator automation. Stores only SHA-256 digests and
 * automatically reloads when updated on disk by CLI management commands.
 */
public final class PlatformApiTokens {
    public static final String SCOPE_UPDATES_DRAFT = "updates:draft";
    public static final String SCOPE_UPDATES_PUBLISH = "updates:publish";
    public static final Set<String> ALLOWED_SCOPES =
            Set.of(SCOPE_UPDATES_DRAFT, SCOPE_UPDATES_PUBLISH);

    public record TokenRecord(
            String id,
            UUID player,
            String hash,
            List<String> scopes,
            long createdAt,
            long revokedAt) {
        public boolean active() {
            return revokedAt == 0L;
        }

        public boolean canDraftUpdates() {
            return scopes != null
                    && (scopes.contains(SCOPE_UPDATES_DRAFT)
                            || scopes.contains(SCOPE_UPDATES_PUBLISH));
        }

        public boolean canPublishUpdates() {
            return scopes != null && scopes.contains(SCOPE_UPDATES_PUBLISH);
        }
    }

    public record CreatedToken(TokenRecord record, String secret) {}

    private record FileData(int format, List<TokenRecord> tokens) {}

    private final Path path;
    private final Map<String, TokenRecord> tokens = new LinkedHashMap<>();
    private FileTime lastModified;
    private long lastSize = -1L;
    private Object lastFileKey;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PlatformApiTokens(Path folder) throws Exception {
        this.path = folder.resolve("api-tokens.json");
        reloadIfNeeded();
    }

    public static String normalizeId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{1,47}")) {
            throw new IllegalArgumentException("令牌名称须为2–48位英文、数字、_.-");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static List<String> normalizeScopes(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("至少指定一个令牌作用域");
        }
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                throw new IllegalArgumentException("令牌作用域无效");
            }
            String scope = item.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("不支持的令牌作用域：" + scope);
            }
            clean.add(scope);
        }
        return List.copyOf(clean);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void reloadIfNeeded() throws Exception {
        if (!Files.exists(path)) {
            if (!tokens.isEmpty() || lastSize != -1L) {
                tokens.clear();
                lastModified = null;
                lastSize = -1L;
                lastFileKey = null;
            }
            return;
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        FileTime mtime = attrs.lastModifiedTime();
        long size = attrs.size();
        Object fileKey = attrs.fileKey();
        if (Objects.equals(mtime, lastModified)
                && size == lastSize
                && Objects.equals(fileKey, lastFileKey)) {
            return;
        }
        FileData file = JSON.fromJson(Files.readString(path), FileData.class);
        if (file == null || file.format() != 1 || file.tokens() == null) {
            throw new IllegalArgumentException("API令牌文件无效");
        }
        Map<String, TokenRecord> loaded = new LinkedHashMap<>();
        for (TokenRecord record : file.tokens()) {
            if (record == null || record.player() == null) {
                throw new IllegalArgumentException("API令牌记录无效");
            }
            String id = normalizeId(record.id());
            String hash = record.hash();
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("API令牌摘要无效");
            }
            List<String> scopes = normalizeScopes(record.scopes());
            if (record.createdAt() <= 0L || record.revokedAt() < 0L) {
                throw new IllegalArgumentException("API令牌时间无效");
            }
            TokenRecord clean =
                    new TokenRecord(
                            id,
                            record.player(),
                            hash,
                            scopes,
                            record.createdAt(),
                            record.revokedAt());
            if (loaded.putIfAbsent(id, clean) != null) {
                throw new IllegalArgumentException("API令牌名称重复：" + id);
            }
        }
        tokens.clear();
        tokens.putAll(loaded);
        lastModified = mtime;
        lastSize = size;
        lastFileKey = fileKey;
    }

    public synchronized TokenRecord verify(String rawSecret) throws Exception {
        reloadIfNeeded();
        if (rawSecret == null
                || rawSecret.isEmpty()
                || rawSecret.length() > 256
                || rawSecret.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        byte[] actual = digest(rawSecret).getBytes(StandardCharsets.US_ASCII);
        TokenRecord matched = null;
        for (TokenRecord record : tokens.values()) {
            byte[] expected = record.hash().getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(actual, expected)) {
                matched = record;
            }
        }
        if (matched == null || !matched.active()) {
            return null;
        }
        return matched;
    }

    public synchronized boolean isActive(TokenRecord expected) {
        if (expected == null) {
            return false;
        }
        try {
            reloadIfNeeded();
        } catch (Exception e) {
            return false;
        }
        TokenRecord current = tokens.get(expected.id());
        return current != null
                && current.active()
                && current.player().equals(expected.player())
                && current.hash().equals(expected.hash())
                && current.scopes().equals(expected.scopes());
    }

    public synchronized CreatedToken create(String id, UUID player, Collection<String> scopes)
            throws Exception {
        reloadIfNeeded();
        if (player == null) {
            throw new IllegalArgumentException("服主UUID必填");
        }
        String cleanId = normalizeId(id);
        if (tokens.containsKey(cleanId)) {
            throw new IllegalArgumentException("令牌名称已存在：" + cleanId);
        }
        List<String> cleanScopes = normalizeScopes(scopes);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = "pmk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TokenRecord record =
                new TokenRecord(
                        cleanId,
                        player,
                        digest(secret),
                        cleanScopes,
                        System.currentTimeMillis(),
                        0L);
        tokens.put(cleanId, record);
        try {
            save();
        } catch (Exception e) {
            tokens.remove(cleanId);
            throw e;
        }
        return new CreatedToken(record, secret);
    }

    public synchronized TokenRecord revoke(String id) throws Exception {
        reloadIfNeeded();
        String cleanId = normalizeId(id);
        TokenRecord existing = tokens.get(cleanId);
        if (existing == null) {
            throw new IllegalArgumentException("令牌不存在：" + cleanId);
        }
        if (!existing.active()) {
            return existing;
        }
        TokenRecord updated =
                new TokenRecord(
                        existing.id(),
                        existing.player(),
                        existing.hash(),
                        existing.scopes(),
                        existing.createdAt(),
                        System.currentTimeMillis());
        tokens.put(cleanId, updated);
        try {
            save();
        } catch (Exception e) {
            tokens.put(cleanId, existing);
            throw e;
        }
        return updated;
    }

    public synchronized List<String> list() throws Exception {
        reloadIfNeeded();
        return tokens.values().stream()
                .sorted(Comparator.comparing(TokenRecord::id))
                .map(
                        r ->
                                r.id()
                                        + " → "
                                        + r.player()
                                        + " ["
                                        + String.join(",", r.scopes())
                                        + "] "
                                        + (r.active() ? "active" : "revoked"))
                .toList();
    }

    private void save() throws Exception {
        ServiceConfig.atomic(path, JSON.toJson(new FileData(1, List.copyOf(tokens.values()))));
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        lastModified = attrs.lastModifiedTime();
        lastSize = attrs.size();
        lastFileKey = attrs.fileKey();
    }
}
