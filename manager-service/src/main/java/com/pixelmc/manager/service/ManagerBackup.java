package com.pixelmc.manager.service;

import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

public final class ManagerBackup {
  public static final List<String> TABLES=List.of("pm_schema","pm_instances","pm_archives","pm_runs","pm_players","pm_logins","pm_imports","pm_commands","pm_audit");
  public static final List<String> TITLE_TABLES=List.of("mt_schema","mt_state","mt_titles","mt_players","mt_owned","mt_equipped","mt_receipts","mt_admins","mt_audit","mt_cell_versions","mt_changes","mt_audit_hidden","mt_notifications","mt_display_preferences");
  public static Path write(ManagerStore store,Path data,TitleService titles,ServiceConfig config)throws Exception{
    Path folder=data.resolve("backups/manager");Files.createDirectories(folder);String name="manager-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".zip";Path temp=folder.resolve(name+".pending"),file=folder.resolve(name);
    try(Connection c=store.open();ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp),StandardCharsets.UTF_8)){
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
      zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(Wire.JSON.toJson(Map.of("format",2,"createdAt",System.currentTimeMillis(),"tables",TABLES,"titlesEnabled",config.titlesEnabled(),"ephemeralPerformanceIncluded",false)).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
      dump(c,zip,"database/",TABLES,true);c.commit();
      if(config.titlesEnabled())try(Connection titleConnection=new cn.pixelmc.memorialtitles.persistence.Database(config.titlesDatabase()).open()){titleConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);titleConnection.setAutoCommit(false);dump(titleConnection,zip,"titles-database/",TITLE_TABLES,false);titleConnection.commit();}
      zip.putNextEntry(new ZipEntry("service-config.json"));zip.write(Wire.JSON.toJson(config).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
      for(String directory:List.of("titles","credentials")){Path assetRoot=data.resolve(directory);if(Files.isDirectory(assetRoot))try(var paths=Files.walk(assetRoot)){var files=paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).limit(10001).toList();if(files.size()>10000)throw new IOException("备份文件数超过安全上限");for(Path p:files){if(p.toString().endsWith(".tmp"))continue;zip.putNextEntry(new ZipEntry("files/"+directory+"/"+assetRoot.relativize(p).toString().replace('\\','/')));Files.copy(p,zip);zip.closeEntry();}}}
    }catch(Exception e){Files.deleteIfExists(temp);throw e;}
    try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
    Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
    ServiceConfig.atomic(file.resolveSibling(file.getFileName()+".sha256"),checksum(file)+"  "+file.getFileName()+"\n");
    return file;
  }
  private static void dump(Connection c,ZipOutputStream zip,String prefix,List<String> tables,boolean manager)throws Exception{
    for(String table:tables){zip.putNextEntry(new ZipEntry(prefix+table+".json"));JsonWriter writer=new JsonWriter(new OutputStreamWriter(zip,StandardCharsets.UTF_8));writer.setSerializeNulls(true);writer.beginArray();try(Statement s=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){s.setFetchSize(256);try(ResultSet r=s.executeQuery("SELECT * FROM "+table)){ResultSetMetaData m=r.getMetaData();while(r.next()){JsonObject row=new JsonObject();for(int j=1;j<=m.getColumnCount();j++){Object value=r.getObject(j);row.add(m.getColumnLabel(j),value==null?JsonNull.INSTANCE:value instanceof java.sql.Date?new JsonPrimitive(value.toString()):Wire.JSON.toJsonTree(value));}if(manager&&table.equals("pm_instances")){JsonObject snapshot=JsonParser.parseString(row.get("snapshot").getAsString()).getAsJsonObject();ManagerStore.scrubPerformance(snapshot);row.addProperty("snapshot",Wire.JSON.toJson(snapshot));}Wire.JSON.toJson(row,writer);}}}writer.endArray();writer.flush();zip.closeEntry();}
  }
  public static String checksum(Path file)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
  public static void retain(Path data,int days)throws Exception{Path dir=data.resolve("backups/manager");long cutoff=System.currentTimeMillis()-Math.max(1,days)*86400000L;if(Files.isDirectory(dir))try(var paths=Files.list(dir)){for(Path p:paths.filter(p->p.getFileName().toString().matches("manager-[0-9]+-[0-9a-f]{8}\\.zip")).toList())if(Files.getLastModifiedTime(p).toMillis()<cutoff){Files.deleteIfExists(p);Files.deleteIfExists(p.resolveSibling(p.getFileName()+".sha256"));}}}
  private ManagerBackup(){}
}
