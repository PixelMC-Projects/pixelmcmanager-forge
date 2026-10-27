// SPDX-License-Identifier: AGPL-3.0-only
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** Authenticated read-only gate. Never starts another service or changes game data. */
public final class UnifiedReadiness {
  public static void main(String[] args) {
    try {
      JsonObject bridge = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
      if (!bridge.get("enabled").getAsBoolean()) throw new IllegalStateException();
      String base = bridge.get("baseUrl").getAsString().replaceAll("/+$", "");
      var request = HttpRequest.newBuilder(URI.create(base + "/bridge/capabilities"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + bridge.get("token").getAsString())
          .header("X-PixelMC-Instance", bridge.get("instanceId").getAsString())
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
          .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException();
      JsonObject value = JsonParser.parseString(response.body()).getAsJsonObject();
      if (!value.get("version").getAsString().equals("0.6.0")
          || value.get("titlesApi").getAsInt() < 3 || !value.get("titlesHealthy").getAsBoolean()
          || !value.get("fortunesEnabled").getAsBoolean()) throw new IllegalStateException();
      System.out.println("[PixelMC unified] Authenticated 0.6.0 backend and both extensions ready.");
    } catch (Exception unavailable) {
      System.err.println("[PixelMC unified] Start and verify the database and web service first; game was not started.");
      System.exit(2);
    }
  }
}
