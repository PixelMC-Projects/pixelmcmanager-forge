package com.pixelmc.pixelmcmanager.bridge;

import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import java.nio.file.*;
import java.net.URI;

public final class BridgeConfig {
    public boolean enabled=false;
    public String baseUrl="http://127.0.0.1:18800",instanceId="minecraft",token="",publicUrl="";
    public static BridgeConfig load(Path path)throws Exception {
        if(!Files.exists(path)){Files.createDirectories(path.getParent());Files.writeString(path,ManagerBridgeApi.JSON.toJson(new BridgeConfig()));}
        BridgeConfig config=ManagerBridgeApi.JSON.fromJson(Files.readString(path),BridgeConfig.class);
        if(config==null)throw new IllegalArgumentException("Empty bridge configuration");
        if(config.enabled){URI url=URI.create(config.baseUrl);if(!java.util.Set.of("http","https").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||!java.util.Set.of("","/").contains(url.getPath()))throw new IllegalArgumentException("baseUrl must be a HTTP(S) origin");
            if(!config.instanceId.matches("[A-Za-z0-9_.-]{1,64}")||config.token.length()<32)throw new IllegalArgumentException("Invalid bridge credentials");}
        config.baseUrl=config.baseUrl.replaceAll("/+$","");return config;
    }
}
