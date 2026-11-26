package com.pixelmc.manager.service;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional read-only pre-launch signing gateway for AutoModpack OSS. Issues short-lived OSS V4
 * GET-only URLs with per-IP rate limits and daily signing budgets while keeping the bucket private.
 */
public final class ModpackPrelaunchGateway implements AutoCloseable {
    public static final String ENV_CONFIG = "PIXELMC_MODPACK_GATEWAY_CONFIG";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter OSS_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;

    public static final class GatewayConfig {
        public boolean enabled;
        public String packId;
        public String trustStore;
        public String activeReleaseFile;
        public String previewActiveReleaseFile;
        public String bucket;
        public String region;
        public String killSwitchFile;
        public String accessKeyIdEnvironment = "AUTOMODPACK_OSS_READ_KEY_ID";
        public String accessKeySecretEnvironment = "AUTOMODPACK_OSS_READ_KEY_SECRET";
        public String securityTokenEnvironment = "AUTOMODPACK_OSS_READ_SECURITY_TOKEN";
        public String serverIdentityKeyEnvironment = "AUTOMODPACK_OSS_SERVER_IDENTITY_PKCS8";
        public int urlLifetimeSeconds = 180;
        public int activeRefreshSeconds = 0;
        public int rateLimitPerMinutePerIp = 20;
        public int dailySignBudget = 5000;
        public int maxPathsPerRequest = 64;
        public boolean allowLocalTestEndpoint;
        public String localTestManifestHost;

        public static GatewayConfig load(Path path) throws IOException {
            if (!path.isAbsolute() || Files.size(path) > 65536) {
                throw new IllegalArgumentException("Invalid gateway config path");
            }
            GatewayConfig c =
                    JSON.fromJson(
                            Files.readString(path, StandardCharsets.UTF_8), GatewayConfig.class);
            if (c == null || !c.enabled) {
                throw new IllegalArgumentException("Prelaunch gateway disabled");
            }
            if (blank(c.packId)
                    || blank(c.bucket)
                    || blank(c.region)
                    || blank(c.trustStore)
                    || !Path.of(c.trustStore).isAbsolute()
                    || blank(c.activeReleaseFile)
                    || !Path.of(c.activeReleaseFile).isAbsolute()
                    || blank(c.killSwitchFile)
                    || !Path.of(c.killSwitchFile).isAbsolute()) {
                throw new IllegalArgumentException("Incomplete prelaunch gateway configuration");
            }
            if (c.urlLifetimeSeconds < 60 || c.urlLifetimeSeconds > 300) {
                throw new IllegalArgumentException("urlLifetimeSeconds must be 60..300");
            }
            if (c.rateLimitPerMinutePerIp < 1 || c.rateLimitPerMinutePerIp > 600) {
                throw new IllegalArgumentException("Invalid rateLimitPerMinutePerIp");
            }
            if (c.dailySignBudget < 1 || c.dailySignBudget > 1_000_000) {
                throw new IllegalArgumentException("Invalid dailySignBudget");
            }
            if (c.maxPathsPerRequest < 1 || c.maxPathsPerRequest > 256) {
                throw new IllegalArgumentException("Invalid maxPathsPerRequest");
            }
            return c;
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    private record TrustInfo(
            String packId,
            PublicKey publisherKey,
            PublicKey serverIdentityKey,
            URI manifestBaseUrl,
            Set<String> allowedHosts,
            long minimumSequence) {}

    private record ReleasePin(
            String channel,
            String packId,
            String releaseId,
            long sequence,
            URI manifestUrl,
            String manifestSha256) {}

    private record VerifiedRelease(
            String channel,
            String packId,
            String releaseId,
            long sequence,
            String payloadDigest,
            URI manifestUrl,
            Map<String, URI> files) {}

    private static final class ChannelSlot {
        final String channel;
        final Path pinPath;
        volatile VerifiedRelease active;
        volatile long lastVerifiedSec;
        long highestSequence;
        String highestRelease;

        ChannelSlot(String channel, Path pinPath) {
            this.channel = channel;
            this.pinPath = pinPath;
        }

        boolean available(int refreshSeconds) {
            return active != null
                    && (refreshSeconds <= 0
                            || Instant.now().getEpochSecond() - lastVerifiedSec
                                    <= refreshSeconds * 2L);
        }
    }

    private final GatewayConfig config;
    private final TrustInfo trust;
    private final PrivateKey serverIdentityKey;
    private final OssV4Signer signer;
    private final ChannelSlot stable;
    private final ChannelSlot preview;
    private final HttpClient http;
    private final Clock clock;
    private final AtomicBoolean disabled = new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private final Map<String, long[]> ipRates = new HashMap<>();
    private LocalDate budgetDay;
    private int dailySignsUsed;

    public static ModpackPrelaunchGateway startFromEnvironment() {
        String envPath = System.getenv(ENV_CONFIG);
        if (envPath == null || envPath.isBlank()) return null;
        try {
            return open(Path.of(envPath));
        } catch (Exception e) {
            System.err.println(
                    "[PixelMC] ModpackPrelaunchGateway disabled ("
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage()
                            + ")");
            return null;
        }
    }

    public static ModpackPrelaunchGateway open(Path configPath) throws Exception {
        GatewayConfig cfg = GatewayConfig.load(configPath);
        return open(
                cfg,
                envRequired(cfg.accessKeyIdEnvironment),
                envRequired(cfg.accessKeySecretEnvironment),
                System.getenv(cfg.securityTokenEnvironment),
                envRequired(cfg.serverIdentityKeyEnvironment),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                Clock.systemUTC());
    }

    static ModpackPrelaunchGateway open(
            GatewayConfig cfg,
            String accessKeyId,
            String accessKeySecret,
            String securityToken,
            String serverIdentityPkcs8Base64,
            HttpClient httpClient,
            Clock clock)
            throws Exception {
        return new ModpackPrelaunchGateway(
                cfg,
                accessKeyId,
                accessKeySecret,
                securityToken,
                serverIdentityPkcs8Base64,
                httpClient,
                clock);
    }

    private ModpackPrelaunchGateway(
            GatewayConfig config,
            String accessKeyId,
            String accessKeySecret,
            String securityToken,
            String serverIdentityPkcs8Base64,
            HttpClient httpClient,
            Clock clock)
            throws Exception {
        this.config = config;
        this.clock = clock;
        this.http = httpClient;
        this.budgetDay = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        this.trust = loadTrust(Path.of(config.trustStore), config.packId);
        this.serverIdentityKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(
                                        Base64.getDecoder().decode(serverIdentityPkcs8Base64)));
        verifyIdentityPair(this.serverIdentityKey, this.trust.serverIdentityKey());
        String expectedHost =
                config.allowLocalTestEndpoint
                                && config.localTestManifestHost != null
                                && !config.localTestManifestHost.isBlank()
                        ? config.localTestManifestHost
                        : config.bucket + ".oss-" + config.region + ".aliyuncs.com";
        this.signer =
                new OssV4Signer(
                        config.bucket,
                        config.region,
                        expectedHost,
                        accessKeyId,
                        accessKeySecret,
                        securityToken,
                        config.allowLocalTestEndpoint);
        this.stable = new ChannelSlot("stable", Path.of(config.activeReleaseFile));
        this.preview =
                config.previewActiveReleaseFile != null
                                && !config.previewActiveReleaseFile.isBlank()
                        ? new ChannelSlot("preview", Path.of(config.previewActiveReleaseFile))
                        : null;
        if (Files.exists(Path.of(config.killSwitchFile))) {
            throw new SecurityException("Kill switch is active");
        }
        refreshAll();
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "PixelMC-Modpack-Gateway");
                            t.setDaemon(true);
                            return t;
                        });
        if (config.activeRefreshSeconds > 0) {
            scheduler.scheduleWithFixedDelay(
                    this::refreshSafely,
                    config.activeRefreshSeconds,
                    config.activeRefreshSeconds,
                    TimeUnit.SECONDS);
        }
        scheduler.scheduleWithFixedDelay(this::checkKillSwitch, 1, 1, TimeUnit.SECONDS);
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "仅支持 POST 请求"));
            return;
        }
        if (exchange.getRequestHeaders().getFirst("Origin") != null) {
            respond(exchange, 403, Map.of("error", "不允许浏览器跨域访问"));
            return;
        }
        String ct =
                Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type"))
                        .orElse("")
                        .toLowerCase(Locale.ROOT);
        if (!ct.startsWith("application/json")) {
            respond(exchange, 415, Map.of("error", "请使用 JSON 请求"));
            return;
        }
        checkKillSwitch();
        if (disabled.get() || !stable.available(config.activeRefreshSeconds)) {
            respond(exchange, 503, Map.of("error", "整合包预启动同步当前暂不可用"));
            return;
        }
        String ip = clientIp(exchange);
        if (!allowIpRate(ip)) {
            respond(exchange, 429, Map.of("error", "同步请求过于频繁，请稍后再试"));
            return;
        }
        byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            respond(exchange, 413, Map.of("error", "请求体过大"));
            return;
        }
        JsonObject req;
        try {
            JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) throw new IllegalArgumentException("需要 JSON 对象");
            req = el.getAsJsonObject();
        } catch (Exception badJson) {
            respond(exchange, 400, Map.of("error", "请求格式无效"));
            return;
        }
        try {
            if (reqLong(req, "schema") != 1) {
                respond(exchange, 400, Map.of("error", "不支持的协议版本"));
                return;
            }
            String packId = reqString(req, "packId");
            if (!config.packId.equals(packId)) {
                respond(exchange, 404, Map.of("error", "未配置的整合包"));
                return;
            }
            String action = reqString(req, "action");
            String requestedChannel =
                    req.has("channel") && req.get("channel").isJsonPrimitive()
                            ? req.get("channel").getAsString()
                            : "stable";
            VerifiedRelease release = resolveRelease(requestedChannel);
            if ("check".equals(action)) {
                handleCheck(exchange, req, release);
            } else if ("signFiles".equals(action)) {
                handleSignFiles(exchange, req, release);
            } else {
                respond(exchange, 400, Map.of("error", "未知同步动作"));
            }
        } catch (BudgetExceeded ex) {
            respond(exchange, 429, Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (SecurityException ex) {
            respond(exchange, 503, Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            respond(exchange, 500, Map.of("error", "签发服务处理失败"));
        }
    }

    private void handleCheck(HttpExchange exchange, JsonObject req, VerifiedRelease release)
            throws Exception {
        String installedDigest =
                req.has("installedDigest") && req.get("installedDigest").isJsonPrimitive()
                        ? req.get("installedDigest").getAsString()
                        : "";
        long installedSeq =
                req.has("installedSequence") && req.get("installedSequence").isJsonPrimitive()
                        ? req.get("installedSequence").getAsLong()
                        : 0L;
        String installedChannel =
                req.has("installedChannel") && req.get("installedChannel").isJsonPrimitive()
                        ? req.get("installedChannel").getAsString()
                        : release.channel();
        boolean upToDate =
                !installedDigest.isBlank()
                        && installedDigest.equals(release.payloadDigest())
                        && installedSeq == release.sequence()
                        && release.channel().equals(installedChannel);

        Instant now = clock.instant();
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", 1);
        payload.addProperty("type", "prelaunch_check");
        payload.addProperty("packId", release.packId());
        payload.addProperty("channel", release.channel());
        payload.addProperty("upToDate", upToDate);
        payload.addProperty("releaseId", release.releaseId());
        payload.addProperty("sequence", release.sequence());
        payload.addProperty("payloadDigest", release.payloadDigest());
        payload.addProperty("issuedAt", now.getEpochSecond());
        if (!upToDate) {
            consumeDailyBudget(1);
            Instant expiry = now.plusSeconds(config.urlLifetimeSeconds);
            URI signedManifest = signer.sign(release.manifestUrl(), now, expiry);
            payload.addProperty("manifestCanonicalUrl", release.manifestUrl().toASCIIString());
            payload.addProperty("manifestUrl", signedManifest.toASCIIString());
            payload.addProperty("expiresAt", expiry.getEpochSecond());
        }
        respondRaw(exchange, 200, signEnvelope(payload));
    }

    private void handleSignFiles(HttpExchange exchange, JsonObject req, VerifiedRelease release)
            throws Exception {
        String releaseId = reqString(req, "releaseId");
        if (!release.releaseId().equals(releaseId)) {
            respond(exchange, 409, Map.of("error", "发布版本已变化，请重新检查更新"));
            return;
        }
        if (!req.has("paths") || !req.get("paths").isJsonArray()) {
            throw new IllegalArgumentException("缺少文件列表");
        }
        JsonArray arr = req.getAsJsonArray("paths");
        if (arr.isEmpty() || arr.size() > config.maxPathsPerRequest) {
            throw new IllegalArgumentException("单次请求文件数量超出限制");
        }
        LinkedHashSet<String> uniquePaths = new LinkedHashSet<>();
        for (JsonElement item : arr) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("文件路径格式无效");
            }
            String p = item.getAsString();
            if (!release.files().containsKey(p)) {
                throw new IllegalArgumentException("文件不在当前已签发清单中: " + p);
            }
            uniquePaths.add(p);
        }
        consumeDailyBudget(uniquePaths.size());

        Instant now = clock.instant();
        Instant expiry = now.plusSeconds(config.urlLifetimeSeconds);
        JsonObject filesObj = new JsonObject();
        for (String path : uniquePaths) {
            URI canonical = release.files().get(path);
            URI signedUrl = signer.sign(canonical, now, expiry);
            JsonObject entry = new JsonObject();
            entry.addProperty("canonicalUrl", canonical.toASCIIString());
            entry.addProperty("url", signedUrl.toASCIIString());
            filesObj.add(path, entry);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", 1);
        payload.addProperty("type", "prelaunch_files");
        payload.addProperty("packId", release.packId());
        payload.addProperty("channel", release.channel());
        payload.addProperty("releaseId", release.releaseId());
        payload.addProperty("sequence", release.sequence());
        payload.addProperty("expiresAt", expiry.getEpochSecond());
        payload.addProperty("issuedAt", now.getEpochSecond());
        payload.add("files", filesObj);
        respondRaw(exchange, 200, signEnvelope(payload));
    }

    private VerifiedRelease resolveRelease(String requestedChannel) {
        checkKillSwitch();
        if (disabled.get()) throw new SecurityException("同步服务已暂停");
        if ("preview".equals(requestedChannel)
                && preview != null
                && preview.available(config.activeRefreshSeconds)) {
            return preview.active;
        }
        if (!stable.available(config.activeRefreshSeconds)) {
            throw new SecurityException("正式版清单暂不可用");
        }
        return stable.active;
    }

    private synchronized boolean allowIpRate(String ip) {
        long now = clock.millis();
        if (ipRates.size() > 4096) {
            ipRates.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000L);
        }
        long[] entry = ipRates.get(ip);
        if (entry == null || now - entry[0] > 60_000L) {
            entry = new long[] {now, 0};
            ipRates.put(ip, entry);
        }
        return ++entry[1] <= config.rateLimitPerMinutePerIp;
    }

    private synchronized void consumeDailyBudget(int count) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            dailySignsUsed = 0;
        }
        if (dailySignsUsed + count > config.dailySignBudget) {
            throw new BudgetExceeded("今日同步签发额度已达上限，请明日再试");
        }
        dailySignsUsed += count;
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    private void checkKillSwitch() {
        if (Files.exists(Path.of(config.killSwitchFile))) {
            disabled.set(true);
        }
    }

    private void refreshSafely() {
        try {
            refreshAll();
        } catch (Exception ignored) {
        }
    }

    public void refreshAll() throws Exception {
        checkKillSwitch();
        if (disabled.get()) return;
        refreshSlot(stable);
        if (preview != null) refreshSlot(preview);
    }

    private void refreshSlot(ChannelSlot slot) throws Exception {
        ReleasePin pin = loadPin(slot.pinPath, slot.channel);
        Instant now = clock.instant();
        URI signedManifestUrl = signer.sign(pin.manifestUrl(), now, now.plusSeconds(120));
        HttpRequest req =
                HttpRequest.newBuilder(signedManifestUrl)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body().length > MAX_MANIFEST_BYTES) {
            throw new IOException("Failed to fetch pinned manifest: HTTP " + resp.statusCode());
        }
        String actualSha = sha256Hex(resp.body());
        if (!actualSha.equals(pin.manifestSha256())) {
            throw new SecurityException("Pinned manifest SHA-256 mismatch");
        }
        VerifiedRelease verified = verifyManifest(resp.body(), pin);
        synchronized (slot) {
            if (slot.highestSequence > 0 && verified.sequence() < slot.highestSequence) {
                throw new SecurityException("Manifest sequence rollback rejected");
            }
            slot.highestSequence = verified.sequence();
            slot.highestRelease = verified.releaseId();
            slot.active = verified;
            slot.lastVerifiedSec = clock.instant().getEpochSecond();
        }
    }

    private VerifiedRelease verifyManifest(byte[] envelopeBytes, ReleasePin pin) throws Exception {
        JsonObject env =
                JsonParser.parseString(new String(envelopeBytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        if (reqLong(env, "schema") != 1) throw new SecurityException("Invalid manifest schema");
        byte[] payloadBytes = Base64.getDecoder().decode(reqString(env, "payload"));
        byte[] sigBytes = Base64.getDecoder().decode(reqString(env, "signature"));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(trust.publisherKey());
        sig.update(payloadBytes);
        if (!sig.verify(sigBytes)) {
            throw new SecurityException("Manifest publisher signature invalid");
        }
        JsonObject payload =
                JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        String packId = reqString(payload, "packId");
        String releaseId = reqString(payload, "releaseId");
        long seq = reqLong(payload, "sequence");
        String channel =
                payload.has("channel") && payload.get("channel").isJsonPrimitive()
                        ? payload.get("channel").getAsString()
                        : "stable";
        if (!trust.packId().equals(packId)
                || !pin.releaseId().equals(releaseId)
                || pin.sequence() != seq
                || !pin.channel().equals(channel)
                || seq < trust.minimumSequence()) {
            throw new SecurityException("Manifest metadata does not match pinned release");
        }
        Map<String, URI> files = new LinkedHashMap<>();
        JsonArray arr = payload.getAsJsonArray("files");
        if (arr == null || arr.isEmpty()) {
            throw new SecurityException("Manifest files array empty");
        }
        for (JsonElement el : arr) {
            JsonObject f = el.getAsJsonObject();
            String path = reqString(f, "path");
            URI url = URI.create(reqString(f, "url"));
            if (!config.allowLocalTestEndpoint
                    && !trust.allowedHosts().contains(url.getHost().toLowerCase(Locale.ROOT))) {
                throw new SecurityException("Untrusted file host: " + url.getHost());
            }
            files.put(path, url);
        }
        return new VerifiedRelease(
                channel,
                packId,
                releaseId,
                seq,
                sha256Hex(payloadBytes),
                pin.manifestUrl(),
                Collections.unmodifiableMap(files));
    }

    private ReleasePin loadPin(Path pinPath, String expectedChannel) throws IOException {
        JsonObject obj =
                JsonParser.parseString(Files.readString(pinPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        if (reqLong(obj, "schema") != 1) throw new IllegalArgumentException("Invalid pin schema");
        String packId = reqString(obj, "packId");
        if (!config.packId.equals(packId)) {
            throw new SecurityException("Pin packId mismatch");
        }
        String channel =
                obj.has("channel") && obj.get("channel").isJsonPrimitive()
                        ? obj.get("channel").getAsString()
                        : "stable";
        if (!expectedChannel.equals(channel)) {
            throw new SecurityException("Pin channel mismatch");
        }
        return new ReleasePin(
                channel,
                packId,
                reqString(obj, "releaseId"),
                reqLong(obj, "sequence"),
                URI.create(reqString(obj, "manifestUrl")),
                reqString(obj, "manifestSha256").toLowerCase(Locale.ROOT));
    }

    private static TrustInfo loadTrust(Path trustPath, String packId) throws Exception {
        JsonObject root =
                JsonParser.parseString(Files.readString(trustPath, StandardCharsets.UTF_8))
                        .getAsJsonObject();
        if (reqLong(root, "schema") != 1) {
            throw new IllegalArgumentException("Unsupported trust schema");
        }
        for (JsonElement el : root.getAsJsonArray("packs")) {
            JsonObject p = el.getAsJsonObject();
            if (!packId.equals(reqString(p, "packId"))) continue;
            PublicKey pub = parsePublicKey(reqString(p, "publicKey"));
            PublicKey srv = parsePublicKey(reqString(p, "serverIdentityPublicKey"));
            Set<String> hosts = new HashSet<>();
            for (JsonElement h : p.getAsJsonArray("allowedHosts")) {
                hosts.add(h.getAsString().toLowerCase(Locale.ROOT));
            }
            return new TrustInfo(
                    packId,
                    pub,
                    srv,
                    URI.create(reqString(p, "manifestUrl")),
                    Set.copyOf(hosts),
                    reqLong(p, "minimumSequence"));
        }
        throw new IllegalArgumentException("Pack not found in trust store: " + packId);
    }

    private static PublicKey parsePublicKey(String b64) throws Exception {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static void verifyIdentityPair(PrivateKey priv, PublicKey pub) throws Exception {
        byte[] probe = "PixelMC-ModpackPrelaunchGateway-probe".getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(priv);
        s.update(probe);
        byte[] sig = s.sign();
        s.initVerify(pub);
        s.update(probe);
        if (!s.verify(sig)) {
            throw new SecurityException("Server identity private key does not match trust store");
        }
    }

    private byte[] signEnvelope(JsonObject payload) throws Exception {
        byte[] payloadBytes = JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(serverIdentityKey);
        s.update(payloadBytes);
        JsonObject env = new JsonObject();
        env.addProperty("schema", 1);
        env.addProperty("packId", config.packId);
        env.addProperty("payload", Base64.getEncoder().encodeToString(payloadBytes));
        env.addProperty("signature", Base64.getEncoder().encodeToString(s.sign()));
        return JSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    private static String clientIp(HttpExchange exchange) {
        InetAddress addr = exchange.getRemoteAddress().getAddress();
        if (addr != null && addr.isLoopbackAddress()) {
            String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return addr == null ? "unknown" : addr.getHostAddress();
    }

    private static String reqString(JsonObject obj, String key) {
        if (!obj.has(key)
                || !obj.get(key).isJsonPrimitive()
                || !obj.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string field: " + key);
        }
        String v = obj.get(key).getAsString();
        if (v.isBlank()) throw new IllegalArgumentException("Blank string field: " + key);
        return v;
    }

    private static long reqLong(JsonObject obj, String key) {
        if (!obj.has(key)
                || !obj.get(key).isJsonPrimitive()
                || !obj.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing numeric field: " + key);
        }
        return obj.get(key).getAsLong();
    }

    private static String envRequired(String name) {
        String v = name == null ? null : System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return v;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void respond(HttpExchange e, int status, Object body) throws IOException {
        respondRaw(e, status, JSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void respondRaw(HttpExchange e, int status, byte[] bytes) throws IOException {
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** OSS V4 GET-only signer byte-identical to AutoModpack-OSS OssUrlSigner. */
    static final class OssV4Signer {
        private final String bucket;
        private final String region;
        private final String host;
        private final String keyId;
        private final String secret;
        private final String securityToken;
        private final boolean allowNon443;

        OssV4Signer(
                String bucket,
                String region,
                String host,
                String keyId,
                String secret,
                String securityToken,
                boolean allowNon443) {
            this.bucket = bucket;
            this.region = region;
            this.host = host;
            this.keyId = keyId;
            this.secret = secret;
            this.securityToken = securityToken == null ? "" : securityToken;
            this.allowNon443 = allowNon443;
        }

        URI sign(URI canonical, Instant signingTime, Instant expiry) {
            if (!"https".equals(canonical.getScheme())
                    || canonical.getRawQuery() != null
                    || canonical.getFragment() != null
                    || canonical.getUserInfo() != null
                    || !host.equalsIgnoreCase(canonical.getHost())
                    || (!allowNon443 && canonical.getPort() != -1 && canonical.getPort() != 443)
                    || canonical.getRawPath() == null
                    || !canonical.getRawPath().startsWith("/")
                    || !canonical.normalize().equals(canonical)) {
                throw new IllegalArgumentException("Invalid OSS object URL");
            }
            long expires = expiry.getEpochSecond() - signingTime.getEpochSecond();
            if (expires < 1 || expires > 300) {
                throw new IllegalArgumentException("OSS URL lifetime exceeds bound");
            }
            String timestamp = OSS_TS.format(signingTime);
            String date = timestamp.substring(0, 8);
            String scope = date + "/" + region + "/oss/aliyun_v4_request";
            TreeMap<String, String> query = new TreeMap<>();
            query.put("x-oss-signature-version", "OSS4-HMAC-SHA256");
            query.put("x-oss-date", timestamp);
            query.put("x-oss-expires", Long.toString(expires));
            query.put("x-oss-credential", keyId + "/" + scope);
            if (!securityToken.isEmpty()) query.put("x-oss-security-token", securityToken);
            StringBuilder cq = new StringBuilder();
            for (var e : query.entrySet()) {
                if (cq.length() > 0) cq.append('&');
                cq.append(encode(e.getKey(), false))
                        .append('=')
                        .append(encode(e.getValue(), false));
            }
            String canonicalQuery = cq.toString();
            String canonicalRequest =
                    "GET\n"
                            + encode("/" + bucket + canonical.getPath(), true)
                            + "\n"
                            + canonicalQuery
                            + "\n\n\nUNSIGNED-PAYLOAD";
            try {
                String hash =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        canonicalRequest.getBytes(
                                                                StandardCharsets.UTF_8)));
                String toSign = "OSS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + hash;
                byte[] signingKey =
                        hmac(("aliyun_v4" + secret).getBytes(StandardCharsets.UTF_8), date);
                signingKey = hmac(signingKey, region);
                signingKey = hmac(signingKey, "oss");
                signingKey = hmac(signingKey, "aliyun_v4_request");
                String signature = HexFormat.of().formatHex(hmac(signingKey, toSign));
                return URI.create(
                        canonical.toASCIIString()
                                + "?"
                                + canonicalQuery
                                + "&x-oss-signature="
                                + signature);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("OSS signing unavailable", e);
            }
        }

        private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String encode(String value, boolean slashSafe) {
            StringBuilder out = new StringBuilder();
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                int c = b & 255;
                if (c >= 'a' && c <= 'z'
                        || c >= 'A' && c <= 'Z'
                        || c >= '0' && c <= '9'
                        || c == '-'
                        || c == '_'
                        || c == '.'
                        || c == '~'
                        || slashSafe && c == '/') {
                    out.append((char) c);
                } else {
                    out.append(String.format("%%%02X", c));
                }
            }
            return out.toString();
        }
    }
}
