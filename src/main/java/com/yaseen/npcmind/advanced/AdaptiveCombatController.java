package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.tool.impl.CombatTargetTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Combat brain: fight-or-flight assessment from real stats, retreat + self-healing when a fight is
 * going badly, equipment selection. Explicit player-ordered fights are respected, but the companion
 * still pulls back and heals when it is about to die.
 */
@Internal
public final class AdaptiveCombatController {
    private static final class State {
        boolean retreating;
        UUID resumeTarget;
        boolean resumeExplicit;
        long retreatSince;
        long lastSafeTick = -1;
        long lastSay = -1000;
        long avoidSince = -1;
        long forceFightUntil;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** True while the combat brain is in its retreat/heal branch for this companion. */
    public static boolean isRetreating(CompanionEntity c) {
        State st = STATES.get(c.getUUID());
        return st != null && st.retreating;
    }

    public static void tick(CompanionEntity c) {
        if (c.level().isClientSide() || c.tickCount % 5 != 0) return;
        // Never change items or navigation while the companion is really eating.
        // (Drawing a bow or raising a shield also "uses" an item, but must not freeze the brain.)
        if (c.isEating()) return;
        State st = STATES.computeIfAbsent(c.getUUID(), k -> new State());

        // A creeper that is fusing next to us: get away immediately, whatever else is going on.
        for (Creeper creeper : c.level().getEntitiesOfClass(Creeper.class, c.getBoundingBox().inflate(7.0D),
                cr -> cr.isAlive() && (cr.isIgnited() || cr.getSwelling(0.0F) > 0.1F))) {
            c.setTarget(null);
            TaskManager.cancelCurrentTask(c);
            flee(c, creeper);
            return;
        }
        // Keep the shield as the normal defensive offhand. A Totem is a temporary emergency
        // replacement; when the danger passes, put the shield back if one is available.
        restoreShieldAfterTotem(c);
        equipEmergencyTotem(c);
        shieldAgainstProjectiles(c);

        LivingEntity target = c.getTarget();
        if (target != null && !target.isAlive()) {
            // A count-based animal hunt chains the next nearby target only after the previous
            // animal has died, so it can collect the natural drops from several animals.
            boolean continuedHunt = CombatTargetTool.onTargetDefeated(c, target);
            target = continuedHunt ? c.getTarget() : null;
            if (!continuedHunt) c.setTarget(null);
        }
        boolean explicit = c.isFriendlyCombat();
        double hp = c.getHealth() + c.getAbsorptionAmount();
        double max = c.getMaxHealth();
        List<LivingEntity> threats = threatsAround(c, target);
        LivingEntity nearest = nearest(c, threats);

        // ---------- 1) retreat / heal state ----------
        if (st.retreating) { handleRetreat(c, st, nearest, hp, max); return; }
        if (target != null || !threats.isEmpty()) {
            double incoming = CombatAssessor.incomingDps(c, threats);
            // Explicit player-ordered combat is not allowed to silently become a retreat
            // at 35% health. A spar is an intentional interaction: keep the target until
            // it dies or the player says stop. Environmental SurvivalGuard still retains
            // the final emergency authority for lava/fire/drowning.
            boolean critical = hp <= max * 0.10 || (!explicit && (hp <= max * 0.35 || (incoming > 0.0 && hp <= incoming * 3.0 && hp < max * 0.6)));
            if (critical) { startRetreat(c, st, target, explicit); return; }
        } else {
            st.avoidSince = -1;
        }

        // ---------- 2) drop targets that are no longer worth keeping ----------
        if (target != null && !explicit && !CombatLearning.dangerous(c, target)) {
            c.setTarget(null);
            c.getNavigation().stop();
            target = null;
        }
        if (target != null && !explicit && c.distanceToSqr(target) > 24.0 * 24.0) {
            c.setTarget(null);
            c.getNavigation().stop();
            target = null;
        }

        // ---------- 3) fight-or-flight gate for targets that are not an explicit player order ----------
        if (target != null && !explicit && c.tickCount % 10 == 0 && c.tickCount >= st.forceFightUntil) {
            List<LivingEntity> group = groupAround(c, target);
            CombatAssessor.Verdict v = CombatAssessor.assess(c, group, BodyguardController.enabled(c) ? 1.25D : 1.0D);
            if (!v.fight()) { avoid(c, st, group, v); return; }
        }

        // ---------- 4) pick a target / finish explicit combat ----------
        if (target == null) {
            if (explicit) {
                c.setFriendlyCombat(false);
                c.setFollowEnabled(false);
                return;
            }
            java.util.List<LivingEntity> candidates = c.level().getEntitiesOfClass(LivingEntity.class,
                    new AABB(c.blockPosition()).inflate(12),
                    e -> e != c && e.isAlive() && CombatLearning.dangerous(c, e) && c.hasLineOfSight(e));
            // Smart selection instead of pure nearest: threat score per distance, with a
            // stickiness bonus so two similar threats no longer make the melee chase flip.
            LivingEntity candidate = CombatAssessor.selectBest(c, candidates);
            if (candidate == null) return;
            if (c.tickCount >= st.forceFightUntil) {
                List<LivingEntity> group = groupAround(c, candidate);
                CombatAssessor.Verdict v = CombatAssessor.assess(c, group, BodyguardController.enabled(c) ? 1.25D : 1.0D);
                if (!v.fight()) { avoid(c, st, group, v); return; }
            }
            TaskManager.cancelCurrentTask(c);
            c.setTarget(candidate);
            target = candidate;
        }

        // ---------- 5) engage ----------
        st.avoidSince = -1;
        c.setTarget(target);
        selectArmor(c);
        c.getCombatLearning().observeFight(target);
        c.getSkills().add(SkillProgression.Skill.COMBAT, 1);
        c.getLookControl().setLookAt(target, 30, 30);
        chooseWeapon(c, target);
        equipShield(c);
    }

    /** Bow when the enemy is far (with hysteresis), best melee weapon when it is close. */
    private static void chooseWeapon(CompanionEntity c, LivingEntity target) {
        if (c.isUsingItem()) return; // never swap mid-draw / mid-block
        double dist = c.distanceTo(target);
        boolean holdingBow = c.getMainHandItem().getItem() instanceof BowItem;
        boolean wantRanged = CompanionRangedGoal.canShoot(c)
                && (holdingBow ? dist > 5.0D : dist > 9.0D) && c.hasLineOfSight(target);
        c.setRangedMode(wantRanged);
        if (wantRanged) equipBow(c);
        else {
            selectWeapon(c, false);
            // With no melee weapon anywhere, selectWeapon leaves the bow in hand and the
            // companion would punch with it. Swap the bow out so bare fists are used instead.
            if (c.getMainHandItem().getItem() instanceof BowItem
                    && !c.isUsingItem()
                    && c.distanceToSqr(target) <= 36.0D) {
                ItemStack bow = c.getMainHandItem().copy();
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                ItemStack left = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(
                        c.getCompanionInventory(), bow);
                if (!left.isEmpty()) c.spawnAtLocation(left);
            }
        }
    }

    private static void equipBow(CompanionEntity c) {
        if (c.getMainHandItem().getItem() instanceof BowItem) return;
        int slot = CompanionRangedGoal.findBowSlot(c);
        if (slot < 0) return;
        ItemStack old = c.getMainHandItem();
        if (!old.isEmpty() && !com.yaseen.npcmind.crafting.InventoryOps.canAdd(c.getCompanionInventory(), old)) return;
        ItemStack bow = c.getCompanionInventory().removeItem(slot, 1);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, bow);
        if (!old.isEmpty()) com.yaseen.npcmind.crafting.InventoryOps.add(c.getCompanionInventory(), old);
    }

    private static void equipShield(CompanionEntity c) {
        if (c.isUsingItem()) return;
        if (!c.getOffhandItem().isEmpty()) return;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack s = c.getCompanionInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof ShieldItem) {
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, c.getCompanionInventory().removeItem(i, 1));
                return;
            }
        }
    }

    /**
     * Put a previously displaced shield back into the offhand after an emergency Totem is no
     * longer needed. Never swap while the companion is critically hurt or actively using an item.
     */
    private static void restoreShieldAfterTotem(CompanionEntity c) {
        if (!c.isShieldDisplacedByTotem() || c.isUsingItem()) return;
        // Do not immediately re-equip while the companion is still in the danger window.
        boolean stillCritical = c.getHealth() <= c.getMaxHealth() * 0.55F
                || (c.getTarget() != null && c.getHealth() <= c.getMaxHealth() * 0.70F);
        if (stillCritical) return;
        ItemStack off = c.getOffhandItem();
        if (!off.isEmpty() && !off.is(Items.TOTEM_OF_UNDYING)) return;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack s = c.getCompanionInventory().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof ShieldItem)) continue;
            ItemStack remembered = c.getDisplacedShield();
            if (!remembered.isEmpty() && !ItemStack.isSameItemSameTags(s, remembered)) continue;
            ItemStack shield = s.copy();
            // The flag means this exact shield role was displaced by the emergency swap.
            // If a Totem is still present, put it back into the same inventory slot. If it was
            // consumed, the offhand is empty and nothing is lost.
            if (!off.isEmpty()) c.getCompanionInventory().setItem(i, off.copy());
            else c.getCompanionInventory().setItem(i, ItemStack.EMPTY);
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, shield);
            c.getCompanionInventory().setChanged();
            c.setShieldDisplacedByTotem(false);
            c.clearDisplacedShield();
            return;
        }
        // If the shield was consumed/lost by another system, do not leave the entity permanently
        // marked as displaced. A later shield can be equipped normally by equipShield().
        if (off.isEmpty() || !off.is(Items.TOTEM_OF_UNDYING)) {
            c.setShieldDisplacedByTotem(false);
            c.clearDisplacedShield();
        }
    }

    /** Equip a real Totem of Undying before lethal damage; restore the shield once danger passes. */
    private static void equipEmergencyTotem(CompanionEntity c) {
        ItemStack off = c.getOffhandItem();
        boolean critical = c.getHealth() <= c.getMaxHealth() * 0.28F ||
                (c.getHealth() + c.getAbsorptionAmount() <= c.getMaxHealth() * 0.45F && c.getTarget() != null);
        if (critical && !off.is(Items.TOTEM_OF_UNDYING)) {
            int slot = -1;
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                if (c.getCompanionInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) { slot = i; break; }
            }
            // A shield in the offhand is a special case: remember that it was displaced so it
            // can be restored even after the Totem is consumed and the offhand becomes empty.
            if (slot >= 0 && (off.isEmpty() || com.yaseen.npcmind.crafting.InventoryOps.canAdd(c.getCompanionInventory(), off))) {
                if (off.getItem() instanceof ShieldItem) {
                    c.setShieldDisplacedByTotem(true);
                    c.rememberDisplacedShield(off);
                }
                ItemStack totem = c.getCompanionInventory().removeItem(slot, 1);
                if (!off.isEmpty()) com.yaseen.npcmind.crafting.InventoryOps.add(c.getCompanionInventory(), off);
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, totem);
            }
        }
    }

    /** Raise the shield when an arrow is flying straight at us (outside close melee, where the goal manages it). */
    private static void shieldAgainstProjectiles(CompanionEntity c) {
        if (!(c.getOffhandItem().getItem() instanceof ShieldItem)) return;
        LivingEntity t = c.getTarget();
        if (t != null && c.distanceTo(t) <= 6.0F) return; // melee goal handles the shield up close
        boolean incoming = false;
        net.minecraft.world.phys.Vec3 me = c.position().add(0.0D, 1.0D, 0.0D);
        for (AbstractArrow a : c.level().getEntitiesOfClass(AbstractArrow.class, c.getBoundingBox().inflate(10.0D),
                x -> x.getOwner() != c)) {
            net.minecraft.world.phys.Vec3 v = a.getDeltaMovement();
            if (v.lengthSqr() < 0.01D) continue; // stuck in the ground
            net.minecraft.world.phys.Vec3 to = me.subtract(a.position());
            if (to.lengthSqr() < 0.01D) continue;
            if (to.normalize().dot(v.normalize()) > 0.85D) { incoming = true; break; }
        }
        boolean shieldUp = c.isUsingItem() && c.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
        if (incoming && !c.isUsingItem()) c.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        else if (!incoming && shieldUp) c.stopUsingItem();
    }

    // ------------------------------------------------------------------ retreat

    private static void startRetreat(CompanionEntity c, State st, LivingEntity target, boolean explicit) {
        st.retreating = true;
        st.retreatSince = c.tickCount;
        st.lastSafeTick = -1;
        st.resumeTarget = target != null ? target.getUUID() : null;
        st.resumeExplicit = explicit;
        TaskManager.cancelCurrentTask(c);
        c.setTarget(null);
        c.getNavigation().stop();
        c.getCombatLearning().retreat();
        say(c, st, "I'm hurt — falling back!");
    }

    private static void handleRetreat(CompanionEntity c, State st, LivingEntity nearest, double hp, double max) {
        c.setTarget(null);
        if (nearest == null) {
            if (st.lastSafeTick < 0) st.lastSafeTick = c.tickCount;
        } else {
            st.lastSafeTick = -1;
        }
        boolean healthy = hp >= max * 0.7;
        boolean safeLong = st.lastSafeTick >= 0 && c.tickCount - st.lastSafeTick > 100;
        boolean timeout = c.tickCount - st.retreatSince > 1200;
        if (healthy || safeLong || timeout) {
            endRetreat(c, st, healthy);
            return;
        }
        double dist = nearest == null ? 99.0D : c.distanceTo(nearest);
        // Heal only once the enemy is far enough not to hit us while we eat.
        if (dist > 5.0D && c.startEating(true)) return;
        if (nearest != null) flee(c, nearest);
    }

    private static void endRetreat(CompanionEntity c, State st, boolean healthy) {
        st.retreating = false;
        st.lastSafeTick = -1;
        boolean resumed = false;
        if (healthy && st.resumeExplicit && st.resumeTarget != null && c.level() instanceof ServerLevel sl) {
            Entity e = sl.getEntity(st.resumeTarget);
            if (e instanceof LivingEntity le && le.isAlive()) {
                c.setFriendlyCombat(true);
                c.setTarget(le);
                resumed = true;
                say(c, st, "I'm healed. Back to the fight.");
            }
        }
        if (!resumed) c.setFriendlyCombat(false);
        st.resumeTarget = null;
    }

    // ------------------------------------------------------------------ avoid

    private static void avoid(CompanionEntity c, State st, List<LivingEntity> group, CombatAssessor.Verdict v) {
        TaskManager.cancelCurrentTask(c);
        c.setTarget(null);
        if (st.avoidSince < 0) st.avoidSince = c.tickCount;
        LivingEntity n = nearest(c, group);
        if (n != null) flee(c, n);
        say(c, st, "This fight is bad (" + v.reason() + "). Keeping my distance.");
        // If running away is not working (cornered), fight back instead of being chased forever.
        if (n != null && c.distanceToSqr(n) < 9.0D && c.tickCount - st.avoidSince > 100) {
            st.forceFightUntil = c.tickCount + 200;
            st.avoidSince = -1;
            say(c, st, "No escape — fighting back!");
        }
    }

    /** Run to the far side of the owner (or directly away if there is no owner / the owner is the enemy). */
    private static void flee(CompanionEntity c, LivingEntity from) {
        Player owner = c.getOwnerPlayer();
        double tx, tz;
        if (owner != null && owner != from && owner.level() == c.level()) {
            double ox = owner.getX() - from.getX();
            double oz = owner.getZ() - from.getZ();
            double ol = Math.max(0.001D, Math.sqrt(ox * ox + oz * oz));
            tx = owner.getX() + ox / ol * 3.0D;
            tz = owner.getZ() + oz / ol * 3.0D;
        } else {
            double ax = c.getX() - from.getX();
            double az = c.getZ() - from.getZ();
            double al = Math.max(0.001D, Math.sqrt(ax * ax + az * az));
            tx = c.getX() + ax / al * 14.0D;
            tz = c.getZ() + az / al * 14.0D;
        }
        NavigationArbiter.request(c, new net.minecraft.core.BlockPos((int)Math.floor(tx), (int)Math.floor(c.getY()), (int)Math.floor(tz)), "combat", 800, 1.35D);
    }

    private static void say(CompanionEntity c, State st, String msg) {
        if (c.tickCount - st.lastSay < 200) return;
        st.lastSay = c.tickCount;
        c.say(msg);
    }

    // ------------------------------------------------------------------ threat scan

    private static List<LivingEntity> threatsAround(CompanionEntity c, LivingEntity target) {
        Player owner = c.getOwnerPlayer();
        return new ArrayList<>(c.level().getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(10.0D),
                e -> e != c && e.isAlive() && (e == target
                        || (e instanceof Monster m && (m.getTarget() == c || (owner != null && m.getTarget() == owner))))));
    }

    private static List<LivingEntity> groupAround(CompanionEntity c, LivingEntity target) {
        List<LivingEntity> list = threatsAround(c, target);
        if (!list.contains(target)) list.add(target);
        return list;
    }

    private static LivingEntity nearest(CompanionEntity c, List<LivingEntity> list) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            double d = c.distanceToSqr(e);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    // ------------------------------------------------------------------ equipment

    private static void selectArmor(CompanionEntity c) {
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            double current = EquipmentAnalyzer.armorScore(c.getItemBySlot(slot), slot);
            int best = -1; double bestScore = current;
            for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
                ItemStack stack = c.getCompanionInventory().getItem(i);
                double score = EquipmentAnalyzer.armorScore(stack, slot);
                if (score > bestScore) { bestScore = score; best = i; }
            }
            if (best >= 0) {
                ItemStack old = c.getItemBySlot(slot);
                // Never take the replacement out of the inventory unless the old item
                // has a guaranteed destination. This makes equipment swapping atomic.
                if (!old.isEmpty() && !com.yaseen.npcmind.crafting.InventoryOps.canAdd(c.getCompanionInventory(), old)) continue;
                ItemStack replacement = c.getCompanionInventory().removeItem(best, 1);
                c.setItemSlot(slot, replacement);
                if (!old.isEmpty()) com.yaseen.npcmind.crafting.InventoryOps.add(c.getCompanionInventory(), old);
            }
        }
    }

    private static void selectWeapon(CompanionEntity c, boolean ranged) {
        int best = -1; double score = -1;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack s = c.getCompanionInventory().getItem(i);
            if (!isMeleeWeapon(s)) continue;
            double v = EquipmentAnalyzer.combatScore(s, ranged);
            if (v > score) { score = v; best = i; }
        }
        if (best >= 0) {
            ItemStack old = c.getMainHandItem();
            if (!isMeleeWeapon(old) || EquipmentAnalyzer.combatScore(c.getMainHandItem(), ranged) < score) {
                if (!old.isEmpty() && !com.yaseen.npcmind.crafting.InventoryOps.canAdd(c.getCompanionInventory(), old)) return;
                ItemStack replacement = c.getCompanionInventory().removeItem(best, 1);
                c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, replacement);
                if (!old.isEmpty()) com.yaseen.npcmind.crafting.InventoryOps.add(c.getCompanionInventory(), old);
            }
        }
    }

    private static boolean isMeleeWeapon(ItemStack s) {
        return EquipmentAnalyzer.isCombatWeapon(s)
                && !(s.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem)
                && !(s.getItem() instanceof net.minecraft.world.item.ShieldItem);
    }

    public static void clear(UUID id) { STATES.remove(id); }

    private AdaptiveCombatController() {}
}
