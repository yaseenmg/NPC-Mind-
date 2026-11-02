package com.yaseen.npcmind.advanced;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.yaseen.npcmind.api.Internal;

/**
 * Server-side reproduction of the important Minecraft 1.20.1 Player#attack rules
 * for an NPC entity: attack-speed cooldown scaling, critical hits, sprint knockback,
 * enchantment damage, fire aspect, sweeping and weapon durability.
 *
 * The companion remains a normal LivingEntity/PathfinderMob for Forge compatibility;
 * this class makes its combat calculation player-like instead of using Mob#doHurtTarget,
 * which has different cadence and damage semantics.
 */
@Internal
public final class PlayerCombatParity {
    private PlayerCombatParity() {}


    /**
     * Shared final combat guard. Every caller, not only the melee AI goal, must prove that at
     * least one plausible part of the target is visible through collision geometry. This prevents
     * tool/AI entry points from bypassing the wall check.
     */
    public static boolean hasClearAttackLine(PathfinderMob attacker, LivingEntity target) {
        return com.yaseen.npcmind.rules.PlayerActionRules.canReachEntity(attacker, target, 3.0D);
    }

    public static int attackDelayTicks(PathfinderMob mob) {
        double speed = mob.getAttributeValue(Attributes.ATTACK_SPEED);
        return Math.max(1, (int)Math.ceil(20.0D / Math.max(0.1D, speed)));
    }

    public static float strength(PathfinderMob mob, int ticker, float partialTick) {
        double delay = 20.0D / Math.max(0.1D, mob.getAttributeValue(Attributes.ATTACK_SPEED));
        return Mth.clamp((float)((ticker + partialTick) / delay), 0.0F, 1.0F);
    }

    public static boolean attack(PathfinderMob attacker, LivingEntity target, int attackTicker) {
        if (attacker.level().isClientSide() || !target.isAlive() || attacker.isUsingItem()
                || !hasClearAttackLine(attacker, target)) return false;
        ItemStack weapon = attacker.getMainHandItem();
        float strength = strength(attacker, attackTicker, 0.5F);
        if (strength < 0.9F) return false;

        double raw = attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float damage = (float)(raw * (0.2F + strength * strength * 0.8F));
        MobType type = target.getMobType();
        float enchantDamage = EnchantmentHelper.getDamageBonus(weapon, type);
        damage += enchantDamage * strength;

        boolean critical = strength > 0.9F
                && attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                && !attacker.isPassenger()
                && !attacker.isSprinting();

        boolean sprintAttack = attacker.isSprinting() && strength > 0.9F;
        if (critical) damage *= 1.5F;

        // Sparring safety. combat_target mode=owner lets the player ask the companion to fight
        // them, but a friendly spar must never be able to kill the owner. Cap the damage so a
        // sparring hit cannot take the player below 2 hearts.
        if (target instanceof net.minecraft.world.entity.player.Player
                && attacker instanceof com.yaseen.npcmind.entity.CompanionEntity npc
                && npc.isFriendlyCombat()) {
            float floor = 4.0F;
            float allowed = Math.max(0.0F, target.getHealth() - floor);
            damage = Math.min(damage, allowed);
            if (damage <= 0.0F) return false;
        }

        net.minecraft.world.damagesource.DamageSource source = attacker.damageSources().mobAttack(attacker);
        boolean hurt = target.hurt(source, damage);
        if (!hurt) return false;
        if (attacker instanceof com.yaseen.npcmind.entity.CompanionEntity companion) {
            companion.addFoodExhaustion(0.1F); // Charge exhaustion only for a successful hit.
        }

        if (critical) {
            attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else if (sprintAttack) {
            attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else {
            attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.0F);
        }

        if (sprintAttack) {
            float yaw = attacker.getYRot() * ((float)Math.PI / 180F);
            target.knockback(0.5D, Mth.sin(yaw), -Mth.cos(yaw));
            attacker.setDeltaMovement(attacker.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D));
            attacker.setSprinting(false);
        } else {
            int knockback = EnchantmentHelper.getKnockbackBonus(attacker);
            if (knockback > 0) {
                float yaw = attacker.getYRot() * ((float)Math.PI / 180F);
                target.knockback(0.5D * knockback, Mth.sin(yaw), -Mth.cos(yaw));
            }
        }

        int fireAspect = EnchantmentHelper.getFireAspect(attacker);
        if (fireAspect > 0 && !target.fireImmune()) {
            target.setSecondsOnFire(fireAspect * 4);
        }

        if (weapon.getItem() instanceof SwordItem
                && strength > 0.9F
                && !critical
                && !sprintAttack
                && attacker.onGround()
                && horizontalSpeed(attacker) < attacker.getSpeed()) {
            float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(attacker);
            if (sweepRatio > 0.0F) {
                doSweep(attacker, target, damage, sweepRatio);
            }
        }

        if (!weapon.isEmpty() && weapon.isDamageableItem()) {
            // Vanilla swords and most dedicated weapons lose one durability per hit;
            // mining tools used as weapons lose two, as in their Item#hurtEnemy rule.
            int durabilityCost = weapon.getItem() instanceof net.minecraft.world.item.DiggerItem ? 2 : 1;
            weapon.hurtAndBreak(durabilityCost, attacker, e -> e.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        }
        attacker.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static double horizontalSpeed(PathfinderMob e) {
        Vec3 v = e.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static void doSweep(PathfinderMob attacker, LivingEntity primary, float damage, float ratio) {
        double range = 1.0D;
        AABB box = primary.getBoundingBox().inflate(range, 0.25D, range);
        Entity owner = (attacker instanceof com.yaseen.npcmind.entity.CompanionEntity comp) ? comp.getOwnerPlayer() : null;
        for (LivingEntity other : attacker.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == attacker || other == primary || other.isAlliedTo(attacker) || other.isDeadOrDying()) continue;
            // Never let a companion's sweep attack hit its own owner (or any player it isn't
            // explicitly targeting): the owner is not a team-mate for isAlliedTo, so friendly
            // fire used to leak through the sweep even though the primary target was a mob.
            if (other == owner) continue;
            if (other instanceof Player && other != primary) continue;
            if (attacker.distanceToSqr(other) >= 9.0D) continue;
            float sweepDamage = 1.0F + damage * ratio;
            other.hurt(attacker.damageSources().mobAttack(attacker), sweepDamage);
            float yaw = attacker.getYRot() * ((float)Math.PI / 180F);
            other.knockback(0.4D, Mth.sin(yaw), -Mth.cos(yaw));
        }
        attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
