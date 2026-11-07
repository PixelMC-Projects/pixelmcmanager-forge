package com.pixelmc.manager.service;

import static com.pixelmc.manager.service.ManagerStore.*;
import static com.pixelmc.manager.service.Wire.*;
import cn.pixelmc.memorialtitles.domain.Model;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Durable community display data. Identity and acquired items retain their source ownership. */
public final class PlayerHubCommunityStore {
    public static final List<String> TABLES = List.of("ph_community_profiles", "ph_community_notes", "ph_community_supplements", "ph_community_collections");
    public static final Set<String> COLORS = Set.of("mist", "lilac", "sage", "sand", "blush", "apricot", "tide", "twilight", "frost", "stone");
    public static final Set<String> PATTERNS = Set.of("classic", "doodle", "tech", "architecture", "nature", "cosmos");
    private static final long DAY = 86_400_000L;
    private static final int OPTIONS = 100, SUPPLEMENTS = 20;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ManagerStore store;
    private final String epoch = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();

    public PlayerHubCommunityStore(ManagerStore store) { this.store = store; }
    public String revision() { return epoch + ":" + sequence.get(); }
    public void changed() { sequence.incrementAndGet(); }

    public void migrate() throws Exception {
        try (Connection c = store.open()) {
            String[] statements = {
                "CREATE TABLE IF NOT EXISTS ph_community_profiles(player_uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,bio VARCHAR(120) NOT NULL DEFAULT '',background VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'mist',pattern VARCHAR(24) CHARACTER SET ascii NOT NULL DEFAULT 'classic',featured_title VARCHAR(64) CHARACTER SET ascii NULL,featured_memory CHAR(36) CHARACTER SET ascii NULL,revision BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS ph_community_notes(player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,archive_id CHAR(36) CHARACTER SET ascii NOT NULL,text VARCHAR(1200) NOT NULL,deleted BOOLEAN NOT NULL DEFAULT FALSE,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(player_uuid,archive_id),KEY archive_members(archive_id,player_uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS ph_community_supplements(id CHAR(36) CHARACTER SET ascii PRIMARY KEY,player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,archive_id CHAR(36) CHARACTER SET ascii NOT NULL,text VARCHAR(600) NOT NULL,request_hash CHAR(64) CHARACTER SET ascii NOT NULL,deleted BOOLEAN NOT NULL DEFAULT FALSE,revision BIGINT NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,KEY member_supplements(player_uuid,archive_id,deleted,created_at,id),KEY archive_members(archive_id,player_uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS ph_community_collections(player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,archive_id CHAR(36) CHARACTER SET ascii NOT NULL,title_event VARCHAR(128) CHARACTER SET ascii NULL,fortune_event VARCHAR(128) CHARACTER SET ascii NULL,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(player_uuid,archive_id),KEY archive_members(archive_id,player_uuid)) ENGINE=InnoDB"
            };
            for (String sql : statements) try (Statement statement = c.createStatement()) { statement.execute(sql); }
        }
    }

    private <T> T transaction(ManagerStore.Work<T> work) throws Exception {
        try (Connection c = store.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (Exception failure) { c.rollback(); throw failure; }
        }
    }

    private static JsonObject obj(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add(pairs[i].toString(), pairs[i + 1] instanceof JsonElement value ? value.deepCopy() : JSON.toJsonTree(pairs[i + 1]));
        return result;
    }
    private JsonObject stamp(JsonObject value) { value.addProperty("revision", revision()); value.addProperty("serverTime", System.currentTimeMillis()); return value; }
    private static JsonObject one(Connection c, String sql, Object... args) throws Exception {
        List<JsonObject> values = rows(c, sql, args);
        if (values.isEmpty()) throw new IllegalArgumentException("资料不存在或不可见");
        return values.get(0);
    }
    private static boolean truth(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value != null && !value.isJsonNull() && (value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : value.getAsLong() != 0);
    }
    private static String nullable(JsonObject value, String key, int maximum) {
        if (!value.has(key) || value.get(key).isJsonNull()) return null;
        if (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("展示字段类型无效");
        return text(value, key, maximum);
    }
    private static String requiredUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("玩家或存档标识无效");
        return uuid(value);
    }
    private static String revisionOf(JsonObject row) { return row.get("revision").getAsString(); }
    private static void expect(String actual, JsonObject payload) {
        if (!Objects.equals(actual, nullable(payload, "expectedRevision", 160))) throw new IllegalStateException("内容已被更新，请保留草稿并重新读取");
    }
    private static String content(JsonObject payload, int limit) {
        String value = nullable(payload, "text", limit);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请先填写内容");
        return value.strip();
    }
    private static int page(JsonObject payload) { return (int) number(payload, "page", 0, 0, 100000); }
    private static JsonObject page(List<JsonObject> all, int page, int size) {
        int start = (int) Math.min(all.size(), (long) page * size);
        return obj("rows", all.subList(start, Math.min(all.size(), start + size)), "total", all.size(), "page", page, "size", size, "hasMore", (long) (page + 1) * size < all.size());
    }
    private static JsonObject identity(Connection c, String player, boolean lock) throws Exception {
        return one(c, "SELECT p.uuid playerId,p.name,p.first_join joinedAt,p.last_seen lastLoginAt FROM pm_profiles p WHERE p.uuid=? AND NOT EXISTS(SELECT 1 FROM pm_profile_exclusions x WHERE x.uuid=p.uuid)" + (lock ? " FOR UPDATE" : ""), uuid(player));
    }
    private static void publicTarget(Connection c, String instance, String player) throws Exception {
        identity(c, player, false);
        one(c, "SELECT p.uuid FROM pm_players p JOIN pm_archives a ON a.id=p.archive_id WHERE p.uuid=? AND a.instance_id=? AND p.join_count>0 LIMIT 1", player, id(instance));
    }
    private static JsonObject memberArchive(Connection c, String instance, String player, String archive) throws Exception {
        identity(c, player, false);
        return one(c, "SELECT a.id,a.name,a.version,CAST(a.start_date AS CHAR) startDate,a.end_at endAt,a.status,a.instance_id instanceId,(i.active_archive=a.id AND a.status='active') isCurrent,p.total_ms onlineMillis,p.join_count visits,p.last_join lastLoginAt FROM pm_archives a JOIN pm_instances i ON i.id=a.instance_id JOIN pm_players p ON p.archive_id=a.id WHERE a.id=? AND p.uuid=? AND p.join_count>0" + (instance == null ? "" : " AND a.instance_id=?"), instance == null ? new Object[]{requiredUuid(archive), player} : new Object[]{requiredUuid(archive), player, id(instance)});
    }
    private static long startAt(JsonObject archive) { return LocalDate.parse(text(archive, "startDate", 10)).atStartOfDay(ZONE).toInstant().toEpochMilli(); }
    private static long endAt(JsonObject archive) { return archive.get("endAt").isJsonNull() ? System.currentTimeMillis() : archive.get("endAt").getAsLong(); }
    private static String date(long at) { return Instant.ofEpochMilli(at).atZone(ZONE).toLocalDate().toString(); }

    private static List<JsonObject> titleChoices(TitleService titles, String player, long from, long through) {
        if (titles == null) return List.of();
        if (!titles.healthy()) throw new IllegalStateException("称号资料暂不可用，请稍后重试");
        Model.Snapshot snapshot = titles.snapshot();
        List<JsonObject> values = new ArrayList<>();
        for (Model.Owned owned : snapshot.owned().values()) {
            if (!owned.player().toString().equals(player) || owned.acquiredAt() <= 0 || owned.acquiredAt() < from || owned.acquiredAt() > through) continue;
            Model.Title title = snapshot.titles().get(owned.title());
            if (title == null) continue;
            values.add(obj("id", title.id(), "name", title.name().plain(), "richName", JSON.toJsonTree(title.name()), "description", title.description(), "rarity", title.presentation().rarity(), "style", title.presentation().theme(), "icon", title.presentation().badge(), "obtainedAt", owned.acquiredAt(), "date", date(owned.acquiredAt()), "eventId", "title:" + title.id() + ":" + owned.acquiredAt()));
        }
        values.sort(Comparator.<JsonObject>comparingLong(item -> item.get("obtainedAt").getAsLong()).reversed().thenComparing(item -> text(item, "id", 64)));
        return values;
    }
    private static JsonObject selected(List<JsonObject> rows, String key, String value) {
        if (value == null) return null;
        return rows.stream().filter(item -> value.equals(text(item, key, 128))).findFirst().orElse(null);
    }
    private static JsonObject fortunePage(Connection c, FortuneBackend fortunes, String player, long from, long through, int page) throws Exception {
        return fortunes == null ? obj("rows", List.of(), "total", 0, "page", page, "size", OPTIONS, "hasMore", false, "status", "unsupported") : fortunes.communityChoices(c, UUID.fromString(player), from, through, page, OPTIONS);
    }
    private static JsonObject fortune(Connection c, FortuneBackend fortunes, String player, String selected, boolean lock) throws Exception {
        return fortunes == null || selected == null || selected.isEmpty() ? null : fortunes.communityChoice(c, UUID.fromString(player), selected, lock);
    }
    private static JsonObject preference(Connection c, String player, FortuneBackend fortunes) throws Exception {
        if (fortunes == null) return obj("value", "", "revision", 0);
        return PlatformStore.valueInTransaction(c, "dailyfortunes", obj("player", player, "field", "featured_date"), false);
    }
    private static JsonObject profileRow(Connection c, String player) throws Exception {
        var rows = rows(c, "SELECT bio,background,pattern profilePattern,featured_title featuredTitleId,featured_memory featuredMemoryId,revision,updated_at updatedAt FROM ph_community_profiles WHERE player_uuid=?", player);
        return rows.isEmpty() ? obj("bio", "", "background", "mist", "profilePattern", "classic", "featuredTitleId", null, "featuredMemoryId", null, "revision", 0, "updatedAt", null) : rows.get(0);
    }
    private JsonObject archiveDto(Connection c, JsonObject archive, String player, TitleService titles, FortuneBackend fortunes) throws Exception {
        long collections = titleChoices(titles, player, startAt(archive), endAt(archive)).size()
            + fortunePage(c, fortunes, player, startAt(archive), endAt(archive), 0).get("total").getAsLong();
        return obj("id", archive.get("id"), "name", archive.get("name"), "version", archive.get("version"), "start", text(archive, "startDate", 10), "end", archive.get("endAt").isJsonNull() ? null : date(archive.get("endAt").getAsLong()), "isCurrent", truth(archive, "isCurrent"), "description", "", "coverPattern", "arcs", "coverColor", truth(archive, "isCurrent") ? "sage" : "mist", "onlineMillis", archive.get("onlineMillis"), "hours", Math.round(archive.get("onlineMillis").getAsDouble() / 360000d) / 10d, "visits", archive.get("visits"), "collections", collections, "moment", null, "momentText", "");
    }
    private JsonObject memories(Connection c, String instance, String player, boolean self, int page, TitleService titles, FortuneBackend fortunes) throws Exception {
        JsonObject person = identity(c, player, false);
        String filter = "p.uuid=? AND p.join_count>0" + (instance == null ? "" : " AND a.instance_id=?");
        Object[] args = instance == null ? new Object[]{player} : new Object[]{player, instance};
        long total = one(c, "SELECT COUNT(*) n FROM pm_archives a JOIN pm_players p ON p.archive_id=a.id WHERE " + filter, args).get("n").getAsLong();
        List<Object> query = new ArrayList<>(Arrays.asList(args)); query.add((long) page * OPTIONS);
        List<JsonObject> archives = rows(c, "SELECT a.id,a.name,a.version,CAST(a.start_date AS CHAR) startDate,a.end_at endAt,a.status,(i.active_archive=a.id AND a.status='active') isCurrent,p.total_ms onlineMillis,p.join_count visits FROM pm_archives a JOIN pm_instances i ON i.id=a.instance_id JOIN pm_players p ON p.archive_id=a.id WHERE " + filter + " ORDER BY isCurrent DESC,a.start_date DESC,a.created_at DESC,a.id LIMIT 100 OFFSET ?", query.toArray());
        List<JsonObject> values = new ArrayList<>(); for (JsonObject archive : archives) values.add(archiveDto(c, archive, player, titles, fortunes));
        return stamp(obj("playerId", player, "playerName", person.get("name"), "isSelf", self, "archives", values, "total", total, "page", page, "size", OPTIONS, "hasMore", (long) (page + 1) * OPTIONS < total));
    }

    private JsonObject options(Connection c, String instance, String player, String kind, int page, TitleService titles, FortuneBackend fortunes) throws Exception {
        JsonObject result = switch (kind) {
            case "titles" -> page(titleChoices(titles, player, 0, Long.MAX_VALUE), page, OPTIONS);
            case "fortunes" -> fortunePage(c, fortunes, player, 0, Long.MAX_VALUE, page);
            case "memories" -> { JsonObject archive = memories(c, instance, player, true, page, titles, fortunes); archive.add("rows", archive.remove("archives")); yield archive; }
            default -> throw new IllegalArgumentException("展示选项类别无效");
        };
        result.addProperty("kind", kind); result.addProperty("playerId", player); return stamp(result);
    }
    private JsonObject profile(Connection c, String instance, String player, boolean self, boolean editable, TitleService titles, FortuneBackend fortunes) throws Exception {
        JsonObject person = identity(c, player, false), stored = profileRow(c, player), pref = preference(c, player, fortunes);
        String selectedDate = text(pref, "value", 10);
        JsonObject featuredFortune = fortune(c, fortunes, player, selectedDate, false);
        JsonObject featuredTitle = selected(titleChoices(titles, player, 0, Long.MAX_VALUE), "id", nullable(stored, "featuredTitleId", 64));
        JsonObject featuredMemory = null;
        String memory = nullable(stored, "featuredMemoryId", 36);
        if (memory != null) {
            try { featuredMemory = archiveDto(c, memberArchive(c, instance, player, memory), player, titles, fortunes); }
            catch (IllegalArgumentException absent) { memory = null; }
        }
        JsonObject value = obj("playerId", player, "name", person.get("name"), "joinedAt", person.get("joinedAt"), "isSelf", self, "title", null, "bio", stored.get("bio"), "background", stored.get("background"), "profilePattern", stored.get("profilePattern"), "featuredTitleId", featuredTitle == null ? null : featuredTitle.get("id"), "featuredFortuneDate", featuredFortune == null ? null : selectedDate, "featuredMemoryId", memory, "featuredTitle", featuredTitle, "featuredFortune", featuredFortune, "featuredMemory", featuredMemory, "updatedAt", stored.get("updatedAt"));
        if (titles != null && titles.healthy()) {
            var snapshot = titles.snapshot(); String equipped = snapshot.equipped().get(UUID.fromString(player));
            JsonObject worn = selected(titleChoices(titles, player, 0, Long.MAX_VALUE), "id", equipped);
            Model.Owned ownership = equipped == null ? null : snapshot.owned().get(new Model.Key(UUID.fromString(player), equipped));
            if (ownership != null && ownership.publicDisplay()) value.add("title", worn == null ? JsonNull.INSTANCE : worn);
        }
        JsonObject revisionFields = value.deepCopy(); revisionFields.remove("isSelf");
        value.addProperty("revision", digest(player + ":" + revisionOf(stored) + ":" + pref.get("revision") + ":" + JSON.toJson(revisionFields)));
        JsonObject result = stamp(obj("profile", value));
        if (editable) {
            JsonObject all = obj("backgrounds", backgrounds(), "patterns", patterns());
            for (String kind : List.of("titles", "fortunes", "memories")) {
                JsonObject part = options(c, instance, player, kind, 0, titles, fortunes);
                all.add(kind, part.get("rows")); all.add(kind + "Total", part.get("total")); all.add(kind + "HasMore", part.get("hasMore"));
            }
            all.addProperty("titlesStatus", titles == null ? "unsupported" : titles.healthy() ? "ready" : "unavailable");
            all.addProperty("fortunesStatus", fortunes == null ? "unsupported" : "ready");
            result.add("options", all);
        }
        return result;
    }

    private JsonObject note(Connection c, String player, String archive) throws Exception {
        var rows = rows(c, "SELECT text,deleted,revision,updated_at updatedAt FROM ph_community_notes WHERE player_uuid=? AND archive_id=?", player, archive);
        JsonObject value = rows.isEmpty() ? obj("text", "", "deleted", false, "revision", "0", "updatedAt", null) : rows.get(0);
        value.addProperty("revision", revisionOf(value)); value.addProperty("deleted", truth(value, "deleted")); return value;
    }
    private JsonObject collection(Connection c, String player, String archive) throws Exception {
        var rows = rows(c, "SELECT title_event titleEventId,fortune_event fortuneEventId,revision,updated_at updatedAt FROM ph_community_collections WHERE player_uuid=? AND archive_id=?", player, archive);
        JsonObject value = rows.isEmpty() ? obj("titleEventId", null, "fortuneEventId", null, "revision", "0", "updatedAt", null) : rows.get(0);
        return obj("selection", obj("titleEventId", value.get("titleEventId"), "fortuneEventId", value.get("fortuneEventId")), "revision", revisionOf(value), "updatedAt", value.get("updatedAt"));
    }
    private JsonObject candidatePage(Connection c, String player, JsonObject archive, TitleService titles, FortuneBackend fortunes, int page) throws Exception {
        if (titles != null && !titles.healthy()) throw new IllegalStateException("称号资料暂不可用，请稍后重试");
        // Sort source acquisition keys in SQL before pagination. Fetch only this page's public projections.
        List<String> sources = new ArrayList<>(); List<Object> args = new ArrayList<>();
        long from = startAt(archive), through = endAt(archive);
        if (titles != null && titles.healthy()) {
            sources.add("SELECT CONCAT('title:',o.title,':',o.acquired_at) eventId,o.acquired_at obtainedAt FROM mt_owned o JOIN mt_titles t ON t.id=o.title WHERE o.player=? AND o.acquired_at>0 AND o.acquired_at>=? AND o.acquired_at<=?");
            args.add(player); args.add(from); args.add(through);
        }
        if (fortunes != null) {
            sources.add("SELECT CONCAT('fortune:',day,':',at_ms) eventId,at_ms obtainedAt FROM df_records WHERE player=? AND state='archived' AND at_ms>=? AND at_ms<=?");
            args.add(player); args.add(from); args.add(through);
        }
        List<JsonObject> values = new ArrayList<>(); long total = 0;
        if (!sources.isEmpty()) {
            String source = "(" + String.join(" UNION ALL ", sources) + ") acquisitions";
            total = one(c, "SELECT COUNT(*) n FROM " + source, args.toArray()).get("n").getAsLong();
            args.add((long) page * OPTIONS);
            for (JsonObject row : rows(c, "SELECT eventId FROM " + source + " ORDER BY obtainedAt DESC,eventId LIMIT 100 OFFSET ?", args.toArray())) {
                JsonObject event = resolveEvent(c, player, archive, text(row, "eventId", 128), titles, fortunes, false);
                if (event != null) values.add(event);
            }
        }
        return stamp(obj("playerId", player, "archiveId", archive.get("id"), "rows", values, "total", total, "page", page, "size", OPTIONS, "hasMore", (long) (page + 1) * OPTIONS < total));
    }
    private JsonObject resolveEvent(Connection c, String player, JsonObject archive, String event, TitleService titles, FortuneBackend fortunes, boolean lock) throws Exception {
        if (event == null) return null;
        for (JsonObject title : titleChoices(titles, player, startAt(archive), endAt(archive))) if (event.equals(text(title, "eventId", 128))) {
            if (lock) one(c, "SELECT acquired_at FROM mt_owned WHERE player=? AND title=? AND acquired_at=? FOR UPDATE", player, text(title, "id", 64), title.get("obtainedAt").getAsLong());
            return obj("eventId", event, "kind", "title", "obtainedAt", title.get("obtainedAt"), "item", title);
        }
        if (event.startsWith("fortune:") && fortunes != null) {
            JsonObject item = fortunes.communityChoiceById(c, UUID.fromString(player), event.substring(8), lock);
            if (item != null && item.get("obtainedAt").getAsLong() >= startAt(archive) && item.get("obtainedAt").getAsLong() <= endAt(archive)) return obj("eventId", event, "kind", "fortune", "obtainedAt", item.get("obtainedAt"), "item", item);
        }
        return null;
    }
    private JsonObject memory(Connection c, String instance, String player, String archiveId, boolean self, boolean editable, int page, TitleService titles, FortuneBackend fortunes) throws Exception {
        JsonObject archive = memberArchive(c, instance, player, archiveId), selected = collection(c, player, archiveId);
        List<JsonObject> items = new ArrayList<>(); JsonObject choice = selected.getAsJsonObject("selection");
        for (String key : List.of("titleEventId", "fortuneEventId")) {
            JsonObject item = resolveEvent(c, player, archive, nullable(choice, key, 128), titles, fortunes, false);
            if (item != null) items.add(item); else choice.add(key, JsonNull.INSTANCE);
        }
        selected.add("items", JSON.toJsonTree(items));
        long total = one(c, "SELECT COUNT(*) n FROM ph_community_supplements WHERE player_uuid=? AND archive_id=? AND deleted=FALSE", player, archiveId).get("n").getAsLong();
        List<JsonObject> supplements = rows(c, "SELECT id,text,created_at createdAt,updated_at updatedAt,revision FROM ph_community_supplements WHERE player_uuid=? AND archive_id=? AND deleted=FALSE ORDER BY created_at,id LIMIT 20 OFFSET ?", player, archiveId, (long) page * SUPPLEMENTS);
        long now = System.currentTimeMillis();
        for (JsonObject supplement : supplements) { long at = supplement.get("createdAt").getAsLong(); supplement.addProperty("revision", revisionOf(supplement)); supplement.addProperty("canDelete", self && now >= at && now - at < DAY); supplement.addProperty("deleteUntil", Math.addExact(at, DAY)); }
        JsonObject result = obj("playerId", player, "archiveId", archiveId, "archive", archiveDto(c, archive, player, titles, fortunes), "isSelf", self, "note", note(c, player, archiveId), "supplements", supplements, "supplementsTotal", total, "supplementsPage", page, "supplementsHasMore", (long) (page + 1) * SUPPLEMENTS < total, "collections", selected);
        if (editable) { JsonObject choices = candidatePage(c, player, archive, titles, fortunes, 0); result.add("candidates", choices.get("rows")); result.add("candidatesTotal", choices.get("total")); result.add("candidatesHasMore", choices.get("hasMore")); }
        return stamp(result);
    }

    private void audit(Connection c, String actor, String action, String player, String archive) throws Exception {
        update(c, "INSERT INTO pm_audit(at_ms,actor,action,detail) VALUES(?,?,?,?)", System.currentTimeMillis(), actor, action, obj("playerId", player, "archiveId", archive).toString());
        update(c, "UPDATE pm_profile_revisions SET revision=revision+1 WHERE uuid=?", player);
    }
    private void saveProfile(Connection c, String instance, String player, JsonObject payload, TitleService titles, FortuneBackend fortunes, String actor) throws Exception {
        JsonObject current = profile(c, instance, player, true, false, titles, fortunes).getAsJsonObject("profile");
        expect(text(current, "revision", 160), payload);
        if (!payload.has("patch") || !payload.get("patch").isJsonObject()) throw new IllegalArgumentException("名片编辑字段缺失");
        JsonObject patch = payload.getAsJsonObject("patch");
        Set<String> allowed = Set.of("bio", "background", "profilePattern", "featuredTitleId", "featuredFortuneDate", "featuredMemoryId");
        if (!allowed.containsAll(patch.keySet())) throw new IllegalArgumentException("名片包含不支持的编辑字段");
        JsonObject before = profileRow(c, player); JsonObject next = before.deepCopy();
        for (String key : patch.keySet()) next.add(key, patch.get(key));
        String bio = nullable(next, "bio", 120), background = nullable(next, "background", 16), pattern = nullable(next, "profilePattern", 24);
        if (bio == null || !COLORS.contains(background) || !PATTERNS.contains(pattern)) throw new IllegalArgumentException("名片外观参数无效");
        String titleId = nullable(next, "featuredTitleId", 64), memoryId = nullable(next, "featuredMemoryId", 36);
        if (patch.has("featuredTitleId") && titleId != null) {
            JsonObject chosen = selected(titleChoices(titles, player, 0, Long.MAX_VALUE), "id", titleId);
            if (chosen == null) throw new IllegalArgumentException("只能选择这位玩家已获得的称号");
            one(c, "SELECT acquired_at FROM mt_owned WHERE player=? AND title=? AND acquired_at=? FOR UPDATE", player, titleId, chosen.get("obtainedAt").getAsLong());
        }
        if (patch.has("featuredMemoryId") && memoryId != null) memberArchive(c, instance, player, memoryId);
        if (patch.has("featuredFortuneDate")) {
            String date = nullable(patch, "featuredFortuneDate", 10);
            if (fortunes == null) { if (date != null) throw new IllegalStateException("签笺服务未启用"); }
            else {
                if (date != null && fortune(c, fortunes, player, date, true) == null) throw new IllegalArgumentException("只能选择这位玩家已归档的签笺");
                JsonObject prior = preference(c, player, fortunes);
                PlatformStore.valueInTransaction(c, "dailyfortunes", obj("player", player, "field", "featured_date", "value", date == null ? "" : date, "expectedRevision", prior.get("revision")), true);
            }
        }
        update(c, "INSERT INTO ph_community_profiles(player_uuid,bio,background,pattern,featured_title,featured_memory,revision,updated_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE bio=VALUES(bio),background=VALUES(background),pattern=VALUES(pattern),featured_title=VALUES(featured_title),featured_memory=VALUES(featured_memory),revision=VALUES(revision),updated_at=VALUES(updated_at)", player, bio.strip(), background, pattern, titleId, memoryId, before.get("revision").getAsLong() + 1, System.currentTimeMillis());
        audit(c, actor, "community.profile.save", player, null);
    }

    private boolean mutateMemory(Connection c, String instance, String player, String action, JsonObject payload, boolean admin, String actor, TitleService titles, FortuneBackend fortunes) throws Exception {
        String archiveId = uuid(text(payload, "archiveId", 36));
        JsonObject archive = memberArchive(c, instance, player, archiveId); long now = System.currentTimeMillis();
        switch (action) {
            case "saveNote", "deleteNote" -> {
                if (!admin && !truth(archive, "isCurrent")) throw new SecurityException("已结束的旅程请使用补记");
                JsonObject old = note(c, player, archiveId); expect(revisionOf(old), payload);
                boolean deleted = action.equals("deleteNote");
                if (deleted && (!admin || !bool(payload, "confirm", false))) throw new SecurityException("删除原文需要管理员确认");
                update(c, "INSERT INTO ph_community_notes(player_uuid,archive_id,text,deleted,revision,updated_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE text=VALUES(text),deleted=VALUES(deleted),revision=VALUES(revision),updated_at=VALUES(updated_at)", player, archiveId, deleted ? "" : content(payload, 1200), deleted, Long.parseLong(revisionOf(old)) + 1, now);
            }
            case "appendSupplement" -> {
                if (truth(archive, "isCurrent")) throw new SecurityException("当前旅程可以直接编辑回忆");
                String text = content(payload, 600), operation = uuid(text(payload, "operationId", 36));
                String supplementId = UUID.nameUUIDFromBytes((player + "\n" + archiveId + "\n" + operation).getBytes(StandardCharsets.UTF_8)).toString();
                var prior = rows(c, "SELECT request_hash,deleted FROM ph_community_supplements WHERE id=?", supplementId);
                if (!prior.isEmpty()) {
                    if (truth(prior.get(0), "deleted")) throw new IllegalStateException("补记已删除，请新建一条补记");
                    if (!text(prior.get(0), "request_hash", 64).equals(digest(text))) throw new IllegalStateException("同一补记提交不能更换正文");
                    return false;
                }
                update(c, "INSERT INTO ph_community_supplements(id,player_uuid,archive_id,text,request_hash,deleted,revision,created_at,updated_at) VALUES(?,?,?,?,?,FALSE,1,?,?)", supplementId, player, archiveId, text, digest(text), now, now);
            }
            case "saveSupplement", "deleteSupplement" -> {
                String supplementId = uuid(text(payload, "supplementId", 36));
                JsonObject old = one(c, "SELECT revision,created_at createdAt,deleted FROM ph_community_supplements WHERE id=? AND player_uuid=? AND archive_id=? FOR UPDATE", supplementId, player, archiveId);
                expect(revisionOf(old), payload);
                if (truth(old, "deleted")) throw new IllegalStateException("这条补记已经删除");
                boolean deleted = action.equals("deleteSupplement");
                if (!admin && (!deleted || now < old.get("createdAt").getAsLong() || now - old.get("createdAt").getAsLong() >= DAY)) throw new SecurityException("补记已超过本人可删除期限");
                if (admin && deleted && !bool(payload, "confirm", false)) throw new IllegalArgumentException("请确认删除这条补记");
                update(c, "UPDATE ph_community_supplements SET text=?,deleted=?,revision=revision+1,updated_at=? WHERE id=?", deleted ? "" : content(payload, 600), deleted, now, supplementId);
            }
            case "saveCollections" -> {
                JsonObject old = collection(c, player, archiveId); expect(revisionOf(old), payload);
                if (!payload.has("selection") || !payload.get("selection").isJsonObject()) throw new IllegalArgumentException("展示选择缺失");
                JsonObject selected = payload.getAsJsonObject("selection");
                if (!selected.keySet().equals(Set.of("titleEventId", "fortuneEventId"))) throw new IllegalArgumentException("展示选择字段无效");
                String title = nullable(selected, "titleEventId", 128), fortune = nullable(selected, "fortuneEventId", 128);
                for (String[] pair : new String[][]{{"title", title}, {"fortune", fortune}}) if (pair[1] != null) {
                    JsonObject event = resolveEvent(c, player, archive, pair[1], titles, fortunes, true);
                    if (event == null || !pair[0].equals(text(event, "kind", 16))) throw new IllegalArgumentException("展示条目必须在该整合包期间获得");
                }
                update(c, "INSERT INTO ph_community_collections(player_uuid,archive_id,title_event,fortune_event,revision,updated_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title_event=VALUES(title_event),fortune_event=VALUES(fortune_event),revision=VALUES(revision),updated_at=VALUES(updated_at)", player, archiveId, title, fortune, Long.parseLong(revisionOf(old)) + 1, now);
            }
            default -> throw new IllegalArgumentException("未注册的社区操作");
        }
        audit(c, actor, "community." + action, player, archiveId);
        return true;
    }

    public JsonObject player(String instance, UUID viewer, String action, JsonObject payload, TitleService titles, FortuneBackend fortunes) throws Exception {
        validatePlayer(action, payload);
        String target = payload.has("playerId") ? uuid(text(payload, "playerId", 36)) : viewer.toString();
        boolean self = target.equals(viewer.toString()), write = Set.of("saveProfile", "saveNote", "appendSupplement", "deleteSupplement", "saveCollections").contains(action);
        boolean[] committed = {write};
        JsonObject result = transaction(c -> {
            publicTarget(c, instance, target);
            if (write) identity(c, target, true);
            if (action.equals("saveProfile")) saveProfile(c, instance, target, payload, titles, fortunes, viewer.toString());
            else if (write) committed[0] = mutateMemory(c, instance, target, action, payload, false, viewer.toString(), titles, fortunes);
            return switch (action) {
                case "snapshot" -> { JsonObject own = profile(c, instance, target, true, true, titles, fortunes); own.add("memories", memories(c, instance, target, true, 0, titles, fortunes)); yield own; }
                case "profile", "saveProfile" -> profile(c, instance, target, self, self, titles, fortunes);
                case "profileOptions" -> options(c, instance, target, text(payload, "kind", 16), page(payload), titles, fortunes);
                case "memories" -> memories(c, instance, target, self, page(payload), titles, fortunes);
                case "memoryOptions" -> candidatePage(c, target, memberArchive(c, instance, target, text(payload, "archiveId", 36)), titles, fortunes, page(payload));
                default -> memory(c, instance, target, text(payload, "archiveId", 36), self, self, write ? 0 : page(payload), titles, fortunes);
            };
        });
        if (committed[0]) changed(); return stamp(result);
    }

    public static void validatePlayer(String action, JsonObject p) {
        Set<String> fields = switch (action) {
            case "snapshot" -> Set.of();
            case "profile" -> Set.of("playerId");
            case "profileOptions" -> Set.of("kind", "page");
            case "memories" -> Set.of("playerId", "page");
            case "memory" -> Set.of("playerId", "archiveId", "page");
            case "memoryOptions" -> Set.of("archiveId", "page");
            case "saveProfile" -> Set.of("patch", "expectedRevision");
            case "saveNote" -> Set.of("archiveId", "text", "expectedRevision");
            case "appendSupplement" -> Set.of("archiveId", "text", "operationId");
            case "deleteSupplement" -> Set.of("archiveId", "supplementId", "expectedRevision");
            case "saveCollections" -> Set.of("archiveId", "selection", "expectedRevision");
            default -> throw new IllegalArgumentException("社区请求未注册");
        };
        if (!fields.containsAll(p.keySet()) || p.toString().getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException("社区请求字段无效");
        for (var entry : p.entrySet()) {
            if (Set.of("patch", "selection").contains(entry.getKey())) { if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("社区编辑格式无效"); }
            else if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("社区参数类型无效");
        }
        for (String key : List.of("operationId", "supplementId")) if (fields.contains(key)) requiredUuid(nullable(p, key, 36));
        if (p.has("playerId")) requiredUuid(nullable(p, "playerId", 36));
        if (fields.contains("archiveId")) uuid(text(p, "archiveId", 36));
        if (p.has("page")) number(p, "page", 0, 0, 100000);
        if (fields.contains("expectedRevision") && (!p.has("expectedRevision") || text(p, "expectedRevision", 160).isEmpty())) throw new IllegalArgumentException("社区修订缺失");
    }

    private static void validateAdmin(String path, JsonObject payload) {
        Set<String> fields = switch (path) {
            case "/save-profile" -> Set.of("playerId", "patch", "expectedRevision");
            case "/save-note" -> Set.of("playerId", "archiveId", "text", "expectedRevision");
            case "/delete-note" -> Set.of("playerId", "archiveId", "confirm", "expectedRevision");
            case "/save-supplement" -> Set.of("playerId", "archiveId", "supplementId", "text", "expectedRevision");
            case "/delete-supplement" -> Set.of("playerId", "archiveId", "supplementId", "confirm", "expectedRevision");
            case "/save-collections" -> Set.of("playerId", "archiveId", "selection", "expectedRevision");
            default -> throw new IllegalArgumentException("管理员操作未注册");
        };
        if (!fields.equals(payload.keySet())) throw new IllegalArgumentException("管理员编辑字段无效");
        for (String key : List.of("playerId", "archiveId", "supplementId")) if (fields.contains(key)) requiredUuid(nullable(payload, key, 36));
        if (fields.contains("confirm") && (!payload.get("confirm").isJsonPrimitive() || !payload.getAsJsonPrimitive("confirm").isBoolean() || !payload.get("confirm").getAsBoolean())) throw new IllegalArgumentException("删除需要明确确认");
    }

    public JsonObject admin(String instance, String method, String path, JsonObject payload, Map<String,String> query, String actor, TitleService titles, FortuneBackend fortunes, Set<UUID> online) throws Exception {
        boolean write = method.equals("POST");
        if (write) validateAdmin(path, payload);
        if (!write && !method.equals("GET")) throw new IllegalArgumentException("仅支持GET或POST");
        String player = write ? nullable(payload, "playerId", 36) : query.get("playerId");
        if (player != null) player = uuid(player);
        final String target = player;
        JsonObject result = transaction(c -> {
            if (!write && path.equals("/players")) return players(c, instance, null, query, titles, fortunes, online);
            if (!write && path.equals("/archives")) return adminArchives(c, instance, query);
            if (!write && path.equals("/archive-players")) return players(c, instance, requiredUuid(query.get("archiveId")), query, titles, fortunes, online);
            if (target == null) throw new IllegalArgumentException("请选择玩家");
            identity(c, target, write);
            String archiveId = write ? nullable(payload, "archiveId", 36) : query.get("archiveId");
            if (write) {
                String action = switch (path) { case "/save-profile" -> "saveProfile"; case "/save-note" -> "saveNote"; case "/delete-note" -> "deleteNote"; case "/save-supplement" -> "saveSupplement"; case "/delete-supplement" -> "deleteSupplement"; case "/save-collections" -> "saveCollections"; default -> throw new IllegalArgumentException("管理员操作未注册"); };
                if (action.equals("saveProfile")) { saveProfile(c, null, target, payload, titles, fortunes, actor); return profile(c, null, target, false, true, titles, fortunes); }
                mutateMemory(c, null, target, action, payload, true, actor, titles, fortunes);
                return memory(c, null, target, archiveId, false, true, 0, titles, fortunes);
            }
            return switch (path) {
                case "/profile" -> profile(c, null, target, false, true, titles, fortunes);
                case "/profile-options" -> options(c, null, target, query.get("kind"), integer(query, "page", 0, 0, 100000), titles, fortunes);
                case "/memory" -> memory(c, null, target, archiveId, false, true, integer(query, "page", 0, 0, 100000), titles, fortunes);
                case "/memory-options" -> candidatePage(c, target, memberArchive(c, null, target, archiveId), titles, fortunes, integer(query, "page", 0, 0, 100000));
                default -> throw new IllegalArgumentException("社区查询未注册");
            };
        });
        if (write) changed(); return stamp(result);
    }
    private JsonObject adminArchives(Connection c, String instance, Map<String,String> query) throws Exception {
        int page = integer(query, "page", 0, 0, 100000), size = 100;
        long total = one(c, "SELECT COUNT(*) n FROM pm_archives WHERE instance_id=?", instance).get("n").getAsLong();
        var values = rows(c, "SELECT a.id,a.name,a.version,CAST(a.start_date AS CHAR) start,a.end_at endedAt,(i.active_archive=a.id AND a.status='active') isCurrent,(SELECT COUNT(*) FROM pm_players p WHERE p.archive_id=a.id AND p.join_count>0) playersCount FROM pm_archives a JOIN pm_instances i ON i.id=a.instance_id WHERE a.instance_id=? ORDER BY isCurrent DESC,a.start_date DESC,a.id LIMIT 100 OFFSET ?", instance, (long) page * size);
        for (JsonObject value : values) { value.addProperty("isCurrent", truth(value, "isCurrent")); value.add("end", value.get("endedAt").isJsonNull() ? JsonNull.INSTANCE : new JsonPrimitive(date(value.get("endedAt").getAsLong()))); }
        return obj("rows", values, "total", total, "page", page, "size", size, "hasMore", (long) (page + 1) * size < total);
    }
    private JsonObject players(Connection c, String instance, String archiveId, Map<String,String> query, TitleService titles, FortuneBackend fortunes, Set<UUID> online) throws Exception {
        int page = integer(query, "page", 0, 0, 100000), size = integer(query, "size", 20, 1, 100);
        String search = query.getOrDefault("search", "").strip(), presence = query.getOrDefault("presence", "all");
        if (search.length() > 120 || !Set.of("all", "online", "offline").contains(presence)) throw new IllegalArgumentException("玩家过滤参数无效");
        String members = archiveId == null ? "" : " AND EXISTS(SELECT 1 FROM pm_players member WHERE member.uuid=p.uuid AND member.archive_id=? AND member.join_count>0)";
        List<Object> args = new ArrayList<>(); if (archiveId != null) { one(c, "SELECT id FROM pm_archives WHERE id=? AND instance_id=?", archiveId, instance); args.add(archiveId); }
        String valid = "NOT EXISTS(SELECT 1 FROM pm_profile_exclusions x WHERE x.uuid=p.uuid)" + members;
        String onlineCondition = online.isEmpty() ? "FALSE" : "p.uuid IN(" + String.join(",", Collections.nCopies(online.size(), "?")) + ")";
        List<Object> countArgs = new ArrayList<>(); for (UUID id : online) countArgs.add(id.toString()); countArgs.addAll(args);
        JsonObject counts = one(c, "SELECT COUNT(*) allCount,COALESCE(SUM(" + onlineCondition + "),0) onlineCount FROM pm_profiles p WHERE " + valid, countArgs.toArray());
        long totalAll = counts.get("allCount").getAsLong(), totalOnline = counts.get("onlineCount").getAsLong();
        List<Object> whereArgs = new ArrayList<>(args);
        String filter = valid + " AND CONCAT(p.name,' ',p.uuid) LIKE ?";
        whereArgs.add("%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        if (!presence.equals("all")) { filter += " AND " + (presence.equals("online") ? "" : "NOT ") + "(" + onlineCondition + ")"; for (UUID id : online) whereArgs.add(id.toString()); }
        long total = one(c, "SELECT COUNT(*) n FROM pm_profiles p WHERE " + filter, whereArgs.toArray()).get("n").getAsLong();
        String sort = query.getOrDefault("sort", "status"), direction = query.getOrDefault("direction", "asc").equals("desc") ? "DESC" : "ASC";
        String lastLogin = archiveId == null ? "p.last_seen" : "(SELECT member.last_join FROM pm_players member WHERE member.uuid=p.uuid AND member.archive_id='" + requiredUuid(archiveId) + "')";
        String titleLabel = titles == null || !titles.healthy() ? "''" : "(SELECT GROUP_CONCAT(segment.text ORDER BY segment.position SEPARATOR '') FROM mt_titles t JOIN mt_owned own ON own.title=t.id AND own.player=p.uuid JOIN JSON_TABLE(t.definition,'$.name.segments[*]' COLUMNS(position FOR ORDINALITY,text VARCHAR(320) PATH '$.text')) segment WHERE t.id=cp.featured_title)";
        String fortuneLabel = fortunes == null ? "''" : "(SELECT JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.snapshot.title')) FROM pm_values v JOIN df_records r ON r.player=v.uuid AND r.day=JSON_UNQUOTE(v.value) AND r.state='archived' WHERE v.namespace='dailyfortunes' AND v.uuid=p.uuid AND v.scope_id='' AND v.field_id='featured_date')";
        String collectionLabel = "CONCAT_WS(' / ',NULLIF(" + titleLabel + ",''),NULLIF(" + fortuneLabel + ",''))";
        String order = switch (sort) { case "name" -> "p.name " + direction; case "bio" -> "COALESCE(cp.bio,'') " + direction; case "memory" -> "(selected.name IS NULL),selected.name " + direction; case "collection" -> "(collectionLabel=''),collectionLabel " + direction; case "lastLogin" -> lastLogin + " " + direction; case "status" -> "isOnline " + (direction.equals("ASC") ? "DESC" : "ASC"); default -> throw new IllegalArgumentException("玩家排序字段无效"); };
        List<Object> resultArgs = new ArrayList<>(); for (UUID id : online) resultArgs.add(id.toString()); resultArgs.addAll(whereArgs); resultArgs.add(size); resultArgs.add((long) page * size);
        var values = rows(c, "SELECT p.uuid playerId,p.name,p.first_join joinedAt," + lastLogin + " lastLoginAt," + collectionLabel + " collectionLabel," + onlineCondition + " isOnline FROM pm_profiles p LEFT JOIN ph_community_profiles cp ON cp.player_uuid=p.uuid LEFT JOIN pm_archives selected ON selected.id=cp.featured_memory WHERE " + filter + " ORDER BY " + order + ",p.name,p.uuid LIMIT ? OFFSET ?", resultArgs.toArray());
        for (JsonObject value : values) { value.remove("collectionLabel"); value.addProperty("isOnline", truth(value, "isOnline")); value.add("profile", profile(c, null, text(value, "playerId", 36), false, false, titles, fortunes).get("profile")); }
        return obj("rows", values, "total", total, "page", page, "size", size, "hasMore", (long) (page + 1) * size < total, "presenceCounts", obj("all", totalAll, "online", totalOnline, "offline", totalAll - totalOnline), "backgrounds", backgrounds(), "patterns", patterns());
    }
    public static List<JsonObject> backgrounds() {
        String[][] entries = {{"mist","雾蓝","#c8d6ec"},{"lilac","浅藤","#d9cfe9"},{"sage","青苔","#ccdcd2"},{"sand","麦穗","#e2d7c3"},{"blush","蔷薇","#e6ced5"},{"apricot","杏光","#e7d1bd"},{"tide","浅汐","#c2dcd9"},{"twilight","暮云","#d0cfe1"},{"frost","霜青","#cedee4"},{"stone","雨石","#d5d6d2"}};
        return Arrays.stream(entries).map(value -> obj("id", value[0], "name", value[1], "color", value[2])).toList();
    }
    public static List<JsonObject> patterns() {
        String[][] entries = {{"classic","流光"},{"doodle","云间露营"},{"tech","星际电路"},{"architecture","方块小城"},{"nature","风过叶梢"},{"cosmos","星河漫游"}};
        return Arrays.stream(entries).map(value -> obj("id", value[0], "name", value[1])).toList();
    }
}
