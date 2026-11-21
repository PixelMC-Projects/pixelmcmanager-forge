package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

final class QuestGameplay {
    private static final String QUEST = "dev.ftb.mods.ftbquests.quest.";
    private static final String TEAMS = "dev.ftb.mods.ftbteams.api.";
    private static final Pattern FORMATTING = Pattern.compile("(?i)&[0-9a-fk-or]");
    private final Class<?> teamType = Class.forName(QUEST + "TeamData");
    private final Class<?> objectType = Class.forName(QUEST + "QuestObject");
    private final Class<?> questType = Class.forName(QUEST + "Quest");
    private final Class<?> linkType = Class.forName(QUEST + "QuestLink");
    private final Class<?> taskType = Class.forName(QUEST + "task.Task");
    private final Class<?> rewardType = Class.forName(QUEST + "reward.Reward");
    private final Class<?> randomType = Class.forName(QUEST + "reward.RandomReward");
    private final Class<?> itemRewardType = Class.forName(QUEST + "reward.ItemReward");
    private final Class<?> itemTaskType = Class.forName(QUEST + "task.ItemTask");
    private final Object file;
    private final Object team;
    private final UUID player;
    private final boolean hideExcluded;
    private final Set<Object> activeTables = Collections.newSetFromMap(new IdentityHashMap<>());
    private int unresolvedRewards;

    private QuestGameplay(ServerPlayer sender) throws ReflectiveOperationException {
        player = sender.getUUID();
        file = Class.forName(QUEST + "ServerQuestFile").getField("INSTANCE").get(null);
        team = playerTeam(file, player);
        hideExcluded = file != null && (boolean) call(file, "isHideExcludedQuests");
    }

    static Object playerTeam(Object file, UUID player) throws ReflectiveOperationException {
        if (file == null) {
            return null;
        }
        Object api = Class.forName(TEAMS + "FTBTeamsAPI").getMethod("api").invoke(null);
        Object manager =
                Class.forName(TEAMS + "FTBTeamsAPI$API").getMethod("getManager").invoke(api);
        if (manager == null) {
            return null;
        }
        Optional<?> current =
                (Optional<?>)
                        Class.forName(TEAMS + "TeamManager")
                                .getMethod("getTeamForPlayerID", UUID.class)
                                .invoke(manager, player);
        if (current.isEmpty()) {
            return null;
        }
        UUID teamId = (UUID) Class.forName(TEAMS + "Team").getMethod("getId").invoke(current.get());
        // The normal FTB getter creates missing team data. A query only reads a loaded team.
        return call(file, "getNullableTeamData", UUID.class, teamId);
    }

    static JsonObject read(ServerPlayer sender, String kind, String target, int page)
            throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("ftbquests")) {
            return missing("FTB Quests（ftbquests）");
        }
        var reader = new QuestGameplay(sender);
        if (reader.team == null) {
            return result("unavailable", "本人的任务手册数据尚未加载。");
        }
        return personal(reader.query(kind, target, page));
    }

    private boolean visible(Object object) throws ReflectiveOperationException {
        if (!(boolean) call(object, "isSearchable", teamType, team)) {
            return false;
        }
        if (linkType.isInstance(object)) {
            var referenced = (Optional<?>) call(object, "getQuest");
            return referenced.isPresent()
                    && visible(referenced.get())
                    && !(boolean) call(call(object, "getChapter"), "isAlwaysInvisible");
        }
        return !questType.isInstance(object)
                || !hideExcluded
                || !(boolean) call(object, "isQuestObjectExcluded", teamType, team);
    }

    private boolean flag(Object object, String method) throws ReflectiveOperationException {
        return (boolean) call(team, method, objectType, object);
    }

    private boolean canStart(Object quest) throws ReflectiveOperationException {
        return (boolean) call(team, "canStartTasks", questType, quest);
    }

    private boolean open(Object quest) throws ReflectiveOperationException {
        return !(boolean) call(quest, "hideDetailsUntilStartable") || canStart(quest);
    }

    private boolean showReward(Object reward) throws ReflectiveOperationException {
        return !(boolean) call(team, "isRewardBlocked", rewardType, reward)
                && !((Enum<?>) call(reward, "getAutoClaimType")).name().equals("INVISIBLE");
    }

    private String claimState(Object reward) throws ReflectiveOperationException {
        return ((Enum<?>)
                        teamType.getMethod("getClaimType", UUID.class, rewardType)
                                .invoke(team, player, reward))
                .name();
    }

    private boolean canClaim(String state) throws ReflectiveOperationException {
        return state.equals("CAN_CLAIM") && !(boolean) call(team, "isLocked");
    }

    private String title(Object object) throws ReflectiveOperationException {
        String raw = (String) call(object, "getRawTitle");
        if (!raw.isBlank()) {
            return rawText(raw);
        }
        if (questType.isInstance(object)) {
            Iterator<?> tasks = ((Collection<?>) call(object, "getTasks")).iterator();
            return tasks.hasNext() ? title(tasks.next()) : "未命名任务";
        }
        if (itemTaskType.isInstance(object)) {
            return text(((ItemStack) call(object, "getItemStack")).getHoverName());
        }
        if (itemRewardType.isInstance(object)) {
            return text(((ItemStack) call(object, "getItem")).getHoverName());
        }
        if (taskType.isInstance(object) || rewardType.isInstance(object)) {
            return call(call(object, "getType"), "getTypeId").toString();
        }
        if (linkType.isInstance(object)) {
            var referenced = (Optional<?>) call(object, "getQuest");
            return referenced.isPresent() ? title(referenced.get()) : "任务链接";
        }
        return "未命名章节";
    }

    private JsonObject identity(Object object) throws ReflectiveOperationException {
        var result = row("id", (String) call(object, "getCodeString"));
        result.addProperty("title", shortText(title(object)));
        return result;
    }

    private JsonObject query(String kind, String target, int page)
            throws ReflectiveOperationException {
        if (kind.equals("quest_detail")) {
            if (!target.matches("(?i)[0-9a-f]{1,16}")) {
                return result("invalid_request", "请用任务检索返回的 ID。");
            }
            Object quest = call(file, "getQuest", long.class, Long.parseUnsignedLong(target, 16));
            if (quest == null || !visible(quest)) {
                return result("not_found", "在本人可见任务中未找到。");
            }
            return detail(quest, page);
        }

        boolean rewards = kind.equals("quest_rewards");
        ResourceLocation item = rewards ? ResourceLocation.tryParse(target) : null;
        if (rewards && (item == null || !ForgeRegistries.ITEMS.containsKey(item))) {
            return result("invalid_request", "请用已注册的物品 ID 反查任务奖励。");
        }
        var rows = new PageWindow(page);
        String needle = target.toLowerCase(Locale.ROOT);
        boolean all = target.equals("*");
        for (Object chapter : (Collection<?>) call(file, "getAllChapters")) {
            if ((boolean) call(chapter, "isAlwaysInvisible")) {
                continue;
            }
            String chapterTitle = title(chapter);
            boolean chapterMatch = all || chapterTitle.toLowerCase(Locale.ROOT).contains(needle);
            for (Object quest : (Collection<?>) call(chapter, "getQuests")) {
                if (!visible(quest)) {
                    continue;
                }
                if (rewards) {
                    if (open(quest)) {
                        for (Object reward : (Collection<?>) call(quest, "getRewards")) {
                            if (showReward(reward)) {
                                addReward(
                                        rows, quest, chapterTitle, reward, reward, item, "", 0, 0);
                            }
                        }
                    }
                    continue;
                }

                boolean idMatch =
                        !chapterMatch
                                && HexFormat.of()
                                        .toHexDigits((long) call(quest, "getId"))
                                        .contains(needle);
                if ((chapterMatch
                                || idMatch
                                || title(quest).toLowerCase(Locale.ROOT).contains(needle))
                        && rows.includeNext()) {
                    var summary = identity(quest);
                    summary.addProperty("chapter", shortText(chapterTitle));
                    summary.addProperty("completed", flag(quest, "isCompleted"));
                    rows.entries.add(summary);
                }
            }
        }
        var result =
                rows.result(rewards ? "本人可见任务的已知奖励来源。" : "仅列提问者可见任务。用任务 ID 查看详情、前置与每项奖励的领取状态。");
        if (rewards && unresolvedRewards > 0) {
            result.addProperty("unresolved_rewards", unresolvedRewards);
        }
        return result;
    }

    private void addReward(
            PageWindow rows,
            Object quest,
            String chapterTitle,
            Object reward,
            Object parent,
            ResourceLocation item,
            String selection,
            double weight,
            int depth)
            throws ReflectiveOperationException {
        boolean isItem = itemRewardType.isInstance(reward);
        ItemStack stack = isItem ? (ItemStack) call(reward, "getItem") : ItemStack.EMPTY;
        ResourceLocation id =
                stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
        String type = reward.getClass().getSimpleName();
        if ((item == null || item.equals(id)) && rows.includeNext()) {
            var entry = identity(reward);
            entry.addProperty("reward_type", type);
            if (id != null) {
                entry.addProperty("item", id.toString());
                entry.addProperty("count", (int) call(reward, "getCount"));
                var tag = new CompoundTag();
                call(reward, "writeData", CompoundTag.class, tag);
                if (tag.getInt("random_bonus") > 0) {
                    entry.addProperty("random_bonus", tag.getInt("random_bonus"));
                }
            }
            if (!selection.isEmpty()) {
                entry.addProperty("selection", selection);
                entry.addProperty("weight", weight);
                entry.addProperty("claim_scope", "parent_reward");
                entry.addProperty("parent_reward_id", (String) call(parent, "getCodeString"));
            }
            String state = claimState(parent);
            String claimPrefix = selection.isEmpty() ? "" : "parent_";
            entry.addProperty(claimPrefix + "claim_state", state);
            entry.addProperty(claimPrefix + "claimed", state.equals("CLAIMED"));
            entry.addProperty(claimPrefix + "can_claim", canClaim(state));
            entry.addProperty("team_reward", (boolean) call(parent, "isTeamReward"));
            if (item != null) {
                entry.addProperty("quest_id", (String) call(quest, "getCodeString"));
                entry.addProperty("quest", shortText(title(quest)));
                entry.addProperty("chapter", shortText(chapterTitle));
            } else {
                entry.addProperty("section", "reward");
            }
            rows.entries.add(entry);
        }
        if (!randomType.isInstance(reward)) {
            if (item != null
                    && (type.equals("CommandReward")
                            || type.equals("CustomReward")
                            || !reward.getClass().getName().startsWith(QUEST + "reward."))) {
                unresolvedRewards++;
            }
            return;
        }
        Object table = call(reward, "getTable");
        if (table == null || depth >= 32 || !activeTables.add(table)) {
            unresolvedRewards++;
            return;
        }
        try {
            String next = selection.isEmpty() ? type : selection + "/" + type;
            boolean selectable =
                    reward == parent && type.equals("ChoiceReward") && canClaim(claimState(parent));
            if ((boolean) call(table, "shouldShowTooltip") || selectable) {
                for (Object weighted : (Collection<?>) call(table, "getWeightedRewards")) {
                    addReward(
                            rows,
                            quest,
                            chapterTitle,
                            call(weighted, "getReward"),
                            parent,
                            item,
                            next,
                            ((Number) call(weighted, "getWeight")).doubleValue(),
                            depth + 1);
                }
            } else {
                unresolvedRewards++;
            }
        } finally {
            activeTables.remove(table);
        }
    }

    private JsonObject detail(Object quest, int page) throws ReflectiveOperationException {
        var summary = identity(quest);
        summary.addProperty("chapter", shortText(title(call(quest, "getChapter"))));
        boolean complete = flag(quest, "isCompleted");
        boolean canStart = canStart(quest);
        summary.addProperty("completed", complete);
        summary.addProperty("started", flag(quest, "isStarted"));
        summary.addProperty("can_start", canStart);
        summary.addProperty("repeatable", (boolean) call(quest, "canBeRepeated"));
        summary.addProperty("optional", (boolean) call(quest, "isOptional"));
        var rows = new PageWindow(page);
        if (canStart || !(boolean) call(quest, "hideDetailsUntilStartable")) {
            summary.addProperty(
                    "minimum_dependencies", (int) call(quest, "getMinRequiredDependencies"));
            summary.addProperty(
                    "progression_mode", ((Enum<?>) call(quest, "getProgressionMode")).name());
            summary.addProperty(
                    "dependencies_complete",
                    (boolean) call(team, "areDependenciesComplete", questType, quest));
            addText(rows, "subtitle", rawText((String) call(quest, "getRawSubtitle")));
            Object hide = call(quest, "getHideTextUntilComplete");
            if (complete || !(boolean) call(hide, "get", boolean.class, false)) {
                for (Object line : (Collection<?>) call(quest, "getRawDescription")) {
                    addText(rows, "description", rawText((String) line));
                }
                addText(rows, "guide", (String) call(quest, "getGuidePage"));
            }
            addTasks(rows, quest);
            for (Object reward : (Collection<?>) call(quest, "getRewards")) {
                if (showReward(reward)) {
                    addReward(rows, quest, "", reward, reward, null, "", 0, 0);
                }
            }
            try (var dependencies = (Stream<?>) call(quest, "streamDependencies")) {
                addRelations(rows, dependencies.iterator(), "dependency");
            }
            addRelations(
                    rows, ((Collection<?>) call(quest, "getDependants")).iterator(), "unlocks");
        } else {
            summary.addProperty("details_locked", true);
        }
        var result = rows.result("本人当前任务页面；奖励逐项标记是否已领。");
        result.add("quest", summary);
        return result;
    }

    private void addTasks(PageWindow rows, Object quest) throws ReflectiveOperationException {
        boolean sequential = (boolean) call(quest, "getRequireSequentialTasks");
        for (Object task : (Collection<?>) call(quest, "getTasks")) {
            boolean complete = flag(task, "isCompleted");
            if (rows.includeNext()) {
                var entry = identity(task);
                entry.addProperty("section", "task");
                entry.addProperty("type", task.getClass().getSimpleName());
                putLong(entry, "progress", (long) call(team, "getProgress", taskType, task));
                putLong(entry, "required", (long) call(task, "getMaxProgress"));
                entry.addProperty("completed", complete);
                if (itemTaskType.isInstance(task)) {
                    var stack = (ItemStack) call(task, "getItemStack");
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (!stack.isEmpty() && id != null) {
                        entry.addProperty("item", id.toString());
                    }
                    entry.addProperty("consume_items", (boolean) call(task, "consumesResources"));
                    entry.addProperty(
                            "only_from_crafting", (boolean) call(task, "isOnlyFromCrafting"));
                    entry.addProperty("task_screen_only", (boolean) call(task, "isTaskScreenOnly"));
                }
                rows.entries.add(entry);
            }
            if (sequential && !complete) {
                break;
            }
        }
    }

    private void addRelations(PageWindow rows, Iterator<?> relations, String section)
            throws ReflectiveOperationException {
        while (relations.hasNext()) {
            Object relation = relations.next();
            if (visible(relation) && rows.includeNext()) {
                if (linkType.isInstance(relation)) {
                    relation = ((Optional<?>) call(relation, "getQuest")).orElseThrow();
                }
                var entry = identity(relation);
                entry.addProperty("section", section);
                entry.addProperty("completed", flag(relation, "isCompleted"));
                rows.entries.add(entry);
            }
        }
    }

    private static String rawText(String raw) {
        // FTB's title and description parsers use client classes. The network-facing output is
        // plain text.
        String value = raw.stripLeading();
        if (value.startsWith("{") || value.startsWith("[")) {
            try {
                Component component = Component.Serializer.fromJson(value);
                if (component != null) {
                    return text(component);
                }
            } catch (JsonParseException ignored) {
                // Some authored plain text begins with a bracket.
            }
        }
        return text(FORMATTING.matcher(raw).replaceAll(""));
    }

    static void addText(PageWindow rows, String section, String text) {
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(start + 300, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            if (rows.includeNext()) {
                var entry = row("section", section);
                entry.addProperty("text", text.substring(start, end));
                rows.entries.add(entry);
            }
            start = end;
        }
    }
}
