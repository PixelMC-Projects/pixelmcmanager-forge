package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import static com.pixelmc.manager.service.ManagerStore.*;
import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Explicit global cleanup with one recovery item; never runs from a plugin-local delete. */
public final class PlatformDeletion {
  private final ManagerStore store;
  private final PlatformStore platform;
  private final PlatformAvatars avatars;
  public PlatformDeletion(ManagerStore store,PlatformStore platform,PlatformAvatars avatars){this.store=store;this.platform=platform;this.avatars=avatars;}
  private record Selection(String table,String column,boolean contains){}
  private List<Selection> selections()throws Exception{
    List<Selection> result=new ArrayList<>();for(String table:List.of("pm_profiles","pm_players","pm_logins","pm_values","pm_admissions"))result.add(new Selection(table,"uuid",false));
    // Native adapters explicitly declare SQL ownership; future typed fields live in pm_values.
    for(JsonObject module:platform.modules())if(module.has("playerTables"))for(JsonElement raw:array(module,"playerTables",64)){
      JsonObject spec=raw.getAsJsonObject();String table=text(spec,"table",64),column=text(spec,"column",64);if(!table.matches("[a-z][a-z0-9_]{0,63}")||!column.matches("[a-z][a-z0-9_]{0,63}")||!module.getAsJsonArray("tables").contains(new JsonPrimitive(table)))throw new SecurityException("Invalid module cleanup registration");result.add(new Selection(table,column,bool(spec,"contains",false)));
    }return result;
  }
  private JsonObject snapshot(Connection c,UUID player)throws Exception{
    JsonObject out=new JsonObject(),tables=new JsonObject();for(Selection selection:selections()){
      if(!PlatformStore.tableExists(c,selection.table()))continue;String condition="`"+selection.column()+"`"+(selection.contains()?" LIKE ?":"=?");String match=selection.contains()?"%"+player+"%":player.toString();List<JsonObject> selected=rows(c,"SELECT * FROM `"+selection.table()+"` WHERE "+condition+" FOR UPDATE",match);
      JsonArray combined=tables.has(selection.table())?tables.getAsJsonArray(selection.table()):new JsonArray();for(JsonObject row:selected)if(!combined.contains(row))combined.add(row);tables.add(selection.table(),combined);
    }
    out.add("tables",tables);out.add("resets",JSON.toJsonTree(rows(c,"SELECT * FROM pm_player_resets WHERE uuid=?",player.toString())));out.add("revisionState",JSON.toJsonTree(rows(c,"SELECT * FROM pm_profile_revisions WHERE uuid=?",player.toString())));out.add("avatar",avatars.read(player));out.addProperty("definitions",definitions(c));if(JSON.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw new IllegalStateException("单项恢复记录过大，清除未执行；请先导出并分批处理历史");return out;
  }
  private String definitions(Connection c)throws Exception{
    JsonObject data=new JsonObject();for(String table:List.of("mt_titles","df_fortunes"))if(PlatformStore.tableExists(c,table))data.add(table,JSON.toJsonTree(rows(c,"SELECT * FROM "+table+" ORDER BY id")));data.add("modules",JSON.toJsonTree(platform.modules()));return digest(JSON.toJson(data));
  }
  public JsonObject preview(UUID player,Set<UUID> online)throws Exception{if(online.contains(player))throw new IllegalStateException("玩家当前在线，请先离线再清除平台资料");try(Connection c=store.open()){c.setAutoCommit(false);try{JsonObject snapshot=snapshot(c,player);var profiles=rows(c,"SELECT name FROM pm_profiles WHERE uuid=?",player.toString());if(profiles.isEmpty())throw new IllegalArgumentException("全局玩家不存在");JsonObject out=new JsonObject();out.addProperty("player",player.toString());out.addProperty("name",profiles.get(0).get("name").getAsString());out.addProperty("scope","platform");out.addProperty("stamp",digest(JSON.toJson(snapshot)));JsonObject counts=new JsonObject();snapshot.getAsJsonObject("tables").entrySet().forEach(e->counts.addProperty(e.getKey(),e.getValue().getAsJsonArray().size()));out.add("counts",counts);out.addProperty("avatar",!avatar(snapshot).isJsonNull());out.addProperty("message","将清除该玩家的全局身份、各整合包统计及已注册插件资料。保留管理员可见的恢复记录；不删除网页账号或Minecraft存档。");c.rollback();return out;}catch(Exception e){c.rollback();throw e;}}}
  public String delete(UUID actor,UUID player,String expected,String confirmName,Set<UUID> online)throws Exception{
    if(online.contains(player))throw new IllegalStateException("玩家当前在线，清除操作已取消");try(Connection c=store.open()){c.setAutoCommit(false);Runnable rollback=null;try{
      var profiles=rows(c,"SELECT name FROM pm_profiles WHERE uuid=? FOR UPDATE",player.toString());if(profiles.isEmpty()||!profiles.get(0).get("name").getAsString().equals(confirmName))throw new IllegalStateException("玩家姓名或资料已改变，请重新预览");JsonObject snapshot=snapshot(c,player);if(!digest(JSON.toJson(snapshot)).equals(expected))throw new IllegalStateException("预览后资料已改变，未清除任何数据");
      for(JsonElement raw:snapshot.getAsJsonObject("tables").getAsJsonArray("pm_players")){JsonObject stats=raw.getAsJsonObject();String archive=stats.get("archive_id").getAsString();update(c,"INSERT INTO pm_player_resets(archive_id,uuid,total_base,join_base) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE total_base=total_base+VALUES(total_base),join_base=join_base+VALUES(join_base)",archive,player.toString(),stats.get("total_ms").getAsLong(),stats.get("join_count").getAsLong());}
      JsonObject tables=snapshot.getAsJsonObject("tables");List<String> order=new ArrayList<>(tables.keySet());Collections.reverse(order);for(String table:order)deleteRows(c,table,tables.getAsJsonArray(table));
      long revision=rows(c,"SELECT revision FROM pm_profile_revisions WHERE uuid=? FOR UPDATE",player.toString()).get(0).get("revision").getAsLong()+1;update(c,"UPDATE pm_profile_revisions SET revision=?,deleted=TRUE,deleted_at=? WHERE uuid=?",revision,System.currentTimeMillis(),player.toString());String recovery=UUID.randomUUID().toString();update(c,"INSERT INTO pm_recovery(id,uuid,actor,at_ms,expected_revision,payload) VALUES(?,?,?,?,?,?)",recovery,player.toString(),actor.toString(),System.currentTimeMillis(),revision,JSON.toJson(snapshot));rollback=avatars.swap(player,avatar(snapshot),JsonNull.INSTANCE);audit(c,actor.toString(),"PLATFORM_PLAYER_DELETE",Map.of("player",player.toString(),"recovery",recovery));c.commit();return recovery;
    }catch(Exception e){c.rollback();if(rollback!=null)rollback.run();throw e;}}
  }
  public void restore(UUID actor,String recovery,Set<UUID> online)throws Exception{
    try(Connection c=store.open()){c.setAutoCommit(false);Runnable rollback=null;try{
      var records=rows(c,"SELECT * FROM pm_recovery WHERE id=? FOR UPDATE",uuid(recovery));if(records.isEmpty()||records.get(0).get("restored").getAsBoolean())throw new IllegalStateException("恢复记录不存在或已使用");JsonObject record=records.get(0);UUID player=UUID.fromString(text(record,"uuid",36));if(online.contains(player))throw new IllegalStateException("玩家在线，暂不能恢复");if(!rows(c,"SELECT uuid FROM pm_profiles WHERE uuid=? FOR UPDATE",player.toString()).isEmpty())throw new IllegalStateException("该玩家已有后续资料，拒绝覆盖；请先核对冲突");
      var versions=rows(c,"SELECT revision FROM pm_profile_revisions WHERE uuid=? FOR UPDATE",player.toString());if(versions.isEmpty()||versions.get(0).get("revision").getAsLong()!=record.get("expected_revision").getAsLong())throw new IllegalStateException("玩家资料已发生后续变动，不能强行恢复");JsonObject snapshot=JsonParser.parseString(text(record,"payload",16*1024*1024)).getAsJsonObject();if(!definitions(c).equals(text(snapshot,"definitions",64)))throw new IllegalStateException("称号、签文定义或插件结构已变动，请先核对恢复冲突");
      JsonObject current=snapshot(c,player);for(var table:current.getAsJsonObject("tables").entrySet())if(!table.getValue().getAsJsonArray().isEmpty())throw new IllegalStateException("插件存在新的玩家资料，拒绝覆盖");if(!current.get("avatar").isJsonNull())throw new IllegalStateException("头像已有后续变动，拒绝覆盖");
      update(c,"DELETE FROM pm_player_resets WHERE uuid=?",player.toString());insertRows(c,"pm_player_resets",snapshot.getAsJsonArray("resets"));
      for(var table:snapshot.getAsJsonObject("tables").entrySet())insertRows(c,table.getKey(),table.getValue().getAsJsonArray());rollback=avatars.swap(player,JsonNull.INSTANCE,avatar(snapshot));update(c,"UPDATE pm_recovery SET restored=TRUE WHERE id=?",recovery);update(c,"UPDATE pm_profile_revisions SET revision=revision+1,deleted=FALSE,deleted_at=? WHERE uuid=?",snapshot.getAsJsonArray("revisionState").get(0).getAsJsonObject().get("deleted_at").getAsLong(),player.toString());audit(c,actor.toString(),"PLATFORM_PLAYER_RESTORE",Map.of("player",player.toString(),"recovery",recovery));c.commit();
    }catch(Exception e){c.rollback();if(rollback!=null)rollback.run();throw e;}}
  }
  private static JsonElement avatar(JsonObject snapshot){return snapshot.has("avatar")?snapshot.get("avatar"):JsonNull.INSTANCE;}
  public JsonObject recoveries()throws Exception{try(Connection c=store.open()){JsonObject out=new JsonObject();out.add("rows",JSON.toJsonTree(rows(c,"SELECT id,uuid player,actor,at_ms at,restored FROM pm_recovery ORDER BY at_ms DESC LIMIT 100")));return out;}}
  private static List<String> primary(Connection c,String table)throws Exception{List<JsonObject> keys=rows(c,"SELECT COLUMN_NAME name FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION",table);if(keys.isEmpty())throw new IllegalStateException("Registered table has no primary key");return keys.stream().map(k->text(k,"name",64)).toList();}
  private static void deleteRows(Connection c,String table,JsonArray data)throws Exception{List<String> keys=primary(c,table);String where=String.join(" AND ",keys.stream().map(k->"`"+k+"`=?").toList());for(JsonElement raw:data){JsonObject row=raw.getAsJsonObject();Object[] args=keys.stream().map(k->row.get(k).getAsString()).toArray();update(c,"DELETE FROM `"+table+"` WHERE "+where,args);}}
  private static void insertRows(Connection c,String table,JsonArray data)throws Exception{for(JsonElement raw:data){JsonObject row=raw.getAsJsonObject();List<String> columns=new ArrayList<>(row.keySet());String sql="INSERT INTO `"+table+"` (`"+String.join("`,`",columns)+"`) VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<columns.size();i++){JsonElement value=row.get(columns.get(i));p.setObject(i+1,value.isJsonNull()?null:value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():value.getAsString());}p.executeUpdate();}}}
}
