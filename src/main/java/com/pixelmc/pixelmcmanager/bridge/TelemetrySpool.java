package com.pixelmc.pixelmcmanager.bridge;


import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Minute segments keep an offline day of ticks on disk, not millions of heap objects. Worker-only. */
public final class TelemetrySpool {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private final Path root;
    private final NavigableMap<Long,Path> segments=new TreeMap<>();
    private final Map<Long,Long> offsets=new HashMap<>();
    private Map<Long,Long> proposed=Map.of();
    private long lastAt;
    public TelemetrySpool(Path parent,String run)throws Exception{
        Path base=parent.toAbsolutePath().normalize();root=base.resolve(UUID.fromString(run).toString());Files.createDirectories(root);
        // A game run owns its transient samples. Never follow links or touch checkpoint/login files.
        try(var dirs=Files.list(base)){for(Path previous:dirs.toList())if(!previous.equals(root)&&Files.isDirectory(previous,LinkOption.NOFOLLOW_LINKS)&&previous.getFileName().toString().matches("[0-9a-f-]{36}")){
            try(var walk=Files.walk(previous)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList())if(file.toAbsolutePath().normalize().startsWith(base))Files.deleteIfExists(file);}
        }}
    }
    public void append(JsonObject frame,long now)throws Exception{
        if(frame==null)return;JsonObject metric=frame.getAsJsonObject("metric");long at=metric.get("at").getAsLong();if(at<=lastAt)return;lastAt=at;
        long minute=at/60000;Path file=segments.computeIfAbsent(minute,k->root.resolve(k+".jsonl"));JsonObject line=new JsonObject();line.add("metric",metric);line.add("ticks",frame.get("longTicks"));
        Files.writeString(file,JSON.toJson(line)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        while(!segments.isEmpty()&&segments.firstKey()<(now-86400000)/60000){long key=segments.firstKey();Files.deleteIfExists(segments.remove(key));offsets.remove(key);}
    }
    public JsonObject batch()throws Exception{
        JsonArray metrics=new JsonArray(),ticks=new JsonArray();Map<Long,Long> next=new HashMap<>(offsets);
        outer:for(var entry:segments.entrySet()){
            long offset=offsets.getOrDefault(entry.getKey(),0L);try(RandomAccessFile reader=new RandomAccessFile(entry.getValue().toFile(),"r")){
                reader.seek(offset);String raw;while((raw=reader.readLine())!=null){if(raw.length()>262144)throw new IOException("Invalid telemetry frame");JsonObject line=JsonParser.parseString(raw).getAsJsonObject();JsonArray more=line.getAsJsonArray("ticks");if(metrics.size()>=512||ticks.size()+more.size()>512)break outer;metrics.add(line.get("metric"));more.forEach(ticks::add);next.put(entry.getKey(),reader.getFilePointer());}
            }
        }
        proposed=next;JsonObject result=new JsonObject();result.add("metrics",metrics);result.add("longTicks",ticks);return result;
    }
    public void acknowledge()throws Exception{offsets.clear();offsets.putAll(proposed);for(Long key:new ArrayList<>(segments.keySet()))if(key<lastAt/60000&&offsets.getOrDefault(key,0L)>=Files.size(segments.get(key))){Files.deleteIfExists(segments.remove(key));offsets.remove(key);}}
}
