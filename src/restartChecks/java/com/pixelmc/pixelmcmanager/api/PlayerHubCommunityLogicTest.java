package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonParser;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.util.*;
import java.util.concurrent.*;

/** Strict own/public request and lifecycle contracts without starting Minecraft. */
public final class PlayerHubCommunityLogicTest {
  private static int checks;
  private static void check(boolean pass,String why){if(!pass)throw new AssertionError(why);checks++;}
  private static void denied(String action,String value){try{PlayerHubCommunityApi.validate(action,value);throw new AssertionError("Invalid request accepted: "+action);}catch(RuntimeException expected){checks++;}}
  private static void failed(CompletableFuture<String> value,String why)throws Exception{try{value.get(2,TimeUnit.SECONDS);throw new AssertionError(why);}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,why);}}
  public static void main(String[] args)throws Exception {
    UUID player=UUID.randomUUID(),other=UUID.randomUUID();String archive=UUID.randomUUID().toString();
    check(PlayerHubCommunityApi.validate("snapshot","{}").size()==0,"No identity override in snapshot");
    check(PlayerHubCommunityApi.validate("profile","{\"playerId\":\""+other+"\"}").has("playerId"),"Approved public target read");
    check(PlayerHubCommunityApi.validate("profileOptions","{\"kind\":\"fortunes\",\"page\":1}").get("page").getAsInt()==1,"Bounded additional choices");
    check(PlayerHubCommunityApi.validate("saveCollections","{\"archiveId\":\""+archive+"\",\"selection\":{\"titleEventId\":null,\"fortuneEventId\":null},\"expectedRevision\":\"opaque\"}").getAsJsonObject("selection").get("titleEventId").isJsonNull(),"Explicit collection clearing retains null");
    for(String action:List.of("deleteNote","saveSupplement","platform","admin","deleteEntry"))denied(action,"{}");
    denied("snapshot","{\"playerId\":\""+other+"\"}");denied("profile","{\"instance\":\"foreign\"}");
    denied("profileOptions","{\"kind\":\"titles\",\"playerId\":\""+other+"\"}");
    denied("profileOptions","{\"kind\":\"admin\"}");denied("profileOptions","{\"kind\":\"titles\",\"page\":-1}");
    denied("profileOptions","{\"kind\":\"titles\",\"page\":1.5}");denied("profileOptions","{\"kind\":\"titles\",\"page\":\"1\"}");denied("profileOptions","{\"kind\":\"titles\",\"page\":100001}");
    denied("profile","{\"playerId\":\"1-2-3-4-5\"}");denied("saveProfile","{\"patch\":{\"name\":\"forged\"},\"expectedRevision\":\"x\"}");
    denied("saveNote","{\"archiveId\":\""+archive+"\",\"text\":\"note\"}");
    denied("appendSupplement","{\"archiveId\":\""+archive+"\",\"text\":\"note\"}");
    denied("saveCollections","{\"archiveId\":\""+archive+"\",\"selection\":{},\"expectedRevision\":\"x\"}");
    denied("saveNote","{\"archiveId\":\""+archive+"\",\"text\":\""+"中".repeat(1201)+"\",\"expectedRevision\":\"x\"}");
    denied("snapshot","[]");denied("snapshot","{");denied("snapshot","{\"nested\":"+"[".repeat(13)+"0"+"]".repeat(13)+"}");denied("snapshot","{\"x\":\""+"中".repeat(3000)+"\"}");
    PlayerStatsStore stats=new PlayerStatsStore(null);stats.recordLogin(player,"IsolatedPlayer",System.currentTimeMillis());Set<UUID> admitted=new HashSet<>(List.of(player));
    PlayerHubCommunityApi.attach(stats,admitted::contains,()->false);failed(PlayerHubCommunityApi.request(player,"snapshot","{}"),"Old/missing service unavailable");
    PlayerHubCommunityApi.attach(stats,admitted::contains,()->true);failed(PlayerHubCommunityApi.request(other,"snapshot","{}"),"Unadmitted caller denied");
    admitted.clear();PlayerHubCommunityApi.logout(player);failed(PlayerHubCommunityApi.request(player,"snapshot","{}"),"Logout revokes caller");
    PlayerHubCommunityApi.attach(null,null,null);failed(PlayerHubCommunityApi.request(player,"snapshot","{}"),"Shutdown detaches caller");
    var descriptor=JsonParser.parseString(PlayerHubCommunityApi.descriptor()).getAsJsonObject();check(PlayerHubCommunityApi.protocolVersion()==1&&descriptor.has("modVersion"),"Independent capability preserves v1 and declares mod version");
    System.out.println("PlayerHub community provider checks passed: "+checks);
  }
}
