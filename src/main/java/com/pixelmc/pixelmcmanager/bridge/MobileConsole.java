package com.pixelmc.pixelmcmanager.bridge;

import com.google.gson.*;
import java.util.*;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Only explicit gameplay events and command responses enter this bounded journal. */
public final class MobileConsole {
    private final ArrayDeque<JsonObject> events=new ArrayDeque<>();
    private long sequence;
    public synchronized void add(String kind,String text){
        if(!Set.of("join","leave","chat","command","result").contains(kind))return;
        JsonObject value=new JsonObject();value.addProperty("id",++sequence);
        value.addProperty("at",System.currentTimeMillis());value.addProperty("kind",kind);
        value.addProperty("text",text.length()>2048?text.substring(0,2048)+"…":text);
        events.add(value);while(events.size()>200)events.remove();
    }
    public synchronized JsonArray snapshot(){JsonArray out=new JsonArray();for(JsonObject e:events)out.add(e.deepCopy());return out;}
    public String execute(MinecraftServer server,String input){
        String command=input.strip();if(command.startsWith("/"))command=command.substring(1);
        if(command.isBlank()||command.length()>1024||command.indexOf('\n')>=0||command.indexOf('\r')>=0)
            throw new IllegalArgumentException("请输入1至1024字的单行命令");
        String root=command.split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        // Account/password commands must never be copied into the mobile journal.
        if(Set.of("login","register","changepassword","web-password","web-create","auth").contains(root)
            || command.matches("(?i).*\\b(web-password|web-create)\\b.*"))
            throw new IllegalArgumentException("凭据操作请使用服务器本地控制台");
        add("command",command);
        List<String> output=new ArrayList<>();
        CommandSource sink=new CommandSource(){
            public void sendSystemMessage(Component message){String line=message.getString();if(output.size()<32){output.add(line);add("result",line);}}
            public boolean acceptsSuccess(){return true;}
            public boolean acceptsFailure(){return true;}
            public boolean shouldInformAdmins(){return false;}
        };
        CommandSourceStack source=server.createCommandSourceStack().withSource(sink);
        boolean[] success={false};server.getCommands().performPrefixedCommand(source.withCallback((ok,value)->success[0]=ok),command);
        if(output.isEmpty()){String line=success[0]?"命令已执行（无文本输出）":"命令未成功执行";output.add(line);add("result",line);}
        String result=String.join("\n",output);
        if(!success[0])throw new IllegalStateException(result.length()>4000?result.substring(0,4000):result);
        return result.length()>4000?result.substring(0,4000):result;
    }
}
