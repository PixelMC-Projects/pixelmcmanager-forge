package com.pixelmc.manager.service;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.WebConfig;
import java.nio.file.*;
import java.util.*;

public record ServiceConfig(Database.Config database,Database.Config titlesDatabase,boolean titlesEnabled,
    String bind,int port,String publicUrl,List<UUID> owners,String dataDirectory) {
  public ServiceConfig {
    Objects.requireNonNull(database);Objects.requireNonNull(titlesDatabase);
    new WebConfig(true,bind,port,publicUrl,480,List.of());
    owners=owners==null?List.of():List.copyOf(owners);
    if(dataDirectory==null||dataDirectory.isBlank())throw new IllegalArgumentException("dataDirectory必填");
  }
  public static ServiceConfig load(Path file)throws Exception{return Objects.requireNonNull(Wire.JSON.fromJson(Files.readString(file),ServiceConfig.class));}
  public Path data(Path configFile){return configFile.toAbsolutePath().getParent().resolve(dataDirectory).normalize();}
  public static void atomic(Path file,String value)throws Exception{
    Files.createDirectories(file.toAbsolutePath().getParent());Path tmp=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".tmp");Files.writeString(tmp,value);
    try{Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
    try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
  }
}
