package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Optional own-player changelog API. No administrator route or identity override is exposed. */
public final class PlayerHubUpdatesApi {
    private static volatile Context runtime;
    private record Context(PlayerStatsStore stats, Predicate<UUID> admitted, BooleanSupplier supported,
                           Map<UUID, Long> requests) {}
    private static final Map<String, Set<String>> FIELDS = Map.of(
        "snapshot", Set.of(), "packs", Set.of("page"), "list", Set.of("packId", "page", "search"),
        "read", Set.of("id", "commentsPage", "replyPages"), "like", Set.of("id", "liked"),
        "comment", Set.of("id", "text", "operationId"), "deleteComment", Set.of("id", "commentId"));

    public static int protocolVersion() { return 1; }
    public static void attach(PlayerStatsStore stats, Predicate<UUID> admitted, BooleanSupplier supported) {
        if (stats != null) {
            ManagerBridgeApi.register("playerhub", "PlayerHub", "1", "玩家名片,历史存档,更新日志,玩家排行,Hub设置");
            ManagerBridgeApi.registerStorage("playerhub", "{\"version\":1,\"name\":\"PlayerHub\",\"scope\":\"global\",\"fields\":[],\"tables\":[],\"uiCapabilities\":[\"更新日志\",\"玩家排行\",\"Hub设置\"]}");
        }
        runtime = stats == null ? null : new Context(stats, admitted, supported, new HashMap<>());
    }
    public static void logout(UUID player) {
        Context context=runtime;
        if(context!=null)synchronized(context.requests){context.requests.remove(player);}
    }
    public static CompletableFuture<String> request(UUID player,String action,String payload) {
        final JsonObject query;
        try { query=validate(action,payload); }
        catch(RuntimeException error){return failure("更新日志请求参数无效");}
        Context context=runtime;
        if(player==null||context==null||!context.admitted.test(player))return failure("玩家会话已失效，请重新连接");
        if(!context.supported.getAsBoolean())return failure("更新日志服务暂不可用，请确认 Manager 网页服务已更新并连接");
        PlayerStatsStore.HubSnapshot expected=context.stats.hubSnapshot(player,System.currentTimeMillis());
        if(expected==null)return failure("玩家会话已失效，请重新连接");
        long now=System.nanoTime();
        synchronized(context.requests){
            Long last=context.requests.get(player);
            if(last!=null&&now-last<100_000_000L)return failure("操作过于频繁，请稍后重试");
            context.requests.put(player,now);
        }
        JsonArray args=new JsonArray();args.add(player.toString());args.add(action);args.add(query);
        JsonObject data=new JsonObject();data.add("args",args);
        return ManagerBridgeApi.call("playerhub","playerHub",data.toString()).thenApply(raw->{
            requireSession(context,player,expected);
            if(raw==null||raw.length()>2097152||raw.getBytes(StandardCharsets.UTF_8).length>2097152)
                throw new IllegalStateException("更新内容过大，请缩小查询范围");
            JsonObject result=JsonParser.parseString(raw).getAsJsonObject();
            if(result.has("error"))throw new IllegalStateException(safeMessage(result.get("error")));
            if(!result.has("result")||!result.get("result").isJsonObject())
                throw new IllegalStateException("更新日志服务响应无效");
            return result.getAsJsonObject("result").toString();
        }).orTimeout(7,TimeUnit.SECONDS).exceptionallyCompose(error->{
            Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof IllegalStateException&&cause.getMessage()!=null&&cause.getMessage().matches(".*[\\p{IsHan}].*"))
                return failure(cause.getMessage());
            return failure(cause instanceof TimeoutException?"更新日志请求超时，请稍后重试":"更新日志服务暂不可用，请稍后重试");
        });
    }
    private static void requireSession(Context context,UUID player,PlayerStatsStore.HubSnapshot expected){
        PlayerStatsStore.HubSnapshot actual=context.stats.hubSnapshot(player,System.currentTimeMillis());
        if(runtime!=context||!context.admitted.test(player)||actual==null||expected.session()!=actual.session())
            throw new IllegalStateException("玩家会话已失效，请重新连接");
    }
    private static String safeMessage(JsonElement value){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return "更新日志操作未完成";
        String message=value.getAsString();
        return message.length()<=160&&!message.contains("\n")&&!message.contains("jdbc:")&&!message.contains("Exception")&&message.matches(".*[\\p{IsHan}].*")
            ?message:"更新日志操作未完成";
    }
    static JsonObject validate(String action,String json){
        Set<String> fields=FIELDS.get(action);
        if(fields==null||json==null||json.length()>8192||json.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException();
        int depth=0;boolean quoted=false,escape=false;
        for(int i=0;i<json.length();i++){char ch=json.charAt(i);if(quoted){if(escape)escape=false;else if(ch=='\\')escape=true;else if(ch=='"')quoted=false;}else if(ch=='"')quoted=true;else if(ch=='{'||ch=='['){if(++depth>16)throw new IllegalArgumentException();}else if(ch=='}'||ch==']')depth--;}
        JsonObject payload=JsonParser.parseString(json).getAsJsonObject();
        if(!fields.containsAll(payload.keySet()))throw new IllegalArgumentException();
        if(!action.equals("snapshot")&&!action.equals("packs")&&!action.equals("list"))id(payload,"id",false);
        switch(action){
            case "packs"->number(payload,"page",0,100000);
            case "list"->{if(payload.has("packId"))id(payload,"packId",true);number(payload,"page",0,100000);if(payload.has("search"))text(payload,"search",128,true);}
            case "read"->{number(payload,"commentsPage",0,100000);if(payload.has("replyPages")){JsonObject pages=payload.getAsJsonObject("replyPages");if(pages.size()>12)throw new IllegalArgumentException();for(var entry:pages.entrySet()){canonicalUuid(entry.getKey());number(pages,entry.getKey(),0,100000);}}}
            case "like"->{JsonElement liked=payload.get("liked");if(liked==null||!liked.isJsonPrimitive()||!liked.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException();}
            case "comment"->{text(payload,"text",2000,false);id(payload,"operationId",false);}
            case "deleteComment"->id(payload,"commentId",false);
            default->{}
        }
        return payload;
    }
    private static void id(JsonObject payload,String key,boolean blank){String value=text(payload,key,36,blank);if(!blank||!value.isEmpty())canonicalUuid(value);}
    private static void canonicalUuid(String value){if(!UUID.fromString(value).toString().equalsIgnoreCase(value))throw new IllegalArgumentException();}
    private static String text(JsonObject payload,String key,int maximum,boolean blank){JsonElement raw=payload.get(key);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString())throw new IllegalArgumentException();String value=raw.getAsString();if(value.length()>maximum||!blank&&value.isBlank())throw new IllegalArgumentException();return value;}
    private static void number(JsonObject payload,String key,int min,int max){if(!payload.has(key))return;JsonElement value=payload.get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();var number=value.getAsBigDecimal();if(number.stripTrailingZeros().scale()>0||number.compareTo(java.math.BigDecimal.valueOf(min))<0||number.compareTo(java.math.BigDecimal.valueOf(max))>0)throw new IllegalArgumentException();}
    private static CompletableFuture<String> failure(String message){return CompletableFuture.failedFuture(new IllegalStateException(message));}
    private PlayerHubUpdatesApi(){}
}
