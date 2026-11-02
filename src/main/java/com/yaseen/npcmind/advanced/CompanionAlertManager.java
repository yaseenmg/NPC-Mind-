package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Teammate threat alerts shared between companions in the same dimension.
 *
 * <p>When a companion takes damage from a hostile mob, the attack is registered here keyed by
 * dimension. An idle companion with nothing better to do may pick up a nearby teammate's alert
 * and assist, preferring the threat that is currently attacking a teammate and is closest to
 * the helper. Alerts expire after a short TTL and are dropped as soon as the threat dies, so
 * the registry can never steer a companion toward a fight that is already over.</p>
 *
 * <p>Allies never raise alerts (companions, the owner, tamed/owned animals) and a companion
 * never assists from its own alert. Same-team alerts carry farther than solitary ones.</p>
 */
@Internal
public final class CompanionAlertManager {
    private CompanionAlertManager() {}

    /** 10 seconds: a threat position stays tactically relevant for this long. */
    private static final long ALERT_TTL_TICKS = 200L;
    private static final double ASSIST_RADIUS_SQR = 24.0D * 24.0D;
    private static final double TEAM_ASSIST_RADIUS_SQR = 48.0D * 48.0D;

    private record Alert(UUID threatId, Vec3 pos, UUID reporterId, String reporterTeamId, long raisedAt) {}
    private static final Map<ResourceLocation, Map<UUID, Alert>> BY_DIM = new ConcurrentHashMap<>();

    /** Register that {@code threat} is attacking {@code reporter}. Allies are never recorded. */
    public static void raise(ServerLevel level, LivingEntity threat, CompanionEntity reporter) {
        if (level == null || threat == null || reporter == null) return;
        if (!threat.isAlive() || !isAlertWorthy(threat, reporter)) return;
        BY_DIM.computeIfAbsent(level.dimension().location(), k -> new ConcurrentHashMap<>())
                .put(threat.getUUID(), new Alert(threat.getUUID(), threat.position(),
                        reporter.getUUID(), reporter.getTeamState().teamId(), level.getGameTime()));
    }

    private static boolean isAlertWorthy(LivingEntity threat, CompanionEntity reporter) {
        if (threat instanceof CompanionEntity) return false;                 // allies never alert
        if (threat == reporter || threat == reporter.getOwnerPlayer()) return false;
        if (threat instanceof TamableAnimal tame && tame.isTame()) return false;
        if (threat instanceof OwnableEntity owned && owned.getOwner() != null) return false;
        return true;
    }

    /**
     * Best threat worth assisting right now, or null. Considers only live, unexpired alerts
     * within the assist radius; prefers a threat that is currently attacking a teammate and
     * ranks closer positions first. Expired/dead alerts are pruned after the scan.
     *
     * <p>Radius scoping: the extended 48-block team radius applies only when the helper is on
     * the <b>same team as the reporter</b> (both non-blank and equal). A companion in some team
     * no longer gets the extended range for alerts raised by members of other teams.</p>
     */
    public static LivingEntity pickAssistTarget(CompanionEntity c) {
        if (c == null || !(c.level() instanceof ServerLevel level)) return null;
        Map<UUID, Alert> map = BY_DIM.get(level.dimension().location());
        if (map == null || map.isEmpty()) return null;
        long now = level.getGameTime();
        String myTeam = c.getTeamState().teamId();
        List<Map.Entry<UUID, Alert>> prune = new ArrayList<>();
        LivingEntity best = null;
        double bestScore = -1.0D;
        for (Map.Entry<UUID, Alert> entry : map.entrySet()) {
            Alert alert = entry.getValue();
            if (now - alert.raisedAt() > ALERT_TTL_TICKS) { prune.add(entry); continue; }
            if (alert.reporterId().equals(c.getUUID())) continue;            // never assist from own alert
            Entity live = level.getEntity(alert.threatId());
            if (!(live instanceof Monster monster) || !monster.isAlive()) {  // dead or gone: prune
                prune.add(entry);
                continue;
            }
            // Precise assistance: only a threat that is actually attacking a teammate right now.
            if (!(monster.getTarget() instanceof CompanionEntity)) continue;
            // Extended team radius requires matching team IDs between reporter and helper —
            // not merely that the helper has any team at all (review fix).
            boolean sameTeam = !myTeam.isBlank() && myTeam.equals(alert.reporterTeamId());
            double radiusSqr = sameTeam ? TEAM_ASSIST_RADIUS_SQR : ASSIST_RADIUS_SQR;
            // Radius is measured against the LIVE entity position: the alert position was
            // frozen at raise time and the fight may have moved since.
            double d2 = c.distanceToSqr(monster.position());
            if (d2 > radiusSqr) continue;
            double score = 100.0D / (1.0D + d2);
            if (score > bestScore) { bestScore = score; best = monster; }
        }
        // Collect-then-remove: pruning inside the iteration is ConcurrentHashMap-safe but can
        // skip entries the iterator had already produced; do it after the loop instead.
        for (Map.Entry<UUID, Alert> entry : prune) map.remove(entry.getKey(), entry.getValue());
        return best;
    }

    /** One-line diagnostics for the inspect_ai tool. */
    public static String status() {
        int total = 0;
        for (Map<UUID, Alert> m : BY_DIM.values()) total += m.size();
        return "activeAlerts=" + total;
    }

    /** Called from CompanionCleanup.purge: drop alerts raised by a removed companion. */
    public static void clear(UUID reporterId) {
        if (reporterId == null) return;
        for (Map<UUID, Alert> m : BY_DIM.values()) {
            m.values().removeIf(alert -> alert.reporterId().equals(reporterId));
        }
    }
}
