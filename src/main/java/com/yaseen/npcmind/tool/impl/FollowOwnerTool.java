package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class FollowOwnerTool implements AITool {
    public String name() { return "follow_owner"; }
    public String description() { return "Follow the player only when explicitly requested. Disabled by default; enabling cancels the previous navigation task."; }
    public String schema() { return "{\"enabled\":boolean(optional)}"; }
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        Boolean enabled = JsonArgs.bool(arguments, "enabled");
        if (enabled == null) enabled = !arguments.contains("false");
        companion.setFollowEnabled(enabled);
        if (enabled) TaskManager.cancelCurrentTask(companion);
        return "RESULT follow_enabled=" + enabled;
    }
}
