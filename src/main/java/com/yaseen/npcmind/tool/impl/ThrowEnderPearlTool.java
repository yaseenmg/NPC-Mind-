package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEnderpearl;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.entity.ModEntities;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import com.yaseen.npcmind.api.Internal;

/** Local ender-pearl use with a six-tick cancellable wind-up and an explicit rare-item permission. */
@Internal
public final class ThrowEnderPearlTool implements AITool {
    private static final String PENDING_KEY = "NPCMindPendingPearl";
    private static final int WINDUP_TICKS = 6;
    private static final int COOLDOWN_TICKS = 200;

    @Override public String name() { return "throw_ender_pearl"; }
    @Override public String description() { return "Use one real ender pearl to approach the current combat target, escape from a nearby threat, or throw forward. Requires the rare-consumption permission."; }
    @Override public String schema() { return "{\"mode\":\"auto|approach|escape|forward(optional)\"}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer player, String arguments) {
        if (!(c.level() instanceof ServerLevel level) || player == null || player.level() != c.level()) {
            return "DENIED server_level_or_owner_mismatch";
        }
        if (!c.getPermissions().allowed(ActionPermissions.Action.CONSUME_RARE)) {
            return "DENIED permission=consume_rare; enable it explicitly before using ender pearls";
        }
        if (c.getEnderPearlCooldownTicks() > 0) return "FAILED ender_pearl_cooldown_ticks=" + c.getEnderPearlCooldownTicks();
        if (c.getPersistentData().contains(PENDING_KEY, 10)) return "FAILED ender_pearl_windup_in_progress";
        if (findPearl(c) == null) return "FAILED no_ender_pearl_in_inventory";

        String mode = JsonArgs.string(arguments, "mode");
        if (mode == null || mode.isBlank()) mode = "auto";
        mode = mode.toLowerCase(java.util.Locale.ROOT).trim();
        LivingEntity target = c.getTarget();
        if (target != null && (!target.isAlive() || target.level() != c.level())) target = null;
        Vec3 aim;

        boolean escaping = mode.equals("escape") || mode.equals("auto") && target != null
                && c.getHealth() <= c.getMaxHealth() * 0.45F && c.distanceToSqr(target) <= 25.0D;
        boolean approaching = mode.equals("approach") || mode.equals("auto") && target != null
                && c.distanceToSqr(target) >= 100.0D;
        if (escaping) {
            if (target == null) return "FAILED escape_requires_a_live_threat_target";
            Vec3 away = c.position().subtract(target.position());
            if (away.horizontalDistanceSqr() < 1.0E-4D) away = new Vec3(-Math.sin(Math.toRadians(c.getYRot())), 0.0D,
                    Math.cos(Math.toRadians(c.getYRot())));
            away = new Vec3(away.x, 0.0D, away.z).normalize();
            aim = c.position().add(away.scale(16.0D)).add(0.0D, 1.5D, 0.0D);
            mode = "escape";
        } else if (approaching) {
            if (target == null || !c.hasLineOfSight(target)) return "FAILED approach_requires_distant_visible_target";
            aim = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
            mode = "approach";
        } else if (mode.equals("forward")) {
            aim = c.position().add(c.getLookAngle().scale(12.0D)).add(0.0D, 1.0D, 0.0D);
        } else {
            return "FAILED no_safe_pearl_use; choose mode=escape|approach|forward or establish a suitable combat target";
        }

        CompoundTag pending = new CompoundTag();
        pending.putLong("due", level.getGameTime() + WINDUP_TICKS);
        pending.putDouble("x", aim.x); pending.putDouble("y", aim.y); pending.putDouble("z", aim.z);
        pending.putString("mode", mode);
        if (target != null) pending.putUUID("target", target.getUUID());
        c.getPersistentData().put(PENDING_KEY, pending);
        c.getNavigation().stop();
        c.getLookControl().setLookAt(aim.x, aim.y, aim.z, 60.0F, 60.0F);
        c.swing(InteractionHand.MAIN_HAND, true);
        return "STARTED ender_pearl_windup_ticks=" + WINDUP_TICKS + " mode=" + mode;
    }

    /** Call from the companion's server tick. Returns true while a pending throw owns the action slot. */
    public static boolean tickPending(CompanionEntity c) {
        if (c == null || c.level().isClientSide() || !(c.level() instanceof ServerLevel level)) return false;
        CompoundTag root = c.getPersistentData();
        if (!root.contains(PENDING_KEY, 10)) return false;
        CompoundTag pending = root.getCompound(PENDING_KEY).copy();
        if (level.getGameTime() < pending.getLong("due")) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(pending.getDouble("x"), pending.getDouble("y"), pending.getDouble("z"), 60.0F, 60.0F);
            return true;
        }
        root.remove(PENDING_KEY);
        if (!c.isAlive() || !c.getPermissions().allowed(ActionPermissions.Action.CONSUME_RARE)
                || c.getEnderPearlCooldownTicks() > 0) return true;
        String mode = pending.getString("mode");
        if ((mode.equals("escape") || mode.equals("approach")) && pending.hasUUID("target")) {
            net.minecraft.world.entity.Entity target = level.getEntity(pending.getUUID("target"));
            if (!(target instanceof LivingEntity living) || !living.isAlive()) return true;
        }
        ItemRef ref = findPearl(c);
        if (ref == null) return true;
        ItemStack stack = get(c, ref);
        Vec3 aim = new Vec3(pending.getDouble("x"), pending.getDouble("y"), pending.getDouble("z"));
        // Vanilla ThrownEnderpearl only teleports ServerPlayer owners, which silently voided
        // the pearl for this mob. The registered subclass applies the same teleport path to
        // any living owner (endermite chance, fall damage, TELEPORT game event, discard).
        CompanionEnderpearl pearl = new CompanionEnderpearl(ModEntities.COMPANION_ENDER_PEARL.get(), level, c);
        pearl.setPos(c.getX(), c.getEyeY() - 0.1D, c.getZ());
        double dx = aim.x - pearl.getX(), dy = aim.y - pearl.getY(), dz = aim.z - pearl.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        pearl.shoot(dx, dy + horizontal * 0.15D, dz, 1.5F, 1.0F);
        if (!level.addFreshEntity(pearl)) return true;
        stack.shrink(1);
        if (stack.isEmpty()) set(c, ref, ItemStack.EMPTY);
        c.setEnderPearlCooldownTicks(COOLDOWN_TICKS);
        c.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, c.blockPosition(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE, 1.0F, 1.0F);
        com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] ender pearl thrown companion={} mode={}", c.getUUID(), mode);
        return true;
    }

    private record ItemRef(int kind, int slot) { }
    private static ItemRef findPearl(CompanionEntity c) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            if (c.getCompanionInventory().getItem(i).is(Items.ENDER_PEARL)) return new ItemRef(0, i);
        }
        if (c.getMainHandItem().is(Items.ENDER_PEARL)) return new ItemRef(1, -1);
        if (c.getOffhandItem().is(Items.ENDER_PEARL)) return new ItemRef(2, -1);
        return null;
    }
    private static ItemStack get(CompanionEntity c, ItemRef ref) {
        return switch (ref.kind()) { case 0 -> c.getCompanionInventory().getItem(ref.slot()); case 1 -> c.getMainHandItem(); default -> c.getOffhandItem(); };
    }
    private static void set(CompanionEntity c, ItemRef ref, ItemStack stack) {
        switch (ref.kind()) { case 0 -> c.getCompanionInventory().setItem(ref.slot(), stack); case 1 -> c.setItemSlot(EquipmentSlot.MAINHAND, stack); default -> c.setItemSlot(EquipmentSlot.OFFHAND, stack); }
    }
}
