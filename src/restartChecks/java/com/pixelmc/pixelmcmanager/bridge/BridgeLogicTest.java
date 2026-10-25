package com.pixelmc.pixelmcmanager.bridge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class BridgeLogicTest {
  static int checks;
  static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
  static JsonObject frame(long at,int ticks,long start){JsonObject frame=new JsonObject(),metric=new JsonObject();metric.addProperty("at",at);metric.addProperty("mspt",15);metric.addProperty("tps",20);metric.addProperty("heap",1);frame.add("metric",metric);JsonArray events=new JsonArray();for(int i=0;i<ticks;i++){JsonObject t=new JsonObject();t.addProperty("at",at);t.addProperty("tickIndex",start+i);t.addProperty("duration",65);events.add(t);}frame.add("longTicks",events);return frame;}
  public static void main(String[] args)throws Exception{Path root=Files.createTempDirectory("pixelmc-spool-check-");long now=System.currentTimeMillis();String run=UUID.randomUUID().toString(),old=UUID.randomUUID().toString();Files.createDirectories(root.resolve(old));Files.writeString(root.resolve(old).resolve("old.jsonl"),"old");Files.writeString(root.resolve("checkpoint.txt"),"preserve");
    TelemetrySpool spool=new TelemetrySpool(root,run);check(!Files.exists(root.resolve(old)),"new game run clears only old transient run directory");check(Files.readString(root.resolve("checkpoint.txt")).equals("preserve"),"checkpoint retained");
    spool.append(frame(now,512,1),now);spool.append(frame(now+1,1,513),now+1);JsonObject first=spool.batch();check(first.getAsJsonArray("longTicks").size()==512,"tick batch bounded");check(spool.batch().equals(first),"unacknowledged batch is repeatable");spool.acknowledge();check(spool.batch().getAsJsonArray("longTicks").size()==1,"next tick resumes after acknowledgement");spool.acknowledge();check(spool.batch().getAsJsonArray("metrics").isEmpty(),"acknowledged frames do not replay");
    spool.append(frame(now+1,3,600),now+1);check(spool.batch().getAsJsonArray("metrics").isEmpty(),"duplicate second frame ignored");
    spool.append(frame(now+60001,2,700),now+60001);check(spool.batch().getAsJsonArray("longTicks").size()==2,"next segment readable");spool.acknowledge();
    try(var paths=Files.list(root.resolve(run))){check(paths.count()<=1,"acknowledged closed minute removed");}
    System.out.println("Bridge disk spool checks passed: "+checks);
  }
}
