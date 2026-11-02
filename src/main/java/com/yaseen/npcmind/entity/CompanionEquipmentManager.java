package com.yaseen.npcmind.entity;

import com.yaseen.npcmind.advanced.EquipmentAnalyzer;
import com.yaseen.npcmind.crafting.InventoryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import com.yaseen.npcmind.api.Internal;

/**
 * Equipment bridge. Inventory and equipment are separate,
 * and every move between them is slot-validated. This prevents arbitrary items
 * (wood blocks, swords, food) from ever being put into an armor slot.
 */
@Internal
public final class CompanionEquipmentManager {
    private CompanionEquipmentManager() {}

    public static boolean equipFromInventory(CompanionEntity c, int inventorySlot, EquipmentSlot slot) {
        if (inventorySlot < 0 || inventorySlot >= c.getCompanionInventory().getContainerSize()) return false;
        ItemStack candidate = c.getCompanionInventory().getItem(inventorySlot);
        if (!isValidForSlot(candidate, slot)) return false;
        ItemStack old = c.getItemBySlot(slot);
        if (!old.isEmpty() && !InventoryOps.canAdd(c.getCompanionInventory(), old)) return false;
        c.getCompanionInventory().setItem(inventorySlot, old);
        c.setItemSlot(slot, candidate.copy());
        c.getCompanionInventory().setChanged();
        return true;
    }

    public static boolean isValidForSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) return false;
        if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot;
        }
        if (slot == EquipmentSlot.OFFHAND) return stack.getItem() instanceof ShieldItem;
        if (slot == EquipmentSlot.MAINHAND) {
            return EquipmentAnalyzer.isCombatWeapon(stack);
        }
        return false;
    }

    /** Equip only when the item is actually useful; ordinary blocks never become equipment. */
    public static boolean autoEquip(CompanionEntity c, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof ArmorItem armor) {
            EquipmentSlot slot = armor.getEquipmentSlot();
            ItemStack current = c.getItemBySlot(slot);
            double incoming = EquipmentAnalyzer.armorScore(stack, slot);
            double existing = EquipmentAnalyzer.armorScore(current, slot);
            if (incoming > existing) {
                int index = findStack(c, stack);
                return index >= 0 && equipFromInventory(c, index, slot);
            }
            return false;
        }
        if (stack.getItem() instanceof ShieldItem) {
            ItemStack current = c.getItemBySlot(EquipmentSlot.OFFHAND);
            if (current.isEmpty()) {
                int index = findStack(c, stack);
                return index >= 0 && equipFromInventory(c, index, EquipmentSlot.OFFHAND);
            }
            return false;
        }
        if (EquipmentAnalyzer.isCombatWeapon(stack)) {
            ItemStack current = c.getItemBySlot(EquipmentSlot.MAINHAND);
            double incoming = EquipmentAnalyzer.combatScore(stack, false);
            double existing = EquipmentAnalyzer.combatScore(current, false);
            if (current.isEmpty() || incoming > existing) {
                int index = findStack(c, stack);
                return index >= 0 && equipFromInventory(c, index, EquipmentSlot.MAINHAND);
            }
        }
        return false;
    }

    private static int findStack(CompanionEntity c, ItemStack wanted) {
        // Preserve object identity first so auto-equip swaps the exact slot being evaluated rather
        // than repeatedly selecting an earlier, equivalent stack in a different slot.
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++)
            if (c.getCompanionInventory().getItem(i) == wanted) return i;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            ItemStack candidate = c.getCompanionInventory().getItem(i);
            if (!candidate.isEmpty() && ItemStack.isSameItemSameTags(candidate, wanted)) return i;
        }
        return -1;
    }
}
