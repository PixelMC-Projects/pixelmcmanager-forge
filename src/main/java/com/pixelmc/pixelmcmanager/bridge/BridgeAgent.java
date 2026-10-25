package com.pixelmc.pixelmcmanager.bridge;

import static com.pixelmc.pixelmcmanager.api.ManagerBridgeApi.JSON;
import com.google.gson.*;
import com.pixelmc.pixelmcmanager.PixelMCManager;
import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import com.pixelmc.pixelmcmanager.config.*;
import com.pixelmc.pixelmcmanager.maintenance.*;
import com.pixelmc.pixelmcmanager.restart.IdleRestartScheduler;
import com.pixelmc.pixelmcmanager.stats.*;
import java.net.*;
import java.net.http.*;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Sampling is on the game thread; all HTTP, journal and disk work is on bounded daemon workers. */
public final class BridgeAgent {
    private final PlayerStatsStore stats;
    private final WelcomeConfigManager config;
    private final MaintenanceScheduler maintenance;
    private final StopServerScheduler stop;
    private final IdleRestartScheduler restart;
    private volatile BridgeConfig bridge;
    private volatile MinecraftServer server;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(worker("Manager-Telemetry"));
    private final ThreadPoolExecutor rpc=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),worker("Manager-Extension"),new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService disk=Executors.newSingleThreadExecutor(worker("Manager-Disk"));
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ArrayBlockingQueue<JsonObject> frames=new ArrayBlockingQueue<>(64);
    private final Map<String,Set<net.minecraft.world.level.ChunkPos>> loadedChunks=new ConcurrentHashMap<>();
    private long lostFrames;
    private final ArrayDeque<JsonObject> collections=new ArrayDeque<>();
    private final Map<String,Long> gcIds=new HashMap<>();
    private final AtomicReference<JsonObject> latest=new AtomicReference<>();
    private final ConcurrentLinkedQueue<JsonObject> loginQueue=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> ackQueue=new ConcurrentLinkedQueue<>();
    private final Set<UUID> admitted=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Long> sessions=new HashMap<>();
    private final Set<String> executing=ConcurrentHashMap.newKeySet();
    private final Map<String,JsonObject> completed=new ConcurrentHashMap<>();
    private final Map<String,Long> levelStarts=new HashMap<>();
    private final Map<String,ArrayDeque<Double>> dimensionSamples=new HashMap<>();
    private final Map<String,double[]> levelTimings=new HashMap<>();
    private final List<JsonObject> ticks=new ArrayList<>();
    private final ArrayDeque<JsonObject> pendingLogins=new ArrayDeque<>();
    private TelemetrySpool spool;
    private volatile Map<String,JsonObject> storage=Map.of();
    private volatile long saveBytes=-1,storageAt;
    private volatile String status="disabled",url="",archive="",world="";
    private String run=UUID.randomUUID().toString();
    private Path folder;
    private long startedAt,tickStart,tickIndex,lastSampleNano,lastSampleTick,nextDisk,seq,lastStatsSync;
    private double tickSum;
    private int tickCount;
    private JsonObject inflight;
    private boolean journalDirty,needHello=true;
    private volatile boolean stopping;
    private volatile boolean finalized;
    private static java.util.concurrent.ThreadFactory worker(String name){return task->{Thread t=new Thread(task,name);t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;};}
    public BridgeAgent(PlayerStatsStore stats,WelcomeConfigManager config,MaintenanceScheduler maintenance,StopServerScheduler stop,IdleRestartScheduler restart){this.stats=stats;this.config=config;this.maintenance=maintenance;this.stop=stop;this.restart=restart;}
    public static boolean fake(ServerPlayer p){if(p instanceof FakePlayer)return true;for(Class<?> type=p.getClass();type!=null;type=type.getSuperclass())if(Set.of("dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake","carpet.patches.EntityPlayerMPFake").contains(type.getName()))return true;return false;}
    public boolean admitted(UUID id){return admitted.contains(id);}
    public String publicUrl(){return url;}
    public void login(ServerPlayer player,long at){if(fake(player))return;admitted.add(player.getUUID());sessions.put(player.getUUID(),at);if(bridge!=null&&!bridge.enabled)return;JsonObject event=new JsonObject();String eventId=UUID.randomUUID().toString();event.addProperty("id",eventId);event.addProperty("sessionId",eventId);event.addProperty("uuid",player.getUUID().toString());event.addProperty("name",player.getGameProfile().getName());event.addProperty("at",at);loginQueue.add(event);}
    public void logout(UUID id){admitted.remove(id);sessions.remove(id);}
    @SubscribeEvent public void started(ServerStartedEvent event){server=event.getServer();startedAt=System.currentTimeMillis();lastSampleNano=System.nanoTime();folder=server.getWorldPath(LevelResource.ROOT).resolve("pixelmcmanager/bridge");ManagerBridgeApi.attach(this);
        io.execute(()->{try{bridge=BridgeConfig.load(FMLPaths.CONFIGDIR.get().resolve("pixelmcmanager-bridge.json"));if(!bridge.enabled)return;url=bridge.publicUrl;Files.createDirectories(folder);Path identity=folder.resolve("world-id.txt");world=Files.exists(identity)?UUID.fromString(Files.readString(identity).strip()).toString():UUID.randomUUID().toString();if(!Files.exists(identity))atomic(identity,world);
            Path journal=folder.resolve("pending-logins.json");if(Files.exists(journal)){JsonArray values=JsonParser.parseString(Files.readString(journal)).getAsJsonArray();if(values.size()>100000)throw new IllegalStateException("Login journal limit exceeded; retain it and inspect manually");values.forEach(v->pendingLogins.add(v.getAsJsonObject()));}
            spool=new TelemetrySpool(folder.resolve("telemetry"),run);status="connecting";io.scheduleWithFixedDelay(this::pump,0,1,TimeUnit.SECONDS);
        }catch(Exception error){status="configuration-error";PixelMCManager.LOGGER.error("Manager bridge unavailable; gameplay continues",error);}});
    }
    @SubscribeEvent public void chunkLoaded(net.minecraftforge.event.level.ChunkEvent.Load event){if(event.getLevel() instanceof ServerLevel level)loadedChunks.computeIfAbsent(level.dimension().location().toString(),k->ConcurrentHashMap.newKeySet()).add(event.getChunk().getPos());}
    @SubscribeEvent public void chunkUnloaded(net.minecraftforge.event.level.ChunkEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){var set=loadedChunks.get(level.dimension().location().toString());if(set!=null)set.remove(event.getChunk().getPos());}}
    private int strong(ServerLevel level){int n=0;for(var pos:loadedChunks.getOrDefault(level.dimension().location().toString(),Set.of()))if(level.isPositionEntityTicking(new net.minecraft.core.BlockPos(pos.x<<4,0,pos.z<<4)))n++;return n;}
    private int entities(ServerLevel level){int n=0;for(var ignored:level.getAllEntities())n++;return n;}
    private void collectGc(JsonObject snapshot){for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean instanceof com.sun.management.GarbageCollectorMXBean gc){var last=gc.getLastGcInfo();if(last!=null&&gcIds.getOrDefault(gc.getName(),-1L)!=last.getId()){gcIds.put(gc.getName(),last.getId());long before=0,after=0;for(var name:ManagementFactory.getMemoryPoolMXBeans())if(name.getType()==MemoryType.HEAP){var b=last.getMemoryUsageBeforeGc().get(name.getName());var a=last.getMemoryUsageAfterGc().get(name.getName());if(b!=null)before+=b.getUsed();if(a!=null)after+=a.getUsed();}JsonObject item=new JsonObject();item.addProperty("at",ManagementFactory.getRuntimeMXBean().getStartTime()+last.getEndTime());item.addProperty("before",before/1073741824d);item.addProperty("after",after/1073741824d);item.addProperty("duration",last.getDuration());collections.add(item);while(collections.size()>128)collections.remove();}}snapshot.add("collections",JSON.toJsonTree(collections));}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST) public void beginLevel(TickEvent.LevelTickEvent event){if(event.phase==TickEvent.Phase.START)levelTick(event);}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public void endLevel(TickEvent.LevelTickEvent event){if(event.phase==TickEvent.Phase.END)levelTick(event);}
    private void levelTick(TickEvent.LevelTickEvent event){if(event.side.isClient()||bridge==null||!bridge.enabled)return;String key=event.level.dimension().location().toString();if(event.phase==TickEvent.Phase.START)levelStarts.put(key,System.nanoTime());else{Long begin=levelStarts.remove(key);if(begin!=null){double[] sum=levelTimings.computeIfAbsent(key,k->new double[2]);sum[0]+=(System.nanoTime()-begin)/1e6;sum[1]++;}}}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST) public void beginTick(TickEvent.ServerTickEvent event){if(event.phase==TickEvent.Phase.START)tick(event);}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public void endTick(TickEvent.ServerTickEvent event){if(event.phase==TickEvent.Phase.END)tick(event);}
    private void tick(TickEvent.ServerTickEvent event){if(stopping||bridge==null||!bridge.enabled)return;long nano=System.nanoTime();if(event.phase==TickEvent.Phase.START){tickStart=nano;return;}if(tickStart==0)return;double ms=(nano-tickStart)/1e6;tickSum+=ms;tickCount++;tickIndex++;
        if(ms>50){JsonObject t=new JsonObject();t.addProperty("at",System.currentTimeMillis());t.addProperty("duration",ms);t.addProperty("tickIndex",tickIndex);if(ticks.size()<512)ticks.add(t);}
        if(nano-lastSampleNano>=1_000_000_000L){double tps=Math.min(20,(tickIndex-lastSampleTick)*1e9/(nano-lastSampleNano));capture(false,tps,tickCount==0?0:tickSum/tickCount);lastSampleTick=tickIndex;lastSampleNano=nano;tickSum=0;tickCount=0;levelTimings.clear();}
    }
    private void capture(boolean stopped,double tps,double mspt){long now=System.currentTimeMillis();JsonObject packet=new JsonObject();packet.addProperty("at",now);packet.addProperty("stopped",stopped);JsonArray people=new JsonArray(),all=new JsonArray();
        boolean full=stopped||now-lastStatsSync>=30000;List<PlayerStatsEntry> entries=full?stats.listAll():admitted.stream().map(id->new PlayerStatsEntry(id,stats.getStats(id))).filter(e->e.stats()!=null).toList();if(full)lastStatsSync=now;for(PlayerStatsEntry entry:entries){PlayerStats p=entry.stats();JsonObject raw=JSON.toJsonTree(p).getAsJsonObject();raw.addProperty("id",entry.uuid().toString());ServerPlayer online=server.getPlayerList().getPlayer(entry.uuid());boolean present=online!=null&&admitted(online.getUUID());if(present){raw.addProperty("totalOnlineMillis",p.totalOnlineMillis+stats.uncheckpointedMillis(entry.uuid(),now));}all.add(raw.deepCopy());if(present){raw.addProperty("world",online.serverLevel().dimension().location().toString());raw.addProperty("dimensionId",online.serverLevel().dimension().location().toString());raw.addProperty("ping",online.latency);raw.addProperty("sessionMillis",Math.max(0,now-sessions.getOrDefault(entry.uuid(),now)));raw.addProperty("isOnline",true);people.add(raw);}}
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(fake(p)){JsonObject raw=new JsonObject();raw.addProperty("id",p.getUUID().toString());raw.addProperty("name",p.getGameProfile().getName());raw.addProperty("fake",true);raw.addProperty("world",p.serverLevel().dimension().location().toString());people.add(raw);}
        packet.add("players",people);packet.add("stats",all);packet.add("longTicks",JSON.toJsonTree(ticks));ticks.clear();JsonObject snapshot=new JsonObject();snapshot.addProperty("version","0.5.1");snapshot.addProperty("minecraft","1.20.1");snapshot.addProperty("java",System.getProperty("java.version"));snapshot.addProperty("maxHeap",Runtime.getRuntime().maxMemory()/1073741824d);snapshot.addProperty("heapCommitted",Runtime.getRuntime().totalMemory()/1073741824d);snapshot.addProperty("limit",server.getMaxPlayers());snapshot.addProperty("saveBytes",saveBytes);snapshot.addProperty("storageAt",storageAt);snapshot.add("players",people);snapshot.add("extensions",ManagerBridgeApi.extensions());snapshot.addProperty("bridgeStatus",status);snapshot.addProperty("tickIndex",tickIndex);
        JsonObject metric=new JsonObject();metric.addProperty("at",now);metric.addProperty("tps",tps);metric.addProperty("mspt",mspt);MemoryUsage memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();metric.addProperty("heap",memory.getUsed()/1073741824d);long gcCount=0,gcMs=0;for(GarbageCollectorMXBean gc:ManagementFactory.getGarbageCollectorMXBeans()){gcCount+=Math.max(0,gc.getCollectionCount());gcMs+=Math.max(0,gc.getCollectionTime());}metric.addProperty("gcCount",gcCount);metric.addProperty("gcMillis",gcMs);packet.add("metric",metric);snapshot.add("metric",metric);snapshot.addProperty("nonHeapMb",ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()/1048576d);snapshot.add("gcNames",JSON.toJsonTree(ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList()));
        JsonArray worlds=new JsonArray();Map<String,Path> paths=new LinkedHashMap<>();for(ServerLevel level:server.getAllLevels()){String id=level.dimension().location().toString();JsonObject w=new JsonObject();w.addProperty("code",id);w.addProperty("name",switch(id){case "minecraft:overworld"->"主世界";case "minecraft:the_nether"->"下界";case "minecraft:the_end"->"末地";default->id;});w.addProperty("chunks",level.getChunkSource().getLoadedChunksCount());int strong=strong(level);w.addProperty("strong",strong);w.addProperty("weak",Math.max(0,level.getChunkSource().getLoadedChunksCount()-strong));w.addProperty("forced",level.getForcedChunks().size());w.addProperty("players",level.players().size());w.addProperty("entities",entities(level));w.addProperty("gameTime",level.getDayTime());w.addProperty("time",id.equals("minecraft:overworld")?"第 "+(level.getDayTime()/24000)+" 天":"—");w.addProperty("weather",level.isThundering()?"雷雨":level.isRaining()?"雨":"晴朗");double[] timing=levelTimings.get(id);JsonObject performance=new JsonObject();if(timing!=null&&timing[1]>0){double avg=timing[0]/timing[1];performance.addProperty("tickMs",avg);performance.addProperty("share",mspt>0?100*avg/mspt:0);performance.addProperty("windowSeconds",1);var samples=dimensionSamples.computeIfAbsent(id,k->new ArrayDeque<>());samples.add(avg);while(samples.size()>60)samples.remove();var sorted=samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();performance.addProperty("sampleCount",sorted.length);performance.addProperty("mean60",Arrays.stream(sorted).average().orElse(0));performance.addProperty("p95Seconds60",sorted[Math.max(0,(int)Math.ceil(sorted.length*.95)-1)]);performance.addProperty("maxSeconds60",sorted[sorted.length-1]);}w.add("performance",performance);JsonObject s=storage.get(id);w.add("storage",s==null?new JsonObject():s.deepCopy());worlds.add(w);paths.put(id,net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),server.getWorldPath(LevelResource.ROOT)));}snapshot.add("worlds",worlds);
        collectGc(snapshot);snapshot.addProperty("lostFrames",lostFrames);snapshot.addProperty("modCount",net.minecraftforge.fml.ModList.get().size());snapshot.addProperty("forge",net.minecraftforge.versions.forge.ForgeVersion.getVersion());snapshot.addProperty("environment",System.getenv("MCSM_INSTANCE_UUID")!=null?"MCSM":System.getenv("container")!=null?"Docker":"独立游戏进程");snapshot.add("management",management());packet.add("snapshot",snapshot);latest.set(packet);if(!frames.offer(packet))lostFrames++;
        if(now>=nextDisk){nextDisk=now+1800000;Path root=server.getWorldPath(LevelResource.ROOT);disk.execute(()->scan(paths,root));}
    }
    private JsonObject management(){JsonObject result=new JsonObject();result.add("welcome",JSON.toJsonTree(config.getConfig()));result.add("announcements",JSON.toJsonTree(config.getConfig().announcements));result.addProperty("autoRestart",config.getConfig().restart.restartOnModChange);result.addProperty("restartStatus",restart.status());result.add("maintenance",JSON.toJsonTree(maintenance.getStatusSnapshot()));result.add("stop",JSON.toJsonTree(stop.getCurrentPlanSnapshot().orElse(null)));return result;}
    private void scan(Map<String,Path> paths,Path root){try{Map<String,JsonObject> out=new LinkedHashMap<>();for(var entry:paths.entrySet()){JsonObject s=new JsonObject();s.addProperty("path",root.toAbsolutePath().normalize().relativize(entry.getValue().toAbsolutePath().normalize()).toString());long total=0;for(String name:List.of("region","entities","poi")){long[] values=bytes(entry.getValue().resolve(name));s.addProperty(name.equals("region")?"terrainBytes":name.equals("entities")?"entityBytes":"poiBytes",values[0]);if(name.equals("region"))s.addProperty("regionFileCount",values[1]);total+=values[0];}s.addProperty("otherBytes",0);s.addProperty("totalBytes",total);s.addProperty("sampledAt",System.currentTimeMillis());out.put(entry.getKey(),s);}storage=Map.copyOf(out);saveBytes=bytes(root)[0];storageAt=System.currentTimeMillis();}catch(Exception e){PixelMCManager.LOGGER.warn("Manager disk sample deferred: {}",e.getClass().getSimpleName());}}
    private static long[] bytes(Path path)throws Exception{long[] result={0,0};if(!Files.exists(path))return result;Files.walkFileTree(path,new SimpleFileVisitor<>(){int count;@Override public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws java.io.IOException{if(Thread.currentThread().isInterrupted())return FileVisitResult.TERMINATE;if(a.isRegularFile()){result[0]+=a.size();result[1]++;}if(++count%256==0)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();return FileVisitResult.TERMINATE;}return FileVisitResult.CONTINUE;}});return result;}
    private static void atomic(Path path,String value)throws Exception{Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,value);try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}}
    private synchronized void pump(){if(bridge==null||!bridge.enabled||world.isEmpty())return;try{
        JsonObject login;while((login=loginQueue.poll())!=null){pendingLogins.add(login);journalDirty=true;}if(journalDirty){atomic(folder.resolve("pending-logins.json"),JSON.toJson(pendingLogins));journalDirty=false;}
        JsonObject frame;while((frame=frames.poll())!=null)spool.append(frame,System.currentTimeMillis());JsonObject newest=latest.getAndSet(null);if(newest!=null){current=newest;freshPending=true;}
        if((needHello&&inflight==null)||archive.isEmpty()){JsonObject hello=new JsonObject();hello.addProperty("runId",run);hello.addProperty("worldId",world);hello.addProperty("startedAt",startedAt);hello.add("info",current==null?new JsonObject():current.get("snapshot"));JsonObject response=request("hello",hello);needHello=false;url=response.get("publicUrl").getAsString();archive=response.has("archiveId")?response.get("archiveId").getAsString():"";if(archive.isEmpty()){status="binding-required";return;}}
        if(inflight==null&&current!=null&&freshPending){freshPending=false;inflight=current.deepCopy();inflight.addProperty("runId",run);inflight.addProperty("archiveId",archive);inflight.addProperty("seq",++seq);inflight.add("logins",head(pendingLogins,512));JsonObject samples=spool.batch();inflight.add("longTicks",samples.get("longTicks"));inflight.add("metrics",samples.get("metrics"));JsonArray acks=new JsonArray();JsonObject ack;while((ack=ackQueue.poll())!=null&&acks.size()<32)acks.add(ack);inflight.add("acks",acks);}
        if(inflight!=null){JsonObject response=request("snapshot",inflight);if(response.get("ack").getAsLong()>=inflight.get("seq").getAsLong()){remove(pendingLogins,inflight.getAsJsonArray("logins").size());spool.acknowledge();atomic(folder.resolve("pending-logins.json"),JSON.toJson(pendingLogins));if(inflight.has("stopped")&&inflight.get("stopped").getAsBoolean())finalized=true;inflight=null;status="connected";}for(JsonElement v:response.getAsJsonArray("commands"))dispatch(v.getAsJsonObject());}
    }catch(Exception e){status="disconnected";needHello=true;}}
    private JsonObject current;
    private boolean freshPending;
    private static JsonArray head(ArrayDeque<JsonObject> queue,int max){JsonArray out=new JsonArray();for(JsonObject v:queue){if(out.size()>=max)break;out.add(v);}return out;}
    private static void remove(ArrayDeque<JsonObject> queue,int count){for(int i=0;i<count&&!queue.isEmpty();i++)queue.remove();}
    private JsonObject request(String op,JsonObject body)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create(bridge.baseUrl+"/bridge/"+op)).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json").header("X-PixelMC-Instance",bridge.instanceId).header("Authorization","Bearer "+bridge.token).POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200||response.body().length()>8*1024*1024)throw new IllegalStateException("Bridge HTTP "+response.statusCode());return JsonParser.parseString(response.body()).getAsJsonObject();}
    public CompletableFuture<String> rpc(String extension,String method,String json){CompletableFuture<String> future=new CompletableFuture<>();try{rpc.execute(()->{try{if(bridge==null||!bridge.enabled)throw new IllegalStateException("Manager bridge disabled");JsonObject data=JsonParser.parseString(json).getAsJsonObject(),body=new JsonObject();body.addProperty("extension",extension);body.addProperty("op",method);body.add("data",data);if(method.equals("login")||method.equals("setUnequippedReminder")){UUID id=UUID.fromString(data.getAsJsonArray("args").get(0).getAsString());if(!admitted(id))throw new SecurityException("Player admission not confirmed");body.addProperty("playerProof",id.toString());}future.complete(JSON.toJson(request("rpc",body)));}catch(Exception e){future.completeExceptionally(e);}});}catch(RejectedExecutionException e){future.completeExceptionally(new IllegalStateException("Extension queue is full"));}return future;}
    private void dispatch(JsonObject command){String id=command.get("id").getAsString();JsonObject done=completed.get(id);if(done!=null){ackQueue.add(done);return;}if(!executing.add(id))return;server.execute(()->{JsonObject ack=new JsonObject();ack.addProperty("id",id);try{if(command.get("expiresAt").getAsLong()<System.currentTimeMillis())throw new IllegalStateException("操作已过期");String op=command.get("op").getAsString();JsonObject payload=JsonParser.parseString(command.get("payload").getAsString()).getAsJsonObject();String message=execute(op,payload);ack.addProperty("ok",true);ack.addProperty("result",message);if(Set.of("welcome","announcements","autoRestart").contains(op)){String serialized=config.snapshotJson();io.execute(()->{try{config.saveSnapshot(serialized);}catch(Exception failure){ack.addProperty("ok",false);ack.addProperty("result","配置已在内存应用，但写盘失败，请检查权限并保存配置");}finish(id,ack);});return;}}catch(Exception e){ack.addProperty("ok",false);ack.addProperty("result",Objects.toString(e.getMessage(),"操作未完成"));}finish(id,ack);});}
    private void finish(String id,JsonObject ack){if(completed.size()>512)completed.clear();completed.put(id,ack);executing.remove(id);ackQueue.add(ack);}
    private String execute(String op,JsonObject p)throws Exception{return switch(op){
        case "extension.callback"->{ManagerBridgeApi.callback(p.get("extension").getAsString(),p.get("op").getAsString(),JSON.toJson(p.get("data")));yield "已通知扩展";}
        case "restart.schedule"->restart.schedule();case "restart.cancel"->restart.cancel();
        case "stop.schedule"->{int seconds=p.get("seconds").getAsInt();if(seconds<10||seconds>604800)throw new IllegalArgumentException("倒计时范围10秒至7天");yield stop.schedule(server,Duration.ofSeconds(seconds)).message();}
        case "stop.cancel"->stop.cancel().message();
        case "maintenance"->{String action=p.get("action").getAsString();yield switch(action){case "on"->maintenance.startNow(server).message();case "off"->maintenance.turnOff().message();case "schedule"->maintenance.schedule(server,Duration.ofSeconds(Math.max(10,Math.min(604800,p.get("seconds").getAsInt())))).message();default->throw new IllegalArgumentException("维护操作无效");};}
        case "autoRestart"->{config.getConfig().restart.restartOnModChange=p.get("enabled").getAsBoolean();restart.optionChanged();yield "模组变更自动重启已更新";}
        case "welcome"->{WelcomeConfig next=JSON.fromJson(p,WelcomeConfig.class);next.normalize(PixelMCManager.LOGGER);WelcomeConfig live=config.getConfig();live.enabled=next.enabled;live.delayTicks=next.delayTicks;live.firstJoinMessages=next.firstJoinMessages;live.returningMessages=next.returningMessages;yield "问候语已保存";}
        case "announcements"->{AnnouncementConfig next=JSON.fromJson(p,AnnouncementConfig.class);next.normalize(PixelMCManager.LOGGER);config.getConfig().announcements=next;yield "轮播公告已保存";}
        default->throw new IllegalArgumentException("未注册的游戏操作");};}
    @SubscribeEvent public void stopping(ServerStoppingEvent event){stopping=true;}
    @SubscribeEvent public void stopped(ServerStoppedEvent event){if(bridge!=null&&bridge.enabled){try{io.submit(()->{pump();captureFinal();pump();}).get(5,TimeUnit.SECONDS);if(!finalized)PixelMCManager.LOGGER.warn("Manager final synchronization pending; archive remains locked");}catch(Exception e){PixelMCManager.LOGGER.warn("Manager final synchronization was not acknowledged; archive remains locked");}}io.shutdownNow();rpc.shutdownNow();disk.shutdownNow();admitted.clear();ManagerBridgeApi.attach(null);
        try{if(com.pixelmc.pixelmcmanager.compat.BackgroundExecutorShutdown.closeMca(getClass().getClassLoader()))PixelMCManager.LOGGER.info("MCA background executor shutdown requested after server stopped; queued tasks may finish normally");}catch(ReflectiveOperationException|RuntimeException failure){PixelMCManager.LOGGER.warn("Optional MCA shutdown integration unavailable: {}",failure.getClass().getSimpleName());}
    }
    private void captureFinal(){if(current!=null){JsonObject end=current.deepCopy();end.addProperty("stopped",true);end.addProperty("at",System.currentTimeMillis());JsonArray all=new JsonArray();for(PlayerStatsEntry e:stats.listAll()){JsonObject p=JSON.toJsonTree(e.stats()).getAsJsonObject();p.addProperty("id",e.uuid().toString());all.add(p);}end.add("stats",all);end.add("players",new JsonArray());end.getAsJsonObject("snapshot").add("players",new JsonArray());latest.set(end);}}
}
