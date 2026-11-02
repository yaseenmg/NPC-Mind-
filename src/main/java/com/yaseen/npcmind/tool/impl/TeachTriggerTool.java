package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/** Stores a user-defined exact phrase -> local command shortcut in the companion's persistent NBT. */
@Internal
public final class TeachTriggerTool implements AITool {
    @Override public String name() { return "teach_trigger"; }
    @Override public String description() {
        return "Teach a persistent phrase shortcut. When the owner later says the exact phrase, NPC Mind routes the stored instruction through its normal local safety and tool validation pipeline.";
    }
    @Override public String schema() { return "{\"phrase\":\"required string\",\"instruction\":\"required string\"}"; }
    @Override public String execute(CompanionEntity c, ServerPlayer p, String arguments) {
        String phrase = JsonArgs.string(arguments, "phrase").trim();
        String instruction = JsonArgs.string(arguments, "instruction").trim();
        if (phrase.isBlank() || instruction.isBlank()) return "FAILED phrase_and_instruction_required";
        if (phrase.length() > 96 || instruction.length() > 240) return "FAILED teaching_entry_too_long";
        if (phrase.equalsIgnoreCase(instruction)) return "FAILED phrase_and_instruction_must_differ";
        c.getTeachingMemory().teach(phrase, instruction);
        return "SUCCESS learned_trigger phrase=\"" + phrase + "\" instruction=\"" + instruction + "\"";
    }
}
