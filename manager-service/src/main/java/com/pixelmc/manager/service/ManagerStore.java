package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import com.google.gson.*;
import java.sql.*;
import java.text.Collator;
import java.time.LocalDate;
import java.util.*;

/** Persistent business data and expiring telemetry are deliberately separate tables. */
public final class ManagerStore {
  private final Database db;
  private volatile boolean platformEnabled;
  void enableProfiles(){platformEnabled=true;}
  public static final long HOUR=3600000L;
  public ManagerStore(Database.Config config){db=new Database(config);}
  public Connection open()throws SQLException{return db.open();}
  public interface Work<T>{T run(Connection c)throws Exception;}
  private <T>T transaction(Work<T> action)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try{T result=action.run(c);c.commit();return result;}catch(Exception e){c.rollback();throw e;}}}
  public static int update(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p.executeUpdate();}}
  public static List<JsonObject> rows(Connection c,String sql,Object...args)throws SQLException{
    try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.setQueryTimeout(3);try(ResultSet r=p.executeQuery()){List<JsonObject> out=new ArrayList<>();ResultSetMetaData m=r.getMetaData();while(r.next()){JsonObject o=new JsonObject();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);o.add(m.getColumnLabel(i),v==null?JsonNull.INSTANCE:m.getColumnType(i)==java.sql.Types.TINYINT?new JsonPrimitive(r.getBoolean(i)):JSON.toJsonTree(v));}out.add(o);}return out;}}
  }
  private static boolean rowBoolean(JsonObject row,String key){
    JsonElement value=row.get(key);
    if(value==null||value.isJsonNull())return false;
    // MariaDB COALESCE(boolean,FALSE) returns INTEGER, while direct columns return BOOLEAN.
    return value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():value.getAsInt()!=0;
  }
  private static JsonObject one(Connection c,String sql,Object...args)throws Exception{var values=rows(c,sql,args);if(values.isEmpty())throw new IllegalStateException("资料不存在或已变更");return values.get(0);}
  public boolean healthy(){try(Connection c=open()){return c.isValid(2);}catch(Exception e){return false;}}
  public void migrate()throws Exception{try(Connection c=open()){
    try(var p=c.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(),':pm_schema'),10)")){try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new SQLException("Manager schema lock unavailable");}}
    try{
      String[] ddl={
        "CREATE TABLE IF NOT EXISTS pm_schema(version INT PRIMARY KEY) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_instances(id VARCHAR(64) CHARACTER SET ascii PRIMARY KEY,token_hash CHAR(64) CHARACTER SET ascii NOT NULL,active_archive CHAR(36) CHARACTER SET ascii NULL,run_id CHAR(36) CHARACTER SET ascii NULL,world_id CHAR(36) CHARACTER SET ascii NULL,started_at BIGINT NOT NULL DEFAULT 0,last_seen BIGINT NOT NULL DEFAULT 0,last_seq BIGINT NOT NULL DEFAULT 0,stopped BOOLEAN NOT NULL DEFAULT TRUE,snapshot LONGTEXT NOT NULL,config_revision BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_archives(id CHAR(36) CHARACTER SET ascii PRIMARY KEY,instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,world_id CHAR(36) CHARACTER SET ascii NOT NULL,name VARCHAR(256) NOT NULL,version VARCHAR(128) NOT NULL,start_date DATE NOT NULL,end_at BIGINT NULL,status VARCHAR(16) CHARACTER SET ascii NOT NULL,finalized BOOLEAN NOT NULL DEFAULT FALSE,created_at BIGINT NOT NULL,summary LONGTEXT NOT NULL,UNIQUE(instance_id,world_id),INDEX(instance_id,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS pm_runs(instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,started_at BIGINT NOT NULL,retired BOOLEAN NOT NULL DEFAULT FALSE,cleaned BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(instance_id,run_id),INDEX(retired,cleaned)) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_players(archive_id CHAR(36) CHARACTER SET ascii NOT NULL,uuid CHAR(36) CHARACTER SET ascii NOT NULL,name VARCHAR(64) NOT NULL,name_sort VARCHAR(768) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,join_count BIGINT NOT NULL,first_join BIGINT NOT NULL,last_join BIGINT NOT NULL,total_ms BIGINT NOT NULL,online BOOLEAN NOT NULL DEFAULT FALSE,counter_warning BOOLEAN NOT NULL DEFAULT FALSE,payload LONGTEXT NOT NULL,PRIMARY KEY(archive_id,uuid),INDEX(archive_id,online,name_sort),INDEX(uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS pm_logins(event_id VARCHAR(128) CHARACTER SET ascii PRIMARY KEY,archive_id CHAR(36) CHARACTER SET ascii NOT NULL,uuid CHAR(36) CHARACTER SET ascii NOT NULL,at_ms BIGINT NOT NULL,session_id VARCHAR(128) CHARACTER SET ascii NOT NULL,name VARCHAR(64) NOT NULL,INDEX(archive_id,uuid,at_ms,event_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS pm_imports(archive_id CHAR(36) CHARACTER SET ascii NOT NULL,source_hash CHAR(64) CHARACTER SET ascii NOT NULL,at_ms BIGINT NOT NULL,players INT NOT NULL,total_ms BIGINT NOT NULL,joins BIGINT NOT NULL,PRIMARY KEY(archive_id,source_hash)) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_commands(id CHAR(36) CHARACTER SET ascii PRIMARY KEY,instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,op VARCHAR(64) CHARACTER SET ascii NOT NULL,payload LONGTEXT NOT NULL,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,state VARCHAR(20) CHARACTER SET ascii NOT NULL,result LONGTEXT NOT NULL,INDEX(instance_id,run_id,state,expires_at)) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,at_ms BIGINT NOT NULL,actor VARCHAR(128) NOT NULL,action VARCHAR(64) NOT NULL,detail LONGTEXT NOT NULL,INDEX(at_ms)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS pm_metrics(instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,at_ms BIGINT NOT NULL,tps DOUBLE NOT NULL,mspt DOUBLE NOT NULL,heap DOUBLE NOT NULL,payload LONGTEXT NOT NULL,PRIMARY KEY(instance_id,run_id,at_ms),INDEX(at_ms)) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_tick_groups(instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,first_tick BIGINT NOT NULL,last_tick BIGINT NOT NULL,at_ms BIGINT NOT NULL,end_at BIGINT NOT NULL,sample_count BIGINT NOT NULL,minimum DOUBLE NOT NULL,maximum DOUBLE NOT NULL,low_group BOOLEAN NOT NULL,PRIMARY KEY(instance_id,run_id,first_tick),INDEX(instance_id,run_id,last_tick),INDEX(instance_id,run_id,end_at),INDEX(end_at)) ENGINE=InnoDB",
        "CREATE TABLE IF NOT EXISTS pm_long_ticks(instance_id VARCHAR(64) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,tick_index BIGINT NOT NULL,at_ms BIGINT NOT NULL,duration DOUBLE NOT NULL,PRIMARY KEY(instance_id,run_id,tick_index),INDEX(instance_id,run_id,at_ms),INDEX(at_ms)) ENGINE=InnoDB"
      };
      for(String sql:ddl)try(Statement s=c.createStatement()){s.execute(sql);}
      update(c,"ALTER TABLE pm_archives ADD COLUMN IF NOT EXISTS finalized BOOLEAN NOT NULL DEFAULT FALSE");
      update(c,"UPDATE pm_archives a JOIN pm_instances i ON i.active_archive=a.id AND i.world_id=a.world_id SET a.finalized=TRUE WHERE i.stopped=TRUE AND i.last_seq>0");
      update(c,"ALTER TABLE pm_players ADD COLUMN IF NOT EXISTS counter_warning BOOLEAN NOT NULL DEFAULT FALSE");
      var version=rows(c,"SELECT MAX(version) v FROM pm_schema").get(0);if(!version.get("v").isJsonNull()&&version.get("v").getAsInt()>1)throw new SQLException("Manager database is newer than this service");
      update(c,"INSERT IGNORE INTO pm_schema(version) VALUES(1)");
    }finally{try(Statement s=c.createStatement()){s.execute("SELECT RELEASE_LOCK(CONCAT(DATABASE(),':pm_schema'))");}}
  }}
  public void register(String instance,String token)throws Exception{Wire.id(instance);if(token.length()<32)throw new IllegalArgumentException("桥接密钥过短");try(Connection c=open()){update(c,"INSERT INTO pm_instances(id,token_hash,snapshot) VALUES(?,?,?)",instance,digest(token),"{}");}}
  public boolean authenticate(String instance,String token)throws Exception{if(token==null||token.length()>256)return false;try(Connection c=open()){var list=rows(c,"SELECT token_hash FROM pm_instances WHERE id=?",Wire.id(instance));return !list.isEmpty()&&java.security.MessageDigest.isEqual(digest(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),list.get(0).get("token_hash").getAsString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));}}
  private static JsonObject lockInstance(Connection c,String id)throws Exception{return one(c,"SELECT * FROM pm_instances WHERE id=? FOR UPDATE",Wire.id(id));}
  private static boolean fresh(JsonObject i,long now){return (!i.has("last_seq")||i.get("last_seq").getAsLong()>0)&&!i.get("stopped").getAsBoolean()&&now-i.get("last_seen").getAsLong()<10000;}
  public JsonObject hello(String instance,JsonObject request)throws Exception{return transaction(c->{
    JsonObject i=lockInstance(c,instance);String run=uuid(text(request,"runId",36)),world=uuid(text(request,"worldId",36));long now=System.currentTimeMillis();
    long start=number(request,"startedAt",0,1,now+10000);String previous=i.get("run_id").isJsonNull()?"":i.get("run_id").getAsString();
    if(!run.equals(previous)){
      if(!rows(c,"SELECT run_id FROM pm_runs WHERE instance_id=? AND run_id=?",instance,run).isEmpty())throw new IllegalStateException("旧运行已关闭，拒绝重新激活");
      if(!previous.isEmpty()&&!i.get("stopped").getAsBoolean()&&now-i.get("last_seen").getAsLong()<10000)throw new IllegalStateException("同一实例仍有活动游戏连接，请勿并行使用相同实例凭据");
      update(c,"UPDATE pm_runs SET retired=TRUE WHERE instance_id=?",instance);
      update(c,"UPDATE pm_archives SET finalized=FALSE WHERE id=? AND world_id=?",i.get("active_archive").isJsonNull()?null:i.get("active_archive").getAsString(),world);
      update(c,"UPDATE pm_players SET online=FALSE WHERE archive_id IN(SELECT id FROM pm_archives WHERE instance_id=?)",instance);
      update(c,"INSERT INTO pm_runs(instance_id,run_id,started_at) VALUES(?,?,?)",instance,run,start);
      update(c,"UPDATE pm_commands SET state='expired' WHERE instance_id=? AND state='pending'",instance);
      update(c,"UPDATE pm_instances SET run_id=?,world_id=?,started_at=?,last_seq=0,stopped=FALSE,last_seen=? WHERE id=?",run,world,start,now,instance);
    }else{
      if(i.get("stopped").getAsBoolean())throw new IllegalStateException("本次运行已最终结算，不能复活");
      if(!world.equals(i.get("world_id").getAsString()))throw new IllegalStateException("运行与存档身份不一致");
      update(c,"UPDATE pm_instances SET last_seen=?,stopped=FALSE WHERE id=?",now,instance);
    }
    JsonObject info=object(request,"info");if(info.toString().length()>262144)throw new IllegalArgumentException("实例描述过大");
    JsonObject snap=run.equals(previous)?JsonParser.parseString(i.get("snapshot").getAsString()).getAsJsonObject():new JsonObject();info.entrySet().forEach(entry->snap.add(entry.getKey(),entry.getValue()));
    // Keep the last actual telemetry until a new snapshot arrives, but never expose it as live.
    update(c,"UPDATE pm_instances SET snapshot=? WHERE id=?",JSON.toJson(snap),instance);
    JsonObject response=new JsonObject();response.addProperty("runId",run);response.addProperty("serverTime",now);
    String active=i.get("active_archive").isJsonNull()?"":i.get("active_archive").getAsString();
    var binding=active.isEmpty()?List.<JsonObject>of():rows(c,"SELECT * FROM pm_archives WHERE id=? AND world_id=? AND status='active'",active,world);
    if(binding.isEmpty())response.addProperty("bindingRequired",true);else{response.addProperty("bindingRequired",false);response.addProperty("archiveId",active);response.add("archive",binding.get(0));}
    return response;
  });}
  public boolean bound(String instance)throws Exception{try(Connection c=open()){return !rows(c,"SELECT a.id FROM pm_instances i JOIN pm_archives a ON i.active_archive=a.id AND i.world_id=a.world_id WHERE i.id=? AND a.status='active'",id(instance)).isEmpty();}}
  public List<JsonObject> instances()throws Exception{try(Connection c=open()){return rows(c,"SELECT id,active_archive archiveId,world_id worldId,run_id runId,started_at startedAt,last_seen lastSeen,stopped FROM pm_instances ORDER BY id");}}
  public List<JsonObject> archives(String instance)throws Exception{try(Connection c=open()){return rows(c,"SELECT id,instance_id instanceId,world_id worldId,name,version,CAST(start_date AS CHAR) startedOn,end_at endedAt,status,finalized,created_at createdAt FROM pm_archives WHERE instance_id=? ORDER BY created_at DESC",instance);}}
  public JsonObject createArchive(JsonObject b,String actor)throws Exception{return transaction(c->{String instance=id(text(b,"instanceId",64));JsonObject i=lockInstance(c,instance);boolean active=bool(b,"active",true);
    if(active&&!i.get("active_archive").isJsonNull())throw new IllegalStateException("请先归档当前整合包");
    String name=text(b,"name",256).strip();if(name.isEmpty())throw new IllegalArgumentException("整合包名称必填");String date=LocalDate.parse(text(b,"startedOn",10)).toString();String world=uuid(text(b,"worldId",36));
    if(active&&!i.get("world_id").isJsonNull()&&!world.equals(i.get("world_id").getAsString()))throw new IllegalStateException("请确认当前游戏存档身份");
    String archive=UUID.randomUUID().toString();update(c,"INSERT INTO pm_archives(id,instance_id,world_id,name,version,start_date,status,created_at,summary) VALUES(?,?,?,?,?,?,?,?,?)",archive,instance,world,name,text(b,"version",128),date,active?"active":"draft",System.currentTimeMillis(),"{}");
    if(active)update(c,"UPDATE pm_instances SET active_archive=? WHERE id=?",archive,instance);
    audit(c,actor,"ARCHIVE_CREATE",b);return one(c,"SELECT id,name,version,CAST(start_date AS CHAR) startedOn,status FROM pm_archives WHERE id=?",archive);
  });}
  public void archive(String archive,String actor)throws Exception{transaction(c->{JsonObject a=one(c,"SELECT * FROM pm_archives WHERE id=? FOR UPDATE",uuid(archive));if(a.get("status").getAsString().equals("archived"))return null;
    JsonObject i=lockInstance(c,a.get("instance_id").getAsString());if(!a.get("finalized").getAsBoolean())throw new IllegalStateException("请正常停服并完成最后同步后归档；连接中断不等于已结算");
    update(c,"UPDATE pm_archives SET status='archived',end_at=? WHERE id=?",System.currentTimeMillis(),archive);
    update(c,"UPDATE pm_players SET online=FALSE WHERE archive_id=?",archive);
    update(c,"UPDATE pm_instances SET active_archive=NULL WHERE id=? AND active_archive=?",i.get("id").getAsString(),archive);
    audit(c,actor,"ARCHIVE_CLOSE",Map.of("archiveId",archive));return null;
  });}
  public JsonObject archiveInfo(String archive)throws Exception{try(Connection c=open()){return one(c,"SELECT id,instance_id instanceId,world_id worldId,name,version,CAST(start_date AS CHAR) startedOn,end_at endedAt,status,summary FROM pm_archives WHERE id=?",uuid(archive));}}
  public JsonObject ingest(String instance,JsonObject b)throws Exception{return transaction(c->{JsonObject i=lockInstance(c,instance);String run=uuid(text(b,"runId",36));if(i.get("run_id").isJsonNull()||!run.equals(i.get("run_id").getAsString()))throw new IllegalStateException("运行已失效");
    String archive=uuid(text(b,"archiveId",36));if(i.get("active_archive").isJsonNull()||!archive.equals(i.get("active_archive").getAsString()))throw new IllegalStateException("活动档案绑定已改变");
    one(c,"SELECT id FROM pm_archives WHERE id=? AND status='active' AND world_id=? FOR UPDATE",archive,i.get("world_id").getAsString());
    long seq=number(b,"seq",0,1,Long.MAX_VALUE),last=i.get("last_seq").getAsLong();if(seq<=last){JsonObject result=new JsonObject();result.addProperty("ack",last);result.addProperty("accepted",false);result.add("commands",JSON.toJsonTree(commands(c,instance,run)));return result;}
    if(i.get("stopped").getAsBoolean())throw new IllegalStateException("本次运行已最终结算");
    long now=System.currentTimeMillis(),at=number(b,"at",now,i.get("started_at").getAsLong()-1000,now+10000);
    Set<String> currentFakes=new HashSet<>();if(platformEnabled)for(JsonElement raw:array(b,"players",2000)){JsonObject player=raw.getAsJsonObject();if(bool(player,"fake",false)){String fake=uuid(text(player,"id",36));currentFakes.add(fake);PlatformStore.excludeFake(c,fake);}}
    if(platformEnabled)for(JsonElement raw:array(b,"logins",512)){JsonObject login=raw.getAsJsonObject();if(!bool(login,"fake",false)&&!currentFakes.contains(text(login,"uuid",36)))PlatformStore.admission(c,instance,archive,login);}
    for(JsonElement raw:array(b,"stats",10000))if(!bool(raw.getAsJsonObject(),"fake",false)&&!currentFakes.contains(text(raw.getAsJsonObject(),"id",36)))upsertPlayer(c,archive,raw.getAsJsonObject(),false,false);
    update(c,"UPDATE pm_players SET online=FALSE WHERE archive_id=? AND online=TRUE",archive);
    for(JsonElement raw:array(b,"players",2000)){JsonObject p=raw.getAsJsonObject();if(bool(p,"fake",false))continue;upsertPlayer(c,archive,p,true,true);}
    // The authenticated bridge journals only admitted logins. Its IO worker can drain
    // that journal before the game thread publishes the next player checkpoint.
    // Preserve the event without creating a profile or blocking the entire stream;
    // only stats/players snapshots register the profile, and event_id makes retries safe.
    for(JsonElement raw:array(b,"logins",512)){JsonObject p=raw.getAsJsonObject();String player=uuid(text(p,"uuid",36));if(currentFakes.contains(player))continue;if(platformEnabled&&!PlatformStore.acceptLogin(c,player,number(p,"at",0,1,now+10000)))continue;String event=text(p,"id",128);if(!event.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("登录事件标识无效");update(c,"INSERT IGNORE INTO pm_logins(event_id,archive_id,uuid,at_ms,session_id,name) VALUES(?,?,?,?,?,?)",event,archive,player,number(p,"at",0,1,now+10000),text(p,"sessionId",128),text(p,"name",64));}
    JsonArray metrics=array(b,"metrics",512);if(metrics.isEmpty()&&b.has("metric")){JsonObject m=object(b,"metric").deepCopy();m.addProperty("at",at);metrics.add(m);}
    try(PreparedStatement p=c.prepareStatement("INSERT IGNORE INTO pm_metrics(instance_id,run_id,at_ms,tps,mspt,heap,payload) VALUES(?,?,?,?,?,?,?)")){
      for(JsonElement raw:metrics){JsonObject m=raw.getAsJsonObject();long when=number(m,"at",at,1,now+10000);if(when<now-12*HOUR)continue;p.setString(1,instance);p.setString(2,run);p.setLong(3,when);p.setDouble(4,finite(m,"tps",0,20));p.setDouble(5,finite(m,"mspt",0,3600000));p.setDouble(6,finite(m,"heap",0,1000000));p.setString(7,JSON.toJson(m));p.addBatch();}p.executeBatch();
    }
    for(JsonElement raw:array(b,"longTicks",512)){JsonObject p=raw.getAsJsonObject();long when=number(p,"at",0,1,now+10000);double duration=finite(p,"duration",50,3600000);if(duration<=50||when<now-24*HOUR)continue;long tick=number(p,"tickIndex",0,0,Long.MAX_VALUE);int inserted=update(c,"INSERT IGNORE INTO pm_long_ticks(instance_id,run_id,tick_index,at_ms,duration) VALUES(?,?,?,?,?)",instance,run,tick,when,duration);if(inserted>0)groupTick(c,instance,run,tick,when,duration);}
    for(JsonElement raw:array(b,"acks",32)){JsonObject a=raw.getAsJsonObject();update(c,"UPDATE pm_commands SET state=?,result=? WHERE id=? AND instance_id=? AND run_id=? AND state='pending'",bool(a,"ok",false)?"done":"failed",text(a,"result",4096),uuid(text(a,"id",36)),instance,run);}
    JsonObject snapshot=object(b,"snapshot");if(platformEnabled)snapshot.add("players",array(b,"players",2000).deepCopy());snapshot.addProperty("at",at);snapshot.addProperty("runId",run);snapshot.addProperty("startedAt",i.get("started_at").getAsLong());
    update(c,"UPDATE pm_instances SET snapshot=?,last_seen=?,last_seq=?,stopped=? WHERE id=?",JSON.toJson(snapshot),now,seq,bool(b,"stopped",false),instance);
    JsonObject summary=new JsonObject();if(snapshot.has("worlds")){JsonArray worlds=snapshot.getAsJsonArray("worlds").deepCopy();for(JsonElement w:worlds)w.getAsJsonObject().remove("performance");summary.add("worlds",worlds);}if(snapshot.has("saveBytes"))summary.add("saveBytes",snapshot.get("saveBytes"));
    update(c,"UPDATE pm_archives SET summary=? WHERE id=?",JSON.toJson(summary),archive);
    if(bool(b,"stopped",false)){update(c,"UPDATE pm_players SET online=FALSE WHERE archive_id=?",archive);update(c,"UPDATE pm_archives SET finalized=TRUE WHERE id=?",archive);}
    JsonObject result=new JsonObject();result.addProperty("ack",seq);result.addProperty("accepted",true);result.add("commands",JSON.toJsonTree(commands(c,instance,run)));return result;
  });}
  private static double finite(JsonObject o,String key,double min,double max){double d=o.get(key).getAsDouble();if(!Double.isFinite(d)||d<min||d>max)throw new IllegalArgumentException(key+"超出范围");return d;}
  private void upsertPlayer(Connection c,String archive,JsonObject p,boolean online,boolean live)throws Exception{
    if(bool(p,"fake",false))return;
    String player=uuid(text(p,"id",36)),name=text(p,"name",64);if(name.isBlank())throw new IllegalArgumentException("玩家名称为空");
    long total=number(p,"totalOnlineMillis",0,0,Long.MAX_VALUE),joins=number(p,"joinCount",0,0,Long.MAX_VALUE),first=number(p,"firstJoinEpochMillis",0,0,Long.MAX_VALUE),last=number(p,"lastJoinEpochMillis",0,0,Long.MAX_VALUE);
    if(platformEnabled){
      if(PlatformStore.deleted(c,player)||PlatformStore.excluded(c,player))return;
      var resets=rows(c,"SELECT total_base,join_base FROM pm_player_resets WHERE archive_id=? AND uuid=?",archive,player);if(!resets.isEmpty()){total=Math.max(0,total-resets.get(0).get("total_base").getAsLong());joins=Math.max(0,joins-resets.get(0).get("join_base").getAsLong());first=last;}
      p.addProperty("totalOnlineMillis",total);p.addProperty("joinCount",joins);p.addProperty("firstJoinEpochMillis",first);
      PlatformStore.identity(c,player,name,first,live&&online?System.currentTimeMillis():last);
    }
    Collator order=Collator.getInstance(Locale.CHINA);order.setStrength(Collator.PRIMARY);String sort=HexFormat.of().formatHex(order.getCollationKey(name.toLowerCase(Locale.ROOT)).toByteArray());
    // Absolute cumulative counters make reconnect/backfill idempotent. Never sum repeated snapshots.
    update(c,"INSERT INTO pm_players(archive_id,uuid,name,name_sort,join_count,first_join,last_join,total_ms,online,payload) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE counter_warning=(join_count>VALUES(join_count) OR total_ms>VALUES(total_ms)+1000),name=VALUES(name),name_sort=VALUES(name_sort),join_count=GREATEST(join_count,VALUES(join_count)),first_join=IF(first_join=0,VALUES(first_join),IF(VALUES(first_join)=0,first_join,LEAST(first_join,VALUES(first_join)))),last_join=GREATEST(last_join,VALUES(last_join)),total_ms=GREATEST(total_ms,VALUES(total_ms)),online=VALUES(online),payload=IF(?,VALUES(payload),payload)",archive,player,name,sort,joins,first,last,total,online,JSON.toJson(p),live);
  }
  public JsonObject state(String instance,String selectedArchive)throws Exception{try(Connection c=open()){
    JsonObject i=one(c,"SELECT id,active_archive,run_id,started_at,last_seen,last_seq,stopped,snapshot FROM pm_instances WHERE id=?",id(instance));long now=System.currentTimeMillis();
    String active=i.get("active_archive").isJsonNull()?"":i.get("active_archive").getAsString();String selected=selectedArchive==null||selectedArchive.isBlank()?active:uuid(selectedArchive);
    JsonObject out=JsonParser.parseString(i.get("snapshot").getAsString()).getAsJsonObject();boolean historical=!selected.equals(active);JsonObject pack=selected.isBlank()?new JsonObject():archiveInfo(selected);
    if(!pack.entrySet().isEmpty()&&!pack.get("instanceId").getAsString().equals(instance))throw new IllegalArgumentException("档案不属于此实例");
    if(historical){out=JsonParser.parseString(pack.get("summary").getAsString()).getAsJsonObject();}
    if(out.has("at")&&now-out.get("at").getAsLong()>12*HOUR)scrubPerformance(out);
    out.addProperty("instanceId",instance);out.addProperty("archiveId",selected);out.addProperty("activeArchiveId",active);out.addProperty("historical",historical||(!pack.entrySet().isEmpty()&&!pack.get("status").getAsString().equals("active")));
    out.addProperty("connected",fresh(i,now));out.addProperty("stopped",i.get("stopped").getAsBoolean());out.addProperty("receivedAt",i.get("last_seen").getAsLong());out.addProperty("serverTime",now);out.add("pack",pack);
    if(!historical){out.add("runId",i.get("run_id"));out.add("startedAt",i.get("started_at"));}
    if(!selected.isBlank()){out.add("totals",rows(c,"SELECT COUNT(*) players,COALESCE(SUM(total_ms),0) totalOnlineMillis,COALESCE(SUM(join_count),0) joinCount,COALESCE(SUM(counter_warning),0) counterWarnings FROM pm_players WHERE archive_id=?"+(platformEnabled?" AND uuid IN(SELECT uuid FROM pm_profiles WHERE uuid NOT IN(SELECT uuid FROM pm_profile_exclusions))":""),selected).get(0));}
    return out;
  }}
  public static void scrubPerformance(JsonObject snapshot){for(String key:List.of("metric","collections","nonHeapMb","heapCommitted","maxHeap","gcNames"))snapshot.remove(key);if(snapshot.has("worlds"))for(JsonElement world:snapshot.getAsJsonArray("worlds"))world.getAsJsonObject().remove("performance");}
  public JsonObject players(String archive,Map<String,String> q,boolean connected)throws Exception{try(Connection c=open()){
    uuid(archive);String source="pm_players";if(platformEnabled&&!q.getOrDefault("historical","false").equals("true"))source="(SELECT '"+uuid(archive)+"' archive_id,g.uuid,g.name,COALESCE(p.name_sort,HEX(LOWER(g.name))) name_sort,COALESCE(p.join_count,0) join_count,COALESCE(p.first_join,g.first_join) first_join,COALESCE(p.last_join,0) last_join,COALESCE(p.total_ms,0) total_ms,COALESCE(p.online,FALSE) online,COALESCE(p.counter_warning,FALSE) counter_warning,COALESCE(p.payload,'{}') payload FROM pm_profiles g LEFT JOIN pm_players p ON p.uuid=g.uuid AND p.archive_id='"+archive+"' WHERE g.uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)) roster";int page=integer(q,"page",0,0,100000),size=integer(q,"size",50,1,100);String key=q.getOrDefault("sort","status"),direction=q.getOrDefault("direction","asc").equals("desc")?"DESC":"ASC";
    String search=q.getOrDefault("q","");if(search.length()>128)throw new IllegalArgumentException("搜索过长");String filter=q.getOrDefault("online","");
    String where="archive_id=? AND (name LIKE ? OR uuid LIKE ?)"+(platformEnabled?" AND uuid IN(SELECT uuid FROM pm_profiles WHERE uuid NOT IN(SELECT uuid FROM pm_profile_exclusions))":"")+(filter.equals("true")?(connected?" AND online=TRUE":" AND 1=0"):"");String pattern="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    String sort=switch(key){case "name"->"name_sort "+direction;case "joinCount"->"join_count "+direction;case "totalOnlineMillis"->"total_ms "+direction;case "status"->"online "+(direction.equals("ASC")?"DESC":"ASC")+",name_sort ASC";case "ping"->"(JSON_EXTRACT(payload,'$.ping') IS NULL),CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.ping')) AS SIGNED) "+direction;case "session"->"(online=FALSE),CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sessionMillis')) AS UNSIGNED) "+direction;case "world"->"(online=FALSE),CASE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.world')) WHEN 'minecraft:overworld' THEN 0 WHEN 'minecraft:the_nether' THEN 1 WHEN 'minecraft:the_end' THEN 2 ELSE 3 END "+direction+",JSON_UNQUOTE(JSON_EXTRACT(payload,'$.world')) "+direction;default->throw new IllegalArgumentException("排序字段无效");};
    var selected=rows(c,"SELECT * FROM "+source+" WHERE "+where+" ORDER BY "+sort+",name_sort ASC,uuid ASC LIMIT ? OFFSET ?",archive,pattern,pattern,size,page*size);
    List<JsonObject> result=new ArrayList<>();for(JsonObject r:selected){JsonObject p=JsonParser.parseString(r.get("payload").getAsString()).getAsJsonObject();p.addProperty("id",r.get("uuid").getAsString());p.addProperty("name",r.get("name").getAsString());p.addProperty("joinCount",r.get("join_count").getAsLong());p.addProperty("totalOnlineMillis",r.get("total_ms").getAsLong());p.addProperty("firstJoinEpochMillis",r.get("first_join").getAsLong());p.addProperty("lastJoinEpochMillis",r.get("last_join").getAsLong());p.addProperty("counterWarning",rowBoolean(r,"counter_warning"));p.addProperty("isOnline",connected&&rowBoolean(r,"online"));result.add(p);}
    JsonObject out=new JsonObject();out.add("rows",JSON.toJsonTree(result));out.addProperty("total",rows(c,"SELECT COUNT(*) n FROM "+source+" WHERE "+where,archive,pattern,pattern).get(0).get("n").getAsLong());out.addProperty("page",page);out.addProperty("size",size);return out;
  }}
  public JsonObject lifetime(String player)throws Exception{try(Connection c=open()){return one(c,"SELECT COALESCE(SUM(total_ms),0) totalOnlineMillis,COALESCE(SUM(join_count),0) joinCount,COUNT(*) archives FROM pm_players WHERE uuid=?",uuid(player));}}
  public JsonObject logins(String archive,String player,int page,int size)throws Exception{try(Connection c=open()){JsonObject out=new JsonObject();out.add("rows",JSON.toJsonTree(rows(c,"SELECT event_id id,at_ms at,session_id sessionId,name FROM pm_logins WHERE archive_id=? AND uuid=? ORDER BY at_ms DESC,event_id DESC LIMIT ? OFFSET ?",uuid(archive),uuid(player),size,page*size)));out.addProperty("total",rows(c,"SELECT COUNT(*) n FROM pm_logins WHERE archive_id=? AND uuid=?",archive,player).get(0).get("n").getAsLong());out.addProperty("page",page);out.addProperty("size",size);return out;}}
  public List<JsonObject> metrics(String instance,String run,long window,long now)throws Exception{try(Connection c=open()){return rows(c,"SELECT at_ms at,tps,mspt,heap FROM pm_metrics WHERE instance_id=? AND run_id=? AND at_ms>=? AND EXISTS(SELECT 1 FROM pm_instances i WHERE i.id=? AND i.run_id=pm_metrics.run_id) ORDER BY at_ms",instance,run,now-Math.min(12*HOUR,window),instance);}}
  public List<JsonObject> longTicks(String instance,String run,long after,long now)throws Exception{try(Connection c=open()){return rows(c,"SELECT tick_index tickIndex,at_ms at,duration,run_id runId FROM pm_long_ticks WHERE instance_id=? AND run_id=? AND at_ms>=? AND tick_index>? AND EXISTS(SELECT 1 FROM pm_instances i WHERE i.id=? AND i.run_id=pm_long_ticks.run_id) ORDER BY tick_index LIMIT 5000",instance,run,now-24*HOUR,after,instance);}}
  private List<JsonObject> commands(Connection c,String instance,String run)throws Exception{return rows(c,"SELECT id,op,payload,expires_at expiresAt FROM pm_commands WHERE instance_id=? AND run_id=? AND state='pending' AND expires_at>? ORDER BY created_at LIMIT 16",instance,run,System.currentTimeMillis());}
  public JsonObject command(String instance,JsonObject b,String actor)throws Exception{return transaction(c->{JsonObject i=lockInstance(c,instance);if(!fresh(i,System.currentTimeMillis()))throw new IllegalStateException("游戏连接不可用，未排队执行");String expected=uuid(text(b,"runId",36));if(!expected.equals(i.get("run_id").getAsString()))throw new IllegalStateException("游戏已重启，请重新确认操作");String op=text(b,"op",64);if(!Set.of("maintenance","welcome","announcements","autoRestart","restart.schedule","restart.cancel","stop.schedule","stop.cancel").contains(op))throw new IllegalArgumentException("不支持的游戏操作");
    JsonObject payload=object(b,"payload");if(payload.toString().length()>131072)throw new IllegalArgumentException("配置过大");String requestId=uuid(text(b,"requestId",36));var old=rows(c,"SELECT id,state,result FROM pm_commands WHERE id=?",requestId);if(!old.isEmpty())return old.get(0);
    if(rows(c,"SELECT id FROM pm_commands WHERE instance_id=? AND state='pending' AND expires_at>? LIMIT 32",instance,System.currentTimeMillis()).size()>=32)throw new IllegalStateException("游戏操作队列已满");
    update(c,"INSERT INTO pm_commands(id,instance_id,run_id,op,payload,created_at,expires_at,state,result) VALUES(?,?,?,?,?,?,?,'pending','')",requestId,instance,expected,op,JSON.toJson(payload),System.currentTimeMillis(),System.currentTimeMillis()+30000);audit(c,actor,"GAME_OPERATION",b);JsonObject result=new JsonObject();result.addProperty("id",requestId);result.addProperty("state","pending");return result;
  });}
  public JsonObject commandResult(String instance,String request)throws Exception{try(Connection c=open()){return one(c,"SELECT id,op,state,result,expires_at expiresAt FROM pm_commands WHERE id=? AND instance_id=?",uuid(request),instance);}}
  public void callback(String instance,String extension,String op,Object payload)throws Exception{transaction(c->{JsonObject i=lockInstance(c,instance);if(!fresh(i,System.currentTimeMillis()))return null;if(rows(c,"SELECT id FROM pm_commands WHERE instance_id=? AND state='pending' AND expires_at>? LIMIT 32",instance,System.currentTimeMillis()).size()>=32)return null;String command=UUID.randomUUID().toString();JsonObject data=new JsonObject();data.addProperty("extension",extension);data.addProperty("op",op);data.add("data",JSON.toJsonTree(payload));update(c,"INSERT INTO pm_commands(id,instance_id,run_id,op,payload,created_at,expires_at,state,result) VALUES(?,?,?,'extension.callback',?,?,?,'pending','')",command,instance,i.get("run_id").getAsString(),JSON.toJson(data),System.currentTimeMillis(),System.currentTimeMillis()+30000);return null;});}
  private static void groupTick(Connection c,String instance,String run,long tick,long at,double duration)throws Exception{
    boolean low=duration<100;long first=tick,last=tick,start=at,end=at,count=1;double min=duration,max=duration;
    if(low){for(JsonObject neighbor:rows(c,"SELECT * FROM pm_tick_groups WHERE instance_id=? AND run_id=? AND low_group=TRUE AND (last_tick=? OR first_tick=?) FOR UPDATE",instance,run,tick-1,tick+1)){
      first=Math.min(first,neighbor.get("first_tick").getAsLong());last=Math.max(last,neighbor.get("last_tick").getAsLong());start=Math.min(start,neighbor.get("at_ms").getAsLong());end=Math.max(end,neighbor.get("end_at").getAsLong());count+=neighbor.get("sample_count").getAsLong();min=Math.min(min,neighbor.get("minimum").getAsDouble());max=Math.max(max,neighbor.get("maximum").getAsDouble());update(c,"DELETE FROM pm_tick_groups WHERE instance_id=? AND run_id=? AND first_tick=?",instance,run,neighbor.get("first_tick").getAsLong());
    }}
    update(c,"INSERT INTO pm_tick_groups(instance_id,run_id,first_tick,last_tick,at_ms,end_at,sample_count,minimum,maximum,low_group) VALUES(?,?,?,?,?,?,?,?,?,?)",instance,run,first,last,start,end,count,min,max,low);
  }
  private record TickCache(long until,JsonObject value){}
  private final Map<String,TickCache> tickCache=new HashMap<>();
  public synchronized JsonObject longTickPage(String instance,String run,int threshold,int page,long now)throws Exception{
    if(!Set.of(50,100,200).contains(threshold))throw new IllegalArgumentException("异常阈值无效");
    String cacheKey=instance+":"+run+":"+threshold+":"+page;var cached=tickCache.get(cacheKey);if(cached!=null&&cached.until()>now)return cached.value().deepCopy();
    String where="instance_id=? AND run_id=? AND end_at>=? AND at_ms<=? AND maximum>? AND EXISTS(SELECT 1 FROM pm_instances i WHERE i.id=? AND i.run_id=pm_tick_groups.run_id)";
    try(Connection c=open()){
      long cutoff=now-24*HOUR;var args=new Object[]{instance,run,cutoff,now,threshold,instance};JsonObject counts=rows(c,"SELECT COUNT(*) total,COALESCE(SUM(sample_count),0) events,MIN(end_at) oldest FROM pm_tick_groups WHERE "+where,args).get(0);
      // At most one continuous group crosses the expiry boundary. Its exact retained extrema/count come from indexed raw rows.
      var boundary=rows(c,"SELECT first_tick,last_tick,sample_count FROM pm_tick_groups WHERE "+where+" AND at_ms<?",instance,run,cutoff,now,threshold,instance,cutoff);
      Map<Long,JsonObject> trimmed=new HashMap<>();for(JsonObject group:boundary){var part=one(c,"SELECT MIN(tick_index) tickIndex,MIN(at_ms) at,MAX(at_ms) endAt,COUNT(*) count,MIN(duration) minimum,MAX(duration) duration FROM pm_long_ticks WHERE instance_id=? AND run_id=? AND tick_index BETWEEN ? AND ? AND at_ms>=?",instance,run,group.get("first_tick").getAsLong(),group.get("last_tick").getAsLong(),cutoff);counts.addProperty("events",counts.get("events").getAsLong()-group.get("sample_count").getAsLong()+part.get("count").getAsLong());trimmed.put(group.get("first_tick").getAsLong(),part);}
      var result=rows(c,"SELECT first_tick tickIndex,at_ms at,end_at endAt,sample_count count,minimum,maximum duration FROM pm_tick_groups WHERE "+where+" ORDER BY at_ms DESC,first_tick DESC LIMIT ? OFFSET ?",instance,run,cutoff,now,threshold,instance,20,Math.multiplyExact(page,20));
      for(JsonObject row:result){JsonObject part=trimmed.get(row.get("tickIndex").getAsLong());if(part!=null)for(var e:part.entrySet())row.add(e.getKey(),e.getValue());row.addProperty("id",run+":"+row.get("tickIndex").getAsLong());row.addProperty("runId",run);row.add("maximum",row.get("duration"));}
      counts.add("rows",JSON.toJsonTree(result));counts.addProperty("page",page);counts.addProperty("size",20);counts.addProperty("serverGrouped",true);tickCache.entrySet().removeIf(e->e.getValue().until()<=now);if(tickCache.size()>128)tickCache.clear();long expires=!boundary.isEmpty()?now:counts.get("oldest").isJsonNull()?now+5000:Math.min(now+5000,counts.get("oldest").getAsLong()+24*HOUR);tickCache.put(cacheKey,new TickCache(expires,counts.deepCopy()));return counts;
    }
  }
  public JsonObject importLegacy(String archive,JsonObject file,String actor,boolean commit)throws Exception{return transaction(c->{JsonObject a=one(c,"SELECT status FROM pm_archives WHERE id=? FOR UPDATE",uuid(archive));if(a.get("status").getAsString().equals("archived"))throw new IllegalStateException("归档资料不可修改");if(number(file,"version",0,1,1)!=1)throw new IllegalArgumentException("旧统计版本无效");JsonObject players=object(file,"players");if(players.size()>10000)throw new IllegalArgumentException("旧档玩家数量过大，请使用分批迁移工具");long total=0,joins=0;
    for(var entry:players.entrySet()){uuid(entry.getKey());JsonObject p=entry.getValue().getAsJsonObject();total=Math.addExact(total,number(p,"totalOnlineMillis",0,0,Long.MAX_VALUE));joins=Math.addExact(joins,number(p,"joinCount",0,0,Long.MAX_VALUE));text(p,"name",64);}
    String hash=digest(JSON.toJson(file));boolean imported=!rows(c,"SELECT source_hash FROM pm_imports WHERE archive_id=? AND source_hash=?",archive,hash).isEmpty();JsonObject report=new JsonObject();report.addProperty("players",players.size());report.addProperty("totalOnlineMillis",total);report.addProperty("joinCount",joins);report.addProperty("checksum",hash);report.addProperty("alreadyImported",imported);
    if(commit&&!imported){if(!rows(c,"SELECT uuid FROM pm_players WHERE archive_id=? LIMIT 1",archive).isEmpty())throw new IllegalStateException("目标已有玩家资料；停止导入以避免覆盖或重复累计");for(var entry:players.entrySet()){JsonObject p=entry.getValue().getAsJsonObject().deepCopy();p.addProperty("id",entry.getKey());upsertPlayer(c,archive,p,false,false);}update(c,"INSERT INTO pm_imports(archive_id,source_hash,at_ms,players,total_ms,joins) VALUES(?,?,?,?,?,?)",archive,hash,System.currentTimeMillis(),players.size(),total,joins);audit(c,actor,"LEGACY_IMPORT",report);}
    return report;
  });}
  public static void audit(Connection c,String actor,String action,Object detail)throws Exception{update(c,"INSERT INTO pm_audit(at_ms,actor,action,detail) VALUES(?,?,?,?)",System.currentTimeMillis(),actor,action,JSON.toJson(detail));}
  public int cleanup(long now)throws Exception{int removed=0;long end=System.nanoTime()+2_000_000_000L;try(Connection c=open()){
    for(String table:List.of("pm_metrics","pm_long_ticks","pm_tick_groups")){long age=table.equals("pm_metrics")?12*HOUR:24*HOUR;for(int batch=0;batch<100&&System.nanoTime()<end;batch++){int n=update(c,"DELETE FROM "+table+" WHERE "+(table.equals("pm_tick_groups")?"end_at":"at_ms")+"<? LIMIT 1000",now-age);removed+=n;if(n<1000)break;}}
    for(var run:rows(c,"SELECT instance_id,run_id FROM pm_runs WHERE retired=TRUE AND cleaned=FALSE LIMIT 32")){
      String instance=run.get("instance_id").getAsString(),id=run.get("run_id").getAsString();boolean empty=true;
      for(String table:List.of("pm_metrics","pm_long_ticks","pm_tick_groups")){for(int batch=0;batch<100&&System.nanoTime()<end;batch++){int n=update(c,"DELETE FROM "+table+" WHERE instance_id=? AND run_id=? LIMIT 1000",instance,id);removed+=n;if(n<1000)break;}empty&=rows(c,"SELECT 1 x FROM "+table+" WHERE instance_id=? AND run_id=? LIMIT 1",instance,id).isEmpty();}
      if(empty)update(c,"UPDATE pm_runs SET cleaned=TRUE WHERE instance_id=? AND run_id=?",instance,id);
    }
    for(JsonObject stale:rows(c,"SELECT id,snapshot FROM pm_instances WHERE last_seen<? LIMIT 32",now-12*HOUR)){JsonObject snapshot=JsonParser.parseString(stale.get("snapshot").getAsString()).getAsJsonObject();if(snapshot.has("metric")||snapshot.has("collections")){scrubPerformance(snapshot);update(c,"UPDATE pm_instances SET snapshot=? WHERE id=?",JSON.toJson(snapshot),stale.get("id").getAsString());}}
    update(c,"UPDATE pm_commands SET state='expired' WHERE state='pending' AND expires_at<?",now);
  }return removed;}
}
