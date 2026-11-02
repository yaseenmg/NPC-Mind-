#!/usr/bin/env python3
"""Static regression checks for fixes enumerated in the NPC Mind source audit.

This validates expected source wiring only. It is not a Java compile or Minecraft simulation.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/yaseen/npcmind"
errors: list[str] = []
checks = 0

def require(path: str, patterns: tuple[str, ...], label: str) -> None:
    global checks
    file = ROOT / path
    if not file.exists():
        errors.append(f"{label}: missing file {path}")
        return
    text = file.read_text(encoding="utf-8")
    for pattern in patterns:
        checks += 1
        if not re.search(pattern, text, re.S):
            errors.append(f"{label}: expected source marker not found: {pattern}")

def require_absent(path: str, label: str) -> None:
    """Library-packaging gate: a path that must NOT exist in a developer-library release."""
    global checks
    checks += 1
    if (ROOT / path).exists():
        errors.append(f"{label}: file must not exist in a library release: {path}")

def forbid(path: str, patterns: tuple[str, ...], label: str) -> None:
    """Assert that none of the patterns appear in an existing file."""
    global checks
    file = ROOT / path
    if not file.exists():
        errors.append(f"{label}: missing file {path}")
        return
    text = file.read_text(encoding="utf-8")
    for pattern in patterns:
        checks += 1
        if re.search(pattern, text, re.S):
            errors.append(f"{label}: forbidden pattern found: {pattern}")

require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r"ItemStack\.isSameItemSameTags\(result,\s*consumedInput\)",
    r"public\s+void\s+setDisplayName\(String\s+name\)",
    r'tag\.putString\("DisplayName"',
    r'BuildHandBackupSlot',
    r'if\s*\(!isUsingItem\(\)\s*\|\|\s*amount\s*<\s*3\.0F\)\s*return',
    r'Config\.AUTO_PICKUP\.get\(\)',
    r'Config\.AUTO_RESPAWN\.get\(\)',
    r'tryFarTeleport\(Player\s+owner,\s*double\s+previousDistanceSquared\)',
), "CompanionEntity")
require("src/main/java/com/yaseen/npcmind/client/ClientRendering.java", (
    r'EntityRenderersEvent\.RegisterRenderers',
    r'event\.registerEntityRenderer\(ModEntities\.COMPANION\.get\(\),\s*CompanionRenderer::new\)',
), "client renderer")
require("src/main/java/com/yaseen/npcmind/menu/CompanionInventoryMenu.java", (
    r'public\s+boolean\s+stillValid\(Player\s+player\)',
    r'companion\.getOwner\(\).*player\.getUUID\(\)',
), "inventory owner validation")
require("src/main/java/com/yaseen/npcmind/agent/AgentMemory.java", (
    r'Config\.MEMORY_LIMIT\.get\(\)',
), "memory bound")
require("src/main/java/com/yaseen/npcmind/agent/AgentEngine.java", (
    r'result\.get\(30,\s*TimeUnit\.SECONDS\)',
    r'return\s+"PENDING execution_started_waiting_for_result',
    r'Delayed result from',
    r'server_queue_timeout_cancelled_before_execution',
), "safe timeout reporting")
require("src/main/java/com/yaseen/npcmind/tool/impl/GatherWoodTool.java", (
    r'for\(int\s+dx=-8;dx<=8;dx\+\+\)',
    r'for\(int\s+dy=-4;dy<=4;dy\+\+\)',
), "bounded wood scan")
require("src/main/java/com/yaseen/npcmind/tool/impl/GatherBlockTool.java", (
    r'for\(int\s+dx=-8;\s*dx<=8;\s*dx\+\+\)',
    r'for\(int\s+dy=-4;\s*dy<=4;\s*dy\+\+\)',
), "bounded block scan")
require("src/main/java/com/yaseen/npcmind/tool/impl/CraftItemTool.java", (
    r'RECIPE_CACHE_TTL_MS\s*=\s*60_000L',
    r'clearRecipeCache\(\)',
    r'RECIPE_CACHE\.computeIfAbsent',
), "crafting recipe cache")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionMeleeGoal.java", (
    r'if\s*\(hit\.getType\(\)\s*!=\s*net\.minecraft\.world\.phys\.HitResult\.Type\.BLOCK\)\s*return\s+true',
), "melee partial line of sight")
require("src/main/java/com/yaseen/npcmind/core/CompanionKernel.java", (
    r'owner\.getBoundingBox\(\)\.inflate\(16\.0D\)',
    r'owner\.level\(\) == c\.level\(\)',
), "near-owner threat radius and dimension validation")
require("src/main/java/com/yaseen/npcmind/ForgeIntegration.java", (
    r'raw\.length\(\)\s*>\s*prefix\.length\(\).*Character\.isWhitespace',
    r'Commands\.literal\("inventory"\)',
    r'Commands\.literal\("name"\)',
    r'Commands\.literal\("transfer"\)',
    r'CompanionRespawnController\.tick\(event\.getServer\(\)\)',
    r'findSafeSpawn\(level,\s*player\.blockPosition\(\),\s*companion\)',
    r'isSafeSpawnPosition\(ServerLevel level,\s*BlockPos feet,\s*CompanionEntity companion\)',
    r'CompanionRespawnController\.hasPendingRespawn',
    r'EmergencyController\.clear\(oldOwner\)',
    r'ThreatMonitor\.clear\(oldOwner\)',
    r'AutonomyController\.clear\(companion\.getUUID\(\)\)',
), "safe spawn and ownership hand-off")
require("src/main/java/com/yaseen/npcmind/state/CompanionRespawnController.java", (
    r'Heightmap\.Types\.MOTION_BLOCKING',
    r'level\.noCollision\(companion,\s*box\)',
    r'hasLiveCompanion',
    r'hasPendingRespawn\(MinecraftServer server, java\.util\.UUID owner\)',
), "safe respawn")
require("src/main/java/com/yaseen/npcmind/client/CompanionRenderer.java", (
    r'DefaultPlayerSkin\.getSkinModelName\(entity\.getUUID\(\)\)',
    r'ModelLayers\.PLAYER_SLIM',
    r'new\s+ArrowLayer<>\(context,\s*this\)',
    r'HumanoidModel\.ArmPose\.BOW_AND_ARROW',
    r'HumanoidModel\.ArmPose\.BLOCK',
), "player skin geometry and item-use poses")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionRangedGoal.java", (
    r'Enchantments\.INFINITY_ARROWS',
    r'ammo\.is\(Items\.ARROW\)',
    r'AbstractArrow\.Pickup\.CREATIVE_ONLY',
), "vanilla infinity bow behavior")
require("src/main/java/com/yaseen/npcmind/advanced/PlayerCombatParity.java", (
    r'weapon\.getItem\(\)\s+instanceof\s+net\.minecraft\.world\.item\.DiggerItem',
    r'weapon\.hurtAndBreak\(durabilityCost',
), "weapon durability parity")
require("src/main/java/com/yaseen/npcmind/rules/PlayerActionRules.java", (
    r'public\s+static\s+boolean\s+canReachPoint\(LivingEntity',
    r'ClipContext\.Block\.COLLIDER',
    r'public\s+static\s+boolean\s+canReachEntity\(LivingEntity',
    r'Double\.isFinite\(point\.x\)',
), "entity-agnostic player action rules")
require("src/main/java/com/yaseen/npcmind/advanced/PlayerCombatParity.java", (
    r'!hasClearAttackLine\(attacker,\s*target\)',
    r'PlayerActionRules\.canReachEntity\(attacker,\s*target,\s*3\.0D\)',
), "central combat wall guard")
require("src/main/java/com/yaseen/npcmind/task/TaskManager.java", (
    r'BlockTags\.BASE_STONE_OVERWORLD',
    r'BlockTags\.BASE_STONE_NETHER',
    r'BlockTags\.DIRT',
    r'BlockTags\.SAND',
    r'Do not generalize to every solid block',
    r'NavigationArbiter\.request\(c, alternateStand, "task", 500, 1\.15D\)',
), "natural access and alternate stand navigation")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionMeleeGoal.java", (
    r'if\s*\(mob\.horizontalCollision\s*&&\s*mob\.onGround\(\)\)\s*mob\.getJumpControl\(\)\.jump\(\)',
), "collision-triggered melee jumping")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionRangedGoal.java", (
    r'if\s*\(mob\.horizontalCollision\s*&&\s*mob\.onGround\(\)\)\s*mob\.getJumpControl\(\)\.jump\(\)',
), "collision-triggered ranged jumping")
require("src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java", (
    r'canReachPoint\(@NotNull LivingEntity actor, @NotNull Vec3 point, double reach\)',
    r'canReachEntity\(@NotNull LivingEntity actor, @NotNull LivingEntity target, double reach\)',
), "generic entity API")
require("src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java", (
    r"registerEntityIntent", r"resolveEntityCommand", r"submitEntityCommand",
    r"controller == null \? \"autonomous\"", r"must_run_on_server_thread",
), "generic entity command framework")
require("src/main/java/com/yaseen/npcmind/api/EntityIntentRegistry.java", (
    r"ToolContract\.validateSchema", r"Comparator\.comparingInt", r"Normalizer\.normalize",
), "validated generic entity intent routing")
require("src/test/java/com/yaseen/npcmind/api/EntityIntentRegistryTest.java", (
    r"hostCanRegisterGenericToolAndResolveEnglishAndArabicAliases",
    r"entityIntentRegistrationRejectsInvalidArgumentsAndUnknownTools",
), "generic entity framework tests")
require("docs/GENERIC_NPC_FRAMEWORK.md", (r"Generic host NPC", r"planner", r"authorization"), "generic NPC developer guide")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r'EntityDataAccessor<Boolean> EATING',
    r'entityData\.define\(EATING, false\)',
    r'entityData\.set\(EATING, true\)',
    r'public boolean isEating\(\) \{ return this\.entityData\.get\(EATING\); \}',
    r'int\[\] yOffsets = \{0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6\}',
    r'isStandableAndClear\(level, candidate, mob\)',
), "client eating sync and cave teleport")
require("src/main/java/com/yaseen/npcmind/tool/impl/StructureSearchTool.java", (
    r'unsupported_structure=trial_chambers',
    r'Minecraft 1\.20\.1 does not register Trial Chambers',
    r'\(\"trial_chambers\"\.equals\(id\) \|\| \"trial\"\.equals\(id\)\)',
), "1.20.1 structure compatibility")
require("src/main/java/com/yaseen/npcmind/task/TaskManager.java", (
    r'c\.addFoodExhaustion\(0\.005F\)',
), "mining food exhaustion")
require("src/main/java/com/yaseen/npcmind/advanced/PlayerCombatParity.java", (
    r'companion\.addFoodExhaustion\(0\.1F\)',
), "combat food exhaustion")
require("build.gradle", (
    r"version\s*=\s*project\.mod_version",
    r"maven\.minecraftforge\.net",
    r'junit-jupiter',
    r"withJavadocJar\(\)",
    r"no mod entrypoint, no mods\.toml",
), "library build metadata and test dependency")
require_absent("src/main/resources/META-INF/mods.toml", "library ships no mod metadata (v1.2.0)")
require_absent("src/main/resources/pack.mcmeta", "library ships no resource-pack metadata (v1.2.0)")
require_absent("THIRD_PARTY_NOTICES.md", "removed root third-party notices file")
require("gradle/wrapper/LICENSE-APACHE-2.0.txt", (
    r"Apache License, Version 2\.0",
    r"TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
), "preserved Gradle Wrapper license text")
require("src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java", (r'API_VERSION\s*=\s*"1\.3\.1"',), "API version")

require("src/main/java/com/yaseen/npcmind/entity/PlayerLikeInteractionController.java", (
    r"new FakePlayer\(level, profile\)",
    r"if \(speed <= 0\.0F\) return 360",
), "isolated interaction context and wrong-tool timing")
require("src/main/java/com/yaseen/npcmind/autonomy/NavigationArbiter.java", (
    r"return request\(c, target\.blockPosition\(\), owner, priority, speed\)",
    r"createPath\(target, 0, localRange\)",
    r"int doubledError = error \* 2",
    r"horizontal > MAX_DIRECT_HORIZONTAL_DISTANCE",
), "bounded grid-walk corridor preflight and entity navigation")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r"followPathTarget\.distSqr\(ownerPos\) >= 9\.0D",
    r"if \(d2 > 49\.0D\) followingSprint = true",
    r"onGround\(\) && horizontalCollision && !getNavigation\(\)\.isDone\(\)",
), "throttled following and collision-only jumping")
require("src/main/java/com/yaseen/npcmind/advanced/CombatMovement.java", (
    r"boolean accepted = NavigationArbiter\.request",
    r"if \(accepted\)",
    r"nextRouteAttemptTick = mob\.tickCount \+ \(distanceSqr > 576\.0D \? 14 : 9\)",
    r"requestedDestination = destination\.immutable\(\)",
), "independent rate-limited combat navigation and lease acceptance")
require("src/main/java/com/yaseen/npcmind/advanced/StuckRecovery.java", (
    r"c\.setDeltaMovement\(dx / len \* STEP_SPEED, motion\.y, dz / len \* STEP_SPEED\)",
), "single impulse stuck recovery")
require("src/main/java/com/yaseen/npcmind/task/TaskManager.java", (
    r"ResourceLedger\.owns\(c, candidate, 1\)",
    r"hasClearLineFromStand\(level, c, alternateStand, target\)",
), "mining reservation and early obstruction validation")
require("src/main/java/com/yaseen/npcmind/rules/PlayerActionRules.java", (
    r"distanceToSqr\(hit\.getLocation\(\)\) \+ 0\.01D >= distanceSqr",
), "line-of-sight boundary tolerance")
require("src/main/java/com/yaseen/npcmind/advanced/PlayerCombatParity.java", (
    r"target\.setSecondsOnFire\(fireAspect \* 4\)",
), "fire aspect duration parity")
require("src/main/java/com/yaseen/npcmind/agent/AgentEngine.java", (
    r't\.split\("\(\?<\=\[\.\!\?؟\]\)\\\\s\+"\)',
), "Arabic sentence punctuation")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionMeleeGoal.java", (
    r"strafeTimer = 40 \+ mob\.getRandom\(\)\.nextInt\(41\)",
), "reduced strafe jitter")

structure_search = JAVA / "tool/impl/StructureSearchTool.java"
if structure_search.exists():
    structure_text = structure_search.read_text(encoding="utf-8")
    checks += 1
    if re.search(r'case\s+"trial_chambers"', structure_text):
        errors.append("StructureSearchTool: unsupported Trial Chambers normalization alias remains")

if (JAVA / "entity/CompanionEntity.java").exists():
    entity = (JAVA / "entity/CompanionEntity.java").read_text(encoding="utf-8")
    checks += 1
    if "PICKUP_READY_AT" in entity:
        errors.append("CompanionEntity: obsolete PICKUP_READY_AT map remains")
if (JAVA / "agent/AgentMemory.java").exists():
    memory = (JAVA / "agent/AgentMemory.java").read_text(encoding="utf-8")
    checks += 1
    if re.search(r'private\s+static\s+final\s+int\s+LIMIT\s*=\s*60', memory):
        errors.append("AgentMemory: old hard-coded memory limit remains")

interaction_controller = JAVA / "entity/PlayerLikeInteractionController.java"
if interaction_controller.exists():
    interaction_text = interaction_controller.read_text(encoding="utf-8")
    checks += 1
    if "FakePlayerFactory.get" in interaction_text:
        errors.append("PlayerLikeInteractionController: cached FakePlayerFactory usage remains")
follow_text = (JAVA / "entity/CompanionEntity.java").read_text(encoding="utf-8")
checks += 1
if "jumpTimer" in follow_text:
    errors.append("CompanionEntity: automatic follow bunny-hop timer remains")
stuck_text = (JAVA / "advanced/StuckRecovery.java").read_text(encoding="utf-8")
checks += 1
if "getMoveControl().setWantedPosition(tx" in stuck_text:
    errors.append("StuckRecovery: MoveControl and direct impulse are both active for corrective step")

# v1.1.7 player-parity follow-up regression checks.
require("src/main/java/com/yaseen/npcmind/agent/ToolSelectionRouter.java", (
    r'containsWord\(s, "eat"\)',
    r'nearest_animal',
    r'animal.*sheep',
    r'animal.*cow',
    r'animal.*pig',
    r'animal.*chicken',
    r'create wall',
), "word-safe eating, animal hunting, and create/build routing")
require("src/main/java/com/yaseen/npcmind/advanced/EquipmentAnalyzer.java", (
    r'item instanceof SwordItem \|\| item instanceof AxeItem \|\| item instanceof ProjectileWeaponItem',
    r'if \(stack.getItem\(\) instanceof AxeItem\) score \+= 8',
), "axe melee weapon scoring")
require("src/main/java/com/yaseen/npcmind/advanced/AdaptiveCombatController.java", (
    r'hp <= max \* 0\.10 \|\| \(!explicit',
    r'startRetreat\(c, st, target, explicit\)',
), "critical-health retreat even in explicit combat")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r"naturalRegen && saturationNow > 0\.0F && \(getHealth\(\) < getMaxHealth\(\)\) && getFoodLevel\(\) >= 20 && tickCount % 10 == 0",
    r"tickCount % 10 == 0",
), "saturation regeneration priority")
require("src/main/java/com/yaseen/npcmind/tool/impl/CombatTargetTool.java", (
    r'mode.equalsIgnoreCase\("nearest_animal"\)',
    r'getEntitiesOfClass\(Animal.class',
    r'matchesSpecies\(e, species\)',
), "animal target filtering")
require("src/main/java/com/yaseen/npcmind/tool/impl/GatherBlockTool.java", (
    r'if \(keyword.equals\("stone"\)\)',
    r'path.equals\("end_stone"\)',
), "stone family does not match redstone ore")
require("src/main/java/com/yaseen/npcmind/tool/impl/SmeltItemTool.java", (
    r'"food"\.equals\(query\)',
    r'if \(!output.getItem\(\)\.isEdible\(\)\) continue',
), "default cook chooses edible outputs")
require("src/main/java/com/yaseen/npcmind/task/TaskManager.java", (
    r'for \(int dy = 1; dy >= -2; dy--\)',
), "builder can use elevated neighboring stand positions")



# v1.1.8 survival progression and natural-loot regression checks.
require("src/main/java/com/yaseen/npcmind/state/SkillProgression.java", (
    r'speed\.setBaseValue\(\.10D\)',
    r'raw top speed',
), "player-like movement speed cap")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r'moved\s*\*\s*0\.1D',
    r'addFoodExhaustion\(isSprinting\(\) \? 0\.2F : 0\.05F\)',
    r'lastAutoJumpExhaustTick\s*>\s*10',
    r'final double radius = tickCount <= pickupEnabledUntilTick \|\| tickCount <= experiencePickupEnabledUntilTick\s*\? 2\.5D : 1\.25D',
), "sprint/jump exhaustion and bounded hunt pickup")
require("src/main/java/com/yaseen/npcmind/agent/ToolSelectionRouter.java", (
    r'counted-animal-hunt intent',
    r'named-entity combat intent',
    r'teach-trigger intent',
    r'"action\\":\\"survival\\"',
), "counted hunts, named combat, teaching and survival intents")
require("src/main/java/com/yaseen/npcmind/crafting/ResourcePlanner.java", (
    r'path\.endsWith\("_wool"\)',
    r'nearest_animal',
    r'Do NOT return merely because one item of that type exists',
), "wool/meat acquisition resumes until recipe quantity is met")
require("src/main/java/com/yaseen/npcmind/tool/impl/CraftItemTool.java", (
    r'target instanceof BedItem',
    r'woolCounts.*merge',
    r'getRecipeFor\(RecipeType\.CRAFTING, grid',
    r'bed_requires_three_.*_wool_items',
), "bed recipe uses three same-color wool and live recipe manager")
require("src/main/java/com/yaseen/npcmind/tool/impl/CombatTargetTool.java", (
    r'record HuntPlan',
    r'onTargetDefeated',
    r'count", 1',
    r'c\.enablePickup\(200\)',
    r'matchesNamedTarget',
), "counted animal hunts, natural loot pickup, and named targets")
require("src/main/java/com/yaseen/npcmind/ForgeIntegration.java", (
    r'LivingDeathEvent',
    r'CombatTargetTool\.onTargetDefeated',
), "hunt continuation on actual death event")
require("src/main/java/com/yaseen/npcmind/autonomy/AutonomyActionRouter.java", (
    r'case SURVIVAL',
    r'chooseSurvivalAction',
    r'minecraft:stone_pickaxe',
    r'minecraft:white_bed',
    r'minecraft:iron_ingot',
), "deterministic early-to-mid Survival progression")
require("src/main/java/com/yaseen/npcmind/core/CompanionKernel.java", (
    r'getLastHurtByMobTimestamp',
    r'hurtAge <= 100',
), "stale attackers no longer preempt work indefinitely")
require("src/main/java/com/yaseen/npcmind/advanced/WorldObservation.java", (
    r'"animals"',
    r'nearby_entity_types',
    r'ForgeRegistries\.ENTITY_TYPES',
), "world observation records animal species and nearby entity types")
require("src/main/java/com/yaseen/npcmind/tool/impl/TeachTriggerTool.java", (
    r'getTeachingMemory\(\)\.teach',
    r'phrase_and_instruction_required',
), "persistent teach-trigger tool is wired")


require("src/main/java/com/yaseen/npcmind/tool/impl/CraftItemTool.java", (
    r'public\s+static\s+void\s+tickPending\(CompanionEntity\s+c\)',
    r'registerPending\(companion,\s*player,\s*target,\s*requested\)',
    r'public\s+static\s+void\s+tickPending',
    r'bed_color_mismatch',
    r'crafting_table_prerequisite=',
), "multi-stage crafting continuation and bed color validation")
require("src/main/java/com/yaseen/npcmind/tool/impl/CombatTargetTool.java", (
    r'color.*sheep wool color',
    r'hasActiveHunt\(CompanionEntity\s+c\)',
    r'matchesColor\(e,\s*color\)',
), "color-matched sheep hunting and stale-hunt recovery")
require("src/main/java/com/yaseen/npcmind/tool/impl/GatherBlockTool.java", (
    r'hiddenHits\.add\(pos\.immutable\(\)\)',
    r'hits\.addAll\(hiddenHits\)',
), "covered natural resources reach MineTask access-path logic")



# Multi-stage crafting, colored beds, placement, and name matching.
require("src/main/java/com/yaseen/npcmind/tool/impl/CraftItemTool.java", (
    r"public\s+static\s+boolean\s+hasPending\(UUID\s+companionId\)",
    r"public\s+static\s+void\s+tickPending\(CompanionEntity\s+c\)",
    r"MAX_PENDING_RETRIES",
    r"TaskManager\.isActive\(c\).*hasActiveSmeltJob",
    r"gainedBeds > 0 && bedAfter < requested",
    r'"PARTIAL crafted=" \+ gainedBeds',
), "bounded multi-stage craft continuation and honest partial bed results")
require("src/main/java/com/yaseen/npcmind/agent/ToolSelectionRouter.java", (
    r"\(\?:(?:a\|an\|the\|that\|nearest\|closest)",
    r"\\p\{L\}.*\\p\{N\}",
    r"named-entity combat intent",
), "named target articles and Unicode-safe boundaries")
require("src/main/java/com/yaseen/npcmind/tool/impl/CombatTargetTool.java", (
    r"normalizeTargetAlias\(JsonArgs\.string\(a,\s*\"target_name\"\)",
    r"case \"قروي زومبي\"",
), "localized target aliases are normalized before search")
require("src/main/java/com/yaseen/npcmind/autonomy/AutonomyActionRouter.java", (
    r"bedItemId\(c\)",
    r"preferredBedOutput\(c\)",
    r"hasNearbyBed\(c,\s*16\)",
    r"new String\[\]\{\"place_block\"",
    r"minecraft:iron_pickaxe",
    r"minecraft:shield",
    r"minecraft:iron_chestplate",
), "survival bed placement and iron-tier progression")
require("src/main/java/com/yaseen/npcmind/tool/impl/BuildPatternTool.java", (
    r'pattern\.equalsIgnoreCase\(\"house\"\).*w\s*=\s*Math\.max\(3,\s*w\).*h\s*=\s*Math\.max\(2,\s*h\).*l\s*=\s*Math\.max\(3,\s*l\)',
    r"doorTarget\s*=\s*base\.relative\(forward,\s*0\).*immutable\(\)",
    r"findDoorBlock\(c\)",
    r"TaskManager\.buildWithFinisher\(",
    r"house_finish=doorway_left_open reason=door_not_in_inventory",
    r"instanceof\s+net\.minecraft\.world\.level\.block\.DoorBlock",
    r"ResourceLedger\.canConsume\(c,\s*stack,\s*1\)",
), "house dimensions and real door finishing")
require("src/main/java/com/yaseen/npcmind/task/TaskManager.java", (
    r"public\s+static\s+String\s+buildWithFinisher\(",
    r"queue\.addLast\(new\s+BuildTask\(java\.util\.List\.of\(finishTarget\.immutable\(\)\)",
), "queued build finishing task")


require("src/main/java/com/yaseen/npcmind/core/CriticEngine.java", (
    r"No generic key=value fallback",
    r"return false;",
), "critic rejects generic diagnostic-only success tokens")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r"EntityDataAccessor<Integer> XP_LEVEL",
    r"EntityDataAccessor<Float> XP_PROGRESS",
    r"EntityDataAccessor<Integer> XP_TOTAL",
    r"void addExperiencePoints\(int points\)",
    r"ExperienceOrb.class",
    r"enableExperiencePickup\(int ticks\)",
    r"if \(!pickupEnabled\) return;",
    r"saveExperience\(owner, getExperienceLevel\(\), getExperienceProgress\(\), getTotalExperiencePoints\(\)\)",
), "player-like XP state, orb collection and respawn persistence")
require("src/main/java/com/yaseen/npcmind/api/PlayerLikeExperience.java", (
    r"public static Snapshot read\(LivingEntity entity\)",
    r"public static boolean grant\(LivingEntity entity, int points\)",
    r"pointsToNextLevel",
    r"getPersistentData\(\)",
), "generic persistent player-like XP helper")
require("src/main/java/com/yaseen/npcmind/tool/impl/ManagePlayerStateTool.java", (
    r"manage_player_state",
    r"xp_level=",
    r"air=",
    r"fully_frozen=",
), "player-like state inspection tool")
require("src/main/java/com/yaseen/npcmind/tool/impl/CollectExperienceTool.java", (
    r"collect_xp",
    r"xp_pickup_window_enabled=true",
    r"enable|disable|status",
), "explicit bounded XP-orb collection tool")
require("src/main/java/com/yaseen/npcmind/api/ToolPack.java", (
    r"PLAYER_STATE\(Set\.of\(\"manage_player_state\", \"collect_xp\"\)\)",
), "player-state tools are an opt-in feature pack")
require("build.gradle", (
    r"withSourcesJar\(\)",
    r"artifactId = 'npc-mind-forge-1.20.1'",
    r"url = layout\.buildDirectory\.dir\('repo'\)",
), "Maven-publish library distribution and sources artifact")

# v1.1.13 survival behaviors implemented behind NPC Mind's existing controller/tool architecture.
require("src/main/java/com/yaseen/npcmind/advanced/WaterBucketClutch.java", (
    r"MIN_FALL_SPEED",
    r"MAX_ACTIVE_TICKS",
    r"WorldActionSecurity\.canPlaceBlock",
    r"convertOne\(c, waterBucket, Items\.WATER_BUCKET, Items\.BUCKET\)",
), "bounded water-bucket fall clutch and protected block placement")
require("src/main/java/com/yaseen/npcmind/advanced/SleepController.java", (
    r"isNight\(ServerLevel level\)",
    r"BedBlock",
    r"startSleeping\(head\)",
    r"SLEEP_TICKS = 100",
    r"MAX_APPROACH_TICKS = 100",
    r"BedBlock\.OCCUPIED",
    r"wake\(c, state",
), "local bed sleep simulation without player-only sleep/time skip")
require("src/main/java/com/yaseen/npcmind/advanced/StuckRecovery.java", (
    r"MAX_SEARCH_COLUMNS_PER_TICK = 4",
    r"searchIndex < s\.searchColumns\.size\(\)",
    r"setAvoidedStandPredicate",
    r"MAX_FALL = 16",
), "per-tick bounded stuck search and avoided stand positions")
require("src/main/java/com/yaseen/npcmind/advanced/AITickThrottle.java", (
    r"boolean due\(int currentTick, String key, int intervalTicks\)",
), "general per-companion background AI throttle")
require("src/main/java/com/yaseen/npcmind/tool/impl/ThrowEnderPearlTool.java", (
    r"throw_ender_pearl",
    r"WINDUP_TICKS = 6",
    r"ThrownEnderpearl",
    r"CONSUME_RARE",
    r"COOLDOWN_TICKS = 200",
    r"level\.addFreshEntity\(pearl\)",
    r"stack\.shrink\(1\)",
    r"tickPending\(CompanionEntity c\)",
), "permission-gated cancellable ender-pearl action")
require("src/main/java/com/yaseen/npcmind/tool/impl/FillWaterBucketTool.java", (
    r"fill_bucket",
    r"level\.getBlockState\(pos\)\.is\(Blocks\.WATER\)",
    r"ClipContext\.Fluid\.SOURCE_ONLY",
    r"isSource\(\)",
    r"WorldActionSecurity\.canInteractWithBlock",
), "source-water-only bucket filling with access and reach checks")
require("src/main/java/com/yaseen/npcmind/agent/ToolSelectionRouter.java", (
    r"ender-pearl tactical intent",
    r"water-bucket acquisition intent",
), "new Arabic/English survival tool routes")
require("src/main/java/com/yaseen/npcmind/core/CompanionKernel.java", (
    r"SleepController\.tick",
    r"aiTickThrottle\(\)\.due",
), "sleep integration and throttled background controllers")
require("src/test/java/com/yaseen/npcmind/advanced/AITickThrottleTest.java", (
    r"keyRunsImmediatelyThenWaitsForItsInterval",
    r"keysHaveIndependentSchedules",
    r"intervalsAreClampedAndResetWorks",
    r"blankKeysAreNotThrottled",
), "JUnit tests for per-companion AI throttling")
require("build.gradle", (r"version = project\.mod_version",), "v1.2.0 library build version")
require("src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java", (r'API_VERSION\s*=\s*"1\.3\.1"',), "v1.3.1 API version")
require("LICENSE", (r"^MIT License", r"Copyright \(c\) 2026 Yaseen"), "root MIT license and copyright")

# ---------------- v1.2.0 library conversion: no @Mod entrypoint, explicit host bootstrap.
require("src/main/java/com/yaseen/npcmind/NPCMindLibrary.java", (
    r"public static void bootstrap\(IEventBus hostModEventBus\)",
    r"ModEntities\.ENTITY_TYPES\.register\(hostModEventBus\)",
    r"ModMenus\.MENUS\.register\(hostModEventBus\)",
    r"EntityAttributeCreationEvent",
    r"registerConfig\(ModConfig\.Type\.COMMON, Config\.SPEC\)",
    r"public static synchronized void registerIntegration\(\)",
    r"MinecraftForge\.EVENT_BUS\.register\(ForgeIntegration\.class\)",
    r"public static void registerClient\(IEventBus hostModEventBus\)",
    r"FMLEnvironment\.dist\.isClient\(\)",
    r"isIntegrationRegistered",
), "explicit library bootstrap facade (v1.2.0)")
forbid("src/main/java/com/yaseen/npcmind/NPCMind.java", (
    r"@Mod\b",
    r"FMLJavaModLoadingContext",
    r"ModLoadingContext",
), "identity holder has no mod entrypoint")
forbid("src/main/java/com/yaseen/npcmind/ForgeIntegration.java", (
    r"@Mod\.EventBusSubscriber",
    r"net\.minecraftforge\.fml\.common\.Mod",
), "integration handlers are explicitly registered, not annotation-scanned")
forbid("src/main/java/com/yaseen/npcmind/client/ClientRendering.java", (
    r"@Mod\.EventBusSubscriber",
), "client rendering is explicitly registered, not annotation-scanned")
require("src/main/java/com/yaseen/npcmind/client/ClientRendering.java", (
    r"public static void register\(IEventBus hostModEventBus\)",
    r"hostModEventBus\.register\(ClientRendering\.class\)",
), "client rendering exposes explicit host-bus registration")
require("docs/examples/HostModExample.java", (
    r"@Mod\(ExampleHostMod\.MOD_ID\)",
    r"NPCMindLibrary\.bootstrap\(modBus\)",
    r"NPCMindLibrary\.registerIntegration\(\)",
    r"NPCMindLibrary\.registerClient\(modBus\)",
), "host-mod example kit")
require("docs/examples/host-mod/mods.toml", (
    r'modLoader="javafml"',
    r'modId="examplehost"',
), "host-mod metadata template")
require("docs/examples/host-mod/build.gradle.snippet", (
    r"fg\.deobf\('com\.yaseen:npc-mind-forge-1\.20\.1:1\.3\.1'\)",
    r"jarJar",
), "host-mod dependency snippet")
require("docs/LIBRARY_CONVERSION_v1.2.0.md", (
    r"developer library",
    r"Breaking changes",
), "library conversion notes")

# ---------------- v1.1.19 review round (items 60-68): stuck work-budget queue, directory and
# saturation parity, follow-teleport exhaustion anchor, critic allow-list, alert scoping.
require("src/main/java/com/yaseen/npcmind/autonomy/CompanionWorkBudget.java", (
    r"ENTRY_TTL_TICKS = 60L",
    r"lastSeenTick",
    r"evictStaleLocked\(now\)",
    r"e\.withLastSeen\(now\)",
    r"static final class RotationQueue",
    r"int evictStale\(long now\)",
    r"boolean mayRun\(UUID id\)",
), "work-budget rotation heartbeat and TTL eviction (stuck-queue fix)")
require("src/main/java/com/yaseen/npcmind/core/CompanionKernel.java", (
    r"CompanionWorkBudget\.release\(c\.getUUID\(\)\)",
), "long task releases the work-budget rotation slot")
require("src/test/java/com/yaseen/npcmind/autonomy/CompanionWorkBudgetTest.java", (
    r"secondCompanionGetsLeaseAfterFirstStopsRequesting",
    r"evictStaleOnlyRemovesExpiredEntries",
    r"heartbeatKeepsActiveWaitersInFifoOrder",
    r"releaseAdvancesRotation",
    r"queueIsBounded",
), "JUnit tests for the work-budget rotation queue")
require("src/main/java/com/yaseen/npcmind/api/PlayerLikeState.java", (
    r"private static float clampSaturation\(float saturation\)",
    r"Math\.min\(MAX_FOOD, saturation\)",
), "saturation carries only the physical 0..20 bound (vanilla setFood parity)")
require("src/main/java/com/yaseen/npcmind/entity/CompanionEntity.java", (
    r"if \(tag\.hasUUID\(OWNER_TAG\)\) setOwner\(tag\.getUUID\(OWNER_TAG\)\)",
    r"UUID previous = this\.owner",
    r"unregisterLiveCompanion\(getUUID\(\)\)",
    r"mob\.resetSprintExhaustionAnchor\(\)",
), "NBT-load registers live companion; owner change unregisters the old owner; follow teleport re-anchors exhaustion")
require("src/main/java/com/yaseen/npcmind/core/CriticEngine.java", (
    r"r\.contains\(\"already_have\"\)",
    r"r\.contains\(\"crafting_table_already_nearby\"\)",
), "critic allow-list matches the real CraftItemTool success literals")
require("src/main/java/com/yaseen/npcmind/advanced/CompanionAlertManager.java", (
    r"String reporterTeamId",
    r"myTeam\.equals\(alert\.reporterTeamId\(\)\)",
    r"List<Map\.Entry<UUID, Alert>> prune",
    r"for \(Map\.Entry<UUID, Alert> entry : prune\) map\.remove\(entry\.getKey\(\), entry\.getValue\(\)\)",
), "team-scoped alert radius and collect-then-remove pruning")
require("src/main/java/com/yaseen/npcmind/advanced/CombatAssessor.java", (
    r"props\.getNutrition\(\) < 3",
    r"hasHarmfulEffect\(props\)",
    r"MobEffectCategory\.HARMFUL",
), "healing-item classification excludes harmful/snack-tier food")

# ---------------- v1.2.0 library hardening (library engineering round): version source,
# manifest/POM declarations, @Internal surface gate, nullability contract, API compatibility
# test, governance docs and CI ----------------
require("gradle.properties", (
    r"(?m)^mod_version=1\.3\.1$",
    r"(?m)^mod_group=com\.yaseen$",
    r"(?m)^minecraft_version=1\.20\.1$",
    r"(?m)^forge_version=47\.4\.10$",
    r"(?m)^jetbrains_annotations_version=24\.1\.0$",
    r"(?m)^junit_version=5\.10\.2$",
), "gradle.properties single version source")
require("build.gradle", (
    r"version = project\.mod_version",
    r"group = project\.mod_group",
    r"withSourcesJar\(\)",
    r"withJavadocJar\(\)",
    r"failOnError = false",
    r"'Automatic-Module-Name'\s*:\s*'com\.yaseen\.npcmind'",
    r"'Implementation-Version'\s*:\s*project\.mod_version",
    r"'Specification-Version'\s*:\s*'1\.2'",
    r"compileOnly \"org\.jetbrains:annotations:\$\{project\.jetbrains_annotations_version\}\"",
    r"pom\.withXml",
    r"forge\.appendNode\('scope', 'provided'\)",
    r"jb\.appendNode\('scope', 'provided'\)",
    r"artifactId = 'npc-mind-forge-1\.20\.1'",
), "library build hardening")
require("src/main/java/com/yaseen/npcmind/api/Internal.java", (
    r"RetentionPolicy\.CLASS",
    r"@Documented",
    r"public @interface Internal",
    r"ElementType\.TYPE",
), "@Internal marker annotation")
require("src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java", (
    r"import org\.jetbrains\.annotations\.NotNull;",
    r"import org\.jetbrains\.annotations\.Nullable;",
    r"public static @NotNull List<ToolInfo> tools\(\)",
    r"public static @NotNull Optional<ToolSelectionRouter\.Route> resolveCommand\(@Nullable String command\)",
    r"public static @NotNull String executeEntityTool\(@Nullable LivingEntity actor",
    r"public static void registerIntent\(@NotNull String\[\] keywords",
), "NPCMindAPI nullability contract")
require("src/test/java/com/yaseen/npcmind/api/ApiCompatibilityTest.java", (
    r'EXPECTED_API_VERSION = "1\.3\.1"',
    r"api\.getField\(\"API_VERSION\"\)",
    r"getDeclaredMethods",
    r"com\.yaseen\.npcmind\.NPCMindLibrary",
    r"internalMarkerUsesClassRetention",
), "API compatibility test gate")
require("tools/check_api_surface.py", (
    r"INTERNAL_MARKED_FILES",
    r"UNMARKED_FILES",
    r"tool/AITool\.java",
    r"NPCMindLibrary\.java",
), "API surface gate")
require(".github/workflows/build.yml", (
    r"actions/setup-java@v4",
    r"java-version: '17'",
    r"check_api_surface\.py",
    r"source_audit\.py",
    r"verify_repair_regressions\.py",
    r"verify_mit_release\.py",
), "CI workflow runs the full gate set")
require("jitpack.yml", (
    r"openjdk17",
), "JitPack build pins JDK 17 for the Gradle 8.1.1 wrapper")
require("docs/API_STABILITY.md", (
    r"Semantic Versioning",
    r"@Internal",
    r"Deprecation",
    r"check_api_surface\.py",
), "API stability policy document")
require("CHANGELOG.md", (
    r"## \[1\.3\.1\]",
    r"## \[1\.3\.0\]",
    r"## \[1\.2\.0\]",
    r"## \[1\.1\.19\]",
), "developer-facing changelog covers the library line")
require("CONTRIBUTING.md", (
    r"check_api_surface\.py",
    r"Unsupported class file major version 65",
), "contributor guide documents the JDK 17 constraint and gates")
require("SECURITY.md", (
    r"FakePlayer",
    r"server-thread",
), "security policy covers world-mutation surface")

print(f"REPAIR_REGRESSION_CHECKS={checks}")
for error in errors:
    print(f"ERROR={error}")
print(f"REPAIR_REGRESSION_ERRORS={len(errors)}")
sys.exit(1 if errors else 0)
