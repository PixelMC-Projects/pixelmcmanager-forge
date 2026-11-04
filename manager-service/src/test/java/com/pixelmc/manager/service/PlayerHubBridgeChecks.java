package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.WebConfig;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real authenticated bridge HTTP against one explicitly disposable loopback database. */
public final class PlayerHubBridgeChecks {
  private static int checks;
  private static final String INSTANCE="hub-check", TOKEN=UUID.randomUUID()+":"+UUID.randomUUID();
  private static final HttpClient HTTP=HttpClient.newHttpClient();
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  private static JsonObject json(String source){return JsonParser.parseString(source).getAsJsonObject();}
  private static JsonObject object(Object...values){JsonObject result=new JsonObject();for(int i=0;i<values.length;i+=2)result.add(values[i].toString(),JSON.toJsonTree(values[i+1]));return result;}
  private static JsonObject bridge(String url,String path,JsonObject body,String instance)throws Exception{
    HttpRequest request=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(20))
        .header("Content-Type","application/json").header("X-PixelMC-Instance",instance).header("Authorization","Bearer "+TOKEN)
        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
    HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
    check(response.statusCode()==200,"Authenticated bridge uses business response envelope");return json(response.body());
  }
  private static JsonObject rpc(String url,String module,String operation,UUID player,Object...args)throws Exception{
    JsonArray values=new JsonArray();values.add(player.toString());for(Object arg:args)values.add(JSON.toJsonTree(arg));
    return bridge(url,"/bridge/rpc",object("extension",module,"op",operation,"playerProof",player.toString(),"data",object("args",values,"revision","")),INSTANCE);
  }
  private static HttpResponse<String> admin(HttpClient client,String url,String path,JsonObject body,String csrf)throws Exception{
    HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(20));
    if(body!=null){request.header("Content-Type","application/json").header("Origin",url);if(csrf!=null)request.header("X-CSRF-Token",csrf);request.POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));}
    return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static JsonObject findTitle(JsonObject catalogue,String id){for(JsonElement entry:catalogue.getAsJsonArray("titles")){JsonObject value=entry.getAsJsonObject();if(value.get("id").getAsString().equals(id))return value;}throw new AssertionError("Saved title absent from actual Manager catalogue");}
  private static void adminMetadata(ManagerController controller,String url,UUID owner,String username,String password)throws Exception{
    controller.web.accounts.create(username,owner,password);
    HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    HttpResponse<String> login=admin(client,url,"/api/login",object("username",username,"password",password),null);
    check(login.statusCode()==200,"Real Manager local administrator logs in");
    String csrf=json(login.body()).get("csrf").getAsString();
    JsonObject title=object("id","hub_preview","name",object("segments",List.of(object("text","山野行者","color",0xe8e2d1,"bold",true,"italic",false,"underline",false,"strike",false)),"gradients",List.of()),
        "description","在漫长旅途中留下的山野足迹。","source","Hub isolated pack","acquisition","参与本地隔离验收","category","探索","order",0,"icon",321,"visible",true,"issuable",true,"revision",0,"createdAt",0,
        "presentation",object("badge","mountain","rarity","rare","theme","sage"));
    HttpResponse<String> saved=admin(client,url,"/api/titles",object("title",title,"expectedRevision",0),csrf);
    check(saved.statusCode()==200,"Actual integrated Manager /api/titles persists presentation metadata");
    JsonObject committed=json(saved.body()).getAsJsonObject("title");
    JsonObject read=findTitle(json(admin(client,url,"/api/state",null,null).body()),"hub_preview");
    check(read.getAsJsonObject("presentation").equals(title.getAsJsonObject("presentation"))&&read.get("icon").getAsInt()==321,"Authenticated GET reads saved presets and preserves CustomModelData");
    check(admin(client,url,"/api/titles",object("title",title,"expectedRevision",0),csrf).statusCode()==409,"Actual admin route rejects stale metadata update");
    JsonObject invalid=committed.deepCopy();invalid.getAsJsonObject("presentation").addProperty("theme","neon");
    HttpResponse<String> invalidResponse=admin(client,url,"/api/titles",object("title",invalid,"expectedRevision",committed.get("revision")),csrf);
    check(invalidResponse.statusCode()==400,"Actual admin route rejects invalid theme IDs: status="+invalidResponse.statusCode()+" response="+invalidResponse.body());
    check(admin(client,url,"/api/reload",object(),csrf).statusCode()==200,"Actual integrated reload path succeeds");
    JsonObject reloaded=findTitle(json(admin(client,url,"/api/state",null,null).body()),"hub_preview");
    check(reloaded.getAsJsonObject("presentation").equals(title.getAsJsonObject("presentation")),"Presentation metadata survives real Manager reload/readback");
  }
  public static void main(String[]args)throws Exception{
    if(!"isolated".equals(System.getenv("PM_HUB_TEST_SCOPE")))throw new IllegalStateException("Owned isolated test launcher required");
    String base="jdbc:mariadb://127.0.0.1:43320/",schema="pm_hub_"+UUID.randomUUID().toString().replace("-","");
    try(Connection connection=DriverManager.getConnection(base,"root","")){connection.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
    Path folder=Files.createTempDirectory(Path.of("build"),"player-hub-bridge-");
    UUID player=UUID.randomUUID(),stranger=UUID.randomUUID(),owner=UUID.randomUUID();
    try{
      int port;try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
      String url="http://127.0.0.1:"+port;
      Database.Config database=new Database.Config(base+schema,"root","");
      ServiceConfig config=new ServiceConfig(database,database,true,"127.0.0.1",port,url,List.of(owner),"data",database,true,"unified",30);
      try(ManagerController controller=new ManagerController(config,folder)){
        controller.web=new PlatformWebServer(controller,new WebConfig(true,"127.0.0.1",port,url,480,List.of()),folder.resolve("platform"));
        String username="hubvisual",password="Hub-Test-"+UUID.randomUUID()+"!";
        adminMetadata(controller,url,owner,username,password);
        controller.store.register(INSTANCE,TOKEN);controller.store.register("other-instance",TOKEN);
        String run=UUID.randomUUID().toString(),world=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        bridge(url,"/bridge/hello",object("runId",run,"worldId",world,"startedAt",now-1000,"info",object()),INSTANCE);
        String archive=controller.store.createArchive(object("instanceId",INSTANCE,"worldId",world,"name","Hub isolated pack","version","test","startedOn","2026-11-04","active",true),"isolated-check").get("id").getAsString();
        JsonObject profile=object("id",player,"name","HubPlayer","joinCount",1,"totalOnlineMillis",1000,"firstJoinEpochMillis",now,"lastJoinEpochMillis",now);
        JsonObject login=object("id",UUID.randomUUID(),"sessionId",UUID.randomUUID(),"uuid",player,"name","HubPlayer","at",now);
        bridge(url,"/bridge/snapshot",object("runId",run,"archiveId",archive,"seq",1,"at",now,"snapshot",object(),"players",List.of(profile),"stats",List.of(profile),"logins",List.of(login),"acks",List.of(),"longTicks",List.of()),INSTANCE);
        check(!rpc(url,"memorialtitles","login",player,"HubPlayer").has("error"),"Admitted title player registers through real bridge");
        JsonObject titles=rpc(url,"memorialtitles","playerHub",player,"snapshot",object());
        check(!titles.has("error")&&titles.getAsJsonObject("result").has("titles"),"Managed title Hub snapshot passes bound backend admission");
        check(!titles.has("snapshot")&&!titles.getAsJsonObject("result").has("players"),"Hub response never attaches administrative Snapshot/roster");
        check(rpc(url,"memorialtitles","playerHub",stranger,"snapshot",object()).has("error"),"Unadmitted player denied");
        check(rpc(url,"memorialtitles","playerHub",player,"deletePlayer",object()).has("error"),"Administrative title action denied");
        JsonObject cross=object("extension","memorialtitles","op","playerHub","playerProof",player.toString(),"data",object("args",List.of(player,"snapshot",object()),"revision",""));
        check(bridge(url,"/bridge/rpc",cross,"other-instance").has("error"),"Instance-specific proof remains required despite global backend admission");

        check(!rpc(url,"dailyfortunes","login",player,"HubPlayer").has("error"),"Admitted fortune player registers through bridge");
        JsonObject state=rpc(url,"dailyfortunes","playerHub",player,"snapshot",object()).getAsJsonObject("result");
        check(state.get("todayState").getAsString().equals("undrawn"),"Managed Fortune Hub reads registered player state");
        JsonObject drawn=rpc(url,"dailyfortunes","draw",player).getAsJsonObject("result").getAsJsonObject("record");
        JsonObject unread=rpc(url,"dailyfortunes","playerHub",player,"snapshot",object()).getAsJsonObject("result");
        check(unread.get("todayRecord").isJsonNull()&&unread.get("todayState").getAsString().equals("unread"),"Actual managed bridge hides unread result");
        String date=drawn.get("date").getAsString(),token=drawn.get("token").getAsString();
        rpc(url,"dailyfortunes","resolve",player,date,token);rpc(url,"dailyfortunes","archive",player,date,token);
        JsonObject archived=rpc(url,"dailyfortunes","playerHub",player,"snapshot",object()).getAsJsonObject("result");
        JsonObject featured=rpc(url,"dailyfortunes","playerHub",player,"feature",object("date",date,"expectedRevision",archived.get("revision").getAsString()));
        check(!featured.has("error")&&featured.getAsJsonObject("result").get("featuredDate").getAsString().equals(date),"Feature commits through registered Manager namespace");
        JsonObject stored=controller.platform.request("dailyfortunes",object("op","storage.get","player",player.toString(),"field","featured_date"),Set.of(player));
        check(stored.get("value").getAsString().equals(date),"Managed feature persists in pm_values");
        try(Connection connection=controller.store.open()){
          check(ManagerStore.rows(connection,"SELECT player FROM df_hub_preferences").isEmpty(),"Managed feature does not create a second player preference authority");
        }
        check(rpc(url,"dailyfortunes","playerHub",stranger,"snapshot",object()).has("error"),"Managed Fortune unknown player denied");
        JsonObject safe=rpc(url,"dailyfortunes","playerHub",player,"read",object("date",date)).getAsJsonObject("result");
        check(safe.keySet().equals(Set.of("id","date","at","fortune"))&&!safe.toString().contains(token)&&!safe.getAsJsonObject("fortune").has("weight"),"Managed record response uses safe DTO allowlist");
        String outputValue=System.getenv("PM_HUB_TEST_OUTPUT");
        if(outputValue!=null){
          Path output=Path.of(outputValue).toAbsolutePath().normalize();Files.createDirectories(output);
          JsonObject calendar=rpc(url,"dailyfortunes","playerHub",player,"calendar",object("month",date.substring(0,7))).getAsJsonObject("result");
          Files.writeString(output.resolve("safe-responses.json"),object("titles",titles.getAsJsonObject("result"),"fortuneUndrawn",state,"fortuneUnread",unread,"fortuneArchived",archived,"fortuneFeatured",featured.getAsJsonObject("result"),"fortuneCalendar",calendar,"fortuneRead",safe).toString());
          if("1".equals(System.getenv("PM_HUB_VISUAL_HOLD"))){
            Path fixture=output.resolve("fixture.json"),stop=output.resolve("stop");long until=System.currentTimeMillis()+300000;
            Files.writeString(fixture,object("url",url,"username",username,"password",password,"owner",owner,"expiresAt",until,"stopFile",stop.toString()).toString());
            System.out.println("ISOLATED_FIXTURE_READY "+fixture);
            try{while(System.currentTimeMillis()<until&&!Files.exists(stop))Thread.sleep(200);}finally{Files.deleteIfExists(fixture);}
          }
        }
      }
      System.out.println("Player Hub managed HTTP bridge checks passed: "+checks);
    }finally{
      try(Connection connection=DriverManager.getConnection(base,"root","")){connection.createStatement().execute("DROP DATABASE "+schema);}
    }
  }
}
