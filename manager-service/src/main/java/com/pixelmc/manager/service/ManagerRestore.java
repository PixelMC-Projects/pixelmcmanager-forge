package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import cn.pixelmc.memorialtitles.persistence.Database;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** Offline, empty-database restore. A marker blocks service startup until both stores and files finish. */
public final class ManagerRestore {
  public static ServiceConfig restore(ServiceConfig target,Path data,Path archive)throws Exception{
    archive=archive.toAbsolutePath().normalize();String hash=ManagerBackup.checksum(archive);
    Path checksum=archive.resolveSibling(archive.getFileName()+".sha256");if(!Files.isRegularFile(checksum)||!Files.readString(checksum).strip().split("\\s+")[0].equals(hash))throw new IllegalArgumentException("备份SHA-256校验失败或缺少校验文件");
    if(target.unified())return restorePlatform(target,data,archive,hash);
    Path marker=data.resolve("restore-pending.json");boolean resume=Files.exists(marker);if(resume&&!text(JsonParser.parseString(Files.readString(marker)).getAsJsonObject(),"checksum",64).equals(hash))throw new IllegalStateException("存在另一个未完成的恢复；请先完成原恢复");
    try(ZipFile zip=new ZipFile(archive.toFile())){
      Set<String> names=new HashSet<>();long size=0;for(var all=zip.entries();all.hasMoreElements();){ZipEntry e=all.nextElement();if(e.isDirectory()||!names.add(e.getName())||e.getSize()<0||(size=Math.addExact(size,e.getSize()))>512L*1024*1024||names.size()>20032)throw new IOException("备份目录或大小超出安全上限");String name=e.getName();if(name.contains("\\")||name.startsWith("/")||Arrays.asList(name.split("/")).contains(".."))throw new IOException("备份路径无效");if(!(name.equals("manifest.json")||name.equals("service-config.json")||name.startsWith("database/")||name.startsWith("titles-database/")||name.startsWith("files/titles/")||name.startsWith("files/credentials/")||name.startsWith("files/platform/")||name.startsWith("files/dailyfortunes/")))throw new IOException("备份含有未注册文件");}
      JsonObject manifest=read(zip,"manifest.json");if(number(manifest,"format",0,2,2)!=2||bool(manifest,"ephemeralPerformanceIncluded",true))throw new IllegalArgumentException("不支持的备份格式");
      ServiceConfig source=JSON.fromJson(read(zip,"service-config.json"),ServiceConfig.class);if(source.titlesEnabled()!=target.titlesEnabled())throw new IllegalArgumentException("称号扩展启用状态与备份不同，请先核对目标配置");
      ManagerStore store=new ManagerStore(target.database());store.migrate();if(target.titlesEnabled())new Database(target.titlesDatabase()).migrate();
      // Validate every registered table before beginning a restore, not halfway through writing it.
      Map<String,String> fingerprints=new HashMap<>();for(String table:ManagerBackup.TABLES)fingerprints.put("database/"+table,fingerprint(zip,"database/"+table+".json"));if(target.titlesEnabled())for(String table:ManagerBackup.TITLE_TABLES)fingerprints.put("titles-database/"+table,fingerprint(zip,"titles-database/"+table+".json"));
      try(Connection c=store.open()){preflight(c,ManagerBackup.TABLES,Set.of("pm_schema"),resume,zip,"database/",fingerprints);}
      if(target.titlesEnabled())try(Connection c=new Database(target.titlesDatabase()).open()){preflight(c,ManagerBackup.TITLE_TABLES,Set.of("mt_schema","mt_state"),resume,zip,"titles-database/",fingerprints);}
      ServiceConfig.atomic(marker,JSON.toJson(Map.of("checksum",hash,"archive",archive.toString())));
      if(target.titlesEnabled())try(Connection c=new Database(target.titlesDatabase()).open()){restoreDatabase(c,ManagerBackup.TITLE_TABLES,Set.of("mt_schema","mt_state"),zip,"titles-database/",fingerprints,resume);}
      try(Connection c=store.open()){restoreDatabase(c,ManagerBackup.TABLES,Set.of("pm_schema"),zip,"database/",fingerprints,resume);}
      for(String name:names)if(name.startsWith("files/")){Path file=data.toAbsolutePath().normalize().resolve(name.substring(6)).normalize();if(!file.startsWith(data.toAbsolutePath().normalize()))throw new IOException("备份路径越界");Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".restore-tmp");try(InputStream input=zip.getInputStream(zip.getEntry(name))){Files.copy(input,temp,StandardCopyOption.REPLACE_EXISTING);}try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
      // Preserve the destination network/DB configuration; restore only the backed-up owner identities.
      ServiceConfig restored=target.withOwners(source.owners());
      ServiceConfig.atomic(data.resolve("restored-owners.json"),JSON.toJson(source.owners()));return restored;
    }
  }
  public static void complete(Path data)throws Exception{Files.deleteIfExists(data.resolve("restore-pending.json"));}
  private static ServiceConfig restorePlatform(ServiceConfig target,Path data,Path archive,String hash)throws Exception{
    Path marker=data.resolve("restore-pending.json");boolean resume=Files.exists(marker);if(resume&&!text(JsonParser.parseString(Files.readString(marker)).getAsJsonObject(),"checksum",64).equals(hash))throw new IllegalStateException("存在另一个未完成的恢复");
    try(ZipFile zip=new ZipFile(archive.toFile())){
      JsonObject manifest=read(zip,"manifest.json");if(number(manifest,"format",0,3,3)!=3||bool(manifest,"ephemeralPerformanceIncluded",true))throw new IllegalArgumentException("统一平台仅接受完整格式3备份");
      List<String> tables=new ArrayList<>();for(JsonElement raw:array(manifest,"tables",1024)){String table=raw.getAsString();if(!table.matches("[a-z][a-z0-9_]{0,63}")||tables.contains(table)||Set.of("pm_metrics","pm_long_ticks","pm_tick_groups").contains(table))throw new IOException("非法或重复表注册");tables.add(table);}
      if(!tables.containsAll(ManagerBackup.TABLES)||!tables.containsAll(PlatformStore.TABLES))throw new IOException("缺少平台持久表");
      Set<String> names=new HashSet<>();long bytes=0;for(var entries=zip.entries();entries.hasMoreElements();){ZipEntry entry=entries.nextElement();String name=entry.getName();if(entry.isDirectory()||!names.add(name)||entry.getSize()<0||(bytes=Math.addExact(bytes,entry.getSize()))>1024L*1024*1024||names.size()>30000||name.contains("\\")||name.startsWith("/")||Arrays.asList(name.split("/")).contains(".."))throw new IOException("备份条目无效");
        boolean allowed=name.equals("manifest.json")||name.equals("service-config.json")||tables.stream().anyMatch(t->name.equals("database/"+t+".json")||name.equals("schema/"+t+".sql"))||List.of("platform","titles","credentials","dailyfortunes").stream().anyMatch(d->name.startsWith("files/"+d+"/"));if(!allowed)throw new IOException("备份含未注册条目");}
      ServiceConfig source=JSON.fromJson(read(zip,"service-config.json"),ServiceConfig.class);if(!source.unified())throw new IllegalArgumentException("不是统一平台备份");
      Map<String,String> fingerprints=new HashMap<>();for(String table:tables)fingerprints.put("database/"+table,fingerprint(zip,"database/"+table+".json"));
      ManagerStore store=new ManagerStore(target.database());try(Connection c=store.open()){
        // No row is changed until every table and asset has passed preflight.
        for(String table:tables){var entry=zip.getEntry("schema/"+table+".sql");if(entry==null||entry.getSize()>262144)throw new IOException("缺少表结构");String ddl;try(InputStream in=zip.getInputStream(entry)){ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);}if(!ddl.startsWith("CREATE TABLE `"+table+"` (")||ddl.contains(";"))throw new IOException("表结构不是单一CREATE TABLE");if(!PlatformStore.tableExists(c,table))try(Statement s=c.createStatement()){s.execute(ddl);}}
        preflight(c,tables,Set.of("pm_schema","pm_platform_schema"),resume,zip,"database/",fingerprints);
      }
      for(String name:names)if(name.startsWith("files/")){Path file=data.toAbsolutePath().normalize().resolve(name.substring(6)).normalize();if(!file.startsWith(data.toAbsolutePath().normalize())||Files.isSymbolicLink(file))throw new IOException("恢复资产路径无效");if(Files.exists(file)){try(InputStream in=zip.getInputStream(zip.getEntry(name))){byte[] restored=in.readAllBytes();if(!Arrays.equals(restored,Files.readAllBytes(file)))throw new IllegalStateException("目标已有不同资产；请使用空数据目录恢复");}}}
      ServiceConfig.atomic(marker,JSON.toJson(Map.of("checksum",hash,"archive",archive.toString())));
      try(Connection c=store.open()){restoreDatabase(c,tables,Set.of("pm_schema","pm_platform_schema"),zip,"database/",fingerprints,resume);}
      for(String name:names)if(name.startsWith("files/")){Path file=data.toAbsolutePath().normalize().resolve(name.substring(6));Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".restore-tmp");try(InputStream in=zip.getInputStream(zip.getEntry(name))){Files.copy(in,temp,StandardCopyOption.REPLACE_EXISTING);}try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
      return target.withOwners(source.owners());
    }
  }
  private static JsonObject read(ZipFile zip,String name)throws Exception{var entry=zip.getEntry(name);if(entry==null||entry.getSize()>1024*1024)throw new IOException("备份缺少元数据或元数据过大");try(Reader r=new InputStreamReader(zip.getInputStream(entry),StandardCharsets.UTF_8)){return JsonParser.parseReader(r).getAsJsonObject();}}
  private interface Each {void row(JsonObject row)throws Exception;}
  private static void readRows(ZipFile zip,String name,Each action)throws Exception{var e=zip.getEntry(name);if(e==null)throw new IOException("备份缺少表："+name);try(JsonReader r=new JsonReader(new InputStreamReader(zip.getInputStream(e),StandardCharsets.UTF_8))){r.beginArray();while(r.hasNext()){JsonObject row=JsonParser.parseReader(r).getAsJsonObject();if(name.endsWith("/mt_display_preferences.json")&&!row.has("reminder_disabled"))row.addProperty("reminder_disabled",false);action.row(row);}r.endArray();}}
  private static class Fingerprint {long count;byte[] sum=new byte[32];void add(JsonObject row)throws Exception{Map<String,String> sorted=new TreeMap<>();row.entrySet().forEach(e->{if(!e.getValue().isJsonNull())sorted.put(e.getKey(),e.getValue().getAsString());});byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(JSON.toJson(sorted).getBytes(StandardCharsets.UTF_8));for(int i=0;i<32;i++)sum[i]^=hash[i];count++;}String value(){return count+":"+HexFormat.of().formatHex(sum);}}
  private static String fingerprint(ZipFile zip,String name)throws Exception{Fingerprint f=new Fingerprint();readRows(zip,name,f::add);return f.value();}
  private static String fingerprint(Connection c,String table)throws Exception{Fingerprint f=new Fingerprint();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+table)){ResultSetMetaData m=r.getMetaData();while(r.next()){JsonObject row=new JsonObject();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);row.add(m.getColumnLabel(i),v==null?JsonNull.INSTANCE:v instanceof java.sql.Date?new JsonPrimitive(v.toString()):JSON.toJsonTree(v));}f.add(row);}}return f.value();}
  private static boolean populated(Connection c,List<String> tables,Set<String> initial)throws Exception{for(String table:tables)if(!initial.contains(table)&&!ManagerStore.rows(c,"SELECT 1 x FROM "+table+" LIMIT 1").isEmpty())return true;return false;}
  private static boolean same(Connection c,List<String> tables,String prefix,Map<String,String> expected)throws Exception{for(String table:tables)if(!fingerprint(c,table).equals(expected.get(prefix+table)))return false;return true;}
  private static void preflight(Connection c,List<String> tables,Set<String> initial,boolean resume,ZipFile zip,String prefix,Map<String,String> expected)throws Exception{if(populated(c,tables,initial)&&!(resume&&same(c,tables,prefix,expected)))throw new IllegalStateException("目标数据库已有资料，拒绝覆盖；请使用空库恢复");}
  private static void restoreDatabase(Connection c,List<String> tables,Set<String> initial,ZipFile zip,String prefix,Map<String,String> expected,boolean resume)throws Exception{
    if(resume&&same(c,tables,prefix,expected))return;preflight(c,tables,initial,resume,zip,prefix,expected);c.setAutoCommit(false);try{
      for(String table:tables){List<String> columns=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+table+" LIMIT 0")){for(int i=1;i<=r.getMetaData().getColumnCount();i++)columns.add(r.getMetaData().getColumnLabel(i));}
        ManagerStore.update(c,"DELETE FROM "+table);String sql="INSERT INTO "+table+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
        try(PreparedStatement p=c.prepareStatement(sql)){int[] batch={0};readRows(zip,prefix+table+".json",row->{if(!columns.containsAll(row.keySet()))throw new IOException("备份字段与目标schema不同");for(int i=0;i<columns.size();i++){JsonElement v=row.get(columns.get(i));p.setObject(i+1,v==null||v.isJsonNull()?null:v.getAsJsonPrimitive().isBoolean()?v.getAsBoolean():v.getAsString());}p.addBatch();if(++batch[0]%256==0)p.executeBatch();});p.executeBatch();}}
      if(!same(c,tables,prefix,expected))throw new IOException("恢复后逐表指纹不匹配，事务已撤销");c.commit();
    }catch(Exception e){c.rollback();throw e;}
  }
  private ManagerRestore(){}
}
