package com.yaseen.npcmind.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Robust JSON argument reader shared by tools; avoids brittle indexOf parsing. */
public final class JsonArgs {
    private JsonArgs() {}
    public static JsonObject object(String json) {
        try { return JsonParser.parseString(json == null ? "{}" : json).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }
    public static String string(String json,String key) {
        try { JsonElement e=object(json).get(key); return e==null||e.isJsonNull()?"":e.getAsString(); }
        catch(Exception e){return "";}
    }
    public static Integer integer(String json,String key) {
        try { JsonElement e=object(json).get(key); return e==null||e.isJsonNull()?null:e.getAsInt(); }
        catch(Exception e){return null;}
    }
    public static int integer(String json,String key,int fallback){Integer v=integer(json,key);return v==null?fallback:v;}
    public static Boolean bool(String json,String key) {
        try { JsonElement e=object(json).get(key); return e==null||e.isJsonNull()?null:e.getAsBoolean(); }
        catch(Exception e){return null;}
    }
}
