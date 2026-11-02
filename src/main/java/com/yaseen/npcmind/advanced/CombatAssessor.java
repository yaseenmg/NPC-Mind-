package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import com.yaseen.npcmind.api.Internal;

/**
 * Decides whether a fight is worth taking, from the REAL stats of both sides
 * (health, armor, toughness, weapon damage, swing speed), using the vanilla armor formula.
 */
@Internal
public final class CombatAssessor {
    public record Verdict(boolean fight, double ratio, double lossFraction, String reason) {}

    /** Vanilla armor damage reduction. */
    static double reduce(double damage, double armor, double toughness) {
        double f = 2.0D + toughness / 4.0D;
        double f1 = Math.max(armor * 0.2D, Math.min(20.0D, armor - damage / f));
        return damage * (1.0D - f1 / 25.0D);
    }

    /**
     * Conservative baseline estimates for enemies whose damage is not a simple melee swing.
     * Deliberately static and pessimistic: they are a quick sizing input for the assess
     * budget below, never a win guarantee (difficulty, arrow type and enchantments are not
     * modelled here — the thresholds in {@link #assess} absorb that uncertainty).
     */
    static double rawDamage(LivingEntity e) {
        if (e instanceof Creeper) return 12.0D;          // explosion
        if (e instanceof AbstractSkeleton) return 4.0D;   // arrows
        AttributeInstance a = e.getAttribute(Attributes.ATTACK_DAMAGE);
        return a == null ? 3.0D : a.getValue();
    }

    static double swings(LivingEntity e) {
        if (e instanceof Player p) {
            AttributeInstance a = p.getAttribute(Attributes.ATTACK_SPEED);
            return a == null ? 1.0D : Math.max(0.5D, Math.min(4.0D, a.getValue()));
        }
        return 1.0D;
    }

    /**
     * Weighted threat score. Higher = better target to
     * engage. Combines the enemy's REAL stats (health, armor, weapon DPS, weapon/offhand
     * enchantments, ranged capability) with priority weighting: whatever is attacking the
     * companion or the owner right now outranks a stronger but uninterested enemy.
     */
    public static double threatScore(CompanionEntity c, LivingEntity e) {
        double score = 0.0D;
        // Tankiness: health and armor make an enemy both harder to kill and more dangerous
        // for longer; armor is weighted above raw HP because it also reduces our DPS.
        score += (e.getHealth() + e.getAbsorptionAmount()) * 0.5D;
        score += e.getArmorValue() * 1.5D;
        // Real offensive capability: vanilla armor-reduced DPS estimate.
        score += reduce(rawDamage(e), c.getArmorValue(), c.getAttributeValue(Attributes.ARMOR_TOUGHNESS))
                * swings(e) * 2.0D;
        // Weapon and offhand enchantment investment signals an actually equipped fighter.
        int levels = 0;
        for (Integer lvl : e.getMainHandItem().getAllEnchantments().values()) {
            if (lvl != null) levels += lvl;
        }
        for (Integer lvl : e.getOffhandItem().getAllEnchantments().values()) {
            if (lvl != null) levels += lvl;
        }
        score += levels * 2.0D;
        if (e.getOffhandItem().is(Items.SHIELD)) score += 4.0D;
        // Ranged attackers keep choosing the engagement distance; close them first.
        if (e instanceof AbstractSkeleton) score += 5.0D;
        // Priority: an enemy currently attacking us or the owner is worth more than its stats.
        if (e instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == c) score *= 1.6D;
        else if (e instanceof net.minecraft.world.entity.Mob mob && c.getOwnerPlayer() != null
                && mob.getTarget() == c.getOwnerPlayer()) score *= 1.5D;
        if (e.getLastHurtByMob() == c) score *= 1.2D;
        return score;
    }

    /**
     * Pick the engagement target from real candidates: best threat-per-distance score, with a
     * stickiness bonus for the current target. Without the bonus, two similar threats made the
     * melee goal flip targets every scan and neither one ever died.
     */
    public static LivingEntity selectBest(CompanionEntity c, java.util.List<LivingEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        LivingEntity current = c.getTarget();
        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            double s = threatScore(c, e) / (1.0D + c.distanceToSqr(e) / 64.0D);
            if (e == current) s += 6.0D;
            if (s > bestScore) { bestScore = s; best = e; }
        }
        return best;
    }

    /** Damage per second the enemies would deal to the companion right now. */
    public static double incomingDps(CompanionEntity c, Collection<? extends LivingEntity> enemies) {
        double armor = c.getArmorValue();
        double tough = c.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double dps = 0.0D;
        for (LivingEntity e : enemies) dps += reduce(rawDamage(e), armor, tough) * swings(e);
        return dps;
    }

    /**
     * True when the companion carries food that can realistically sustain it through a fight.
     * Not every edible item qualifies: golden apples are always real combat healing, while
     * other food counts only if it restores a meaningful amount and carries no harmful side
     * effects (rotten flesh, spider eyes, poisonous potatoes, raw chicken and pufferfish are
     * excluded) — previously any edible item made the assessor over-confident (review note).
     */
    public static boolean hasHealingItems(CompanionEntity c) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack s = c.getCompanionInventory().getItem(i);
            if (s.isEmpty() || !s.isEdible()) continue;
            if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) return true;
            FoodProperties props = s.getItem().getFoodProperties(s, c);
            if (props == null) continue;
            if (props.getNutrition() < 3) continue;          // snack-tier food: no fight budget credit
            if (hasHarmfulEffect(props)) continue;           // risky food must not inflate confidence
            return true;
        }
        return false;
    }

    private static boolean hasHarmfulEffect(FoodProperties props) {
        var possible = props.getEffects();
        if (possible == null || possible.isEmpty()) return false;
        for (var pair : possible) {
            if (pair != null && pair.getFirst() != null
                    && pair.getFirst().getEffect() != null
                    && pair.getFirst().getEffect().getCategory() == MobEffectCategory.HARMFUL) return true;
        }
        return false;
    }

    /**
     * @param leniency multiply the accepted risk (e.g. 1.25 when the player explicitly asked for protection)
     */
    public static Verdict assess(CompanionEntity c, Collection<? extends LivingEntity> enemies, double leniency) {
        if (enemies.isEmpty()) return new Verdict(true, 99.0D, 0.0D, "");
        double myHp = Math.max(1.0D, c.getHealth() + c.getAbsorptionAmount());
        double myArmor = c.getArmorValue();
        double myDmg = c.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double mySwings = 20.0D / CompanionMeleeGoal.cooldownTicksFor(c.getMainHandItem());

        double ttk = 0.0D;   // seconds needed to kill everyone, one after another
        for (LivingEntity e : enemies) {
            double hp = e.getHealth() + e.getAbsorptionAmount();
            double taken = Math.max(0.5D, reduce(myDmg, e.getArmorValue(), e.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));
            ttk += hp / (taken * mySwings);
        }
        double incoming = incomingDps(c, enemies);
        // Enemies die one by one, so the average damage rate over the fight is lower than at the start.
        double expectedLoss = incoming * ttk * 0.75D;
        double timeToDie = myHp / Math.max(0.01D, incoming * 0.75D);
        double ratio = timeToDie / Math.max(0.1D, ttk);
        double lossFraction = expectedLoss / myHp;

        double allowed = myArmor < 4.0D ? 0.35D : (myArmor < 10.0D ? 0.55D : 0.70D);
        if (hasHealingItems(c)) allowed += 0.10D;
        allowed *= leniency;

        // The companion was fleeing from fights it could actually win (e.g. 3 zombies while
        // armored), which felt unfair. Only run away when the fight is genuinely losing: the
        // time-advantage must be clearly against us (ratio < 1.1) OR the expected HP loss is
        // above the armor-based budget.
        boolean fight = ratio >= 1.1D && lossFraction <= allowed;
        String reason = "";
        if (!fight) {
            if (myArmor < 4.0D) reason = "low armor";
            else if (enemies.size() > 1) reason = "outnumbered";
            else if (myHp < c.getMaxHealth() * 0.5D) reason = "low health";
            else reason = "unfavourable fight";
        }
        return new Verdict(fight, ratio, lossFraction, reason);
    }

    private CombatAssessor() {}
}
