package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import com.yaseen.npcmind.api.Internal;

/**
 * Rate-limited pursuit controller used by melee combat. It records the last destination rather
 * than retaining a path object, and delegates companion routes to the shared navigation lease.
 */
@Internal
public final class CombatMovement {
    private final Mob mob;
    private int nextRouteAttemptTick;
    private int nextFootworkTick;
    private int footworkEndsAt;
    private BlockPos requestedDestination;

    public CombatMovement(Mob mob) {
        this.mob = mob;
    }

    /** Starts a fresh pursuit cycle. */
    public void reset() {
        nextRouteAttemptTick = mob.tickCount;
        nextFootworkTick = mob.tickCount;
        footworkEndsAt = 0;
        requestedDestination = null;
    }

    /** Stops only navigation currently owned by this combat controller. */
    public void stop() {
        if (mob instanceof CompanionEntity companion) {
            if ("combat_move".equals(NavigationArbiter.owner(companion))) {
                NavigationArbiter.release(companion, "combat_move");
                mob.getNavigation().stop();
            }
        } else {
            mob.getNavigation().stop();
        }
        requestedDestination = null;
        footworkEndsAt = 0;
    }

    /**
     * Advance toward a living target without restarting pathfinding on every tick.
     *
     * @param target the current combat target
     * @param attackRange preferred close-combat range in blocks
     * @param speed movement speed passed to the navigation system
     * @param movementAllowed false while another combat action temporarily locks movement
     * @param inReach true when the caller has established a valid attack lane
     * @param separation centre-to-centre distance at which the entities touch
     */
    public void tick(LivingEntity target, double attackRange, double speed,
                     boolean movementAllowed, boolean inReach, double separation) {
        if (target == null || !target.isAlive()) {
            stop();
            return;
        }
        if (!movementAllowed) {
            if (mob instanceof CompanionEntity companion
                    && "combat_move".equals(NavigationArbiter.owner(companion))) {
                NavigationArbiter.release(companion, "combat_move");
            }
            mob.getNavigation().stop();
            footworkEndsAt = 0;
            requestedDestination = null;
            return;
        }

        double distanceSqr = mob.distanceToSqr(target);
        if ((inReach || distanceSqr <= attackRange * attackRange)
                && distanceSqr <= separation * separation) {
            stop();
            return;
        }

        // A short movement pulse can correct positioning when a target is technically within
        // reach but the companion's body is not aligned for a clean hit.
        if (inReach) {
            if (mob.tickCount >= nextFootworkTick) {
                nextFootworkTick = mob.tickCount + 31;
                footworkEndsAt = mob.tickCount + 5;
            }
            if (mob.tickCount >= footworkEndsAt) {
                mob.getNavigation().stop();
                return;
            }
        }

        BlockPos destination = target.blockPosition();
        boolean destinationShifted = requestedDestination == null
                || requestedDestination.distSqr(destination) > 2.25D;
        boolean routeActive = !mob.getNavigation().isDone();

        // Keep a useful route as long as its endpoint is still close to the target's last
        // requested block. The next check is deadline-based, not tick-by-tick path creation.
        if (routeActive && !destinationShifted) return;
        if (mob.tickCount < nextRouteAttemptTick) return;
        nextRouteAttemptTick = mob.tickCount + (distanceSqr > 576.0D ? 14 : 9);

        double safeSpeed = Math.max(0.1D, Math.min(1.0D, speed));
        if (mob instanceof CompanionEntity companion) {
            boolean accepted = NavigationArbiter.request(
                    companion, destination, "combat_move", 800, safeSpeed);
            if (accepted) {
                requestedDestination = destination.immutable();
            } else {
                if ("combat_move".equals(NavigationArbiter.owner(companion))) {
                    NavigationArbiter.release(companion, "combat_move");
                    mob.getNavigation().stop();
                }
                requestedDestination = null;
            }
            return;
        }

        Path candidate = mob.getNavigation().createPath(destination, 0);
        if (candidate != null && mob.getNavigation().moveTo(candidate, safeSpeed)) {
            requestedDestination = destination.immutable();
        } else {
            requestedDestination = null;
            mob.getNavigation().stop();
        }
    }

    /** True while vanilla navigation currently has an active route. */
    public boolean hasRoute() {
        return !mob.getNavigation().isDone();
    }
}
