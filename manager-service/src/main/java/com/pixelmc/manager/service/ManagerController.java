package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.web.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class ManagerController implements PlatformWebServer.Host,AutoCloseable {
  public final ManagerStore store;
  public final TitleService titles;
  public final FortuneBackend fortunes;
  public final ServiceConfig config;
  public final Path data;
  public PlatformWebServer web;
  public final PlatformStore platform;
  private TitlesPlatformAdapter titlesAdapter;
  private final Map<String,JsonObject> gameModules=new ConcurrentHashMap<>();
  private final Map<String,Set<String>> gameNamespaces=new ConcurrentHashMap<>();
  private final java.util.concurrent.locks.ReentrantReadWriteLock moduleGate=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
  private record Presence(Set<UUID> players,long until){}
  private final Map<String,Presence> presence=new ConcurrentHashMap<>();
  private final ScheduledExecutorService tasks=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"PixelMCManager-ServiceMaintenance");t.setDaemon(true);return t;});
  private final AtomicInteger streams=new AtomicInteger();
  private final String boot=UUID.randomUUID().toString();
  private final AtomicLong titleRevision=new AtomicLong();
  private final AtomicInteger observedTitleRetention=new AtomicInteger(-1);
  private volatile long nextBackup,lastBackup;
  private volatile String maintenanceError="";
  private volatile boolean platformReady;
  private final Map<String,long[]> limits=new HashMap<>();
  public ManagerController(ServiceConfig config,Path data)throws Exception{
    this.config=config;this.data=data;Files.createDirectories(data);
    store=new ManagerStore(config.database());platform=new PlatformStore(store);try{store.migrate();platform.migrate();}catch(Exception unavailable){maintenanceError="Manager数据库暂不可用，自动重连中";}
    titles=config.titlesEnabled()?new TitleService(new cn.pixelmc.memorialtitles.persistence.Database(config.titlesDatabase())):null;
    fortunes=config.fortunesEnabled()?new FortuneBackend(config.fortunesDatabase().url(),config.fortunesDatabase().user(),config.fortunesDatabase().password(),data.resolve("dailyfortunes")):null;
    if(titles!=null){if(config.unified())titles.attachPlatform(platformHook("memorialtitles"));titlesAdapter=new TitlesPlatformAdapter(titles,platformHook("memorialtitles"));if(!config.unified())titles.attachPlatform(null);titles.listen(s->{titleRevision.incrementAndGet();int prior=observedTitleRetention.getAndSet(s.settings().retentionDays());if(prior>=0&&prior!=s.settings().retentionDays())try{platform.retention(s.settings().retentionDays(),"titles-settings-compatibility");}catch(Exception e){maintenanceError="平台备份保留策略保存失败";}mirror();});}
    if(config.unified()&&fortunes!=null)fortunes.attachPlatform(platformHook("dailyfortunes"));
    try{if(config.unified())initializePlatform();else{if(titles!=null)platform.register("memorialtitles",moduleManifest("memorialtitles",TitlesPlatformAdapter.storageManifest()));if(fortunes!=null)platform.register("dailyfortunes",moduleManifest("dailyfortunes",FortuneBackend.storageManifest()));platform.backfill();}}catch(Exception unavailable){maintenanceError="平台数据库暂不可用，等待自动重连";}
    if(config.titlesEnabled())try{titles.reconnect().get(15,TimeUnit.SECONDS);}catch(Exception unavailable){maintenanceError="称号数据库暂不可用，其他功能继续运行";}
    try{JsonObject state=JsonParser.parseString(Files.readString(data.resolve("maintenance.json"))).getAsJsonObject();lastBackup=state.get("lastBackup").getAsLong();}catch(Exception ignored){}
    nextBackup=lastBackup>0?Math.max(System.currentTimeMillis(),lastBackup+12*ManagerStore.HOUR):System.currentTimeMillis()+12*ManagerStore.HOUR;
  }
  public void startMaintenance(){tasks.scheduleWithFixedDelay(()->{try{store.migrate();platform.migrate();if(config.unified()&&!platformReady)initializePlatform();store.cleanup(System.currentTimeMillis());maintenanceError="";}catch(Exception e){maintenanceError="性能清理暂未完成";}},1,300,TimeUnit.SECONDS);
    tasks.scheduleWithFixedDelay(()->{try{if(config.titlesEnabled())try{titles.reconnect().get(15,TimeUnit.SECONDS);}catch(Exception unavailable){maintenanceError="称号数据库暂不可用，其他功能继续运行";}syncPack();if(System.currentTimeMillis()>=nextBackup){backupAll();nextBackup=System.currentTimeMillis()+12*ManagerStore.HOUR;}}catch(Exception e){maintenanceError="数据维护或备份失败，请检查服务日志";nextBackup=Math.max(nextBackup,System.currentTimeMillis()+60000);}},15,30,TimeUnit.SECONDS);
  }
  private void syncPack()throws Exception{if(!config.titlesEnabled()||!titles.healthy())return;var instances=store.instances().stream().filter(i->!i.get("archiveId").isJsonNull()).toList();if(instances.size()!=1)return;var archive=store.archiveInfo(instances.get(0).get("archiveId").getAsString());var old=titles.snapshot().settings();String name=archive.get("name").getAsString()+(archive.get("version").getAsString().isBlank()?"":" "+archive.get("version").getAsString());String date=archive.get("startedOn").getAsString();if(!old.packName().equals(name)||!old.packStartedOn().equals(date))titles.settings(TitleService.Actor.consoleActor(),new Settings(old.reward(),old.head(),old.chat(),old.tab(),old.headScale(),old.headOffset(),old.chatPrefix(),old.retentionDays(),name,date)).get(5,TimeUnit.SECONDS);}
  @Override public boolean bridgeAuthority(HttpExchange e,String path){if(!path.startsWith("/bridge/")||!e.getRequestMethod().equals("POST")||e.getRequestHeaders().getFirst("Origin")!=null)return false;try{var uri=java.net.URI.create("http://"+e.getRequestHeaders().getFirst("Host"));return uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getPort()==config.port()&&uri.getRawPath().isEmpty()&&uri.getRawQuery()==null;}catch(Exception invalid){return false;}}
  @Override public int httpThreads(){return 8;}

  @Override public boolean platformAdmin(UUID id){return config.owners().contains(id);}
  @Override public String profileName(UUID id){try{JsonObject value=platform.profile(id.toString());return value.get("profile").isJsonNull()?id.toString():value.getAsJsonObject("profile").get("name").getAsString();}catch(Exception e){return id.toString();}}
  @Override public Set<UUID> online(){long now=System.currentTimeMillis();Set<UUID> out=new HashSet<>();presence.values().stream().filter(p->p.until()>now).forEach(p->out.addAll(p.players()));return Set.copyOf(out);}
  public void mirror(){if(!config.titlesEnabled())return;try{ServiceConfig.atomic(data.resolve("titles/definitions.json"),cn.pixelmc.memorialtitles.domain.Model.JSON.toJson(AdminWebServer.definitions(titles.snapshot())));}catch(Exception e){maintenanceError="称号已提交，配置镜像待写入";}}
  public CompletableFuture<Path> backup(TitleService.Actor actor){TitleService.requireAdmin(titles.snapshot(),actor);return titles.background(this::backupAll);}
  public CompletableFuture<Void> reload(TitleService.Actor actor){return titles.background(()->cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(Files.readString(data.resolve("titles/definitions.json")),AdminWebServer.Definitions.class)).thenCompose(d->titles.reload(actor,d.titles(),d.settings()));}
  @Override public void requestAvatar(UUID id,String nonce){
    for(var entry:presence.entrySet())if(entry.getValue().until()>System.currentTimeMillis()&&entry.getValue().players().contains(id)){
      for(var module:gameModules.entrySet()){if(!gameNamespaces.getOrDefault(entry.getKey(),Set.of()).contains(module.getKey()))continue;JsonObject metadata=module.getValue();JsonObject manifest=metadata.has("storage")?metadata.getAsJsonObject("storage"):metadata;JsonArray caps=manifest.has("capabilities")?manifest.getAsJsonArray("capabilities"):new JsonArray();if(!caps.contains(new JsonPrimitive("client-avatar")))continue;try{store.callback(entry.getKey(),module.getKey(),"avatarRequest",Map.of("player",id.toString(),"nonce",nonce));}catch(Exception ignored){}return;}
      return;
    }
  }
  private synchronized boolean rate(String key,int max){long now=System.currentTimeMillis();if(limits.size()>4096)limits.entrySet().removeIf(e->now-e.getValue()[0]>60000);if(limits.size()>8192)return false;long[] counter=limits.computeIfAbsent(key,k->new long[]{now,0});if(now-counter[0]>60000){counter[0]=now;counter[1]=0;}return ++counter[1]<=max;}
  @Override public boolean publicRoute(HttpExchange e,String path)throws Exception{
    if(!path.startsWith("/bridge/"))return false;
    if(!e.getRequestMethod().equals("POST")||e.getRequestHeaders().getFirst("Origin")!=null){json(e,403,Map.of("error","仅游戏桥接可用"));return true;}
    if(!Optional.ofNullable(e.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")){json(e,415,Map.of("error","需要JSON"));return true;}
    String ip=e.getRemoteAddress().getAddress().getHostAddress();if(!rate("bridge:"+ip,600)){json(e,429,Map.of("error","桥接请求过多"));return true;}
    String instance=Objects.toString(e.getRequestHeaders().getFirst("X-PixelMC-Instance"),"");String auth=Objects.toString(e.getRequestHeaders().getFirst("Authorization"),"");
    if(!auth.startsWith("Bearer ")||!store.authenticate(instance,auth.substring(7))){json(e,403,Map.of("error","桥接认证失败"));return true;}
    JsonObject b=body(e);
    moduleGate.readLock().lock();try{switch(path){
      case "/bridge/capabilities"->json(e,200,Map.of("version","0.6.0","protocol",1,"titlesApi",3,"platformStorage",config.unified()?1:0,"titlesHealthy",titles!=null&&titles.healthy(),"fortunesEnabled",fortunes!=null));
      case "/bridge/hello"->{JsonObject response=store.hello(instance,b);registerGameModules(instance,object(b,"info"));response.addProperty("publicUrl",config.publicUrl());response.addProperty("protocol",1);response.addProperty("platformStorage",config.unified()?1:0);json(e,200,response);}
      case "/bridge/snapshot"->{JsonObject response=store.ingest(instance,b);if(response.has("accepted")&&response.get("accepted").getAsBoolean()){
        registerGameModules(instance,object(b,"snapshot"));Set<UUID> players=new HashSet<>();for(JsonElement raw:array(b,"players",2000)){JsonObject p=raw.getAsJsonObject();if(!bool(p,"fake",false))players.add(UUID.fromString(text(p,"id",36)));}
        Presence before=presence.put(instance,new Presence(Set.copyOf(players),bool(b,"stopped",false)?0:System.currentTimeMillis()+10000));
        if(web!=null){if(before!=null)for(UUID id:before.players())if(!players.contains(id))web.avatars.logout(id);for(JsonElement raw:array(b,"logins",512)){UUID id=UUID.fromString(text(raw.getAsJsonObject(),"uuid",36));if(players.contains(id))web.avatars.login(id);}}
      }json(e,200,response);}
      case "/bridge/rpc"->{String module=text(b,"extension",64);if(text(b,"op",64).equals("platform")){if(!gameNamespaces.getOrDefault(instance,Set.of()).contains(module))throw new SecurityException("Extension not registered for this instance");JsonObject result=new JsonObject();JsonObject payload=object(b,"data");if(text(payload,"op",64).equals("avatars.upload")){UUID player=UUID.fromString(text(payload,"player",36));if(!admitted(instance).contains(player)||!player.toString().equals(text(b,"playerProof",36)))throw new SecurityException("Client avatar requires current admission proof");}result.add("result",platformHook(module).apply(payload));json(e,200,result);}else if(module.equals("dailyfortunes"))json(e,200,fortuneRpc(instance,b));else if(module.equals("memorialtitles"))json(e,200,titleRpc(instance,b));else throw new IllegalArgumentException("Extension RPC not registered");}
      default->json(e,404,Map.of("error","桥接路由不存在"));
    }}finally{moduleGate.readLock().unlock();}
    return true;
  }
  private JsonObject fortuneRpc(String instance,JsonObject b)throws Exception{
    if(fortunes==null)throw new IllegalArgumentException("DailyFortunes 扩展未启用");
    Presence report=presence.get(instance);Set<UUID> admitted=report!=null&&report.until()>System.currentTimeMillis()?report.players():Set.of();
    JsonObject response=new JsonObject();try{response.add("result",fortunes.rpc(text(b,"op",64),object(b,"data"),text(b,"playerProof",36),admitted));}
    catch(Exception error){response.addProperty("error",fortuneError(error));}response.addProperty("healthy",bool(fortunes.status(),"database",false));return response;
  }
  private static String fortuneError(Exception error){return error instanceof IllegalArgumentException||error instanceof IllegalStateException||error instanceof SecurityException?Objects.toString(error.getMessage(),"抽签操作未完成"):"抽签数据暂不可用，请稍后重试";}
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
        Set<String> allowed=Set.of("reconnect","login","notifications","acknowledgeNotifications","authorize","saveTitle","grant","titleDeletePreview","deleteTitle","revoke","equip","privacy","toggleDisplayPosition","setUnequippedReminder","settings","reload","history","searchHistory","migrateIdentity","changes","undo","deletePreview","deletePlayer","export","importPreview","restoreEmpty");
        if(!allowed.contains(op))throw new SecurityException("扩展操作未注册");
        if(op.equals("login")&&!store.bound(instance))throw new IllegalStateException("当前存档尚未绑定整合包，首登处理等待管理员完成设置");
        if(op.equals("login")&&(!args.get(0).getAsString().equals(text(b,"playerProof",36))||!admitted(instance).contains(UUID.fromString(args.get(0).getAsString()))))throw new SecurityException("只接受成功入服玩家的身份报告");
        if(op.equals("setUnequippedReminder") && (args.size()!=2
            || !args.get(0).getAsString().equals(text(b,"playerProof",36))
            || !args.get(1).isJsonPrimitive() || !args.get(1).getAsJsonPrimitive().isBoolean()))
          throw new SecurityException("只接受已入服玩家本人的提醒开关与布尔参数");
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
    JsonObject out=new JsonObject();out.addProperty("version","0.6.0");out.add("instances",JSON.toJsonTree(store.instances()));
    out.add("archives",JSON.toJsonTree(instance.isBlank()?List.of():store.archives(instance)));
    JsonObject state=instance.isBlank()?new JsonObject():store.state(instance,archive);avatars(state);out.add("state",state);out.addProperty("databaseHealthy",true);out.add("extensions",extensions());
    out.addProperty("titleRevision",boot+":"+titleRevision.get());out.addProperty("maintenanceError",maintenanceError);out.addProperty("lastBackup",lastBackup);return out;
  }
  private void avatars(JsonObject state){if(web==null)return;if(state.has("players"))for(JsonElement raw:state.getAsJsonArray("players")){JsonObject p=raw.getAsJsonObject();if(!bool(p,"fake",false)){UUID id=UUID.fromString(text(p,"id",36));long version=web.avatars.version(id);p.addProperty("avatarVersion",version);p.addProperty("avatarUrl","/api/manager/avatars/"+id+".png?v="+version);}}}
  private JsonArray extensions()throws Exception{
    JsonArray values=new JsonArray();for(JsonObject manifest:platform.modules()){
      JsonObject value=manifest.deepCopy();String ns=text(value,"id",64);value.addProperty("registered",true);value.addProperty("enabled",ns.equals("memorialtitles")?titles!=null:ns.equals("dailyfortunes")?fortunes!=null:gameModules.containsKey(ns));value.addProperty("gameConnected",gameNamespaces.entrySet().stream().anyMatch(entry->entry.getValue().contains(ns)&&onlineInstances().contains(entry.getKey())));value.addProperty("storageReady",store.healthy());value.addProperty("storageMode",config.storageMode());value.addProperty("scope",value.has("scope")?value.get("scope").getAsString():"global");value.addProperty("healthy",store.healthy()&&(ns.equals("memorialtitles")?titles!=null&&titles.healthy():ns.equals("dailyfortunes")?fortunes!=null&&bool(fortunes.status(),"database",false):true));
      if(ns.equals("memorialtitles"))value.add("capabilities",JSON.toJsonTree(List.of("称号档案","玩家收藏","首登奖励","操作记录")));else if(ns.equals("dailyfortunes"))value.add("capabilities",JSON.toJsonTree(List.of("签文档案","玩家签历","操作记录","抽签设置")));else if(value.has("uiCapabilities"))value.add("capabilities",value.get("uiCapabilities"));
      if(ns.equals("memorialtitles")&&titles!=null){value.addProperty("titles",titles.snapshot().titles().size());value.addProperty("players",platform.profiles().getAsJsonArray("players").size());}values.add(value);
    }return values;
  }
  private Set<String> onlineInstances(){long now=System.currentTimeMillis();Set<String> result=new HashSet<>();presence.forEach((key,value)->{if(value.until()>now)result.add(key);});return result;}
  private Set<UUID> admitted(String instance){Presence p=presence.get(instance);return p!=null&&p.until()>System.currentTimeMillis()?p.players():Set.of();}
  public void initializePlatform()throws Exception{
    if(titles!=null)platform.register("memorialtitles",moduleManifest("memorialtitles",TitlesPlatformAdapter.storageManifest()));
    if(fortunes!=null)platform.register("dailyfortunes",moduleManifest("dailyfortunes",FortuneBackend.storageManifest()));
    if(fortunes!=null)fortunes.request("GET","/state",new JsonObject(),Map.of(),"platform-init",Set.of());
    platform.backfill();platformReady=true;
  }
  private static JsonObject moduleManifest(String namespace,JsonObject manifest){
    JsonArray rules=new JsonArray();if(namespace.equals("memorialtitles")){
      for(String table:List.of("mt_players","mt_owned","mt_equipped","mt_receipts","mt_admins","mt_notifications","mt_display_preferences"))rules.add(cleanupRule(table,table.equals("mt_players")?"uuid":"player",false));
      for(String column:List.of("actor","target"))rules.add(cleanupRule("mt_audit",column,false));rules.add(cleanupRule("mt_cell_versions","cell_key",true));rules.add(cleanupRule("mt_changes","payload",true));
    }else if(namespace.equals("dailyfortunes")){rules.add(cleanupRule("df_players","uuid",false));rules.add(cleanupRule("df_records","player",false));rules.add(cleanupRule("df_requests","player",false));rules.add(cleanupRule("df_audit","payload",true));}
    manifest.add("playerTables",rules);return manifest;
  }
  private static JsonObject cleanupRule(String table,String column,boolean contains){JsonObject rule=new JsonObject();rule.addProperty("table",table);rule.addProperty("column",column);rule.addProperty("contains",contains);return rule;}
  private void registerGameModules(String instance,JsonObject info)throws Exception{
    if(!info.has("extensions"))return;Set<String> present=new HashSet<>();for(JsonElement element:array(info,"extensions",64)){
      JsonObject module=element.getAsJsonObject();String ns=text(module,"id",64);if(!module.has("storage"))continue;present.add(ns);if(module.equals(gameModules.get(ns)))continue;JsonObject manifest=object(module,"storage");
      if(!ns.equals("memorialtitles")&&!ns.equals("dailyfortunes")){if(manifest.has("tables")&&!manifest.getAsJsonArray("tables").isEmpty())throw new SecurityException("Only native service adapters own SQL tables");platform.register(ns,manifest);}
      gameModules.put(ns,module.deepCopy());
    }gameNamespaces.put(instance,Set.copyOf(present));gameModules.keySet().removeIf(ns->gameNamespaces.values().stream().noneMatch(set->set.contains(ns)));
  }
  private java.util.function.Function<JsonObject,JsonObject> platformHook(String namespace){return request->{try{
    String op=text(request,"op",64);if(op.startsWith("storage.")&&!config.unified())throw new IllegalStateException("命名空间存储需要先迁移到统一平台");if(op.equals("avatars.version")){JsonObject value=new JsonObject();value.addProperty("version",web==null?0:web.avatars.version(UUID.fromString(text(request,"player",36))));return value;}
    if(op.equals("avatars.head")){JsonObject value=new JsonObject();value.addProperty("png",Base64.getEncoder().encodeToString(web.avatars.head(UUID.fromString(text(request,"player",36)))));return value;}
    if(op.equals("avatars.upload")){UUID id=UUID.fromString(text(request,"player",36));if(!online().contains(id))throw new SecurityException("Player not admitted");web.avatars.uploadFallback(id,text(request,"nonce",128),text(request,"png",1048576));JsonObject out=new JsonObject();out.addProperty("ok",true);return out;}
    if(op.equals("titles.mirror")){mirror();JsonObject value=new JsonObject();value.addProperty("ok",true);return value;}
    if(op.equals("titles.definitions"))return JSON.toJsonTree(AdminWebServer.definitions(titles.snapshot())).getAsJsonObject();
    return platform.request(namespace,request,online());
  }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("平台服务暂不可用",e);}};}
  private boolean platformRoute(HttpExchange e,String path,UUID actor)throws Exception{
    boolean post=e.getRequestMethod().equals("POST");
    if(path.startsWith("/api/manager/player-delete/")||path.equals("/api/manager/player-recoveries")||path.equals("/api/manager/player-restore")){
      if(!config.unified())throw new IllegalStateException("全局资料操作需要先迁移到统一平台存储");PlatformDeletion deletion=new PlatformDeletion(store,platform,web.avatars);
      if(path.equals("/api/manager/player-recoveries")&&!post){json(e,200,deletion.recoveries());return true;}
      if(!post)throw new IllegalArgumentException("需要POST");for(JsonObject instance:store.instances())if(!instance.get("stopped").getAsBoolean()&&System.currentTimeMillis()-instance.get("lastSeen").getAsLong()>10000)throw new IllegalStateException("游戏连接中断，无法确认玩家已离线，请恢复连接后再操作");JsonObject b=body(e);
      if(path.equals("/api/manager/player-restore")){deletion.restore(actor,text(b,"recovery",36),online());if(titles!=null)titles.reconnect().get(15,TimeUnit.SECONDS);json(e,200,Map.of("ok",true));return true;}
      UUID player=UUID.fromString(text(b,"player",36));if(config.owners().contains(player)||web.accounts.list().stream().anyMatch(a->a.endsWith(" → "+player)))throw new IllegalStateException("此玩家关联平台管理员或网页账号，请先单独处理账号；本入口不删除管理身份");
      if(path.endsWith("/preview")){json(e,200,deletion.preview(player,online()));return true;}
      if(!path.endsWith("/confirm")||!bool(b,"confirm",false))throw new IllegalArgumentException("需要明确二次确认");String recovery=deletion.delete(actor,player,text(b,"stamp",64),text(b,"confirmName",64),online());if(titles!=null)titles.reconnect().get(15,TimeUnit.SECONDS);json(e,200,Map.of("ok",true,"recovery",recovery));return true;
    }
    if(path.equals("/api/manager/backup-policy")){if(post){JsonObject b=body(e);platform.retention((int)number(b,"retentionDays",-1,1,3650),actor.toString());}json(e,200,Map.of("retentionDays",platform.retention(config.backupRetentionDays()),"intervalHours",12));return true;}
    if(path.equals("/api/manager/profiles")&&!post){json(e,200,platform.profiles());return true;}
    if(path.startsWith("/api/avatars/")&&!post){String tail=path.substring("/api/avatars/".length());if(!tail.endsWith(".png"))throw new IllegalArgumentException("头像路径无效");UUID id=UUID.fromString(tail.substring(0,tail.length()-4));if(platform.profile(id.toString()).get("profile").isJsonNull())throw new IllegalArgumentException("玩家未登记");byte[] bytes=web.avatars.head(id);e.getResponseHeaders().set("Content-Type","image/png");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);return true;}
    if(path.startsWith("/api/extensions/")){
      if(!config.unified())throw new IllegalStateException("命名空间存储需要先迁移到统一平台");
      String[] parts=path.substring("/api/extensions/".length()).split("/");if(parts.length!=3||!parts[1].equals("storage")||!Set.of("get","set").contains(parts[2])||!post)throw new IllegalArgumentException("扩展存储路由无效");
      JsonObject request=body(e);request.addProperty("op","storage."+parts[2]);json(e,200,platform.request(parts[0],request,online()));return true;
    }
    boolean daily=path.startsWith("/api/dailyfortunes/");if(daily&&!config.unified())return false;String local=daily?path.substring("/api/dailyfortunes".length()):path.substring(Math.min(4,path.length()));
    if(!Set.of("/backup","/backups","/backup/download","/backup/checksum","/backup/verify","/backup-preview").contains(local)&&!local.startsWith("/backups/"))return false;
    Path folder=data.resolve("backups/manager").toAbsolutePath().normalize();
    if(local.equals("/backup")&&post){json(e,200,Map.of("file",backupAll().getFileName().toString(),"scope","platform"));return true;}
    if(local.equals("/backups")&&!post){List<Map<String,Object>> result=new ArrayList<>();if(Files.isDirectory(folder))try(var files=Files.list(folder)){for(Path file:files.filter(p->p.getFileName().toString().matches("manager-[0-9]+-[0-9a-f]{8}\\.zip")).sorted(Comparator.reverseOrder()).limit(2000).toList())result.add(Map.of("file",file.getFileName().toString(),"bytes",Files.size(file),"modified",Files.getLastModifiedTime(file).toMillis(),"checksum",Files.isRegularFile(file.resolveSibling(file.getFileName()+".sha256")),"scope","platform"));}json(e,200,daily?Map.of("rows",result):result);return true;}
    if(local.equals("/backup-preview")&&post){JsonObject b=body(e);String name=text(b,"file",160);json(e,200,backupPreview(folder,name));return true;}
    if(local.equals("/backup/verify")&&!post){json(e,200,backupPreview(folder,query(e).getOrDefault("file","")));return true;}
    if(!post){String name=Set.of("/backup/download","/backup/checksum","/backup/verify").contains(local)?query(e).getOrDefault("file",""):local.substring("/backups/".length()).replaceFirst("/(download|checksum)$","");if(!name.matches("manager-[0-9]+-[0-9a-f]{8}\\.zip"))throw new IllegalArgumentException("备份文件名无效");Path file=folder.resolve(name+((local.endsWith("/checksum")||local.equals("/backup/checksum"))?".sha256":""));if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("备份不存在");e.getResponseHeaders().set("Content-Type",(local.endsWith("/checksum")||local.equals("/backup/checksum"))?"text/plain; charset=utf-8":"application/zip");e.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+file.getFileName()+"\"");e.sendResponseHeaders(200,Files.size(file));Files.copy(file,e.getResponseBody());return true;}
    throw new IllegalArgumentException("平台备份恢复仅支持停服后空库恢复");
  }
  private Map<String,Object> backupPreview(Path folder,String name)throws Exception{
    if(!name.matches("manager-[0-9]+-[0-9a-f]{8}\\.zip"))throw new IllegalArgumentException("备份文件名无效");Path file=folder.resolve(name),sha=folder.resolve(name+".sha256");if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||!Files.isRegularFile(sha))throw new IllegalArgumentException("备份或校验文件不存在");String actual=ManagerBackup.checksum(file);if(!Files.readString(sha).strip().split("\\s+")[0].equals(actual))throw new IllegalStateException("备份校验失败");try(var zip=new java.util.zip.ZipFile(file.toFile())){JsonObject manifest;try(var in=zip.getInputStream(zip.getEntry("manifest.json"))){manifest=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}Map<String,Object> out=new LinkedHashMap<>();out.put("file",name);out.put("valid",true);out.put("verified",true);out.put("checksum",actual);out.put("scope","platform");out.put("createdAt",manifest.get("createdAt").getAsLong());out.put("tables",manifest.getAsJsonArray("tables").size());out.put("message","完整平台备份校验通过；恢复需停止游戏和网页，在空库执行 restore。");return out;}
  }
  @Override public boolean adminRoute(HttpExchange e,String path,UUID actorId,BooleanSupplier authorized)throws Exception{
    boolean destructive=path.startsWith("/api/manager/player-delete/")||path.equals("/api/manager/player-restore");
    java.util.concurrent.locks.Lock gate=destructive?moduleGate.writeLock():path.startsWith("/api/manager/")?null:moduleGate.readLock();
    if(gate!=null)gate.lock();try{return adminRouteLocked(e,path,actorId,authorized);}finally{if(gate!=null)gate.unlock();}
  }
  private boolean adminRouteLocked(HttpExchange e,String path,UUID actorId,BooleanSupplier authorized)throws Exception{
    TitleService.Actor actor=new TitleService.Actor(actorId,actorId.toString(),false,true);
    if(platformRoute(e,path,actorId))return true;
    if(titlesAdapter!=null&&titlesAdapter.handle(e,path,actorId,online()))return true;
    if(path.startsWith("/api/dailyfortunes/")){if(fortunes==null){json(e,404,Map.of("error","DailyFortunes 扩展未启用"));return true;}String method=e.getRequestMethod();if(!method.equals("GET")&&!method.equals("POST")){json(e,405,Map.of("error","仅支持 GET / POST"));return true;}try{json(e,200,fortunes.request(method,path.substring("/api/dailyfortunes".length()),method.equals("POST")?body(e):new JsonObject(),query(e),actor.player().toString(),online()));}catch(SecurityException denied){json(e,403,Map.of("error",fortuneError(denied)));}catch(IllegalArgumentException invalid){json(e,400,Map.of("error",fortuneError(invalid)));}catch(IllegalStateException conflict){json(e,409,Map.of("error",fortuneError(conflict)));}catch(Exception unavailable){json(e,503,Map.of("error","抽签数据暂不可用，请稍后重试"));}return true;}
    if(!path.startsWith("/api/manager/"))return false;var q=query(e);String instance=instance(q),archive=q.getOrDefault("archive","");boolean post=e.getRequestMethod().equals("POST");
    if(path.startsWith("/api/manager/avatars/")&&!post){String tail=path.substring("/api/manager/avatars/".length());if(!tail.endsWith(".png"))throw new IllegalArgumentException("头像路径无效");UUID id=UUID.fromString(tail.substring(0,tail.length()-4));boolean known=!platform.profile(id.toString()).get("profile").isJsonNull();if(!known&&!config.owners().contains(id))throw new IllegalArgumentException("玩家未登记");byte[] bytes;if(known)bytes=web.avatars.head(id);else try(var in=getClass().getResourceAsStream("/web/default-heads/"+Math.floorMod(id.hashCode(),18)+".png")){bytes=Objects.requireNonNull(in).readAllBytes();}e.getResponseHeaders().set("Content-Type","image/png");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);return true;}
    switch(path){
      case "/api/manager/bootstrap","/api/manager/state"->{if(post)throw new IllegalArgumentException("只读接口");json(e,200,bootstrap(instance,archive));}
      case "/api/manager/events"->{if(post)throw new IllegalArgumentException("只读接口");stream(e,instance,archive,authorized);}
      case "/api/manager/archives"->{if(!post){json(e,200,store.archives(instance));break;}JsonObject created=store.createArchive(body(e),actor.player().toString());syncPack();json(e,200,created);}
      case "/api/manager/archive"->{if(!post)throw new IllegalArgumentException("需要POST");JsonObject b=body(e);store.archive(text(b,"id",36),actor.player().toString());json(e,200,Map.of("ok",true));}
      case "/api/manager/import/preview","/api/manager/import/confirm"->{if(!post)throw new IllegalArgumentException("需要POST");JsonObject b=body(e);if(path.endsWith("confirm")&&!text(b,"checksum",64).equals(digest(JSON.toJson(object(b,"file")))))throw new IllegalArgumentException("导入文件已改变");json(e,200,store.importLegacy(text(b,"archiveId",36),object(b,"file"),actor.player().toString(),path.endsWith("confirm")));}
      case "/api/manager/players"->{if(post)throw new IllegalArgumentException("只读接口");JsonObject state=store.state(instance,archive);String target=state.get("archiveId").getAsString();if(state.get("historical").getAsBoolean())q.put("historical","true");JsonObject result=target.isEmpty()?new JsonObject():store.players(target,q,state.get("connected").getAsBoolean()&&!state.get("historical").getAsBoolean());if(!result.has("rows")){result.add("rows",new JsonArray());result.addProperty("total",0);}for(JsonElement raw:result.getAsJsonArray("rows")){JsonObject p=raw.getAsJsonObject();UUID id=UUID.fromString(text(p,"id",36));long version=web.avatars.version(id);p.addProperty("avatarVersion",version);p.addProperty("avatarUrl","/api/manager/avatars/"+id+".png?v="+version);}json(e,200,result);}
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
  public synchronized Path backupAll()throws Exception{Path file=ManagerBackup.write(store,data,titles,config);lastBackup=System.currentTimeMillis();ServiceConfig.atomic(data.resolve("maintenance.json"),JSON.toJson(Map.of("lastBackup",lastBackup)));ManagerBackup.retain(data,platform.retention(config.backupRetentionDays()));return file;}
  private final AtomicBoolean closing=new AtomicBoolean();
  private final CountDownLatch closed=new CountDownLatch(1);
  @Override public void close(){if(!closing.compareAndSet(false,true)){try{closed.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return;}try{tasks.shutdownNow();if(web!=null)web.close();if(fortunes!=null)fortunes.close();if(titles!=null)titles.close();}finally{closed.countDown();}}
}
