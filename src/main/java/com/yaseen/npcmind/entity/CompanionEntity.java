package com.yaseen.npcmind.entity;
import com.yaseen.npcmind.advanced.InventoryIntelligence;
import com.yaseen.npcmind.autonomy.NavigationArbiter;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.advanced.CombatLearning;
import com.yaseen.npcmind.advanced.SurvivalGuard;
import com.yaseen.npcmind.advanced.SleepController;
import com.yaseen.npcmind.advanced.AITickThrottle;
import com.yaseen.npcmind.core.CompanionKernel;
import com.yaseen.npcmind.state.CompanionState;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.state.TeachingMemory;
import com.yaseen.npcmind.state.CraftingKnowledge;
import com.yaseen.npcmind.state.WorldMemory;
import com.yaseen.npcmind.state.LongTermGoal;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.DecisionJournal;
import com.yaseen.npcmind.debug.AuditLog;
import com.yaseen.npcmind.advanced.ExperimentJournal;
import com.yaseen.npcmind.autonomy.AutonomyState;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.agent.PlanExecutionController;
import com.yaseen.npcmind.team.CompanionTeam;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

@Internal
public class CompanionEntity extends PathfinderMob {
    private static volatile CompanionSpeech speechSink;
    private static final String OWNER_TAG = "Owner";
    private static final EntityDataAccessor<Integer> FOOD_LEVEL = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final int MAX_FOOD = 20;
    private static final EntityDataAccessor<Float> FOOD_SATURATION = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> XP_LEVEL = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> XP_PROGRESS = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> XP_TOTAL = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private float foodExhaustion = 0.0F;
    /** Last server position for player-like distance-based sprint exhaustion. */
    private net.minecraft.world.phys.Vec3 lastSprintExhaustionPosition;
    /** Offline-owner speech log rate limit: one log line per interval per companion. */
    private static final java.util.Map<java.util.UUID, Long> LAST_OFFLINE_LOG = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long OFFLINE_LOG_INTERVAL_TICKS = 200L;
    private static final EntityDataAccessor<Integer> SELECTED_SLOT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> EATING = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> ANIMATION_ACTION = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BYTE);
    /** 0 normal, 1 mining, 2 placing, 3 eating/use-action. */
    private int animationActionTicks = 0;
    private int eatingTicks = 0;
    private int eatingStartedTick = 0;
    private int eatingDuration = 1;
    /** Prevent repeated wall-collision jump attempts from charging exhaustion every server tick. */
    private int lastAutoJumpExhaustTick = -100;
    private int enderPearlCooldownTicks = 0;
    private ItemStack eatingStack = ItemStack.EMPTY;
    private ItemStack eatingRestore = ItemStack.EMPTY;
    private UUID owner;
    private boolean followEnabled = false;
    /** Explicit combat chosen by the player; unlike bodyguard mode this may target the owner. */
    private boolean friendlyCombat = false;
    private static final String CANONICAL_NAME = "Steve";
    private String displayName = CANONICAL_NAME;
    /** Inventory slot temporarily holding the pre-build main hand. Restored after cancel/reload. */
    private int buildHandBackupSlot = -1;
    /** Pickup window requested by an explicit harvesting/mining task; measured in entity ticks. */
    private long pickupEnabledUntilTick = -1L;
    /** Independent bounded XP-orb pickup window; an XP-only command must not vacuum item drops. */
    private long experiencePickupEnabledUntilTick = -1L;
    /** The companion's real persistent 36-slot player-style inventory (27 main + 9 hotbar). */
    private final SimpleContainer inventory = new SimpleContainer(36);
    private final CombatLearning combatLearning = new CombatLearning(this);
    private final SurvivalGuard.State survivalState = new SurvivalGuard.State();
    private final SleepController.State sleepState = new SleepController.State();
    private final AITickThrottle aiTickThrottle = new AITickThrottle();
    private final CompanionState companionState = new CompanionState();
    private final SkillProgression skills = new SkillProgression();
    private final TeachingMemory teaching = new TeachingMemory();
    private final CraftingKnowledge craftingKnowledge = new CraftingKnowledge();
    private final WorldMemory worldMemory = new WorldMemory();
    private final LongTermGoal goal = new LongTermGoal();
    private final ActionPermissions permissions = new ActionPermissions();
    private final DecisionJournal decisions = new DecisionJournal();
    private final ExperimentJournal experiments = new ExperimentJournal();
    private final AutonomyState autonomy = new AutonomyState();
    private final CompanionTeam team = new CompanionTeam();
    private BlockPos smeltJobPos;
    private String smeltJobOutput = "";
    private long smeltJobStartedTick = -1L;
    private net.minecraft.resources.ResourceLocation smeltJobDimension;
    /** True only when an emergency Totem displaced the companion's real shield. */
    private boolean shieldDisplacedByTotem = false;
    /** Snapshot of the shield that was moved out of the offhand for the emergency Totem. */
    private ItemStack displacedShield = ItemStack.EMPTY;

    public boolean isShieldDisplacedByTotem() { return shieldDisplacedByTotem; }
    public ItemStack getDisplacedShield() { return displacedShield; }
    public void rememberDisplacedShield(ItemStack shield) { displacedShield = shield == null ? ItemStack.EMPTY : shield.copy(); }
    public void clearDisplacedShield() { displacedShield = ItemStack.EMPTY; }
    public void setShieldDisplacedByTotem(boolean value) { shieldDisplacedByTotem = value; }

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        // Default display name for new companions; owners and host mods can change it.
        this.displayName = CANONICAL_NAME;
        this.setCustomName(Component.literal(CANONICAL_NAME));
        this.setCustomNameVisible(true);
    }

    /**
     * Keep the vanilla LivingEntity combat/navigation machinery active. Earlier
     * builds imported these goals but never registered them, which meant that a
     * target could be selected while the entity had no goal capable of actually
     * following/attacking it.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new com.yaseen.npcmind.advanced.CompanionRangedGoal(this));
        this.goalSelector.addGoal(1, new com.yaseen.npcmind.advanced.CompanionMeleeGoal(this, 1.2D));
        this.goalSelector.addGoal(2, new FollowOwnerGoal(this, 1.05D, 3.0F, 24.0F));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(FOOD_LEVEL, MAX_FOOD);
        this.entityData.define(FOOD_SATURATION, 5.0F);
        this.entityData.define(XP_LEVEL, 0);
        this.entityData.define(XP_PROGRESS, 0.0F);
        this.entityData.define(XP_TOTAL, 0);
        this.entityData.define(SELECTED_SLOT, 0);
        this.entityData.define(EATING, false);
        this.entityData.define(ANIMATION_ACTION, (byte) 0);
    }

    /** True only while the companion is really eating (not drawing a bow / blocking). */
    public boolean isEating() { return this.entityData.get(EATING); }

    private boolean rangedMode = false;
    public boolean isRangedMode() { return rangedMode; }
    public void setRangedMode(boolean v) { rangedMode = v; }

    public int getFoodLevel() { return this.entityData.get(FOOD_LEVEL); }
    public void setFoodLevel(int value) { this.entityData.set(FOOD_LEVEL, Math.max(0, Math.min(MAX_FOOD, value))); }
    public int getMaxFoodLevel() { return MAX_FOOD; }
    public int getExperienceLevel() { return this.entityData.get(XP_LEVEL); }
    public float getExperienceProgress() { return this.entityData.get(XP_PROGRESS); }
    public int getTotalExperiencePoints() { return this.entityData.get(XP_TOTAL); }
    public int getEnderPearlCooldownTicks() { return enderPearlCooldownTicks; }
    public void setEnderPearlCooldownTicks(int ticks) { enderPearlCooldownTicks = Math.max(0, Math.min(1200, ticks)); }

    /** Accept experience points from real orbs or a deterministic host-mod reward. */
    public void addExperiencePoints(int points) {
        if (level().isClientSide() || points <= 0) return;
        int level = getExperienceLevel();
        float progress = getExperienceProgress();
        int total = (int) Math.min(Integer.MAX_VALUE, (long) getTotalExperiencePoints() + points);
        int need = xpNeededForNextLevel(level);
        float pointsWithinLevel = progress * need + points;
        while (pointsWithinLevel >= need && level < 21862) {
            pointsWithinLevel -= need;
            level++;
            need = xpNeededForNextLevel(level);
        }
        if (level >= 21862) {
            level = 21862;
            pointsWithinLevel = 0.0F;
            total = Integer.MAX_VALUE;
        }
        setExperienceState(level, need <= 0 ? 0.0F : pointsWithinLevel / need, total);
    }

    public void setExperienceState(int level, float progress, int total) {
        int safeLevel = Math.max(0, Math.min(21862, level));
        float safeProgress = Float.isFinite(progress) ? Math.max(0.0F, Math.min(0.999999F, progress)) : 0.0F;
        this.entityData.set(XP_LEVEL, safeLevel);
        this.entityData.set(XP_PROGRESS, safeProgress);
        this.entityData.set(XP_TOTAL, Math.max(0, total));
    }

    public static int xpNeededForNextLevel(int level) {
        int safeLevel = Math.max(0, level);
        if (safeLevel >= 30) return 112 + (safeLevel - 30) * 9;
        if (safeLevel >= 15) return 37 + (safeLevel - 15) * 5;
        return 7 + safeLevel * 2;
    }

    public float getFoodSaturation() { return this.entityData.get(FOOD_SATURATION); }
    public float getFoodExhaustion() { return foodExhaustion; }
    public void setFoodSaturation(float value) { this.entityData.set(FOOD_SATURATION, Math.max(0.0F, Math.min(getFoodLevel(), Math.min(20.0F, value)))); }
    public void addFoodExhaustion(float amount) {
        if (amount <= 0.0F) return;
        foodExhaustion += amount;
        while (foodExhaustion >= 4.0F) {
            foodExhaustion -= 4.0F;
            if (getFoodSaturation() > 0.0F) setFoodSaturation(Math.max(0.0F, getFoodSaturation() - 1.0F));
            else if (getFoodLevel() > 0) setFoodLevel(getFoodLevel() - 1);
        }
    }
    public int getSelectedSlot() { return this.entityData.get(SELECTED_SLOT); }
    public void setSelectedSlot(int slot) { this.entityData.set(SELECTED_SLOT, Math.max(0, Math.min(8, slot))); }

    public byte getAnimationAction() { return this.entityData.get(ANIMATION_ACTION); }

    /**
     * Broadcast a short explicit action to the client renderer. This is used for
     * actions that cannot be inferred reliably from vanilla LivingEntity flags,
     * such as server-side progressive mining and direct block placement.
     */
    public void playAnimationAction(byte action, int ticks) {
        this.entityData.set(ANIMATION_ACTION, action);
        // Vanilla food use can last longer than 20 ticks; keep the action flag alive for the
        // complete use duration instead of clearing it before eating has finished.
        this.animationActionTicks = Math.max(0, Math.min(100, ticks));
        // ANIMATION_ACTION is useful for custom client state, but vanilla PlayerModel
        // renders hand movement from the normal swing state. Trigger both so mining and
        // placing are visible on clients that use the stock renderer as well.
        if ((action == 1 || action == 2) && !level().isClientSide()) {
            swing(InteractionHand.MAIN_HAND, true);
        }
    }

    /** Starts a real vanilla-style eating cycle so the client can render the food in hand. */
    /** Eats only when hungry (normal food). */
    public boolean startEatingFromInventory() { return eat(false, false); }

    /** Raw meats a player-like companion prefers to cook before eating (still eaten if it is all there is). */
    private static boolean isRawMeat(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.BEEF) || stack.is(net.minecraft.world.item.Items.PORKCHOP)
                || stack.is(net.minecraft.world.item.Items.CHICKEN) || stack.is(net.minecraft.world.item.Items.MUTTON)
                || stack.is(net.minecraft.world.item.Items.COD) || stack.is(net.minecraft.world.item.Items.SALMON)
                || stack.is(net.minecraft.world.item.Items.RABBIT);
    }

    /**
     * Applies only the companion's custom food-level / saturation / mood numbers.
     * Used when vanilla already ran {@code finishUsingItem} (race lost) so status effects
     * and container remainders are not applied a second time.
     */
    private void applyEatingNutritionNumbersOnly(ItemStack food) {
        if (food == null || food.isEmpty() || !food.isEdible()) return;
        var props = food.getFoodProperties(this);
        int nutrition = props == null ? 0 : props.getNutrition();
        int gain = Math.max(1, nutrition);
        int oldFood = getFoodLevel();
        setFoodLevel(oldFood + gain);
        float saturationGain = props == null ? 0.0F : props.getNutrition() * props.getSaturationModifier() * 2.0F;
        setFoodSaturation(Math.min(getFoodLevel(), getFoodSaturation() + saturationGain));
        getCompanionState().eatEvent();
    }

    /**
     * Full completion path: run {@code finishUsingItem} (status effects + genuine container
     * remainder) then apply the companion's player-like nutrition numbers exactly once.
     * Call this only when we are the ones finishing the use action.
     */
    private void applyEatingNutrition(ItemStack food) {
        if (food == null || food.isEmpty() || !food.isEdible()) return;
        var props = food.getFoodProperties(this);
        // Some vanilla/custom edible items return the same non-empty ItemStack when used by a
        // non-Player LivingEntity. The one item was already split from inventory before eating,
        // so re-inserting that same item duplicates food. Preserve only genuine container results.
        ItemStack consumedInput = food.copy();
        ItemStack result = food.finishUsingItem(level(), this);
        int nutrition = props == null ? 0 : props.getNutrition();
        int gain = Math.max(1, nutrition);
        int oldFood = getFoodLevel();
        setFoodLevel(oldFood + gain);
        float saturationGain = props == null ? 0.0F : props.getNutrition() * props.getSaturationModifier() * 2.0F;
        setFoodSaturation(Math.min(getFoodLevel(), getFoodSaturation() + saturationGain));
        getCompanionState().eatEvent();
        if (!result.isEmpty() && !ItemStack.isSameItemSameTags(result, consumedInput)) {
            ItemStack leftover = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(inventory, result.copy());
            if (!leftover.isEmpty()) spawnAtLocation(leftover);
        }
    }

    /**
     * Eats to restore health as well as hunger. With {@code emergency} the best healing item
     * (golden apples first) is used even if the hunger bar is full.
     */
    public boolean startEating(boolean emergency) { return eat(true, emergency); }

    private boolean eat(boolean allowHurtEating, boolean allowGolden) {
        if (isUsingItem() || level().isClientSide()) return false;
        boolean hungry = getFoodLevel() < MAX_FOOD;
        boolean hurt = getHealth() < getMaxHealth() - 1.0F;
        if (!hungry && !(allowHurtEating && hurt)) return false;
        int best = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isEdible()) continue;
            boolean enchanted = stack.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE);
            boolean golden = enchanted || stack.is(net.minecraft.world.item.Items.GOLDEN_APPLE);
            if (golden && !allowGolden) continue; // keep golden apples for emergencies
            net.minecraft.world.food.FoodProperties fp = stack.getFoodProperties(this);
            if (fp == null) continue;
            boolean harmful = false;
            for (var effect : fp.getEffects()) {
                if (effect.getFirst().getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) { harmful = true; break; }
            }
            if (harmful) continue; // rotten flesh, spider eye, pufferfish...
            double score = fp.getNutrition() + fp.getNutrition() * fp.getSaturationModifier();
            // Raw meat is emergency food: a player-like companion cooks it first. Penalise it so
            // any cooked alternative wins while raw meat stays as the fallback when nothing else exists.
            if (isRawMeat(stack)) score -= 6.0D;
            if (golden) score += enchanted ? 200.0D : 100.0D;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best < 0) return false;
        ItemStack one = inventory.getItem(best).split(1);
        if (one.isEmpty()) return false;
        eatingRestore = getMainHandItem().copy();
        eatingStack = one.copy();
        this.entityData.set(EATING, true);
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, one);
        eatingDuration = Math.max(1, one.getUseDuration());
        // Finish one tick BEFORE the vanilla countdown so our completion path (which applies the
        // player-like nutrition) always wins the race against vanilla's completeUsingItem().
        eatingTicks = Math.max(1, eatingDuration - 1);
        eatingStartedTick = tickCount;
        playAnimationAction((byte) 3, eatingDuration);
        startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        inventory.setChanged();
        return true;
    }

    /**
     * PathfinderMob does not normally wear player-style armor durability down.
     * Vanilla mobs can therefore keep armor at full durability forever.
     * Apply the normal minimum-one / quarter-damage durability rule used by
     * vanilla living entities and broadcast the break event to the client.
     */
    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        if (amount <= 0.0F) return;
        int damage = Math.max(1, (int) Math.floor(amount / 4.0F));
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()
                    && !(stack.getItem().isFireResistant() && source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE))) {
                stack.hurtAndBreak(damage, this, e -> e.broadcastBreakEvent(slot));
            }
        }
    }

    /**
     * Mob#doHurtTarget never wears the held weapon down (only Player#attack does), so the
     * companion's sword used to last forever. Apply the vanilla rule: 1 durability for
     * swords, 2 for tools/other damageable items, using the normal Unbreaking logic.
     */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        // Keep every direct combat entry point on the same player-parity damage path.
        if (target instanceof net.minecraft.world.entity.LivingEntity living) {
            return com.yaseen.npcmind.advanced.PlayerCombatParity.attack(
                    this, living, com.yaseen.npcmind.advanced.PlayerCombatParity.attackDelayTicks(this));
        }
        return false;
    }

    /**
     * Vanilla mobs never wear a shield down when it blocks (only Player does).
     * Apply the player rule: blocked damage >= 3 costs 1 + floor(damage) durability.
     */
    @Override
    protected void hurtCurrentlyUsedShield(float amount) {
        // getUsedItemHand() has a default value even when the entity is not actively using an item.
        // The vanilla shield path should only wear the shield that is currently being raised.
        if (!isUsingItem() || amount < 3.0F) return;
        ItemStack shield = getUseItem();
        if (!level().isClientSide() && shield.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            int wear = 1 + (int) Math.floor(amount);
            final InteractionHand hand = getUsedItemHand();
            shield.hurtAndBreak(wear, this, e -> e.broadcastBreakEvent(hand));
            if (shield.isEmpty()) {
                setItemSlot(hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                stopUsingItem();
                playSound(net.minecraft.sounds.SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + getRandom().nextFloat() * 0.4F);
            }
        }
    }

    @Override
    public void aiStep() {
        // Handle the companion's own eating timer before LivingEntity can
        // auto-complete the use action. This keeps the visible eating animation
        // synchronized with the actual consumption.
        // Eating got interrupted from outside (item use stopped): give the food back and restore the weapon.
        // But vanilla's updatingUsingItem() runs BEFORE aiStep() and completes the consumption on the
        // same tick our own timer would expire, which used to land here and "restore" already-consumed
        // food in an endless eat-without-saturation loop. Distinguish interruption (early) from
        // completion (the full duration already elapsed).
        if (!level().isClientSide() && !eatingStack.isEmpty() && !isUsingItem()) {
            int elapsed = tickCount - eatingStartedTick;
            if (elapsed >= Math.max(1, eatingDuration - 1)) {
                // Vanilla already ran finishUsingItem (effects + remainder already applied).
                // Only apply the companion's custom food/saturation numbers — never call
                // finishUsingItem again or golden-apple / honey-bottle effects would double.
                applyEatingNutritionNumbersOnly(eatingStack.copy());
            } else {
                // SimpleContainer.addItem silently discards the stack when the inventory is full.
                // Insert through the remainder-aware helper and drop the rest so food is never lost.
                ItemStack returnedFood = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(inventory, eatingStack.copy());
                if (!returnedFood.isEmpty()) spawnAtLocation(returnedFood);
            }
            setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, eatingRestore);
            eatingRestore = ItemStack.EMPTY;
            eatingStack = ItemStack.EMPTY;
            this.entityData.set(EATING, false);
        }
        if (!level().isClientSide() && isUsingItem() && getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && !eatingStack.isEmpty()) {
            if (eatingTicks > 0) eatingTicks--;
            if (eatingTicks <= 0) {
                ItemStack food = getUseItem().copy();
                try {
                    applyEatingNutrition(food);
                } catch (Throwable failure) {
                    // The item was already split from the inventory. Restore it instead of
                    // losing food when a modded food implementation throws, and leave a trace.
                    com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] food use failed for {} on companion {}",
                            food.getItem().builtInRegistryHolder().key().location(), getUUID(), failure);
                    if (!food.isEmpty()) {
                        ItemStack restored = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(inventory, food.copy());
                        if (!restored.isEmpty()) spawnAtLocation(restored);
                    }
                } finally {
                    stopUsingItem();
                    setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, eatingRestore);
                    eatingRestore = ItemStack.EMPTY;
                    eatingStack = ItemStack.EMPTY;
                    this.entityData.set(EATING, false);
                }
            }
        }
        super.aiStep();
        if (!level().isClientSide()) {
            // Player-like hunger/exhaustion and natural regeneration.
            // Match the vanilla player exhaustion scale for sprinting. Falling itself is not a
            // per-tick exhaustion source; jump costs are applied at the actual jump trigger below.
            // Exhaustion is distance-based, not a flat amount every tick. A flat per-tick cost
            // made sprinting either almost free (0.01/tick) or about four times too expensive
            // (0.1/tick) depending on the build. Charge vanilla-like 0.1 per horizontal block.
            net.minecraft.world.phys.Vec3 currentPos = position();
            if (lastSprintExhaustionPosition != null && isSprinting()) {
                double dx = currentPos.x - lastSprintExhaustionPosition.x;
                double dz = currentPos.z - lastSprintExhaustionPosition.z;
                double moved = Math.sqrt(dx * dx + dz * dz);
                if (Double.isFinite(moved) && moved > 0.001D) addFoodExhaustion((float)(moved * 0.1D));
            }
            lastSprintExhaustionPosition = currentPos;
            // Vanilla FoodData parity: fast saturation-backed healing requires food >= 20 and
            // heals min(saturation, 6) / 6 HP while spending the same amount as exhaustion.
            // The slow tier stays at food >= 18 for 1.0 HP every 80 ticks. Both respect the
            // RULE_NATURAL_REGENERATION gamerule exactly like a real player.
            boolean naturalRegen = level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION);
            float saturationNow = getFoodSaturation();
            if (naturalRegen && saturationNow > 0.0F && (getHealth() < getMaxHealth()) && getFoodLevel() >= 20 && tickCount % 10 == 0) {
                float healAmount = Math.min(saturationNow, 6.0F);
                heal(healAmount / 6.0F);
                addFoodExhaustion(healAmount);
            } else if (naturalRegen && (getHealth() < getMaxHealth()) && getFoodLevel() >= 18 && tickCount % 80 == 0) {
                heal(1.0F);
                addFoodExhaustion(6.0F);
            } else if (getFoodLevel() == 0 && tickCount % 80 == 0) {
                net.minecraft.world.Difficulty difficulty = level().getDifficulty();
                if (difficulty != net.minecraft.world.Difficulty.PEACEFUL) {
                    float floor = difficulty == net.minecraft.world.Difficulty.EASY ? 10.0F
                            : difficulty == net.minecraft.world.Difficulty.NORMAL ? 1.0F : 0.0F;
                    if (getHealth() > floor) hurt(damageSources().starve(), 1.0F);
                }
            }
            if (tickCount % 20 == 0 && getFoodLevel() <= 6 && !isFriendlyCombat() && getTarget() == null) startEatingFromInventory();
            if (tickCount % 100 == 0 && getTarget() == null && !isFriendlyCombat() && getHealth() < getMaxHealth() * 0.7F) startEating(false);
        }
    }

    public void setOwner(UUID id) {
        UUID previous = this.owner;
        this.owner = id;
        // Ownership change (transfer command, reassignment): drop the old owner's directory
        // entry first, otherwise the previous owner stays registered as owning a live
        // companion until a hasLiveCompanion probe happens to clean it up (review fix).
        if (previous != null && !previous.equals(id) && !level().isClientSide()) {
            com.yaseen.npcmind.state.CompanionRespawnController.unregisterLiveCompanion(getUUID());
        }
        // Every owner->companion assignment (spawn command, automatic respawn, NBT load) is
        // also the birth of a live companion for that owner: keep the lightweight directory
        // used by CompanionRespawnController.hasLiveCompanion in sync without scanning every
        // entity of every dimension.
        if (id != null && !level().isClientSide()) {
            com.yaseen.npcmind.state.CompanionRespawnController.registerLiveCompanion(id, getUUID(),
                    level().dimension().location());
        }
    }

    /**
     * Clears the sprint-exhaustion anchor after a position discontinuity (ender-pearl
     * teleport): otherwise the first aiStep after landing measures the whole jump against
     * the pre-teleport anchor and charges a large exhaustion spike for distance never
     * actually walked.
     */
    public void resetSprintExhaustionAnchor() { lastSprintExhaustionPosition = null; }

    public void setFollowEnabled(boolean enabled) { this.followEnabled = enabled; }

    /** Mirror the owner's sneak input for visible player-like posture and safe slow movement. */
    public void setPlayerLikeCrouching(boolean crouching) {
        if (isShiftKeyDown() != crouching) setShiftKeyDown(crouching);
        if (isPassenger() || getPose() == net.minecraft.world.entity.Pose.SLEEPING
                || getPose() == net.minecraft.world.entity.Pose.DYING
                || getPose() == net.minecraft.world.entity.Pose.FALL_FLYING
                || getPose() == net.minecraft.world.entity.Pose.SWIMMING) return;
        net.minecraft.world.entity.Pose desired = crouching
                ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING;
        if (getPose() != desired && (crouching || getPose() == net.minecraft.world.entity.Pose.CROUCHING)) {
            setPose(desired);
        }
    }

    public boolean isFollowEnabled() { return followEnabled; }

    public SleepController.State sleepState() { return sleepState; }
    public AITickThrottle aiTickThrottle() { return aiTickThrottle; }

    public void setFriendlyCombat(boolean enabled) { this.friendlyCombat = enabled; }
    public boolean isFriendlyCombat() { return friendlyCombat; }

    public UUID getOwner() {
        return owner;
    }

    /**
     * Real player-style hand-off: right-clicking the owned companion with an item
     * actually gives it the held stack.  The previous build only handled dropped
     * ItemEntities, so a normal Minecraft "give him this food/armor" interaction
     * appeared to do nothing.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Shift + empty-hand interaction opens the real inventory menu. Client-side owner state is
        // not trusted here; the server validates ownership before sending the menu packet.
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            if (level().isClientSide()) return InteractionResult.SUCCESS;
            if (owner != null && owner.equals(player.getUUID()) && player instanceof ServerPlayer serverPlayer) {
                net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer,
                        new net.minecraft.world.SimpleMenuProvider((id, playerInventory, ignored) ->
                                new com.yaseen.npcmind.menu.CompanionInventoryMenu(id, playerInventory, this),
                                Component.literal(getDisplayName().getString() + " Inventory")),
                        buffer -> buffer.writeInt(getId()));
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }
        // Item handoff remains an explicit host opt-in; the built-in menu avoids silently consuming items.
        if (!allowDirectItemTransfer()) return InteractionResult.PASS;
        if (player == null || owner == null || !owner.equals(player.getUUID())) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return InteractionResult.PASS;
        // A plain right-click must never swallow what the player is holding (it happened by
        // accident all the time). Giving an item now requires Shift + right-click.
        if (!player.isShiftKeyDown()) {
            if (!level().isClientSide() && hand == InteractionHand.MAIN_HAND) {
                player.displayClientMessage(Component.literal("Shift + right-click to hand me an item"), true);
            }
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) return InteractionResult.sidedSuccess(true);

        int before = held.getCount();
        ItemStack remainder = CompanionItemHandler.accept(this, held, player, true);
        int consumed = before - remainder.getCount();
        if (consumed <= 0) return InteractionResult.PASS;

        player.setItemInHand(hand, remainder);
        return InteractionResult.CONSUME;
    }

    /** Override in a host NPC when Shift-right-click item hand-off is desired. */
    protected boolean allowDirectItemTransfer() { return false; }

    public Container getCompanionInventory() { return inventory; }
    public CombatLearning getCombatLearning() { return combatLearning; }
    public SurvivalGuard.State survivalState() { return survivalState; }
    public CompanionState getCompanionState() { return companionState; }
    public SkillProgression getSkills() { return skills; }
    public TeachingMemory getTeachingMemory() { return teaching; }
    public CraftingKnowledge getCraftingKnowledge() { return craftingKnowledge; }
    public WorldMemory getWorldMemory() { return worldMemory; }
    public LongTermGoal getGoal() { return goal; }
    public ActionPermissions getPermissions() { return permissions; }
    public DecisionJournal getDecisions() { return decisions; }
    public ExperimentJournal getExperiments() { return experiments; }
    public AutonomyState getAutonomy() { return autonomy; }
    public CompanionTeam getTeamState() { return team; }

    public void startSmeltJob(BlockPos pos, String outputId) {
        smeltJobPos = pos.immutable();
        smeltJobOutput = outputId == null ? "" : outputId;
        smeltJobStartedTick = tickCount;
        // Track the owning dimension: a position alone is ambiguous once the companion
        // changes dimension and would let a Nether block be mistaken for the original furnace.
        smeltJobDimension = level().dimension().location();
    }
    public boolean hasActiveSmeltJob() { return smeltJobPos != null; }

    /** Enable short-range drop collection for a bounded number of ticks after a harvest task. */
    public void enablePickup(int ticks) {
        long until = (long) tickCount + Math.max(0, ticks);
        pickupEnabledUntilTick = Math.max(pickupEnabledUntilTick, until);
        experiencePickupEnabledUntilTick = Math.max(experiencePickupEnabledUntilTick, until);
    }

    public void enableExperiencePickup(int ticks) {
        experiencePickupEnabledUntilTick = Math.max(experiencePickupEnabledUntilTick,
                (long) tickCount + Math.max(0, ticks));
    }

    public void disablePickupWindow() { pickupEnabledUntilTick = (long) tickCount - 1L; }
    public void disableExperiencePickupWindow() { experiencePickupEnabledUntilTick = (long) tickCount - 1L; }
    public boolean isPickupEnabled() {
        return com.yaseen.npcmind.Config.AUTO_PICKUP.get() || tickCount <= pickupEnabledUntilTick;
    }
    public boolean isExperiencePickupEnabled() {
        return com.yaseen.npcmind.Config.AUTO_PICKUP.get() || tickCount <= experiencePickupEnabledUntilTick;
    }

    private void collectNearbyDrops() {
        if (level().isClientSide() || tickCount % 2 != 0 || !isAlive()) return;
        boolean pickupEnabled = isPickupEnabled();
        boolean xpPickupEnabled = isExperiencePickupEnabled();
        if (pickupEnabledUntilTick >= 0 && tickCount > pickupEnabledUntilTick) pickupEnabledUntilTick = -1L;
        if (experiencePickupEnabledUntilTick >= 0 && tickCount > experiencePickupEnabledUntilTick) experiencePickupEnabledUntilTick = -1L;
        if (!pickupEnabled && !xpPickupEnabled) return;
        // Compact local pickup radius avoids vacuuming items from across a room. Auto-pickup
        // is configurable; harvesting tasks can temporarily enable it for their own drops.
        // During a deliberate harvest/hunt, allow a slightly wider pickup radius so drops from
        // the target's death position are collected before the companion moves to its next target.
        // Outside that bounded window, keep the normal compact radius to avoid vacuuming loot.
        final double radius = tickCount <= pickupEnabledUntilTick || tickCount <= experiencePickupEnabledUntilTick
                ? 2.5D : 1.25D;
        // ExperienceOrb.playerTouch only targets real Player instances. A PathfinderMob therefore
        // needs an explicit, server-authoritative pickup path and its own persisted XP state.
        if (xpPickupEnabled) {
            for (net.minecraft.world.entity.ExperienceOrb orb : level().getEntitiesOfClass(
                    net.minecraft.world.entity.ExperienceOrb.class,
                    getBoundingBox().inflate(radius, 1.25D, radius),
                    e -> e.isAlive() && e.getValue() > 0 && e.distanceToSqr(this) <= radius * radius)) {
                int value = orb.getValue();
                addExperiencePoints(value);
                orb.discard();
                level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.12F, 1.0F);
            }
        }
        if (!pickupEnabled) return;
        for (net.minecraft.world.entity.item.ItemEntity drop : level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                getBoundingBox().inflate(radius, 1.25D, radius),
                e -> e.isAlive() && !e.getItem().isEmpty()
                        && !e.hasPickUpDelay()
                        // Items the companion itself just dropped as junk must not be vacuumed
                        // straight back up (drop -> pickup -> drop loop).
                        && !InventoryIntelligence.recentlyDropped(this, e.getItem())
                        && e.distanceToSqr(this) <= radius * radius)) {
            ItemStack before = drop.getItem().copy();
            ItemStack remainder = CompanionItemHandler.accept(this, before, null, false);
            int consumed = before.getCount() - remainder.getCount();
            if (consumed <= 0) continue;

            if (remainder.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(remainder);
            }
            inventory.setChanged();
            level().broadcastEntityEvent(this, (byte) 7);
        }
    }

    public String findLookTargetUuid(ServerPlayer player) {
        if (player == null || level().isClientSide()) return null;
        net.minecraft.world.phys.HitResult hit = player.pick(12.0D, 1.0F, false);
        if (hit instanceof net.minecraft.world.phys.EntityHitResult ehr) {
            net.minecraft.world.entity.Entity e = ehr.getEntity();
            if (e != null && e != this && e.isAlive() && e instanceof net.minecraft.world.entity.LivingEntity) return e.getUUID().toString();
        }
        return null;
    }



    @Override
    public Component getDisplayName() {
        // Respect a host mod's Entity#setCustomName call instead of forcing a cached label.
        Component custom = getCustomName();
        return custom != null ? custom : Component.literal(displayName);
    }

    /** Set a persistent, player-visible name from a host mod or local command. */
    public void setDisplayName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) value = CANONICAL_NAME;
        if (value.length() > 64) value = value.substring(0, 64);
        displayName = value;
        setCustomName(Component.literal(value));
        setCustomNameVisible(true);
    }

    public int getBuildHandBackupSlot() { return buildHandBackupSlot; }

    public void setBuildHandBackupSlot(int slot) {
        buildHandBackupSlot = slot >= 0 && slot < inventory.getContainerSize() ? slot : -1;
    }

    /** Restores the prior main-hand item after a build task is cancelled or interrupted by a reload. */
    public void restoreBuildHandBackup() {
        int slot = buildHandBackupSlot;
        if (slot < 0 || slot >= inventory.getContainerSize()) { buildHandBackupSlot = -1; return; }
        ItemStack currentHand = getMainHandItem().copy();
        ItemStack previousHand = inventory.getItem(slot).copy();
        inventory.setItem(slot, currentHand);
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, previousHand);
        inventory.setChanged();
        buildHandBackupSlot = -1;
    }

    public Player getOwnerPlayer() {
        if (owner == null || level().getServer() == null) return null;
        return level().getServer().getPlayerList().getPlayer(owner);
    }

    /** Installs the host mod's chat/UI/narration adapter. Pass null to restore plain messages. */
    public static void setSpeechSink(CompanionSpeech sink) { speechSink = sink; }

    public void say(String text) {
        if (text == null || text.isBlank()) return;
        if (getOwnerPlayer() instanceof ServerPlayer player) {
            String styled = getCompanionState().styleSpeech(text);
            CompanionSpeech sink = speechSink;
            if (sink != null) sink.speak(this, player, styled);
            else player.sendSystemMessage(Component.literal("<" + displayName + "> " + styled));
        } else if (!level().isClientSide()) {
            // Never drop diagnostics silently when the owner is offline: keep them in the
            // server log so problems stay visible instead of disappearing with the chat.
            // Rate-limited: AgentEngine failure loops can emit many messages per second and
            // a busy server log is both an I/O cost and a noise cost for operators.
            long now = level().getGameTime();
            Long last = LAST_OFFLINE_LOG.get(getUUID());
            if (last == null || now - last >= OFFLINE_LOG_INTERVAL_TICKS) {
                LAST_OFFLINE_LOG.put(getUUID(), now);
                com.yaseen.npcmind.NPCMind.LOG.info("[NPCMind] {} (owner offline): {}", displayName, text);
            }
        }
    }

    /** Called from CompanionCleanup.purge so offline log rate limits do not leak per UUID. */
    public static void clearOfflineLogRateLimit(java.util.UUID id) { if (id != null) LAST_OFFLINE_LOG.remove(id); }

    public void ask(String message) {
        if (level().isClientSide()) return;
        message = com.yaseen.npcmind.ai.InputNormalizer.normalize(message);
        com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] companion.ask owner={} msg={}", getOwnerPlayer() != null, message);
        if (getOwnerPlayer() instanceof ServerPlayer player) {
            com.yaseen.npcmind.debug.Trace.log(getUUID(), "CHAT", "owner message = \"" + message + "\"");
            AuditLog.chat(getUUID(), "OWNER_TO_COMPANION", message);
            player.displayClientMessage(Component.literal("Thinking..."), true);
            String learned = teaching.resolve(message);
            com.yaseen.npcmind.debug.Trace.log("CHAT", "-> routed to local deterministic command engine; learnedShortcut={}", learned != null);
            AgentEngine.runAsync(this, player, learned == null ? message : learned);
        }
    }

    private net.minecraft.core.BlockPos auditLastMovePos;

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && enderPearlCooldownTicks > 0) enderPearlCooldownTicks--;
        if (!level().isClientSide()) {
            net.minecraft.core.BlockPos now = blockPosition();
            if (auditLastMovePos == null) auditLastMovePos = now;
            // Movement used to append two file lines per crossed block boundary (global + per-companion
            // log) on the main server thread - continuous I/O pressure for zero diagnostic value.
            // Sample at most every 100 ticks (5 s) instead.
            else if (!auditLastMovePos.equals(now) && tickCount % 100 == 0) {
                AuditLog.event("MOVEMENT", "MOVE", getUUID(),
                        "from=" + auditLastMovePos.getX() + "," + auditLastMovePos.getY() + "," + auditLastMovePos.getZ()
                                + " | to=" + now.getX() + "," + now.getY() + "," + now.getZ()
                                + " | tick=" + tickCount);
                auditLastMovePos = now;
            }
        }
        if (animationActionTicks > 0) {
            animationActionTicks--;
            if (animationActionTicks == 0) this.entityData.set(ANIMATION_ACTION, (byte) 0);
        }
        // Universal navigation jump assist: whenever the companion is actually moving along a path
        // (follow, mining, exploration, combat...) and bumps into a 1-block step, make it jump.
        // Previously only the follow goal did this, so during tasks it "walked fast but never
        // jumped" and got stuck on single blocks.
        if (!level().isClientSide() && onGround() && horizontalCollision && !getNavigation().isDone()
                && tickCount - lastAutoJumpExhaustTick > 10) {
            // Charge per actual jump attempt, at vanilla-like exhaustion values; never per tick
            // while pressed against the same wall.
            addFoodExhaustion(isSprinting() ? 0.2F : 0.05F);
            lastAutoJumpExhaustTick = tickCount;
            getJumpControl().jump();
        }
        collectNearbyDrops();
        // A craft can require several physical acquisition steps. Resume it after the real task,
        // combat hunt, drop pickup or furnace job has finished rather than ending at STARTED.
        if (!level().isClientSide()) com.yaseen.npcmind.tool.impl.CraftItemTool.tickPending(this);
        tickSmeltJob();
        com.yaseen.npcmind.advanced.InventoryIntelligence.tick(this);
        // One authoritative lifecycle owner. Controllers are no longer chained
        // ad-hoc from the entity class; CompanionKernel applies their priority and prevents a
        // background/autonomous controller from stealing navigation from an active work task.
        // The pearl wind-up owns the action slot for its 7 ticks, but survival reflexes keep
        // running the whole time: an escape pearl thrown while standing in lava must never
        // pause lava/fire/drowning checks until the wind-up completes.
        if (!com.yaseen.npcmind.tool.impl.ThrowEnderPearlTool.tickPending(this)) {
            CompanionKernel.tick(this);
        } else {
            com.yaseen.npcmind.advanced.SurvivalGuard.tick(this, this.survivalState());
        }
        companionState.tick(this);
        skills.applyAttributes(this);
        if (!level().isClientSide() && tickCount % 40 == 0) {
            String notice = companionState.autonomyMessage(this);
            if (notice != null) say(notice);
        }
    }

    private void tickSmeltJob() {
        if (level().isClientSide() || smeltJobPos == null) return;
        // A furnace job is valid in its own dimension only. After a dimension change the
        // stored position may collide with an unrelated Nether/End block, so release the job.
        if (smeltJobDimension == null || !smeltJobDimension.equals(level().dimension().location())) {
            clearSmeltJob();
            return;
        }
        // Do not discard a valid job merely because its chunk is temporarily unloaded.
        if (!level().isLoaded(smeltJobPos)) return;
        var blockEntity = level().getBlockEntity(smeltJobPos);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            // The chunk is loaded and there is no furnace at the stored position: it was broken
            // or replaced. Release the lock so later crafting/smelting jobs can proceed.
            clearSmeltJob();
            return;
        }
        if (tickCount - smeltJobStartedTick < 5) return;
        ItemStack out = furnace.getItem(2);
        if (out.isEmpty()) return;
        if (!smeltJobOutput.isBlank() && !out.getItem().builtInRegistryHolder().key().location().toString().equals(smeltJobOutput)) {
            say("That furnace isn't producing what I expected.");
            clearSmeltJob();
            return;
        }
        ItemStack moving = out.copy();
        ItemStack left = com.yaseen.npcmind.crafting.InventoryOps.addAndReturnRemainder(inventory, moving);
        int moved = moving.getCount() - left.getCount();
        if (moved > 0) {
            out.shrink(moved);
            if (out.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged(); inventory.setChanged();
            say("Smelting done — collected the output.");
            clearSmeltJob();
        }
    }

    private void clearSmeltJob() {
        smeltJobPos = null;
        smeltJobOutput = "";
        smeltJobStartedTick = -1L;
        smeltJobDimension = null;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide()) {
            if (com.yaseen.npcmind.Config.AUTO_RESPAWN.get() && owner != null
                    && level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                // Persist a respawn request before the entity is removed. Contents still drop on
                // death; respawning never duplicates the companion's inventory.
                com.yaseen.npcmind.state.CompanionWorldData data =
                        com.yaseen.npcmind.state.CompanionWorldData.get(serverLevel);
                data.saveRespawnPoint(owner, serverLevel.dimension().location(),
                        blockPosition().getX(), blockPosition().getY(), blockPosition().getZ());
                net.minecraft.nbt.CompoundTag skillSnapshot = new net.minecraft.nbt.CompoundTag();
                skills.save(skillSnapshot);
                data.saveDeadCompanionId(owner, getUUID());
                data.saveSkills(owner, getUUID(), skillSnapshot);
                data.saveExperience(owner, getExperienceLevel(), getExperienceProgress(), getTotalExperiencePoints());
                // Name + permission snapshot survive the UUID change so respawn keeps identity and safety gates.
                data.saveCustomName(owner, getCustomName() == null ? displayName : getCustomName().getString());
                net.minecraft.nbt.CompoundTag permSnapshot = new net.minecraft.nbt.CompoundTag();
                permissions.save(permSnapshot);
                data.savePermissionSnapshot(owner, permSnapshot);
                // Tool-skill statistics live under the companion's own UUID, which dies with
                // the entity. Mirror them into the owner's record so respawn can seed the new
                // companion instead of starting every tool's ok/fail history from zero.
                data.saveToolSkillsSnapshot(owner, data.getToolSkills(getUUID()));
                data.markPending(owner, com.yaseen.npcmind.Config.RESPAWN_DELAY_SECONDS.get() * 1000L);
            }
            // A build task may temporarily equip a block stack and keep the previous hand item in
            // an inventory slot. Restore that swap before dropping contents so death cannot drop
            // a duplicated block stack or lose the previously selected item.
            TaskManager.cancelCurrentTask(this);
            // Drop the real inventory and worn equipment at the exact death position.
            // The respawn system must never restore these items after death.
            dropCompanionContents();
        }
        // One purge releases every static per-companion map (tasks, navigation lease, combat,
        // exploration, travel, planner future, memory, journal, skills). Before this, each map
        // kept a UUID entry forever and a respawned NPC inherited stale state.
        com.yaseen.npcmind.core.CompanionCleanup.purge(getUUID());
        super.die(source);
    }

    private void dropCompanionContents() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack.copy());
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.HAND
                    && slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) continue;
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack.copy());
                setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
        tag.putBoolean("FollowEnabled", followEnabled);
        tag.putBoolean("FriendlyCombat", friendlyCombat);
        tag.putInt("FoodLevel", getFoodLevel());
        tag.putFloat("FoodSaturation", getFoodSaturation());
        tag.putFloat("FoodExhaustion", foodExhaustion);
        tag.putInt("XpLevel", getExperienceLevel());
        tag.putFloat("XpProgress", getExperienceProgress());
        tag.putInt("XpTotal", getTotalExperiencePoints());
        tag.putInt("SelectedSlot", getSelectedSlot());
        Component currentName = getCustomName();
        tag.putString("DisplayName", currentName == null ? displayName : currentName.getString());
        ListTag equipment = new ListTag();
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack stack = getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("Slot", slot.getName());
            entry.put("Item", stack.save(new CompoundTag()));
            equipment.add(entry);
        }
        tag.put("Equipment", equipment);
        ListTag list = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slot = new CompoundTag();
                slot.putInt("Slot", i);
                slot.put("Item", stack.save(new CompoundTag()));
                list.add(slot);
            }
        }
        tag.put("Inventory", list);
        if (buildHandBackupSlot >= 0) tag.putInt("BuildHandBackupSlot", buildHandBackupSlot);
        if (smeltJobPos != null) {
            tag.putLong("SmeltJobPos", smeltJobPos.asLong());
            tag.putString("SmeltJobOutput", smeltJobOutput);
            tag.putLong("SmeltJobStarted", smeltJobStartedTick);
            if (smeltJobDimension != null) tag.putString("SmeltJobDimension", smeltJobDimension.toString());
        }
        tag.putInt("EnderPearlCooldown", enderPearlCooldownTicks);
        tag.putBoolean("ShieldDisplacedByTotem", shieldDisplacedByTotem);
        if (!displacedShield.isEmpty()) tag.put("DisplacedShield", displacedShield.save(new CompoundTag()));
        PlanExecutionController.save(getUUID(), tag);
        combatLearning.save(tag);
        companionState.save(tag);
        skills.save(tag);
        teaching.save(tag);
        craftingKnowledge.save(tag);
        worldMemory.save(tag);
        goal.save(tag);
        permissions.save(tag);
        decisions.save(tag);
        experiments.save(tag);
        autonomy.save(tag);
        team.save(tag);
        // Autonomy toggles previously lived only in static maps, so exploration/bodyguard
        // silently switched off after a chunk reload or server restart.
        tag.putBoolean("ExplorationActive", com.yaseen.npcmind.advanced.ExplorationController.active(this));
        tag.putBoolean("BodyguardEnabled", com.yaseen.npcmind.advanced.BodyguardController.enabled(this));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Route through setOwner instead of writing the field directly: on the server side
        // this registers the loaded companion in the live-companion directory, so a pending
        // automatic respawn or spawn guard cannot create a duplicate for the same owner
        // after a server restart or chunk reload (review fix #2).
        if (tag.hasUUID(OWNER_TAG)) setOwner(tag.getUUID(OWNER_TAG));
        followEnabled = tag.contains("FollowEnabled") && tag.getBoolean("FollowEnabled");
        friendlyCombat = tag.getBoolean("FriendlyCombat");
        if (tag.contains("FoodLevel", 3)) setFoodLevel(tag.getInt("FoodLevel"));
        if (tag.contains("FoodSaturation", 5)) setFoodSaturation(tag.getFloat("FoodSaturation"));
        if (tag.contains("FoodExhaustion", 5)) foodExhaustion = Math.max(0.0F, tag.getFloat("FoodExhaustion"));
        if (tag.contains("XpLevel", 3) || tag.contains("XpProgress", 5) || tag.contains("XpTotal", 3)) {
            setExperienceState(tag.getInt("XpLevel"), tag.getFloat("XpProgress"), tag.getInt("XpTotal"));
        }
        if (tag.contains("SelectedSlot", 3)) setSelectedSlot(tag.getInt("SelectedSlot"));
        // Prefer the persisted display name, then a custom name restored by the base Entity.
        // Older saves without either value migrate to Steve, but custom names are no longer lost.
        String savedName = tag.contains("DisplayName", 8) ? tag.getString("DisplayName") : "";
        if (savedName.isBlank()) {
            Component restored = getCustomName();
            savedName = restored == null ? CANONICAL_NAME : restored.getString();
        }
        setDisplayName(savedName);
        shieldDisplacedByTotem = tag.getBoolean("ShieldDisplacedByTotem");
        displacedShield = tag.contains("DisplacedShield", 10) ? ItemStack.of(tag.getCompound("DisplacedShield")) : ItemStack.EMPTY;
        PlanExecutionController.load(getUUID(), tag);
        combatLearning.load(tag);
        companionState.load(tag);
        skills.load(tag);
        teaching.load(tag);
        craftingKnowledge.load(tag);
        worldMemory.load(tag);
        goal.load(tag);
        permissions.load(tag);
        decisions.load(tag);
        experiments.load(tag);
        autonomy.load(tag);
        team.load(tag);
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            setItemSlot(slot, ItemStack.EMPTY);
        }
        if (tag.contains("Equipment", 9)) {
            ListTag equipment = tag.getList("Equipment", 10);
            for (int i = 0; i < equipment.size(); i++) {
                CompoundTag entry = equipment.getCompound(i);
                String name = entry.getString("Slot");
                net.minecraft.world.entity.EquipmentSlot slot = null;
                for (net.minecraft.world.entity.EquipmentSlot candidate : net.minecraft.world.entity.EquipmentSlot.values()) {
                    if (candidate.getName().equals(name)) { slot = candidate; break; }
                }
                if (slot != null && entry.contains("Item", 10)) setItemSlot(slot, ItemStack.of(entry.getCompound("Item")));
            }
        }
        inventory.clearContent();
        if (tag.contains("SmeltJobPos", 4)) {
            smeltJobPos = BlockPos.of(tag.getLong("SmeltJobPos"));
            smeltJobOutput = tag.getString("SmeltJobOutput");
            smeltJobStartedTick = tag.getLong("SmeltJobStarted");
            smeltJobDimension = tag.contains("SmeltJobDimension", 8)
                    ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("SmeltJobDimension")) : null;
        }
        if (tag.contains("EnderPearlCooldown", 3)) enderPearlCooldownTicks = tag.getInt("EnderPearlCooldown");
        if (tag.contains("Inventory", 9)) {
            ListTag list = tag.getList("Inventory", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag slot = list.getCompound(i);
                int index = slot.getInt("Slot");
                if (index >= 0 && index < inventory.getContainerSize()) inventory.setItem(index, ItemStack.of(slot.getCompound("Item")));
            }
        }
        buildHandBackupSlot = tag.contains("BuildHandBackupSlot", 3) ? tag.getInt("BuildHandBackupSlot") : -1;
        // TaskManager's in-flight task queue is transient. If a save occurred while construction
        // had temporarily equipped blocks, restore the hand here so the pre-build item is not lost.
        if (buildHandBackupSlot >= 0) restoreBuildHandBackup();
    }

    public static class FollowOwnerGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final CompanionEntity mob;
        private final double speed;
        private final float min;
        private final float max;

        public FollowOwnerGoal(CompanionEntity mob, double speed, float min, float max) {
            this.mob = mob;
            this.speed = speed;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean canUse() {
            Player p = mob.getOwnerPlayer();
            return mob.followEnabled && !TaskManager.isActive(mob) && mob.getTarget() == null
                    && p != null && p.level() == mob.level() && mob.distanceTo(p) > min;
        }

        @Override
        public boolean canContinueToUse() {
            Player p = mob.getOwnerPlayer();
            // Follow is intentionally same-dimension. Cross-dimension ownership transfer needs
            // an explicit host/server travel policy; distance checks across levels are meaningless.
            return mob.followEnabled && !TaskManager.isActive(mob) && mob.getTarget() == null
                    && p != null && p.level() == mob.level() && mob.distanceTo(p) > min;
        }

        private int farTeleportCooldown = 0;
        private int followRefreshTick = 0;
        private int followRetryTick = 0;
        private BlockPos followPathTarget;
        private double lastFollowPathSpeed = Double.NaN;
        private boolean followingSprint;

        @Override
        public void tick() {
            Player p = mob.getOwnerPlayer();
            if (p == null || p.level() != mob.level()) return;
            if (farTeleportCooldown > 0) farTeleportCooldown--;
            double d2 = mob.distanceToSqr(p);
            // A companion cannot naturally path through an unloaded-chunk gap. If it falls more
            // than 48 blocks behind, recover near its owner in the same dimension, using only
            // already-loaded chunks and a collision-checked standing location.
            if (d2 > 2304.0D && farTeleportCooldown == 0) {
                if (tryFarTeleport(p, d2)) {
                    farTeleportCooldown = 100;
                    return;
                }
                // A blocked or unsafe owner position should not trigger 48 collision checks every tick.
                farTeleportCooldown = 40;
            }
            // Hysteresis prevents sprint toggling when the owner hovers around one distance
            // threshold. Sprinting changes speed only; jumping is reserved for a real collision.
            if (d2 > 49.0D) followingSprint = true;
            else if (d2 < 25.0D) followingSprint = false;
            boolean crouching = p.isShiftKeyDown();
            mob.setPlayerLikeCrouching(crouching);
            mob.getLookControl().setLookAt(p, 30.0F, 30.0F);
            boolean sprint = followingSprint && !crouching && mob.getFoodLevel() > 6 && !mob.isUsingItem();
            mob.setSprinting(sprint);
            double followSpeed = crouching ? Math.max(0.25D, speed * 0.30D)
                    : sprint ? Math.max(1.30D, speed + 0.20D) : speed;
            BlockPos ownerPos = p.blockPosition();
            boolean targetShifted = followPathTarget == null || followPathTarget.distSqr(ownerPos) >= 9.0D;
            boolean speedChanged = !Double.isFinite(lastFollowPathSpeed)
                    || Math.abs(lastFollowPathSpeed - followSpeed) >= 0.06D;
            boolean refreshDue = mob.tickCount >= followRefreshTick;
            boolean pathFinished = mob.getNavigation().isDone() && mob.tickCount >= followRetryTick;
            if (mob.tickCount >= followRetryTick && (targetShifted || speedChanged || refreshDue || pathFinished)) {
                boolean requested = NavigationArbiter.request(mob, ownerPos, "follow", 300, followSpeed);
                followRetryTick = mob.tickCount + (requested ? 10 : 5);
                if (requested) {
                    followPathTarget = ownerPos.immutable();
                    lastFollowPathSpeed = followSpeed;
                    followRefreshTick = mob.tickCount + 10;
                }
            }
        }

        @Override
        public void stop() {
            mob.setPlayerLikeCrouching(false);
            mob.setSprinting(false);
            followingSprint = false;
            followPathTarget = null;
            lastFollowPathSpeed = Double.NaN;
        }

        private boolean isStandableAndClear(net.minecraft.server.level.ServerLevel level,
                net.minecraft.core.BlockPos feet, CompanionEntity mob) {
            net.minecraft.core.BlockPos head = feet.above();
            net.minecraft.core.BlockPos floor = feet.below();
            if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(head)
                    || !level.getWorldBorder().isWithinBounds(feet)
                    || !level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) return false;
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()
                    || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return false;
            double x = feet.getX() + 0.5D;
            double y = feet.getY();
            double z = feet.getZ() + 0.5D;
            net.minecraft.world.phys.AABB box = mob.getDimensions(
                    net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(x, y, z);
            return level.noCollision(mob, box);
        }

        private void teleportTo(Player owner, net.minecraft.core.BlockPos feet,
                double previousDistanceSquared) {
            double x = feet.getX() + 0.5D;
            double y = feet.getY();
            double z = feet.getZ() + 0.5D;
            NavigationArbiter.release(mob, "follow");
            mob.getNavigation().stop();
            mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mob.setPos(x, y, z);
            mob.setYRot(owner.getYRot());
            mob.setYHeadRot(owner.getYRot());
            mob.setSprinting(false);
            // Position discontinuity: re-anchor sprint exhaustion exactly like the ender-pearl
            // path, otherwise the first aiStep measures the whole teleport as sprint distance
            // and charges an unearned exhaustion spike (review fix #4).
            mob.resetSprintExhaustionAnchor();
            com.yaseen.npcmind.debug.AuditLog.event("FOLLOW_RECOVERY", "TELEPORT", mob.getUUID(),
                    "owner=" + owner.getUUID() + " previousDistanceSquared=" + (long) previousDistanceSquared
                            + " destination=" + feet);
        }

        private boolean tryFarTeleport(Player owner, double previousDistanceSquared) {
            if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel level)
                    || owner.level() != level) return false;
            net.minecraft.core.BlockPos origin = owner.blockPosition();
            java.util.List<net.minecraft.core.BlockPos> nearbyColumns = new java.util.ArrayList<>(48);
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                    origin.offset(-3, 0, -3), origin.offset(3, 0, 3))) {
                int dx = pos.getX() - origin.getX();
                int dz = pos.getZ() - origin.getZ();
                if (dx == 0 && dz == 0) continue;
                nearbyColumns.add(new net.minecraft.core.BlockPos(pos.getX(), origin.getY(), pos.getZ()));
            }
            nearbyColumns.sort(java.util.Comparator.comparingInt(
                    pos -> Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ()))));

            // Test a compact local set before relying on a surface-height fallback; this also works in caves.
            int[] yOffsets = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6};
            for (net.minecraft.core.BlockPos column : nearbyColumns) {
                if (!level.isInWorldBounds(column) || !level.getWorldBorder().isWithinBounds(column)
                        || !level.hasChunkAt(column)) continue;
                for (int dy : yOffsets) {
                    net.minecraft.core.BlockPos candidate = column.offset(0, dy, 0);
                    if (isStandableAndClear(level, candidate, mob)) {
                        teleportTo(owner, candidate, previousDistanceSquared);
                        return true;
                    }
                }
                net.minecraft.core.BlockPos surface = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column);
                if (isStandableAndClear(level, surface, mob)) {
                    teleportTo(owner, surface, previousDistanceSquared);
                    return true;
                }
            }
            return false;
        }
    }
}
