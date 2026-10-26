// SPDX-License-Identifier: AGPL-3.0-only
// Copyright 2026 zcpu / PixelMC
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** Authenticated read-only startup gate. No game command, database write or credential logging. */
public final class ManagerUiReadiness {
  public static void main(String[] args) {
    try {
      if(args.length!=1)throw new IllegalArgumentException();
      JsonObject bridge=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
      if(!bridge.get("enabled").getAsBoolean())throw new IllegalStateException();
      String base=bridge.get("baseUrl").getAsString().replaceAll("/+$", "");
      HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/bridge/capabilities"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
          .header("Authorization","Bearer "+bridge.get("token").getAsString())
          .header("X-PixelMC-Instance",bridge.get("instanceId").getAsString())
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      HttpResponse<String> response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
          .send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()!=200)throw new IllegalStateException();
      JsonObject value=JsonParser.parseString(response.body()).getAsJsonObject();
      if(value.get("titlesApi").getAsInt()<2||!value.get("titlesHealthy").getAsBoolean()
          ||!versionAtLeast(value.get("version").getAsString(),new int[]{0,5,2}))throw new IllegalStateException();
      System.out.println("[Manager UI staging] Authenticated backend 0.5.2 / Titles API 2 is ready.");
    } catch(Exception unavailable) {
      System.out.println("[Manager UI staging] Backend upgrade is unconfirmed; keep current game mods.");
      System.exit(2);
    }
  }
  private static boolean versionAtLeast(String value,int[] expected){String[] parts=value.split("\\.");if(parts.length!=3)return false;for(int i=0;i<3;i++){int actual=Integer.parseInt(parts[i]);if(actual!=expected[i])return actual>expected[i];}return true;}
}
