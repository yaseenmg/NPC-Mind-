package com.yaseen.npcmind.security;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import com.yaseen.npcmind.api.Internal;

/** Central server-side security checks for AI world actions. */
@Internal
public final class WorldActionSecurity {
    private static final double RANGE_SQR = 64.0D;
    private WorldActionSecurity() {}

    public static boolean canInteractWithBlock(CompanionEntity c, ServerPlayer owner, BlockPos pos) {
        if (c == null || owner == null || pos == null || !c.level().isInWorldBounds(pos)
                || !c.level().getWorldBorder().isWithinBounds(pos) || !c.level().isLoaded(pos)) return false;
        if (!owner.getUUID().equals(c.getOwner()) || c.level() != owner.level()) return false;
        double x = pos.getX() + .5D, y = pos.getY() + .5D, z = pos.getZ() + .5D;
        // Range belongs to the acting companion, not the owner. Requiring the owner to stand next
        // to every target made a 10-block wall, a farm row or a remote chest impossible after the
        // NPC walked away. Ownership, same-dimension checks, mayInteract and the Forge claim hooks
        // remain the authorization boundary.
        return c.distanceToSqr(x, y, z) <= RANGE_SQR && c.level().mayInteract(owner, pos);
    }

    /** Container/furnace access: spawn protection AND the right-click event, so claim mods can veto it. */
    public static boolean canAccessContainer(CompanionEntity c, ServerPlayer owner, BlockPos pos) {
        if (!canInteractWithBlock(c, owner, pos)) return false;
        try {
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.UP, pos, false);
            var ev = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(owner, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev);
            return !ev.isCanceled() && ev.getUseBlock() != net.minecraftforge.eventbus.api.Event.Result.DENY;
        } catch (Throwable t) {
            return false; // fail closed
        }
    }

    public static boolean canUseContainer(CompanionEntity c, ServerPlayer owner, BlockPos pos, BlockEntity be) {
        return be instanceof Container && canInteractWithBlock(c, owner, pos);
    }

    public static boolean canInteractWithEntity(CompanionEntity c, ServerPlayer owner, Entity target) {
        return c != null && owner != null && target != null && owner.getUUID().equals(c.getOwner())
                && c.level() == owner.level() && target.level() == owner.level()
                && c.distanceToSqr(target) <= RANGE_SQR;
    }

    /** Fires Forge's placement event so protection/claim mods can cancel a direct AI placement. */
    public static boolean canPlaceBlock(CompanionEntity c, ServerPlayer owner, BlockPos pos) {
        if (!canInteractWithBlock(c, owner, pos)) return false;
        BlockSnapshot snapshot = BlockSnapshot.create(c.level().dimension(), c.level(), pos);
        return !ForgeEventFactory.onBlockPlace(owner, snapshot, Direction.UP);
    }
}
