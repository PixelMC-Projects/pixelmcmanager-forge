package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.*;
import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.domain.Model;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.memorialtitles.web.WebConfig;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Real HTTP/SQL checks, exclusively against the disposable loopback launcher. */
public final class PlayerHubCommunityChecks {
  private static int checks;
  private static final String INSTANCE="community-check", TOKEN=UUID.randomUUID()+":"+UUID.randomUUID();
  private static final UUID OWNER=UUID.randomUUID(), PLAYER=UUID.randomUUID(), OTHER=UUID.randomUUID();
  private static final HttpClient HTTP=HttpClient.newHttpClient();
  private static HttpClient admin;
  private static String url,csrf,current,old;
  private static final JsonArray saved=new JsonArray();
  private static JsonObject obj(Object... pairs){JsonObject o=new JsonObject();for(int n=0;n<pairs.length;n+=2)o.add(pairs[n].toString(),pairs[n+1] instanceof JsonElement e?e.deepCopy():JSON.toJsonTree(pairs[n+1]));return o;}
  private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  private static HttpResponse<String> req(HttpClient client,String path,JsonObject body,boolean csrfHeader)throws Exception{
    var b=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(25));
    if(body!=null){b.header("Origin",url).header("Content-Type","application/json");if(csrfHeader)b.header("X-CSRF-Token",csrf);b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));}
    return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static JsonObject get(String path)throws Exception{var r=req(admin,"/api/playerhub/community"+path,null,false);check(r.statusCode()==200,"GET "+path+" "+r.statusCode()+" "+r.body());return json(r.body());}
  private static JsonObject post(String path,JsonObject body)throws Exception{var r=req(admin,"/api/playerhub/community"+path,body,true);check(r.statusCode()==200,"POST "+path+" "+r.statusCode()+" "+r.body());return json(r.body());}
  private static JsonObject bridge(String instance,String path,JsonObject body)throws Exception{
    var b=HttpRequest.newBuilder(URI.create(url+"/bridge/"+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json").header("X-PixelMC-Instance",instance).header("Authorization","Bearer "+TOKEN).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());check(r.statusCode()==200,"Bridge HTTP "+path);return json(r.body());
  }
  private static JsonObject envelope(UUID player,String action,JsonObject body){JsonArray args=new JsonArray();args.add(player.toString());args.add(action);args.add(body.deepCopy());return obj("extension","playerhub","op","community","playerProof",player,"data",obj("args",args));}
  private static JsonObject rpc(UUID player,String action,JsonObject body)throws Exception{return bridge(INSTANCE,"rpc",envelope(player,action,body));}
  private static JsonObject result(UUID player,String action,JsonObject body)throws Exception{var r=rpc(player,action,body);check(r.has("result")&&!r.has("error"),"Business "+action+" "+r);check(!r.has("snapshot"),"No admin snapshot in player response");return r.getAsJsonObject("result");}
  private static JsonObject memory(UUID player,String archive)throws Exception{return result(player,"memory",obj("archiveId",archive));}
  private static JsonObject profile()throws Exception{return result(PLAYER,"profile",obj()).getAsJsonObject("profile");}
  private static void sql(ManagerController c,String sql,Object... args)throws Exception{try(Connection db=c.store.open()){update(db,sql,args);}}
  private static long count(ManagerController c,String sql,Object... args)throws Exception{try(Connection db=c.store.open()){return rows(db,sql,args).get(0).get("n").getAsLong();}}
  private static void run(ManagerController c,Path folder,Database.Config restoredDb)throws Exception{
    c.web=new PlatformWebServer(c,new WebConfig(true,"127.0.0.1",c.config.port(),url,480,List.of()),folder.resolve("platform"));
    String password="1".equals(System.getenv("PM_COMMUNITY_VISUAL_HOLD")) ? "Community-Isolated-UI-20261107!" : "Disposable-"+UUID.randomUUID()+"!";c.web.accounts.create("communitycheck",OWNER,password);
    admin=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    var login=req(admin,"/api/login",obj("username","communitycheck","password",password),false);check(login.statusCode()==200,"Test admin authentication");csrf=json(login.body()).get("csrf").getAsString();
    check(req(HTTP,"/api/playerhub/community/players",null,false).statusCode()==401,"Anonymous admin denied");
    check(req(admin,"/api/playerhub/community/save-profile",obj(),false).statusCode()==403,"CSRF required for community writes");
    c.store.register(INSTANCE,TOKEN);c.store.register("foreign-community",TOKEN);
    long now=System.currentTimeMillis();String run=UUID.randomUUID().toString(),world=UUID.randomUUID().toString();
    JsonObject hello=bridge(INSTANCE,"hello",obj("runId",run,"worldId",world,"startedAt",now,"info",obj()));check(hello.get("communityApi").getAsInt()==1&&hello.has("communityRevision"),"Hello capability/token");
    current=c.store.createArchive(obj("instanceId",INSTANCE,"worldId",world,"name","Current Pack","version","2","startedOn",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"active",true),OWNER.toString()).get("id").getAsString();
    old=c.store.createArchive(obj("instanceId",INSTANCE,"worldId",UUID.randomUUID(),"name","Old Pack","version","1","startedOn","2025-01-01","active",false),OWNER.toString()).get("id").getAsString();
    sql(c,"UPDATE pm_archives SET status='archived',end_at=? WHERE id=?",now-86400000,old);
    List<JsonObject> people=new ArrayList<>(),logins=new ArrayList<>();for(UUID p:List.of(PLAYER,OTHER)){String name=p.equals(PLAYER)?"AlphaPlayer":"BetaPlayer";people.add(obj("id",p,"name",name,"joinCount",2,"totalOnlineMillis",3600000,"firstJoinEpochMillis",now-100000,"lastJoinEpochMillis",now));logins.add(obj("id",UUID.randomUUID(),"sessionId",UUID.randomUUID(),"uuid",p,"name",name,"at",now));}
    var ack=bridge(INSTANCE,"snapshot",obj("runId",run,"archiveId",current,"seq",1,"at",now,"snapshot",obj(),"players",people,"stats",people,"logins",logins,"acks",List.of(),"longTicks",List.of()));check(ack.has("communityRevision"),"Checkpoint token");
    sql(c,"INSERT INTO pm_players SELECT ?,uuid,name,name_sort,join_count,first_join,last_join,total_ms,FALSE,counter_warning,payload FROM pm_players WHERE archive_id=? AND uuid=?",old,current,PLAYER.toString());
    check(rpc(UUID.randomUUID(),"snapshot",obj()).has("error"),"Unadmitted player denied");
    check(bridge("foreign-community","rpc",envelope(PLAYER,"snapshot",obj())).has("error"),"Foreign instance denied");
    JsonObject forged=envelope(PLAYER,"snapshot",obj());forged.addProperty("playerProof",OTHER.toString());check(bridge(INSTANCE,"rpc",forged).has("error"),"Forged identity proof denied");
    check(rpc(PLAYER,"saveProfile",obj("playerId",OTHER,"patch",obj("bio","forged"),"expectedRevision","0")).has("error"),"Self write cannot select another player");
    check(rpc(PLAYER,"deleteNote",obj("archiveId",old,"expectedRevision","0")).has("error"),"Admin-only action absent from player route");
    JsonObject snap=result(PLAYER,"snapshot",obj());check(snap.has("options")&&snap.getAsJsonObject("memories").get("total").getAsInt()==2,"Self preload has real membership");
    check(snap.getAsJsonObject("memories").getAsJsonArray("archives").get(0).getAsJsonObject().get("id").getAsString().equals(current),"Current archive first");
    check(snap.getAsJsonObject("memories").getAsJsonArray("archives").get(1).getAsJsonObject().get("momentText").getAsString().isEmpty(),"Missing history stays empty");
    JsonObject shared=snap.getAsJsonObject("sharedArchiveCounts");
    check(shared!=null&&shared.keySet().equals(Set.of(OTHER.toString()))&&shared.get(OTHER.toString()).getAsInt()==1,
        "Shared archive counts include only the online peer and the one common archive");
    check(!result(OTHER,"profile",obj("playerId",PLAYER)).has("sharedArchiveCounts"),
        "Public profile does not expose online peer archive counts");
    check(!result(OTHER,"profile",obj("playerId",PLAYER)).has("options"),"Other-player profile omits private choices");
    check(!result(OTHER,"memory",obj("playerId",PLAYER,"archiveId",old)).has("candidates"),"Other-player memory omits private candidates");
    check(rpc(OTHER,"memory",obj("archiveId",old)).has("error"),"No invented archive membership");
    check(rpc(PLAYER,"profileOptions",obj("kind","fortunes","playerId",OTHER)).has("error"),"Private options always self");
    JsonObject initial=profile();String before=c.community.revision();
    JsonObject changed=result(PLAYER,"saveProfile",obj("patch",obj("bio","A durable biography","background","sage","profilePattern","nature"),"expectedRevision",initial.get("revision"))).getAsJsonObject("profile");
    check(!c.community.revision().equals(before)&&changed.get("bio").getAsString().equals("A durable biography"),"Committed profile updates token");
    check(rpc(PLAYER,"saveProfile",obj("patch",obj("bio","stale"),"expectedRevision",initial.get("revision"))).has("error"),"Profile CAS rejects stale revision");
    check(rpc(PLAYER,"saveProfile",obj("patch",obj("name","forged"),"expectedRevision",changed.get("revision"))).has("error"),"Immutable profile fields rejected");
    JsonObject note=memory(PLAYER,current).getAsJsonObject("note");
    result(PLAYER,"saveNote",obj("archiveId",current,"text","Current original","expectedRevision",note.get("revision")));
    check(rpc(PLAYER,"saveNote",obj("archiveId",old,"text","rewrite history","expectedRevision","0")).has("error"),"Historical originals immutable to self");
    check(rpc(PLAYER,"appendSupplement",obj("archiveId",current,"text","wrong type","operationId",UUID.randomUUID())).has("error"),"Current archive uses original note");
    String operation=UUID.randomUUID().toString();JsonObject append=obj("archiveId",old,"text","Historical supplement","operationId",operation);
    JsonObject supplement=result(PLAYER,"appendSupplement",append).getAsJsonArray("supplements").get(0).getAsJsonObject();String appendRevision=c.community.revision();result(PLAYER,"appendSupplement",append);check(c.community.revision().equals(appendRevision),"Idempotent retry emits no false change token");
    check(memory(PLAYER,old).get("supplementsTotal").getAsInt()==1,"Operation retry creates one supplement");
    JsonObject altered=append.deepCopy();altered.addProperty("text","changed");check(rpc(PLAYER,"appendSupplement",altered).has("error"),"Operation payload mismatch rejected");
    check(rpc(OTHER,"deleteSupplement",obj("archiveId",old,"supplementId",supplement.get("id"),"expectedRevision",supplement.get("revision"))).has("error"),"Other player cannot delete supplement");
    sql(c,"UPDATE ph_community_supplements SET created_at=? WHERE id=?",now-86400001,supplement.get("id").getAsString());
    check(rpc(PLAYER,"deleteSupplement",obj("archiveId",old,"supplementId",supplement.get("id"),"expectedRevision",supplement.get("revision"))).has("error"),"24-hour deadline enforced in transaction");
    post("/save-supplement",obj("playerId",PLAYER,"archiveId",old,"supplementId",supplement.get("id"),"text","Admin corrected old supplement","expectedRevision",supplement.get("revision")));
    post("/save-note",obj("playerId",PLAYER,"archiveId",old,"text","Admin historical original","expectedRevision","0"));
    var adminNote=get("/memory?playerId="+PLAYER+"&archiveId="+old).getAsJsonObject("note");
    check(req(admin,"/api/playerhub/community/delete-note",obj("playerId",PLAYER,"archiveId",old,"confirm",false,"expectedRevision",adminNote.get("revision")),true).statusCode()==400,"Admin delete needs explicit true");
    post("/delete-note",obj("playerId",PLAYER,"archiveId",old,"confirm",true,"expectedRevision",adminNote.get("revision")));
    check(memory(PLAYER,old).getAsJsonObject("note").get("deleted").getAsBoolean(),"Deleted original stays tombstoned");
    JsonObject latest=memory(PLAYER,old).getAsJsonArray("supplements").get(0).getAsJsonObject();
    post("/delete-supplement",obj("playerId",PLAYER,"archiveId",old,"supplementId",latest.get("id"),"confirm",true,"expectedRevision",latest.get("revision")));
    check(rpc(PLAYER,"appendSupplement",append).has("error")&&memory(PLAYER,old).get("supplementsTotal").getAsInt()==0,"Deleted operation cannot revive content");
    for(int n=0;n<125;n++){LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(n+2);long at=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();JsonObject record=obj("id",PLAYER+":"+date,"player",PLAYER,"date",date.toString(),"state","archived","at",at,"snapshot",obj("id",n+1,"title","Fortune "+n,"grade","吉","poem","Source poem"));sql(c,"INSERT INTO df_records(player,day,state,at_ms,fortune,pending,payload) VALUES(?,?,?,?,?,FALSE,?)",PLAYER.toString(),date.toString(),"archived",at,n+1,record.toString());}
    JsonObject fortunes=result(PLAYER,"profileOptions",obj("kind","fortunes","page",1));check(fortunes.get("total").getAsInt()==125&&fortunes.getAsJsonArray("rows").size()==25&&fortunes.get("playerId").getAsString().equals(PLAYER.toString()),"Fortune choices page beyond first 100");
    JsonObject candidates=result(PLAYER,"memoryOptions",obj("archiveId",old,"page",1));check(candidates.get("total").getAsInt()==125&&candidates.getAsJsonArray("rows").size()==25&&candidates.get("archiveId").getAsString().equals(old),"Archive acquisition choices paginate in SQL");
    check(result(PLAYER,"memoryOptions",obj("archiveId",current)).get("total").getAsInt()==0,"Acquisition interval excludes historical events");
    String selectedDate=fortunes.getAsJsonArray("rows").get(0).getAsJsonObject().get("date").getAsString();
    var priorProfile=profile();result(PLAYER,"saveProfile",obj("patch",obj("featuredFortuneDate",selectedDate,"featuredMemoryId",old),"expectedRevision",priorProfile.get("revision")));
    check(c.platform.request("dailyfortunes",obj("op","storage.get","player",PLAYER,"field","featured_date"),Set.of()).get("value").getAsString().equals(selectedDate),"Featured fortune reuses owner preference field");
    JsonObject collection=memory(PLAYER,old).getAsJsonObject("collections"),choice=candidates.getAsJsonArray("rows").get(0).getAsJsonObject();
    result(PLAYER,"saveCollections",obj("archiveId",old,"selection",obj("titleEventId",null,"fortuneEventId",choice.get("eventId")),"expectedRevision",collection.get("revision")));
    check(memory(PLAYER,old).getAsJsonObject("collections").getAsJsonArray("items").size()==1,"Acquired source selection visible");
    JsonObject currentCollection=memory(PLAYER,current).getAsJsonObject("collections");check(rpc(PLAYER,"saveCollections",obj("archiveId",current,"selection",obj("titleEventId",null,"fortuneEventId",choice.get("eventId")),"expectedRevision",currentCollection.get("revision"))).has("error"),"Cannot showcase event outside archive interval");
    c.titles.saveTitle(TitleService.Actor.consoleActor(),new Model.Title("community-title",Model.RichText.plain("Selected title"),"Test title","test","test","",0,0,true,true,0),0).get(10,TimeUnit.SECONDS);
    c.titles.login(PLAYER,"AlphaPlayer").get(10,TimeUnit.SECONDS);c.titles.grant(TitleService.Actor.consoleActor(),List.of(PLAYER),"community-title","isolation").get(10,TimeUnit.SECONDS);
    result(PLAYER,"saveProfile",obj("patch",obj("featuredTitleId","community-title"),"expectedRevision",profile().get("revision")));
    JsonObject sorted=get("/players?instance="+INSTANCE+"&sort=collection&direction=asc&size=1");check(sorted.get("total").getAsInt()==2&&sorted.getAsJsonArray("rows").get(0).getAsJsonObject().get("playerId").getAsString().equals(PLAYER.toString()),"Title/fortune collection sorting occurs before server page with null last");
    for(int n=0;n<25;n++)sql(c,"INSERT INTO ph_community_supplements VALUES(?,?,?,?,?,FALSE,1,?,?)",UUID.randomUUID().toString(),OTHER.toString(),current,"Pagination "+n,digest("Pagination "+n),now+n,now+n);
    JsonObject supplementPage=result(OTHER,"memory",obj("archiveId",current,"page",1));check(supplementPage.get("supplementsTotal").getAsInt()==25&&supplementPage.getAsJsonArray("supplements").size()==5&&!supplementPage.get("supplementsHasMore").getAsBoolean(),"Supplement pages retain truthful total and reachable older rows");
    check(get("/archive-players?instance="+INSTANCE+"&archiveId="+old+"&sort=lastLogin").get("total").getAsInt()==1,"Archive players use real membership");
    JsonObject checkpoint=get("/profile?playerId="+PLAYER).getAsJsonObject("profile");
    sql(c,"CREATE TRIGGER community_fail_profile BEFORE INSERT ON ph_community_profiles FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated transaction failure'");
    var fail=req(admin,"/api/playerhub/community/save-profile",obj("playerId",PLAYER,"patch",obj("featuredFortuneDate",null,"bio","must roll back"),"expectedRevision",checkpoint.get("revision")),true);
    check(fail.statusCode()==503,"Transaction SQL failure returns unavailable");sql(c,"DROP TRIGGER community_fail_profile");
    check(profile().get("bio").equals(checkpoint.get("bio"))&&c.platform.request("dailyfortunes",obj("op","storage.get","player",PLAYER,"field","featured_date"),Set.of()).get("value").getAsString().equals(selectedDate),"Profile and owner preference roll back together");
    String cas=profile().get("revision").getAsString();ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<JsonObject>> writes=new ArrayList<>();for(int n=0;n<2;n++){final int k=n;writes.add(pool.submit(()->rpc(PLAYER,"saveProfile",obj("patch",obj("bio","Concurrent "+k),"expectedRevision",cas))));}int successes=0;for(var f:writes)if(f.get(15,TimeUnit.SECONDS).has("result"))successes++;check(successes==1,"Concurrent same-revision profile writes have one winner");}finally{pool.shutdownNow();}
    sql(c,"DELETE FROM df_records WHERE player=? AND day=?",PLAYER.toString(),choice.getAsJsonObject("item").get("date").getAsString());c.community.changed();
    check(memory(PLAYER,old).getAsJsonObject("collections").getAsJsonArray("items").isEmpty(),"Source deletion withdraws selected collection immediately");
    check(c.platform.durableTables().containsAll(PlayerHubCommunityStore.TABLES),"All four tables belong to full backup");
    Path backup=c.backupAll();try(ZipFile z=new ZipFile(backup.toFile())){for(String table:PlayerHubCommunityStore.TABLES)check(z.getEntry("schema/"+table+".sql")!=null&&z.getEntry("database/"+table+".json")!=null,"Backup includes schema/data "+table);}
    ServiceConfig restoreConfig=new ServiceConfig(restoredDb,restoredDb,true,"127.0.0.1",c.config.port(),url,List.of(OWNER),"data",restoredDb,true,"unified",30);Path restoreFolder=folder.resolve("restored");ManagerRestore.restore(restoreConfig,restoreFolder,backup);ManagerStore restored=new ManagerStore(restoredDb);try(Connection db=restored.open()){check(rows(db,"SELECT * FROM ph_community_profiles WHERE player_uuid=?",PLAYER.toString()).size()==1,"Empty-schema restore retains profile");check(rows(db,"SELECT * FROM ph_community_supplements WHERE player_uuid=? AND deleted=TRUE",PLAYER.toString()).size()==1,"Backup retains supplement tombstone");}ManagerRestore.complete(restoreFolder);
    PlatformDeletion deletion=new PlatformDeletion(c.store,c.platform,c.web.avatars);JsonObject preview=deletion.preview(PLAYER,Set.of());for(String table:PlayerHubCommunityStore.TABLES)check(preview.getAsJsonObject("counts").has(table),"Player deletion owns "+table);String recovery=deletion.delete(OWNER,PLAYER,preview.get("stamp").getAsString(),"AlphaPlayer",Set.of());
    for(String table:PlayerHubCommunityStore.TABLES)check(count(c,"SELECT COUNT(*) n FROM "+table+" WHERE player_uuid=?",PLAYER.toString())==0,"Delete removes "+table);check(rpc(PLAYER,"snapshot",obj()).has("error"),"Old admission cannot read deleted player");deletion.restore(OWNER,recovery,Set.of());
    check(profile().get("featuredMemoryId").getAsString().equals(old)&&memory(PLAYER,old).getAsJsonObject("note").get("deleted").getAsBoolean(),"Player restore retains selections and tombstones");
    check(count(c,"SELECT COUNT(*) n FROM pm_audit WHERE action LIKE 'community.%'")>=8,"Community admin/player writes audited");
    saved.add(result(PLAYER,"snapshot",obj()));saved.add(get("/players?sort=name"));saved.add(memory(PLAYER,old));Files.writeString(folder.resolve("safe-community-dtos.json"),saved.toString());
    Map<String,JsonObject> dtos=new LinkedHashMap<>();
    dtos.put("snapshot",result(PLAYER,"snapshot",obj()));dtos.put("profile-self",result(PLAYER,"profile",obj()));dtos.put("profile-other",result(OTHER,"profile",obj("playerId",PLAYER)));
    dtos.put("memories-self",result(PLAYER,"memories",obj()));dtos.put("memories-other",result(OTHER,"memories",obj("playerId",PLAYER)));
    dtos.put("memory-current-self",memory(PLAYER,current));dtos.put("memory-history-self",memory(PLAYER,old));dtos.put("memory-history-other",result(OTHER,"memory",obj("playerId",PLAYER,"archiveId",old)));
    dtos.put("memory-options",result(PLAYER,"memoryOptions",obj("archiveId",old)));dtos.put("memory-options-page1",result(PLAYER,"memoryOptions",obj("archiveId",old,"page",1)));
    for(String kind:List.of("titles","fortunes","memories"))dtos.put("profile-options-"+kind,result(PLAYER,"profileOptions",obj("kind",kind)));
    Path dtoFolder=folder.resolve("dto");Files.createDirectories(dtoFolder);for(var entry:dtos.entrySet())Files.writeString(dtoFolder.resolve(entry.getKey()+".json"),entry.getValue().toString());
    if("1".equals(System.getenv("PM_COMMUNITY_VISUAL_HOLD"))){
      result(PLAYER,"appendSupplement",obj("archiveId",old,"text","Disposable UI verification supplement","operationId",UUID.randomUUID()));
      Files.writeString(folder.resolve("visual.json"),obj("url",url,"playerId",PLAYER,"oldArchive",old,"currentArchive",current,"stopFile",folder.resolve("stop").toString()).toString());
      System.out.println("ISOLATED_COMMUNITY_UI_READY");
      long until=System.currentTimeMillis()+15*60_000;
      while(System.currentTimeMillis()<until&&!Files.exists(folder.resolve("stop")))Thread.sleep(200);
    }
  }
  public static void main(String[] args)throws Exception{
    if(!"isolated".equals(System.getenv("PM_HUB_TEST_SCOPE")))throw new IllegalStateException("Disposable launcher required");String base=System.getenv("PM_COMMUNITY_JDBC_BASE");if(base==null||!base.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("Isolated loopback database required");Path folder=Path.of(System.getenv("PM_HUB_TEST_OUTPUT"));String schema="pm_community_"+UUID.randomUUID().toString().replace("-",""),restored=schema+"_restore";
    try(Connection db=DriverManager.getConnection(base,"root","")){db.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");db.createStatement().execute("CREATE DATABASE "+restored+" CHARACTER SET utf8mb4");}
    try{
      int port;try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=s.getLocalPort();}url="http://127.0.0.1:"+port;
      Database.Config db=new Database.Config(base+schema,"root","");ManagerStore prior=new ManagerStore(db);prior.migrate();new PlayerHubUpdatesStore(prior).migrate();
      String legacyEntry=UUID.randomUUID().toString(),legacyPack=UUID.randomUUID().toString();
      try(Connection connection=prior.open()){
        for(String table:PlayerHubCommunityStore.TABLES)connection.createStatement().execute("DROP TABLE "+table);
        update(connection,"UPDATE ph_settings SET comment_limit=777 WHERE id=1");
        update(connection,"INSERT INTO ph_packs(id,instance_id,name,version,created_at,updated_at) VALUES(?,?,?,?,?,?)",legacyPack,"legacy-check","Legacy retained pack","1",1,1);
        update(connection,"INSERT INTO ph_entries(id,pack_id,pack_name,pack_version,update_version,title,summary,category,body,pinned,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,FALSE,'draft',1,1)",legacyEntry,legacyPack,"Legacy","1","1","Legacy retained article","old summary","old category","old body");
      }
      PlatformStore oldPlatform=new PlatformStore(prior);oldPlatform.migrate();JsonObject manifest=PlayerHubUpdatesStore.manifest();manifest.addProperty("version",2);JsonArray tables=new JsonArray();for(String t:PlayerHubUpdatesStore.TABLES)if(!PlayerHubCommunityStore.TABLES.contains(t))tables.add(t);manifest.add("tables",tables);JsonArray rules=new JsonArray();for(JsonElement r:manifest.getAsJsonArray("playerTables"))if(!PlayerHubCommunityStore.TABLES.contains(r.getAsJsonObject().get("table").getAsString()))rules.add(r);manifest.add("playerTables",rules);oldPlatform.register("playerhub",manifest);
      ServiceConfig cfg=new ServiceConfig(db,db,true,"127.0.0.1",port,url,List.of(OWNER),"data",db,true,"unified",30);
      try(ManagerController c=new ManagerController(cfg,folder)){
        try(Connection connection=c.store.open()){
          check(rows(connection,"SELECT version FROM pm_modules WHERE namespace='playerhub'").get(0).get("version").getAsInt()==PlayerHubUpdatesStore.manifest().get("version").getAsInt(),"Existing v2 manifest upgrades to the current version");
          for(String table:PlayerHubCommunityStore.TABLES)check(rows(connection,"SELECT COUNT(*) n FROM "+table).get(0).get("n").getAsInt()==0,"Community table created on existing schema "+table);
          check(rows(connection,"SELECT comment_limit FROM ph_settings WHERE id=1").get(0).get("comment_limit").getAsInt()==777,"Upgrade retains old settings");
          check(rows(connection,"SELECT body FROM ph_entries WHERE id=?",legacyEntry).get(0).get("body").getAsString().equals("old body"),"Upgrade retains old article data");
        }
        boolean downgrade=false;try{c.platform.register("playerhub",manifest);}catch(IllegalStateException expected){downgrade=true;}check(downgrade,"Old manifest cannot silently downgrade upgraded schema");
        run(c,folder,new Database.Config(base+restored,"root",""));
      }
      System.out.println("PlayerHub community HTTP/SQL checks passed: "+checks);
    }finally{try(Connection db=DriverManager.getConnection(base,"root","")){db.createStatement().execute("DROP DATABASE "+schema);db.createStatement().execute("DROP DATABASE "+restored);}}
  }
}
