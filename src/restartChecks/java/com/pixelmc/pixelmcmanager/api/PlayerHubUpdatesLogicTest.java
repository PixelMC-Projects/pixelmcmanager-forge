package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.util.*;
import java.util.concurrent.*;

/** Contract/admission checks with no running game or database. */
public final class PlayerHubUpdatesLogicTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void denied(String action,String payload){try{PlayerHubUpdatesApi.validate(action,payload);throw new AssertionError("Accepted invalid request: "+action);}catch(RuntimeException expected){checks++;}}
    private static void failed(CompletableFuture<String> future,String message)throws Exception{try{future.get(2,TimeUnit.SECONDS);throw new AssertionError(message);}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,message);}}
    public static void main(String[] args)throws Exception{
        String id=UUID.randomUUID().toString(),comment=UUID.randomUUID().toString();
        check(PlayerHubUpdatesApi.validate("snapshot","{}").size()==0,"Snapshot has no identity override");
        check(PlayerHubUpdatesApi.validate("list","{\"packId\":\""+id+"\",\"page\":5,\"search\":\"修复\"}").get("page").getAsInt()==5,"Bounded list query accepted");
        check(PlayerHubUpdatesApi.validate("read","{\"id\":\""+id+"\",\"commentsPage\":1,\"replyPages\":{\""+comment+"\":2}}").has("replyPages"),"Own reply paging explicitly allowed");
        check(!PlayerHubUpdatesApi.validate("like","{\"id\":\""+id+"\",\"liked\":false}").get("liked").getAsBoolean(),"Explicit unlike retains false");
        check(PlayerHubUpdatesApi.validate("comment","{\"id\":\""+id+"\",\"text\":\"建议\",\"operationId\":\""+comment+"\"}").has("operationId"),"Comment retry identity required");
        check(PlayerHubUpdatesApi.validate("packs","{\"page\":1}").get("page").getAsInt()==1,"Pack pagination accepted");
        denied("packs","{\"page\":-1}");denied("packs","{\"page\":1.5}");denied("packs","{\"page\":100001}");
        denied("packs","{\"instanceId\":\"other-instance\"}");
        denied("deleteEntry","{}");denied("reply","{}");denied("platform","{}");
        denied("snapshot","{\"uuid\":\""+id+"\"}");denied("snapshot","[]");denied("snapshot","{");
        denied("like","{\"id\":\""+id+"\",\"liked\":\"false\"}");denied("like","{\"id\":\"1-2-3-4-5\",\"liked\":true}");
        denied("list","{\"page\":-1}");denied("list","{\"page\":1.5}");denied("list","{\"page\":100001}");
        denied("list","{\"search\":\""+"x".repeat(129)+"\"}");
        denied("comment","{\"id\":\""+id+"\",\"text\":\"text\"}");
        denied("comment","{\"id\":\""+id+"\",\"text\":\"  \",\"operationId\":\""+comment+"\"}");
        denied("comment","{\"id\":\""+id+"\",\"text\":\""+"x".repeat(2001)+"\",\"operationId\":\""+comment+"\"}");
        denied("read","{\"id\":\""+id+"\",\"replyPages\":{\""+comment+"\":-1}}");
        denied("read","{\"id\":\""+id+"\",\"player\":\""+comment+"\"}");
        denied("snapshot","{\"nested\":"+"[".repeat(17)+"0"+"]".repeat(17)+"}");
        denied("snapshot","{\"x\":\""+"中".repeat(3000)+"\"}");
        JsonObject pages=new JsonObject();for(int i=0;i<13;i++)pages.addProperty(UUID.randomUUID().toString(),0);
        denied("read","{\"id\":\""+id+"\",\"replyPages\":"+pages+"}");
        UUID player=UUID.fromString(id),stranger=UUID.randomUUID();
        PlayerStatsStore stats=new PlayerStatsStore(null);stats.recordLogin(player,"LocalPlayer",System.currentTimeMillis());
        Set<UUID> admitted=new HashSet<>();admitted.add(player);
        PlayerHubUpdatesApi.attach(stats,admitted::contains,()->false);
        check(PlayerHubUpdatesApi.protocolVersion()==1,"Optional provider API version remains 1");
        failed(PlayerHubUpdatesApi.request(player,"snapshot","{}"),"Unsupported/old service is explicit unavailable");
        PlayerHubUpdatesApi.attach(stats,admitted::contains,()->true);
        failed(PlayerHubUpdatesApi.request(stranger,"snapshot","{}"),"Other/unadmitted identity cannot query");
        failed(PlayerHubUpdatesApi.request(player,"snapshot","{\"uuid\":\""+stranger+"\"}"),"Payload cannot impersonate another player");
        admitted.clear();PlayerHubUpdatesApi.logout(player);
        failed(PlayerHubUpdatesApi.request(player,"snapshot","{}"),"Logout revokes provider");
        PlayerHubUpdatesApi.attach(null,null,null);
        failed(PlayerHubUpdatesApi.request(player,"snapshot","{}"),"Shutdown detaches provider");
        System.out.println("PlayerHub changelog provider checks passed: "+checks);
    }
}
