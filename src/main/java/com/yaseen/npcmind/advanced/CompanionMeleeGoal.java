package com.yaseen.npcmind.advanced;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import com.yaseen.npcmind.api.Internal;

/**
 * Player-like melee fighting instead of vanilla's slow "walk straight in, hit once a second".
 * - Attack cadence follows the held weapon's real attack speed (sword ~0.65s).
 * - Vanilla survival player entity reach (3.0 blocks), measured from the attacker's eye to the target hitbox.
 * - Footwork: circles/strafes the target, steps back while its swing recharges, closes in to hit.
 */
@Internal
public final class CompanionMeleeGoal extends Goal {
    private static final double REACH = 3.0D;
    private static final double REACH_SQR = REACH * REACH;

    private final PathfinderMob mob;
    private final double speed;
    private final CombatMovement movement;
    private int attackTicker;
    private int strafeTimer;
    private int strafeDir = 1;

    public CompanionMeleeGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.movement = new CombatMovement(mob);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || t.isSpectator() || combatBlockedByWork(t)) return false;
        // The bow goal owns the fight while ranged mode is on and the companion can really shoot.
        return !(mob instanceof com.yaseen.npcmind.entity.CompanionEntity ce
                && ce.isRangedMode() && ce.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                && CompanionRangedGoal.findArrowSlot(ce) >= 0);
    }

    /** Task movement owns navigation until the NPC is actually threatened. */
    private boolean combatBlockedByWork(LivingEntity target) {
        if (!(mob instanceof CompanionEntity companion) || companion.isFriendlyCombat()
                || !TaskManager.isActive(companion)) return false;
        return !isImmediateThreat(companion, target);
    }

    private static boolean isImmediateThreat(CompanionEntity companion, LivingEntity target) {
        return (target == companion.getLastHurtByMob() && companion.distanceToSqr(target) <= 256.0D)
                || (target instanceof net.minecraft.world.entity.Mob attacker && attacker.getTarget() == companion);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        if (mob instanceof CompanionEntity companion && TaskManager.isActive(companion)
                && !companion.isFriendlyCombat() && isImmediateThreat(companion, companion.getTarget())) {
            TaskManager.cancelForReflex(companion, "direct_combat_threat");
        }
        mob.setAggressive(true);
        movement.reset();
        attackTicker = PlayerCombatParity.attackDelayTicks(mob);
        mob.setSprinting(false);
    }

    @Override
    public void stop() {
        if (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND) mob.stopUsingItem();
        mob.setAggressive(false);
        mob.setSprinting(false);
        movement.stop();
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
    }

    private double distSqrTo(LivingEntity t) {
        Vec3 eye = mob.getEyePosition();
        AABB bb = t.getBoundingBox();
        double dx = Math.max(Math.max(bb.minX - eye.x, 0.0D), eye.x - bb.maxX);
        double dy = Math.max(Math.max(bb.minY - eye.y, 0.0D), eye.y - bb.maxY);
        double dz = Math.max(Math.max(bb.minZ - eye.z, 0.0D), eye.z - bb.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private int cooldownTicks() {
        return cooldownTicksFor(mob.getMainHandItem());
    }

    /** Ticks between swings for the given main-hand item (sword ~13, axe ~20, bare hand 10). */
    public static int cooldownTicksFor(ItemStack s) {
        if (s == null || s.isEmpty()) return 10;
        double attackSpeed = 4.0D;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)) {
            attackSpeed += m.getAmount();
        }
        attackSpeed = Math.max(0.5D, Math.min(4.0D, attackSpeed));
        return Math.max(6, (int) Math.ceil(20.0D / attackSpeed));
    }

    /**
     * Raise the shield while waiting for the next swing (and the enemy is close), lower it just
     * before swinging. A shield needs ~5 ticks to become effective, so it is raised early.
     */
    private void handleShield(double d2, boolean eating) {
        if (!(mob.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) || eating) return;
        boolean usingShield = mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND;
        LivingEntity target = mob.getTarget();
        boolean enemySwing = target != null && target.swinging && target.swingTime >= 0 && target.swingTime < 8 && d2 <= 25.0D;
        boolean dangerClose = d2 <= 20.25D && attackTicker < PlayerCombatParity.attackDelayTicks(mob) - 5;
        // A swing of our own is ready and in reach: lower the shield this tick so the counterattack
        // is not swallowed by PlayerCombatParity.attack's isUsingItem() guard.
        boolean readyToStrike = attackTicker >= PlayerCombatParity.attackDelayTicks(mob) && d2 <= REACH_SQR;
        boolean wantUp = (enemySwing || dangerClose) && !readyToStrike;
        if (wantUp && !mob.isUsingItem()) mob.startUsingItem(InteractionHand.OFF_HAND);
        else if (!wantUp && usingShield) mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        attackTicker = Math.min(attackTicker + 1, PlayerCombatParity.attackDelayTicks(mob));

        double d2 = distSqrTo(t);
        double separation = (mob.getBbWidth() + t.getBbWidth()) * 0.5D + 0.25D;
        if (d2 > 30.0D) {
            // Far away: paced pursuit. CombatMovement keeps the route it already planned and
            // only allows a new A* search every 20-26 ticks, so chasing a running target no
            // longer re-paths every 4 ticks.
            boolean sprint = d2 > 64.0D;
            mob.setSprinting(sprint);
            movement.tick(t, REACH, sprint ? Math.max(1.35D, speed + 0.18D) : speed,
                    true, false, separation);
        } else {
            // Close combat: manual footwork.
            if (d2 <= separation * separation) {
                // Bodies touching: stop pushing, just keep the swing cadence going.
                mob.getNavigation().stop();
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
            } else {
            mob.getNavigation().stop();
            if (--strafeTimer <= 0) {
                // Hold a direction for a while; never alternate every few ticks.
                strafeTimer = 40 + mob.getRandom().nextInt(41);
                strafeDir = mob.getRandom().nextBoolean() ? 1 : -1;
            }
            boolean ready = attackTicker >= PlayerCombatParity.attackDelayTicks(mob);
            double fx = t.getX() - mob.getX();
            double fz = t.getZ() - mob.getZ();
            double len = Math.max(0.001D, Math.sqrt(fx * fx + fz * fz));
            fx /= len; fz /= len;
            double sx = -fz * strafeDir, sz = fx * strafeDir;
            double forward = ready ? 1.0D : (d2 < 5.0D ? -0.45D : 0.05D);
            double side = ready ? 0.18D : 0.42D;
            double px = mob.getX() + (fx * forward + sx * side) * 1.4D;
            double pz = mob.getZ() + (fz * forward + sz * side) * 1.4D;
            boolean sprint = d2 > 42.25D || (!ready && d2 > 9.0D);
            mob.setSprinting(sprint);
            mob.getMoveControl().setWantedPosition(px, mob.getY(), pz, sprint ? Math.max(1.35D, speed + 0.18D) : speed);
            // Sprinting alone does not make a human player jump; jump only to clear an actual obstacle.
            if (mob.horizontalCollision && mob.onGround()) mob.getJumpControl().jump();
            }
        }

        boolean eatingMain = mob instanceof com.yaseen.npcmind.entity.CompanionEntity ce2
                ? ce2.isEating()
                : (mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.MAIN_HAND);
        handleShield(d2, eatingMain);
        if (attackTicker >= PlayerCombatParity.attackDelayTicks(mob) && d2 <= REACH_SQR && !eatingMain && pathClear(t)) {
            if (PlayerCombatParity.attack(mob, t, attackTicker)) {
                attackTicker = 0;
            }
        }
    }

    /**
     * Strict melee reach test. Vanilla {@code hasLineOfSight} only casts a single eye-to-eye ray,
     * so a companion could still land hits through a 1-block gap, a fence, iron bars or glass
     * panes (their collision box is thin and the center ray slips past it). We cast rays from the
     * attacker's eye to three points on the target (eye, mid-body, feet). An attack is permitted
     * when at least one plausible sample ray is clear; this preserves natural attacks through door
     * gaps and other narrow openings while still rejecting a fully occluded target.
     */
    private boolean pathClear(LivingEntity t) {
        Vec3 eye = mob.getEyePosition();
        AABB bb = t.getBoundingBox();
        double cx = (bb.minX + bb.maxX) * 0.5D;
        double cz = (bb.minZ + bb.maxZ) * 0.5D;
        Vec3[] samples = new Vec3[] {
                new Vec3(cx, t.getEyeY(), cz),
                new Vec3(cx, (bb.minY + bb.maxY) * 0.5D, cz),
                new Vec3(cx, bb.minY + 0.1D, cz)
        };
        for (Vec3 target : samples) {
            net.minecraft.world.phys.BlockHitResult hit = mob.level().clip(new net.minecraft.world.level.ClipContext(
                    eye, target,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return true;
        }
        return false;
    }
}
