package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.network.chat.Component;

final class GameplayData {
    private static final int PAGE_SIZE = 8;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    static JsonObject result(String status, String message) {
        var r = new JsonObject();
        r.addProperty("status", status);
        r.addProperty("message", message);
        return r;
    }

    static JsonObject missing(String mod) {
        return result("mod_not_installed", "当前服务器未安装 " + mod + "。");
    }

    static JsonObject page(List<JsonObject> rows, int page, String text) {
        long offset = (page - 1L) * PAGE_SIZE;
        if (page < 1 || page > 1 && offset >= rows.size()) return result("invalid_page", "页码超出范围。");
        return page(
                rows.subList((int) offset, (int) Math.min(offset + PAGE_SIZE, rows.size())),
                page,
                rows.size(),
                text);
    }

    private static JsonObject page(List<JsonObject> current, int page, long total, String text) {
        long offset = (page - 1L) * PAGE_SIZE;
        if (page < 1 || page > 1 && offset >= total) {
            return result("invalid_page", "页码超出范围。");
        }
        var out = result("live", text);
        var entries = new JsonArray();
        current.forEach(entries::add);
        out.add("entries", entries);
        out.addProperty("total", total);
        out.addProperty("page", page);
        out.addProperty("more", offset + PAGE_SIZE < total);
        return out;
    }

    static final class PageWindow {
        private final int page;
        private final long offset;
        private long total;
        final List<JsonObject> entries = new ArrayList<>(PAGE_SIZE);

        PageWindow(int page) {
            this.page = page;
            this.offset = (page - 1L) * PAGE_SIZE;
        }

        boolean includeNext() {
            long index = total++;
            return index >= offset && index < offset + PAGE_SIZE;
        }

        JsonObject result(String message) {
            return page(entries, page, total, message);
        }
    }

    static void putLong(JsonObject object, String name, long value) {
        if (value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER) {
            object.addProperty(name, value);
        } else {
            object.addProperty(name, Long.toString(value));
        }
    }

    static JsonObject row(String key, String value) {
        var row = new JsonObject();
        row.addProperty(key, value);
        return row;
    }

    static String text(Object v) {
        String s = v instanceof Component c ? c.getString() : Objects.toString(v, "");
        return Objects.toString(net.minecraft.ChatFormatting.stripFormatting(s), "");
    }

    static String shortText(Object v) {
        String s = text(v);
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    static Object field(Object obj, String name) throws ReflectiveOperationException {
        return obj.getClass().getField(name).get(obj);
    }

    static Object call(Object obj, String name) throws ReflectiveOperationException {
        return obj.getClass().getMethod(name).invoke(obj);
    }

    static Object call(Object obj, String name, Class<?> arg, Object value)
            throws ReflectiveOperationException {
        return obj.getClass().getMethod(name, arg).invoke(obj, value);
    }

    static List<?> list(Object obj, String name) throws ReflectiveOperationException {
        return List.copyOf((Collection<?>) call(obj, name));
    }

    static JsonObject personal(JsonObject result) {
        var p = row("status", "live");
        result.add("personal", p);
        return result;
    }

    private GameplayData() {}
}
