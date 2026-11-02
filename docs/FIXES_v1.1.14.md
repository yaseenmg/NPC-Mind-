# NPC Mind v1.1.14 — Fix Log

A repair release built on v1.1.13, addressing every confirmed bug from the review reports (v1.1.12/13) against Forge 1.20.1 / MCP 1.20.1 mappings.

## 1) Source-translation errors (compile blockers)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 1 | `core/SkillLibrary.java` + `state/CompanionWorldData.java` | Passing a `ListTag` into `saveSkills(UUID, CompoundTag)` and assigning a `CompoundTag` to a `ListTag`, with a semantic conflict on top: both skill systems wrote under the same `skills` key in two different formats, corrupting each other | Wrap tool statistics in a `CompoundTag` with a `{format, entries}` shape + split the stores: `tool_skills` for SkillLibrary and `skills` for the SkillProgression snapshot taken on death, with automatic migration of old data |
| 2 | `client/ClientScreens.java` (deleted) → `client/ClientRendering.java` | Reliance on the menu-screens event, which is not guaranteed across all Forge 1.20.1 builds | Screens registered via `MenuScreens.register(...)` inside `FMLClientSetupEvent.enqueueWork` (a pattern guaranteed on every 1.20.1 build) |
| 3 | `task/TaskManager.java` (TillTask) | `BlockState` used without an import | Added `import net.minecraft.world.level.block.state.BlockState` |
| 4 | `tool/impl/CraftItemTool.java` (craftBed) | Treating the `getRecipeFor` result as a `RecipeHolder` (a 1.20.2+ API) while 1.20.1 returns `Optional<CraftingRecipe>` | Use `recipeMatch.get().getId()` directly |
| 5 | `entity/CompanionEntity.java` (collectNearbyDrops) | Reading the private field `orb.value` of `ExperienceOrb` | Use `getValue()` in both sites |

## 2) Critical bugs

| # | File | Problem | Fix |
|---|------|---------|-----|
| 6 | `entity/CompanionEntity.tick()` | During the 7-tick pearl windup the `CompanionKernel` stops entirely, so `SurvivalGuard` no longer runs (no lava/fire/drowning checks) | When a windup is active, `SurvivalGuard.tick()` is called explicitly instead of skipping everything |
| 7 | `tool/impl/ThrowEnderPearlTool.java` + new entity `entity/CompanionEnderpearl.java` | The vanilla pearl only teleports a `ServerPlayer` → the bot consumed a pearl with no teleport at all | A `CompanionEnderpearl` entity registered in `ModEntities` with a `ThrownItemRenderer`, replicating the vanilla path (5% endermite chance, 5 fall damage, TELEPORT event, discard) for any `LivingEntity` owner |
| 8 | `advanced/WaterBucketClutch.java` | Placed water directly above lava without the vanilla conversion + lost the original block when the bucket conversion failed (wrote AIR) | Source lava → `OBSIDIAN`, flowing lava → `COBBLESTONE` with the `FIRE_EXTINGUISH` sound; on failure the original state is restored, and the dead condition in `findPlacement` was removed |
| 9 | `tool/ToolRegistry.enableOnlyTools` | Dropped the external tools (`EXTERNAL_NAMES`) unlike `enableOnlyPacks` | `next.addAll(EXTERNAL_NAMES)` before saving |

## 3) Confirmed logic bugs

| # | File | Problem | Fix |
|---|------|---------|-----|
| 10 | `autonomy/AutonomyActionRouter` | At hunger 9–12 with no food, both the eat and hunt conditions failed, so it went gathering wood instead | Hunting threshold `<= 12` instead of `<= 8` |
| 11 | `entity/CompanionEntity.tickSmeltJob` | `smeltJobPos` carried no dimension → after a dimension change a Nether furnace could be treated as the original furnace, or the original smelt job cancelled | `smeltJobDimension` stored in memory and NBT, and the job cleared on mismatch |
| 12 | 9 read-only inspection tools | Results had no prefix → `AgentEngine` replied "I couldn't complete that action" despite success | Unified `RESULT` prefix for: InspectRecipes, InspectRegistry, Location, InspectInventory, InspectCompanionInventory, InspectNearbyEntities, InspectContainer, InspectEquipment (InspectLookBlock was already correct) |
| 13 | `core/WorldSnapshot` | The inventory signature ignored both hands and armor → equipment changes were invisible to the critic | `EquipmentSlot` slots added to the signature |
| 14 | `core/CriticEngine` | `filled_water_bucket=true`, `learned_trigger` and `SUCCESS structure=` were classified as failures | Allow-list expanded |
| 15 | `entity/CompanionEntity` NBT | `enderPearlCooldownTicks` was not saved → cooldown bypassed via save/load | `EnderPearlCooldown` saved/read |
| 16 | `api/PlayerLikeState.tick` | Regeneration deviated from vanilla: threshold 18 for fast regen, flat 1.0 healing, flat exhaustion | Exact match of vanilla `FoodData`: fast (sat>0, food>=20, every 10 ticks, heal `min(sat,6)/6` and exhaustion `min(sat,6)`) and slow (food>=18, every 80 ticks, 1.0/6.0) |
| 17 | `entity/CompanionEntity.aiStep` | Fast regen at food 18–19 (faster than a player), exhaustion 3.0, and `RULE_NATURAL_REGENERATION` ignored | Same vanilla matching + gamerule respected; refeeding via `InventoryOps.addAndReturnRemainder` with the remainder dropped instead of `SimpleContainer.addItem`, which destroys items when full (3 sites) |
| 18 | `task/TaskManager` (TillTask) | Un-tillable cells skipped without `skipped++` → false `SUCCESS farm_done` | Every skip counted |
| 19 | `task/TaskManager` (6 sites) + `autonomy/ResourceLedger` | `canConsume` (the reservation gate) misused as an equipment gate → false `needs_pickaxe` failures while the tool existed | New `ResourceLedger.owns(c, stack, n)` (pure ownership, no reservation) used in the six sites: hasPickaxeTier, equipBestMiningTool, hasBuildBlock, ensureBuildBlockInHand (×2), consumeBuildBlock |
| 20 | `ForgeIntegration` | No `LivingHurtEvent` handler: retaliation was only a periodic sweep (16 blocks/100 ticks), and a sleeping companion kept sleeping while being hit | Event handler: `setTarget` on the attacker (owner hits exempted) + `SleepController.wakeOnHurt` |
| 21 | `advanced/SleepController` | No wake on ranged attack (>16 blocks) + multiple companions in the same bed | `getLastHurtByMob` checked with a 100-tick window on every sleep tick + a `BED_CLAIMS` reservation record (bed→UUID) released on wake/cleanup |
| 22 | `advanced/StuckRecovery` | `gaveUp` was set but never read → the task hung for up to 45 seconds | `TaskManager.cancelForReflex(c, "stuck_unreachable")` on giving up |
| 23 | `advanced/ExplorationController` | Heightmaps read for 289 columns up to ±128 blocks without load checks → fake y (-64) and wrong candidates | `hasChunkAt` before every `getHeightmapPos` |
| 24 | `core/CompanionKernel` + `advanced/AITickThrottle` | An external throttle duplicated the internal intervals (40/100/200) → the controllers were effectively stopped | Throttle removed from observation/scene_memory/daily_routine/suggestions, and the eviction limit in `due()` corrected |

## 4) Minor fixes

- `memory/MemoryStore`: `load`/`save`/`unload` wired to player join/leave events (previously `remember` memory was lost).
- `state/ActionPermissions`: no path existed to enable CONSUME_RARE/DROP/DESTROY → new command `/npcmind permission <action> <true|false>` + corrected the `PermissionTool` text (it mentioned a non-existent `/aic permission set`) — without this `throw_ender_pearl` was permanently dead.
- `tool/impl/FillWaterBucketTool`: `PLAYERS` sound channel instead of `BLOCKS` (matching the vanilla `BucketItem`).
- `tool/impl/StructureSearchTool.pollDiscovery`: clean player-facing message "Found x at N blocks." instead of the internal `STARTED structure_target=...` form.
- `autonomy/AutonomyActionRouter`: removed the `Status:` message repeated every 5 seconds.
- `state/CompanionState`: the count now only includes monsters actually targeting the companion or its owner (no false panic from a passing zombie).
- `api/NPCMindActions.setCrouching`: pose guards (passenger/sleeping/swimming/flying/dying).
- `tool/impl/AutonomyTool`: `toUpperCase(Locale.ROOT)` + `mode=SURVIVAL` consistent with `action=survival` (enables the routine).
- `entity/CompanionEntity.say`: messages logged to the server log when the owner is offline instead of being silently dropped.
- `entity/CompanionEntity.ask`: `Trace.log` called with the correct signature (it previously matched the (area, fmt, a, b) overload, losing the line).
- `tool/impl/InspectCompanionInventoryTool` and `FindNearbyContainerTool`: a literal `\n` in the result header → a real newline.
- `.gitignore` + `tools/export_source_txt.py`: ignore `*.args` and `javac.*`.
- Version updated to `1.1.14` in `build.gradle`, `mods.toml`, `NPCMindAPI.API_VERSION` and the verification-tool expectations.

## 5) Intentionally left unchanged

- Report items "unconfirmed, need runtime" (armor behavior in combat, the bow edge case with no arrows, the original food>=18 slow-regen balance) — to be tested in-game before any change.
- `CompanionRenderer`, the visible armor layer, `ThreatMonitor`, and un-wired dead code — out of scope for this round (confirmed items only), deferred to a feature release.
- Note on the bundled reference: the AI reference bundled in the external archive (RAR) is a goal-based intelligence framework (Goals/Brains) and does not cover the pearl/sleep/bucket systems; the fixes were therefore written directly against the vanilla 1.20.1 mappings while following the same principles (server-thread guards, control-loop separation).

## 6) Verification performed and its limits

- ✅ Comprehensive static check: brace/string balance for all 160 Java files, import availability for every new symbol, and no leftovers of the bad patterns (orb.value, RecipeHolder, RegisterMenuScreensEvent...).
- ✅ The project's own tools: `verify_repair_regressions.py` (272 checks, 0 errors) and `verify_mit_release.py` (0 errors) after updating their expectations for the new code.
- ⚠️ No real `./gradlew clean build` was executed (an environment without Forge dependencies) and no in-game test — recommended locally before publishing, focusing on: the pearl in lava, the bucket above lava, sleeping with more than one companion, and `tool_skills` persistence across death/return.
