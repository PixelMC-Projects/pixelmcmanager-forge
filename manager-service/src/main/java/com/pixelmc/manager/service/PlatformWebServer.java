package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.JSON;
import cn.pixelmc.memorialtitles.web.WebConfig;


import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Embedded same-origin administrator console. SQL work stays in TitleService's bounded worker. */
public final class PlatformWebServer implements AutoCloseable {
  public interface Host {
    Set<UUID> online();
    default void requestAvatar(UUID id,String nonce){}
    boolean platformAdmin(UUID id);
    String profileName(UUID id);
    default boolean bridgeAuthority(HttpExchange e,String path){return false;}
    default boolean publicRoute(HttpExchange e,String path)throws Exception{return false;}
    default int httpThreads(){return 8;}
    boolean adminRoute(HttpExchange e,String path,UUID actor,java.util.function.BooleanSupplier authorized)throws Exception;
  }

  private record Session(
      String username, UUID player, long accountVersion, String csrf, long expires) {}

  private record Rate(long start,int count){}

  private static final class Failure extends RuntimeException {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final Host host;
  private final WebConfig config;
  public final PlatformAccounts accounts;
  public final PlatformAvatars avatars;
  private final HttpServer http;
  private final ExecutorService executor;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, Rate> rates = new HashMap<>();
  private static final SecureRandom RANDOM = new SecureRandom();

  public PlatformWebServer(
      Host host, WebConfig config, Path accountFolder)
      throws Exception {
    this.host = host;
    this.config = config;
    accounts = new PlatformAccounts(accountFolder);
    avatars = new PlatformAvatars(accountFolder.resolve("avatars"), host::requestAvatar);
    http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 32);
    executor =
        new ThreadPoolExecutor(
            Math.max(4,Math.min(16,host.httpThreads())),
            Math.max(4,Math.min(16,host.httpThreads())),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
              Thread t = new Thread(r, "PixelMC-Platform-Web");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    http.setExecutor(executor);
    http.createContext("/", this::handle);
    http.start();
  }

  public int port() {
    return http.getAddress().getPort();
  }

  public String address() {
    return config.publicUrl().isEmpty() ? "http://<服务器IP>:" + port() : config.publicUrl();
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private synchronized void rate(String key, int limit) {
    long now = System.currentTimeMillis();
    if (rates.size() > 4096) rates.entrySet().removeIf(e -> now - e.getValue().start() > 60000);
    if (rates.size() > 8192) throw new Failure(429, "请求过多，请稍后重试");
    Rate old = rates.get(key);
    Rate next =
        old == null || now - old.start() > 60000
            ? new Rate(now, 1)
            : new Rate(old.start(), old.count() + 1);
    rates.put(key, next);
    if (next.count() > limit) throw new Failure(429, "请求过于频繁，请一分钟后再试");
  }

  private void handle(HttpExchange exchange) {
    try {
      headers(exchange);
      String path = exchange.getRequestURI().getPath();
      if (!validHost(exchange.getRequestHeaders().getFirst("Host")) && !host.bridgeAuthority(exchange,path))
        throw new Failure(403, "访问地址未允许，请使用服务器IP和端口");
      if (host.publicRoute(exchange, path)) return;
      if (path.startsWith("/api/")) {
        rate("api:" + exchange.getRemoteAddress().getAddress().getHostAddress(), 600);
        if (!Set.of("GET", "POST").contains(exchange.getRequestMethod()))
          throw new Failure(405, "不支持的请求方法");
        if (exchange.getRequestMethod().equals("POST")) {
          String origin = exchange.getRequestHeaders().getFirst("Origin");
          String local = "http://" + exchange.getRequestHeaders().getFirst("Host");
          if (origin == null
              || !(origin.equals(local)
                  || origin.equals(config.publicUrl())
                  || config.extraOrigins().contains(origin)))
            throw new Failure(403, "请求来源不匹配，请从管理台本身操作");
          if (!Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type"))
              .orElse("")
              .toLowerCase(Locale.ROOT)
              .startsWith("application/json")) throw new Failure(415, "请使用JSON请求");
        }
        api(exchange, path);
      } else {
        if (!exchange.getRequestMethod().equals("GET")) throw new Failure(405, "不支持的请求方法");
        assets(exchange, path);
      }
    } catch (Throwable error) {
      // Exchange may already be closed after a client disconnect; never expose stack
      // traces/secrets.
      try {
        Throwable cause = error;
        while (cause.getCause() != null
            && (cause instanceof CompletionException
                || cause instanceof ExecutionException
                || cause instanceof java.lang.reflect.InvocationTargetException))
          cause = cause.getCause();
        int code =
            cause instanceof Failure f
                ? f.status
                : cause instanceof SecurityException
                    ? 403
                    : cause instanceof IllegalArgumentException
                            || cause instanceof JsonParseException
                        ? 400
                        : cause instanceof TimeoutException ? 504 : cause instanceof java.sql.SQLException ? 503 : 409;
        String message =
            cause instanceof java.sql.SQLException
                ? "数据库连接或事务失败，请检查服务端数据库状态"
                : cause instanceof Failure
                        || cause instanceof IllegalArgumentException
                        || cause instanceof IllegalStateException
                        || cause instanceof SecurityException
                    ? Objects.toString(cause.getMessage(), "请求无效")
                    : "操作失败，请查看服务端日志或稍后重试";
        json(exchange, code, Map.of("error", message));
      } catch (Exception ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  private void headers(HttpExchange e) {
    e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    e.getResponseHeaders().set("X-Frame-Options", "DENY");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src"
                + " 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';"
                + " object-src 'none'; base-uri 'none'; form-action 'self'");
  }

  private boolean validHost(String authority) {
    if (authority == null
        || authority.contains("@")
        || authority.contains("/")
        || authority.contains("\\")) return false;
    if (!config.publicUrl().isEmpty()
        && authority.equalsIgnoreCase(URI.create(config.publicUrl()).getRawAuthority()))
      return true;
    try {
      URI uri = URI.create("http://" + authority);
      String host = uri.getHost();
      if (uri.getPort() != port() || host == null) return false;
      if (host.equals("localhost") || host.equals("[::1]")) return true;
      if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}"))
        return Arrays.stream(host.split("\\.")).allMatch(n -> Integer.parseInt(n) <= 255);
      return host.matches("\\[[a-fA-F0-9:]+\\]");
    } catch (Exception e) {
      return false;
    }
  }

  private void api(HttpExchange e, String path) throws Exception {
    boolean post = e.getRequestMethod().equals("POST");
    if (path.equals("/api/login") && post) {
      rate("login:" + e.getRemoteAddress().getAddress().getHostAddress(), 10);
      JsonObject body = body(e);
      PlatformAccounts.Account account =
          accounts.verify(text(body, "username", 64), text(body, "password", 256));
      if (account == null || !(host.platformAdmin(account.player())))
        throw new Failure(401, "用户名、密码或管理授权无效");
      long now = System.currentTimeMillis();
      sessions.entrySet().removeIf(entry -> entry.getValue().expires() < now);
      if (sessions.size() >= 512) throw new Failure(503, "会话数量已满，请稍后登录");
      String raw = token();
      Session session =
          new Session(
              account.username(),
              account.player(),
              account.version(),
              token(),
              now + config.sessionMinutes() * 60000L);
      sessions.put(digest(raw), session);
      cookie(e, raw, config.sessionMinutes() * 60);
      json(e, 200, me(session));
      return;
    }
    Session session = session(e);
    if (post
        && !MessageDigest.isEqual(
            session.csrf().getBytes(StandardCharsets.UTF_8),
            Optional.ofNullable(e.getRequestHeaders().getFirst("X-CSRF-Token"))
                .orElse("")
                .getBytes(StandardCharsets.UTF_8))) throw new Failure(403, "会话验证失败，请重新登录");
    if (path.equals("/api/logout") && post) {
      sessions.remove(sessionKey(e));
      cookie(e, "", 0);
      json(e, 200, Map.of("ok", true));
      return;
    }
    PlatformAccounts.Account account = accounts.get(session.username());
    if (account == null || account.version() != session.accountVersion())
      throw new Failure(401, "密码已变更或账号已移除，请重新登录");
    if(!host.platformAdmin(session.player()))throw new Failure(403,"管理授权已撤销");
    if (path.equals("/api/me") && !post) {
      json(e, 200, me(session));
      return;
    }
    if (path.equals("/api/password") && post) {
      JsonObject b = body(e);
      if (!accounts.changePassword(
          session.username(), text(b, "current", 256), text(b, "password", 128)))
        throw new Failure(400, "当前密码不正确");
      sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(session.username()));
      cookie(e, "", 0);
      json(e, 200, Map.of("ok", true));
      return;
    }
    if (host.adminRoute(e, path, session.player(), () -> {
      PlatformAccounts.Account current=accounts.get(session.username());
      return current!=null && current.version()==session.accountVersion()
          && session.expires()>System.currentTimeMillis() && sessions.containsValue(session)
          && (host.platformAdmin(session.player()));
    })) return;
    throw new Failure(404,"接口不存在");
  }
  private Map<String,Object> me(Session session){return Map.of("username",session.username(),"player",session.player(),"playerName",host.profileName(session.player()),"csrf",session.csrf(),"expiresAt",session.expires());}

  private Session session(HttpExchange e) {
    Session s = sessions.get(sessionKey(e));
    if (s == null || s.expires() < System.currentTimeMillis()) {
      sessions.remove(sessionKey(e));
      throw new Failure(401, "请登录管理控制台");
    }
    return s;
  }

  private String sessionKey(HttpExchange e) {
    for (String pair :
        Optional.ofNullable(e.getRequestHeaders().getFirst("Cookie")).orElse("").split(";")) {
      String value = pair.trim();
      String prefix = "MTSESSION_" + port() + "=";
      if (value.startsWith(prefix)) {
        String raw = value.substring(prefix.length());
        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\""))
          raw = raw.substring(1, raw.length() - 1);
        return digest(raw);
      }
    }
    return "";
  }

  private void cookie(HttpExchange e, String value, int seconds) {
    e.getResponseHeaders()
        .add(
            "Set-Cookie",
            "MTSESSION_"
                + port()
                + "="
                + value
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + seconds
                + (config.publicUrl().startsWith("https://") ? "; Secure" : ""));
  }

  private static Map<String, String> query(HttpExchange e) {
    Map<String, String> out = new HashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw != null)
      for (String pair : raw.split("&")) {
        String[] v = pair.split("=", 2);
        out.put(
            URLDecoder.decode(v[0], StandardCharsets.UTF_8),
            v.length == 1 ? "" : URLDecoder.decode(v[1], StandardCharsets.UTF_8));
      }
    return out;
  }

  private static int integer(Map<String, String> q, String name, int fallback, int min, int max) {
    int value = Integer.parseInt(q.getOrDefault(name, Integer.toString(fallback)));
    if (value < min || value > max) throw new IllegalArgumentException("分页范围无效");
    return value;
  }

  private static String text(JsonObject b, String key, int max) {
    if (b == null
        || !b.has(key)
        || !b.get(key).isJsonPrimitive()
        || !b.getAsJsonPrimitive(key).isString())
      throw new IllegalArgumentException("缺少文字字段：" + key);
    String value = b.get(key).getAsString();
    if(value.length()>max)throw new IllegalArgumentException("文字过长");
    return value;
  }

  private static JsonObject body(HttpExchange e) throws Exception {
    byte[] bytes = e.getRequestBody().readNBytes(8 * 1024 * 1024 + 1);
    if (bytes.length > 8 * 1024 * 1024) throw new Failure(413, "请求超过8MiB");
    String raw = new String(bytes, StandardCharsets.UTF_8);
    int depth = 0;
    boolean string = false, escape = false;
    for (char c : raw.toCharArray()) {
      if (string) {
        if (escape) escape = false;
        else if (c == '\\') escape = true;
        else if (c == '\"') string = false;
      } else if (c == '\"') string = true;
      else if (c == '{' || c == '[') {
        if (++depth > 32) throw new IllegalArgumentException("JSON嵌套过深");
      } else if (c == '}' || c == ']') depth--;
    }
    return JsonParser.parseString(raw).getAsJsonObject();
  }

  private void assets(HttpExchange e, String path) throws Exception {
    if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0)
      throw new Failure(404, "页面不存在");
    if (path.equals("/") || !path.substring(path.lastIndexOf('/') + 1).contains("."))
      path = "/index.html";
    String type =
        path.endsWith(".html")
            ? "text/html; charset=utf-8"
            : path.endsWith(".js")
                ? "text/javascript; charset=utf-8"
                : path.endsWith(".css")
                    ? "text/css; charset=utf-8"
                    : path.endsWith(".woff2")
                        ? "font/woff2"
                        : path.endsWith(".svg")
                            ? "image/svg+xml"
                            : path.endsWith(".png") ? "image/png" : "application/octet-stream";
    try (InputStream stream = PlatformWebServer.class.getResourceAsStream("/web" + path)) {
      if (stream == null) throw new Failure(404, "网页资源未找到，请使用包含网页的完整模组JAR");
      if (path.startsWith("/assets/"))
        e.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
      bytes(e, 200, stream.readAllBytes(), type);
    }
  }

  private static void json(HttpExchange e, int status, Object value) throws IOException {
    bytes(
        e,
        status,
        JSON.toJson(value).getBytes(StandardCharsets.UTF_8),
        "application/json; charset=utf-8");
  }

  private static void bytes(HttpExchange e, int status, byte[] value, String type)
      throws IOException {
    e.getResponseHeaders().set("Content-Type", type);
    e.sendResponseHeaders(status, value.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(value);
    }
  }

  @Override
  public void close() {
    http.stop(1);
    executor.shutdownNow();
    avatars.close();
    sessions.clear();
  }
}
