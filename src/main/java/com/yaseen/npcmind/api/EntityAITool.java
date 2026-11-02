package com.yaseen.npcmind.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable server-side tool whose actor can be any LivingEntity, not only NPC Mind's bundled
 * CompanionEntity. Host mods own actor permissions, target policy and any entity-specific state.
 */
public interface EntityAITool {
    String name();
    String description();
    String schema();

    /** Execute on the logical server thread. Controller is null for autonomous/unowned NPCs. */
    String execute(LivingEntity actor, @Nullable ServerPlayer controller, String arguments);
}
