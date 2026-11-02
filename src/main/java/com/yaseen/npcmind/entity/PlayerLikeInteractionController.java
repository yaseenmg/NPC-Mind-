package com.yaseen.npcmind.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

/**
 * Player-like world interaction boundary for NPC Mind.
 *
 * not a FakePlayer. A short-lived server-side FakePlayer interaction context is therefore
 * used only when vanilla BlockItem placement requires a Player context; the actual NPC keeps
 * its own inventory, movement, permissions, and animation state.
 *
 * <p>Important invariants: this controller never chooses an unloaded support block, never
 * places outside the block reach, requires a ray trace to the exact supporting face, and
 * never consumes NPC inventory items itself. The task that owns the action commits inventory
 * changes only after the world confirms that the expected block was placed.
 */
@Internal
public final class PlayerLikeInteractionController {
    private static final double PLAYER_REACH_SQR = 4.5D * 4.5D;
    private static final Direction[] PLACEMENT_FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
    };
    private static final double[] FACE_SAMPLE_OFFSETS = {0.2D, 0.5D, 0.8D};

    private PlayerLikeInteractionController() {}

    /** Vanilla-style break duration using tool speed, correct-tool harvest, effects, water, and airborne penalty. */
    public static int calculateBreakTicks(LivingEntity actor, ServerLevel level, BlockState state,
                                          BlockPos pos, ItemStack tool) {
        if (actor == null || level == null || state == null || pos == null) return 20;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) return Integer.MAX_VALUE;
        if (hardness == 0.0F) return 1;

        ItemStack held = tool == null ? ItemStack.EMPTY : tool;
        float speed = held.isEmpty() ? 1.0F : held.getDestroySpeed(state);
        if (speed <= 0.0F) return 360; // capped slow fallback; never promote a bad tool to hand speed
        int efficiency = EnchantmentHelper.getBlockEfficiency(actor);
        if (efficiency > 0 && !held.isEmpty() && speed > 1.0F) speed += efficiency * efficiency + 1.0F;
        if (MobEffectUtil.hasDigSpeed(actor)) {
            speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(actor) + 1) * 0.2F;
        }
        if (actor.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            int amplifier = actor.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier();
            speed *= switch (amplifier) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        if (actor.isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(actor)) speed /= 5.0F;
        if (!actor.onGround()) speed /= 5.0F;

        boolean correctTool = !state.requiresCorrectToolForDrops() || (!held.isEmpty() && held.isCorrectToolForDrops(state));
        float relative = speed / hardness / (correctTool ? 30.0F : 100.0F);
        if (relative <= 0.0F || Float.isNaN(relative)) return 360;
        return Math.max(1, Math.min(360, (int) Math.ceil(1.0D / relative)));
    }

    /** Find a support face that can actually be ray-hit, including slabs/stairs/partial blocks. */
    public static BlockHitResult findPlacementHit(ServerLevel level, CompanionEntity companion, BlockPos target) {
        if (level == null || companion == null || target == null
                || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)
                || !level.hasChunkAt(target)) return null;
        if (!level.getBlockState(target).canBeReplaced()) return null;
        Vec3 eye = companion.getEyePosition();
        for (Direction face : PLACEMENT_FACE_ORDER) {
            BlockPos support = target.relative(face.getOpposite());
            if (!level.isInWorldBounds(support) || !level.getWorldBorder().isWithinBounds(support)
                    || !level.hasChunkAt(support)) continue;
            for (double a : FACE_SAMPLE_OFFSETS) {
                for (double b : FACE_SAMPLE_OFFSETS) {
                    Vec3 candidatePoint = faceSamplePoint(support, face, a, b);
                    if (eye.distanceToSqr(candidatePoint) > PLAYER_REACH_SQR) continue;
                    BlockHitResult ray = level.clip(new ClipContext(eye, candidatePoint,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, companion));
                    // OUTLINE plus several hit samples lets the NPC use the same partial surfaces
                    // a player can click on slabs, stairs, fences, trapdoors, and modded blocks.
                    if (ray.getType() == HitResult.Type.BLOCK
                            && support.equals(ray.getBlockPos()) && ray.getDirection() == face) {
                        return ray;
                    }
                }
            }
        }
        return null;
    }

    /** Construct a point near the requested face and inside the block's 1x1 coordinate box. */
    private static Vec3 faceSamplePoint(BlockPos pos, Direction face, double a, double b) {
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        switch (face) {
            case UP -> { x = pos.getX() + a; y = pos.getY() + 0.999D; z = pos.getZ() + b; }
            case DOWN -> { x = pos.getX() + a; y = pos.getY() + 0.001D; z = pos.getZ() + b; }
            case EAST -> { x = pos.getX() + 0.999D; y = pos.getY() + a; z = pos.getZ() + b; }
            case WEST -> { x = pos.getX() + 0.001D; y = pos.getY() + a; z = pos.getZ() + b; }
            case SOUTH -> { x = pos.getX() + a; y = pos.getY() + b; z = pos.getZ() + 0.999D; }
            case NORTH -> { x = pos.getX() + a; y = pos.getY() + b; z = pos.getZ() + 0.001D; }
        }
        return new Vec3(x, y, z);
    }

    /**
     * Use an item from the NPC inventory as if it were selected in the main hand for one click.
     * The NPC's prior hand and inventory slot are restored atomically in a finally block; the
     * updated/consumed source stack is committed to its original slot only when the world confirms
     * the requested state change. This prevents tool/seed swaps from losing the original hand.
     */
    public static boolean useItemOnFaceFromInventorySlot(ServerLevel level, CompanionEntity companion,
                                                          BlockPos clickedBlock, Direction face, int sourceSlot,
                                                          BlockPos expectedStatePos, Block expectedBlock) {
        if (level == null || companion == null || sourceSlot < 0
                || sourceSlot >= companion.getCompanionInventory().getContainerSize()) return false;
        var inventory = companion.getCompanionInventory();
        ItemStack source = inventory.getItem(sourceSlot);
        if (source.isEmpty()) return false;

        ItemStack originalHand = companion.getMainHandItem().copy();
        ItemStack originalSource = source.copy();
        inventory.setItem(sourceSlot, originalHand.copy());
        companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, originalSource.copy());
        inventory.setChanged();

        ItemStack updatedSource = null;
        try {
            updatedSource = useItemOnFace(level, companion, clickedBlock, face,
                    companion.getMainHandItem(), expectedStatePos, expectedBlock);
            if (updatedSource != null) {
                // Swing while the real NPC has the interacting item equipped, not after restoring
                // an unrelated sword/tool to its hand.
                companion.swing(InteractionHand.MAIN_HAND, true);
                return true;
            }
            return false;
        } finally {
            companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, originalHand);
            inventory.setItem(sourceSlot, updatedSource == null ? originalSource : updatedSource);
            inventory.setChanged();
        }
    }

    /** Convenience preflight used by the task layer before walking to a placement site. */
    public static boolean canPlaceAt(ServerLevel level, CompanionEntity companion, BlockPos target, Block block) {
        return level != null && companion != null && target != null && block != null
                && findPlacementHit(level, companion, target) != null
                && !hasPlacementCollision(level, companion, target, block);
    }

    /**
     * Find a visible face on an existing block for hoe/seed item use, respecting player reach and
     * aiming. It samples a small grid to avoid missing partial collision/outline shapes.
     */
    public static BlockHitResult findBlockFaceHit(ServerLevel level, CompanionEntity companion,
                                                   BlockPos clickedBlock, Direction face) {
        if (level == null || companion == null || clickedBlock == null || face == null
                || !level.isInWorldBounds(clickedBlock) || !level.getWorldBorder().isWithinBounds(clickedBlock)
                || !level.hasChunkAt(clickedBlock)) return null;
        Vec3 eye = companion.getEyePosition();
        for (double a : FACE_SAMPLE_OFFSETS) {
            for (double b : FACE_SAMPLE_OFFSETS) {
                Vec3 point = faceSamplePoint(clickedBlock, face, a, b);
                if (eye.distanceToSqr(point) > PLAYER_REACH_SQR) continue;
                BlockHitResult ray = level.clip(new ClipContext(eye, point,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, companion));
                if (ray.getType() == HitResult.Type.BLOCK && clickedBlock.equals(ray.getBlockPos())
                        && ray.getDirection() == face) return ray;
            }
        }
        return null;
    }

    /**
     * Run a real ItemStack.useOn interaction against an existing block face and verify the world
     * change before returning an updated copy of the source stack. This covers vanilla hoe tilling
     * and ItemNameBlockItem crops (wheat seeds, carrots, potatoes, beetroot), preserving item hooks,
     * Forge events and durability semantics instead of directly writing crop/farmland states.
     *
     * @return the updated source stack on success (possibly EMPTY if consumed/broken), or null on
     *         failure. The caller must install the returned stack in its original inventory slot.
     */
    public static ItemStack useItemOnFace(ServerLevel level, CompanionEntity companion,
                                          BlockPos clickedBlock, Direction face, ItemStack sourceStack,
                                          BlockPos expectedStatePos, Block expectedBlock) {
        if (level == null || companion == null || clickedBlock == null || sourceStack == null
                || sourceStack.isEmpty() || expectedStatePos == null || expectedBlock == null
                || !level.isInWorldBounds(expectedStatePos) || !level.getWorldBorder().isWithinBounds(expectedStatePos)
                || !level.hasChunkAt(expectedStatePos)) return null;
        BlockHitResult hit = findBlockFaceHit(level, companion, clickedBlock, face);
        if (hit == null) return null;
        BlockState before = level.getBlockState(expectedStatePos);
        if (!expectedStatePos.equals(clickedBlock) && !before.canBeReplaced()) return null;

        FakePlayer actor = getInteractionPlayer(level, companion);
        ItemStack interactionStack = sourceStack.copy();
        interactionStack.setCount(1);
        actor.setPos(companion.getX(), companion.getY(), companion.getZ());
        Vec3 delta = hit.getLocation().subtract(companion.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(delta.y, Math.max(1.0E-5D, horizontal)) * (180.0D / Math.PI)));
        actor.setYRot(yaw);
        actor.setYHeadRot(yaw);
        actor.setYBodyRot(yaw);
        actor.setXRot(pitch);
        actor.setShiftKeyDown(companion.isShiftKeyDown());
        actor.setItemInHand(InteractionHand.MAIN_HAND, interactionStack);
        try {
            InteractionResult result = interactionStack.useOn(new net.minecraft.world.item.context.UseOnContext(
                    level, actor, InteractionHand.MAIN_HAND, interactionStack, hit));
            BlockState after = level.getBlockState(expectedStatePos);
            if (!after.is(expectedBlock) || (after.equals(before) && !result.consumesAction())) return null;

            if (sourceStack.isDamageableItem()) {
                // Copy the interaction result back so vanilla tool damage (including breakage) is
                // committed only if the world-side action actually succeeded.
                if (interactionStack.isEmpty()) return ItemStack.EMPTY;
                ItemStack updated = interactionStack.copy();
                updated.setCount(sourceStack.getCount());
                return updated;
            }
            ItemStack updated = sourceStack.copy();
            updated.shrink(1);
            return updated;
        } catch (RuntimeException exception) {
            com.yaseen.npcmind.debug.Trace.log("INTERACTION", "item-use failed target={} expected={} error={}",
                    expectedStatePos, expectedBlock, exception.getClass().getSimpleName());
            return null;
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }

    /**
     * Invoke the real BlockItem.useOn path with player orientation/shift state. Returns true only
     * if the expected block appears at the target; caller owns the corresponding inventory commit.
     */
    public static boolean placeHeldBlock(ServerLevel level, CompanionEntity companion,
                                         BlockPos target, Block expectedBlock) {
        if (level == null || companion == null || target == null || expectedBlock == null
                || !level.isInWorldBounds(target) || !level.getWorldBorder().isWithinBounds(target)
                || !level.hasChunkAt(target)) return false;
        ItemStack npcHand = companion.getMainHandItem();
        if (npcHand.isEmpty() || !(npcHand.getItem() instanceof BlockItem)
                || Block.byItem(npcHand.getItem()) != expectedBlock) return false;
        BlockState before = level.getBlockState(target);
        if (!before.canBeReplaced()) return false;
        BlockHitResult hit = findPlacementHit(level, companion, target);
        if (hit == null || hasPlacementCollision(level, companion, target, expectedBlock)) return false;

        FakePlayer actor = getInteractionPlayer(level, companion);
        ItemStack interactionStack = npcHand.copy();
        interactionStack.setCount(1);
        actor.setPos(companion.getX(), companion.getY(), companion.getZ());
        // BlockItem derives stair/log/door orientation from the player's view rotation, not
        // from the click face. Point the interaction context at the clicked support face so
        // directional blocks are placed as a player looking at that face would place them.
        Vec3 eye = companion.getEyePosition();
        Vec3 delta = hit.getLocation().subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(delta.y, Math.max(1.0E-5D, horizontal)) * (180.0D / Math.PI)));
        actor.setYRot(yaw);
        actor.setYHeadRot(yaw);
        actor.setYBodyRot(yaw);
        actor.setXRot(pitch);
        actor.setShiftKeyDown(companion.isShiftKeyDown());
        actor.setItemInHand(InteractionHand.MAIN_HAND, interactionStack);
        try {
            // The context stack is a one-item copy. NPC inventory consumption is committed by
            // TaskManager only after comparing the resulting world state, avoiding item loss on FAIL.
            InteractionResult result = interactionStack.useOn(new net.minecraft.world.item.context.UseOnContext(
                    level, actor, InteractionHand.MAIN_HAND, interactionStack, hit));
            BlockState after = level.getBlockState(target);
            return after.is(expectedBlock) && (result.consumesAction() || !after.equals(before));
        } catch (RuntimeException exception) {
            com.yaseen.npcmind.debug.Trace.log("INTERACTION", "block-placement failed target={} expected={} error={}",
                    target, expectedBlock, exception.getClass().getSimpleName());
            return false;
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }

    /**
     * A FakePlayer is not the visible CompanionEntity, so vanilla's entity-placement collision
     * test alone cannot protect the NPC from placing a solid block through its own body. Mirror
     */
    private static boolean hasPlacementCollision(ServerLevel level, CompanionEntity companion,
                                                 BlockPos target, Block expectedBlock) {
        BlockState candidate = expectedBlock.defaultBlockState();
        AABB ownBox = companion.getBoundingBox().inflate(0.05D);
        for (net.minecraft.world.phys.AABB local : candidate.getCollisionShape(level, target).toAabbs()) {
            AABB worldBox = local.move(target).inflate(0.05D);
            if (worldBox.intersects(ownBox)) return true;
            for (net.minecraft.world.entity.Entity entity : level.getEntities(companion, worldBox,
                    e -> e.isAlive() && !e.isRemoved() && !e.isSpectator())) {
                if (worldBox.intersects(entity.getBoundingBox())) return true;
            }
        }
        return false;
    }

    private static FakePlayer getInteractionPlayer(ServerLevel level, CompanionEntity companion) {
        // Never use FakePlayerFactory here: it caches by (level, GameProfile), which lets two
        // companions owned by the same online player mutate the same position/hand context.
        // A fresh object per action prevents cross-NPC state contamination while preserving the
        // owner's UUID for claim/permission mods when the owner is online.
        GameProfile profile;
        if (companion.getOwnerPlayer() instanceof net.minecraft.server.level.ServerPlayer owner) {
            profile = owner.getGameProfile();
        } else {
            String id = companion.getUUID().toString().replace("-", "");
            UUID profileId = UUID.nameUUIDFromBytes(("npcmind-player-context:" + id).getBytes(StandardCharsets.UTF_8));
            String profileName = "NPCM" + id.substring(0, 12); // legal 16-character player profile name
            profile = new GameProfile(profileId, profileName);
        }
        FakePlayer player = new FakePlayer(level, profile);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }
}
