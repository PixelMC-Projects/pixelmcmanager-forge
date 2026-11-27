package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.Wire.*;
import static com.pixelmc.manager.service.ManagerStore.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.web.WebConfig;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** Real HTTP/SQL contracts, using only a database process owned by the isolated launcher. */
public final class PlayerHubUpdatesChecks {
  private static int checks;
    private static final String INSTANCE = "updates-check",
            TOKEN = UUID.randomUUID() + ":" + UUID.randomUUID();
  private static final HttpClient HTTP=HttpClient.newHttpClient();
  private static final UUID PLAYER=UUID.randomUUID(),OTHER=UUID.randomUUID(),OWNER=UUID.randomUUID();
  private static HttpClient admin;
  private static String csrf,url;
  private static void check(boolean pass,String reason){if(!pass)throw new AssertionError(reason);checks++;}
  private static JsonObject obj(Object...pairs){JsonObject o=new JsonObject();for(int i=0;i<pairs.length;i+=2)o.add(pairs[i].toString(),JSON.toJsonTree(pairs[i+1]));return o;}
  private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
  private static HttpResponse<String> request(HttpClient client,String path,JsonObject body,String token)throws Exception{
    HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(20));
    if(body!=null){
            b.header("Origin", url).header("Content-Type", "application/json");
            if (token != null) b.header("X-CSRF-Token", token);
            b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));}
    return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static HttpResponse<String> bearerRequest(
          HttpClient client,
          String path,
          JsonObject body,
          String secret,
          Map<String, String> extraHeaders)
          throws Exception {
    HttpRequest.Builder b =
            HttpRequest.newBuilder(URI.create(url + path))
                    .timeout(java.time.Duration.ofSeconds(20));
    if (secret != null) {
        b.header("Authorization", "Bearer " + secret);
    }
    if (extraHeaders != null) {
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            b.header(header.getKey(), header.getValue());
        }
    }
    if (body != null) {
        b.header("Content-Type", "application/json");
        b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
  private static JsonObject get(String path)throws Exception{HttpResponse<String> r=request(admin,path,null,null);
        check(r.statusCode() == 200, "Admin GET " + path);
        return json(r.body());}
  private static JsonObject post(String path,JsonObject value)throws Exception{
        HttpResponse<String> r = request(admin, "/api/playerhub" + path, value, csrf);
        check(
                r.statusCode() == 200,
                "Admin POST " + path + " status=" + r.statusCode() + " error=" + r.body());
        return json(r.body());}
  private static JsonObject bridge(String instance,String path,JsonObject value)throws Exception{
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url + path))
                        .timeout(java.time.Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("X-PixelMC-Instance", instance)
                        .header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString(value.toString()))
                        .build();
    HttpResponse<String> r=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200, "Authenticated bridge business envelope");
        return json(r.body());
  }
  private static JsonObject rpc(UUID player,String action,JsonObject payload)throws Exception{
        return bridge(
                INSTANCE,
                "/bridge/rpc",
                obj(
                        "extension",
                        "playerhub",
                        "op",
                        "playerHub",
                        "playerProof",
                        player.toString(),
                        "data",
                        obj("args", List.of(player.toString(), action, payload))));
    }
  private static JsonObject result(UUID player,String action,JsonObject payload)throws Exception{JsonObject response=rpc(player,action,payload);
        check(
                !response.has("error"),
                "Player " + action + " safe business success: " + response.get("error"));
        check(!response.has("snapshot"), "No administrative snapshot attached");
        return response.getAsJsonObject("result");
    }
  private static JsonObject entry(String id)throws Exception{
        return get("/api/playerhub/entry?id=" + id).getAsJsonObject("entry");
    }
  private static JsonObject save(String pack,String title,boolean publish,boolean pin)throws Exception{
        return post(
                        "/entry/save",
                        obj(
                                "packId",
                                pack,
                                "packName",
                                "验收包",
                                "packVersion",
                                "1.0",
                                "updateVersion",
                                "0.1",
                                "title",
                                title,
                                "summary",
                                "隔离验收摘要",
                                "category",
                                "功能更新",
                                "body",
                                "旧正文 **加粗**",
                                "pinned",
                                pin,
                                "publish",
                                publish,
                                "expectedRevision",
                                0))
                .getAsJsonObject("entry");
    }
  private static JsonObject settings(JsonObject current,boolean updates,boolean likes,boolean comments,int limit,int cooldown){
        JsonObject next =
                obj(
                        "updatesEnabled",
                        updates,
                        "likesEnabled",
                        likes,
                        "commentsEnabled",
                        comments,
                        "commentLimit",
                        limit,
                        "commentCooldown",
                        cooldown,
                        "expectedRevision",
                        current.get("revision"));
        return next;}
  private static JsonObject ownRead(String id)throws Exception{
        return result(PLAYER, "read", obj("id", id));
    }
  private static void denied(RunnableChecked action,String reason)throws Exception{boolean denied=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){denied=true;}check(denied,reason);}
  private interface RunnableChecked{void run()throws Exception;}
  private static JsonObject archive(ManagerController c,String world,String name,String version)throws Exception{
        HttpResponse<String> r =
                request(
                        admin,
                        "/api/manager/archives",
                        obj(
                                "instanceId",
                                INSTANCE,
                                "worldId",
                                world,
                                "name",
                                name,
                                "version",
                                version,
                                "startedOn",
                                "2026-11-05",
                                "active",
                                true),
                        csrf);
        check(r.statusCode() == 200, "Create pack through real authenticated Manager route");
        return json(r.body());
  }
  private static void run(ManagerController c,Path folder,String restoredSchema,Database.Config restoredDatabase)throws Exception{
        c.web =
                new PlatformWebServer(
                        c,
                        new WebConfig(true, "127.0.0.1", c.config.port(), url, 480, List.of()),
                        folder.resolve("platform"));
        String username = "updatesvisual", password = "Isolated-" + UUID.randomUUID() + "!";
        c.web.accounts.create(username,OWNER,password);
    admin=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> login =
                request(admin, "/api/login", obj("username", username, "password", password), null);
        check(login.statusCode() == 200, "Dedicated test administrator logs in");
        csrf = json(login.body()).get("csrf").getAsString();
        check(
                request(HTTP, "/api/playerhub/state", null, null).statusCode() == 401,
                "Anonymous admin access denied");
        check(
                request(admin, "/api/playerhub/settings", obj(), null).statusCode() == 403,
                "Existing admin CSRF authority reused");
    c.store.register(INSTANCE,TOKEN);c.store.register("other-instance",TOKEN);
    String run=UUID.randomUUID().toString(),world=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        JsonObject hello =
                bridge(
                        INSTANCE,
                        "/bridge/hello",
                        obj(
                                "runId",
                                run,
                                "worldId",
                                world,
                                "startedAt",
                                now - 1000,
                                "info",
                                obj(
                                        "extensions",
                                        List.of(
                                                obj(
                                                        "id",
                                                        "playerhub",
                                                        "storage",
                                                        obj(
                                                                "version", 1, "tables", List.of(),
                                                                "fields", List.of()))))));
        check(
                hello.get("playerHubApi").getAsInt() == 1 && hello.has("playerHubRevision"),
                "Capability and boot revision piggyback hello");
    String archive=archive(c,world,"完整整合包名称 · 副标题","1.0").get("id").getAsString();
    List<JsonObject> profiles=new ArrayList<>(),logins=new ArrayList<>();for(UUID p:List.of(PLAYER,OTHER)){
            String name = p.equals(PLAYER) ? "LocalPlayer" : "OtherPlayer";
            profiles.add(
                    obj(
                            "id",
                            p,
                            "name",
                            name,
                            "joinCount",
                            1,
                            "totalOnlineMillis",
                            1000,
                            "firstJoinEpochMillis",
                            now,
                            "lastJoinEpochMillis",
                            now));
            logins.add(
                    obj(
                            "id",
                            UUID.randomUUID(),
                            "sessionId",
                            UUID.randomUUID(),
                            "uuid",
                            p,
                            "name",
                            name,
                            "at",
                            now));
        }
        JsonObject frame =
                obj(
                        "runId",
                        run,
                        "archiveId",
                        archive,
                        "seq",
                        1,
                        "at",
                        now,
                        "snapshot",
                        obj(),
                        "players",
                        profiles,
                        "stats",
                        profiles,
                        "logins",
                        logins,
                        "acks",
                        List.of(),
                        "longTicks",
                        List.of());
        JsonObject ack = bridge(INSTANCE, "/bridge/snapshot", frame);
        check(
                ack.has("playerHubRevision"),
                "Existing snapshot carries revision without new poller");
        JsonObject state = get("/api/playerhub/state"), prefs = state.getAsJsonObject("settings");
        String pack = state.get("selectedPackId").getAsString();
        check(
                state.getAsJsonArray("packs").size() == 1,
                "Manager pack metadata creates native stable group");
        check(
                prefs.get("commentCooldown").getAsInt() == 10
                        && state.getAsJsonArray("packs")
                                .get(0)
                                .getAsJsonObject()
                                .get("shortName")
                                .getAsString()
                                .equals("完整整合包名称"),
                "Approved cooldown and short pack label defaults");
        JsonObject boot = get("/api/manager/bootstrap");
        check(boot.has("playerHubRevision"), "Admin bootstrap and SSE share revision token");
    JsonObject module=null;
        for (JsonElement raw : boot.getAsJsonArray("extensions"))
            if (text(raw.getAsJsonObject(), "id", 64).equals("playerhub"))
                module = raw.getAsJsonObject();
        check(
                module != null
                        && module.getAsJsonArray("tables").size()
                                == PlayerHubUpdatesStore.TABLES.size()
                        && module.get("enabled").getAsBoolean(),
                "Game metadata cannot replace native SQL manifest");
        check(
                c.platform.durableTables().containsAll(PlayerHubUpdatesStore.TABLES),
                "All changelog tables join complete-platform backup");
    check(rpc(UUID.randomUUID(),"snapshot",obj()).has("error"),"Unadmitted identity rejected");
        check(
                rpc(PLAYER, "deleteEntry", obj()).has("error"),
                "No administrator operations exposed to players");
        JsonObject cross =
                obj(
                        "extension",
                        "playerhub",
                        "op",
                        "playerHub",
                        "playerProof",
                        PLAYER.toString(),
                        "data",
                        obj("args", List.of(PLAYER.toString(), "snapshot", obj())));
        check(
                bridge("other-instance", "/bridge/rpc", cross).has("error"),
                "Proof bound to admitted instance");
        cross.addProperty("playerProof", OTHER.toString());
        check(
                bridge(INSTANCE, "/bridge/rpc", cross).has("error"),
                "Forged proof cannot select another player");
        JsonObject first = save(pack, "AAA 已发布日志", true, false),
                pinned = save(pack, "ZZZ 置顶日志", true, true),
                draft = save(pack, "草稿日志", false, false);
        String id = first.get("id").getAsString(),
                pinnedId = pinned.get("id").getAsString(),
                draftId = draft.get("id").getAsString();
    check(draft.get("publishedAt").isJsonNull(),"Unpublished timestamp retains JSON null");
        JsonObject list =
                get("/api/playerhub/entries?packId=" + pack + "&sort=title&direction=asc&size=1");
        check(
                list.getAsJsonArray("rows")
                                .get(0)
                                .getAsJsonObject()
                                .get("id")
                                .getAsString()
                                .equals(pinnedId)
                        && list.get("total").getAsInt() == 3,
                "Pins first even when title sorting and server paging");
        check(
                list.getAsJsonObject("counts").get("draft").getAsInt() == 1,
                "Tab counts computed beyond current page");
        check(
                get("/api/playerhub/entries?packId=" + pack + "&search=ZZZ")
                                .getAsJsonObject("counts")
                                .get("all")
                                .getAsInt()
                        == 3,
                "Category counts stay pack-scoped independently of search");
        JsonObject publishedList = result(PLAYER, "list", obj("packId", pack));
        check(
                publishedList.get("total").getAsInt() == 2
                        && !publishedList.toString().contains(draftId),
                "Player pages contain only published summaries");
        check(
                !publishedList.getAsJsonArray("rows").get(0).getAsJsonObject().has("body"),
                "Summary preload omits full article and interactions");
        check(
                publishedList.get("latestId").getAsString().equals(pinnedId),
                "Player latest marker follows unfiltered default ordering");
        JsonObject searchedOld = result(PLAYER, "list", obj("packId", pack, "search", "AAA"));
        check(
                searchedOld.get("total").getAsInt() == 1
                        && searchedOld
                                .getAsJsonArray("rows")
                                .get(0)
                                .getAsJsonObject()
                                .get("id")
                                .getAsString()
                                .equals(id)
                        && searchedOld.get("latestId").getAsString().equals(pinnedId),
                "Searching an older update never reassigns the latest marker");
        check(
                searchedOld.getAsJsonObject("counts").get("published").getAsInt() == 2
                        && searchedOld.getAsJsonObject("counts").get("all").getAsInt() == 2,
                "Player header counts stay unfiltered while search pagination total changes");
        check(
                result(PLAYER, "snapshot", obj())
                        .getAsJsonObject("list")
                        .get("latestId")
                        .getAsString()
                        .equals(pinnedId),
                "Initial snapshot list uses the same latest marker");
        try (Connection db = c.store.open()) {
            update(
                    db,
                    "UPDATE ph_entries SET published_at=? WHERE id=?",
                    pinned.get("publishedAt").getAsLong() + 1,
                    id);
        }
        try {
            JsonObject snapshot = result(PLAYER, "snapshot", obj());
            JsonArray notices = snapshot.getAsJsonArray("stationNotices");
            check(
                    notices.size() == 2
                            && notices.get(0).getAsJsonObject().get("id").getAsString().equals(id)
                            && notices.get(1)
                                    .getAsJsonObject()
                                    .get("id")
                                    .getAsString()
                                    .equals(pinnedId),
                    "Station feed orders published current-pack articles by time, independent of"
                        + " pin priority");
            check(
                    notices.get(0)
                            .getAsJsonObject()
                            .keySet()
                            .equals(
                                    Set.of(
                                            "id",
                                            "packId",
                                            "packName",
                                            "packVersion",
                                            "updateVersion",
                                            "title",
                                            "summary",
                                            "category",
                                            "publishedAt")),
                    "Station feed contains only public summary fields");
        } finally {
            try (Connection db = c.store.open()) {
                update(
                        db,
                        "UPDATE ph_entries SET published_at=? WHERE id=?",
                        first.get("publishedAt").getAsLong(),
                        id);
            }
        }
    JsonObject inbox = result(PLAYER, "snapshot", obj()).getAsJsonObject("list");
    long publication = inbox.get("publication").getAsLong();
        check(
                publication > inbox.get("seenThrough").getAsLong(),
                "Initial directory snapshot reports new publications");
    String beforeVisit = c.updates.revision();
    result(PLAYER, "markSectionSeen", obj("publication", publication));
        check(
                result(PLAYER, "snapshot", obj())
                                .getAsJsonObject("list")
                                .get("seenThrough")
                                .getAsLong()
                        == publication,
                "Section visit persists across snapshots");
        check(
                ownRead(id).getAsJsonObject("entry").get("unread").getAsBoolean(),
                "Section visit and article preload leave article unread");
    result(PLAYER, "markEntrySeen", obj("id", id, "publication", first.get("publishedAt")));
        check(
                !ownRead(id).getAsJsonObject("entry").get("unread").getAsBoolean(),
                "Opening this edition marks just this article");
        check(
                ownRead(pinnedId).getAsJsonObject("entry").get("unread").getAsBoolean(),
                "Other articles remain unread");
        check(
                result(OTHER, "snapshot", obj())
                                .getAsJsonObject("list")
                                .get("seenThrough")
                                .getAsLong()
                        == 0,
                "Another player's directory state is independent");
        check(
                result(OTHER, "read", obj("id", id))
                        .getAsJsonObject("entry")
                        .get("unread")
                        .getAsBoolean(),
                "Another player's article state is independent");
        check(
                rpc(PLAYER, "markEntrySeen", obj("id", id, "publication", publication + 1))
                        .has("error"),
                "Mismatched article edition rejected");
        check(
                beforeVisit.equals(c.updates.revision()),
                "Personal receipts never invalidate all subscribers");
    check(rpc(PLAYER,"read",obj("id",draftId)).has("error"),"Draft ID guessing denied");
        JsonObject doc =
                json(
                        "{type:'doc',content:[{type:'heading',attrs:{level:3},content:[{type:'text',text:'三级标题'}]},{type:'paragraph',content:[{type:'text',text:'加粗',marks:[{type:'bold'}]},{type:'hardBreak'},{type:'text',text:'换行'}]},{type:'bulletList',content:[{type:'listItem',content:[{type:'paragraph',content:[{type:'text',text:'列表'}]}]}]}]}");
    JsonObject edit=first.deepCopy();
        edit.add("bodyDocument", doc);
        edit.addProperty("expectedRevision", first.get("revision").getAsLong());
        edit.addProperty("publish", false);
        first = post("/entry/save", edit).getAsJsonObject("entry");
        check(
                first.get("bodyDocument").equals(doc)
                        && first.get("body").getAsString().contains("加粗\n换行"),
                "Restricted document saved and canonical legacy body derived");
        check(
                first.get("publishedAt").equals(edit.get("publishedAt")),
                "Published edit preserves original publication time");
        check(
                request(admin, "/api/playerhub/entry/save", edit, csrf).statusCode() == 409,
                "Stale admin revisions cannot overwrite");
    JsonObject invalid=first.deepCopy();
        invalid.addProperty("expectedRevision", first.get("revision").getAsLong());
        invalid.addProperty("publish", false);
        invalid.add(
                "bodyDocument",
                json("{type:'doc',content:[{type:'image',attrs:{src:'javascript:alert(1)'}}]}"));
        check(
                request(admin, "/api/playerhub/entry/save", invalid, csrf).statusCode() == 400,
                "Arbitrary document nodes and HTML attributes refused");
    String revision=c.updates.revision();
        result(PLAYER, "like", obj("id", id, "liked", true));
        String likedRevision=c.updates.revision();
        check(
                !likedRevision.equals(revision),
                "Successful mutation advances notification revision");
        result(PLAYER, "like", obj("id", id, "liked", true));
        check(
                c.updates.revision().equals(likedRevision),
                "Repeated desired like is idempotent and sends no false change");
        JsonObject likes = get("/api/playerhub/entry?id=" + id).getAsJsonObject("likes");
        check(
                likes.get("total").getAsInt() == 1
                        && likes.getAsJsonArray("rows")
                                        .get(0)
                                        .getAsJsonObject()
                                        .get("createdAt")
                                        .getAsLong()
                                > 0,
                "One durable like with exact timestamp available to admin");
        check(!ownRead(id).has("likes"), "Player response never exposes liker directory");
        result(PLAYER, "like", obj("id", id, "liked", false));
        check(
                ownRead(id).getAsJsonObject("entry").get("likesCount").getAsInt() == 0,
                "Explicit unlike removes only caller's like");
        check(
                rpc(PLAYER, "like", obj("id", id, "liked", "true")).has("error"),
                "Boolean strings cannot toggle likes");
    prefs=post("/settings",settings(prefs,true,true,true,500,0)).getAsJsonObject("settings");
    String operation=UUID.randomUUID().toString();
        JsonObject commentPayload = obj("id", id, "text", "仅我和管理员可见的留言", "operationId", operation);
        JsonObject thread =
                result(PLAYER, "comment", commentPayload)
                        .getAsJsonObject("comments")
                        .getAsJsonArray("rows")
                        .get(0)
                        .getAsJsonObject();
        String commentId = thread.get("id").getAsString();
        String commentRevision=c.updates.revision();
        result(PLAYER, "comment", commentPayload);
        check(
                c.updates.revision().equals(commentRevision)
                        && ownRead(id).getAsJsonObject("comments").get("total").getAsInt() == 1,
                "Comment retry persists exactly once");
    JsonObject changed=commentPayload.deepCopy();
        changed.addProperty("text", "different");
        check(
                rpc(PLAYER, "comment", changed).has("error"),
                "Operation ID reuse with changed payload rejected");
        JsonObject otherThread =
                result(
                                OTHER,
                                "comment",
                                obj(
                                        "id",
                                        id,
                                        "text",
                                        "另一个人的私密留言",
                                        "operationId",
                                        UUID.randomUUID()))
                        .getAsJsonObject("comments")
                        .getAsJsonArray("rows")
                        .get(0)
                        .getAsJsonObject();
        String otherComment = otherThread.get("id").getAsString();
    JsonObject privateRead=ownRead(id);
        check(
                privateRead.getAsJsonObject("comments").get("total").getAsInt() == 1
                        && !privateRead.toString().contains(otherComment)
                        && privateRead.getAsJsonObject("entry").get("commentsCount").getAsInt()
                                == 1,
                "Private DTO and counts do not reveal another player's threads");
        check(
                rpc(PLAYER, "deleteComment", obj("id", id, "commentId", otherComment)).has("error"),
                "Players cannot delete other threads by ID");
        JsonObject replyPayload =
                obj(
                        "id",
                        id,
                        "commentId",
                        commentId,
                        "text",
                        "管理员私密回复",
                        "operationId",
                        UUID.randomUUID());
        post("/comment/reply", replyPayload);
        post("/comment/reply", replyPayload);
        check(
                ownRead(id)
                                .getAsJsonObject("comments")
                                .getAsJsonArray("rows")
                                .get(0)
                                .getAsJsonObject()
                                .get("replyCount")
                                .getAsInt()
                        == 1,
                "Admin reply idempotency and author-only delivery");
        check(
                !result(OTHER, "read", obj("id", id)).toString().contains("管理员私密回复"),
                "Replies never leak to another admitted player");
        JsonObject pending =
                get("/api/playerhub/entry?id=" + id + "&commentsFilter=pending")
                        .getAsJsonObject("comments");
        check(
                pending.get("total").getAsInt() == 1
                        && pending.getAsJsonObject("counts").get("all").getAsInt() == 2,
                "Pending filter and total counts are SQL-backed");
        prefs =
                post("/settings", settings(prefs, true, true, false, 500, 0))
                        .getAsJsonObject("settings");
        check(
                rpc(
                                PLAYER,
                                "comment",
                                obj(
                                        "id",
                                        id,
                                        "text",
                                        "new disabled",
                                        "operationId",
                                        UUID.randomUUID()))
                        .has("error"),
                "Comments pause blocks new player messages");
        post(
                "/comment/reply",
                obj(
                        "id",
                        id,
                        "commentId",
                        commentId,
                        "text",
                        "暂停后管理员仍可回复",
                        "operationId",
                        UUID.randomUUID()));
        check(
                ownRead(id)
                                .getAsJsonObject("comments")
                                .getAsJsonArray("rows")
                                .get(0)
                                .getAsJsonObject()
                                .get("replyCount")
                                .getAsInt()
                        == 2,
                "Comments pause retains history and admin reply");
        prefs =
                post("/settings", settings(prefs, true, true, true, 50, 300))
                        .getAsJsonObject("settings");
        check(
                rpc(
                                PLAYER,
                                "comment",
                                obj("id", id, "text", "too fast", "operationId", UUID.randomUUID()))
                        .has("error"),
                "Global comment cooldown enforced in SQL transaction");
        check(
                rpc(
                                PLAYER,
                                "comment",
                                obj(
                                        "id",
                                        id,
                                        "text",
                                        "字".repeat(51),
                                        "operationId",
                                        UUID.randomUUID()))
                        .has("error"),
                "Configured comment length enforced");
        check(
                request(
                                        admin,
                                        "/api/playerhub/settings",
                                        settings(prefs, true, true, true, 49, 0),
                                        csrf)
                                .statusCode()
                        == 400,
                "Settings range validation server-owned");
    prefs=post("/settings",settings(prefs,true,true,true,500,0)).getAsJsonObject("settings");
        JsonObject packInfo =
                get("/api/playerhub/state").getAsJsonArray("packs").get(0).getAsJsonObject();
        post(
                "/pack/visibility",
                obj(
                        "packId",
                        pack,
                        "visible",
                        false,
                        "expectedRevision",
                        packInfo.get("revision")));
        check(
                result(PLAYER, "snapshot", obj()).getAsJsonArray("packs").isEmpty()
                        && rpc(PLAYER, "read", obj("id", id)).has("error"),
                "Current pack may be hidden without deleting history");
        check(
                entry(id).get("id").getAsString().equals(id),
                "Hidden pack always remains visible to admin");
        packInfo = get("/api/playerhub/state").getAsJsonArray("packs").get(0).getAsJsonObject();
        post(
                "/pack/visibility",
                obj("packId", pack, "visible", true, "expectedRevision", packInfo.get("revision")));
        prefs =
                post("/settings", settings(prefs, false, true, true, 500, 0))
                        .getAsJsonObject("settings");
        check(
                result(PLAYER, "snapshot", obj())
                        .getAsJsonObject("list")
                        .get("latestId")
                        .getAsString()
                        .isEmpty(),
                "Disabled snapshot retains an explicit empty latest marker");
        check(
                result(PLAYER, "snapshot", obj()).getAsJsonArray("packs").isEmpty()
                        && rpc(PLAYER, "like", obj("id", id, "liked", true)).has("error"),
                "Disabled entrance cannot be bypassed by direct player mutations");
        prefs =
                post("/settings", settings(prefs, true, true, true, 500, 0))
                        .getAsJsonObject("settings");
    first=entry(id);
        JsonObject withdraw =
                post(
                                "/entry/status",
                                obj(
                                        "id",
                                        id,
                                        "status",
                                        "withdrawn",
                                        "expectedRevision",
                                        first.get("revision")))
                        .getAsJsonObject("entry");
        check(
                rpc(PLAYER, "read", obj("id", id)).has("error"),
                "Withdrawn entries not readable by UUID");
        post(
                "/entry/status",
                obj("id", id, "status", "published", "expectedRevision", withdraw.get("revision")));
        check(
                ownRead(id).getAsJsonObject("comments").get("total").getAsInt() == 1,
                "Republishing preserves existing interactions");
    PlayerHubUpdatesStore reopened=new PlayerHubUpdatesStore(c.store);reopened.migrate();
        check(
                reopened.read(INSTANCE, PLAYER.toString(), id, 0, 0, "all", obj())
                                .getAsJsonObject("entry")
                                .get("bodyDocument")
                                .equals(doc)
                        && !reopened.revision().equals(c.updates.revision()),
                "Durable reload preserves rich document and uses new notification epoch");
    String stableRevision=c.updates.revision();c.updates.syncPacks();
        check(
                stableRevision.equals(c.updates.revision()),
                "Unchanged metadata sync does not create notifications");
    String replacementWorld=UUID.randomUUID().toString();try(Connection db=c.store.open()){
            update(
                    db,
                    "UPDATE pm_archives SET status='archived',finalized=TRUE WHERE id=?",
                    archive);
            update(
                    db,
                    "UPDATE pm_instances SET active_archive=NULL,world_id=? WHERE id=?",
                    replacementWorld,
                    INSTANCE);
        }
        String replacement =
                archive(c, replacementWorld, "完整整合包名称 · 副标题", "2.0").get("id").getAsString();
        state = get("/api/playerhub/state");
        check(
                state.getAsJsonArray("packs").size() == 1
                        && state.get("selectedPackId").getAsString().equals(pack)
                        && state.getAsJsonArray("packs")
                                .get(0)
                                .getAsJsonObject()
                                .get("version")
                                .getAsString()
                                .equals("2.0"),
                "Ordinary world archive replacement and version update keep stable group");
        check(
                entry(id).get("packVersion").getAsString().equals("1.0"),
                "Per-entry historical pack/version snapshots preserved");
        stableRevision=c.updates.revision();c.updates.syncPacks();
        check(
                stableRevision.equals(c.updates.revision()),
                "Historical versions do not churn group revision");
    try(Connection db=c.store.open()){
            update(
                    db,
                    "UPDATE pm_archives SET status='archived',finalized=TRUE WHERE id=?",
                    replacement);
            update(
                    db,
                    "UPDATE pm_instances SET active_archive=NULL,world_id=? WHERE id=?",
                    UUID.randomUUID().toString(),
                    INSTANCE);
        }String switchWorld;try(Connection db=c.store.open()){
            switchWorld =
                    rows(db, "SELECT world_id FROM pm_instances WHERE id=?", INSTANCE)
                            .get(0)
                            .get("world_id")
                            .getAsString();
        }
        archive(c, switchWorld, "真正的新整合包：食旅纪行", "3.0");
        state = get("/api/playerhub/state");
        check(
                state.getAsJsonArray("packs").size() == 2
                        && !state.get("selectedPackId").getAsString().equals(pack),
                "Different full pack name creates distinct stable group");
        check(
                state.getAsJsonArray("packs")
                        .get(0)
                        .getAsJsonObject()
                        .get("shortName")
                        .getAsString()
                        .equals("真正的新整合包"),
                "Chinese-colon subtitles removed only from display short name");
        check(
                rpc(PLAYER, "read", obj("id", id)).has("error"),
                "Switching packs hides prior group by default");
        packInfo=null;
        for (JsonElement raw : state.getAsJsonArray("packs"))
            if (text(raw.getAsJsonObject(), "id", 36).equals(pack))
                packInfo = raw.getAsJsonObject();
        post(
                "/pack/visibility",
                obj("packId", pack, "visible", true, "expectedRevision", packInfo.get("revision")));
    PlatformDeletion deletion=new PlatformDeletion(c.store,c.platform,c.web.avatars);JsonObject preview=deletion.preview(PLAYER,Set.of());
        check(
                preview.getAsJsonObject("counts").get("ph_comments").getAsInt() == 1
                        && preview.getAsJsonObject("counts").get("ph_replies").getAsInt() == 2,
                "Global deletion preview owns private changelog rows");
        String recovery =
                deletion.delete(
                        OWNER, PLAYER, preview.get("stamp").getAsString(), "LocalPlayer", Set.of());
        try(Connection db=c.store.open()){
            check(
                    rows(db, "SELECT id FROM ph_comments WHERE author_uuid=?", PLAYER.toString())
                                    .isEmpty()
                            && rows(
                                            db,
                                            "SELECT id FROM ph_replies WHERE owner_uuid=?",
                                            PLAYER.toString())
                                    .isEmpty(),
                    "Global deletion removes all owned private threads/replies");
        }deletion.restore(OWNER,recovery,Set.of());
        check(
                ownRead(id)
                                .getAsJsonObject("comments")
                                .getAsJsonArray("rows")
                                .get(0)
                                .getAsJsonObject()
                                .get("replyCount")
                                .getAsInt()
                        == 2,
                "Recovery restores original owned interactions atomically");
    UUID conflictPlayer=UUID.randomUUID();try(Connection db=c.store.open()){
            PlatformStore.identity(db, conflictPlayer.toString(), "ConflictPlayer", now, now);
            update(
                    db,
                    "INSERT INTO ph_comments(id,entry_id,author_uuid,text,created_at)"
                        + " VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    id,
                    conflictPlayer.toString(),
                    "冲突恢复隔离记录",
                    now);
        }
    JsonObject conflictPreview=deletion.preview(conflictPlayer,Set.of());
        String conflictRecovery =
                deletion.delete(
                        OWNER,
                        conflictPlayer,
                        conflictPreview.get("stamp").getAsString(),
                        "ConflictPlayer",
                        Set.of());
        JsonObject beforeConflict=entry(id);
        post(
                "/entry/pin",
                obj(
                        "id",
                        id,
                        "pinned",
                        !beforeConflict.get("pinned").getAsBoolean(),
                        "expectedRevision",
                        beforeConflict.get("revision")));
        denied(
                () -> deletion.restore(OWNER, conflictRecovery, Set.of()),
                "Changed log definitions prevent force-restoring dangling personal records");
    Path backup=c.backupAll();try(ZipFile zip=new ZipFile(backup.toFile())){JsonObject manifest;
            try (var in = zip.getInputStream(zip.getEntry("manifest.json"))) {
                manifest=json(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            for (String table : PlayerHubUpdatesStore.TABLES)
                check(
                        manifest.getAsJsonArray("tables").contains(new JsonPrimitive(table))
                                && zip.getEntry("schema/" + table + ".sql") != null
                                && zip.getEntry("database/" + table + ".json") != null,
                        "Backup includes durable schema+rows " + table);
        }
        Path restoreFolder = Files.createTempDirectory(Path.of("build"), "updates-restore-");
        ServiceConfig destination =
                new ServiceConfig(
                        restoredDatabase,
                        restoredDatabase,
                        false,
                        "127.0.0.1",
                        c.config.port(),
                        url,
                        List.of(OWNER),
                        "data",
                        restoredDatabase,
                        false,
                        "unified",
                        30);
        ManagerRestore.restore(destination,restoreFolder,backup);ManagerStore restored=new ManagerStore(restoredDatabase);PlayerHubUpdatesStore restoredUpdates=new PlayerHubUpdatesStore(restored);
        JsonObject restoredRead =
                restoredUpdates.read(INSTANCE, PLAYER.toString(), id, 0, 0, "all", obj());
        check(
                restoredRead.getAsJsonObject("entry").get("bodyDocument").equals(doc)
                        && restoredRead.getAsJsonObject("comments").get("total").getAsInt() == 1,
                "Complete-platform empty-schema restore preserves changelog and private ownership");
        ManagerRestore.complete(restoreFolder);
        denied(
                () -> ManagerRestore.restore(destination, restoreFolder, backup),
                "Populated target never overwritten");
    List<String> paginationPacks=new ArrayList<>();try(Connection db=c.store.open()){for(int i=0;i<101;i++){String extra=UUID.randomUUID().toString();paginationPacks.add(extra);
                update(
                        db,
                        "INSERT INTO"
                            + " ph_packs(id,instance_id,name,version,current_pack,visible,created_at,updated_at)"
                            + " VALUES(?,?,?,?,FALSE,TRUE,?,?)",
                        extra,
                        INSTANCE,
                        "分页隔离包 " + i,
                        "1.0",
                        now + i,
                        now);
            }}
        JsonObject packsOne = get("/api/playerhub/packs?page=0"),
                packsTwo = result(PLAYER, "packs", obj("page", 1));
        check(
                packsOne.getAsJsonArray("rows").size() == 100
                        && packsOne.get("hasMore").getAsBoolean()
                        && packsOne.get("total").getAsInt() == 103
                        && packsTwo.getAsJsonArray("rows").size() == 3
                        && !packsTwo.get("hasMore").getAsBoolean(),
                "Pack pagination never silently drops the 101st historical group");
        try(Connection db=c.store.open()){
            for (String extra : paginationPacks)
                update(db, "DELETE FROM ph_packs WHERE id=?", extra);
        }
        for (int i = 0; i < 21; i++)
            post(
                    "/comment/reply",
                    obj(
                            "id",
                            id,
                            "commentId",
                            commentId,
                            "text",
                            "分页回复 " + i,
                            "operationId",
                            UUID.randomUUID()));
        JsonObject boundedReplies =
                ownRead(id)
                        .getAsJsonObject("comments")
                        .getAsJsonArray("rows")
                        .get(0)
                        .getAsJsonObject();
        check(
                boundedReplies.getAsJsonArray("replies").size() == 20
                        && boundedReplies.get("replyCount").getAsInt() == 23
                        && boundedReplies.get("repliesHasMore").getAsBoolean(),
                "Each thread reply page remains bounded with truthful totals");
        check(
                get("/api/playerhub/replies?id=" + id + "&commentId=" + commentId + "&page=1")
                                .getAsJsonArray("rows")
                                .size()
                        == 3,
                "Older reply history remains reachable through paging");
        JsonObject wrongDelete =
                obj(
                        "id",
                        id,
                        "confirmTitle",
                        "unsaved title",
                        "expectedRevision",
                        entry(id).get("revision"));
        check(
                request(admin, "/api/playerhub/entry/delete", wrongDelete, csrf).statusCode()
                        == 409,
                "Exact persisted title required for entry deletion");
        result(PLAYER, "deleteComment", obj("id", id, "commentId", commentId));
        check(
                ownRead(id).getAsJsonObject("comments").get("total").getAsInt() == 0,
                "Author deletion removes own thread and reply tree");
        result(PLAYER, "comment", commentPayload);
        check(
                ownRead(id).getAsJsonObject("comments").get("total").getAsInt() == 0,
                "Retry after deleted thread never recreates content");
        post("/comment/delete", obj("id", id, "commentId", otherComment));
        check(
                get("/api/playerhub/entry?id=" + id)
                                .getAsJsonObject("comments")
                                .get("total")
                                .getAsInt()
                        == 0,
                "Admin can delete any thread");
        result(PLAYER, "like", obj("id", pinnedId, "liked", true));
        result(
                PLAYER,
                "comment",
                obj("id", pinnedId, "text", "删除前留言", "operationId", UUID.randomUUID()));
        JsonObject pinnedNow=entry(pinnedId);
        post(
                "/entry/delete",
                obj(
                        "id",
                        pinnedId,
                        "confirmTitle",
                        pinnedNow.get("title"),
                        "expectedRevision",
                        pinnedNow.get("revision")));
        try(Connection db=c.store.open()){
            for (String table : List.of("ph_likes", "ph_comments", "ph_replies", "ph_operations"))
                check(
                        rows(db, "SELECT entry_id FROM " + table + " WHERE entry_id=?", pinnedId)
                                .isEmpty(),
                        "Entry deletion cascades durable rows " + table);
        }
        check(rpc(PLAYER, "read", obj("id", pinnedId)).has("error"), "Deleted entry inaccessible");
        verifyLoopbackApiTokens(c, folder, state.get("selectedPackId").getAsString(), id);
    // Leave a meaningful test-only state for optional browser acceptance.
    for(int i=1;i<=14;i++)save(state.get("selectedPackId").getAsString(),"浏览验收更新 "+i,i%3!=0,i==1);
        result(PLAYER, "like", obj("id", id, "liked", true));
        JsonObject visual =
                result(
                        PLAYER,
                        "comment",
                        obj("id", id, "text", "请管理员检查这条隔离留言。", "operationId", UUID.randomUUID()));
        System.out.println("PlayerHub updates HTTP/SQL assertions complete: " + checks);
        String outputValue = System.getenv("PM_HUB_TEST_OUTPUT");
        if(outputValue!=null){Path output=Path.of(outputValue).toAbsolutePath().normalize();Files.createDirectories(output);
            Files.writeString(
                    output.resolve("safe-responses.json"),
                    obj(
                                    "snapshot",
                                    result(PLAYER, "snapshot", obj()),
                                    "read",
                                    visual,
                                    "adminState",
                                    get("/api/playerhub/state"))
                            .toString());
            if ("1".equals(System.getenv("PM_HUB_VISUAL_HOLD"))) {
                Path fixture = output.resolve("fixture.json"), stop = output.resolve("stop");
                long until=System.currentTimeMillis()+15*60_000;
                Files.writeString(
                        fixture,
                        obj(
                                        "url",
                                        url,
                                        "username",
                                        username,
                                        "password",
                                        password,
                                        "owner",
                                        OWNER,
                                        "player",
                                        PLAYER,
                                        "entryId",
                                        id,
                                        "expiresAt",
                                        until,
                                        "stopFile",
                                        stop.toString())
                                .toString());
                System.out.println("ISOLATED_UPDATES_FIXTURE_READY " + fixture);
                try{while(System.currentTimeMillis()<until&&!Files.exists(stop))Thread.sleep(200);}finally{Files.deleteIfExists(fixture);}}}
  }

  private static void verifyLoopbackApiTokens(
          ManagerController c, Path folder, String packId, String publishedEntryId)
          throws Exception {
        com.sun.net.httpserver.Headers emptyHeaders = new com.sun.net.httpserver.Headers();
        check(
                PlatformWebServer.isLoopbackAllowed(
                        InetAddress.getByName("127.0.0.1"), emptyHeaders),
                "IPv4 loopback allowed for API token");
        check(
                PlatformWebServer.isLoopbackAllowed(InetAddress.getByName("::1"), emptyHeaders),
                "IPv6 loopback allowed for API token");
        for (String nonLoopback : List.of("172.17.0.1", "10.0.0.5", "203.0.113.9")) {
            check(
                    !PlatformWebServer.isLoopbackAllowed(
                            InetAddress.getByName(nonLoopback), emptyHeaders),
                    "Non-loopback IP rejected for API token: " + nonLoopback);
        }
        for (String proxyHeader : List.of("X-Forwarded-For", "X-Real-IP", "Forwarded")) {
            com.sun.net.httpserver.Headers proxied = new com.sun.net.httpserver.Headers();
            proxied.add(proxyHeader, "198.51.100.7");
            check(
                    !PlatformWebServer.isLoopbackAllowed(
                            InetAddress.getByName("127.0.0.1"), proxied),
                    "Proxied header rejected on loopback API token: " + proxyHeader);
        }

        // Simulate CLI process creating tokens on disk while web server is already running.
        Path platformDir = folder.resolve("platform");
        PlatformApiTokens externalCli = new PlatformApiTokens(platformDir);
        PlatformApiTokens.CreatedToken draftToken =
                externalCli.create("draft-bot", OWNER, List.of(PlatformApiTokens.SCOPE_UPDATES_DRAFT));
        PlatformApiTokens.CreatedToken publishToken =
                externalCli.create(
                        "pub-bot", OWNER, List.of(PlatformApiTokens.SCOPE_UPDATES_PUBLISH));
        PlatformApiTokens.CreatedToken nonOwnerToken =
                externalCli.create(
                        "non-owner", PLAYER, List.of(PlatformApiTokens.SCOPE_UPDATES_PUBLISH));

        check(
                bearerRequest(HTTP, "/api/playerhub/state", null, "pmk_invalid", null).statusCode()
                        == 401,
                "Invalid API token returns 401");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/state",
                                null,
                                publishToken.secret(),
                                Map.of("X-Forwarded-For", "198.51.100.8"))
                        .statusCode()
                        == 403,
                "X-Forwarded-For rejected on live loopback HTTP");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/state",
                                null,
                                publishToken.secret(),
                                Map.of("Origin", url))
                        .statusCode()
                        == 403,
                "Origin header rejected on API token GET");
        check(
                bearerRequest(admin, "/api/playerhub/state", null, publishToken.secret(), null)
                        .statusCode()
                        == 403,
                "Mixed session cookie and API token rejected");
        check(
                bearerRequest(HTTP, "/api/playerhub/state", null, nonOwnerToken.secret(), null)
                        .statusCode()
                        == 403,
                "Non-owner API token denied");

        // Draft-only token read/write and publish guardrails.
        check(
                bearerRequest(HTTP, "/api/playerhub/state", null, draftToken.secret(), null)
                                .statusCode()
                        == 200,
                "Draft token hot-reloads and reads state");
        check(
                bearerRequest(HTTP, "/api/playerhub/packs?page=0", null, draftToken.secret(), null)
                                .statusCode()
                        == 200,
                "Draft token reads packs");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entries?packId=" + packId,
                                null,
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 200,
                "Draft token reads entries");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry?id=" + publishedEntryId,
                                null,
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 200,
                "Draft token reads single entry");

        JsonObject richDoc =
                obj(
                        "type",
                        "doc",
                        "content",
                        List.of(
                                obj(
                                        "type",
                                        "heading",
                                        "attrs",
                                        obj("level", 3),
                                        "content",
                                        List.of(obj("type", "text", "text", "本次更新"))),
                                obj(
                                        "type",
                                        "bulletList",
                                        "content",
                                        List.of(
                                                obj(
                                                        "type",
                                                        "listItem",
                                                        "content",
                                                        List.of(
                                                                obj(
                                                                        "type",
                                                                        "paragraph",
                                                                        "content",
                                                                        List.of(
                                                                                obj(
                                                                                        "type",
                                                                                        "text",
                                                                                        "text",
                                                                                        "支持本机自动发稿",
                                                                                        "marks",
                                                                                        List.of(
                                                                                                obj(
                                                                                                        "type",
                                                                                                        "bold")))))))))));

        JsonObject draftPayload =
                obj(
                        "packId",
                        packId,
                        "packName",
                        "验收包",
                        "packVersion",
                        "1.0",
                        "updateVersion",
                        "0.2",
                        "title",
                        "回环令牌草稿",
                        "summary",
                        "仅保存草稿",
                        "category",
                        "功能更新",
                        "body",
                        "草稿正文",
                        "bodyDocument",
                        richDoc,
                        "pinned",
                        false,
                        "publish",
                        false,
                        "expectedRevision",
                        0);
        HttpResponse<String> draftCreated =
                bearerRequest(
                        HTTP, "/api/playerhub/entry/save", draftPayload, draftToken.secret(), null);
        check(draftCreated.statusCode() == 200, "Draft token creates draft entry");
        JsonObject draftEntry = json(draftCreated.body()).getAsJsonObject("entry");
        check(
                "draft".equals(draftEntry.get("status").getAsString()),
                "Draft token entry remains draft");

        JsonObject draftEdit = draftPayload.deepCopy();
        draftEdit.addProperty("id", draftEntry.get("id").getAsString());
        draftEdit.addProperty("title", "回环令牌草稿修订");
        draftEdit.addProperty("expectedRevision", draftEntry.get("revision").getAsLong());
        check(
                bearerRequest(HTTP, "/api/playerhub/entry/save", draftEdit, draftToken.secret(), null)
                                .statusCode()
                        == 200,
                "Draft token can edit existing draft");

        JsonObject forbiddenPublish = draftPayload.deepCopy();
        forbiddenPublish.addProperty("publish", true);
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry/save",
                                forbiddenPublish,
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Draft token cannot publish directly");

        JsonObject existingPublished = entry(publishedEntryId);
        JsonObject editPublished = draftPayload.deepCopy();
        editPublished.addProperty("id", publishedEntryId);
        editPublished.addProperty("expectedRevision", existingPublished.get("revision").getAsLong());
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry/save",
                                editPublished,
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Draft token cannot edit already published entry");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry/status",
                                obj(
                                        "id",
                                        publishedEntryId,
                                        "status",
                                        "withdrawn",
                                        "expectedRevision",
                                        existingPublished.get("revision")),
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Draft token cannot change entry status");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry/pin",
                                obj(
                                        "id",
                                        publishedEntryId,
                                        "pinned",
                                        true,
                                        "expectedRevision",
                                        existingPublished.get("revision")),
                                draftToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Draft token cannot pin entry");

        // Publish token creates and publishes directly, increments revision, and writes audit actor.
        String revisionBefore = c.updates.revision();
        JsonObject publishPayload = draftPayload.deepCopy();
        publishPayload.addProperty("title", "回环令牌直发公告");
        publishPayload.addProperty("summary", "通过本机回环令牌直接发布");
        publishPayload.addProperty("publish", true);
        HttpResponse<String> publishedRes =
                bearerRequest(
                        HTTP,
                        "/api/playerhub/entry/save",
                        publishPayload,
                        publishToken.secret(),
                        null);
        check(publishedRes.statusCode() == 200, "Publish token publishes entry directly");
        JsonObject publishedByToken = json(publishedRes.body()).getAsJsonObject("entry");
        String tokenEntryId = publishedByToken.get("id").getAsString();
        check(
                "published".equals(publishedByToken.get("status").getAsString()),
                "Publish token entry is published");
        check(
                !revisionBefore.equals(c.updates.revision()),
                "Publish token increments live playerHubRevision");
        check(
                "回环令牌直发公告"
                        .equals(
                                ownRead(tokenEntryId)
                                        .getAsJsonObject("entry")
                                        .get("title")
                                        .getAsString()),
                "Player reads entry published by API token");
        try (Connection db = c.store.open()) {
            List<JsonObject> auditRows =
                    rows(
                            db,
                            "SELECT actor,action FROM pm_audit WHERE actor=? AND action='PLAYERHUB_ENTRY_SAVE'",
                            "token:pub-bot:" + OWNER);
            check(!auditRows.isEmpty(), "Audit log records scoped token actor");
        }

        HttpResponse<String> pinnedRes =
                bearerRequest(
                        HTTP,
                        "/api/playerhub/entry/pin",
                        obj(
                                "id",
                                tokenEntryId,
                                "pinned",
                                true,
                                "expectedRevision",
                                publishedByToken.get("revision")),
                        publishToken.secret(),
                        null);
        check(pinnedRes.statusCode() == 200, "Publish token can pin entry");
        long revAfterPin = json(pinnedRes.body()).getAsJsonObject("entry").get("revision").getAsLong();
        HttpResponse<String> withdrawnRes =
                bearerRequest(
                        HTTP,
                        "/api/playerhub/entry/status",
                        obj(
                                "id",
                                tokenEntryId,
                                "status",
                                "withdrawn",
                                "expectedRevision",
                                revAfterPin),
                        publishToken.secret(),
                        null);
        check(withdrawnRes.statusCode() == 200, "Publish token can withdraw entry");

        // Strictly denied routes even for publish token.
        long revAfterWithdraw =
                json(withdrawnRes.body()).getAsJsonObject("entry").get("revision").getAsLong();
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/entry/delete",
                                obj(
                                        "id",
                                        tokenEntryId,
                                        "confirmTitle",
                                        "回环令牌直发公告",
                                        "expectedRevision",
                                        revAfterWithdraw),
                                publishToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Publish token cannot delete entries");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/settings",
                                obj("updatesEnabled", false, "expectedRevision", 1),
                                publishToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Publish token cannot modify global settings");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/pack/visibility",
                                obj("packId", packId, "visible", false, "expectedRevision", 1),
                                publishToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Publish token cannot change pack visibility");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/comment/reply",
                                obj(
                                        "id",
                                        tokenEntryId,
                                        "commentId",
                                        UUID.randomUUID(),
                                        "text",
                                        "hi",
                                        "operationId",
                                        UUID.randomUUID()),
                                publishToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Publish token cannot reply to comments");
        check(
                bearerRequest(
                                HTTP,
                                "/api/playerhub/comment/delete",
                                obj("id", tokenEntryId, "commentId", UUID.randomUUID()),
                                publishToken.secret(),
                                null)
                        .statusCode()
                        == 403,
                "Publish token cannot delete comments");
        for (String forbiddenGet :
                List.of(
                        "/api/playerhub/replies?id="
                                + tokenEntryId
                                + "&commentId="
                                + UUID.randomUUID(),
                        "/api/me",
                        "/api/manager/bootstrap",
                        "/api/playerhub/leaderboards?instance=" + INSTANCE,
                        "/api/playerhub/community/state?instance=" + INSTANCE)) {
            check(
                    bearerRequest(HTTP, forbiddenGet, null, publishToken.secret(), null)
                                    .statusCode()
                            == 403,
                    "Publish token denied forbidden route: " + forbiddenGet);
        }

        // Revoking token via external CLI immediately takes effect on running web server.
        externalCli.revoke("pub-bot");
        check(
                bearerRequest(HTTP, "/api/playerhub/state", null, publishToken.secret(), null)
                                .statusCode()
                        == 401,
                "Revoked API token immediately rejected via hot reload");

        // CLI end-to-end command check (create / list / revoke / reject non-owner).
        Path cliDir = folder.resolve("cli-token-check");
        Path cliCfg = cliDir.resolve("manager.json");
        Path cliOut = cliDir.resolve("out-token.json");
        ServiceConfig.atomic(cliCfg, Wire.JSON.toJson(c.config));
        Main.main(
                new String[] {
                    "api-token",
                    cliCfg.toString(),
                    "create",
                    "cli-bot",
                    OWNER.toString(),
                    PlatformApiTokens.SCOPE_UPDATES_PUBLISH,
                    cliOut.toString()
                });
        JsonObject cliCred = JsonParser.parseString(Files.readString(cliOut)).getAsJsonObject();
        check(
                cliCred.get("token").getAsString().startsWith("pmk_"),
                "CLI creates pmk_ credential file");
        Main.main(new String[] {"api-token", cliCfg.toString(), "list"});
        Main.main(new String[] {"api-token", cliCfg.toString(), "revoke", "cli-bot"});
        denied(
                () ->
                        Main.main(
                                new String[] {
                                    "api-token",
                                    cliCfg.toString(),
                                    "create",
                                    "bad-owner",
                                    PLAYER.toString(),
                                    PlatformApiTokens.SCOPE_UPDATES_DRAFT
                                }),
                "CLI rejects non-owner UUID when creating API token");
  }
  public static void main(String[]args)throws Exception{
        if (!"isolated".equals(System.getenv("PM_HUB_TEST_SCOPE")))
            throw new IllegalStateException("Owned isolated test launcher required");
        String base = "jdbc:mariadb://127.0.0.1:43320/",
                schema = "pm_updates_" + UUID.randomUUID().toString().replace("-", ""),
                restoredSchema = schema + "_restore";
        try (Connection c = DriverManager.getConnection(base, "root", "")) {
            c.createStatement().execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            c.createStatement()
                    .execute("CREATE DATABASE " + restoredSchema + " CHARACTER SET utf8mb4");
        }
    try{int port;try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
            url = "http://127.0.0.1:" + port;
            Database.Config db = new Database.Config(base + schema, "root", "");
            ServiceConfig config =
                    new ServiceConfig(
                            db,
                            db,
                            false,
                            "127.0.0.1",
                            port,
                            url,
                            List.of(OWNER),
                            "data",
                            db,
                            false,
                            "unified",
                            30);
            Path folder = Files.createTempDirectory(Path.of("build"), "updates-http-");
            try(ManagerController controller=new ManagerController(config,folder)){
                run(
                        controller,
                        folder,
                        restoredSchema,
                        new Database.Config(base + restoredSchema, "root", ""));
            }
            System.out.println("PlayerHub updates HTTP/SQL checks passed: " + checks);
        }finally{
            try (Connection c = DriverManager.getConnection(base, "root", "")) {
                c.createStatement().execute("DROP DATABASE " + schema);
                c.createStatement().execute("DROP DATABASE " + restoredSchema);
            }}
  }
}
