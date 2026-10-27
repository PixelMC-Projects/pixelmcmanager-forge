package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import static com.pixelmc.manager.service.ManagerStore.*;
import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Platform identity and typed, optimistic, namespace-isolated extension storage. */
public final class PlatformStore {
  private final ManagerStore store;
  public PlatformStore(ManagerStore store){this.store=store;}
  public static final List<String> TABLES=List.of("pm_platform_schema","pm_platform_policy","pm_profiles","pm_profile_exclusions","pm_modules","pm_fields","pm_values","pm_admissions","pm_profile_revisions","pm_player_resets","pm_recovery");
  public void migrate()throws Exception{try(Connection c=store.open()){
    String[] ddl={
      "CREATE TABLE IF NOT EXISTS pm_platform_schema(version INT PRIMARY KEY) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_platform_policy(id VARCHAR(64) CHARACTER SET ascii PRIMARY KEY,value LONGTEXT NOT NULL) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_profiles(uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,name VARCHAR(64) NOT NULL,first_join BIGINT NOT NULL DEFAULT 0,last_seen BIGINT NOT NULL DEFAULT 0,revision BIGINT NOT NULL DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS pm_profile_exclusions(uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,reason VARCHAR(64) CHARACTER SET ascii NOT NULL,observed_at BIGINT NOT NULL) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_modules(namespace VARCHAR(64) CHARACTER SET ascii PRIMARY KEY,version INT NOT NULL,manifest LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS pm_fields(namespace VARCHAR(64) CHARACTER SET ascii NOT NULL,field_id VARCHAR(64) CHARACTER SET ascii NOT NULL,schema_version INT NOT NULL,spec LONGTEXT NOT NULL,PRIMARY KEY(namespace,field_id)) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_values(namespace VARCHAR(64) CHARACTER SET ascii NOT NULL,uuid CHAR(36) CHARACTER SET ascii NOT NULL,scope_id VARCHAR(36) CHARACTER SET ascii NOT NULL,field_id VARCHAR(64) CHARACTER SET ascii NOT NULL,value LONGTEXT NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(namespace,uuid,scope_id,field_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS pm_admissions(instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,uuid CHAR(36) CHARACTER SET ascii NOT NULL,event_id VARCHAR(128) CHARACTER SET ascii NOT NULL,archive_id CHAR(36) CHARACTER SET ascii NOT NULL,first_global BOOLEAN NOT NULL,first_archive BOOLEAN NOT NULL,at_ms BIGINT NOT NULL,PRIMARY KEY(instance_id,uuid)) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_profile_revisions(uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 1,deleted BOOLEAN NOT NULL DEFAULT FALSE,deleted_at BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_player_resets(archive_id CHAR(36) CHARACTER SET ascii NOT NULL,uuid CHAR(36) CHARACTER SET ascii NOT NULL,total_base BIGINT NOT NULL,join_base BIGINT NOT NULL,PRIMARY KEY(archive_id,uuid)) ENGINE=InnoDB",
      "CREATE TABLE IF NOT EXISTS pm_recovery(id CHAR(36) CHARACTER SET ascii PRIMARY KEY,uuid CHAR(36) CHARACTER SET ascii NOT NULL,actor CHAR(36) CHARACTER SET ascii NOT NULL,at_ms BIGINT NOT NULL,expected_revision BIGINT NOT NULL,payload LONGTEXT NOT NULL,restored BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"};
    for(String sql:ddl)try(Statement s=c.createStatement()){s.execute(sql);}
    update(c,"ALTER TABLE pm_profile_revisions ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE");update(c,"ALTER TABLE pm_profile_revisions ADD COLUMN IF NOT EXISTS deleted_at BIGINT NOT NULL DEFAULT 0");
    var versions=rows(c,"SELECT MAX(version) version FROM pm_platform_schema");if(!versions.get(0).get("version").isJsonNull()&&versions.get(0).get("version").getAsInt()>1)throw new IllegalStateException("Platform schema newer than service");
    update(c,"INSERT IGNORE INTO pm_platform_schema(version) VALUES(1)");store.enableProfiles();
  }}
  public static void identity(Connection c,String uuid,String name,long first,long last)throws Exception{
    Wire.uuid(uuid);if(name==null||name.isBlank()||name.length()>64)throw new IllegalArgumentException("Invalid profile name");
    if(deleted(c,uuid)||excluded(c,uuid))return;
    update(c,"INSERT INTO pm_profiles(uuid,name,first_join,last_seen) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision=revision+IF(VALUES(last_seen)>last_seen OR VALUES(name)<>name,1,0),name=IF(VALUES(last_seen)>=last_seen,VALUES(name),name),first_join=IF(first_join=0,VALUES(first_join),IF(VALUES(first_join)=0,first_join,LEAST(first_join,VALUES(first_join)))),last_seen=GREATEST(last_seen,VALUES(last_seen))",uuid,name,first,last);
    update(c,"INSERT IGNORE INTO pm_profile_revisions(uuid,revision) VALUES(?,1)",uuid);
  }
  public static boolean deleted(Connection c,String player)throws Exception{var status=rows(c,"SELECT deleted FROM pm_profile_revisions WHERE uuid=?",player);return !status.isEmpty()&&status.get(0).get("deleted").getAsBoolean();}
  public static boolean excluded(Connection c,String player)throws Exception{return !rows(c,"SELECT uuid FROM pm_profile_exclusions WHERE uuid=?",player).isEmpty();}
  public static void excludeFake(Connection c,String player)throws Exception{if(!rows(c,"SELECT uuid FROM pm_admissions WHERE uuid=? LIMIT 1",uuid(player)).isEmpty())return;update(c,"INSERT INTO pm_profile_exclusions(uuid,reason,observed_at) VALUES(?,'verified-fake',?) ON DUPLICATE KEY UPDATE observed_at=VALUES(observed_at)",player,System.currentTimeMillis());}
  public static boolean acceptLogin(Connection c,String player,long at)throws Exception{var status=rows(c,"SELECT deleted_at FROM pm_profile_revisions WHERE uuid=?",player);return status.isEmpty()||at>status.get(0).get("deleted_at").getAsLong();}
  /** Pure, replay-safe import; never calls module login/reward/receipt paths. */
  public long backfill()throws Exception{try(Connection c=store.open()){
    c.setAutoCommit(false);try{
      Set<String> fake=new HashSet<>();for(JsonObject p:rows(c,"SELECT DISTINCT uuid FROM pm_players WHERE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.fake')),'false') IN ('true','1')")){String player=text(p,"uuid",36);fake.add(player);excludeFake(c,player);}
      for(JsonObject p:rows(c,"SELECT uuid,name,first_join,last_join FROM pm_players WHERE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.fake')),'false') NOT IN ('true','1') ORDER BY last_join"))identity(c,text(p,"uuid",36),text(p,"name",64),p.get("first_join").getAsLong(),p.get("last_join").getAsLong());
      if(tableExists(c,"mt_players"))for(JsonObject p:rows(c,"SELECT uuid,name,joined_at,last_seen FROM mt_players ORDER BY last_seen"))if(!fake.contains(text(p,"uuid",36)))identity(c,text(p,"uuid",36),text(p,"name",64),p.get("joined_at").getAsLong(),p.get("last_seen").getAsLong());
      if(tableExists(c,"df_players"))for(JsonObject p:rows(c,"SELECT uuid,name,0 first_seen,last_seen FROM df_players ORDER BY last_seen"))if(!fake.contains(text(p,"uuid",36)))identity(c,text(p,"uuid",36),text(p,"name",64),p.get("first_seen").getAsLong(),p.get("last_seen").getAsLong());
      c.commit();return rows(c,"SELECT COUNT(*) count FROM pm_profiles WHERE uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)").get(0).get("count").getAsLong();
    }catch(Exception e){c.rollback();throw e;}
  }}
  public static boolean tableExists(Connection c,String table)throws SQLException{return !rows(c,"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",table).isEmpty();}
  public int retention(int fallback)throws Exception{try(Connection c=store.open()){var value=rows(c,"SELECT value FROM pm_platform_policy WHERE id='backupRetentionDays'");return value.isEmpty()?fallback:Integer.parseInt(value.get(0).get("value").getAsString());}}
  public void retention(int days,String actor)throws Exception{if(days<1||days>3650)throw new IllegalArgumentException("备份保留天数须为1–3650");try(Connection c=store.open()){update(c,"INSERT INTO pm_platform_policy(id,value) VALUES('backupRetentionDays',?) ON DUPLICATE KEY UPDATE value=VALUES(value)",Integer.toString(days));audit(c,actor,"PLATFORM_BACKUP_RETENTION",Map.of("days",days));}}
  public JsonObject profiles()throws Exception{try(Connection c=store.open()){
    var list=rows(c,"SELECT uuid id,name,first_join firstJoinedAt,first_join joinedAt,last_seen lastSeen,revision FROM pm_profiles WHERE uuid NOT IN(SELECT uuid FROM pm_profile_exclusions) ORDER BY name,uuid");JsonObject result=new JsonObject();result.add("players",JSON.toJsonTree(list));result.addProperty("revision",list.stream().mapToLong(p->p.get("revision").getAsLong()).sum());return result;
  }}
  public JsonObject profile(String player)throws Exception{try(Connection c=store.open()){var list=rows(c,"SELECT uuid id,name,first_join firstJoinedAt,first_join joinedAt,last_seen lastSeen,revision FROM pm_profiles WHERE uuid=? AND uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)",uuid(player));JsonObject result=new JsonObject();result.add("profile",list.isEmpty()?JsonNull.INSTANCE:list.get(0));return result;}}
  public static void admission(Connection c,String instance,String archive,JsonObject p)throws Exception{
    String player=uuid(text(p,"uuid",36)),event=text(p,"id",128);long at=number(p,"at",0,1,Long.MAX_VALUE);if(!acceptLogin(c,player,at))return;var revisions=rows(c,"SELECT revision FROM pm_profile_revisions WHERE uuid=?",player);var old=rows(c,"SELECT event_id FROM pm_admissions WHERE instance_id=? AND uuid=?",instance,player);if(!old.isEmpty()&&old.get(0).get("event_id").getAsString().equals(event))return;
    // Logins are inserted before profiles. Existing migrated identities never become first-global.
    boolean firstGlobal=revisions.isEmpty()&&rows(c,"SELECT uuid FROM pm_profiles WHERE uuid=?",player).isEmpty();boolean firstArchive=rows(c,"SELECT uuid FROM pm_players WHERE archive_id=? AND uuid=? AND join_count>0",archive,player).isEmpty()&&rows(c,"SELECT event_id FROM pm_logins WHERE uuid=? AND archive_id=? AND event_id<>? LIMIT 1",player,archive,event).isEmpty();update(c,"UPDATE pm_profile_revisions SET deleted=FALSE WHERE uuid=?",player);update(c,"DELETE FROM pm_profile_exclusions WHERE uuid=?",player);
    identity(c,player,text(p,"name",64),number(p,"at",0,1,Long.MAX_VALUE),number(p,"at",0,1,Long.MAX_VALUE));
    update(c,"INSERT INTO pm_admissions(instance_id,uuid,event_id,archive_id,first_global,first_archive,at_ms) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=VALUES(event_id),archive_id=VALUES(archive_id),first_global=VALUES(first_global),first_archive=VALUES(first_archive),at_ms=VALUES(at_ms)",instance,player,event,archive,firstGlobal,firstArchive,p.get("at").getAsLong());
  }
  public JsonObject loginContext(String player,Set<UUID> online)throws Exception{JsonObject value=new JsonObject();value.addProperty("admitted",online.contains(UUID.fromString(uuid(player))));value.addProperty("eventId","");value.addProperty("firstGlobalLogin",false);value.addProperty("firstArchiveLogin",false);value.addProperty("archiveId","");if(!value.get("admitted").getAsBoolean())return value;
    try(Connection c=store.open()){var list=rows(c,"SELECT a.event_id eventId,a.first_global firstGlobalLogin,a.first_archive firstArchiveLogin,a.archive_id archiveId FROM pm_admissions a JOIN pm_instances i ON i.id=a.instance_id WHERE a.uuid=? AND i.stopped=FALSE AND i.last_seen>? ORDER BY a.at_ms DESC LIMIT 1",player,System.currentTimeMillis()-10000);if(!list.isEmpty())list.get(0).entrySet().forEach(e->value.add(e.getKey(),e.getValue()));}return value;
  }
  public synchronized void register(String namespace,JsonObject manifest)throws Exception{
    namespace=namespace(namespace);int version=(int)number(manifest,"version",1,1,100000);String raw=JSON.toJson(manifest);if(raw.length()>65536)throw new IllegalArgumentException("Manifest too large");
    JsonArray tables=manifest.has("tables")?array(manifest,"tables",256):new JsonArray();for(JsonElement item:tables){String table=item.getAsString();if(!(namespace.equals("memorialtitles")&&table.matches("mt_[a-z_]+")||namespace.equals("dailyfortunes")&&table.matches("df_[a-z_]+")))throw new SecurityException("Only native modules own their SQL namespace");}
    if(manifest.has("playerTables"))for(JsonElement item:array(manifest,"playerTables",64)){JsonObject rule=item.getAsJsonObject();String table=text(rule,"table",64),column=text(rule,"column",64);if(!tables.contains(new JsonPrimitive(table))||!column.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid player-table ownership");}
    JsonArray fields=manifest.has("fields")?array(manifest,"fields",128):new JsonArray();Set<String> ids=new HashSet<>();for(JsonElement item:fields){JsonObject spec=item.getAsJsonObject();String field=namespace(text(spec,"id",64));if(!ids.add(field))throw new IllegalArgumentException("Duplicate storage field");validateSpec(spec);}
    try(Connection c=store.open()){c.setAutoCommit(false);try{
      var old=rows(c,"SELECT version,manifest FROM pm_modules WHERE namespace=? FOR UPDATE",namespace);if(!old.isEmpty()&&old.get(0).get("version").getAsInt()>version)throw new IllegalStateException("Module schema downgrade refused");
      if(!old.isEmpty()&&old.get(0).get("version").getAsInt()==version&&!JsonParser.parseString(old.get(0).get("manifest").getAsString()).equals(manifest))throw new IllegalStateException("Manifest changed without schema version increment");
      for(JsonElement item:fields){JsonObject spec=item.getAsJsonObject();String field=text(spec,"id",64);var prior=rows(c,"SELECT spec FROM pm_fields WHERE namespace=? AND field_id=?",namespace,field);if(!prior.isEmpty()&&!JsonParser.parseString(prior.get(0).get("spec").getAsString()).equals(spec))throw new IllegalStateException("Field migration required before changing type/default/scope");update(c,"INSERT IGNORE INTO pm_fields(namespace,field_id,schema_version,spec) VALUES(?,?,?,?)",namespace,field,version,JSON.toJson(spec));}
      update(c,"INSERT INTO pm_modules(namespace,version,manifest) VALUES(?,?,?) ON DUPLICATE KEY UPDATE version=VALUES(version),manifest=VALUES(manifest)",namespace,version,raw);c.commit();
    }catch(Exception e){c.rollback();throw e;}}
  }
  private static String namespace(String value){if(!value.matches("[a-z][a-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid namespace or field");return value;}
  private static void validateSpec(JsonObject spec){String type=text(spec,"type",16),scope=text(spec,"scope",16);if(!Set.of("boolean","integer","number","string","object","array").contains(type)||!Set.of("global","archive").contains(scope)||!spec.has("default"))throw new IllegalArgumentException("Field type, scope and default required");validateValue(spec,spec.get("default"));}
  private static void validateValue(JsonObject spec,JsonElement value){if(value==null||value.isJsonNull())throw new IllegalArgumentException("Null storage value");String type=text(spec,"type",16);boolean valid=switch(type){case "boolean"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean();case "integer"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&value.getAsBigDecimal().stripTrailingZeros().scale()<=0;case "number"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&Double.isFinite(value.getAsDouble());case "string"->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString();case "object"->value.isJsonObject();case "array"->value.isJsonArray();default->false;};if(!valid)throw new IllegalArgumentException("Storage type mismatch");long max=number(spec,"maxBytes",16384,1,65536);if(JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>max)throw new IllegalArgumentException("Storage quota exceeded");}
  public Function<JsonObject,JsonObject> hook(String namespace,java.util.function.Supplier<Set<UUID>> online){String owner=namespace(namespace);return request->{try{return request(owner,request,online.get());}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("平台存储暂不可用",e);}};}
  public JsonObject request(String owner,JsonObject request,Set<UUID> online)throws Exception{
    String op=text(request,"op",64);return switch(op){case "profiles.list"->profiles();case "profiles.online"->{JsonObject value=new JsonObject();value.add("players",JSON.toJsonTree(online));yield value;}case "profiles.get"->profile(text(request,"player",36));case "profiles.loginContext"->loginContext(text(request,"player",36),online);case "storage.get","storage.set"->value(owner,request,op.endsWith("set"));default->throw new IllegalArgumentException("Platform operation not registered");};
  }
  private JsonObject value(String owner,JsonObject request,boolean write)throws Exception{
    if(request.has("namespace")&&!text(request,"namespace",64).equals(owner))throw new SecurityException("Namespace ownership violation");String player=uuid(text(request,"player",36)),field=namespace(text(request,"field",64));
    try(Connection c=store.open()){c.setAutoCommit(false);try{
      var specs=rows(c,"SELECT spec FROM pm_fields WHERE namespace=? AND field_id=?",owner,field);if(specs.isEmpty())throw new IllegalArgumentException("Unregistered storage field");JsonObject spec=JsonParser.parseString(specs.get(0).get("spec").getAsString()).getAsJsonObject();String scope=text(spec,"scope",16).equals("global")?"":uuid(text(request,"archiveId",36));
      if(!scope.isEmpty()){var archive=rows(c,"SELECT status FROM pm_archives WHERE id=?",scope);if(archive.isEmpty()||write&&!archive.get(0).get("status").getAsString().equals("active"))throw new IllegalStateException("Historical archive is read-only");}
      if(rows(c,"SELECT uuid FROM pm_profiles WHERE uuid=? AND uuid NOT IN(SELECT uuid FROM pm_profile_exclusions) FOR UPDATE",player).isEmpty())throw new IllegalArgumentException("Player absent from global directory");
      var previous=rows(c,"SELECT value,revision FROM pm_values WHERE namespace=? AND uuid=? AND scope_id=? AND field_id=? FOR UPDATE",owner,player,scope,field);long revision=previous.isEmpty()?0:previous.get(0).get("revision").getAsLong();JsonElement value=previous.isEmpty()?spec.get("default"):JsonParser.parseString(previous.get(0).get("value").getAsString());
      if(write){long expected=number(request,"expectedRevision",-1,0,Long.MAX_VALUE);if(revision!=expected)throw new IllegalStateException("Storage revision conflict; refresh before retrying");value=request.get("value");validateValue(spec,value);revision++;update(c,"INSERT INTO pm_values(namespace,uuid,scope_id,field_id,value,revision) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE value=VALUES(value),revision=VALUES(revision)",owner,player,scope,field,JSON.toJson(value),revision);update(c,"UPDATE pm_profile_revisions SET revision=revision+1 WHERE uuid=?",player);}
      c.commit();JsonObject result=new JsonObject();result.add("value",value.deepCopy());result.addProperty("revision",revision);result.addProperty("scope",text(spec,"scope",16));return result;
    }catch(Exception e){c.rollback();throw e;}}
  }
  public List<JsonObject> modules()throws Exception{try(Connection c=store.open()){List<JsonObject> result=new ArrayList<>();for(JsonObject row:rows(c,"SELECT namespace,manifest FROM pm_modules ORDER BY namespace")){JsonObject manifest=JsonParser.parseString(row.get("manifest").getAsString()).getAsJsonObject();manifest.addProperty("id",row.get("namespace").getAsString());result.add(manifest);}return result;}}
  public List<String> durableTables()throws Exception{LinkedHashSet<String> result=new LinkedHashSet<>(ManagerBackup.TABLES);result.addAll(TABLES);try(Connection c=store.open()){for(JsonObject module:modules()){String namespace=text(module,"id",64);if(module.has("tables"))for(JsonElement item:module.getAsJsonArray("tables")){String table=item.getAsString();if(!(namespace.equals("memorialtitles")&&table.matches("mt_[a-z_]+")||namespace.equals("dailyfortunes")&&table.matches("df_[a-z_]+")))throw new SecurityException("Unowned SQL table in manifest");if(!tableExists(c,table))throw new IllegalStateException("Registered durable table is unavailable: "+table);result.add(table);}}}return List.copyOf(result);}
}
