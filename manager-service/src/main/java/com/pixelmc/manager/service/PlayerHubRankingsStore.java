package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.*;
import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Public, scoped read projections. Sources remain owned by their existing modules. */
public final class PlayerHubRankingsStore {
    public static final List<String> TABLES = List.of(
            "ph_ranking_values", "ph_ranking_boards", "ph_ranking_published");
    private static final List<String> BOARDS = List.of("food", "fortunes", "online");
    private static final long MAX_VALUE = 9_007_199_254_740_991L;
    private static final String GLOBAL_SCOPE = "dailyfortunes";
    private static final String UPSERT_VALUE = "INSERT INTO ph_ranking_values(board,scope_id,player_uuid,player_name,metric_value,updated_at) VALUES(?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),metric_value=VALUES(metric_value),updated_at=VALUES(updated_at)";
    private final ManagerStore store;
    private final String epoch = UUID.randomUUID().toString();
    private final AtomicLong changes = new AtomicLong();
    private final AtomicLong[] boardChanges = {new AtomicLong(),new AtomicLong(),new AtomicLong()};
    private final Set<UUID> fortunePlayers = new LinkedHashSet<>();
    private boolean fortuneBaseline = true;

    public PlayerHubRankingsStore(ManagerStore store) { this.store = store; }
    public String revision() { return epoch + ":" + changes.get(); }
    public void changed() { changed(7); }
    public void changed(int mask) {
        changes.incrementAndGet();
        for(int index=0;index<BOARDS.size();index++)if((mask&(1<<index))!=0)boardChanges[index].incrementAndGet();
    }
    public JsonObject revisions() {
        JsonObject result=new JsonObject();
        for(int index=0;index<BOARDS.size();index++)result.addProperty(BOARDS.get(index),epoch+":"+boardChanges[index].get());
        return result;
    }

    public void migrate() throws Exception {
        try (Connection connection = store.open()) {
            update(connection, "CREATE TABLE IF NOT EXISTS ph_ranking_values("
                    + "board VARCHAR(16) CHARACTER SET ascii NOT NULL,scope_id VARCHAR(64) CHARACTER SET ascii NOT NULL,"
                    + "player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,player_name VARCHAR(64) NOT NULL,"
                    + "metric_value BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(board,scope_id,player_uuid),"
                    + "KEY ranking_value(board,scope_id,metric_value,player_uuid),KEY ranking_player(player_uuid)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            update(connection, "CREATE TABLE IF NOT EXISTS ph_ranking_boards("
                    + "board VARCHAR(16) CHARACTER SET ascii NOT NULL,scope_id VARCHAR(64) CHARACTER SET ascii NOT NULL,"
                    + "source_status VARCHAR(16) NOT NULL,revision BIGINT NOT NULL DEFAULT 0,"
                    + "previous_revision BIGINT NOT NULL DEFAULT 0,previous_at BIGINT NOT NULL DEFAULT 0,dirty BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "published_at BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(board,scope_id)) ENGINE=InnoDB");
            update(connection, "CREATE TABLE IF NOT EXISTS ph_ranking_published("
                    + "board VARCHAR(16) CHARACTER SET ascii NOT NULL,scope_id VARCHAR(64) CHARACTER SET ascii NOT NULL,"
                    + "revision BIGINT NOT NULL,player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,"
                    + "metric_value BIGINT NOT NULL,rank_value BIGINT NOT NULL,position_value BIGINT NOT NULL,"
                    + "PRIMARY KEY(board,scope_id,revision,player_uuid),"
                    + "KEY ranking_page(board,scope_id,revision,position_value),KEY ranking_owner(player_uuid)) ENGINE=InnoDB");
        }
    }

    private static boolean source(Connection c, String board, String scope, String status) throws Exception {
        List<JsonObject> existing = rows(c, "SELECT source_status FROM ph_ranking_boards WHERE board=? AND scope_id=? FOR UPDATE", board, scope);
        if (!existing.isEmpty() && text(existing.get(0), "source_status", 16).equals(status)) return false;
        update(c, "INSERT INTO ph_ranking_boards(board,scope_id,source_status) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE source_status=VALUES(source_status),dirty=TRUE", board, scope, status);
        return true;
    }

    /** Called inside the existing authenticated telemetry transaction, after stats acceptance. */
    public int ingest(Connection c, String archive, JsonObject packet) throws Exception {
        JsonObject metadata = object(object(packet, "snapshot"), "foodCompatibility");
        String foodStatus = metadata.has("status") ? text(metadata, "status", 16) : "unsupported";
        if (!Set.of("ready", "unsupported", "unavailable").contains(foodStatus)) foodStatus = "unavailable";
        // Match the snapshot publication lock order across simultaneous telemetry and reads.
        int changedMask = source(c, "food", archive, foodStatus)?1:0;
        if(source(c, "online", archive, "ready"))changedMask|=4;
        Map<String, JsonObject> observed = new LinkedHashMap<>();
        for (String key : List.of("stats", "players")) for (JsonElement raw : array(packet, key, 10000)) {
            JsonObject entry = raw.getAsJsonObject();
            if (!bool(entry, "fake", false)) observed.put(uuid(text(entry, "id", 36)), entry);
        }
        List<String> ids = new ArrayList<>(observed.keySet());
        boolean onlineChanged = false, foodChanged = false;
        try (PreparedStatement write = c.prepareStatement(UPSERT_VALUE)) {
            for (int offset = 0; offset < ids.size(); offset += 250) {
                List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + 250));
                List<Object> parameters = new ArrayList<>(); parameters.add(archive); parameters.addAll(batch);
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                List<JsonObject> accepted = rows(c, "SELECT p.uuid,p.name,p.total_ms,o.metric_value old_online,o.player_name online_name,"
                        + "f.metric_value old_food,f.player_name food_name FROM pm_players p JOIN pm_profiles g ON g.uuid=p.uuid "
                        + "LEFT JOIN ph_ranking_values o ON o.board='online' AND o.scope_id=p.archive_id AND o.player_uuid=p.uuid "
                        + "LEFT JOIN ph_ranking_values f ON f.board='food' AND f.scope_id=p.archive_id AND f.player_uuid=p.uuid "
                        + "WHERE p.archive_id=? AND p.uuid IN(" + placeholders + ") AND p.uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)",
                        parameters.toArray());
                for (JsonObject stats : accepted) {
                    String player = stats.get("uuid").getAsString(), name = stats.get("name").getAsString();
                    long online = stats.get("total_ms").getAsLong();
                    if (different(stats, "old_online", "online_name", online, name)) {
                        addValue(write, "online", archive, player, name, online); onlineChanged = true;
                    }
                    JsonObject incoming = observed.get(player);
                    if (foodStatus.equals("ready") && incoming.has("effectiveFoods")) {
                        long count = number(incoming, "effectiveFoods", -1, 0, MAX_VALUE);
                        if (different(stats, "old_food", "food_name", count, name)) {
                            addValue(write, "food", archive, player, name, count); foodChanged = true;
                        }
                    }
                }
                write.executeBatch(); write.clearBatch();
            }
        }
        if (onlineChanged) update(c, "UPDATE ph_ranking_boards SET dirty=TRUE WHERE board='online' AND scope_id=?", archive);
        if (foodChanged) update(c, "UPDATE ph_ranking_boards SET dirty=TRUE WHERE board='food' AND scope_id=?", archive);
        return changedMask | (onlineChanged?4:0) | (foodChanged?1:0);
    }

    private static boolean different(JsonObject row, String valueKey, String nameKey, long value, String name) {
        return row.get(valueKey).isJsonNull() || row.get(valueKey).getAsLong() != value || !name.equals(row.get(nameKey).getAsString());
    }
    private static void addValue(PreparedStatement write, String board, String scope, String player, String name, long value) throws Exception {
        if (value < 0 || value > MAX_VALUE) throw new IllegalArgumentException("排行数值超出范围");
        write.setString(1, board); write.setString(2, scope); write.setString(3, player); write.setString(4, name);
        write.setLong(5, value); write.setLong(6, System.currentTimeMillis()); write.addBatch();
    }

    public synchronized void fortuneChanged(UUID player) {
        if (fortunePlayers.size() >= 10000) { fortunePlayers.clear(); fortuneBaseline = true; }
        else fortunePlayers.add(player);
        changed(2);
    }

    public synchronized void invalidateAll() throws Exception {
        fortuneBaseline = true;
        try (Connection c = store.open()) { update(c, "UPDATE ph_ranking_boards SET dirty=TRUE"); }
        changed();
    }

    private void refreshFortunes(FortuneBackend fortunes) throws Exception {
        String status = fortunes == null ? "unsupported" : "ready";
        Map<UUID, Long> values = Map.of();
        try { if (status.equals("ready")) {
            if (fortuneBaseline) values = fortunes.archivedKindCounts();
            else if (!fortunePlayers.isEmpty()) {
                Map<UUID, Long> selected = new LinkedHashMap<>();
                for (UUID player : fortunePlayers) selected.put(player, fortunes.archivedKindCount(player));
                values = selected;
            }
        } } catch (RuntimeException unavailable) { status = "unavailable"; }
        try (Connection c = store.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                source(c, "fortunes", GLOBAL_SCOPE, status);
                if (status.equals("ready")) {
                    boolean dataChanged = fortuneBaseline;
                    if (fortuneBaseline) {
                        update(c, "DELETE FROM ph_ranking_values WHERE board='fortunes' AND scope_id=?", GLOBAL_SCOPE);
                        update(c, "UPDATE ph_ranking_boards SET dirty=TRUE WHERE board='fortunes' AND scope_id=?", GLOBAL_SCOPE);
                    }
                    List<UUID> ids = new ArrayList<>(values.keySet());
                    try (PreparedStatement write = c.prepareStatement(UPSERT_VALUE)) {
                        for (int offset = 0; offset < ids.size(); offset += 250) {
                            List<UUID> batch = ids.subList(offset, Math.min(ids.size(), offset + 250));
                            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                            List<JsonObject> profiles = rows(c, "SELECT p.uuid,p.name,v.metric_value old_value,v.player_name old_name "
                                    + "FROM pm_profiles p LEFT JOIN ph_ranking_values v ON v.board='fortunes' AND v.scope_id='dailyfortunes' AND v.player_uuid=p.uuid "
                                    + "WHERE p.uuid IN(" + placeholders + ") AND p.uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)", batch.stream().map(UUID::toString).toArray());
                            for (JsonObject profile : profiles) {
                                UUID player = UUID.fromString(profile.get("uuid").getAsString());
                                String name = profile.get("name").getAsString();
                                if (different(profile, "old_value", "old_name", values.get(player), name)) {
                                    addValue(write, "fortunes", GLOBAL_SCOPE, player.toString(), name, values.get(player)); dataChanged = true;
                                }
                            }
                            write.executeBatch(); write.clearBatch();
                        }
                    }
                    if (dataChanged) update(c, "UPDATE ph_ranking_boards SET dirty=TRUE WHERE board='fortunes' AND scope_id=?", GLOBAL_SCOPE);
                }
                c.commit();
                if (status.equals("ready")) { fortuneBaseline = false; fortunePlayers.clear(); }
            } catch (Exception error) { c.rollback(); throw error; }
        }
    }

    private static String activeArchive(Connection c, String instance) throws Exception {
        List<JsonObject> active = rows(c, "SELECT a.id FROM pm_instances i JOIN pm_archives a ON a.id=i.active_archive "
                + "AND a.world_id=i.world_id WHERE i.id=? AND a.status='active'", id(instance));
        if (active.isEmpty()) throw new IllegalStateException("当前服务器尚未绑定活动存档");
        return active.get(0).get("id").getAsString();
    }

    private static JsonObject publish(Connection c, String board, String scope) throws Exception {
        List<JsonObject> states = rows(c, "SELECT * FROM ph_ranking_boards WHERE board=? AND scope_id=? FOR UPDATE", board, scope);
        if (states.isEmpty()) {
            source(c, board, scope, "unavailable");
            states = rows(c, "SELECT * FROM ph_ranking_boards WHERE board=? AND scope_id=? FOR UPDATE", board, scope);
        }
        JsonObject state = states.get(0);
        if (!state.get("source_status").getAsString().equals("ready") || !state.get("dirty").getAsBoolean()) return state;
        long previous = state.get("revision").getAsLong(), next = Math.addExact(previous, 1);
        update(c, "INSERT INTO ph_ranking_published(board,scope_id,revision,player_uuid,metric_value,rank_value,position_value) "
                + "SELECT ?,?,?,v.player_uuid,v.metric_value,RANK() OVER(ORDER BY v.metric_value DESC),"
                + "ROW_NUMBER() OVER(ORDER BY v.metric_value DESC,v.player_uuid ASC) "
                + "FROM ph_ranking_values v JOIN pm_profiles p ON p.uuid=v.player_uuid "
                + "WHERE v.board=? AND v.scope_id=? AND v.metric_value>0 "
                + "AND p.uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)", board, scope, next, board, scope);
        long at = System.currentTimeMillis();
        long previousAt = state.get("published_at").getAsLong();
        update(c, "UPDATE ph_ranking_boards SET revision=?,previous_revision=?,previous_at=?,dirty=FALSE,published_at=? WHERE board=? AND scope_id=?",
                next, previous, previousAt, at, board, scope);
        update(c, "DELETE FROM ph_ranking_published WHERE board=? AND scope_id=? AND revision<?", board, scope, Math.max(0, previous-1));
        state.addProperty("previous_at",previousAt);
        state.addProperty("revision", next); state.addProperty("previous_revision", previous); state.addProperty("published_at", at);
        return state;
    }

    private String token(String board, String scope, long revision) { return board + ":" + scope + ":" + revision; }
    private static String board(JsonObject query) {
        String result = text(query, "board", 16);
        if (!BOARDS.contains(result)) throw new IllegalArgumentException("排行类别无效");
        return result;
    }

    private JsonObject projection(Connection c, String instance, UUID viewer, String board, int page, int size,
                                  String expected, boolean locate) throws Exception {
        String scope = board.equals("fortunes") ? GLOBAL_SCOPE : activeArchive(c, instance);
        JsonObject state = publish(c, board, scope);
        String status = state.get("source_status").getAsString();
        long revision = state.get("revision").getAsLong(), previous = state.get("previous_revision").getAsLong();
        long publishedAt = state.get("published_at").getAsLong();
        if (!expected.isEmpty() && !expected.equals(token(board, scope, revision))) {
            if (previous <= 0 || !expected.equals(token(board, scope, previous)))
                throw new IllegalStateException("排行已更新，请重新读取当前榜单");
            revision = previous; previous = Math.max(0, revision-1);
            publishedAt = state.get("previous_at").getAsLong();
        }
        List<JsonObject> profile = rows(c, "SELECT name FROM pm_profiles WHERE uuid=? AND uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)", viewer.toString());
        String name = profile.isEmpty() ? "管理员" : profile.get(0).get("name").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1); result.addProperty("board", board); result.addProperty("supported", !status.equals("unsupported"));
        result.addProperty("period", "all"); result.addProperty("ranking", "competition");
        result.add("scope", JSON.toJsonTree(Map.of("kind", board.equals("fortunes") ? "global" : "archive", "id", scope,
                "label", board.equals("fortunes") ? "全局收藏" : "本存档")));
        result.addProperty("unit", board.equals("food") ? "foods" : board.equals("fortunes") ? "fortunes" : "ms");
        result.add("viewer", JSON.toJsonTree(Map.of("playerId", viewer.toString(), "name", name)));
        result.add("rows", new JsonArray()); result.add("self", JsonNull.INSTANCE);
        result.add("total", JsonNull.INSTANCE); result.add("updatedAt", JsonNull.INSTANCE);
        result.add("revision", JsonNull.INSTANCE); result.add("comparisonRevision", JsonNull.INSTANCE);
        if (status.equals("ready")) {
            String base = " FROM ph_ranking_published r JOIN pm_profiles p ON p.uuid=r.player_uuid "
                    + "LEFT JOIN ph_ranking_published old ON old.board=r.board AND old.scope_id=r.scope_id "
                    + "AND old.player_uuid=r.player_uuid AND old.revision=? "
                    + "WHERE r.board=? AND r.scope_id=? AND r.revision=? "
                    + "AND p.uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)";
            String columns = "SELECT r.player_uuid,p.name,r.metric_value,r.rank_value,r.position_value,old.rank_value previous_rank";
            List<JsonObject> own = rows(c, columns + base + " AND r.player_uuid=?", previous, board, scope, revision, viewer.toString());
            if (locate && !own.isEmpty()) page = (int)((own.get(0).get("position_value").getAsLong() - 1) / size);
            long total = rows(c, "SELECT COUNT(*) AS count_value" + base, previous, board, scope, revision).get(0).get("count_value").getAsLong();
            JsonArray entries = new JsonArray();
            for (JsonObject row : rows(c, columns + base + " ORDER BY r.position_value LIMIT ? OFFSET ?",
                    previous, board, scope, revision, size, Math.multiplyExact(page, size))) entries.add(publicRow(row));
            result.add("rows", entries); result.addProperty("total", total);
            result.addProperty("updatedAt", publishedAt);
            result.addProperty("revision", token(board, scope, revision));
            if (previous > 0) result.addProperty("comparisonRevision", token(board, scope, previous));
            if (!own.isEmpty()) result.add("self", publicRow(own.get(0)));
            else if (!profile.isEmpty()) {
                JsonObject self = new JsonObject(); self.addProperty("playerId", viewer.toString()); self.addProperty("name", name);
                self.add("rank", JsonNull.INSTANCE); self.add("rankDelta", JsonNull.INSTANCE); self.add("value", JsonNull.INSTANCE);
                List<JsonObject> known = rows(c, "SELECT metric_value FROM ph_ranking_values WHERE board=? AND scope_id=? AND player_uuid=?", board, scope, viewer.toString());
                if (!known.isEmpty()) self.addProperty("value", known.get(0).get("metric_value").getAsLong());
                else if (board.equals("fortunes") && !profile.isEmpty()) self.addProperty("value", 0);
                result.add("self", self);
            }
            if (total == 0) { status = "empty"; result.add("self", JsonNull.INSTANCE); result.add("comparisonRevision", JsonNull.INSTANCE); }
        }
        result.addProperty("status", status); result.addProperty("page", page); result.addProperty("size", size);
        return result;
    }

    private static JsonObject publicRow(JsonObject row) {
        JsonObject result = new JsonObject();
        String player = row.get("player_uuid").getAsString();
        result.addProperty("playerId", player); result.add("name", row.get("name"));
        result.add("rank", row.get("rank_value")); result.add("value", row.get("metric_value"));
        if (row.get("previous_rank").isJsonNull()) result.add("rankDelta", JsonNull.INSTANCE);
        else result.addProperty("rankDelta", row.get("previous_rank").getAsLong() - row.get("rank_value").getAsLong());
        result.add("avatar", JSON.toJsonTree(Map.of("url", "/api/avatars/" + player + ".png", "version", "platform")));
        return result;
    }

    public synchronized JsonObject player(String instance, UUID viewer, String action, JsonObject query,
                                          FortuneBackend fortunes) throws Exception {
        if (!Set.of("snapshot", "page", "aroundMe", "window").contains(action)) throw new IllegalArgumentException("排行操作无效");
        Set<String> allowed = switch(action) {
            case "snapshot" -> Set.of();
            case "page" -> Set.of("board", "page", "revision");
            case "window" -> Set.of("board", "page", "count");
            default -> Set.of("board", "revision");
        };
        if (!allowed.containsAll(query.keySet())) throw new IllegalArgumentException("排行请求包含不支持的字段");
        if (action.equals("snapshot") || board(query).equals("fortunes")) refreshFortunes(fortunes);
        try (Connection c = store.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                if (rows(c,"SELECT uuid FROM pm_profiles WHERE uuid=? AND uuid NOT IN(SELECT uuid FROM pm_profile_exclusions)",viewer.toString()).isEmpty())
                    throw new SecurityException("玩家资料尚未完成入服确认");
                JsonObject result;
                if (action.equals("snapshot")) {
                    JsonObject boards = new JsonObject();
                    for (String board : BOARDS) boards.add(board, projection(c, instance, viewer, board, 0, 20, "", false));
                    result = new JsonObject(); result.addProperty("schemaVersion", 1); result.add("boards", boards);
                } else if (action.equals("window")) {
                    String board=board(query);
                    int first=(int)number(query,"page",0,0,100000), count=(int)number(query,"count",1,1,5);
                    JsonObject header=projection(c,instance,viewer,board,0,20,"",false);
                    JsonArray pages=new JsonArray();
                    if (header.get("status").getAsString().equals("ready")) {
                        int last=(int)((header.get("total").getAsLong()-1)/20);
                        first=Math.min(first,last);
                        for(int page=first;page<=Math.min(last,first+count-1);page++)
                            pages.add(projection(c,instance,viewer,board,page,20,header.get("revision").getAsString(),false));
                    }
                    result=new JsonObject();result.add("header",header);result.add("pages",pages);
                } else result = projection(c, instance, viewer, board(query), (int)number(query, "page", 0, 0, 100000),
                        20, text(query, "revision", 160), action.equals("aroundMe"));
                c.commit(); return result;
            } catch (Exception error) { c.rollback(); throw error; }
        }
    }

    public synchronized JsonObject admin(String instance, UUID viewer, Map<String, String> query, FortuneBackend fortunes) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("board", query.getOrDefault("board", "food"));
        if (!query.getOrDefault("period", "all").equals("all")) throw new IllegalArgumentException("排行时间范围无效");
        int page = Integer.parseInt(query.getOrDefault("page", "0")), size = Integer.parseInt(query.getOrDefault("size", "20"));
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new IllegalArgumentException("排行分页无效");
        if (board(body).equals("fortunes")) refreshFortunes(fortunes);
        try (Connection c = store.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try { JsonObject result = projection(c, instance, viewer, board(body), page, size, "", false); c.commit(); return result; }
            catch (Exception error) { c.rollback(); throw error; }
        }
    }
}
