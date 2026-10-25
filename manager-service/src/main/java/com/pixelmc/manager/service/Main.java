package com.pixelmc.manager.service;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class Main {
  public static void main(String[] args)throws Exception{
    if(args.length<2){System.out.println("PixelMC Manager 0.5.0 · Java17\n用法: java -jar service.jar <init-config|init-admin|instance|run|backup|restore|migrate-web> <manager.json> [参数]");return;}
    String action=args[0];Path file=Path.of(args[1]).toAbsolutePath().normalize();
    if(action.equals("init-config")){
      if(Files.exists(file))throw new IllegalStateException("配置已存在，未覆盖");
      ServiceConfig config=new ServiceConfig(new Database.Config("jdbc:mariadb://127.0.0.1:3306/pixelmc_manager","pixelmc_manager","CHANGE_ME"),new Database.Config("jdbc:mariadb://127.0.0.1:3306/memorialtitles","memorialtitles","CHANGE_ME"),false,"127.0.0.1",18800,"http://127.0.0.1:18800",List.of(),"data");
      ServiceConfig.atomic(file,Wire.JSON.toJson(config));System.out.println("已创建配置："+file);return;
    }
    ServiceConfig config=ServiceConfig.load(file);Path data=config.data(file);Files.createDirectories(data);
    if(action.equals("init-admin")){
      if(args.length!=5)throw new IllegalArgumentException("init-admin 配置文件 用户名 UUID 密码文件");
      String password=Files.readString(Path.of(args[4])).stripTrailing();UUID owner=UUID.fromString(args[3]);
      AccountStore accounts=new AccountStore(data.resolve("titles"));accounts.create(args[2],owner,password);
      Set<UUID> owners=new LinkedHashSet<>(config.owners());owners.add(owner);
      ServiceConfig.atomic(file,Wire.JSON.toJson(new ServiceConfig(config.database(),config.titlesDatabase(),config.titlesEnabled(),config.bind(),config.port(),config.publicUrl(),List.copyOf(owners),config.dataDirectory())));
      System.out.println("管理员账号已创建（未登记游戏玩家）；密码未输出。");return;
    }
    if(action.equals("archive")){
      if(args.length!=3)throw new IllegalArgumentException("archive 配置文件 整合包绑定.json");var store=new ManagerStore(config.database());store.migrate();var binding=com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();var existing=store.archives(Wire.text(binding,"instanceId",64)).stream().filter(a->a.get("status").getAsString().equals("active")).findFirst();if(existing.isPresent()){if(!existing.get().get("worldId").getAsString().equals(Wire.text(binding,"worldId",36)))throw new IllegalStateException("活动档案已绑定其他世界");System.out.println("活动档案已存在，未重复创建。");return;}store.createArchive(binding,"console/deploy");System.out.println("真实整合包档案已建立，等待游戏接入；未创建玩家或称号预设。");return;
    }
    if(action.equals("migrate-web")){
      if(args.length!=4)throw new IllegalArgumentException("migrate-web 配置文件 旧称号配置目录 网页用户名");Path legacy=Path.of(args[2]).toAbsolutePath().normalize();AccountStore accounts=new AccountStore(legacy);AccountStore.Account account=accounts.get(AccountStore.normalize(args[3]));if(account==null)throw new IllegalArgumentException("旧账号不存在");Path target=data.resolve("titles/web-accounts.json");if(Files.exists(target))throw new IllegalStateException("目标已有账号文件，未覆盖");ServiceConfig.atomic(target,Files.readString(legacy.resolve("web-accounts.json")));
      Path avatars=legacy.resolve("avatars");if(Files.isDirectory(avatars))try(var files=Files.walk(avatars)){for(Path source:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){Path destination=data.resolve("titles/avatars").resolve(avatars.relativize(source));Files.createDirectories(destination.getParent());Files.copy(source,destination);}}
      Set<UUID> owners=new LinkedHashSet<>(config.owners());owners.add(account.player());ServiceConfig.atomic(file,Wire.JSON.toJson(new ServiceConfig(config.database(),config.titlesDatabase(),config.titlesEnabled(),config.bind(),config.port(),config.publicUrl(),List.copyOf(owners),config.dataDirectory())));System.out.println("旧网页账号哈希和头像缓存已复制，指定账号已授权；未修改原目录或重置密码。");return;
    }
    if(action.equals("instance")){
      if(args.length!=3)throw new IllegalArgumentException("instance 配置文件 实例ID");
      byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);ManagerStore store=new ManagerStore(config.database());store.migrate();store.register(args[2],token);
      Path target=data.resolve("credentials").resolve(Wire.id(args[2])+"-bridge.json");ServiceConfig.atomic(target,Wire.JSON.toJson(Map.of("enabled",true,"baseUrl",config.publicUrl(),"instanceId",args[2],"token",token,"publicUrl",config.publicUrl())));System.out.println("实例已注册。私密桥接配置保存在："+target);return;
    }
    if(action.equals("restore")){
      if(args.length!=3)throw new IllegalArgumentException("restore 配置文件 完整备份.zip；必须使用空库、停止网页与游戏");
      ServiceConfig restored=ManagerRestore.restore(config,data,Path.of(args[2]));ServiceConfig.atomic(file,Wire.JSON.toJson(restored));ManagerRestore.complete(data);System.out.println("空库恢复完成；目标数据库地址和网页地址保留，账号与授权已恢复。");return;
    }
    if(Files.exists(data.resolve("restore-pending.json")))throw new IllegalStateException("存在未完成的恢复，网页未启动。请对同一备份重新执行restore完成恢复。");
    try(ManagerController controller=new ManagerController(config,data)){
      if(action.equals("backup")){System.out.println("已保存："+controller.backupAll());return;}
      if(!action.equals("run"))throw new IllegalArgumentException("未知操作");
      controller.web=new AdminWebServer(controller.titles,controller,new WebConfig(true,config.bind(),config.port(),config.publicUrl(),480,List.of()),data.resolve("titles"),data.resolve("backups/titles"));
      controller.startMaintenance();CountDownLatch stop=new CountDownLatch(1);Runtime.getRuntime().addShutdownHook(new Thread(()->{stop.countDown();controller.close();},"Manager-Shutdown"));
      Thread stdin=new Thread(()->{try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in))){String line;while((line=reader.readLine())!=null)if(line.strip().equalsIgnoreCase("stop")){stop.countDown();return;}}catch(Exception ignored){}},"Manager-Console");stdin.setDaemon(true);stdin.start();
      System.out.println("PixelMC Manager 独立后台已启动："+controller.web.address()+"；控制台输入 stop 正常停止。");stop.await();
    }
  }
}
