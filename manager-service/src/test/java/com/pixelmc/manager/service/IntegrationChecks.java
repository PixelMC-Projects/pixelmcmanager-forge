package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.*;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Only an isolated loopback database on port 43317. Never reads production configuration. */
public final class IntegrationChecks {
  static int assertions;
  static String schema="pm_check_"+UUID.randomUUID().toString().replace("-","");
  static String titleSchema=schema+"_titles",fortuneSchema=schema+"_fortunes",run=UUID.randomUUID().toString(),world=UUID.randomUUID().toString(),player=UUID.randomUUID().toString();
  static String token=UUID.randomUUID()+":"+UUID.randomUUID();
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  interface Throwing{void run()throws Exception;}
  static void denied(Throwing action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(IllegalStateException|IllegalArgumentException|SecurityException expected){assertions++;}}
  static JsonObject obj(String value){return JsonParser.parseString(value).getAsJsonObject();}
  static JsonObject hello(String id,String worldId){return obj("{runId:'"+id+"',worldId:'"+worldId+"',startedAt:"+(System.currentTimeMillis()-10000)+",info:{}}");}
  static JsonObject profile(long joins,long millis){JsonObject p=obj("{id:'"+player+"',name:'LocalTester',joinCount:"+joins+",totalOnlineMillis:"+millis+",firstJoinEpochMillis:100,lastJoinEpochMillis:200,ping:55,world:'minecraft:overworld',sessionMillis:5000}");return p;}
  static JsonObject packet(String archive,long seq){JsonObject p=obj("{runId:'"+run+"',archiveId:'"+archive+"',seq:"+seq+",at:"+System.currentTimeMillis()+",snapshot:{},players:[],stats:[],logins:[],acks:[],longTicks:[]}");p.getAsJsonArray("stats").add(profile(3,3000));p.getAsJsonArray("players").add(profile(3,3000));JsonObject metric=obj("{at:"+System.currentTimeMillis()+",tps:20,mspt:12,heap:0.3}");p.add("metric",metric);JsonObject s=obj("{metric:{tps:20,mspt:12,heap:0.3},worlds:[],players:[]}");p.add("snapshot",s);return p;}
  static String create(ManagerStore store,String worldId)throws Exception{return store.createArchive(obj("{instanceId:'local-test',worldId:'"+worldId+"',name:'隔离整合包',version:'test',startedOn:'2026-10-24',active:true}"),"test").get("id").getAsString();}
  public static void main(String[] args)throws Exception{
    System.setProperty("jdk.httpclient.allowRestrictedHeaders","host");
    String base=System.getenv().getOrDefault("PM_TEST_JDBC_BASE","jdbc:mariadb://127.0.0.1:43317/");
    if(!base.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("Only an isolated loopback database may be tested");
    String testPassword=System.getenv().getOrDefault("PM_TEST_PASSWORD", "");
    try(Connection c=DriverManager.getConnection(base,"root",testPassword)){c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");c.createStatement().execute("CREATE DATABASE "+titleSchema+" CHARACTER SET utf8mb4");c.createStatement().execute("CREATE DATABASE "+fortuneSchema+" CHARACTER SET utf8mb4");}
    Path folder=Files.createTempDirectory(Path.of("manager-service"),".integration-");
    var db=new Database.Config(base+schema,"root",testPassword);var titleDb=new Database.Config(base+titleSchema,"root",testPassword);
    try{
      ManagerStore store=new ManagerStore(db);store.migrate();store.register("local-test",token);check(store.authenticate("local-test",token),"token works");check(!store.authenticate("local-test","wrong"),"wrong token denied");
      check(store.hello("local-test",hello(run,world)).get("bindingRequired").getAsBoolean(),"unbound world reported");denied(()->store.hello("local-test",hello(UUID.randomUUID().toString(),UUID.randomUUID().toString())),"hello reserves connection lease before first telemetry");String archive=create(store,world);
      check(!store.state("local-test","").get("connected").getAsBoolean(),"hello is not live telemetry");
      String eventId=UUID.randomUUID().toString();JsonObject earlyLogin=obj("{id:'"+eventId+"',sessionId:'session-test',uuid:'"+player+"',name:'LocalTester',at:"+System.currentTimeMillis()+"}");
      JsonObject early=packet(archive,1);early.add("stats",new JsonArray());early.add("players",new JsonArray());early.getAsJsonArray("logins").add(earlyLogin);
      check(store.ingest("local-test",early).get("accepted").getAsBoolean(),"admitted login before checkpoint does not stall telemetry");
      check(store.players(archive,Map.of(),true).get("total").getAsInt()==0,"login journal alone never creates player profiles");
      check(store.logins(archive,player,0,8).get("total").getAsInt()==1,"early login is durably preserved");
      check(!store.ingest("local-test",early).get("accepted").getAsBoolean(),"early login packet retry is idempotent");
      JsonObject legacy=obj("{version:1,players:{}}");JsonObject old=profile(2,2000);old.remove("id");legacy.getAsJsonObject("players").add(player,old);
      check(store.importLegacy(archive,legacy,"test",false).get("players").getAsInt()==1,"legacy preview");store.importLegacy(archive,legacy,"test",true);check(store.importLegacy(archive,legacy,"test",true).get("alreadyImported").getAsBoolean(),"legacy import idempotent");
      JsonObject p=packet(archive,2);p.getAsJsonArray("logins").add(earlyLogin.deepCopy());
      for(long[] t:new long[][]{{1,51},{2,60},{4,70},{5,99},{7,100},{8,200},{9,201}})p.getAsJsonArray("longTicks").add(obj("{tickIndex:"+t[0]+",duration:"+t[1]+",at:"+System.currentTimeMillis()+"}"));
      check(store.ingest("local-test",p).get("accepted").getAsBoolean(),"snapshot committed");check(!store.ingest("local-test",p).get("accepted").getAsBoolean(),"snapshot replay deduplicated");
      check(store.state("local-test","").get("connected").getAsBoolean(),"real snapshot live");check(store.logins(archive,player,0,8).get("total").getAsInt()==1,"login deduplicated");
      var grouped=store.longTickPage("local-test",run,50,0,System.currentTimeMillis());check(grouped.get("total").getAsInt()==5&&grouped.get("events").getAsInt()==7,"true consecutive groups only");check(store.longTickPage("local-test",run,100,0,System.currentTimeMillis()).get("events").getAsInt()==2,"strict threshold excludes exact100");
      var page=store.players(archive,Map.of("sort","status"),true);check(page.getAsJsonArray("rows").get(0).getAsJsonObject().get("isOnline").getAsBoolean(),"JDBC booleans normalized");check(store.lifetime(player).get("joinCount").getAsLong()==3,"aggregate snapshots not added twice");
      denied(()->store.archive(archive,"test"),"live archive denied");try(Connection c=store.open()){ManagerStore.update(c,"UPDATE pm_instances SET last_seen=? WHERE id='local-test'",System.currentTimeMillis()-20000);}denied(()->store.archive(archive,"test"),"disconnect cannot finalize archive");
      JsonObject rollback=packet(archive,3);rollback.getAsJsonArray("stats").set(0,profile(1,500));rollback.getAsJsonArray("players").set(0,profile(1,500));store.ingest("local-test",rollback);check(store.state("local-test","").getAsJsonObject("totals").get("counterWarnings").getAsInt()==1,"lower restored checkpoint is reported instead of silently resummed");
      JsonObject terminal=packet(archive,4);terminal.addProperty("stopped",true);store.ingest("local-test",terminal);check(!store.ingest("local-test",terminal).get("accepted").getAsBoolean(),"terminal lost-response retry safe");store.archive(archive,"test");check(store.state("local-test",archive).get("historical").getAsBoolean(),"archived data read-only");
      String previous=run;run=UUID.randomUUID().toString();world=UUID.randomUUID().toString();store.hello("local-test",hello(run,world));String next=create(store,world);store.ingest("local-test",packet(next,1));denied(()->store.ingest("local-test",p),"old run cannot resurrect");check(store.metrics("local-test",previous,43200000,System.currentTimeMillis()).isEmpty(),"old performance hidden immediately");check(store.metrics("local-test",run,43200000,System.currentTimeMillis()).size()==1,"new performance independent");
      check(store.state("local-test",archive).get("archiveId").getAsString().equals(archive),"history scope independent");check(store.state("local-test","").get("archiveId").getAsString().equals(next),"active write binding unchanged");check(store.lifetime(player).get("joinCount").getAsLong()==6,"cross-pack totals separate from per-pack");store.cleanup(System.currentTimeMillis());check(store.players(archive,Map.of(),false).get("total").getAsInt()==1,"cleanup keeps durable players");
      UUID owner=UUID.randomUUID();var config=new ServiceConfig(db,titleDb,true,"127.0.0.1",18801,"http://127.0.0.1:18801",List.of(owner),"data",new Database.Config(base+fortuneSchema,"root",testPassword),true);
      try(ManagerController controller=new ManagerController(config,folder)){
        controller.web=new PlatformWebServer(controller,new WebConfig(true,"127.0.0.1",18801,"http://127.0.0.1:18801",480,List.of()),folder.resolve("platform"));controller.web.accounts.create("localadmin",owner,"Test-Only-Pass-123!");
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();String url=config.publicUrl();
        check(send(client,url,"/api/manager/bootstrap",null,null,null).statusCode()==401,"anonymous API rejected");check(send(client,url,"/api/dailyfortunes/state",null,null,null).statusCode()==401,"anonymous fortune API rejected");var login=send(client,url,"/api/login",obj("{username:'localadmin',password:'Test-Only-Pass-123!'}"),null,url);check(login.statusCode()==200,"real username login");String csrf=JsonParser.parseString(login.body()).getAsJsonObject().get("csrf").getAsString();
        check(send(client,url,"/api/manager/bootstrap",null,null,null).statusCode()==200,"real bootstrap");check(send(client,url,"/api/manager/avatars/"+owner+".png",null,null,null).statusCode()==200,"manager avatar fallback independent of title player profile");check(send(client,url,"/api/manager/backup",new JsonObject(),null,url).statusCode()==403,"missing CSRF blocked");check(send(client,url,"/api/manager/backup",new JsonObject(),csrf,"http://evil.invalid").statusCode()==403,"foreign origin blocked");
        fortuneChecks(client,url,csrf,next);
        JsonObject rpc=obj("{extension:'memorialtitles',op:'login',data:{args:['"+player+"','LocalTester'],revision:''}}");check(bridge(client,url,rpc).get("error").getAsString().contains("身份"),"unproved login rejected");rpc.addProperty("playerProof",player);JsonObject response=bridge(client,url,rpc);check(!response.has("error")&&response.has("snapshot"),"title RPC snapshot transfer");String revision=response.get("revision").getAsString();rpc.addProperty("op","reconnect");rpc.getAsJsonObject("data").add("args",new JsonArray());var dns=HttpRequest.newBuilder(URI.create(url+"/bridge/rpc")).header("Host","pixelmc-manager-web:18801").header("Content-Type","application/json").header("X-PixelMC-Instance","local-test").header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(rpc))).build();check(client.send(dns,HttpResponse.BodyHandlers.ofString()).statusCode()==200,"container DNS bridge authority accepted without weakening browser origin checks");rpc.addProperty("op","reconnect");rpc.getAsJsonObject("data").add("args",new JsonArray());rpc.getAsJsonObject("data").addProperty("revision",revision);check(!bridge(client,url,rpc).has("snapshot"),"unchanged title snapshot not rebroadcast");
        JsonObject reminder=obj("{extension:'memorialtitles',op:'setUnequippedReminder',data:{args:['"+player+"',false],revision:''}}");
        check(bridge(client,url,reminder).has("error")&&controller.titles.snapshot().displayPreferences(UUID.fromString(player)).reminderEnabled(),"reminder RPC without admission proof rejected");
        reminder.addProperty("playerProof",UUID.randomUUID().toString());check(bridge(client,url,reminder).has("error"),"reminder RPC cannot name another proved player");
        reminder.addProperty("playerProof",player);check(!bridge(client,url,reminder).has("error")&&!controller.titles.snapshot().displayPreferences(UUID.fromString(player)).reminderEnabled(),"proved reminder off persists through Manager bridge");
        reminder.getAsJsonObject("data").getAsJsonArray("args").set(1,new JsonPrimitive("true"));check(bridge(client,url,reminder).has("error")&&!controller.titles.snapshot().displayPreferences(UUID.fromString(player)).reminderEnabled(),"reminder RPC rejects non-boolean parameters without changing preference");
        reminder.getAsJsonObject("data").getAsJsonArray("args").set(1,new JsonPrimitive(true));check(!bridge(client,url,reminder).has("error")&&controller.titles.snapshot().displayPreferences(UUID.fromString(player)).reminderEnabled(),"proved reminder on restores enabled default");
        controller.titles.toggleDisplayPosition(UUID.fromString(player),cn.pixelmc.memorialtitles.domain.Model.DisplayPosition.HEAD).get();
        try(Connection c=new Database(titleDb).open()){ManagerStore.update(c,"INSERT INTO mt_notifications(player,title,title_name,granted,created_at) VALUES(?,?,?,?,?)",player,"historical-title","{\"segments\":[],\"gradients\":[]}",true,System.currentTimeMillis());}
        Path backup=controller.backupAll();try(var zip=new java.util.zip.ZipFile(backup.toFile())){check(zip.getEntry("database/pm_metrics.json")==null&&zip.getEntry("database/pm_long_ticks.json")==null&&zip.getEntry("database/pm_tick_groups.json")==null,"performance excluded from backup");check(zip.getEntry("database/pm_logins.json")!=null,"durable logins backed up");}
        String restoredDb=schema+"_restored",restoredTitles=titleSchema+"_restored";
        try(Connection c=DriverManager.getConnection(base,"root",testPassword)){c.createStatement().execute("CREATE DATABASE "+restoredDb+" CHARACTER SET utf8mb4");c.createStatement().execute("CREATE DATABASE "+restoredTitles+" CHARACTER SET utf8mb4");}
        try{
          Path restoreData=folder.resolve("restore");var destination=new ServiceConfig(new Database.Config(base+restoredDb,"root",testPassword),new Database.Config(base+restoredTitles,"root",testPassword),true,"127.0.0.1",18802,"http://127.0.0.1:18802",List.of(),"data");
          Path legacyBackup=legacyReminderBackup(backup);
          ServiceConfig restored=ManagerRestore.restore(destination,restoreData,legacyBackup);ManagerRestore.complete(restoreData);try(Connection c=new Database(restored.titlesDatabase()).open()){check(ManagerStore.rows(c,"SELECT id FROM mt_notifications").size()==1,"unread offline notices included in full backup");}
          try(Connection c=new Database(restored.titlesDatabase()).open()){var prefs=ManagerStore.rows(c,"SELECT reminder_disabled FROM mt_display_preferences WHERE player=\""+player+"\"");check(prefs.size()==1&&!prefs.get(0).get("reminder_disabled").getAsBoolean(),"legacy Manager backup without reminder column restores enabled default");}
          check(restored.owners().contains(owner),"backup restores owner identities");check(new ManagerStore(restored.database()).lifetime(player).get("joinCount").getAsLong()==6,"empty DB restore preserves durable counters");check(new PlatformAccounts(restoreData.resolve("platform")).verify("localadmin","Test-Only-Pass-123!")!=null,"backup restores username/password hash");
          denied(()->ManagerRestore.restore(destination,restoreData,legacyBackup),"restore cannot overwrite populated database");
        }finally{try(Connection c=DriverManager.getConnection(base,"root",testPassword)){c.createStatement().execute("DROP DATABASE "+restoredDb);c.createStatement().execute("DROP DATABASE "+restoredTitles);}}
        for(int i=0;i<10;i++)send(client,url,"/api/login",obj("{username:'bad',password:'bad'}"),null,url);check(send(client,url,"/api/login",obj("{username:'bad',password:'bad'}"),null,url).statusCode()==429,"login brute force limit");
      }
      JsonObject secondStop=packet(next,3);secondStop.addProperty("stopped",true);store.ingest("local-test",secondStop);String unboundWorld=UUID.randomUUID().toString(),unboundRun=UUID.randomUUID().toString();check(store.hello("local-test",hello(unboundRun,unboundWorld)).get("bindingRequired").getAsBoolean(),"new world does not reuse old archive binding");check(!store.bound("local-test"),"unbound world cannot issue previous pack first-login reward");store.archive(next,"test");check(store.archiveInfo(next).get("status").getAsString().equals("archived"),"previous world final sync still permits archive after a new world hello");
      UUID offlineOwner=UUID.randomUUID();var unavailable=new Database.Config("jdbc:mariadb://127.0.0.1:43318/unavailable","root","");
      try(ManagerController controller=new ManagerController(new ServiceConfig(unavailable,titleDb,false,"127.0.0.1",18803,"http://127.0.0.1:18803",List.of(offlineOwner),"data"),folder.resolve("offline"))){
        controller.web=new PlatformWebServer(controller,new WebConfig(true,"127.0.0.1",18803,"http://127.0.0.1:18803",480,List.of()),folder.resolve("offline/platform"));controller.web.accounts.create("offlineadmin",offlineOwner,"Test-Only-Pass-123!");var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();check(send(client,"http://127.0.0.1:18803","/api/login",obj("{username:'offlineadmin',password:'Test-Only-Pass-123!'}"),null,"http://127.0.0.1:18803").statusCode()==200,"DB outage does not lock local account login");check(send(client,"http://127.0.0.1:18803","/api/manager/bootstrap",null,null,null).statusCode()==503,"DB outage returns explicit unavailable state");
      }
      System.out.println("PASS: "+assertions+" Manager SQL/HTTP/lifecycle assertions; isolated schemas removed.");
    }finally{try(Connection c=DriverManager.getConnection(base,"root",testPassword)){c.createStatement().execute("DROP DATABASE IF EXISTS "+schema);c.createStatement().execute("DROP DATABASE IF EXISTS "+titleSchema);c.createStatement().execute("DROP DATABASE IF EXISTS "+fortuneSchema);}}
  }
  static void fortuneChecks(HttpClient client,String url,String csrf,String archive)throws Exception{
    check(send(client,url,"/api/dailyfortunes/settings",new JsonObject(),null,url).statusCode()==403,"fortune writes require CSRF");
    check(send(client,url,"/api/dailyfortunes/settings",new JsonObject(),csrf,"http://evil.invalid").statusCode()==403,"fortune writes reject foreign origin");
    JsonObject state=obj(send(client,url,"/api/dailyfortunes/state",null,null,null).body());check(state.getAsJsonObject("health").get("database").getAsBoolean(),"real fortune database connects");
    var page=obj(send(client,url,"/api/dailyfortunes/fortunes?size=7&page=1",null,null,null).body());check(page.getAsJsonArray("rows").size()==7&&page.get("total").getAsInt()==60,"fortune definitions use backend pagination");
    check(page.getAsJsonObject("categoryCounts").get("all").getAsInt()==60,"fortune category counts cover full pool");
    JsonObject rpc=obj("{extension:'dailyfortunes',op:'login',data:{args:['"+player+"','LocalTester']}}");
    check(bridge(client,url,rpc).has("error"),"fortune login without proof denied");rpc.addProperty("playerProof",player);check(bridge(client,url,rpc).has("error"),"fortune login before telemetry admission denied");
    var ingest=HttpRequest.newBuilder(URI.create(url+"/bridge/snapshot")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-PixelMC-Instance","local-test").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(packet(archive,2)))).build();
    check(obj(client.send(ingest,HttpResponse.BodyHandlers.ofString()).body()).get("accepted").getAsBoolean(),"fortune authority receives real bridge admission");
    check(!bridge(client,url,rpc).has("error"),"admitted fortune player registered");rpc.addProperty("op","draw");rpc.getAsJsonObject("data").getAsJsonArray("args").remove(1);
    JsonObject drawn=bridge(client,url,rpc);check(!drawn.has("error"),"fortune draw uses authoritative bridge");check(bridge(client,url,rpc).has("error"),"second daily draw rejected");
    JsonObject rows=obj(send(client,url,"/api/dailyfortunes/players?presence=online&sort=total&direction=desc",null,null,null).body());check(rows.get("total").getAsInt()==1&&rows.getAsJsonObject("presenceCounts").get("online").getAsInt()==1,"fortune presence counts from admitted humans");
    JsonObject record=drawn.getAsJsonObject("result").getAsJsonObject("record");String date=record.get("date").getAsString();
    JsonObject calendar=obj(send(client,url,"/api/dailyfortunes/calendar?player="+player+"&month="+date.substring(0,7),null,null,null).body());check(calendar.getAsJsonArray("records").size()==1&&!calendar.getAsJsonArray("records").get(0).getAsJsonObject().has("token"),"web calendar omits receipt tokens");
    JsonObject recovery=obj("{player:'"+player+"',date:'"+date+"',requestId:'web-test-recover-0001'}");check(send(client,url,"/api/dailyfortunes/recover",recovery,csrf,url).statusCode()==200,"web recovery durably queues original receipt");
    JsonObject definition=page.getAsJsonArray("rows").get(0).getAsJsonObject().deepCopy();definition.addProperty("weight",2);var saved=send(client,url,"/api/dailyfortunes/fortune",obj("{fortune:"+JSON.toJson(definition)+",expectedRevision:1}"),csrf,url);check(saved.statusCode()==200,"fortune update persisted");check(send(client,url,"/api/dailyfortunes/fortune",obj("{fortune:"+JSON.toJson(definition)+",expectedRevision:1}"),csrf,url).statusCode()==409,"stale fortune edit conflicts");
    check(send(client,url,"/api/dailyfortunes/backup",new JsonObject(),csrf,url).statusCode()==200,"fortune full backup created");var backups=obj(send(client,url,"/api/dailyfortunes/backups",null,null,null).body());String file=backups.getAsJsonArray("rows").get(0).getAsJsonObject().get("file").getAsString();
    check(obj(send(client,url,"/api/dailyfortunes/backup/verify?file="+file,null,null,null).body()).get("verified").getAsBoolean(),"fortune backup SHA256 verifies");
    check(send(client,url,"/api/dailyfortunes/backup/download?file=..%2Fmanager.json",null,null,null).statusCode()==400,"fortune backup path traversal denied");
    check(send(client,url,"/api/dailyfortunes/config/export",null,null,null).statusCode()==200,"fortune configuration exports independently");
    JsonObject configFile=obj(send(client,url,"/api/dailyfortunes/config/export",null,null,null).body());JsonObject importBody=new JsonObject();importBody.add("file",configFile);
    var preview=send(client,url,"/api/dailyfortunes/config/preview",importBody,csrf,url);check(preview.statusCode()==200,"fortune configuration preview returns server validation context");
    check(send(client,url,"/api/dailyfortunes/config/import",obj(preview.body()),csrf,url).statusCode()==200,"fortune import returns the complete preview context including concurrency stamp");
    var capabilities=HttpRequest.newBuilder(URI.create(url+"/bridge/capabilities")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-PixelMC-Instance","local-test").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    check(obj(client.send(capabilities,HttpResponse.BodyHandlers.ofString()).body()).get("titlesApi").getAsInt()>=3,"authenticated read-only startup capability gate reports API version");
  }
  static Path legacyReminderBackup(Path backup)throws Exception{
    Path legacy=backup.resolveSibling(backup.getFileName()+".legacy.zip");
    try(var source=new java.util.zip.ZipFile(backup.toFile());var output=new java.util.zip.ZipOutputStream(Files.newOutputStream(legacy))){
      for(var entries=source.entries();entries.hasMoreElements();){var entry=entries.nextElement();byte[] bytes;try(var input=source.getInputStream(entry)){bytes=input.readAllBytes();}
        if(entry.getName().equals("titles-database/mt_display_preferences.json")){JsonArray rows=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();for(JsonElement row:rows)row.getAsJsonObject().remove("reminder_disabled");bytes=JSON.toJson(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        if(entry.getName().equals("titles-database/mt_schema.json")){JsonArray rows=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray(),old=new JsonArray();for(JsonElement row:rows)if(row.getAsJsonObject().get("version").getAsInt()<6)old.add(row);bytes=JSON.toJson(old).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));output.write(bytes);output.closeEntry();
      }
    }
    Files.writeString(legacy.resolveSibling(legacy.getFileName()+".sha256"),ManagerBackup.checksum(legacy)+"  "+legacy.getFileName()+"\n");return legacy;
  }
  static HttpResponse<String> send(HttpClient client,String base,String path,JsonObject body,String csrf,String origin)throws Exception{var r=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(20));if(csrf!=null)r.header("X-CSRF-Token",csrf);if(origin!=null)r.header("Origin",origin);if(body!=null)r.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));return client.send(r.build(),HttpResponse.BodyHandlers.ofString());}
  static JsonObject bridge(HttpClient client,String base,JsonObject body)throws Exception{var r=HttpRequest.newBuilder(URI.create(base+"/bridge/rpc")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-PixelMC-Instance","local-test").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();var response=client.send(r,HttpResponse.BodyHandlers.ofString());check(response.statusCode()==200,"machine RPC envelope");return JsonParser.parseString(response.body()).getAsJsonObject();}
}
