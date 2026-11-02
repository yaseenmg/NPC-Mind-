package com.yaseen.npcmind;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.core.CompanionRuntime;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.entity.ModEntities;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.menu.CompanionInventoryMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * The missing Forge glue for NPC Mind.
 *
 * <p>Before this class the mod had <b>no</b> event subscriber at all. That meant:</p>
 * <ul>
 *   <li>{@link CompanionRuntime#tick} was never called, so {@code TaskManager} never advanced a
 *       single move/mine/build task — the companion looked frozen.</li>
 *   <li>{@link CompanionEntity#ask} was never called by any player input, so the AI brain was
 *       never invoked and the companion never replied.</li>
 * </ul>
 *
 * <p>This class is the single, explicit entry point: a server tick driver, a {@code /npcmind}
 * command tree, and optional {@code !npc}-prefixed chat routing. Host mods can still disable chat
 * routing in the config and drive {@code CompanionEntity.ask} themselves.</p>
 *
 * <p><b>Library note (v1.2.0):</b> this class is no longer annotation-scanned — the project
 * ships as a developer library without a mod identity. The host mod activates these handlers
 * explicitly through {@link NPCMindLibrary#registerIntegration()}, which registers this class
 * on the global Forge bus (all handlers are static, so class-based registration is enough).</p>
 */
@Internal
public final class ForgeIntegration {

    /** Drives deterministic task execution and structure discovery once per server tick. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        CompanionRuntime.tick(event.getServer());
        com.yaseen.npcmind.state.CompanionRespawnController.tick(event.getServer());
    }

    /** Registers the player-facing command tree. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("npcmind")
                .then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
                .then(Commands.literal("ask")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> ask(ctx.getSource(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("come").executes(ctx -> command(ctx.getSource(), "come here")))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("follow")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> follow(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("diagnose").executes(ctx -> diagnose(ctx.getSource())))
                .then(Commands.literal("inventory").executes(ctx -> openInventory(ctx.getSource())))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> rename(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(ctx -> transfer(ctx.getSource(), StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("permission")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> permission(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "action"),
                                                BoolArgumentType.getBool(ctx, "value")))))));
    }

    /**
     * Optional chat control: a message starting with the configured prefix (default {@code !npc }) is
     * routed to the nearest owned companion within 32 blocks. Disabled by default in host mods
     * that want to own input themselves.
     */
    /**
     * Releases every static per-companion map when an NPC leaves the world for any reason
     * (death, /kill, despawn, chunk unload). {@code Entity#setRemoved} is final in 1.20.1, so the
     * Forge leave-level event is the correct hook. Without this, every spawn/despawn cycle leaked
     * a UUID entry in a dozen controllers.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof CompanionEntity companion) {
            // Chunk unloads and dimension changes fire this event too, and the old unconditional
            // purge wiped PENDING crafts, HUNTS and the plan controller for a companion that is
            // simply out of sight. Only an actual end-of-life removal (death, /kill, discard,
            // despawn) releases the per-companion state.
            var reason = companion.getRemovalReason();
            if (reason != net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    && reason != net.minecraft.world.entity.Entity.RemovalReason.DISCARDED) return;
            com.yaseen.npcmind.core.CompanionCleanup.purge(companion);
        }
    }

    /**
     * Counted survival hunts must chain on the actual death event, not on a polling guess: vanilla
     * target goals may clear a dead target before the companion's next AI tick. This keeps
     * "kill three sheep" deterministic and lets natural drops be picked up between targets.
     * Matching also accepts the tracked recent hunt target: vanilla goals can null
     * {@code getTarget()} before the kill lands (out-of-follow-range stop, re-selection,
     * damage interruption), and the old strict {@code getTarget() == dead} filter silently
     * dropped exactly those deaths. The tracker lives only 10 seconds and is owned by
     * CombatTargetTool, so a kill can never be credited to a companion that was not
     * hunting that entity.
     */
    @SubscribeEvent
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        var dead = event.getEntity();
        for (CompanionEntity companion : level.getEntitiesOfClass(CompanionEntity.class,
                dead.getBoundingBox().inflate(32.0D),
                c -> c.isAlive() && (c.getTarget() == dead
                        || com.yaseen.npcmind.tool.impl.CombatTargetTool.isRecentHuntTarget(c, dead)))) {
            com.yaseen.npcmind.tool.impl.CombatTargetTool.onTargetDefeated(companion, dead);
        }
    }

    /**
     * Event-driven threat response. The periodic kernel scan only notices attackers within
     * 16 blocks during a 100-tick window, so a ranged hit from farther away used to go
     * unanswered and a sleeping companion stayed asleep through it. A hurt event is an
     * immediate, same-tick signal: face the attacker and wake from simulated sleep.
     */
    @SubscribeEvent
    public static void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof CompanionEntity companion)) return;
        if (event.getAmount() <= 0.0F) return;
        // CombatLearning statistics were wired nowhere (the comment referenced a ServerEvents
        // class that does not exist), so fights/damageTaken stayed zero forever.
        companion.getCombatLearning().damageTaken(event.getAmount());
        net.minecraft.world.entity.Entity attacker = event.getSource().getEntity();
        if (attacker instanceof net.minecraft.world.entity.LivingEntity livingAttacker
                && livingAttacker.isAlive() && livingAttacker != companion
                && !(attacker instanceof ServerPlayer ownerPlayer && ownerPlayer.getUUID().equals(companion.getOwner()))) {
            companion.setTarget(livingAttacker);
            // Teammate alert: hostile hits on a companion are broadcast (dimension-scoped,
            // TTL-bounded) so idle teammates can assist. Allies never raise alerts.
            if (livingAttacker instanceof net.minecraft.world.entity.monster.Monster monster
                    && companion.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.yaseen.npcmind.advanced.CompanionAlertManager.raise(serverLevel, monster, companion);
            }
        }
        com.yaseen.npcmind.advanced.SleepController.wakeOnHurt(companion);
    }

    /** Companion dealt damage to someone: keep the learning counters real. */
    @SubscribeEvent
    public static void onLivingDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof CompanionEntity companion) {
            companion.getCombatLearning().damageDealt(event.getAmount());
        }
    }

    /** Persistent memory lifetime follows the owner's session (load on join, save on leave). */
    @SubscribeEvent
    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide()) {
            com.yaseen.npcmind.memory.MemoryStore.load(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide()) {
            com.yaseen.npcmind.memory.MemoryStore.save(player);
            com.yaseen.npcmind.memory.MemoryStore.unload(player);
        }
    }

    @SubscribeEvent
    public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        // RecipeManager instances can survive a datapack reload; invalidate cached recipe candidates.
        com.yaseen.npcmind.tool.impl.CraftItemTool.clearRecipeCache();
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!Config.ENABLE_CHAT_CONTROL.get()) return;
        String prefix = Config.CHAT_PREFIX.get();
        if (prefix == null || prefix.isEmpty()) return;
        String raw = event.getRawText();
        if (raw == null || !raw.regionMatches(true, 0, prefix, 0, prefix.length())) return;
        // A configured prefix is a command token, not an arbitrary text prefix. With the default
        // "!npc", ordinary messages such as "!nice" must never be cancelled.
        if (raw.length() > prefix.length() && !Character.isWhitespace(raw.charAt(prefix.length()))) return;
        String message = raw.substring(prefix.length()).trim();
        if (message.isEmpty()) return;
        ServerPlayer player = event.getPlayer();
        CompanionEntity companion = nearestOwned(player, 32.0D);
        if (companion == null) return;
        event.setCanceled(true);
        companion.ask(message);
    }

    // ------------------------------------------------------------------ command bodies

    private static int openInventory(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Only a player can open a companion inventory.")); return 0; }
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) { source.sendFailure(Component.literal("No owned companion nearby.")); return 0; }
        net.minecraftforge.network.NetworkHooks.openScreen(player,
                new net.minecraft.world.SimpleMenuProvider((id, inv, ignored) -> new CompanionInventoryMenu(id, inv, companion),
                        Component.literal(companion.getDisplayName().getString() + " Inventory")),
                buffer -> buffer.writeInt(companion.getId()));
        return 1;
    }

    private static int rename(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Only a player can rename a companion.")); return 0; }
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) { source.sendFailure(Component.literal("No owned companion nearby.")); return 0; }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 64) {
            source.sendFailure(Component.literal("Name must contain 1 to 64 characters.")); return 0;
        }
        companion.setDisplayName(clean);
        source.sendSuccess(() -> Component.literal("Companion renamed to " + clean + "."), false);
        return 1;
    }

    private static int transfer(CommandSourceStack source, String targetName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Only a player can transfer ownership.")); return 0; }
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) { source.sendFailure(Component.literal("No owned companion nearby.")); return 0; }
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) { source.sendFailure(Component.literal("Target player must be online.")); return 0; }
        if (target.level() != companion.level()) {
            source.sendFailure(Component.literal("The target player must be in the same dimension.")); return 0;
        }
        java.util.UUID oldOwner = companion.getOwner();
        companion.setOwner(target.getUUID());
        if (oldOwner != null && !oldOwner.equals(target.getUUID())) {
            // These warning/rescue cooldowns are keyed by the old owner's UUID.
            com.yaseen.npcmind.advanced.EmergencyController.clear(oldOwner);
            com.yaseen.npcmind.advanced.ThreatMonitor.clear(oldOwner);
            // AutonomyController's per-entity scheduler is keyed by the companion UUID.
            com.yaseen.npcmind.autonomy.AutonomyController.clear(companion.getUUID());
        }
        source.sendSuccess(() -> Component.literal("Companion ownership transferred to " + target.getGameProfile().getName() + "."), false);
        target.sendSystemMessage(Component.literal("You now own " + companion.getDisplayName().getString() + "."));
        return 1;
    }

    private static int spawn(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can spawn a companion."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        // One companion per owner: the old command happily spawned duplicates, which split
        // follow/bodyguard behaviour and doubled every autonomous scan.
        if (hasOwnedCompanionAnywhere(source.getServer(), player.getUUID())) {
            source.sendFailure(Component.literal(
                    "You already own a living companion. Use it, transfer it (/npcmind transfer <player>), or kill it before spawning another."));
            return 0;
        }
        if (Config.AUTO_RESPAWN.get()
                && com.yaseen.npcmind.state.CompanionRespawnController.hasPendingRespawn(
                        source.getServer(), player.getUUID())) {
            source.sendFailure(Component.literal(
                    "A companion respawn is already pending for you. Wait for it to finish to avoid duplicate companions."));
            return 0;
        }
        CompanionEntity companion = ModEntities.COMPANION.get().create(level);
        if (companion == null) {
            source.sendFailure(Component.literal("Could not create the companion entity."));
            return 0;
        }
        BlockPos spawnPos = findSafeSpawn(level, player.blockPosition(), companion);
        if (spawnPos == null) {
            companion.discard();
            source.sendFailure(Component.literal(
                    "Could not find a safe, non-colliding spawn position within 2 blocks. Clear nearby space and try again."));
            return 0;
        }
        companion.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                player.getYRot(), 0.0F);
        companion.setOwner(player.getUUID());
        if (!level.addFreshEntity(companion)) {
            companion.discard();
            source.sendFailure(Component.literal("The companion could not be added to the world."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawned a companion owned by " + player.getName().getString() + "."), false);
        return 1;
    }

    /** Find a stable spawn point beside the player without placing the companion inside them. */
    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos center, CompanionEntity companion) {
        // Evaluate a small, prebuilt neighborhood in deterministic near-to-far order.
        java.util.List<BlockPos> columns = new java.util.ArrayList<>(25);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, 0, -2), center.offset(2, 0, 2))) {
            columns.add(pos.immutable());
        }
        columns.sort(java.util.Comparator
                .comparingInt((BlockPos pos) -> Math.max(Math.abs(pos.getX() - center.getX()),
                        Math.abs(pos.getZ() - center.getZ())))
                .thenComparingDouble(pos -> pos.distSqr(center)));
        int[] verticalOffsets = {0, 1, -1, 2, -2, 3, -3};
        for (BlockPos column : columns) {
            if (column.getX() == center.getX() && column.getZ() == center.getZ()) continue;
            for (int dy : verticalOffsets) {
                BlockPos candidate = column.offset(0, dy, 0);
                if (isSafeSpawnPosition(level, candidate, companion)) return candidate;
            }
        }
        // Surface positions are a last fallback and may include the center column.
        for (BlockPos column : columns) {
            if (!level.isInWorldBounds(column) || !level.getWorldBorder().isWithinBounds(column)
                    || !level.hasChunkAt(column)) continue;
            BlockPos surface = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column);
            if (isSafeSpawnPosition(level, surface, companion)) return surface;
        }
        return null;
    }

    private static boolean isSafeSpawnPosition(ServerLevel level, BlockPos feet,
            CompanionEntity companion) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(head)
                || !level.getWorldBorder().isWithinBounds(feet)
                || !level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()
                || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return false;
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        AABB box = companion.getDimensions(Pose.STANDING).makeBoundingBox(x, y, z);
        return level.noCollision(companion, box);
    }

    private static int ask(CommandSourceStack source, String message) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can talk to a companion."));
            return 0;
        }
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) {
            source.sendFailure(Component.literal("No owned companion nearby. Use /npcmind spawn first."));
            return 0;
        }
        companion.ask(message);
        return 1;
    }

    private static int command(CommandSourceStack source, String message) {
        return ask(source, message);
    }

    private static int follow(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) {
            source.sendFailure(Component.literal("No owned companion nearby."));
            return 0;
        }
        companion.setFollowEnabled(enabled);
        if (enabled) TaskManager.cancelCurrentTask(companion);
        source.sendSuccess(() -> Component.literal("Companion follow=" + enabled + "."), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) {
            source.sendFailure(Component.literal("No owned companion nearby."));
            return 0;
        }
        TaskManager.cancelCurrentTask(companion);
        companion.setTarget(null);
        companion.setFriendlyCombat(false);
        companion.getNavigation().stop();
        source.sendSuccess(() -> Component.literal("Companion stopped."), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) {
            source.sendFailure(Component.literal("No owned companion nearby."));
            return 0;
        }
        String line = companion.getCompanionState().snapshot()
                + " | hp=" + String.format(Locale.ROOT, "%.1f", companion.getHealth())
                + "/" + String.format(Locale.ROOT, "%.1f", companion.getMaxHealth())
                + " | task=" + TaskManager.status(companion);
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int diagnose(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        AgentEngine.diagnose(player);
        return 1;
    }

    /**
     * Owner-only safety switch: {@code /npcmind permission consume_rare true} enables rare
     * consumables (ender pearls), {@code drop}/{@code destroy} stay opt-in the same way.
     * Without this command the flags were unreachable and their gated tools were dead code.
     */
    private static int permission(CommandSourceStack source, String action, boolean value) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can change companion permissions."));
            return 0;
        }
        CompanionEntity companion = nearestOwned(player, 64.0D);
        if (companion == null) {
            source.sendFailure(Component.literal("No owned companion nearby."));
            return 0;
        }
        com.yaseen.npcmind.state.ActionPermissions.Action parsed;
        try {
            parsed = com.yaseen.npcmind.state.ActionPermissions.Action.valueOf(
                    action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            source.sendFailure(Component.literal(
                    "Unknown permission action. Valid: build, mine, farm, trade, craft, consume_rare, drop, destroy."));
            return 0;
        }
        companion.getPermissions().set(parsed, value);
        source.sendSuccess(() -> Component.literal("Permission " + parsed.name().toLowerCase(Locale.ROOT)
                + "=" + value + " for " + companion.getDisplayName().getString() + "."), false);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasOwnedCompanionAnywhere(MinecraftServer server, java.util.UUID owner) {
        if (server == null || owner == null) return false;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion && companion.isAlive()
                        && owner.equals(companion.getOwner())) return true;
            }
        }
        return false;
    }

    private static CompanionEntity nearestOwned(ServerPlayer player, double maxDistance) {
        CompanionEntity best = null;
        double bestDistance = maxDistance * maxDistance;
        // Filter the dimension BEFORE touching the entity list. The old loop iterated every
        // entity of every dimension and only then discarded foreign-level results.
        for (var entity : player.serverLevel().getAllEntities()) {
            if (!(entity instanceof CompanionEntity companion) || !companion.isAlive()) continue;
            if (!player.getUUID().equals(companion.getOwner())) continue;
            double distance = companion.distanceToSqr(player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = companion;
            }
        }
        return best;
    }

    private ForgeIntegration() {}
}
