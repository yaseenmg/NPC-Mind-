package com.yaseen.npcmind.advanced;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import com.yaseen.npcmind.api.Internal;

/** Reads Forge Energy from real ItemStack/block capabilities. No simulated energy. */
@Internal
public final class EnergyInspector {
    public static String item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        IEnergyStorage e = stack.getCapability(ForgeCapabilities.ENERGY).orElse(null);
        if (e == null) return "none";
        return "energy=" + e.getEnergyStored() + "/" + e.getMaxEnergyStored()
                + ",receive=" + e.canReceive() + ",extract=" + e.canExtract();
    }

    public static String block(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return "invalid";
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return "none";
        IEnergyStorage e = blockEntity.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
        if (e == null) return "none";
        return "energy=" + e.getEnergyStored() + "/" + e.getMaxEnergyStored()
                + ",receive=" + e.canReceive() + ",extract=" + e.canExtract();
    }
    private EnergyInspector() {}
}
