package com.yaseen.npcmind.api;

import com.yaseen.npcmind.advanced.PlayerCombatParity;
import com.yaseen.npcmind.core.ServerThreadGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

/**
 * Small entity-agnostic action primitives for host mods. These do not spawn entities, own goals,
 * or create inventory. Host AI decides when to call them and validates its own game-specific policy.
 */
public final class NPCMindActions {
    private NPCMindActions() {}

    /**
     * Request a path to a real, loaded, walkable feet position. No chunk is force-loaded and no
     * block is changed. Returns false if the destination is invalid or navigation rejects it.
     */
    public static boolean navigateTo(PathfinderMob actor, BlockPos feetPosition, double speed) {
        if (!isServerActor(actor) || feetPosition == null || !Double.isFinite(speed) || speed <= 0.0D || speed > 2.0D) return false;
        if (!(actor.level() instanceof ServerLevel level) || !level.isInWorldBounds(feetPosition)
                || !level.getWorldBorder().isWithinBounds(feetPosition) || !level.isLoaded(feetPosition)
                || !level.isLoaded(feetPosition.above()) || !level.isLoaded(feetPosition.below())) return false;
        if (!level.getBlockState(feetPosition).getCollisionShape(level, feetPosition).isEmpty()) return false;
        if (!level.getBlockState(feetPosition.above()).getCollisionShape(level, feetPosition.above()).isEmpty()) return false;
        if (level.getBlockState(feetPosition.below()).getCollisionShape(level, feetPosition.below()).isEmpty()) return false;
        PathNavigation navigation = actor.getNavigation();
        return navigation.moveTo(feetPosition.getX() + 0.5D, feetPosition.getY(), feetPosition.getZ() + 0.5D, speed);
    }

    /** Stop only the actor's current navigation path; does not clear targets or cancel host tasks. */
    public static void stopNavigation(PathfinderMob actor) {
        if (isServerActor(actor)) actor.getNavigation().stop();
    }

    /** Jump once when a path-following entity actually hits a horizontal obstacle while grounded. */
    public static boolean jumpIfBlocked(PathfinderMob actor) {
        if (!isServerActor(actor) || !actor.onGround() || !actor.horizontalCollision || actor.getNavigation().isDone()) return false;
        actor.getJumpControl().jump();
        return true;
    }

    /** Set vanilla sprint state. The host AI remains responsible for hunger/exhaustion rules. */
    public static boolean setSprinting(LivingEntity actor, boolean sprinting) {
        if (!isServerActor(actor)) return false;
        actor.setSprinting(sprinting);
        return actor.isSprinting() == sprinting;
    }

    /** Set crouch state and pose where the target entity supports the vanilla crouching pose. */
    public static boolean setCrouching(LivingEntity actor, boolean crouching) {
        if (!isServerActor(actor)) return false;
        // Poses the engine owns: forcing CROUCHING/STANDING over them would break swimming
        // visuals, sleeping mobs, gliding entities and passengers.
        if (actor.isPassenger() || actor.getPose() == Pose.SLEEPING || actor.getPose() == Pose.SWIMMING
                || actor.getPose() == Pose.FALL_FLYING || actor.getPose() == Pose.DYING) return false;
        actor.setShiftKeyDown(crouching);
        Pose desired = crouching ? Pose.CROUCHING : Pose.STANDING;
        if (actor.getPose() != desired && (crouching || actor.getPose() == Pose.CROUCHING)) actor.setPose(desired);
        return actor.isShiftKeyDown() == crouching;
    }

    /** Run the library's cooldown/critical/enchantment/durability combat rules for any PathfinderMob. */
    public static boolean attackWithPlayerRules(PathfinderMob attacker, LivingEntity target, int elapsedChargeTicks) {
        if (!isServerActor(attacker) || target == null || target.level() != attacker.level() || !target.isAlive()) return false;
        return PlayerCombatParity.attack(attacker, target, Math.max(0, elapsedChargeTicks));
    }

    public static int playerLikeAttackDelayTicks(LivingEntity actor) {
        if (!(actor instanceof PathfinderMob pathfinder) || !isServerActor(actor)) return 20;
        return PlayerCombatParity.attackDelayTicks(pathfinder);
    }

    /** Entity-agnostic collision/line-of-sight test before an interaction or attack. */
    public static boolean canInteractWith(LivingEntity actor, LivingEntity target, double reach) {
        return NPCMindAPI.canReachEntity(actor, target, reach);
    }

    /** Entity-agnostic check for a block hit/location point. */
    public static boolean canInteractAt(LivingEntity actor, Vec3 point, double reach) {
        return NPCMindAPI.canReachPoint(actor, point, reach);
    }

    private static boolean isServerActor(Entity actor) {
        return ServerThreadGuard.isOnServerThread(actor) && actor.isAlive();
    }
}
