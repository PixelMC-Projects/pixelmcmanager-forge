package com.pixelmc.manager.service;


import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/** Mojang-first heads, with an explicitly requested client fallback. Never gates game login. */
public final class PlatformAvatars implements AutoCloseable {
  @FunctionalInterface
  interface Fetcher {
    byte[] fetch(URI uri, int limit) throws Exception;
  }

  private final Path folder;
  private final Path clientFolder;
  private final Fetcher fetcher;
  private final BiConsumer<UUID, String> fallback;

  private record Ticket(String nonce, long expires) {}

  private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();
  private final ExecutorService worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          r -> {
            var t = new Thread(r, "PixelMC-Avatars");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final Set<UUID> requested = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> retryAfter = new ConcurrentHashMap<>();
  private final Map<UUID, Long> versions = new ConcurrentHashMap<>();
  private final Map<UUID, Long> generations = new ConcurrentHashMap<>();
  private final Object cacheLock = new Object();
  private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public PlatformAvatars(Path folder) {
    this(folder, null, (id, nonce) -> {});
  }

  public PlatformAvatars(Path folder, BiConsumer<UUID, String> fallback) {
    this(folder, null, fallback);
  }

  PlatformAvatars(Path folder, Fetcher fetcher) {
    this(folder, fetcher, (id, nonce) -> {});
  }

  PlatformAvatars(Path folder, Fetcher fetcher, BiConsumer<UUID, String> fallback) {
    this.folder = folder.resolve("mojang");
    this.clientFolder = folder.resolve("client");
    this.fetcher = fetcher == null ? this::fetch : fetcher;
    this.fallback = fallback;
  }

  private Path selected(UUID id) {
    Path client = clientFolder.resolve(id + ".png");
    return Files.isRegularFile(clientFolder.resolve(id + ".active")) && Files.isRegularFile(client)
        ? client
        : folder.resolve(id + ".png");
  }

  public long version(UUID id) {
    return versions.computeIfAbsent(
        id,
        key -> {
          try {
            return Files.getLastModifiedTime(selected(key)).toMillis();
          } catch (IOException e) {
            return 0L;
          }
        });
  }

  public byte[] head(UUID id) throws Exception {
    Path file = selected(id);
    if (Files.isRegularFile(file)) return Files.readAllBytes(file);
    resolve(id, false);
    return defaultHead(id);
  }

  private static byte[] defaultHead(UUID id) throws IOException {
    try (InputStream input =
        PlatformAvatars.class.getResourceAsStream(
            "/web/default-heads/" + Math.floorMod(id.hashCode(), 18) + ".png")) {
      if (input == null) throw new IOException("Default head resource missing");
      return input.readAllBytes();
    }
  }

  public void login(UUID id) {
    blocked.remove(id);
    tickets.remove(id);
    resolve(id, true);
  }

  public void logout(UUID id) {
    tickets.remove(id);
  }

  private void resolve(UUID id, boolean login) {
    if (blocked.contains(id)) return;
    if (!login && (version(id) > 0 || System.currentTimeMillis() < retryAfter.getOrDefault(id, 0L)))
      return;
    if (id.version() != 4) {
      if (login) requestFallback(id);
      return;
    }
    if (!requested.add(id)) return;
    long generation = generations.getOrDefault(id, 0L);
    enqueue(
        id,
        () -> {
          boolean success = false;
          try {
            String compactId = id.toString().replace("-", "");
            var profile =
                JsonParser.parseString(
                        new String(
                            fetcher.fetch(
                                URI.create(
                                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                                        + compactId),
                                65536),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!compactId.equalsIgnoreCase(profile.get("id").getAsString()))
              throw new IOException("Mojang profile identity mismatch");
            String encoded = null;
            for (var property : profile.getAsJsonArray("properties")) {
              var p = property.getAsJsonObject();
              if (p.get("name").getAsString().equals("textures")) {
                encoded = p.get("value").getAsString();
                break;
              }
            }
            if (encoded == null || encoded.length() > 16384) return;
            var data =
                JsonParser.parseString(
                        new String(
                            Base64.getDecoder().decode(encoded),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (data.has("profileId")
                && !compactId.equalsIgnoreCase(data.get("profileId").getAsString()))
              throw new IOException("Mojang texture identity mismatch");
            var textures = data.getAsJsonObject("textures");
            if (!textures.has("SKIN")) {
              if (persist(id, decode(defaultHead(id), 8, true), false, generation)) {
                tickets.remove(id);
                success = true;
              }
              return;
            }
            String raw = textures.getAsJsonObject("SKIN").get("url").getAsString();
            URI uri = URI.create(raw);
            if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || !uri.getPath().matches("/texture/[a-fA-F0-9]{32,128}")) return;
            BufferedImage skin =
                decode(
                    fetcher.fetch(
                        URI.create("https://textures.minecraft.net" + uri.getPath()), 1048576),
                    1024,
                    false);
            if (persist(id, compose(skin), false, generation)) {
              tickets.remove(id);
              success = true;
            }
          } catch (Exception ignored) {
            /* Keep the last head when skin services are unavailable. */
          } finally {
            if (!success && generations.getOrDefault(id, 0L) == generation) requestFallback(id);
            retryAfter.put(id, System.currentTimeMillis() + 60000);
            requested.remove(id);
          }
        });
  }

  private void requestFallback(UUID id) {
    if (blocked.contains(id)) return;
    Ticket ticket = new Ticket(UUID.randomUUID().toString(), System.currentTimeMillis() + 60000);
    tickets.put(id, ticket);
    try {
      fallback.accept(id, ticket.nonce());
    } catch (RuntimeException ignored) {
      tickets.remove(id, ticket);
    }
  }

  public void uploadFallback(UUID id, String nonce, String encoded) {
    Ticket ticket = tickets.get(id);
    if (ticket == null
        || !ticket.nonce().equals(nonce)
        || ticket.expires() < System.currentTimeMillis()
        || encoded.length() > 8192) return;
    long generation = generations.getOrDefault(id, 0L);
    enqueue(
        id,
        () -> {
          if (tickets.get(id) != ticket || ticket.expires() < System.currentTimeMillis()) return;
          try {
            BufferedImage head = decode(Base64.getDecoder().decode(encoded), 8, true);
            persist(id, head, true, generation);
            tickets.remove(id, ticket);
          } catch (Exception ignored) {
            /* Keep the current cache if the requested fallback is unavailable or invalid. */
          }
        });
  }

  private byte[] fetch(URI uri, int limit) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var input = response.body()) {
      if (response.statusCode() != 200) throw new IOException("Skin unavailable");
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IOException("Skin too large");
      return bytes;
    }
  }

  private void enqueue(UUID id, Runnable task) {
    try {
      worker.execute(task);
    } catch (RejectedExecutionException ignored) {
      requested.remove(id);
    }
  }

  public static BufferedImage decode(byte[] bytes, int max, boolean thumbnail) throws IOException {
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Invalid image");
      var reader = readers.next();
      try {
        reader.setInput(input);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > max || h > max || (thumbnail && (w != 8 || h != 8)))
          throw new IOException("Invalid head size");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  public static BufferedImage compose(BufferedImage skin) throws IOException {
    if (skin.getWidth() < 64 || skin.getWidth() % 64 != 0 || skin.getHeight() < skin.getWidth() / 2)
      throw new IOException("Invalid skin size");
    int unit = skin.getWidth() / 64;
    var head = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) {
        int base = skin.getRGB((8 + x) * unit, (8 + y) * unit) | 0xff000000;
        int hat = skin.getRGB((40 + x) * unit, (8 + y) * unit),
            alpha = hat >>> 24,
            color = 0xff000000;
        for (int shift : new int[] {16, 8, 0})
          color |=
              ((((hat >> shift) & 255) * alpha + ((base >> shift) & 255) * (255 - alpha) + 127)
                      / 255)
                  << shift;
        head.setRGB(x, y, color);
      }
    return head;
  }

  private boolean persist(UUID id, BufferedImage head, boolean client, long generation)
      throws IOException {
    synchronized (cacheLock) {
      if (blocked.contains(id) || generations.getOrDefault(id, 0L) != generation) return false;
      Path target = client ? clientFolder : folder;
      Files.createDirectories(target);
      Path file = target.resolve(id + ".png"), temp = target.resolve(id + ".tmp");
      Path marker = clientFolder.resolve(id + ".active");
      boolean changedSource = Files.isRegularFile(marker) != client;
      var output = new ByteArrayOutputStream();
      ImageIO.write(head, "png", output);
      byte[] bytes = output.toByteArray();
      boolean changedImage =
          !Files.isRegularFile(file) || !Arrays.equals(bytes, Files.readAllBytes(file));
      if (changedImage) {
        Files.write(temp, bytes);
        try {
          Files.move(
              temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      }
      if (client) Files.writeString(marker, "client fallback");
      else Files.deleteIfExists(marker);
      if (!changedImage && !changedSource) return true;
      versions.compute(
          id,
          (key, previous) ->
              Math.max(System.currentTimeMillis(), previous == null ? 1 : previous + 1));
      return true;
    }
  }

  public JsonElement read(UUID id) throws IOException {
    synchronized (cacheLock) {
      JsonObject data = new JsonObject();
      for (var file :
          Map.of(
                  "mojang",
                  folder.resolve(id + ".png"),
                  "client",
                  clientFolder.resolve(id + ".png"),
                  "legacy",
                  folder.getParent().resolve(id + ".png"))
              .entrySet())
        if (Files.isRegularFile(file.getValue())) {
          if (Files.size(file.getValue()) > 8192) throw new IOException("头像缓存文件过大，请检查后再清除");
          data.addProperty(
              file.getKey(),
              Base64.getEncoder().encodeToString(Files.readAllBytes(file.getValue())));
        }
      if (Files.isRegularFile(clientFolder.resolve(id + ".active")))
        data.addProperty("active", true);
      return data.size() == 0 ? JsonNull.INSTANCE : data;
    }
  }

  public Runnable swap(UUID id, JsonElement expected, JsonElement desired) throws Exception {
    synchronized (cacheLock) {
      if (!read(id).equals(expected)) throw new IllegalStateException("头像缓存已发生后续变动，请重新确认");
      generations.merge(id, 1L, Long::sum);
      tickets.remove(id);
      if (desired.isJsonNull()) blocked.add(id);
      else blocked.remove(id);
      try {
        writeCache(id, desired);
      } catch (Exception failure) {
        try {
          writeCache(id, expected);
          if (expected.isJsonNull()) blocked.add(id);
          else blocked.remove(id);
        } catch (Exception restore) {
          failure.addSuppressed(restore);
        }
        throw failure;
      }
      versions.compute(
          id,
          (key, previous) ->
              Math.max(System.currentTimeMillis(), previous == null ? 1 : previous + 1));
      return () -> {
        try {
          swap(id, desired, expected);
        } catch (Exception failure) {
          throw new IllegalStateException("数据库回滚时头像缓存已变化，请检查恢复记录", failure);
        }
      };
    }
  }

  public void playerPresent(UUID id, boolean present) {
    synchronized (cacheLock) {
      if (present) {
        blocked.remove(id);
        if (!Files.isRegularFile(selected(id))) versions.remove(id);
      } else blocked.add(id);
    }
  }

  private void writeCache(UUID id, JsonElement value) throws Exception {
    JsonObject data = value.isJsonNull() ? new JsonObject() : value.getAsJsonObject();
    Map<String, Path> files =
        Map.of(
            "mojang",
            folder.resolve(id + ".png"),
            "client",
            clientFolder.resolve(id + ".png"),
            "legacy",
            folder.getParent().resolve(id + ".png"));
    Map<String, byte[]> images = new HashMap<>();
    for (String key : files.keySet())
      if (data.has(key)) {
        String encoded = data.get(key).getAsString();
        if (encoded.length() > 12000) throw new IOException("恢复头像大小无效");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        decode(bytes, 8, true);
        images.put(key, bytes);
      }
    for (var file : files.entrySet()) {
      if (!images.containsKey(file.getKey())) Files.deleteIfExists(file.getValue());
      else {
        Files.createDirectories(file.getValue().getParent());
        Path temp = file.getValue().resolveSibling(file.getValue().getFileName() + ".restore-tmp");
        Files.write(temp, images.get(file.getKey()));
        try {
          Files.move(
              temp,
              file.getValue(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, file.getValue(), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    Path marker = clientFolder.resolve(id + ".active");
    if (data.has("active") && data.get("active").getAsBoolean()) {
      Files.createDirectories(clientFolder);
      Files.writeString(marker, "client fallback");
    } else Files.deleteIfExists(marker);
  }


  public void close() {
    worker.shutdownNow();
  }
}
