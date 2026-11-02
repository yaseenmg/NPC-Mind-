package com.yaseen.npcmind.menu;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import com.yaseen.npcmind.api.Internal;

/** 36 companion slots plus the normal 36 player inventory slots, with shift-click transfer. */
@Internal
public final class CompanionInventoryMenu extends AbstractContainerMenu {
    public static final int COMPANION_SLOTS = 36;
    private final CompanionEntity companion;
    private final Container companionInventory;

    public CompanionInventoryMenu(int windowId, Inventory playerInventory, FriendlyByteBuf data) {
        this(windowId, playerInventory, findCompanion(playerInventory, data.readInt()));
    }

    public CompanionInventoryMenu(int windowId, Inventory playerInventory, CompanionEntity companion) {
        super(ModMenus.COMPANION_INVENTORY.get(), windowId);
        this.companion = companion;
        this.companionInventory = companion == null ? new SimpleContainer(COMPANION_SLOTS) : companion.getCompanionInventory();
        this.companionInventory.startOpen(playerInventory.player);

        // Companion inventory: four player-style rows (27 main + 9 hotbar).
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(companionInventory, row * 9 + col, 8 + col * 18, 20 + row * 18));
            }
        }
        // Player main inventory.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 110 + row * 18));
            }
        }
        // Player hotbar.
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 168));
        }
    }

    private static CompanionEntity findCompanion(Inventory inventory, int entityId) {
        Entity entity = inventory.player.level().getEntity(entityId);
        return entity instanceof CompanionEntity companion ? companion : null;
    }

    public CompanionEntity companion() { return companion; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack sourceStack = source.getItem();
        ItemStack original = sourceStack.copy();
        boolean moved = index < COMPANION_SLOTS
                ? moveItemStackTo(sourceStack, COMPANION_SLOTS, slots.size(), true)
                : moveItemStackTo(sourceStack, 0, COMPANION_SLOTS, false);
        if (!moved) return ItemStack.EMPTY;
        if (sourceStack.isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
        if (sourceStack.getCount() == original.getCount()) return ItemStack.EMPTY;
        source.onTake(player, sourceStack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return companion != null && companion.isAlive()
                && companion.getOwner() != null && companion.getOwner().equals(player.getUUID())
                && companion.level() == player.level() && companion.distanceToSqr(player) <= 64.0D;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        companionInventory.stopOpen(player);
    }
}
