package com.yaseen.npcmind.crafting;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import com.yaseen.npcmind.api.Internal;

/** Real inventory operations against the companion's live Container. */
@Internal
public final class InventoryOps {
    private InventoryOps() {}

    public static boolean canAdd(Container inv, ItemStack incoming) {
        if (incoming.isEmpty()) return true;
        int remaining = incoming.getCount();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, incoming)) {
                remaining -= Math.min(incoming.getMaxStackSize() - s.getCount(), remaining);
                if (remaining <= 0) return true;
            }
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                remaining -= Math.min(incoming.getMaxStackSize(), remaining);
                if (remaining <= 0) return true;
            }
        }
        return false;
    }

    /** Insert as much as possible and return the actual remainder. The input stack is not mutated. */
    public static ItemStack addAndReturnRemainder(Container inv, ItemStack incoming) {
        ItemStack remaining = incoming.copy();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, remaining)) {
                int room = Math.min(s.getMaxStackSize(), remaining.getMaxStackSize()) - s.getCount();
                if (room > 0) {
                    int n = Math.min(room, remaining.getCount());
                    s.grow(n);
                    remaining.shrink(n);
                }
            }
        }
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, remaining.split(Math.min(remaining.getCount(), remaining.getMaxStackSize())));
            }
        }
        if (!remaining.isEmpty()) return remaining;
        return ItemStack.EMPTY;
    }

    public static boolean add(Container inv, ItemStack incoming) {
        if (!canAdd(inv, incoming)) return false;
        ItemStack remaining = incoming.copy();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, remaining)) {
                int room = Math.min(s.getMaxStackSize(), remaining.getMaxStackSize()) - s.getCount();
                if (room > 0) {
                    int n = Math.min(room, remaining.getCount());
                    s.grow(n);
                    remaining.shrink(n);
                }
            }
        }
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, remaining.split(Math.min(remaining.getCount(), remaining.getMaxStackSize())));
            }
        }
        return remaining.isEmpty();
    }
}
