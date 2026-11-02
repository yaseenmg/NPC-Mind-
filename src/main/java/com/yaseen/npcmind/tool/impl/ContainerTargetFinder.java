package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

import java.util.Comparator;

/** Shared, bounded search for real loaded containers that the owner and companion may access. */
final class ContainerTargetFinder {
    private ContainerTargetFinder() {}

    static BlockPos nearest(CompanionEntity companion, ServerPlayer owner, int radius) {
        if (companion == null || owner == null || companion.level() != owner.level()) return null;
        BlockPos base = companion.blockPosition();
        return BlockPos.betweenClosedStream(base.offset(-radius, -radius, -radius),
                        base.offset(radius, radius, radius))
                .filter(pos -> companion.level().isLoaded(pos))
                .filter(pos -> companion.level().getBlockEntity(pos) instanceof Container)
                .filter(pos -> WorldActionSecurity.canAccessContainer(companion, owner, pos))
                .min(Comparator.comparingDouble(pos -> companion.distanceToSqr(
                        pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)))
                .map(BlockPos::immutable).orElse(null);
    }
}
