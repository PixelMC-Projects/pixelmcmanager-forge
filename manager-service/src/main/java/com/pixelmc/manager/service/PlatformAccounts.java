package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.JSON;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Console-created accounts. Never stores passwords or grants Minecraft administrator rights. */
public final class PlatformAccounts {
  public record Account(String username, UUID player, String salt, String hash, int iterations, long version) {}
  private record FileData(int format,List<Account> accounts) {}
  private final Path path;
  private final Map<String,Account> accounts=new HashMap<>();
  private static final SecureRandom RANDOM=new SecureRandom();
  private static final int ITERATIONS=600000;
  public PlatformAccounts(Path folder) throws Exception {
    path=folder.resolve("web-accounts.json");
    if(Files.exists(path)){
      FileData file=JSON.fromJson(Files.readString(path),FileData.class);
      if(file==null||file.format()!=1||file.accounts()==null)throw new IllegalArgumentException("网页账号文件无效");
      for(Account a:file.accounts()){
        if(a.player()==null||a.iterations()<100000||a.iterations()>2000000||Base64.getDecoder().decode(a.salt()).length!=24||Base64.getDecoder().decode(a.hash()).length!=32||accounts.putIfAbsent(normalize(a.username()),a)!=null)throw new IllegalArgumentException("网页账号记录无效");
      }
    }
  }
  public static String normalize(String value){
    if(value==null||!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,31}"))throw new IllegalArgumentException("用户名须为3–32位英文、数字、_.-");
    return value.toLowerCase(Locale.ROOT);
  }
  private static String derive(String password,byte[] salt,int iterations)throws Exception{
    char[] chars=password.toCharArray();PBEKeySpec spec=new PBEKeySpec(chars,salt,iterations,256);
    try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
    finally{spec.clearPassword();Arrays.fill(chars,'\0');}
  }
  public synchronized Account get(String name){return accounts.get(name);}
  public Account verify(String username,String password)throws Exception{
    Account a;
    try{a=get(normalize(username));}catch(IllegalArgumentException e){a=null;}
    byte[] salt=a==null?new byte[24]:Base64.getDecoder().decode(a.salt());
    String actual=derive(password,salt,a==null?ITERATIONS:a.iterations());
    if(a==null||!MessageDigest.isEqual(Base64.getDecoder().decode(actual),Base64.getDecoder().decode(a.hash())))return null;
    return a;
  }
  public synchronized void create(String username,UUID player,String password)throws Exception{
    String name=normalize(username);if(accounts.containsKey(name))throw new IllegalArgumentException("用户名已存在；重置请使用 web-password");
    store(name,player,password,1);
  }
  public synchronized void password(String username,String password)throws Exception{
    Account old=accounts.get(normalize(username));if(old==null)throw new IllegalArgumentException("账号不存在");
    store(old.username(),old.player(),password,old.version()+1);
  }
  public synchronized boolean changePassword(String username,String current,String password)throws Exception{
    if(verify(username,current)==null)return false;
    password(username,password);return true;
  }
  private void store(String name,UUID player,String password,long version)throws Exception{
    if(password==null||password.length()<12||password.length()>128)throw new IllegalArgumentException("密码须为12–128个字符");
    byte[] salt=new byte[24];RANDOM.nextBytes(salt);
    Account before=accounts.put(name,new Account(name,player,Base64.getEncoder().encodeToString(salt),derive(password,salt,ITERATIONS),ITERATIONS,version));
    try{save();}catch(Exception e){if(before==null)accounts.remove(name);else accounts.put(name,before);throw e;}
  }
  public synchronized void delete(String username)throws Exception{
    String name=normalize(username);Account old=accounts.remove(name);if(old==null)throw new IllegalArgumentException("账号不存在");
    try{save();}catch(Exception e){accounts.put(name,old);throw e;}
  }
  public synchronized List<String> list(){return accounts.values().stream().sorted(Comparator.comparing(Account::username)).map(a->a.username()+" → "+a.player()).toList();}
  private void save()throws Exception{
    Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");
    Files.writeString(temp,JSON.toJson(new FileData(1,List.copyOf(accounts.values()))));
    try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
    try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
  }
  public static String randomPassword(){byte[] bytes=new byte[18];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
