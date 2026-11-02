package com.yaseen.npcmind.rules;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.yaseen.npcmind.api.Internal;
import com.yaseen.npcmind.core.ServerThreadGuard;

/**
 * Entity-agnostic player-style interaction rules for host mods.
 * These checks accept any LivingEntity, not only NPC Mind's CompanionEntity.
 * Call them on the logical server before attempting a real world interaction.
 */
@Internal
public final class PlayerActionRules {
    private PlayerActionRules() {}

    /** Checks eye reach and collision-based visibility to a concrete point. */
    public static boolean canReachPoint(LivingEntity actor, Vec3 point, double reach) {
        if (!ServerThreadGuard.isOnServerThread(actor)) return false;
        if (actor == null || point == null || actor.isRemoved() || reach < 0.0D
                || !Double.isFinite(reach) || !Double.isFinite(point.x)
                || !Double.isFinite(point.y) || !Double.isFinite(point.z)) return false;
        Vec3 eye = actor.getEyePosition();
        double distanceSqr = eye.distanceToSqr(point);
        if (distanceSqr > reach * reach) return false;
        var hit = actor.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        if (hit.getType() != HitResult.Type.BLOCK) return true;
        // A ray ending exactly on the visible surface is valid; an earlier collision is not.
        return eye.distanceToSqr(hit.getLocation()) + 0.01D >= distanceSqr;
    }

    /** A target is reachable if any conservative body sample is visible within the given reach. */
    public static boolean canReachEntity(LivingEntity actor, LivingEntity target, double reach) {
        if (!ServerThreadGuard.isOnServerThread(actor)) return false;
        if (target == null || actor == target || !target.isAlive()
                || actor.level() != target.level() || reach < 0.0D || !Double.isFinite(reach)) return false;
        AABB box = target.getBoundingBox();
        double x = (box.minX + box.maxX) * 0.5D;
        double z = (box.minZ + box.maxZ) * 0.5D;
        double midY = (box.minY + box.maxY) * 0.5D;
        Vec3 eye = actor.getEyePosition();
        Vec3 nearest = new Vec3(
                Math.max(box.minX, Math.min(box.maxX, eye.x)),
                Math.max(box.minY, Math.min(box.maxY, eye.y)),
                Math.max(box.minZ, Math.min(box.maxZ, eye.z)));
        Vec3[] points = new Vec3[] {
                nearest,
                new Vec3(x, Math.max(box.minY + 0.05D, Math.min(box.maxY - 0.05D, target.getEyeY())), z),
                new Vec3(x, midY, z),
                new Vec3(x, box.minY + Math.min(0.15D, Math.max(0.02D, (box.maxY - box.minY) * 0.2D)), z),
                new Vec3(box.minX + (box.maxX - box.minX) * 0.15D, midY, z),
                new Vec3(box.maxX - (box.maxX - box.minX) * 0.15D, midY, z)
        };
        for (Vec3 point : points) if (canReachPoint(actor, point, reach)) return true;
        return false;
    }
}
