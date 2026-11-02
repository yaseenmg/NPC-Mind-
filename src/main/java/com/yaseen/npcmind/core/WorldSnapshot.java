package com.yaseen.npcmind.core;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import com.yaseen.npcmind.api.Internal;

/** Immutable, compact observation used by planning and post-action verification. */
@Internal
public record WorldSnapshot(
        BlockPos position,
        float health,
        int food,
        float saturation,
        int inventoryItems,
        String inventorySignature,
        int nearbyHostiles,
        boolean inWater,
        boolean onFire,
        boolean inLava,
        boolean hasTarget,
        String taskStatus,
        long tick
) {
    public static WorldSnapshot capture(CompanionEntity c, ServerPlayer owner) {
        int items = 0;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            if (!c.getCompanionInventory().getItem(i).isEmpty()) items++;
        }
        StringBuilder inventory = new StringBuilder();
        // Equipment changes (weapon swap, bucket in hand, armor) are real state changes the
        // critic must see; the old signature covered the carried container only.
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            var stack = c.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            var key = stack.getItem().builtInRegistryHolder().key().location();
            inventory.append(slot.getName()).append(':').append(key).append('x').append(stack.getCount()).append(':')
                    .append(stack.getTag() == null ? "" : stack.getTag().hashCode()).append(';');
        }
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var key = stack.getItem().builtInRegistryHolder().key().location();
            inventory.append(i).append(':').append(key).append('x').append(stack.getCount()).append(':')
                    .append(stack.getTag() == null ? "" : stack.getTag().hashCode()).append(';');
        }
        String inventorySignature = Integer.toHexString(inventory.toString().hashCode());

        int hostiles = 0;
        for (LivingEntity e : c.level().getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(12))) {
            if (e != c && e.isAlive() && e instanceof net.minecraft.world.entity.monster.Monster) hostiles++;
        }
        return new WorldSnapshot(c.blockPosition(), c.getHealth(), c.getFoodLevel(), c.getFoodSaturation(),
                items, inventorySignature, hostiles, c.isInWaterOrBubble(), c.isOnFire(), c.isInLava(), c.getTarget() != null,
                com.yaseen.npcmind.task.TaskManager.status(c), c.tickCount);
    }
}
