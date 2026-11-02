package com.yaseen.npcmind.api;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.core.ServerThreadGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;

/**
 * Opt-in player-like food/state simulation for any LivingEntity.
 *
 * <p>For NPC Mind's CompanionEntity this is a read/write adapter over its built-in food state;
 * its tick is already driven internally and this class deliberately does not tick it twice. For
 * other entities, call {@link #tick(LivingEntity)} once per server tick from the host mod. Values
 * live in Forge persistent entity NBT and are server-authoritative; host mods that render them on
 * clients must synchronize them using their own entity data/networking policy. Read snapshots on
 * the logical server thread; an off-thread read returns a neutral snapshot without touching NBT.</p>
 *
 * <p>This is a small deterministic compatibility layer, not a replacement for ServerPlayer's
 * FoodData. The host decides which actions add exhaustion and when food items are consumed.</p>
 */
public final class PlayerLikeState {
    private static final String ROOT = "NPCMindPlayerLikeState";
    private static final int MAX_FOOD = 20;
    private static final float MAX_EXHAUSTION = 40.0F;

    public record Snapshot(
            float health,
            float maxHealth,
            int foodLevel,
            float saturation,
            float exhaustion,
            int foodTickTimer,
            int airSupply,
            int maxAirSupply,
            int fireTicks,
            int frozenTicks,
            boolean fullyFrozen,
            PlayerLikeExperience.Snapshot experience
    ) {}

    private PlayerLikeState() {}

    /** Read player-like state plus vanilla LivingEntity environmental properties. */
    public static Snapshot read(LivingEntity entity) {
        if (!ServerThreadGuard.isOnServerThread(entity)) {
            return new Snapshot(0, 0, MAX_FOOD, 5.0F, 0, 0, 0, 0, 0, 0, false,
                    new PlayerLikeExperience.Snapshot(0, 0, 0));
        }
        int food = MAX_FOOD;
        float saturation = 5.0F;
        float exhaustion = 0.0F;
        int timer = 0;
        if (entity instanceof CompanionEntity companion) {
            food = companion.getFoodLevel();
            saturation = companion.getFoodSaturation();
            exhaustion = companion.getFoodExhaustion();
        } else {
            CompoundTag state = readStateTag(entity);
            food = clampFood(state.getInt("food"));
            saturation = clampSaturation(state.getFloat("saturation"));
            exhaustion = clampExhaustion(state.getFloat("exhaustion"));
            timer = Math.max(0, state.getInt("foodTickTimer"));
        }
        PlayerLikeExperience.Snapshot xp = PlayerLikeExperience.read(entity);
        return new Snapshot(entity.getHealth(), entity.getMaxHealth(), food, saturation, exhaustion, timer,
                entity.getAirSupply(), entity.getMaxAirSupply(), entity.getRemainingFireTicks(),
                entity.getTicksFrozen(), entity.isFullyFrozen(), xp);
    }

    /**
     * Tick opt-in hunger and natural regeneration for a non-Companion LivingEntity.
     * Call once per server tick. Returns false when the caller should not tick (client, wrong thread,
     * dead/removed entity, or CompanionEntity whose built-in tick already owns these rules).
     */
    public static boolean tick(LivingEntity entity) {
        if (!ServerThreadGuard.isOnServerThread(entity)) return false;
        if (entity.isRemoved() || !entity.isAlive()) return false;
        if (entity instanceof CompanionEntity) return false;
        if (!(entity.level() instanceof ServerLevel serverLevel)) return false;

        CompoundTag state = readStateTag(entity);
        int food = state.contains("food", 3) ? clampFood(state.getInt("food")) : MAX_FOOD;
        float saturation = state.contains("saturation", 5) ? clampSaturation(state.getFloat("saturation")) : 5.0F;
        float exhaustion = state.contains("exhaustion", 5) ? clampExhaustion(state.getFloat("exhaustion")) : 0.0F;
        int timer = Math.max(0, state.getInt("foodTickTimer"));

        while (exhaustion >= 4.0F) {
            exhaustion -= 4.0F;
            if (saturation > 0.0F) {
                saturation = Math.max(0.0F, saturation - 1.0F);
            } else if (serverLevel.getDifficulty() != Difficulty.PEACEFUL && food > 0) {
                food--;
            }
        }

        boolean hurt = entity.getHealth() < entity.getMaxHealth();
        boolean naturalRegen = serverLevel.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        // Vanilla FoodData parity (1.20.1): fast tier = saturation > 0 && hurt && food >= 20,
        // every 10 ticks heal min(saturation, 6) / 6 and spend the same amount as exhaustion;
        // slow tier = hurt && food >= 18, every 80 ticks heal 1.0 and spend 6.0 exhaustion.
        if (naturalRegen && saturation > 0.0F && hurt && food >= 20) {
            timer++;
            if (timer >= 10) {
                float healAmount = Math.min(saturation, 6.0F);
                entity.heal(healAmount / 6.0F);
                exhaustion = Math.min(MAX_EXHAUSTION, exhaustion + healAmount);
                timer = 0;
            }
        } else if (naturalRegen && hurt && food >= 18) {
            timer++;
            if (timer >= 80) {
                entity.heal(1.0F);
                exhaustion = Math.min(MAX_EXHAUSTION, exhaustion + 6.0F);
                timer = 0;
            }
        } else if (food <= 0) {
            timer++;
            if (timer >= 80) {
                float floor = switch (serverLevel.getDifficulty()) {
                    case PEACEFUL -> entity.getMaxHealth();
                    case EASY -> Math.min(10.0F, entity.getMaxHealth());
                    case NORMAL -> Math.min(1.0F, entity.getMaxHealth());
                    case HARD -> 0.0F;
                };
                if (entity.getHealth() > floor) entity.hurt(serverLevel.damageSources().starve(), 1.0F);
                timer = 0;
            }
        } else {
            timer = 0;
        }

        writeState(entity, food, saturation, exhaustion, timer);
        return true;
    }

    /** Add vanilla-scaled exhaustion (4 points consume one saturation or food point). */
    public static boolean addExhaustion(LivingEntity entity, float amount) {
        if (!isServerThread(entity) || amount <= 0.0F || !Float.isFinite(amount)) return false;
        if (entity instanceof CompanionEntity companion) {
            companion.addFoodExhaustion(amount);
            return true;
        }
        CompoundTag state = readStateTag(entity);
        int food = state.contains("food", 3) ? clampFood(state.getInt("food")) : MAX_FOOD;
        float saturation = state.contains("saturation", 5) ? clampSaturation(state.getFloat("saturation")) : 5.0F;
        float exhaustion = state.contains("exhaustion", 5) ? clampExhaustion(state.getFloat("exhaustion")) : 0.0F;
        exhaustion = Math.min(MAX_EXHAUSTION, exhaustion + amount);
        while (exhaustion >= 4.0F) {
            exhaustion -= 4.0F;
            if (saturation > 0.0F) saturation = Math.max(0.0F, saturation - 1.0F);
            else if (entity.level().getDifficulty() != Difficulty.PEACEFUL && food > 0) food--;
        }
        writeState(entity, food, saturation, exhaustion, Math.max(0, state.getInt("foodTickTimer")));
        return true;
    }

    /** Set the food bar only; saturation is left untouched, mirroring vanilla FoodData.setFood. */
    public static boolean setFoodLevel(LivingEntity entity, int food) {
        if (!isServerThread(entity)) return false;
        int safeFood = clampFood(food);
        if (entity instanceof CompanionEntity companion) {
            // Vanilla FoodData.setFood(int) changes ONLY the food value; saturation is owned
            // by eat()/exhaustion. The old min(saturation, food) clamp here silently
            // destroyed saved saturation on every host-mod food write — a parity deviation
            // flagged in review. CompanionEntity.setFoodSaturation still enforces its own
            // internal invariant when saturation itself is written.
            companion.setFoodLevel(safeFood);
            return true;
        }
        CompoundTag state = readStateTag(entity);
        float savedSaturation = state.getFloat("saturation");
        // Keep the existing saturation untouched (physical 0..20 bound only) exactly like
        // vanilla setFood, instead of collapsing it to the new food level.
        float carriedSaturation = Float.isFinite(savedSaturation)
                ? Math.max(0.0F, Math.min(MAX_FOOD, savedSaturation)) : 0.0F;
        writeState(entity, safeFood, carriedSaturation,
                clampExhaustion(state.getFloat("exhaustion")), Math.max(0, state.getInt("foodTickTimer")));
        return true;
    }

    /** Apply nutrition and saturation from an item whose normal use has already been accepted by the host. */
    public static boolean consumeFood(LivingEntity entity, int nutrition, float saturationModifier) {
        if (!isServerThread(entity) || nutrition <= 0 || !Float.isFinite(saturationModifier) || saturationModifier < 0) return false;
        Snapshot old = read(entity);
        int nextFood = clampFood(old.foodLevel() + nutrition);
        float saturationGain = nutrition * saturationModifier * 2.0F;
        float nextSaturation = Math.min(nextFood, Math.min(20.0F, old.saturation() + saturationGain));
        if (entity instanceof CompanionEntity companion) {
            companion.setFoodLevel(nextFood);
            companion.setFoodSaturation(nextSaturation);
        } else {
            writeState(entity, nextFood, nextSaturation, old.exhaustion(), old.foodTickTimer());
        }
        return true;
    }

    private static boolean isServerThread(LivingEntity entity) {
        return ServerThreadGuard.isOnServerThread(entity);
    }

    private static CompoundTag readStateTag(LivingEntity entity) {
        CompoundTag persistent = entity.getPersistentData();
        if (!persistent.contains(ROOT, 10)) {
            CompoundTag state = new CompoundTag();
            state.putInt("food", MAX_FOOD);
            state.putFloat("saturation", 5.0F);
            state.putFloat("exhaustion", 0.0F);
            state.putInt("foodTickTimer", 0);
            persistent.put(ROOT, state);
        }
        return persistent.getCompound(ROOT);
    }

    private static void writeState(LivingEntity entity, int food, float saturation, float exhaustion, int timer) {
        CompoundTag state = new CompoundTag();
        state.putInt("food", clampFood(food));
        // Saturation carries its own physical 0..20 bound only. Clamping it to the food level
        // here (the old clampSaturation(saturation, food)) silently destroyed saved saturation
        // whenever a host mod wrote a lower food level, which vanilla FoodData.setFood(int)
        // never does — it changes the food value alone (item 55 completion).
        state.putFloat("saturation", clampSaturation(saturation));
        state.putFloat("exhaustion", clampExhaustion(exhaustion));
        state.putInt("foodTickTimer", Math.max(0, Math.min(79, timer)));
        entity.getPersistentData().put(ROOT, state);
    }

    private static int clampFood(int food) { return Math.max(0, Math.min(MAX_FOOD, food)); }
    /** Physical bound only — vanilla keeps saturation independent of the food level between writes. */
    private static float clampSaturation(float saturation) {
        return Float.isFinite(saturation) ? Math.max(0.0F, Math.min(MAX_FOOD, saturation)) : 0.0F;
    }
    private static float clampExhaustion(float exhaustion) {
        return Float.isFinite(exhaustion) ? Math.max(0.0F, Math.min(MAX_EXHAUSTION, exhaustion)) : 0.0F;
    }
}
