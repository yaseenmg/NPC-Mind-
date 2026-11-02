package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.Config;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.Comparator;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class InspectNearbyEntitiesTool implements AITool {
    public String name() { return "inspect_nearby_entities"; }
    public String description() { return "Inspect real entities near the owner, including type, name and position."; }
    public String schema() { return "{\"radius\":number(optional)}"; }
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        int radius = number(arguments, "radius", Config.CONTEXT_RADIUS.get());
        radius = Math.max(1, Math.min(radius, 64));
        StringBuilder b = new StringBuilder("REAL_NEARBY_ENTITIES\n");
        player.serverLevel().getEntities(player, player.getBoundingBox().inflate(radius)).stream()
            .filter(e -> e != player)
            .sorted(Comparator.comparingDouble((Entity e) -> player.distanceToSqr(e)))
            .limit(Config.MAX_NEARBY_ENTITIES.get())
            .forEach(e -> b.append(e.getType().builtInRegistryHolder().key().location())
                .append(" name=").append(e.getName().getString())
                .append(" pos=").append(e.blockPosition()).append('\n'));
        return "RESULT " + b;
    }
    static int number(String json, String key, int fallback) {
        try {
            String m = "\"" + key + "\"";
            int p = json.indexOf(m); if (p < 0) return fallback;
            int c = json.indexOf(':', p + m.length());
            int e = c + 1; while (e < json.length() && (Character.isWhitespace(json.charAt(e)) || json.charAt(e) == '"')) e++;
            int s = e; while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
            return Integer.parseInt(json.substring(s, e));
        } catch (Exception ignored) { return fallback; }
    }
}
