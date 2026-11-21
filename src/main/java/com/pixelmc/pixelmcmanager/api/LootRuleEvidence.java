package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Annotate literal references in common LootJS chains. No script or predicate is executed. */
final class LootRuleEvidence {
    private static final String ID = "#?[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final String NAME = "[a-zA-Z_$][a-zA-Z0-9_$]*";
    private static final Pattern ARRAY =
            Pattern.compile("(?:let|const|var)\\s+(" + NAME + ")\\s*=\\s*\\[\\s*");
    private static final Pattern ENTRY =
            Pattern.compile("\\[\\s*(?:Item\\.of\\(\\s*)?['\"](" + ID + ")['\"]");
    private static final Pattern LOOP =
            Pattern.compile(
                    "("
                            + NAME
                            + ")\\.forEach\\(\\s*\\(?\\s*\\[\\s*("
                            + NAME
                            + ")\\b[^]]*]\\s*\\)?\\s*=>\\s*\\{");
    private static final Pattern TABLE =
            Pattern.compile("\\.addLootTableModifier\\(\\s*['\"](" + ID + ")['\"]\\s*\\)");
    private static final Pattern OP =
            Pattern.compile("\\.(removeLoot|addLoot|addWeightedLoot|replaceLoot)\\(");
    private static final Pattern FIRST = Pattern.compile("^['\"](" + ID + ")['\"]");
    private static final Pattern REPLACEMENT =
            Pattern.compile("^['\"]" + ID + "['\"]\\s*,\\s*['\"](" + ID + ")['\"]");
    private static final Pattern SPARSE_STRING = Pattern.compile("(['\"`])([^'\"`]*?)\\1");

    private record Rule(String table, String operation, int line, String excerpt, String role) {}

    private record Entry(int line, String id) {}

    private final Map<Integer, List<Rule>> byLine = new HashMap<>();
    private final Map<Integer, List<Entry>> operandsByTableLine = new HashMap<>();

    LootRuleEvidence(List<String> lines) {
        var arrays = new HashMap<String, List<Entry>>();
        String array = null;
        String loop = null;
        String variable = null;
        boolean inComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).strip();
            if (raw.contains("/*")) inComment = true;
            if (inComment) {
                if (raw.contains("*/")) inComment = false;
                continue;
            }
            String code = code(raw);
            if (code.startsWith("LootJS.modifiers(")) {
                arrays.clear();
                array = loop = variable = null;
            }
            Matcher decl = ARRAY.matcher(code);
            if (decl.matches()) {
                array = decl.group(1);
                arrays.put(array, new ArrayList<>());
                continue;
            }
            if (array != null) {
                Matcher member = ENTRY.matcher(code);
                if (member.lookingAt()) arrays.get(array).add(new Entry(i, member.group(1)));
                if (code.startsWith("]")) array = null;
            }
            Matcher each = LOOP.matcher(code);
            if (each.matches()) {
                loop = each.group(1);
                variable = each.group(2);
            } else if (code.startsWith("})")) {
                loop = variable = null;
            }
            Matcher table = TABLE.matcher(code);
            if (!table.find()) continue;
            StringBuilder chain = new StringBuilder(code.substring(table.end()));
            // Only follow this expression. A later statement can operate on a different modifier.
            int depth = depthChange(chain.toString());
            for (int j = i + 1;
                    j < lines.size()
                            && j <= i + 14
                            && chain.length() < 1200
                            && chain.indexOf(";") < 0;
                    j++) {
                String next = code(lines.get(j));
                if (next.isEmpty()) continue;
                if (next.contains("/*") || next.contains(".addLootTableModifier(")) break;
                if (depth <= 0 && !next.startsWith(".")) break;
                chain.append(' ').append(next);
                depth += depthChange(next);
            }
            int end = chain.indexOf(";");
            if (end >= 0) chain.setLength(end);
            Matcher op = OP.matcher(chain);
            if (!op.find()) continue;
            String operation = op.group(1);
            String args = chain.substring(op.end()).stripLeading();
            String excerpt = snippet(table.group() + chain);
            String tableId = table.group(1);
            add(i, new Rule(tableId, operation, i + 1, excerpt, "table"));
            if (loop != null
                    && arrays.containsKey(loop)
                    && linkedArgument(args, operation, variable)) {
                operandsByTableLine.put(i, arrays.get(loop));
                for (Entry entry : arrays.get(loop))
                    add(entry.line, new Rule(tableId, operation, i + 1, excerpt, entry.id));
            }
            // Match literal operands at their exact lines, excluding IDs in predicate chains.
            var operands =
                    operation.equals("replaceLoot") ? List.of(FIRST, REPLACEMENT) : List.of(FIRST);
            for (var operand : operands) {
                Matcher literal = operand.matcher(args);
                if (!literal.find()) continue;
                String id = literal.group(1);
                for (int j = i; j < lines.size() && j <= i + 14; j++) {
                    if (j > i
                            && (code(lines.get(j)).contains(".addLootTableModifier(")
                                    || code(lines.get(j)).startsWith("})"))) break;
                    if (code(lines.get(j)).contains("'" + id + "'")
                            || code(lines.get(j)).contains("\"" + id + "\"")) {
                        add(j, new Rule(tableId, operation, i + 1, excerpt, id));
                        break;
                    }
                }
            }
        }
    }

    private static boolean linkedArgument(String args, String operation, String variable) {
        String v = Pattern.quote(variable);
        return switch (operation) {
            case "removeLoot", "replaceLoot" -> args.matches("^" + v + "\\s*[,)].*");
            case "addLoot" ->
                    args.matches(
                            "^\\[?\\s*(?:LootEntry\\.of\\(\\s*|Item\\.of\\(\\s*)?"
                                    + v
                                    + "\\s*[,)].*");
            case "addWeightedLoot" ->
                    args.matches("^\\[[0-9, ]+]\\s*,\\s*\\[\\s*Item\\.of\\(\\s*" + v + "\\s*\\).*");
            default -> false;
        };
    }

    private static String code(String raw) {
        int comment = raw.indexOf("//");
        return (comment < 0 ? raw : raw.substring(0, comment)).strip();
    }

    private static int depthChange(String code) {
        String delimiters = SPARSE_STRING.matcher(code).replaceAll("");
        int depth = 0;
        for (int i = 0; i < delimiters.length(); i++) {
            switch (delimiters.charAt(i)) {
                case '(', '[' -> depth++;
                case ')', ']' -> depth--;
            }
        }
        return depth;
    }

    static String snippet(String raw) {
        String text = code(raw);
        if (text.length() > 640) text = text.substring(0, 640);
        if (text.matches("(?i).*(secret|password|token|api.?key|https?:|sk-).*")) return "参见脚本位置";
        String safe =
                SPARSE_STRING
                        .matcher(text)
                        .replaceAll(
                                match ->
                                        Matcher.quoteReplacement(
                                                match.group(2).matches(ID)
                                                        ? match.group()
                                                        : "'…'"));
        return safe.length() <= 260 ? safe : safe.substring(0, 260) + "…";
    }

    private void add(int line, Rule rule) {
        byLine.computeIfAbsent(line, unused -> new ArrayList<>()).add(rule);
    }

    void annotate(JsonObject row, int line, String match, String text) {
        var rules = new com.google.gson.JsonArray();
        for (Rule rule : byLine.getOrDefault(line, List.of())) {
            if (!(rule.role.equals(match) || rule.role.equals("table") && rule.table.equals(match)))
                continue;
            var entry = new JsonObject();
            entry.addProperty("table", rule.table);
            entry.addProperty("operation", rule.operation);
            entry.addProperty("rule_line", rule.line);
            entry.addProperty("code", rule.excerpt);
            rules.add(entry);
        }
        if (!rules.isEmpty()) row.add("script_rules", rules);
        var operands = operandsByTableLine.get(line);
        if (operands != null) {
            var sample = new com.google.gson.JsonArray();
            operands.stream().limit(8).forEach(entry -> sample.add(entry.id));
            row.add("operand_sample", sample);
            row.addProperty("literal_operand_count", operands.size());
        }
        row.addProperty("excerpt", snippet(text));
    }
}
