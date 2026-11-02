package com.yaseen.npcmind.client;

import com.yaseen.npcmind.menu.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import com.yaseen.npcmind.api.Internal;

/** Lightweight inventory screen without a custom texture dependency. */
@Internal
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 204;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xEE20242A);
        graphics.fill(x, y, x + imageWidth, y + 2, 0xFF59636E);
        graphics.fill(x, y + imageHeight - 2, x + imageWidth, y + imageHeight, 0xFF59636E);
        graphics.fill(x, y, x + 2, y + imageHeight, 0xFF59636E);
        graphics.fill(x + imageWidth - 2, y, x + imageWidth, y + imageHeight, 0xFF59636E);
        // Slot wells: companion, player main inventory, and hotbar.
        drawSlotGrid(graphics, x, y, 20, 4);
        drawSlotGrid(graphics, x, y, 110, 3);
        drawSlotGrid(graphics, x, y, 168, 1);
    }

    private static void drawSlotGrid(GuiGraphics graphics, int x, int y, int rowY, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 7 + col * 18;
                int sy = y + rowY - 1 + row * 18;
                graphics.fill(sx, sy, sx + 18, sy + 18, 0xFF101317);
                graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF383E46);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String name = menu.companion() == null ? "Companion" : menu.companion().getDisplayName().getString();
        graphics.drawString(font, name + " Inventory", 8, 7, 0xFFFFFFFF, false);
        graphics.drawString(font, "Player Inventory", 8, 98, 0xFFD6DCE3, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
