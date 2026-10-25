package com.pixelmc.manager.service;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Wire {
  public static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
  public static JsonObject body(HttpExchange e) throws Exception {
    byte[] bytes=e.getRequestBody().readNBytes(8*1024*1024+1);
    if(bytes.length>8*1024*1024)throw new IllegalArgumentException("请求超过8MiB");
    String raw=new String(bytes,StandardCharsets.UTF_8);int depth=0;boolean string=false,escape=false;
    for(char c:raw.toCharArray()){if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>32)throw new IllegalArgumentException("JSON嵌套过深");}else if(c=='}'||c==']')depth--;}
    return JsonParser.parseString(raw).getAsJsonObject();
  }
  public static String text(JsonObject o,String key,int max){String s=o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";if(s.length()>max||s.indexOf('\0')>=0)throw new IllegalArgumentException(key+"长度无效");return s;}
  public static long number(JsonObject o,String key,long fallback,long min,long max){if(!o.has(key)||o.get(key).isJsonNull())return fallback;String s=o.get(key).getAsString();if(!s.matches("-?\\d+"))throw new IllegalArgumentException(key+"须为整数");long n=Long.parseLong(s);if(n<min||n>max)throw new IllegalArgumentException(key+"超出范围");return n;}
  public static boolean bool(JsonObject o,String key,boolean fallback){return o.has(key)?o.get(key).getAsBoolean():fallback;}
  public static JsonObject object(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonObject()?o.getAsJsonObject(key):new JsonObject();}
  public static JsonArray array(JsonObject o,String key,int max){JsonArray a=o.has(key)&&o.get(key).isJsonArray()?o.getAsJsonArray(key):new JsonArray();if(a.size()>max)throw new IllegalArgumentException(key+"数量超过安全上限");return a;}
  public static String id(String s){if(s==null||!s.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("标识无效");return s;}
  public static String uuid(String s){return UUID.fromString(s).toString();}
  public static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  public static Map<String,String> query(HttpExchange e){Map<String,String> out=new HashMap<>();for(String pair:Objects.toString(e.getRequestURI().getRawQuery(),"").split("&")){if(pair.isEmpty())continue;String[] p=pair.split("=",2);out.put(java.net.URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length<2?"":java.net.URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return out;}
  public static int integer(Map<String,String> q,String key,int value,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(q.getOrDefault(key,""+value))));}catch(Exception e){throw new IllegalArgumentException(key+"参数无效");}}
  public static void json(HttpExchange e,int status,Object o)throws Exception{byte[] b=JSON.toJson(o).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);}
  private Wire(){}
}
