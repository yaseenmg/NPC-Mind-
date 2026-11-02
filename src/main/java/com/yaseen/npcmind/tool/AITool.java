package com.yaseen.npcmind.tool;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;

/** A real Minecraft operation exposed to the AI. Implementations must inspect or mutate the live server only. */
public interface AITool {
    String name();
    String description();
    String schema();
    String execute(CompanionEntity companion, ServerPlayer player, String arguments);
}
