package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.*;
import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.WebConfig;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Only run with the owned disposable MariaDB launcher. No production credentials or data. */
public final class PlayerHubRankingsChecks {
    private static int checks;
    private static String url,csrf,run,world,archive;
    private static long sequence,clock;
    private static final String INSTANCE="ranking-check", TOKEN=UUID.randomUUID().toString();
    private static final UUID OWNER=UUID.randomUUID(), PLAYER=id(25);
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static HttpClient admin;
    private static final List<JsonObject> players=new ArrayList<>();
    private static final JsonArray saved=new JsonArray();
    private static UUID id(int index){return UUID.fromString(String.format("00000000-0000-4000-8000-%012d",index));}
    private static JsonObject obj(Object...pairs){JsonObject value=new JsonObject();for(int i=0;i<pairs.length;i+=2)value.add(pairs[i].toString(),JSON.toJsonTree(pairs[i+1]));return value;}
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static HttpResponse<String> request(HttpClient client,String path,JsonObject body)throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(20));
        if(body!=null){builder.header("Content-Type","application/json").header("Origin",url);if(csrf!=null)builder.header("X-CSRF-Token",csrf);builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));}
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static JsonObject bridge(String instance,String path,JsonObject body)throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create(url+"/bridge/"+path)).timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("X-PixelMC-Instance",instance).header("Authorization","Bearer "+TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());check(response.statusCode()==200,"Bridge status "+response.statusCode()+" "+response.body());return json(response.body());
    }
    private static JsonObject envelope(UUID player,String action,JsonObject query){return obj("extension","playerhub","op","rankings","playerProof",player.toString(),"data",obj("args",List.of(player.toString(),action,query)));}
    private static JsonObject result(UUID player,String action,JsonObject query)throws Exception {
        JsonObject response=bridge(INSTANCE,"rpc",envelope(player,action,query));check(response.has("result")&&!response.has("error"),"Ranking business response "+response);return response.getAsJsonObject("result");
    }
    private static JsonObject page(String board)throws Exception {JsonObject value=result(PLAYER,"page",obj("board",board,"page",0));saved.add(value.deepCopy());return value;}
    private static JsonObject frame(String status,boolean login)throws Exception {
        List<JsonObject> logins=new ArrayList<>();if(login)for(JsonObject player:players)logins.add(obj("id",UUID.randomUUID(),"sessionId",UUID.randomUUID(),"uuid",player.get("id"),"name",player.get("name"),"at",clock));
        return bridge(INSTANCE,"snapshot",obj("runId",run,"archiveId",archive,"seq",++sequence,"at",++clock,
                "snapshot",obj("foodCompatibility",obj("status",status)),"players",players,"stats",players,"logins",logins,"acks",List.of(),"longTicks",List.of()));
    }
    private static JsonObject row(JsonObject page,int index){return page.getAsJsonArray("rows").get(index).getAsJsonObject();}
    private static void run(ManagerController controller,Path folder,Database.Config restoreDb)throws Exception {
        controller.web=new PlatformWebServer(controller,new WebConfig(true,"127.0.0.1",controller.config.port(),url,480,List.of()),folder.resolve("platform"));
        String password="Disposable-"+UUID.randomUUID()+"!";controller.web.accounts.create("rankingscheck",OWNER,password);
        admin=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var login=request(admin,"/api/login",obj("username","rankingscheck","password",password));check(login.statusCode()==200,"Isolated admin login");csrf=json(login.body()).get("csrf").getAsString();
        check(request(HTTP,"/api/playerhub/leaderboards",null).statusCode()==401,"Anonymous admin denied");
        controller.store.register(INSTANCE,TOKEN);controller.store.register("foreign-check",TOKEN);
        run=UUID.randomUUID().toString();world=UUID.randomUUID().toString();clock=System.currentTimeMillis();
        JsonObject hello=bridge(INSTANCE,"hello",obj("runId",run,"worldId",world,"startedAt",clock-1000,"info",obj()));
        check(hello.get("leaderboardsApi").getAsInt()==1,"Capability advertised");
        var create=request(admin,"/api/manager/archives",obj("instanceId",INSTANCE,"worldId",world,"name","Synthetic Pack","version","1.0","startedOn","2026-11-06","active",true));
        check(create.statusCode()==200,"Create isolated archive");archive=json(create.body()).get("id").getAsString();
        for(int index=1;index<=25;index++)players.add(obj("id",id(index),"name","Player"+index,"joinCount",1,"totalOnlineMillis",(26-index)*3600000L,"firstJoinEpochMillis",clock,"lastJoinEpochMillis",clock,"effectiveFoods",index==3?240:260-index*10));
        JsonObject ack=frame("unsupported",true);check(ack.has("leaderboardsRevisions"),"Per-board hints piggyback telemetry");
        check(page("food").get("status").getAsString().equals("unsupported"),"Food mod absent is unsupported");
        check(page("online").get("total").getAsInt()==25,"Online time accepted from Manager stats");
        check(bridge(INSTANCE,"rpc",envelope(UUID.randomUUID(),"snapshot",obj())).has("error"),"Unknown identity denied");
        check(bridge("foreign-check","rpc",envelope(PLAYER,"snapshot",obj())).has("error"),"Foreign instance denied");
        JsonObject forged=envelope(PLAYER,"snapshot",obj());forged.addProperty("playerProof",id(1).toString());check(bridge(INSTANCE,"rpc",forged).has("error"),"Forged proof denied");
        check(bridge(INSTANCE,"rpc",envelope(PLAYER,"page",obj("board","food","page",0,"uuid",id(1)))).has("error"),"Player cannot select UUID");
        JsonArray batch=new JsonArray();batch.add(envelope(PLAYER,"page",obj("board","online","page",0)));batch.add(envelope(UUID.randomUUID(),"page",obj("board","online","page",0)));
        JsonObject batched=bridge(INSTANCE,"rpc",obj("extension","playerhub","op","rankingsBatch","data",obj("requests",batch)));
        check(batched.getAsJsonArray("results").get(0).getAsJsonObject().has("result")&&batched.getAsJsonArray("results").get(1).getAsJsonObject().has("error"),"Batched viewers retain independent admission");
        frame("ready",false);JsonObject first=page("food");String original=first.get("revision").getAsString();
        check(row(first,1).get("rank").getAsInt()==2&&row(first,2).get("rank").getAsInt()==2&&row(first,3).get("rank").getAsInt()==4,"Competition ranks with stable ties");
        check(first.getAsJsonObject("self").get("rank").getAsInt()==25,"Self outside first page retained");
        JsonObject around=result(PLAYER,"aroundMe",obj("board","food","revision",original));check(around.get("page").getAsInt()==1&&around.getAsJsonArray("rows").size()==5,"Around-me locates by ordinal");
        players.get(24).addProperty("effectiveFoods",270);frame("ready",false);JsonObject second=page("food");
        check(second.getAsJsonObject("self").get("rankDelta").getAsInt()==24,"Rank delta uses previous complete published board");
        check(second.get("comparisonRevision").getAsString().equals(original),"Comparison revision refers to previous publication");
        JsonObject old=result(PLAYER,"page",obj("board","food","page",1,"revision",original));check(old.getAsJsonObject("self").get("value").getAsInt()==10,"Old page stays immutable after ingest");
        JsonObject window=result(PLAYER,"window",obj("board","food","page",0,"count",5));
        check(window.getAsJsonArray("pages").size()==2,"Window bounded by actual pages");
        for(JsonElement value:window.getAsJsonArray("pages"))check(value.getAsJsonObject().get("revision").equals(window.getAsJsonObject("header").get("revision")),"Window shares one revision");
        frame("unavailable",false);check(page("food").get("status").getAsString().equals("unavailable"),"Adapter error does not publish a fake zero");
        players.get(24).addProperty("effectiveFoods",0);frame("ready",false);JsonObject reset=page("food");
        check(reset.getAsJsonObject("self").get("rank").isJsonNull()&&reset.getAsJsonObject("self").get("value").getAsInt()==0,"Source reset preserves known zero without a rank");
        check(bridge(INSTANCE,"rpc",envelope(PLAYER,"page",obj("board","food","page",1,"revision",original))).has("error"),"Expired revision rejects mixed snapshots");
        try(Connection db=controller.store.open()) {
            for(int index=1;index<=4;index++)update(db,"INSERT INTO df_records(player,day,state,at_ms,fortune,pending,payload) VALUES(?,?,?,?,?,?,?)",PLAYER.toString(),"2026-11-0"+index,index==4?"unread":"archived",clock,index<=2?1:2,false,"{}");
        }
        controller.rankings.fortuneChanged(PLAYER);check(page("fortunes").getAsJsonObject("self").get("value").getAsInt()==2,"Fortunes count distinct archived kinds, excluding unread");
        try(Connection db=controller.store.open()){update(db,"DELETE FROM df_records WHERE player=? AND fortune=2",PLAYER.toString());}
        controller.rankings.fortuneChanged(PLAYER);check(page("fortunes").getAsJsonObject("self").get("value").getAsInt()==1,"Fortune reset decreases projection");
        var adminResponse=request(admin,"/api/playerhub/leaderboards?instance=ranking-check&board=food&period=all&size=100",null);check(adminResponse.statusCode()==200&&json(adminResponse.body()).get("total").getAsInt()==24,"Read-only admin projection");
        saved.add(json(adminResponse.body()));
        check(controller.platform.durableTables().containsAll(PlayerHubRankingsStore.TABLES),"All ranking tables registered for backup");
        Path backup=controller.backupAll();try(ZipFile zip=new ZipFile(backup.toFile())){for(String table:PlayerHubRankingsStore.TABLES)check(zip.getEntry("schema/"+table+".sql")!=null&&zip.getEntry("database/"+table+".json")!=null,"Backup contains "+table);}
        Path restoreFolder=folder.resolve("restored");ServiceConfig config=new ServiceConfig(restoreDb,restoreDb,false,"127.0.0.1",controller.config.port(),url,List.of(OWNER),"data",restoreDb,true,"unified",30);
        ManagerRestore.restore(config,restoreFolder,backup);ManagerStore restored=new ManagerStore(restoreDb);try(Connection db=restored.open()){check(rows(db,"SELECT * FROM ph_ranking_values WHERE board='food'").size()==25,"Empty-schema restore retains ranking projection");}ManagerRestore.complete(restoreFolder);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> reader=pool.submit(()->{try{for(int n=0;n<12;n++)controller.rankings.player(INSTANCE,PLAYER,"window",obj("board","online","page",0,"count",2),controller.fortunes);}catch(Exception error){throw new CompletionException(error);}});
            Future<?> writer=pool.submit(()->{try{for(int n=0;n<12;n++){players.get(0).addProperty("totalOnlineMillis",100000000L+n*1000);frame("ready",false);}}catch(Exception error){throw new CompletionException(error);}});
            reader.get(25,TimeUnit.SECONDS);writer.get(25,TimeUnit.SECONDS);check(true,"Concurrent ingest/publication finishes without deadlock");
        } finally {pool.shutdownNow();}
        PlatformDeletion deletion=new PlatformDeletion(controller.store,controller.platform,controller.web.avatars);
        JsonObject preview=deletion.preview(PLAYER,Set.of());check(preview.getAsJsonObject("counts").has("ph_ranking_values"),"Global deletion owns ranking values");
        String recovery=deletion.delete(OWNER,PLAYER,preview.get("stamp").getAsString(),"Player25",Set.of());controller.rankings.invalidateAll();
        check(bridge(INSTANCE,"rpc",envelope(PLAYER,"snapshot",obj())).has("error"),"Deleted profile cannot use old admission");
        try(Connection db=controller.store.open()){check(rows(db,"SELECT player_uuid FROM ph_ranking_values WHERE player_uuid=?",PLAYER.toString()).isEmpty(),"Owned projection removed");}
        deletion.restore(OWNER,recovery,Set.of());controller.rankings.invalidateAll();check(page("fortunes").getAsJsonObject("self").get("value").getAsInt()==1,"Deleted-player recovery rebuilds source projection");
        try(Connection db=controller.store.open()) {update(db,"UPDATE pm_archives SET status='archived',finalized=TRUE WHERE id=?",archive);update(db,"UPDATE pm_instances SET active_archive=NULL,world_id=? WHERE id=?",UUID.randomUUID().toString(),INSTANCE);}
        check(bridge(INSTANCE,"rpc",envelope(PLAYER,"page",obj("board","online","page",0))).has("error"),"Missing active archive cannot reuse old world leaderboard");
        check(page("fortunes").getAsJsonObject("self").get("value").getAsInt()==1,"Global fortune scope survives archive change");
        Files.writeString(folder.resolve("safe-ranking-dtos.json"),saved.toString());
    }
    public static void main(String[] args)throws Exception {
        if(!"isolated".equals(System.getenv("PM_HUB_TEST_SCOPE")))throw new IllegalStateException("Owned isolated launcher required");
        Path folder=Path.of(System.getenv("PM_HUB_TEST_OUTPUT"));String base="jdbc:mariadb://127.0.0.1:43320/",schema="pm_rankings_"+UUID.randomUUID().toString().replace("-",""),restored=schema+"_restore";
        try(Connection db=DriverManager.getConnection(base,"root","")){db.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");db.createStatement().execute("CREATE DATABASE "+restored+" CHARACTER SET utf8mb4");}
        try {
            int port;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}url="http://127.0.0.1:"+port;
            Database.Config database=new Database.Config(base+schema,"root","");ServiceConfig config=new ServiceConfig(database,database,false,"127.0.0.1",port,url,List.of(OWNER),"data",database,true,"unified",30);
            try(ManagerController controller=new ManagerController(config,folder)){run(controller,folder,new Database.Config(base+restored,"root",""));}
            System.out.println("PlayerHub rankings HTTP/SQL checks passed: "+checks);
        } finally {try(Connection db=DriverManager.getConnection(base,"root","")){db.createStatement().execute("DROP DATABASE "+schema);db.createStatement().execute("DROP DATABASE "+restored);}}
    }
}
