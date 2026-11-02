package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.api.PlayerLikeExperience;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;

/** Read-only, deterministic player-like state inspection for integrations and behaviour packs. */
@Internal
public final class ManagePlayerStateTool implements AITool {
    @Override public String name() { return "manage_player_state"; }
    @Override public String description() { return "Read the companion's player-like health, hunger, air, fire, freezing and XP state."; }
    @Override public String schema() { return "{\"action\":\"status(optional)\"}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer player, String arguments) {
        PlayerLikeExperience.Snapshot xp = PlayerLikeExperience.read(c);
        return "RESULT player_state=true health=" + round(c.getHealth()) + "/" + round(c.getMaxHealth())
                + " food=" + c.getFoodLevel() + " saturation=" + round(c.getFoodSaturation())
                + " exhaustion=" + round(c.getFoodExhaustion())
                + " air=" + c.getAirSupply() + "/" + c.getMaxAirSupply()
                + " fire_ticks=" + c.getRemainingFireTicks() + " frozen_ticks=" + c.getTicksFrozen()
                + " fully_frozen=" + c.isFullyFrozen()
                + " xp_level=" + xp.level() + " xp_progress=" + round(xp.progress())
                + " xp_total=" + xp.totalPoints()
                + " dimension=" + c.level().dimension().location()
                + " position=" + c.blockPosition().getX() + "," + c.blockPosition().getY() + "," + c.blockPosition().getZ();
    }

    private static String round(float value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }
}
