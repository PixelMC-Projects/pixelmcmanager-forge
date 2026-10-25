package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.web.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class ManagerController implements AdminWebServer.Host,AutoCloseable {
  public final ManagerStore store;
  public final TitleService titles;
  public final ServiceConfig config;
  public final Path data;
  public AdminWebServer web;
  private record Presence(Set<UUID> players,long until){}
  private final Map<String,Presence> presence=new ConcurrentHashMap<>();
  private final ScheduledExecutorService tasks=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"PixelMCManager-ServiceMaintenance");t.setDaemon(true);return t;});
  private final AtomicInteger streams=new AtomicInteger();
  private final String boot=UUID.randomUUID().toString();
  private final AtomicLong titleRevision=new AtomicLong();
  private volatile long nextBackup,lastBackup;
  private volatile String maintenanceError="";
  private final Map<String,long[]> limits=new HashMap<>();
  public ManagerController(ServiceConfig config,Path data)throws Exception{
    this.config=config;this.data=data;Files.createDirectories(data);
    store=new ManagerStore(config.database());try{store.migrate();}catch(Exception unavailable){maintenanceError="Manager数据库暂不可用，自动重连中";}
    titles=new TitleService(new cn.pixelmc.memorialtitles.persistence.Database(config.titlesDatabase()));
    titles.listen(s->{titleRevision.incrementAndGet();mirror();});
    if(config.titlesEnabled())try{titles.reconnect().get(15,TimeUnit.SECONDS);}catch(Exception unavailable){maintenanceError="称号数据库暂不可用，其他功能继续运行";}
    try{JsonObject state=JsonParser.parseString(Files.readString(data.resolve("maintenance.json"))).getAsJsonObject();lastBackup=state.get("lastBackup").getAsLong();}catch(Exception ignored){}
    nextBackup=lastBackup>0?Math.max(System.currentTimeMillis(),lastBackup+12*ManagerStore.HOUR):System.currentTimeMillis()+12*ManagerStore.HOUR;
  }
  public void startMaintenance(){tasks.scheduleWithFixedDelay(()->{try{store.migrate();store.cleanup(System.currentTimeMillis());maintenanceError="";}catch(Exception e){maintenanceError="性能清理暂未完成";}},1,300,TimeUnit.SECONDS);
    tasks.scheduleWithFixedDelay(()->{try{if(config.titlesEnabled())try{titles.reconnect().get(15,TimeUnit.SECONDS);}catch(Exception unavailable){maintenanceError="称号数据库暂不可用，其他功能继续运行";}syncPack();if(System.currentTimeMillis()>=nextBackup){backupAll();nextBackup=System.currentTimeMillis()+12*ManagerStore.HOUR;}}catch(Exception e){maintenanceError="数据维护或备份失败，请检查服务日志";nextBackup=Math.max(nextBackup,System.currentTimeMillis()+60000);}},15,30,TimeUnit.SECONDS);
  }
  private void syncPack()throws Exception{if(!config.titlesEnabled()||!titles.healthy())return;var instances=store.instances().stream().filter(i->!i.get("archiveId").isJsonNull()).toList();if(instances.size()!=1)return;var archive=store.archiveInfo(instances.get(0).get("archiveId").getAsString());var old=titles.snapshot().settings();String name=archive.get("name").getAsString()+(archive.get("version").getAsString().isBlank()?"":" "+archive.get("version").getAsString());String date=archive.get("startedOn").getAsString();if(!old.packName().equals(name)||!old.packStartedOn().equals(date))titles.settings(TitleService.Actor.consoleActor(),new Settings(old.reward(),old.head(),old.chat(),old.tab(),old.headScale(),old.headOffset(),old.chatPrefix(),old.retentionDays(),name,date)).get(5,TimeUnit.SECONDS);}
  @Override public boolean bridgeAuthority(HttpExchange e,String path){if(!path.startsWith("/bridge/")||!e.getRequestMethod().equals("POST")||e.getRequestHeaders().getFirst("Origin")!=null)return false;try{var uri=java.net.URI.create("http://"+e.getRequestHeaders().getFirst("Host"));return uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getPort()==config.port()&&uri.getRawPath().isEmpty()&&uri.getRawQuery()==null;}catch(Exception invalid){return false;}}
  @Override public int httpThreads(){return 8;}
  @Override public boolean platformAvailable(){return true;}
  @Override public boolean platformAdmin(UUID id){return config.owners().contains(id);}
  @Override public Set<UUID> online(){long now=System.currentTimeMillis();Set<UUID> out=new HashSet<>();presence.values().stream().filter(p->p.until()>now).forEach(p->out.addAll(p.players()));return Set.copyOf(out);}
  @Override public void mirror(){if(!config.titlesEnabled())return;try{ServiceConfig.atomic(data.resolve("titles/definitions.json"),cn.pixelmc.memorialtitles.domain.Model.JSON.toJson(AdminWebServer.definitions(titles.snapshot())));}catch(Exception e){maintenanceError="称号已提交，配置镜像待写入";}}
  @Override public CompletableFuture<Path> backup(TitleService.Actor actor){return titles.export(actor).thenCompose(a->titles.background(()->{Path folder=data.resolve("backups/titles");Path file=Backup.write(folder,a);Backup.retain(folder,a.settings().retentionDays());return file;}));}
  @Override public CompletableFuture<Void> reload(TitleService.Actor actor){return titles.background(()->cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(Files.readString(data.resolve("titles/definitions.json")),AdminWebServer.Definitions.class)).thenCompose(d->titles.reload(actor,d.titles(),d.settings()));}
  @Override public void requestAvatar(UUID id,String nonce){for(var entry:presence.entrySet())if(entry.getValue().until()>System.currentTimeMillis()&&entry.getValue().players().contains(id)){try{store.callback(entry.getKey(),"memorialtitles","avatarRequest",Map.of("player",id.toString(),"nonce",nonce));}catch(Exception ignored){}return;}}
  private synchronized boolean rate(String key,int max){long now=System.currentTimeMillis();if(limits.size()>4096)limits.entrySet().removeIf(e->now-e.getValue()[0]>60000);if(limits.size()>8192)return false;long[] counter=limits.computeIfAbsent(key,k->new long[]{now,0});if(now-counter[0]>60000){counter[0]=now;counter[1]=0;}return ++counter[1]<=max;}
  @Override public boolean publicRoute(HttpExchange e,String path)throws Exception{
    if(!path.startsWith("/bridge/"))return false;
    if(!e.getRequestMethod().equals("POST")||e.getRequestHeaders().getFirst("Origin")!=null){json(e,403,Map.of("error","仅游戏桥接可用"));return true;}
    if(!Optional.ofNullable(e.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")){json(e,415,Map.of("error","需要JSON"));return true;}
    String ip=e.getRemoteAddress().getAddress().getHostAddress();if(!rate("bridge:"+ip,600)){json(e,429,Map.of("error","桥接请求过多"));return true;}
    String instance=Objects.toString(e.getRequestHeaders().getFirst("X-PixelMC-Instance"),"");String auth=Objects.toString(e.getRequestHeaders().getFirst("Authorization"),"");
    if(!auth.startsWith("Bearer ")||!store.authenticate(instance,auth.substring(7))){json(e,403,Map.of("error","桥接认证失败"));return true;}
    JsonObject b=body(e);
    switch(path){
      case "/bridge/hello"->{JsonObject response=store.hello(instance,b);response.addProperty("publicUrl",config.publicUrl());response.addProperty("protocol",1);json(e,200,response);}
      case "/bridge/snapshot"->{JsonObject response=store.ingest(instance,b);if(response.has("accepted")&&response.get("accepted").getAsBoolean()){
        Set<UUID> players=new HashSet<>();for(JsonElement raw:array(b,"players",2000)){JsonObject p=raw.getAsJsonObject();if(!bool(p,"fake",false))players.add(UUID.fromString(text(p,"id",36)));}
        Presence before=presence.put(instance,new Presence(Set.copyOf(players),bool(b,"stopped",false)?0:System.currentTimeMillis()+10000));
        if(web!=null){if(before!=null)for(UUID id:before.players())if(!players.contains(id))web.avatars.logout(id);for(JsonElement raw:array(b,"logins",512)){UUID id=UUID.fromString(text(raw.getAsJsonObject(),"uuid",36));if(players.contains(id))web.avatars.login(id);}}
      }json(e,200,response);}
      case "/bridge/rpc"->json(e,200,titleRpc(instance,b));
      default->json(e,404,Map.of("error","桥接路由不存在"));
    }
    return true;
  }
  private JsonObject titleRpc(String instance,JsonObject b)throws Exception{
    if(!config.titlesEnabled()||!text(b,"extension",64).equals("memorialtitles"))throw new IllegalArgumentException("扩展未启用");
    String op=text(b,"op",64);JsonObject request=object(b,"data");JsonArray args=array(request,"args",16);JsonObject response=new JsonObject();
    try{
      Object result;
      if(op.equals("backendBackup")){result=backup(cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(args.get(0),TitleService.Actor.class)).get(30,TimeUnit.SECONDS).toString();}
      else if(op.equals("backendReload")){reload(cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(args.get(0),TitleService.Actor.class)).get(30,TimeUnit.SECONDS);result=null;}
      else if(op.equals("avatarUpload")){
        UUID player=UUID.fromString(args.get(0).getAsString());if(!online().contains(player))throw new SecurityException("玩家不在线");
        web.avatars.uploadFallback(player,args.get(1).getAsString(),args.get(2).getAsString());result=null;
      }else{
        Set<String> allowed=Set.of("reconnect","login","notifications","acknowledgeNotifications","authorize","saveTitle","grant","titleDeletePreview","deleteTitle","revoke","equip","privacy","toggleDisplayPosition","settings","reload","history","searchHistory","migrateIdentity","changes","undo","deletePreview","deletePlayer","export","importPreview","restoreEmpty");
        if(!allowed.contains(op))throw new SecurityException("扩展操作未注册");
        if(op.equals("login")&&!store.bound(instance))throw new IllegalStateException("当前存档尚未绑定整合包，首登处理等待管理员完成设置");
        if(op.equals("login")&&!args.get(0).getAsString().equals(text(b,"playerProof",36)))throw new SecurityException("只接受成功入服玩家的身份报告");
        var method=Arrays.stream(TitleService.class.getMethods()).filter(m->m.getName().equals(op)&&m.getParameterCount()==args.size()&&m.getReturnType()==CompletableFuture.class).findFirst().orElseThrow(()->new IllegalArgumentException("扩展参数不匹配"));
        if(op.equals("reconnect")){result=null;}else{
          Object[] values=new Object[args.size()];for(int j=0;j<values.length;j++)values[j]=cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(args.get(j),method.getGenericParameterTypes()[j]);
          result=((CompletableFuture<?>)method.invoke(titles,values)).get(30,TimeUnit.SECONDS);
          if(op.equals("login")&&web!=null)web.avatars.login(UUID.fromString(args.get(0).getAsString()));
        }
      }
      response.add("result",cn.pixelmc.memorialtitles.domain.Model.JSON.toJsonTree(result));
    }catch(Exception error){Throwable cause=error;while(cause.getCause()!=null&&(cause instanceof ExecutionException||cause instanceof java.lang.reflect.InvocationTargetException))cause=cause.getCause();response.addProperty("error",cause instanceof java.sql.SQLException?"称号数据库暂不可用":Objects.toString(cause.getMessage(),"扩展操作失败"));}
    response.addProperty("healthy",titles.healthy());String revision=boot+":"+titleRevision.get();response.addProperty("revision",revision);
    if(!revision.equals(text(request,"revision",128)))response.add("snapshot",cn.pixelmc.memorialtitles.domain.Model.JSON.toJsonTree(SnapshotTransfer.of(titles.snapshot())));
    return response;
  }
  private String instance(Map<String,String> q)throws Exception{String id=q.getOrDefault("instance","");if(!id.isBlank())return Wire.id(id);var all=store.instances();return all.isEmpty()?"":all.get(0).get("id").getAsString();}
  private JsonObject bootstrap(String instance,String archive)throws Exception{
    JsonObject out=new JsonObject();out.addProperty("version","0.5.1");out.add("instances",JSON.toJsonTree(store.instances()));
    out.add("archives",JSON.toJsonTree(instance.isBlank()?List.of():store.archives(instance)));
    JsonObject state=instance.isBlank()?new JsonObject():store.state(instance,archive);avatars(state);out.add("state",state);out.addProperty("databaseHealthy",true);out.add("extensions",extensions());
    out.addProperty("titleRevision",boot+":"+titleRevision.get());out.addProperty("maintenanceError",maintenanceError);out.addProperty("lastBackup",lastBackup);return out;
  }
  private void avatars(JsonObject state){if(web==null)return;if(state.has("players"))for(JsonElement raw:state.getAsJsonArray("players")){JsonObject p=raw.getAsJsonObject();if(!bool(p,"fake",false)){UUID id=UUID.fromString(text(p,"id",36));long version=web.avatars.version(id);p.addProperty("avatarVersion",version);p.addProperty("avatarUrl","/api/manager/avatars/"+id+".png?v="+version);}}}
  private JsonArray extensions(){JsonArray values=new JsonArray();if(config.titlesEnabled()){JsonObject title=new JsonObject();title.addProperty("id","memorialtitles");title.addProperty("name","MemorialTitles");title.addProperty("label","纪念称号");title.addProperty("page","titles");title.addProperty("preference","titles");title.addProperty("titles",titles.snapshot().titles().size());title.addProperty("players",titles.snapshot().players().size());title.addProperty("healthy",titles.healthy());title.add("capabilities",JSON.toJsonTree(List.of("称号档案","玩家收藏","首登奖励","操作记录")));values.add(title);}return values;}
  @Override public boolean adminRoute(HttpExchange e,String path,TitleService.Actor actor,BooleanSupplier authorized)throws Exception{
    if(!path.startsWith("/api/manager/"))return false;var q=query(e);String instance=instance(q),archive=q.getOrDefault("archive","");boolean post=e.getRequestMethod().equals("POST");
    if(path.startsWith("/api/manager/avatars/")&&!post){String tail=path.substring("/api/manager/avatars/".length());if(!tail.endsWith(".png"))throw new IllegalArgumentException("头像路径无效");UUID id=UUID.fromString(tail.substring(0,tail.length()-4));boolean known=store.lifetime(id.toString()).get("archives").getAsInt()>0;if(!known&&!config.owners().contains(id))throw new IllegalArgumentException("玩家未登记");byte[] bytes;if(known)bytes=web.avatars.head(id);else try(var in=getClass().getResourceAsStream("/web/default-heads/"+Math.floorMod(id.hashCode(),18)+".png")){bytes=Objects.requireNonNull(in).readAllBytes();}e.getResponseHeaders().set("Content-Type","image/png");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);return true;}
    switch(path){
      case "/api/manager/bootstrap","/api/manager/state"->{if(post)throw new IllegalArgumentException("只读接口");json(e,200,bootstrap(instance,archive));}
      case "/api/manager/events"->{if(post)throw new IllegalArgumentException("只读接口");stream(e,instance,archive,authorized);}
      case "/api/manager/archives"->{if(!post){json(e,200,store.archives(instance));break;}JsonObject created=store.createArchive(body(e),actor.player().toString());syncPack();json(e,200,created);}
      case "/api/manager/archive"->{if(!post)throw new IllegalArgumentException("需要POST");JsonObject b=body(e);store.archive(text(b,"id",36),actor.player().toString());json(e,200,Map.of("ok",true));}
      case "/api/manager/import/preview","/api/manager/import/confirm"->{if(!post)throw new IllegalArgumentException("需要POST");JsonObject b=body(e);if(path.endsWith("confirm")&&!text(b,"checksum",64).equals(digest(JSON.toJson(object(b,"file")))))throw new IllegalArgumentException("导入文件已改变");json(e,200,store.importLegacy(text(b,"archiveId",36),object(b,"file"),actor.player().toString(),path.endsWith("confirm")));}
      case "/api/manager/players"->{if(post)throw new IllegalArgumentException("只读接口");JsonObject state=store.state(instance,archive);String target=state.get("archiveId").getAsString();JsonObject result=target.isEmpty()?new JsonObject():store.players(target,q,state.get("connected").getAsBoolean()&&!state.get("historical").getAsBoolean());if(!result.has("rows")){result.add("rows",new JsonArray());result.addProperty("total",0);}for(JsonElement raw:result.getAsJsonArray("rows")){JsonObject p=raw.getAsJsonObject();UUID id=UUID.fromString(text(p,"id",36));long version=web.avatars.version(id);p.addProperty("avatarVersion",version);p.addProperty("avatarUrl","/api/manager/avatars/"+id+".png?v="+version);}json(e,200,result);}
      case "/api/manager/lifetime"->{if(post)throw new IllegalArgumentException("只读接口");json(e,200,store.lifetime(q.getOrDefault("player","")));}
      case "/api/manager/logins"->{if(post)throw new IllegalArgumentException("只读接口");String target=store.state(instance,archive).get("archiveId").getAsString();json(e,200,target.isEmpty()?Map.of("rows",List.of(),"total",0,"page",0,"size",8):store.logins(target,q.getOrDefault("player",""),integer(q,"page",0,0,1000000),integer(q,"size",8,1,100)));}
      case "/api/manager/performance"->{if(post)throw new IllegalArgumentException("只读接口");JsonObject state=store.state(instance,archive);long window=Long.parseLong(q.getOrDefault("window","300000"));json(e,200,state.get("historical").getAsBoolean()||!state.has("runId")||state.get("runId").isJsonNull()?List.of():store.metrics(instance,state.get("runId").getAsString(),window,System.currentTimeMillis()));}
      case "/api/manager/long-ticks"->{if(post)throw new IllegalArgumentException("只读接口");JsonObject state=store.state(instance,archive);json(e,200,state.get("historical").getAsBoolean()||!state.has("runId")||state.get("runId").isJsonNull()?Map.of("rows",List.of(),"total",0,"events",0):store.longTickPage(instance,state.get("runId").getAsString(),integer(q,"threshold",50,50,200),integer(q,"page",0,0,1000000),System.currentTimeMillis()));}
      case "/api/manager/command"->{if(post){JsonObject b=body(e);String selected=text(b,"archiveId",36);JsonObject state=store.state(instance,selected);if(state.get("historical").getAsBoolean())throw new IllegalStateException("历史档案只读");json(e,200,store.command(instance,b,actor.player().toString()));}else json(e,200,store.commandResult(instance,q.getOrDefault("id","")));}
      case "/api/manager/backup"->{if(!post)throw new IllegalArgumentException("需要POST");json(e,200,Map.of("file",backupAll().getFileName().toString()));}
      default->json(e,404,Map.of("error","Manager接口不存在"));
    }
    return true;
  }
  private void stream(HttpExchange e,String instance,String archive,BooleanSupplier authorized)throws Exception{
    if(streams.incrementAndGet()>2){streams.decrementAndGet();json(e,429,Map.of("error","实时连接数量已满"));return;}
    try{e.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("X-Accel-Buffering","no");e.sendResponseHeaders(200,0);long until=System.nanoTime()+60_000_000_000L;
      while(System.nanoTime()<until&&authorized.getAsBoolean()){
        String event="event: snapshot\ndata: "+JSON.toJson(bootstrap(instance,archive))+"\n\n";e.getResponseBody().write(event.getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();Thread.sleep(1000);
      }
    }finally{streams.decrementAndGet();}
  }
  public synchronized Path backupAll()throws Exception{Path file=ManagerBackup.write(store,data,titles,config);lastBackup=System.currentTimeMillis();ServiceConfig.atomic(data.resolve("maintenance.json"),JSON.toJson(Map.of("lastBackup",lastBackup)));ManagerBackup.retain(data,titles.snapshot().settings().retentionDays());return file;}
  private final AtomicBoolean closing=new AtomicBoolean();
  private final CountDownLatch closed=new CountDownLatch(1);
  @Override public void close(){if(!closing.compareAndSet(false,true)){try{closed.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return;}try{tasks.shutdownNow();if(web!=null)web.close();titles.close();}finally{closed.countDown();}}
}
