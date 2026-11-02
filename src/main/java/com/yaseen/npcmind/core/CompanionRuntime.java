package com.yaseen.npcmind.core;

import com.yaseen.npcmind.NPCMind;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.advanced.StructureTravelController;
import com.yaseen.npcmind.tool.impl.StructureSearchTool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

/** New authoritative runtime: observe -> reflex -> execute -> verify -> remember. */
@Internal
public final class CompanionRuntime {
    private static long lastSweep;

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            TaskManager.tick(level);
            // Collect companions once per level. Structure travel and reflexes share this
            // immutable per-tick list instead of scanning every entity collection twice.
            java.util.ArrayList<CompanionEntity> companions = new java.util.ArrayList<>();
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity c && c.isAlive()) companions.add(c);
            }
            for (CompanionEntity c : companions) {
                StructureSearchTool.pollDiscovery(c);
                StructureTravelController.tick(c);
            }
            // Target selection is owned by CompanionKernel on the entity tick. Keeping it out of
            // this server-wide sweep prevents a second writer from changing targets between
            // TaskManager and the entity AI goals.
        }
        if (server.getTickCount() % 1200 == 0) NPCMind.LOG.debug("[NPCMind] runtime heartbeat");
    }

    public static WorldSnapshot observe(CompanionEntity c, ServerPlayer owner) { return WorldSnapshot.capture(c, owner); }
    public static String context(CompanionEntity c) {
        Player owner = c.getOwnerPlayer();
        if (!(owner instanceof ServerPlayer serverOwner)) return "owner=offline";
        WorldSnapshot s = observe(c, serverOwner);
        return "pos=" + s.position() + " hp=" + s.health() + " food=" + s.food() + " sat=" + s.saturation()
                + " hostiles=" + s.nearbyHostiles() + " task=" + s.taskStatus()
                + " recent=" + ActionJournal.recent(c.getUUID())
                + " skills=" + SkillLibrary.retrieve(c, "");
    }
    private static CompanionEntity find(MinecraftServer server, UUID id) {
        for (ServerLevel l : server.getAllLevels()) if (l.getEntity(id) instanceof CompanionEntity c) return c;
        return null;
    }
    private CompanionRuntime() {}
}
