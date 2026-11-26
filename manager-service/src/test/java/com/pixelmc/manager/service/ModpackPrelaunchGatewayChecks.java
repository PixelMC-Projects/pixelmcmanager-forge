package com.pixelmc.manager.service;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained verification suite for ModpackPrelaunchGateway. */
public final class ModpackPrelaunchGatewayChecks {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("modpack-gateway-checks-");
        try {
            runAll(tmp);
            System.out.println(
                    "PASS: ModpackPrelaunchGateway OSS-V4 signing, Ed25519 verification, upToDate"
                            + " fast-path, signFiles allowlist, IP rate limit, daily budget, kill"
                            + " switch");
        } finally {
            deleteTree(tmp);
        }
    }

    private static void runAll(Path tmp) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pubKp = gen.generateKeyPair();
        KeyPair srvKp = gen.generateKeyPair();

        String packId = "pixelmc-test-pack";
        String host = "test-bucket.oss-cn-hangzhou.aliyuncs.com";

        JsonObject manifestPayload = new JsonObject();
        manifestPayload.addProperty("schema", 1);
        manifestPayload.addProperty("packId", packId);
        manifestPayload.addProperty("channel", "stable");
        manifestPayload.addProperty("releaseId", "rel-10");
        manifestPayload.addProperty("sequence", 10);
        manifestPayload.addProperty("consentProtocolVersion", 1);
        manifestPayload.addProperty("managementScopeVersion", "scope-1");
        manifestPayload.addProperty("createdAt", "2026-11-26T00:00:00Z");
        JsonArray files = new JsonArray();
        JsonObject f1 = new JsonObject();
        f1.addProperty("path", "mods/pixelmc-hub.jar");
        f1.addProperty("size", 12);
        f1.addProperty("sha256", sha256("hub-contents".getBytes(StandardCharsets.UTF_8)));
        f1.addProperty(
                "url",
                "https://" + host + "/packs/test/releases/rel-10/files/mods/pixelmc-hub.jar");
        files.add(f1);
        manifestPayload.add("files", files);

        byte[] manifestPayloadBytes = JSON.toJson(manifestPayload).getBytes(StandardCharsets.UTF_8);
        String payloadDigest = sha256(manifestPayloadBytes);
        byte[] manifestEnvelopeBytes = signEd25519(manifestPayloadBytes, pubKp.getPrivate(), null);
        String manifestSha256 = sha256(manifestEnvelopeBytes);

        Path trustPath = tmp.resolve("trust.json");
        JsonObject trustPack = new JsonObject();
        trustPack.addProperty("packId", packId);
        trustPack.addProperty("identity", "Test Server");
        trustPack.addProperty("publicKey", b64(pubKp.getPublic().getEncoded()));
        trustPack.addProperty("serverIdentityPublicKey", b64(srvKp.getPublic().getEncoded()));
        trustPack.addProperty("manifestUrl", "https://" + host + "/packs/test/active.json");
        JsonArray hosts = new JsonArray();
        hosts.add(host);
        trustPack.add("allowedHosts", hosts);
        JsonArray roots = new JsonArray();
        roots.add("mods");
        trustPack.add("allowedRoots", roots);
        trustPack.addProperty("minimumSequence", 1);
        JsonObject trustRoot = new JsonObject();
        trustRoot.addProperty("schema", 1);
        JsonArray packs = new JsonArray();
        packs.add(trustPack);
        trustRoot.add("packs", packs);
        Files.writeString(trustPath, JSON.toJson(trustRoot), StandardCharsets.UTF_8);

        Path pinPath = tmp.resolve("active-pin.json");
        JsonObject pin = new JsonObject();
        pin.addProperty("schema", 1);
        pin.addProperty("packId", packId);
        pin.addProperty("channel", "stable");
        pin.addProperty("releaseId", "rel-10");
        pin.addProperty("sequence", 10);
        pin.addProperty(
                "manifestUrl", "https://" + host + "/packs/test/releases/rel-10/manifest.json");
        pin.addProperty("manifestSha256", manifestSha256);
        Files.writeString(pinPath, JSON.toJson(pin), StandardCharsets.UTF_8);

        Path killSwitch = tmp.resolve("kill-switch");

        // Local stub HttpClient that intercepts the OSS manifest fetch and returns
        // manifestEnvelopeBytes
        AtomicReference<URI> fetchedManifestUri = new AtomicReference<>();
        HttpClient stubHttp =
                new StubHttpClient(
                        req -> {
                            fetchedManifestUri.set(req.uri());
                            return manifestEnvelopeBytes;
                        });

        ModpackPrelaunchGateway.GatewayConfig cfg = new ModpackPrelaunchGateway.GatewayConfig();
        cfg.enabled = true;
        cfg.packId = packId;
        cfg.trustStore = trustPath.toAbsolutePath().toString();
        cfg.activeReleaseFile = pinPath.toAbsolutePath().toString();
        cfg.bucket = "test-bucket";
        cfg.region = "cn-hangzhou";
        cfg.killSwitchFile = killSwitch.toAbsolutePath().toString();
        cfg.urlLifetimeSeconds = 120;
        cfg.rateLimitPerMinutePerIp = 5;
        cfg.dailySignBudget = 3;
        cfg.maxPathsPerRequest = 10;

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);

        try (ModpackPrelaunchGateway gw =
                ModpackPrelaunchGateway.open(
                        cfg,
                        "TEST-KEY-ID",
                        "test-secret-value",
                        null,
                        b64(srvKp.getPrivate().getEncoded()),
                        stubHttp,
                        fixedClock)) {
            check(
                    fetchedManifestUri.get() != null
                            && fetchedManifestUri
                                    .get()
                                    .getRawQuery()
                                    .contains("x-oss-signature-version=OSS4-HMAC-SHA256"),
                    "Manifest fetch must use OSS4 signed URL");

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/modpack/prelaunch",
                    ex -> {
                        try {
                            gw.handle(ex);
                        } finally {
                            ex.close();
                        }
                    });
            server.start();
            try {
                URI endpoint =
                        URI.create(
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/api/modpack/prelaunch");
                HttpClient client = HttpClient.newHttpClient();

                // 1. upToDate fast path (0 budget consumed)
                var rUpToDate =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        10,
                                        "installedDigest",
                                        payloadDigest),
                                "10.0.0.1");
                check(rUpToDate.statusCode() == 200, "upToDate status 200");
                JsonObject p1 = verifyServerResponse(rUpToDate.body(), srvKp.getPublic(), packId);
                check(p1.get("upToDate").getAsBoolean(), "must be upToDate=true");
                check(!p1.has("manifestUrl"), "upToDate must not sign manifestUrl");

                // 2. Outdated check -> returns signed manifestUrl (budget 1/3)
                var rOutdated =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        0,
                                        "installedDigest",
                                        ""),
                                "10.0.0.2");
                check(rOutdated.statusCode() == 200, "outdated check status 200");
                JsonObject p2 = verifyServerResponse(rOutdated.body(), srvKp.getPublic(), packId);
                check(!p2.get("upToDate").getAsBoolean(), "must be upToDate=false");
                check(
                        p2.get("manifestUrl").getAsString().contains("x-oss-signature="),
                        "manifestUrl signed");

                // 3. Reject unlisted path in signFiles
                var rBadPath =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "signFiles",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "releaseId",
                                        "rel-10",
                                        "paths",
                                        List.of("mods/secret.jar")),
                                "10.0.0.2");
                check(rBadPath.statusCode() == 400, "unlisted path must return 400");

                // 4. Valid signFiles (budget 2/3)
                var rSign =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "signFiles",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "releaseId",
                                        "rel-10",
                                        "paths",
                                        List.of("mods/pixelmc-hub.jar")),
                                "10.0.0.2");
                check(rSign.statusCode() == 200, "valid signFiles status 200");
                JsonObject p3 = verifyServerResponse(rSign.body(), srvKp.getPublic(), packId);
                check(
                        p3.getAsJsonObject("files")
                                .getAsJsonObject("mods/pixelmc-hub.jar")
                                .get("url")
                                .getAsString()
                                .contains("x-oss-signature="),
                        "signed file URL returned");

                // 5. Consume remaining budget (3/3), then hit daily budget cap (429)
                var rBudget3 =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        0),
                                "10.0.0.3");
                check(rBudget3.statusCode() == 200, "budget 3/3 allowed");
                var rBudget4 =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        0),
                                "10.0.0.4");
                check(rBudget4.statusCode() == 429, "daily budget exceeded must return 429");

                // 6. Per-IP rate limit (IP 10.0.0.5 sends 6 upToDate checks, 6th returns 429)
                for (int i = 0; i < 5; i++) {
                    var ok =
                            post(
                                    client,
                                    endpoint,
                                    Map.of(
                                            "schema",
                                            1,
                                            "action",
                                            "check",
                                            "packId",
                                            packId,
                                            "channel",
                                            "stable",
                                            "installedSequence",
                                            10,
                                            "installedDigest",
                                            payloadDigest),
                                    "10.0.0.5");
                    check(ok.statusCode() == 200, "ip rate attempt " + i);
                }
                var throttled =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        10,
                                        "installedDigest",
                                        payloadDigest),
                                "10.0.0.5");
                check(throttled.statusCode() == 429, "6th request from same IP must return 429");

                // 7. Kill switch -> 503
                Files.writeString(killSwitch, "stop");
                var killed =
                        post(
                                client,
                                endpoint,
                                Map.of(
                                        "schema",
                                        1,
                                        "action",
                                        "check",
                                        "packId",
                                        packId,
                                        "channel",
                                        "stable",
                                        "installedSequence",
                                        10,
                                        "installedDigest",
                                        payloadDigest),
                                "10.0.0.6");
                check(killed.statusCode() == 503, "kill switch must return 503");
            } finally {
                server.stop(0);
            }
        }

        // 8. Offsite backup status parsing (missing / ok / failed / stale >24h)
        long nowMs = Instant.parse("2026-11-26T16:00:00Z").toEpochMilli();
        Path statusFile = tmp.resolve("offsite-status.json");
        JsonObject missing = ManagerController.readOffsiteBackupStatus(statusFile, nowMs);
        check(!missing.get("configured").getAsBoolean(), "missing status should be unconfigured");
        check(missing.get("healthy").getAsBoolean(), "unconfigured should not alarm");

        Files.writeString(
                statusFile,
                "{\"schema\":1,\"state\":\"ok\",\"last_success\":\"2026-11-26T12:20:00Z\",\"error\":null}");
        JsonObject okStatus = ManagerController.readOffsiteBackupStatus(statusFile, nowMs);
        check(
                okStatus.get("configured").getAsBoolean()
                        && okStatus.get("healthy").getAsBoolean()
                        && !okStatus.has("warning"),
                "recent ok backup should be healthy");

        Files.writeString(
                statusFile,
                "{\"schema\":1,\"state\":\"failed\",\"last_success\":\"2026-11-23T04:20:00Z\",\"error\":\"Unrecognized"
                    + " running Manager service\"}");
        JsonObject failedStatus = ManagerController.readOffsiteBackupStatus(statusFile, nowMs);
        check(
                failedStatus.get("configured").getAsBoolean()
                        && !failedStatus.get("healthy").getAsBoolean()
                        && failedStatus.get("warning").getAsString().contains("数据库异地备份失败"),
                "failed backup should produce warning");

        Files.writeString(
                statusFile,
                "{\"schema\":1,\"state\":\"ok\",\"last_success\":\"2026-11-24T10:00:00Z\",\"error\":null}");
        JsonObject staleStatus = ManagerController.readOffsiteBackupStatus(statusFile, nowMs);
        check(
                staleStatus.get("configured").getAsBoolean()
                        && !staleStatus.get("healthy").getAsBoolean()
                        && staleStatus.get("stale").getAsBoolean()
                        && staleStatus.get("warning").getAsString().contains("未成功"),
                "stale >24h backup should produce warning");
    }

    private static HttpResponse<byte[]> post(
            HttpClient client, URI uri, Map<String, Object> body, String realIp) throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .header("X-Real-IP", realIp)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)))
                        .build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonObject verifyServerResponse(byte[] raw, PublicKey srvPub, String packId)
            throws Exception {
        JsonObject env =
                JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        check(packId.equals(env.get("packId").getAsString()), "envelope packId");
        byte[] payload = Base64.getDecoder().decode(env.get("payload").getAsString());
        byte[] sig = Base64.getDecoder().decode(env.get("signature").getAsString());
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(srvPub);
        s.update(payload);
        check(s.verify(sig), "envelope Ed25519 signature valid");
        return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static byte[] signEd25519(byte[] payload, PrivateKey key, String packId)
            throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(payload);
        JsonObject env = new JsonObject();
        env.addProperty("schema", 1);
        if (packId != null) env.addProperty("packId", packId);
        env.addProperty("payload", b64(payload));
        env.addProperty("signature", b64(s.sign()));
        return JSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void check(boolean cond, String label) {
        if (!cond) throw new AssertionError("FAILED: " + label);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        }
    }

    /** Minimal stub HttpClient for offline unit testing. */
    private static final class StubHttpClient extends HttpClient {
        interface Handler {
            byte[] handle(HttpRequest req);
        }

        private final Handler handler;

        StubHttpClient(Handler handler) {
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            byte[] body = handler.handle(request);
            return (HttpResponse<T>)
                    new HttpResponse<byte[]>() {
                        @Override
                        public int statusCode() {
                            return 200;
                        }

                        @Override
                        public HttpRequest request() {
                            return request;
                        }

                        @Override
                        public Optional<HttpResponse<byte[]>> previousResponse() {
                            return Optional.empty();
                        }

                        @Override
                        public HttpHeaders headers() {
                            return HttpHeaders.of(Map.of(), (a, b) -> true);
                        }

                        @Override
                        public byte[] body() {
                            return body;
                        }

                        @Override
                        public Optional<javax.net.ssl.SSLSession> sslSession() {
                            return Optional.empty();
                        }

                        @Override
                        public URI uri() {
                            return request.uri();
                        }

                        @Override
                        public Version version() {
                            return Version.HTTP_1_1;
                        }
                    };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }
}
