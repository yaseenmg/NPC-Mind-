package com.yaseen.npcmind.tool;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict runtime contract for every registered tool. A command or host integration cannot invoke a tool with
 * malformed JSON, missing required arguments, unknown argument keys, or obviously invalid
 * primitive values.  This is deliberately network-independent and forms the same safety boundary for local
 * commands and host-mod integrations.
 */
public final class ToolContract {
    public record Field(String name, Kind kind, boolean required, String enumText) {}
    public enum Kind { STRING, NUMBER, BOOLEAN, ARRAY, OBJECT, ANY }
    public record Validation(boolean ok, String reason, JsonObject normalized) {}

    private static final Pattern FIELD = Pattern.compile("\\\"?([A-Za-z_][A-Za-z0-9_]*)\\\"?\\s*:\\s*([^,}]+)");
    private static final Set<String> CONTROL_KEYS = Set.of("type", "name", "arguments", "text", "reason");

    private ToolContract() {}

    public static Validation validate(AITool tool, String raw) {
        if (tool == null) return new Validation(false, "unknown_tool", new JsonObject());
        return validateSchema(tool.schema(), raw);
    }

    /** Validate raw arguments against a tool's compact schema without requiring CompanionEntity. */
    public static Validation validateSchema(String schemaText, String raw) {
        JsonObject in;
        try {
            JsonElement e = JsonParser.parseString(raw == null || raw.isBlank() ? "{}" : raw);
            if (!e.isJsonObject()) return new Validation(false, "arguments_not_object", new JsonObject());
            in = e.getAsJsonObject();
        } catch (Exception e) {
            return new Validation(false, "invalid_json_arguments", new JsonObject());
        }
        List<Field> fields = parse(schemaText);
        Set<String> known = new HashSet<>();
        for (Field f : fields) known.add(f.name());
        for (String key : in.keySet()) if (!known.contains(key))
            return new Validation(false, "unknown_argument=" + key, new JsonObject());
        JsonObject normalized = in.deepCopy();
        for (Field f : fields) {
            if (!in.has(f.name())) {
                if (f.required) return new Validation(false, "missing_argument=" + f.name(), new JsonObject());
                continue;
            }
            JsonElement value = in.get(f.name());
            if (!matches(f.kind, value)) return new Validation(false, "invalid_type=" + f.name() + ":" + f.kind, new JsonObject());
            if (f.enumText != null && !f.enumText.isBlank() && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                Set<String> allowed = parseEnum(f.enumText);
                if (!allowed.isEmpty() && !allowed.contains(value.getAsString()))
                    return new Validation(false, "invalid_enum=" + f.name(), new JsonObject());
            }
            if (f.kind == Kind.NUMBER && !Double.isFinite(value.getAsDouble()))
                return new Validation(false, "non_finite_number=" + f.name(), new JsonObject());
        }
        return new Validation(true, "ok", normalized);
    }

    public static JsonObject toJsonSchema(AITool tool) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (Field f : parse(tool.schema())) {
            JsonObject p = new JsonObject();
            switch (f.kind) {
                case STRING -> p.addProperty("type", "string");
                case NUMBER -> p.addProperty("type", "number");
                case BOOLEAN -> p.addProperty("type", "boolean");
                case ARRAY -> p.addProperty("type", "array");
                case OBJECT -> p.addProperty("type", "object");
                default -> {}
            }
            if (f.enumText != null) {
                JsonArray e = new JsonArray();
                for (String s : parseEnum(f.enumText)) e.add(s);
                if (!e.isEmpty()) p.add("enum", e);
            }
            props.add(f.name(), p);
            if (f.required) required.add(f.name());
        }
        schema.add("properties", props);
        if (!required.isEmpty()) schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    public static List<Field> parse(String schemaText) {
        if (schemaText == null || schemaText.isBlank() || "{}".equals(schemaText.trim())) return List.of();
        String s = schemaText.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1);
        List<Field> out = new ArrayList<>();
        for (String part : splitTopLevel(s, ',')) {
            int colon = indexOfTopLevel(part, ':');
            if (colon < 0) continue;
            String name = part.substring(0, colon).trim().replace("\"", "").replace("'", "");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            String type = part.substring(colon + 1).trim().replace('\'', '\"');
            boolean required = !type.toLowerCase(Locale.ROOT).contains("optional");
            Kind kind = kindOf(type);
            String enumText = null;
            int pipe = type.indexOf('|');
            if (pipe >= 0 && kind != Kind.ANY) {
                enumText = type.replace("\"", "").replace("'", "").replace("(optional)", "").trim();
            }
            out.add(new Field(name, kind, required, enumText));
        }
        return out;
    }

    private static List<String> splitTopLevel(String s, char separator) {
        List<String> out = new ArrayList<>();
        int start = 0, square = 0, curly = 0;
        boolean quoted = false; char quote = 0; boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (escaped) { escaped = false; continue; }
                if (ch == '\\') { escaped = true; continue; }
                if (ch == quote) quoted = false;
                continue;
            }
            if (ch == '"' || ch == '\'') { quoted = true; quote = ch; continue; }
            if (ch == '[') square++; else if (ch == ']') square--;
            else if (ch == '{') curly++; else if (ch == '}') curly--;
            else if (ch == separator && square == 0 && curly == 0) { out.add(s.substring(start, i).trim()); start = i + 1; }
        }
        if (start < s.length()) out.add(s.substring(start).trim());
        return out;
    }

    private static int indexOfTopLevel(String s, char wanted) {
        int square = 0, curly = 0; boolean quoted = false; char quote = 0; boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (escaped) { escaped = false; continue; }
                if (ch == '\\') { escaped = true; continue; }
                if (ch == quote) quoted = false;
                continue;
            }
            if (ch == '"' || ch == '\'') { quoted = true; quote = ch; continue; }
            if (ch == '[') square++; else if (ch == ']') square--;
            else if (ch == '{') curly++; else if (ch == '}') curly--;
            else if (ch == wanted && square == 0 && curly == 0) return i;
        }
        return -1;
    }

    private static Kind kindOf(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        // The project uses a compact schema DSL rather than JSON Schema. Parse structure
        // before looking for ':' so nested object schemas cannot be mistaken for strings.
        if (t.startsWith("[") || t.contains("[")) return Kind.ARRAY;
        if (t.startsWith("{") || t.contains("{")) return Kind.OBJECT;
        if (t.contains("boolean") && t.contains("string")) return Kind.ANY;
        if (t.contains("boolean") || t.contains("true|false") || t.equals("true") || t.equals("false")) return Kind.BOOLEAN;
        if (t.contains("number") || t.contains("integer") || t.matches("-?[0-9]+(\\.[0-9]+)?")) return Kind.NUMBER;
        if (t.contains("string") || t.contains("text") || t.contains("item_id")
                || t.contains("namespace:item") || t.contains("keyword") || t.contains("uuid")
                || t.contains("name") || t.contains("id") || t.contains("|")) return Kind.STRING;
        // The compact DSL also uses concrete words such as "status" and "text" to describe
        // string parameters. Treat an otherwise-unrecognized scalar descriptor as a string,
        // not ANY: otherwise objects/booleans silently passed required textual arguments.
        return Kind.STRING;
    }

    private static boolean matches(Kind k, JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (!e.isJsonPrimitive() && k != Kind.ARRAY && k != Kind.OBJECT && k != Kind.ANY) return false;
        return switch (k) {
            case STRING -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
            case NUMBER -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
            case BOOLEAN -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
            case ARRAY -> e.isJsonArray();
            case OBJECT -> e.isJsonObject();
            case ANY -> true;
        };
    }

    private static Set<String> parseEnum(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.replace("\"", "").replace("'", "").split("\\|")) {
            String v = p.trim();
            if (v.matches("[A-Za-z0-9_\\-]+")) out.add(v);
        }
        return out;
    }
}
