package com.pixelmc.manager.service;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.WebConfig;
import java.nio.file.*;
import java.util.*;

public record ServiceConfig(Database.Config database,Database.Config titlesDatabase,boolean titlesEnabled,
    String bind,int port,String publicUrl,List<UUID> owners,String dataDirectory,
    Database.Config fortunesDatabase,boolean fortunesEnabled) {
  public ServiceConfig(Database.Config database,Database.Config titlesDatabase,boolean titlesEnabled,String bind,int port,String publicUrl,List<UUID> owners,String dataDirectory){this(database,titlesDatabase,titlesEnabled,bind,port,publicUrl,owners,dataDirectory,null,false);}
  public ServiceConfig {
    Objects.requireNonNull(database);Objects.requireNonNull(titlesDatabase);
    new WebConfig(true,bind,port,publicUrl,480,List.of());
    owners=owners==null?List.of():List.copyOf(owners);
    if(dataDirectory==null||dataDirectory.isBlank())throw new IllegalArgumentException("dataDirectory必填");
    if(fortunesEnabled&&(fortunesDatabase==null||fortunesDatabase.url().equals(titlesDatabase.url())||fortunesDatabase.url().equals(database.url())))throw new IllegalArgumentException("DailyFortunes 必须配置独立数据库，不能使用称号或 Manager 数据库");
  }
  public ServiceConfig withOwners(List<UUID> value){return new ServiceConfig(database,titlesDatabase,titlesEnabled,bind,port,publicUrl,value,dataDirectory,fortunesDatabase,fortunesEnabled);}
  public static ServiceConfig load(Path file)throws Exception{return Objects.requireNonNull(Wire.JSON.fromJson(Files.readString(file),ServiceConfig.class));}
  public Path data(Path configFile){return configFile.toAbsolutePath().getParent().resolve(dataDirectory).normalize();}
  public static void atomic(Path file,String value)throws Exception{
    Files.createDirectories(file.toAbsolutePath().getParent());Path tmp=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".tmp");Files.writeString(tmp,value);
    try{Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
    try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
  }
}
