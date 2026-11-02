package com.yaseen.npcmind.entity;

import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/**
 * Host-mod speech adapter. NPC Mind uses a plain server message by default, while a host
 * can inject chat, translation, UI, or custom narration without changing the AI core.
 */
@FunctionalInterface
@Internal
public interface CompanionSpeech {
    void speak(CompanionEntity npc, ServerPlayer player, String text);
}
