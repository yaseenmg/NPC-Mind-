package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import com.yaseen.npcmind.api.Internal;

/**
 * Single gateway for all registered Minecraft tools. No raw command argument reaches a tool directly:
 * resolve -> contract validate -> server-thread execute -> audit.  This is the hard boundary
 * that makes the AI brain replaceable without making the world executor unsafe or brittle.
 */
@Internal
public final class AgentToolRuntime {
    private AgentToolRuntime() {}

    public record Result(String tool, String actionId, boolean accepted, boolean blocked, String result) {}

    public static Result invoke(CompanionEntity companion, ServerPlayer player, String name, String rawArgs) {
        return invoke(companion, player, name, rawArgs, UUID.randomUUID().toString());
    }

    public static Result invoke(CompanionEntity companion, ServerPlayer player, String name, String rawArgs, String actionId) {
        return InternalToolExecutor.invoke(companion, player, name, rawArgs, actionId);
    }
}
