package com.yaseen.npcmind.task;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Real server-side task state. No simulated world state is stored here. */
@Internal
public final class TaskManager {
    /** Per-companion queue cap. The old cap of 8 silently truncated survival chains that
     * legitimately queue 12+ blocks (furnace needs 8 stone after 3 pickaxe + 2 sword ore),
     * stalling the whole survival progression at the furnace step. */
    private static final int MAX_QUEUE = 32;
    private static final Map<UUID, java.util.concurrent.ConcurrentLinkedDeque<Task>> TASKS = new ConcurrentHashMap<>();
    public static String move(CompanionEntity companion, BlockPos requested) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (requested == null) return "ERROR missing_target";
        if (!level.isInWorldBounds(requested) || !level.getWorldBorder().isWithinBounds(requested)) return "FAILED target_outside_world_bounds";
        if (!level.hasChunkAt(requested)) return "FAILED target_chunk_not_loaded";
        if (companion.blockPosition().distSqr(requested) > 256.0D * 256.0D) return "FAILED target_too_far";
        var targetState = level.getBlockState(requested);
        if (!targetState.getFluidState().isEmpty() || targetState.is(net.minecraft.world.level.block.Blocks.LAVA)
                || targetState.is(net.minecraft.world.level.block.Blocks.FIRE)) return "DENIED hazardous_target";
        BlockPos target = resolveStandPosition(level, requested, companion);
        if (target == null) return "FAILED no_safe_stand_near_target=" + requested;
        // Claim navigation BEFORE cancelling the current task. The old order cancelled first and
        // requested second, so a rejected request lost the original task with nothing in its place.
        if (!NavigationArbiter.request(companion, target, "task", 500, 1.15)) {
            return "FAILED navigation_request_rejected target=" + target + " current_navigation_owner=" + NavigationArbiter.owner(companion);
        }
        cancelCurrentTask(companion);
        UUID id = companion.getUUID();
        var queue = TASKS.computeIfAbsent(id,k->new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new MoveTask(target));
        com.yaseen.npcmind.debug.Trace.log("TASK", "move START from={} requested={} resolved={}", companion.blockPosition(), requested, target);
        return "STARTED action_id=" + queue.peekLast().actionId + " move_to requested=" + requested + " destination=" + target;
    }

    /** Resolve a requested world block to a standable feet cell. This avoids pathing into walls,
     * floor blocks or the middle of a tree while preserving the requested location when possible. */
    private static BlockPos resolveStandPosition(ServerLevel level, BlockPos requested, CompanionEntity companion) {
        if (isStandableCell(level, requested)) return requested.immutable();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = 2; dy >= -2; dy--) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos candidate = requested.offset(dx, dy, dz);
            if (!level.hasChunkAt(candidate) || !isStandableCell(level, candidate)) continue;
            double distance = candidate.distSqr(requested);
            double travel = companion.distanceToSqr(candidate.getX() + .5D, candidate.getY(), candidate.getZ() + .5D);
            double score = distance * 4.0D + travel * 0.001D;
            if (score < bestDistance) { bestDistance = score; best = candidate.immutable(); }
        }
        return best;
    }

    private static boolean isStandableCell(ServerLevel level, BlockPos feet) {
        if (!level.isInWorldBounds(feet) || !level.getWorldBorder().isWithinBounds(feet)
                || !level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) return false;
        var at = level.getBlockState(feet);
        var head = level.getBlockState(feet.above());
        var floor = level.getBlockState(feet.below());
        return at.getCollisionShape(level, feet).isEmpty() && head.getCollisionShape(level, feet.above()).isEmpty()
                && at.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && !floor.getCollisionShape(level, feet.below()).isEmpty()
                && floor.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP);
    }

    public static String mine(CompanionEntity companion, BlockPos target) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (target == null) return "FAILED missing_target";
        if (!level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)) return "FAILED target_outside_world_bounds=" + target;
        if (!level.hasChunkAt(target)) return "FAILED target_chunk_not_loaded=" + target;
        if (companion.blockPosition().distSqr(target) > 256.0D * 256.0D) return "FAILED target_too_far=" + target;
        var state = level.getBlockState(target);
        if (state.isAir()) return "FAILED target_is_air=" + target;
        if (state.getDestroySpeed(level, target) < 0.0F) return "FAILED block_cannot_be_destroyed=" + target;
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online";
        UUID id = companion.getUUID();
        var queue = TASKS.computeIfAbsent(id,k->new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (queue.size() >= MAX_QUEUE) return "FAILED task_queue_full max=" + MAX_QUEUE;
        queue.addLast(new MineTask(target));
        com.yaseen.npcmind.debug.Trace.log("TASK", "mine START block={}", target);
        return "STARTED action_id=" + queue.peekLast().actionId + " mine_block target=" + target;
    }

    /**
     * Dig a safe 1x2 tunnel (or a 2-high staircase going down) in a horizontal direction.
     * Aborts instead of continuing when lava/water is next to the next step or a drop is ahead.
     * dirIndex: 0=N 1=E 2=S 3=W.
     */
    public static String tunnel(CompanionEntity companion, int dirIndex, int steps, boolean down) {
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online";
        var queue = TASKS.computeIfAbsent(companion.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (queue.size() >= MAX_QUEUE) return "FAILED task_queue_full max=" + MAX_QUEUE;
        net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST};
        queue.addLast(new TunnelTask(dirs[((dirIndex % 4) + 4) % 4], Math.max(1, Math.min(16, steps)), down));
        return "STARTED action_id=" + queue.peekLast().actionId + " tunnel dir=" + dirs[((dirIndex % 4) + 4) % 4] + " steps=" + steps + " down=" + down;
    }

    /**
     * Progressive building.
     *
     * <p>The old build_pattern / build_blueprint tools called {@code setBlockAndUpdate} in a tight
     * loop, so a whole wall appeared in a single server tick, from any distance, with no walking,
     * no reach check and no line of sight. That is not how a player builds and it is the main
     * reason "it does not build correctly".
     *
     * <p>This task ports the Altoclef {@code TaskChain} idea: the build is a sequential chain of
     * small sub-steps. For every target cell the companion must (1) be within real placement reach,
     * walking to a standable spot next to the cell if it is not, (2) re-validate the cell is still
     * replaceable and that the block can survive there, (3) pass the protection/claim check, and
     * only then (4) consume one real item and place the block, with a short pacing delay between
     * placements so the action is visible and interruptible.
     */
    public static String build(CompanionEntity companion, java.util.List<BlockPos> targets,
                               net.minecraft.world.level.block.Block block, String label) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (targets == null || targets.isEmpty()) return "FAILED no_build_targets";
        if (targets.size() > 1024) return "FAILED too_many_build_cells maximum=1024";
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) return "FAILED invalid_build_block";
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online; world mutation is blocked";
        BlockPos first = targets.get(0);
        if (first == null || !level.isInWorldBounds(first) || !level.getWorldBorder().isWithinBounds(first)) return "FAILED invalid_build_site";
        if (!level.hasChunkAt(first)) return "FAILED target_chunk_not_loaded";
        if (companion.blockPosition().distSqr(first) > 128.0D * 128.0D) return "FAILED build_site_too_far navigate there first";
        for (BlockPos target : targets) {
            if (target == null || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target))
                return "FAILED target_outside_world_bounds";
            if (companion.blockPosition().distSqr(target) > 128.0D * 128.0D)
                return "FAILED build_target_too_far target=" + target;
            if (!level.hasChunkAt(target)) return "FAILED build_target_chunk_not_loaded target=" + target;
        }
        cancelCurrentTask(companion);
        var queue = TASKS.computeIfAbsent(companion.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new BuildTask(new java.util.ArrayList<>(targets), block, label));
        com.yaseen.npcmind.debug.Trace.log("TASK", "build START block={} cells={}", block, targets.size());
        return "STARTED action_id=" + queue.peekLast().actionId + " build cells=" + targets.size() + " block=" + label;
    }

    /**
     * Queue a finishing block behind a larger build without cancelling the shell task. This is
     * used for multi-material house details such as a real door; each task still uses the normal
     * walk/reach/protection/BlockItem path and consumes its own real inventory item.
     */
    public static String buildWithFinisher(CompanionEntity companion, java.util.List<BlockPos> targets,
                                           net.minecraft.world.level.block.Block block, String label,
                                           BlockPos finishTarget, net.minecraft.world.level.block.Block finishBlock,
                                           String finishLabel) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (finishTarget == null || finishBlock == null || finishBlock == net.minecraft.world.level.block.Blocks.AIR)
            return "FAILED invalid_finishing_block";
        if (!level.isInWorldBounds(finishTarget) || !level.getWorldBorder().isWithinBounds(finishTarget)
                || !level.hasChunkAt(finishTarget)) return "FAILED finishing_target_not_loaded_or_out_of_bounds";
        if (companion.blockPosition().distSqr(finishTarget) > 128.0D * 128.0D)
            return "FAILED finishing_target_too_far";
        String result = build(companion, targets, block, label);
        if (result == null || !result.startsWith("STARTED")) return result;
        var queue = TASKS.get(companion.getUUID());
        if (queue == null || queue.isEmpty()) return result + " finishing_not_queued";
        queue.addLast(new BuildTask(java.util.List.of(finishTarget.immutable()), finishBlock,
                finishLabel == null || finishLabel.isBlank() ? label + "/finish" : finishLabel));
        return result + " finishing=queued";
    }

    /**
     * Append one more BuildTask group to the existing build queue WITHOUT cancelling the
     * running one. Used by the multi-material blueprint builder: each glyph group (main
     * block, door, glass, ...) becomes its own BuildTask, all executed in order through the
     * normal walk/reach/protection/BlockItem path. Bounds mirror {@link #build}.
     */
    public static String queueBuild(CompanionEntity companion, java.util.List<BlockPos> targets,
                                    net.minecraft.world.level.block.Block block, String label) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (targets == null || targets.isEmpty()) return "FAILED no_build_targets";
        if (targets.size() > 1024) return "FAILED too_many_build_cells maximum=1024";
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) return "FAILED invalid_build_block";
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online; world mutation is blocked";
        var queue = TASKS.get(companion.getUUID());
        if (queue == null || queue.isEmpty()) return "FAILED no_active_build_to_extend";
        for (BlockPos target : targets) {
            if (target == null || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)
                    || !level.hasChunkAt(target))
                return "FAILED build_target_not_loaded_or_out_of_bounds target=" + target;
            if (companion.blockPosition().distSqr(target) > 128.0D * 128.0D)
                return "FAILED build_target_too_far target=" + target;
        }
        queue.addLast(new BuildTask(new java.util.ArrayList<>(targets), block,
                label == null || label.isBlank() ? "blueprint/group" : label));
        return "QUEUED build cells=" + targets.size() + " block=" + label;
    }

    /**
     * Progressive farm preparation: till and plant one cell at a time, walking into real reach
     * first. Previously {@code till_and_plant} converted a whole 17x17 patch of dirt into farmland
     * and planted it in a single tick from wherever the companion happened to stand.
     */
    public static String tillAndPlant(CompanionEntity companion, java.util.List<BlockPos> ground, int radius) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (ground == null || ground.isEmpty()) return "FAILED no_tillable_ground radius=" + radius;
        if (ground.size() > 96) return "FAILED too_many_farm_cells maximum=96";
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online; world mutation is blocked";
        for (BlockPos target : ground) {
            if (target == null || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target))
                return "FAILED farm_target_outside_world_bounds";
            if (!level.hasChunkAt(target)) return "FAILED farm_target_chunk_not_loaded=" + target;
            if (companion.blockPosition().distSqr(target) > 128.0D * 128.0D) return "FAILED farm_too_far navigate there first target=" + target;
        }
        cancelCurrentTask(companion);
        var queue = TASKS.computeIfAbsent(companion.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new TillTask(new java.util.ArrayList<>(ground), radius));
        com.yaseen.npcmind.debug.Trace.log("TASK", "farm START cells={}", ground.size());
        return "STARTED action_id=" + queue.peekLast().actionId + " farm cells=" + ground.size();
    }

    /** Queue progressive harvesting of mature crops; each crop requires real reach and protection checks. */
    public static String harvestCrops(CompanionEntity companion, java.util.List<BlockPos> targets, boolean replant) {
        if (!(companion.level() instanceof ServerLevel level)) return "ERROR not_server";
        if (targets == null || targets.isEmpty()) return "FAILED no_crop_targets";
        if (targets.size() > 96) return "FAILED too_many_crop_targets maximum=96";
        if (!(companion.getOwnerPlayer() instanceof ServerPlayer)) return "DENIED owner is not online; world mutation is blocked";
        for (BlockPos target : targets) {
            if (target == null || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target))
                return "FAILED crop_target_outside_world_bounds";
            if (!level.hasChunkAt(target)) return "FAILED crop_target_chunk_not_loaded=" + target;
            if (companion.blockPosition().distSqr(target) > 128.0D * 128.0D)
                return "FAILED crop_target_too_far=" + target;
        }
        cancelCurrentTask(companion);
        var queue = TASKS.computeIfAbsent(companion.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        queue.addLast(new HarvestTask(new java.util.ArrayList<>(targets), replant));
        com.yaseen.npcmind.debug.Trace.log("TASK", "harvest START crops={} replant={}", targets.size(), replant);
        return "STARTED action_id=" + queue.peekLast().actionId + " harvest_crops targets=" + targets.size() + " replant=" + replant;
    }

    /** Takes (and clears) the last finished-task status so the quest director can learn why a task failed. */    public static String takeLastResult(CompanionEntity companion) {
        return LAST_RESULT.remove(companion.getUUID());
    }
    private static final Map<UUID, String> LAST_RESULT = new ConcurrentHashMap<>();

    /** Drops every task/result entry for a companion that died or was removed (leak fix). */
    public static void purge(UUID id) {
        if (id == null) return;
        TASKS.remove(id);
        LAST_RESULT.remove(id);
    }

    public static boolean isActive(CompanionEntity companion) {
        return companion != null && isActive(companion.getUUID());
    }

    /** Thread-safe task queue query for the local brain worker, which must not inspect the world. */
    public static boolean isActive(UUID companionId) {
        if (companionId == null) return false;
        var q = TASKS.get(companionId);
        return q != null && !q.isEmpty();
    }

    /** Cancel the current navigation/mining task and stop vanilla navigation immediately. */
    public static boolean cancelCurrentTask(CompanionEntity companion) {
        var q = TASKS.remove(companion.getUUID());
        NavigationArbiter.release(companion, "task");
        companion.getNavigation().stop();
        // A building task temporarily moves the player's selected block stack into the hand.
        // Restore the original hand atomically before a newer command replaces that task.
        if (q != null) {
            Task t = q.peekFirst();
            if (t != null) t.onCancel(companion);
            if (t instanceof BuildTask buildTask) buildTask.restoreBuildHand(companion);
            if (t != null) t.status = "CANCELLED by newer command";
            return true;
        }
        return false;
    }

    /** High-priority cancellation that publishes a terminal result for replanning. */
    public static boolean cancelForReflex(CompanionEntity companion, String reason) {
        boolean cancelled = cancelCurrentTask(companion);
        if (cancelled) LAST_RESULT.put(companion.getUUID(), "FAILED survival_reflex " + reason);
        return cancelled;
    }

    public static String status(CompanionEntity companion) {
        var q = TASKS.get(companion.getUUID());
        Task t = q == null ? null : q.peekFirst();
        return t == null ? "IDLE" : "action_id=" + t.actionId + " " + t.status + " queue=" + q.size();
    }

    public static void tick(ServerLevel level) {
        for (Map.Entry<UUID, java.util.concurrent.ConcurrentLinkedDeque<Task>> e : TASKS.entrySet()) {
            Entity entity = level.getEntity(e.getKey());
            if (!(entity instanceof CompanionEntity companion)) continue;
            var queue = e.getValue();
            Task task = queue.peekFirst();
            if (task == null) { TASKS.remove(e.getKey(), queue); continue; }
            task.tick(companion);
            if (task.done) {
                task.onFinish(companion);
                NavigationArbiter.release(companion, "task");
                com.yaseen.npcmind.debug.Trace.log("TASK", "DONE action_id={} status={}", task.actionId, com.yaseen.npcmind.debug.Trace.clip(task.status));
                LAST_RESULT.put(e.getKey(), task.status);
                queue.pollFirst();
            }
            if (queue.isEmpty()) TASKS.remove(e.getKey(), queue);
        }
    }

    private abstract static class Task {
        final String actionId = UUID.randomUUID().toString();
        String status = "RUNNING";
        boolean done;
        abstract void tick(CompanionEntity companion);
        void onFinish(CompanionEntity companion) {}
        void onCancel(CompanionEntity companion) { onFinish(companion); }
    }

    private static final class MoveTask extends Task {
        private static final int MAX_TOTAL_TICKS = 900;       // 45 seconds at 20 TPS
        private static final int STUCK_TICKS = 180;          // re-plan after 9 seconds without progress
        final BlockPos target;
        int ticks;
        int retries;
        int lastProgressTick;
        double bestDistance = Double.MAX_VALUE;
        MoveTask(BlockPos target) { this.target = target; }
        void tick(CompanionEntity c) {
            ticks++;
            double d = c.distanceToSqr(target.getX() + .5D, target.getY(), target.getZ() + .5D);
            if (d <= 2.25D) {
                NavigationArbiter.release(c, "task");
                status = "SUCCESS reached " + target;
                done = true;
                return;
            }
            if (d + 0.05D < bestDistance) { bestDistance = d; lastProgressTick = ticks; retries = 0; }

            if (c.tickCount % 10 == 0) {
                if (!NavigationArbiter.request(c, target, "task", 500, 1.15D)) retries++;
                else if (!c.getNavigation().isDone()) retries = 0;
            }
            if (ticks - lastProgressTick >= STUCK_TICKS) {
                // A path may legitimately finish at a local waypoint before the destination. Stop
                // it and let the arbiter plan the next segment instead of failing on isDone().
                c.getNavigation().stop();
                NavigationArbiter.release(c, "task");
                retries++;
                lastProgressTick = ticks;
            }
            if (ticks >= MAX_TOTAL_TICKS || retries >= 6) {
                NavigationArbiter.release(c, "task");
                c.getNavigation().stop();
                status = "FAILED navigation_timeout target=" + target + " distance=" + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(d));
                done = true;
            }
        }
    }

    private static final class MineTask extends Task {
        final BlockPos target;
        int miningTicks = 0;
        int requiredTicks = -1;
        int age = 0;
        boolean permitted = false;
        /** Main-hand item before the first tool swap; restored on finish so the next fight
         * does not start with a pickaxe because the weapon stayed swapped out. */
        ItemStack handBeforeMining = null;
        MineTask accessTask;
        final int accessDepth;
        MineTask(BlockPos target) { this(target, 0); }
        MineTask(BlockPos target, int accessDepth) { this.target = target; this.accessDepth = accessDepth; }

        @Override
        void onFinish(CompanionEntity c) {
            clearBreakProgress(c);
            restoreCombatHand(c);
            if (accessTask != null) accessTask.onFinish(c);
        }

        /** Swap the pre-mining item back into the main hand (weapon-first combat parity). */
        private void restoreCombatHand(CompanionEntity c) {
            if (handBeforeMining == null) return;
            ItemStack current = c.getMainHandItem();
            if (!ItemStack.isSameItemSameTags(current, handBeforeMining)) {
                if (!current.isEmpty()) {
                    ItemStack left = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(
                            c.getCompanionInventory(), current.copy());
                    if (!left.isEmpty()) c.spawnAtLocation(left);
                }
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        handBeforeMining.isEmpty() ? ItemStack.EMPTY : handBeforeMining.copy());
                c.getCompanionInventory().setChanged();
            }
            handBeforeMining = null;
        }

        @Override
        void onCancel(CompanionEntity c) {
            onFinish(c);
            if (accessTask != null) accessTask.onCancel(c);
        }

        private void clearBreakProgress(CompanionEntity c) {
            if (c.level() instanceof ServerLevel level && level.isLoaded(target)) {
                level.destroyBlockProgress(c.getId(), target, -1);
            }
        }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { status = "FAILED not server level"; done = true; return; }
            // A target the companion can never reach (buried ore, blocked path) used to keep the whole
            // task queue "active" forever and freeze every autonomous system. Give up after ~25 s.
            if (++age > 500) { status = "FAILED timeout unreachable " + target; done = true; return; }
            if (!level.isLoaded(target)) return;
            var state = level.getBlockState(target);
            if (state.isAir()) { status = "SUCCESS block already air"; done = true; return; }
            if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) { status = "DENIED owner is not online"; done = true; return; }

            // Finish an access-path child before preparing the parent's tool. Otherwise the parent
            // can swap the main hand every tick while the child is breaking its blocker.
            if (accessTask != null) {
                accessTask.tick(c);
                if (!accessTask.done) return;
                if (accessTask.done) accessTask.onFinish(c);
                if (accessTask.status.startsWith("FAILED") || accessTask.status.startsWith("DENIED")) {
                    status = "FAILED access_path " + accessTask.status;
                    done = true;
                    return;
                }
                accessTask = null;
                return;
            }

            // Ask protection/claim mods ONCE per block (it used to fire BreakEvent on every tick of mining).
            if (!permitted) {
                if (!level.mayInteract(owner, target) || ForgeHooks.onBlockBreakEvent(level, owner.gameMode.getGameModeForPlayer(), owner, target) == -1) {
                    status = "DENIED block protection or spawn protection at " + target; done = true; return;
                }
                permitted = true;
            }

            float hardness = state.getDestroySpeed(level, target);
            if (hardness < 0) { status = "FAILED block cannot be destroyed"; done = true; return; }

            // Behave like a player: choose a suitable real tool from the companion's inventory before mining.
            // Axes are preferred for logs/wood, pickaxes for stone/ores, and the hand
            // remains a valid fallback for blocks that can actually be broken by hand.
            if (handBeforeMining == null) handBeforeMining = c.getMainHandItem().copy();
            equipBestMiningTool(c, state);
            var tool = c.getMainHandItem();

            // A missing correct tool is NOT a reason to refuse the action.
            // The companion behaves like a player: if an axe/pickaxe is available it
            // uses the appropriate tool; otherwise it simply mines with its hand.
            // Vanilla may give fewer/no normal drops when the correct tool is missing,
            // but the block itself is still allowed to be broken.
            //
            // Exception (Altoclef MiningRequirement port): a block that literally cannot drop
            // anything without a minimum pickaxe tier (obsidian, ancient debris, diamond/iron/
            // redstone ore...) is refused up front with the exact tier needed, instead of
            // grinding for the 500-tick timeout and returning empty-handed.
            com.yaseen.npcmind.advanced.MiningRequirement requirement =
                    com.yaseen.npcmind.advanced.MiningRequirement.forBlock(state.getBlock());
            if (requirement != com.yaseen.npcmind.advanced.MiningRequirement.HAND
                    && !requirement.satisfiedBy(tool)
                    && !hasPickaxeTier(c, requirement)) {
                status = "FAILED needs_" + requirement.name().toLowerCase(java.util.Locale.ROOT)
                        + "_pickaxe to harvest " + state.getBlock().getName().getString();
                done = true;
                return;
            }

            net.minecraft.world.phys.Vec3 targetCenter = new net.minecraft.world.phys.Vec3(
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
            double eyeDistanceSqr = c.getEyePosition().distanceToSqr(targetCenter);
            double bodyDistanceSqr = c.distanceToSqr(targetCenter);

            // Unlike a path goal, a mined block is a solid cell and must never be used as a
            // navigation destination. If natural terrain blocks line of sight, clear that
            // blocker first (bounded to 8 nested blockers and 16 blocks from the NPC).
            BlockPos blocker = findAccessBlock(level, c, target);
            if (blocker != null && !blocker.equals(target)) {
                var blockerState = level.getBlockState(blocker);
                if (!isNaturalAccessBlock(blockerState)) {
                    BlockPos alternateStand = BuildTask.standSpot(level, target, c);
                    if (alternateStand == null || !hasClearLineFromStand(level, c, alternateStand, target)) {
                        status = "FAILED blocked_by_non_natural_block=" + blocker;
                        done = true;
                        return;
                    }
                    // Never mine the built obstruction as a shortcut. If it blocks the current
                    // view, travel to the validated stand from which the target is visible.
                    if (eyeDistanceSqr <= 20.25D) {
                        if (alternateStand.equals(c.blockPosition())) {
                            status = "FAILED blocked_by_non_natural_block=" + blocker;
                            done = true;
                            return;
                        }
                        if (!NavigationArbiter.request(c, alternateStand, "task", 500, 1.15D)) {
                            status = "FAILED no_path_around_non_natural_block=" + blocker;
                            done = true;
                            return;
                        }
                        status = "RUNNING routing_around_non_natural_block=" + blocker + " stand=" + alternateStand;
                        return;
                    }
                } else if (eyeDistanceSqr <= 20.25D
                        || (bodyDistanceSqr <= 64.0D && BuildTask.standSpot(level, target, c) == null)) {
                    if (accessDepth >= 8) {
                        status = "FAILED access_path_depth_limit target=" + target;
                        done = true;
                        return;
                    }
                    accessTask = new MineTask(blocker, accessDepth + 1);
                    status = "RUNNING opening_access_path blocker=" + blocker + " target=" + target;
                    accessTask.tick(c);
                    return;
                }
            }

            // Use the NPC's eye-to-block distance like a real player (~4.5 blocks of reach).
            // The old code compared squared body distance to 2.5 and then navigated directly into
            // the solid target, so the pathfinder often never reached a point from which it could
            // mine. A valid nearby stand cell is now the destination, not the ore/log block itself.
            if (eyeDistanceSqr > 20.25D) {
                BlockPos stand = BuildTask.standSpot(level, target, c);
                if (stand == null) {
                    status = "FAILED no_standable_access_to_target=" + target;
                    done = true;
                    return;
                }
                c.getLookControl().setLookAt(target.getX() + .5D, target.getY() + .5D, target.getZ() + .5D,
                        30.0F, 30.0F);
                NavigationArbiter.request(c, stand, "task", 500, 1.15);
                status = "RUNNING moving_to_mining_stand target=" + target + " stand=" + stand;
                c.playAnimationAction((byte) 0, 0);
                return;
            }

            // IMPORTANT: never call destroyBlock immediately when a mining task starts.
            // Minecraft block breaking is progressive; the old implementation completed
            // every queued MineTask on its first tick, which made an entire tree disappear
            // almost instantly. We now spend real server ticks mining each block and only
            // destroy it after the required progress has elapsed.
            if (requiredTicks < 0) {
                requiredTicks = calculateMiningTicks(c, level, state, target, tool);
                miningTicks = 0;
                c.getNavigation().stop();
            }

            c.getLookControl().setLookAt(target.getX() + .5D, target.getY() + .5D, target.getZ() + .5D,
                    30.0F, 30.0F);
            c.playAnimationAction((byte) 1, 5);
            miningTicks++;
            int crackStage = Math.max(0, Math.min(9, (int) ((miningTicks * 10.0D) / Math.max(1, requiredTicks))));
            level.destroyBlockProgress(c.getId(), target, crackStage);
            status = "RUNNING mining " + target + " progress=" + miningTicks + "/" + requiredTicks;
            // Server-side block mutation does not automatically create the player hand
            // animation. Broadcast a real swing periodically so clients see the axe/pickaxe
            // moving while the progressive break timer is running.
            if ((miningTicks & 3) == 1) c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

            if (miningTicks < requiredTicks) return;

            // Re-check the block immediately before the final break so a changed/removed
            // block cannot be destroyed accidentally after a long queue wait.
            var finalState = level.getBlockState(target);
            if (finalState.isAir()) { c.playAnimationAction((byte) 0, 0); status = "SUCCESS block changed to air while mining"; done = true; return; }
            if (!finalState.is(state.getBlock())) {
                c.playAnimationAction((byte) 0, 0);
                status = "FAILED block changed while mining";
                done = true;
                return;
            }

            c.enablePickup(200);
            // passes a pre-break copy to the loot system. Keep the same order here instead of
            // treating every tool as a generic durability item: modded tools may implement custom
            // mineBlock behavior, and the copied stack preserves Fortune/Silk Touch for drops.
            boolean harvest = !finalState.requiresCorrectToolForDrops()
                    || (!tool.isEmpty() && tool.isCorrectToolForDrops(finalState));
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(target);
            ItemStack toolForDrops = tool.copy();
            // Capturing drops before destroyBlock avoids container/modded-block loot
            // changing after the block entity is removed from the world.
            java.util.List<ItemStack> drops = harvest
                    ? net.minecraft.world.level.block.Block.getDrops(finalState, level, target, be, c, toolForDrops)
                    : java.util.List.of();
            boolean changed = level.destroyBlock(target, false, c);
            if (changed) {
                if (!tool.isEmpty()) tool.getItem().mineBlock(tool, level, finalState, target, c);
                for (ItemStack drop : drops) {
                    if (!drop.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, target, drop);
                }
                if (harvest) {
                    int fortune = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                            net.minecraft.world.item.enchantment.Enchantments.BLOCK_FORTUNE, toolForDrops);
                    int silkTouch = net.minecraft.world.item.enchantment.EnchantmentHelper.hasSilkTouch(toolForDrops) ? 1 : 0;
                    int experience = finalState.getExpDrop(level, level.getRandom(), target, fortune, silkTouch);
                    if (experience > 0) net.minecraft.world.entity.ExperienceOrb.award(level,
                            net.minecraft.world.phys.Vec3.atCenterOf(target), experience);
                }
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool);
            }
            level.destroyBlockProgress(c.getId(), target, -1);
            if (changed) {
                c.addFoodExhaustion(0.005F); // Vanilla player mining exhaustion is per block broken.
                c.getSkills().add(SkillProgression.Skill.MINING, 10);
                c.getCompanionState().positiveEvent();
            }
            c.playAnimationAction((byte) 0, 0);
            status = changed ? "SUCCESS mined " + target + " tool=" + (tool.isEmpty() ? "hand" : tool.getHoverName().getString()) : "FAILED destroyBlock returned false for " + target;
            done = true;
        }

        /**
         * Approximate vanilla-style progressive mining time for a server-side NPC.
         * Logs with an axe take several ticks rather than being destroyed instantly;
         * slower tools/hand mining take longer. The floor prevents queued tree logs
         * from all disappearing in the same tick even with very fast tools.
         */
        /** True when the companion owns any pickaxe at or above {@code requirement}. */
        private static boolean hasPickaxeTier(CompanionEntity c,
                com.yaseen.npcmind.advanced.MiningRequirement requirement) {
            if (requirement.satisfiedBy(c.getMainHandItem())) return true;
            var inv = c.getCompanionInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (requirement.satisfiedBy(stack)
                        && com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, stack, 1)) return true;
            }
            return false;
        }

        private static void equipBestMiningTool(CompanionEntity c, net.minecraft.world.level.block.state.BlockState state) {            ItemStack current = c.getMainHandItem();
            double bestScore = miningToolScore(current, state);
            int bestSlot = -1;
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                ItemStack candidate = c.getCompanionInventory().getItem(i);
                if (!candidate.isEmpty()
                        && !com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, candidate, 1)) continue;
                double score = miningToolScore(candidate, state);
                if (score > bestScore + 0.001D) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0) return;

            ItemStack candidate = c.getCompanionInventory().getItem(bestSlot).copy();
            ItemStack old = c.getMainHandItem().copy();
            c.getCompanionInventory().setItem(bestSlot, old);
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, candidate);
            c.getCompanionInventory().setChanged();
        }

        private static double miningToolScore(ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
            if (stack == null || stack.isEmpty()) return 0.25D;
            float speed = stack.getDestroySpeed(state);
            boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
            // Keep a non-optimal item in the hand when no proper tool exists; empty inventory
            // slots must not force an unnecessary equipment swap on every mining command.
            if (state.requiresCorrectToolForDrops() && !correct) return 0.25D;
            if (speed <= 0.0F) return 0.0D;
            // Correct tools dominate. Among valid tools, the fastest wins.
            return 1000.0D + speed;
        }

        private static BlockPos findAccessBlock(ServerLevel level, CompanionEntity c, BlockPos target) {
            net.minecraft.world.phys.Vec3 from = c.getEyePosition();
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, c));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
            BlockPos blocker = hit.getBlockPos();
            if (blocker.equals(target)) return null;
            var st = level.getBlockState(blocker);
            if (st.isAir() || !st.getFluidState().isEmpty()) return null;
            return blocker.immutable();
        }

        /** Tests whether a safe stand cell can see the requested block without mining a player-built obstruction. */
        private static boolean hasClearLineFromStand(ServerLevel level, CompanionEntity c, BlockPos stand, BlockPos target) {
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(
                    stand.getX() + 0.5D, stand.getY() + c.getEyeHeight(), stand.getZ() + 0.5D);
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
            var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, c));
            return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                    || target.equals(hit.getBlockPos());
        }

        private static boolean isNaturalAccessBlock(net.minecraft.world.level.block.state.BlockState st) {
            // Prefer semantic world-generation tags so modded stone/dirt variants can be cleared
            // just like vanilla terrain. Do not generalize to every solid block: that could mine
            // player-built walls, chests, machines, or protected structures without consent.
            return st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                    || st.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)
                    || st.is(net.minecraft.tags.BlockTags.DIRT)
                    || st.is(net.minecraft.tags.BlockTags.SAND)
                    || st.is(net.minecraft.tags.BlockTags.LOGS)
                    || st.is(net.minecraft.tags.BlockTags.LEAVES)
                    || st.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || st.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                    || st.is(net.minecraft.world.level.block.Blocks.SAND)
                    || st.is(net.minecraft.world.level.block.Blocks.RED_SAND)
                    || st.is(net.minecraft.world.level.block.Blocks.STONE)
                    || st.is(net.minecraft.world.level.block.Blocks.DEEPSLATE)
                    || st.is(net.minecraft.world.level.block.Blocks.TUFF)
                    || st.is(net.minecraft.world.level.block.Blocks.ANDESITE)
                    || st.is(net.minecraft.world.level.block.Blocks.DIORITE)
                    || st.is(net.minecraft.world.level.block.Blocks.GRANITE)
                    || st.is(net.minecraft.world.level.block.Blocks.NETHERRACK);
        }

        private static int calculateMiningTicks(CompanionEntity companion, ServerLevel level,
                                                net.minecraft.world.level.block.state.BlockState state,
                                                BlockPos pos, net.minecraft.world.item.ItemStack tool) {
            // Mining Fatigue, Aqua Affinity, airborne penalty, Efficiency and tool correctness.
            return Math.max(4, com.yaseen.npcmind.entity.PlayerLikeInteractionController
                    .calculateBreakTicks(companion, level, state, pos, tool));
        }
    }

    private static final class TunnelTask extends Task {
        final net.minecraft.core.Direction dir;
        final boolean down;
        int stepsLeft;
        MineTask current;
        final java.util.ArrayDeque<BlockPos> pending = new java.util.ArrayDeque<>();
        BlockPos nextFeet;
        int moveTicks;

        TunnelTask(net.minecraft.core.Direction dir, int steps, boolean down) {
            this.dir = dir; this.stepsLeft = steps; this.down = down;
        }

        private void fail(String why) { status = "FAILED " + why; done = true; }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { fail("not server level"); return; }
            if (current != null) {
                current.tick(c);
                if (!current.done) return;
                if (current.status.startsWith("DENIED") || current.status.startsWith("FAILED")) { fail("tunnel_blocked " + current.status); return; }
                current = null;
            }
            while (!pending.isEmpty()) {
                BlockPos t = pending.pollFirst();
                var st = level.getBlockState(t);
                if (st.isAir() || st.getCollisionShape(level, t).isEmpty()) continue;
                current = new MineTask(t);
                status = "RUNNING tunnel digging " + t;
                return;
            }
            if (nextFeet != null) {
                double d = c.distanceToSqr(nextFeet.getX() + 0.5, nextFeet.getY(), nextFeet.getZ() + 0.5);
                if (d <= 0.6D) {
                    nextFeet = null;
                    if (--stepsLeft <= 0) { status = "SUCCESS tunnel_done"; done = true; }
                    return;
                }
                moveTicks++;
                if (moveTicks % 10 == 1) NavigationArbiter.request(c, nextFeet, "task", 500, 1.0D);
                if (moveTicks > 140) fail("could_not_step_into_tunnel");
                return;
            }
            BlockPos feet = c.blockPosition();
            BlockPos ahead = feet.relative(dir);
            BlockPos n = down ? ahead.below() : ahead;
            if (!level.isInWorldBounds(ahead) || !level.isInWorldBounds(n)
                    || !level.getWorldBorder().isWithinBounds(ahead) || !level.getWorldBorder().isWithinBounds(n)) {
                fail("world_border_or_height_limit"); return;
            }
            // Hazards: never dig next to lava or water (flooding / burning), never walk off a drop.
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = n.offset(dx, dy, dz);
                if (!level.isInWorldBounds(q) || !level.getWorldBorder().isWithinBounds(q)) { fail("world_border_or_height_limit"); return; }
                if (!level.hasChunkAt(q)) { fail("unloaded_chunk_ahead"); return; }
                if (!level.getFluidState(q).isEmpty()) { fail("fluid_ahead"); return; }
            }
            if (!down) {
                BlockPos floor = n.below();
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) { fail("drop_ahead"); return; }
            }
            if (n.getY() <= level.getMinBuildHeight() + 6) { fail("too_deep"); return; }
            pending.addLast(ahead.above());
            pending.addLast(ahead);
            if (down) pending.addLast(n);
            nextFeet = n;
            moveTicks = 0;
            status = "RUNNING tunnel planning step";
        }
    }

    /**
     * One cell at a time, walk-then-place. See {@link #build} for why this exists.
     * A short cooldown between placements makes the build observable (the client sees the arm
     * swing and the block appear one by one) instead of a wall materialising in one tick.
     */
    private static final class BuildTask extends Task {
        private static final double REACH_SQR = 20.25D;    // ~4.5-block player placement reach
        private static final int MIN_PLACE_DELAY = 10;
        private static final int MAX_PLACE_DELAY = 28;
        private static final int MAX_MOVE_TICKS = 220;      // per cell, ~11 s to get in range

        final java.util.List<BlockPos> targets;
        final net.minecraft.world.level.block.Block block;
        final String label;
        int index;
        int placed;
        int alreadyPresent;
        int skipped;
        int moveTicks;
        int cooldown;
        int placementFailures;
        int equippedSourceSlot = -1;
        /** Second pass over skipped cells: terrain changes while the companion works through the
         * pattern (it builds supports, removes its own temporary blocks), so a cell that had no
         * support face on the first pass often becomes placeable later. */
        boolean secondPass = false;

        BuildTask(java.util.List<BlockPos> targets, net.minecraft.world.level.block.Block block, String label) {
            this.targets = targets;
            this.block = block;
            this.label = label;
        }

        private void finish(CompanionEntity companion, String why) {
            restoreBuildHand(companion);
            status = why + " placed=" + placed + " already_present=" + alreadyPresent + " skipped=" + skipped + " requested=" + targets.size() + " block=" + label;
            done = true;
        }

        /** Return any unused building stack to the inventory and restore the previous main-hand item. */
        private void restoreBuildHand(CompanionEntity companion) {
            if (companion == null) return;
            // The entity persists this swap marker. That also lets readAdditionalSaveData recover
            // correctly if the world saves while this transient TaskManager task is in progress.
            if (equippedSourceSlot >= 0 || companion.getBuildHandBackupSlot() >= 0) {
                companion.restoreBuildHandBackup();
            }
            equippedSourceSlot = -1;
        }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { finish(c, "FAILED not_server_level"); return; }
            if (cooldown > 0) { cooldown--; return; }
            if (index >= targets.size()) {
                // First finished pass: retry every skipped cell once (support faces may exist now).
                if (!secondPass && skipped > 0 && placed > 0) {
                    secondPass = true;
                    index = 0;
                    skipped = 0;
                    moveTicks = 0;
                    status = "RUNNING build_second_pass retrying skipped cells placed=" + placed;
                    return;
                }
                int satisfied = placed + alreadyPresent;
                if (satisfied == targets.size()) finish(c, "SUCCESS build_done");
                else if (satisfied > 0) finish(c, "PARTIAL build_done");
                else finish(c, "FAILED build_nothing_placed");
                return;
            }

            BlockPos pos = targets.get(index);
            if (!level.isLoaded(pos)) { index++; skipped++; moveTicks = 0; return; }

            var state = level.getBlockState(pos);
            if (state.is(block)) { alreadyPresent++; index++; moveTicks = 0; placementFailures = 0; return; } // already built
            if (!state.canBeReplaced()) { skipped++; index++; moveTicks = 0; placementFailures = 0; return; }   // occupied by something else

            if (!hasBuildBlock(c)) { finish(c, "PARTIAL out_of_material"); return; }

            double d = c.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            boolean placementAccess = d <= REACH_SQR
                    && com.yaseen.npcmind.entity.PlayerLikeInteractionController.canPlaceAt(level, c, pos, block);
            if (!placementAccess) {
                BlockPos stand = standSpot(level, pos, c);
                if (stand == null) {
                    // Do not spend the full watchdog interval retrying an impossible placement
                    // (for example, a cell surrounded by fluids or with no safe footing).
                    skipped++;
                    index++;
                    moveTicks = 0;
                    return;
                }
                if (!NavigationArbiter.request(c, stand, "task", 500, 1.1D)) {
                    moveTicks++;
                    status = "RUNNING waiting_for_build_path cell=" + pos + " placed=" + placed + "/" + targets.size();
                    if (moveTicks > MAX_MOVE_TICKS) { skipped++; index++; moveTicks = 0; }
                    return;
                }
                c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 30.0F, 30.0F);
                moveTicks++;
                status = "RUNNING walking_to_build_site cell=" + pos + " placed=" + placed + "/" + targets.size();
                if (moveTicks > MAX_MOVE_TICKS) { skipped++; index++; moveTicks = 0; }
                return;
            }

            // In range: behave like a player placing a block.
            c.getNavigation().stop();
            c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 30.0F, 30.0F);
            if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)
                    || !com.yaseen.npcmind.security.WorldActionSecurity.canPlaceBlock(c, owner, pos)) {
                skipped++; index++; moveTicks = 0; return;
            }
            if (!ensureBuildBlockInHand(c)) { finish(c, "PARTIAL out_of_material"); return; }
            // Unlike setBlockAndUpdate(defaultBlockState), this executes vanilla BlockItem.useOn
            // with the NPC's facing/sneak context. Stairs, logs, doors, slabs and other directional
            // blocks therefore receive the state a player would normally place.
            if (com.yaseen.npcmind.entity.PlayerLikeInteractionController.placeHeldBlock(level, c, pos, block)) {
                placementFailures = 0;
                consumeBuildBlock(c);
                c.playAnimationAction((byte) 2, 4);
                c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                c.getSkills().add(SkillProgression.Skill.BUILDING, 3);
                c.getCompanionState().positiveEvent();
                placed++;
                cooldown = MIN_PLACE_DELAY + c.getRandom().nextInt(MAX_PLACE_DELAY - MIN_PLACE_DELAY + 1);
            } else {
                // A transient placement rejection (mod hook, protection race, entity moved into
                // the cell) must not silently consume/skip the target on its first failed click.
                placementFailures++;
                if (placementFailures < 3) {
                    cooldown = 6;
                    status = "RUNNING retrying_block_placement cell=" + pos + " attempt=" + placementFailures;
                    return;
                }
                skipped++;
                placementFailures = 0;
            }
            index++;
            moveTicks = 0;
            status = "RUNNING building placed=" + placed + "/" + targets.size() + " cell=" + pos;
        }

        private boolean hasBuildBlock(CompanionEntity c) {
            ItemStack hand = c.getMainHandItem();
            if (!hand.isEmpty() && hand.getItem() instanceof net.minecraft.world.item.BlockItem
                    && net.minecraft.world.level.block.Block.byItem(hand.getItem()) == block
                    && com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, hand, 1)) return true;
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                // The current source slot stores the user's previous hand while a block stack is
                // equipped. It must not be mistaken for building material, even if the hand itself
                // happened to contain the same block type before construction started.
                if (i == equippedSourceSlot) continue;
                ItemStack st = c.getCompanionInventory().getItem(i);
                if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem
                        && net.minecraft.world.level.block.Block.byItem(st.getItem()) == block
                        && com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, st, 1)) return true;
            }
            return false;
        }

        private boolean ensureBuildBlockInHand(CompanionEntity c) {
            ItemStack hand = c.getMainHandItem();
            if (!hand.isEmpty() && hand.getItem() instanceof net.minecraft.world.item.BlockItem
                    && net.minecraft.world.level.block.Block.byItem(hand.getItem()) == block) return true;
            var inventory = c.getCompanionInventory();
            int nextSlot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (i == equippedSourceSlot) continue;
                ItemStack st = inventory.getItem(i);
                if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem
                        && net.minecraft.world.level.block.Block.byItem(st.getItem()) == block
                        && com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, st, 1)) {
                    nextSlot = i; break;
                }
            }
            if (nextSlot < 0) return false;

            ItemStack selectedBlockStack = inventory.getItem(nextSlot).copy();
            if (equippedSourceSlot < 0) {
                // Swap like a player selecting a hotbar block: store the old hand in this slot and
                // render the chosen stack in the NPC's real equipment hand.
                ItemStack oldHand = c.getMainHandItem().copy();
                inventory.setItem(nextSlot, oldHand);
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, selectedBlockStack);
                equippedSourceSlot = nextSlot;
                c.setBuildHandBackupSlot(nextSlot);
            } else {
                // The old source slot currently contains the previous hand. Move that backup to
                // the new source slot before switching stacks; never duplicate or discard either.
                ItemStack previousHand = inventory.getItem(equippedSourceSlot).copy();
                inventory.setItem(equippedSourceSlot, c.getMainHandItem().copy());
                inventory.setItem(nextSlot, previousHand);
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, selectedBlockStack);
                equippedSourceSlot = nextSlot;
                c.setBuildHandBackupSlot(nextSlot);
            }
            inventory.setChanged();
            return true;
        }

        private void consumeBuildBlock(CompanionEntity c) {
            ItemStack hand = c.getMainHandItem();
            if (!hand.isEmpty() && net.minecraft.world.level.block.Block.byItem(hand.getItem()) == block) {
                hand.shrink(1);
                if (hand.isEmpty()) c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                c.getCompanionInventory().setChanged();
                return;
            }
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                if (i == equippedSourceSlot) continue;
                ItemStack st = c.getCompanionInventory().getItem(i);
                if (!st.isEmpty() && net.minecraft.world.level.block.Block.byItem(st.getItem()) == block
                        && com.yaseen.npcmind.autonomy.ResourceLedger.owns(c, st, 1)) {
                    c.getCompanionInventory().removeItem(i, 1);
                    return;
                }
            }
        }

        /**
         * A standable cell next to {@code pos}: same level or one below, solid floor, two blocks of
         * head room, no fluid. Returns the closest valid one to the companion.
         */
        private static BlockPos standSpot(ServerLevel level, BlockPos pos, CompanionEntity c) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 1; dy >= -2; dy--) {
                if (dx == 0 && dz == 0 && dy == 0) continue;
                BlockPos p = pos.offset(dx, dy, dz);
                BlockPos head = p.above();
                BlockPos floor = p.below();
                if (!level.isInWorldBounds(p) || !level.isInWorldBounds(head) || !level.isInWorldBounds(floor)
                        || !level.getWorldBorder().isWithinBounds(p) || !level.hasChunkAt(p)
                        || !level.hasChunkAt(head) || !level.hasChunkAt(floor) || p.distSqr(c.blockPosition()) <= 0.25D) continue;
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue;
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
                if (!level.getFluidState(p).isEmpty() || !level.getFluidState(head).isEmpty()) continue;
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                        || !level.getBlockState(floor).isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)) continue;
                double d = c.distanceToSqr(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (d < bestD) { bestD = d; best = p.immutable(); }
            }
            return best;
        }
    }

    /**
     * Harvest mature vanilla crops sequentially. It uses the same walk-into-reach behavior as
     * building and mining instead of teleporting the action to every crop in a radius.
     */
    private static final class HarvestTask extends Task {
        private static final double REACH_SQR = 20.25D;
        private static final int HARVEST_COOLDOWN = 3;
        private static final int MAX_MOVE_TICKS = 220;
        final java.util.List<BlockPos> targets;
        final boolean replant;
        int index;
        int harvested;
        int replanted;
        int skipped;
        int moveTicks;
        int cooldown;

        HarvestTask(java.util.List<BlockPos> targets, boolean replant) {
            this.targets = targets;
            this.replant = replant;
        }

        private void finish(String reason) {
            String outcome = harvested == 0 ? "FAILED" : (skipped > 0 ? "PARTIAL" : "SUCCESS");
            status = outcome + " " + reason + " harvested=" + harvested + " replanted=" + replanted
                    + " skipped=" + skipped + " requested=" + targets.size();
            done = true;
        }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { finish("not_server_level"); return; }
            if (cooldown > 0) { cooldown--; return; }
            if (index >= targets.size()) { finish("harvest_done"); return; }
            if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) {
                status = "DENIED owner_offline"; done = true; return;
            }

            BlockPos pos = targets.get(index);
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.isLoaded(pos)) {
                skipped++; index++; moveTicks = 0; return;
            }
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) || !crop.isMaxAge(state)) {
                skipped++; index++; moveTicks = 0; return;
            }

            net.minecraft.world.phys.Vec3 eye = c.getEyePosition();
            net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D);
            boolean inReach = eye.distanceToSqr(center) <= REACH_SQR;
            boolean clearSight = false;
            if (inReach) {
                net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
                        eye, center, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, c));
                clearSight = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
            }
            if (!inReach || !clearSight) {
                BlockPos stand = BuildTask.standSpot(level, pos, c);
                if (stand == null) {
                    skipped++; index++; moveTicks = 0; return;
                }
                NavigationArbiter.request(c, stand, "task", 500, 1.1D);
                c.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);
                moveTicks++;
                status = "RUNNING walking_to_crop target=" + pos + " harvested=" + harvested + "/" + targets.size();
                if (moveTicks > MAX_MOVE_TICKS) {
                    skipped++; index++; moveTicks = 0; c.getNavigation().stop();
                }
                return;
            }

            c.getNavigation().stop();
            c.getLookControl().setLookAt(center.x, center.y, center.z, 30.0F, 30.0F);
            if (!com.yaseen.npcmind.security.WorldActionSecurity.canInteractWithBlock(c, owner, pos)
                    || net.minecraftforge.common.ForgeHooks.onBlockBreakEvent(level, owner.gameMode.getGameModeForPlayer(), owner, pos) == -1) {
                skipped++; index++; moveTicks = 0; return;
            }
            if (!level.destroyBlock(pos, true, c)) {
                skipped++; index++; moveTicks = 0; return;
            }
            harvested++;
            c.enablePickup(200);
            c.getSkills().add(SkillProgression.Skill.FARMING, 3);
            c.getCompanionState().positiveEvent();
            c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

            // Replant through the real seed ItemStack.useOn path. Prefer the held stack; otherwise
            // temporarily equip a matching inventory slot for this click and restore the old hand.
            if (replant) {
                BlockPos below = pos.below();
                if (level.hasChunkAt(below)
                        && level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.FARMLAND)
                        && level.getBlockState(pos).canBeReplaced()
                        && com.yaseen.npcmind.security.WorldActionSecurity.canPlaceBlock(c, owner, pos)) {
                    boolean didReplant = false;
                    if (com.yaseen.npcmind.tool.impl.FarmAreaTool.isSeedFor(state.getBlock(), c.getMainHandItem())) {
                        ItemStack updatedSeed = com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFace(
                                level, c, below, net.minecraft.core.Direction.UP, c.getMainHandItem(), pos, state.getBlock());
                        if (updatedSeed != null) {
                            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, updatedSeed);
                            c.getCompanionInventory().setChanged();
                            c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                            didReplant = true;
                        }
                    } else {
                        int seedSlot = com.yaseen.npcmind.tool.impl.FarmAreaTool.findSeedSlotFor(c, state.getBlock());
                        if (seedSlot >= 0) {
                            didReplant = com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFaceFromInventorySlot(
                                    level, c, below, net.minecraft.core.Direction.UP, seedSlot, pos, state.getBlock());
                        }
                    }
                    if (didReplant) replanted++;
                }
            }
            index++;
            moveTicks = 0;
            cooldown = HARVEST_COOLDOWN;
            status = "RUNNING harvesting harvested=" + harvested + " replanted=" + replanted + "/" + targets.size();
        }
    }

    private TaskManager() {}

    /**
     * One cell at a time: walk into reach, till the ground, then plant. Mirrors {@link BuildTask}
     * so a farm grows visibly instead of appearing all at once from across the field.
     */
    private static final class TillTask extends Task {
        private static final int COOLDOWN = 3;
        private static final int MAX_MOVE_TICKS = 220;

        final java.util.List<BlockPos> ground;
        final int radius;
        int index;
        int tilled;
        int planted;
        int skipped;
        int moveTicks;
        int cooldown;

        TillTask(java.util.List<BlockPos> ground, int radius) {
            this.ground = ground;
            this.radius = radius;
        }

        private void finish(String why) {
            String outcome = why.startsWith("SUCCESS") && skipped > 0 ? "PARTIAL" : why;
            status = outcome + " tilled=" + tilled + " planted=" + planted + " skipped=" + skipped + " cells=" + ground.size();
            done = true;
        }

        /** Use a real hoe, committing its wear back to either the hand or its inventory slot. */
        private boolean useHoe(ServerLevel level, CompanionEntity c, BlockPos pos) {
            if (c.getMainHandItem().getItem() instanceof net.minecraft.world.item.HoeItem) {
                ItemStack updated = com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFace(
                        level, c, pos, net.minecraft.core.Direction.UP, c.getMainHandItem(), pos,
                        net.minecraft.world.level.block.Blocks.FARMLAND);
                if (updated == null) return false;
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, updated);
                c.getCompanionInventory().setChanged();
                c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                return true;
            }
            var inv = c.getCompanionInventory();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                if (inv.getItem(slot).getItem() instanceof net.minecraft.world.item.HoeItem) {
                    return com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFaceFromInventorySlot(
                            level, c, pos, net.minecraft.core.Direction.UP, slot, pos,
                            net.minecraft.world.level.block.Blocks.FARMLAND);
                }
            }
            return false;
        }

        /** Plant with a real seed item while preserving the NPC's other held item. */
        private boolean plantCrop(ServerLevel level, CompanionEntity c, BlockPos support,
                                  net.minecraft.world.level.block.Block crop) {
            BlockPos target = support.above();
            if (com.yaseen.npcmind.tool.impl.TillAndPlantTool.isSeedForCrop(crop, c.getMainHandItem())) {
                ItemStack updated = com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFace(
                        level, c, support, net.minecraft.core.Direction.UP, c.getMainHandItem(), target, crop);
                if (updated == null) return false;
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, updated);
                c.getCompanionInventory().setChanged();
                c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                return true;
            }
            var inv = c.getCompanionInventory();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                if (com.yaseen.npcmind.tool.impl.TillAndPlantTool.isSeedForCrop(crop, inv.getItem(slot))) {
                    return com.yaseen.npcmind.entity.PlayerLikeInteractionController.useItemOnFaceFromInventorySlot(
                            level, c, support, net.minecraft.core.Direction.UP, slot, target, crop);
                }
            }
            return false;
        }

        void tick(CompanionEntity c) {
            if (!(c.level() instanceof ServerLevel level)) { finish("FAILED not_server_level"); return; }
            if (cooldown > 0) { cooldown--; return; }
            if (index >= ground.size()) {
                if (planted > 0 && skipped == 0) finish("SUCCESS farm_done");
                else if (planted > 0) finish("PARTIAL farm_done");
                else if (tilled > 0) finish("PARTIAL tilled_without_successful_planting");
                else finish("FAILED nothing_tillable_or_no_hoe");
                return;
            }
            if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) { finish("DENIED owner_offline"); return; }

            BlockPos pos = ground.get(index);
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.isLoaded(pos) || !level.isLoaded(pos.above())) {
                skipped++; index++; moveTicks = 0; return;
            }

            BlockState st = level.getBlockState(pos);
            boolean tillable = st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || st.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.FARMLAND);
            if (!tillable || !level.getBlockState(pos.above()).isAir()) {
                // Count every skipped cell: otherwise covered/rocky cells silently vanish and the
                // task can report SUCCESS farm_done while most of the plot was never processed.
                skipped++; index++; moveTicks = 0; return;
            }

            net.minecraft.world.phys.BlockHitResult soilHit =
                    com.yaseen.npcmind.entity.PlayerLikeInteractionController.findBlockFaceHit(
                            level, c, pos, net.minecraft.core.Direction.UP);
            if (soilHit == null) {
                BlockPos stand = BuildTask.standSpot(level, pos, c);
                if (stand != null) NavigationArbiter.request(c, stand, "task", 500, 1.1D);
                c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 30.0F, 30.0F);
                moveTicks++;
                status = "RUNNING walking_to_farm cell=" + pos + " tilled=" + tilled + " planted=" + planted;
                if (moveTicks > MAX_MOVE_TICKS) { skipped++; index++; moveTicks = 0; }
                return;
            }

            c.getNavigation().stop();
            c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 30.0F, 30.0F);
            if (!com.yaseen.npcmind.security.WorldActionSecurity.canPlaceBlock(c, owner, pos)) {
                skipped++; index++; moveTicks = 0; return;
            }

            if (!st.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                // A real player needs a hoe to convert soil into farmland; never fake-till with
                // setBlockAndUpdate. Vanilla useOn determines whether this soil can be tilled.
                if (!useHoe(level, c, pos)) {
                    skipped++; index++; moveTicks = 0; return;
                }
                tilled++;
                c.playAnimationAction((byte) 2, 4);
                c.getSkills().add(SkillProgression.Skill.FARMING, 2);
                // Re-read after the hoe interaction: vanilla useOn swapped the soil to farmland,
                // so the pre-till snapshot in `st` is now stale. Without this re-read the second
                // check below compared against the old GRASS/DIRT state and skipped planting on
                // every freshly tilled cell — tilling succeeded but crops were never planted.
                st = level.getBlockState(pos);
            }

            if (!st.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                skipped++; index++; moveTicks = 0; return;
            }
            net.minecraft.world.level.block.Block crop =
                    com.yaseen.npcmind.tool.impl.TillAndPlantTool.pickCropFor(c);
            if (crop != null && level.getBlockState(pos.above()).canBeReplaced()
                    && level.getBlockState(pos.above()).getFluidState().isEmpty()
                    && plantCrop(level, c, pos, crop)) {
                c.playAnimationAction((byte) 2, 4);
                c.getSkills().add(SkillProgression.Skill.FARMING, 3);
                planted++;
            } else {
                skipped++;
            }

            index++;
            moveTicks = 0;
            cooldown = COOLDOWN;
            status = "RUNNING farming tilled=" + tilled + " planted=" + planted + "/" + ground.size();
        }
    }

}
