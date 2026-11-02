package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.memory.MemoryStore;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class RememberTool implements AITool {
    public String name() { return "remember"; }
    public String description() { return "Store an explicit player-approved fact in persistent memory for future sessions."; }
    public String schema() { return "{\"fact\":\"text\"}"; }
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        String fact = text(arguments);
        if (fact.isBlank()) return "RESULT remembered=false reason=empty_fact";
        MemoryStore.add(player, "MEMORY_FACT: " + fact);
        return "RESULT remembered=true";
    }
    private String text(String json) { return JsonArgs.string(json,"fact"); }

}
