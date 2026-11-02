package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.advanced.BodyguardController;
import com.yaseen.npcmind.autonomy.NavigationArbiter;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Player-directed friendly combat. Sparring with the owner (mode=owner/player/spar) is an
 * explicit interaction and stays allowed. For mode=named/target the resolved target goes
 * through {@link #isProtectedAlly}, an ally guard based on target liveness and ownership
 * checks: a named or UUID target can
 * never resolve to the owner, a creative/spectator (invulnerable) player, a fellow
 * companion, or an owned/tamed pet. That prevents accidental friendly fire from loose
 * name matching (e.g. "hunt Steve" while the owner is named Steve) while leaving every
 * deliberate combat mode intact.
 */
@Internal
public final class CombatTargetTool implements AITool {
    private record HuntPlan(String species, String color, int remaining, UUID currentTarget) {}
    private static final Map<UUID, HuntPlan> HUNTS = new ConcurrentHashMap<>();

    /**
     * Short-lived record of the live hunt target per companion. Vanilla target goals may
     * clear {@code getTarget()} before the target actually dies (out-of-follow-range stop,
     * re-selection, damage interruption), which previously broke the death-event linkage in
     * ForgeIntegration.onLivingDeath and let hasActiveHunt drop a still-valid plan.
     */
    private record RecentTarget(UUID targetId, long gameTime) {}
    private static final Map<UUID, RecentTarget> LAST_HUNT_TARGET = new ConcurrentHashMap<>();
    /** 10 seconds: window in which a cleared target may still die and count for the hunt. */
    private static final long RECENT_TARGET_TTL_TICKS = 200L;

    /** Remember the live hunt target so the death handler can credit a kill even after the
     * vanilla goal cleared the target reference. Cleared together with the plan. */
    public static void trackHuntTarget(CompanionEntity c, LivingEntity target) {
        if (c == null || target == null) return;
        LAST_HUNT_TARGET.put(c.getUUID(), new RecentTarget(target.getUUID(), c.level().getGameTime()));
    }

    /** Whether {@code dead} was this companion's hunt target recently enough to chain a plan. */
    public static boolean isRecentHuntTarget(CompanionEntity c, Entity dead) {
        if (c == null || dead == null) return false;
        RecentTarget rec = LAST_HUNT_TARGET.get(c.getUUID());
        if (rec == null || !rec.targetId().equals(dead.getUUID())) return false;
        if (c.level().getGameTime() - rec.gameTime() > RECENT_TARGET_TTL_TICKS) {
            LAST_HUNT_TARGET.remove(c.getUUID(), rec);
            return false;
        }
        return true;
    }

    /** Continue a count-based hunt only after the current target actually dies. */
    public static boolean onTargetDefeated(CompanionEntity c, LivingEntity defeated) {
        if (c == null || defeated == null) return false;
        HuntPlan plan = HUNTS.get(c.getUUID());
        if (plan == null || !defeated.getUUID().equals(plan.currentTarget())) return false;
        if (plan.remaining() <= 0) { HUNTS.remove(c.getUUID()); return false; }
        Animal next = nearestAnimal(c, plan.species(), plan.color());
        if (next == null) { HUNTS.remove(c.getUUID()); return false; }
        HUNTS.put(c.getUUID(), new HuntPlan(plan.species(), plan.color(), plan.remaining() - 1, next.getUUID()));
        trackHuntTarget(c, next);
        c.setTarget(next);
        c.enablePickup(200);
        c.getLookControl().setLookAt(next, 30.0F, 30.0F);
        return true;
    }

    public static void clearHunt(UUID companionId) {
        if (companionId == null) return;
        HUNTS.remove(companionId);
        LAST_HUNT_TARGET.remove(companionId);
    }
    public static boolean hasActiveHunt(CompanionEntity c) {
        if (c == null) return false;
        HuntPlan plan = HUNTS.get(c.getUUID());
        if (plan == null) return false;
        if (!(c.level() instanceof ServerLevel serverLevel)) return false;
        Entity planned = serverLevel.getEntity(plan.currentTarget());
        if (planned instanceof LivingEntity living && living.isAlive()
                && c.getTarget() != null && c.getTarget().getUUID().equals(plan.currentTarget())) return true;
        // Vanilla goals may have cleared the target reference while the planned target is
        // still alive and was recently this companion's hunt target. Give the death handler
        // its chance: drop the plan only when the target is gone, dead-and-unconsumed, or
        // outside the tracking window — never merely because getTarget() was nulled.
        if (planned instanceof LivingEntity alive && alive.isAlive() && isRecentHuntTarget(c, alive)) return true;
        // A target can be cleared by vanilla goals without a death event (for example on unload or
        // target invalidation). Do not leave crafting blocked forever behind a stale hunt plan.
        HUNTS.remove(c.getUUID(), plan);
        return false;
    }

    /**
     * Reject targets that must never be attacked via mode=named/target:
     * - the companion itself and its owner (explicit sparring uses mode=owner instead),
     * - other companions of this mod (same class never fights itself),
     * - players in creative/spectator or with invulnerable abilities (not damageable),
     * - any owned/tamed entity: the owner's wolf, cat, parrot, horse, or another
     *   companion's pet (getPetOwner pattern).
     */
    private static boolean isProtectedAlly(CompanionEntity c, Entity e) {
        if (e == c || e == c.getOwnerPlayer()) return true;
        if (e instanceof CompanionEntity) return true;
        if (e instanceof ServerPlayer sp
                && (sp.isCreative() || sp.isSpectator() || sp.getAbilities().invulnerable)) return true;
        if (e instanceof OwnableEntity owned && owned.getOwner() != null) return true;
        if (e instanceof TamableAnimal tame && tame.isTame()) return true;
        return false;
    }

    private static Animal nearestAnimal(CompanionEntity c, String species) { return nearestAnimal(c, species, ""); }

    private static Animal nearestAnimal(CompanionEntity c, String species, String color) {
        return c.level().getEntitiesOfClass(Animal.class, new AABB(c.blockPosition()).inflate(16),
                e -> e.isAlive() && !(e instanceof TamableAnimal tame && tame.isTame())
                        // Sheared sheep regrow wool over time; killing one yields mutton only and
                        // starves the wool chain the hunt was started for.
                        && !(e instanceof net.minecraft.world.entity.animal.Sheep sh && sh.isSheared())
                        && matchesSpecies(e, species) && matchesColor(e, color))
                .stream().min(Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
    }

    private static boolean matchesColor(Animal e, String color) {
        if (color == null || color.isBlank()) return true;
        if (!(e instanceof net.minecraft.world.entity.animal.Sheep sheep)) return false;
        return sheep.getColor().getName().equalsIgnoreCase(color.trim());
    }

    private static boolean matchesSpecies(Animal e, String species) {
        String path = e.getType().builtInRegistryHolder().key().location().getPath();
        if (species == null || species.isBlank()) {
            // An unspecified survival hunt means food-bearing animals, not horses, wolves,
            // villagers or every passive creature in the Animal hierarchy.
            return path.equals("sheep") || path.equals("cow") || path.equals("mooshroom")
                    || path.equals("pig") || path.equals("chicken") || path.equals("rabbit");
        }
        return path.equals(species) || path.endsWith("_" + species);
    }

    private static String normalizeTargetAlias(String requested) {
        String value = requested == null ? "" : requested.trim().replace('ـ', ' ');
        return switch (value) {
            case "الزومبي", "زومبي", "الميت الحي", "ميت حي" -> "zombie";
            case "هسک", "هَسْك" -> "husk";
            case "غريق", "الغريق" -> "drowned";
            case "هيكل عظمي", "هيكل عظمي عادي", "الهيكل العظمي" -> "skeleton";
            case "ستراي", "المتجمد" -> "stray";
            case "ويذر سكيلتون", "هيكل ويذر" -> "wither skeleton";
            case "عنكبوت", "العنكبوت" -> "spider";
            case "عنكبوت الكهف" -> "cave spider";
            case "كريبر", "المفجر" -> "creeper";
            case "إندرمان", "اندرمان", "انديرمان" -> "enderman";
            case "ساحرة", "الساحرة", "ويتش" -> "witch";
            case "غول حديدي", "غول الحديد", "ايرون غولم" -> "iron golem";
            case "قروي", "القروي" -> "villager";
            case "قروي زومبي", "قرويّ زومبي" -> "zombie_villager";
            case "خروف", "الخروف", "غنم", "غنمة", "sheep" -> "sheep";
            case "بقرة", "البقرة", "بقر", "cow", "cows" -> "cow";
            case "خنزير", "الخنزير", "pig", "pigs" -> "pig";
            case "دجاج", "دجاجة", "الدجاج", "chickens" -> "chicken";
            default -> value;
        };
    }

    private static boolean matchesNamedTarget(LivingEntity entity, String requested) {
        String path = entity.getType().builtInRegistryHolder().key().location().getPath().toLowerCase(java.util.Locale.ROOT);
        String custom = entity.getCustomName() == null ? "" : entity.getCustomName().getString().toLowerCase(java.util.Locale.ROOT);
        if (custom.equals(requested) || custom.contains(requested)) return true;
        String normalized = requested.replace(' ', '_').replace('-', '_');
        if (path.equals(normalized) || path.endsWith("_" + normalized)) return true;
        return switch (normalized) {
            case "zombie" -> path.equals("zombie") || path.equals("husk") || path.equals("drowned") || path.equals("zombie_villager");
            case "skeleton" -> path.equals("skeleton") || path.equals("stray") || path.equals("wither_skeleton");
            case "spider" -> path.equals("spider") || path.equals("cave_spider");
            case "sheep" -> path.equals("sheep");
            case "cow" -> path.equals("cow") || path.equals("mooshroom");
            case "pig" -> path.equals("pig");
            case "chicken" -> path.equals("chicken");
            case "creeper" -> path.equals("creeper");
            case "enderman" -> path.equals("enderman");
            case "witch" -> path.equals("witch");
            default -> false;
        };
    }

    public String name(){ return "combat_target"; }
    public String description(){
        return "Player-directed combat. mode=owner means fight/spar with the player; " +
                "mode=nearest_hostile attacks the nearest hostile monster; mode=nearest_animal hunts a nearby untamed animal " +
                "for survival resources (optional animal=sheep|cow|pig|chicken); mode=target attacks target_uuid; " +
                "mode=stop ends explicit combat. This is a friend interaction, so owner targeting is allowed.";
    }
    public String schema(){
        // The enum MUST list every value execute() accepts, otherwise ToolContract
        // rejects valid calls (player/spar/cancel) with invalid_enum before execution.
        return "{\"mode\":\"owner|player|spar|nearest_hostile|nearest_animal|named|target|stop|cancel\",\"target_uuid\":\"string(optional)\",\"target_name\":\"string(optional)\",\"animal\":\"string(optional)\",\"color\":\"string(optional, sheep wool color)\",\"count\":\"integer(optional, 1-16)\"}";
    }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        String mode = JsonArgs.string(a,"mode");
        if (mode.isBlank()) mode = "nearest_hostile";

        if (mode.equalsIgnoreCase("stop") || mode.equalsIgnoreCase("cancel") || mode.equalsIgnoreCase("none")) {
            TaskManager.cancelCurrentTask(c);
            NavigationArbiter.release(c.getUUID());
            c.setFriendlyCombat(false);
            clearHunt(c.getUUID());
            c.setTarget(null);
            c.setFollowEnabled(false);
            c.getNavigation().stop();
            return "SUCCESS combat_stopped";
        }

        LivingEntity target = null;
        if (mode.equalsIgnoreCase("owner") || mode.equalsIgnoreCase("player") || mode.equalsIgnoreCase("spar")) {
            target = p;
        } else if (mode.equalsIgnoreCase("named")) {
            String requested = normalizeTargetAlias(JsonArgs.string(a, "target_name").toLowerCase(java.util.Locale.ROOT).trim()
                    .replaceFirst("^(?:a|an|that|the|nearest|closest)\\s+", ""));
            if (requested.isBlank()) return "FAILED target_name_required";
            target = c.level().getEntitiesOfClass(LivingEntity.class, new AABB(c.blockPosition()).inflate(24),
                    e -> e != c && e.isAlive() && !isProtectedAlly(c, e) && matchesNamedTarget(e, requested))
                    .stream().min(Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
            if (target == null) return "FAILED no_entity_matching_name=" + requested;
        } else if (mode.equalsIgnoreCase("target")) {
            String raw = JsonArgs.string(a, "target_uuid");
            try {
                UUID id = UUID.fromString(raw);
                Entity e = ((ServerLevel) c.level()).getEntity(id);
                if (e instanceof LivingEntity living && living.isAlive() && e != c) {
                    // Explicit reason instead of a misleading "not found" when the UUID
                    // belongs to someone the ally guard protects.
                    if (isProtectedAlly(c, e)) return "FAILED target_is_protected_ally_owner_companion_pet_or_creative";
                    target = living;
                }
            } catch (IllegalArgumentException ignored) {}
            if (target == null) return "FAILED target_uuid_not_found_or_not_living";
        } else if (mode.equalsIgnoreCase("nearest_animal")) {
            String species = JsonArgs.string(a, "animal").toLowerCase(java.util.Locale.ROOT).trim();
            target = nearestAnimal(c, species, JsonArgs.string(a, "color").toLowerCase(java.util.Locale.ROOT).trim());
        } else {
            target = c.level().getEntitiesOfClass(LivingEntity.class,
                    new AABB(c.blockPosition()).inflate(16),
                    e -> e != c && e.isAlive() && e instanceof Monster)
                    .stream().min(Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
        }

        if (target == null) return "FAILED no_combat_target";

        // Explicit combat owns movement and must not compete with an earlier mining/build/move task.
        TaskManager.cancelCurrentTask(c);
        NavigationArbiter.release(c.getUUID());
        // Explicit player combat wins over bodyguard/follow behavior until stopped or target dies.
        BodyguardController.clear(c.getUUID());
        c.setFriendlyCombat(true);
        clearHunt(c.getUUID());
        if (mode.equalsIgnoreCase("nearest_animal")) {
            // Animal loot is generated by Minecraft's normal death drops. Temporarily enable
            // short-range pickup so wool/mutton/etc. are actually collected before the next hunt.
            c.enablePickup(200);
            int requested = Math.max(1, Math.min(16, JsonArgs.integer(a, "count", 1)));
            if (requested > 1) HUNTS.put(c.getUUID(), new HuntPlan(
                    JsonArgs.string(a, "animal").toLowerCase(java.util.Locale.ROOT).trim(),
                    JsonArgs.string(a, "color").toLowerCase(java.util.Locale.ROOT).trim(), requested - 1, target.getUUID()));
        }
        trackHuntTarget(c, target);
        c.setTarget(target);
        c.setFollowEnabled(false);
        c.getNavigation().stop();
        c.getLookControl().setLookAt(target,30,30);
        return "STARTED combat target=" + target.getUUID() + " mode=" + mode + " friendly=true";
    }
}
