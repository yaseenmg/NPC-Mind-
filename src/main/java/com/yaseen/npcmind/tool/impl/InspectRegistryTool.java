package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class InspectRegistryTool implements AITool {
    public String name() { return "inspect_registry"; }
    public String description() { return "Search the live Forge registries for real items, blocks, entities or fluids by id/name."; }
    public String schema() { return "{\"kind\":\"item|block|entity|fluid\",\"query\":\"text\"}"; }
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        String kind = text(arguments, "kind");
        String query = text(arguments, "query");
        return "RESULT " + ModpackKnowledge.search(kind, query, 32);
    }
    private static String text(String json, String key) { return JsonArgs.string(json,key); }
}
