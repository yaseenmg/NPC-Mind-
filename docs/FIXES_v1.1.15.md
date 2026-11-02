# NPC Mind v1.1.15 — Fix Log (second round)

A follow-up release addressing **every remaining statically-repairable item** from the review report after the v1.1.14 round (which covered the source-translation errors and the "confirmed" list). This round covers: remaining high-risk logic bugs, medium bugs, performance notes, and dead-code wiring.

## 1) High-risk logic bugs (remaining)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 1 | `agent/AgentEngine` | `waitForTask` timing out after 3 minutes reports `TIMEOUT`, which the critic classifies as failure while the task is still running → command retries pile up | Reclassified as asynchronous work (`STILL_RUNNING ... do not retry`), like the `PENDING` path |
| 2 | `tool/impl/SmartVeinMiningTool` | `deepslate_iron_ore` and `raw_iron_block` do not match `iron_ore` → iron/diamond not found at depth (most ores) | Ore family: `deepslate_*`, `raw_*_block`, and the absolute form, matched in both directions |
| 3 | `task/TaskManager.mine` | The queue cap of 8 truncates 12-ore requests (the furnace needs 8 after the pickaxe and sword) and breaks the survival chain | Cap of 32 per `mine`/`tunnel` with an explicit failure message when full |
| 4 | `advanced/StuckRecovery.tick` | A 0.2 blocks/tick threshold classifies slow speeds (crouching, doors/gates) as stuck | Movement measured over a 10-tick window with a 0.35-block minimum total |
| 5 | `advanced/StuckRecovery.advanceSearch` | `getHeight(surface)` + `fall < 0` rejects every candidate underground | When the surface is above the companion: the column is scanned downward up to 10 blocks looking for a standable cell |
| 6 | `state/DecisionJournal` | `hasSuccessAfter` searched the whole journal instead of everything after the baseline | Per-constraint sequence numbers, with the search bounded by `seq > baseline` (saved and restored) |
| 7 | `core/CompanionKernel` | `ExplorationController` only ran in EXPLORER mode → `explore_mode` and the `search_structure` discovery window were dead in the default mode | Also runs whenever an exploration window is active |
| 8 | `agent/ToolSelectionRouter` | Single-word keys `till`/`kill` partially matched `still`/`skill` | Word boundaries for single-word Latin keys; non-Latin aliases use locale-aware matching and preserve attached articles |
| 9 | `agent/ToolSelectionRouter` | a localized word meaning "all" was interpreted as "eat" ("collect all the wood", "put food in the chest") | The eat intent requires the Arabic imperative verb at sentence start, or explicit context words |
| 10 | `agent/ToolSelectionRouter` | `resourceItemId` matched `log` inside `blog` and `wood` inside `wooden` | Word boundaries for Latin; `wooden planks` now returns planks |
| 11 | `agent/ToolSelectionRouter` | `parsedCount` capped at 32 while crafting allows 64 | Cap raised to 64 |
| 12 | `agent/ToolSelectionRouter` | No Arabic hunting-count support ("kill 3 sheep") + a dead `Enderman` branch after normalization | Full Arabic count pattern + dead branch removed |
| 13 | `entity/CompanionEntity.aiStep` (eating) | Vanilla `updatingUsingItem` completes consumption before `aiStep`, entering the "interrupted" branch and re-feeding → an eat-without-saturation loop | Our timer ends one tick before vanilla's + early interruption distinguished from completion (nutrition applied once; no returning consumed food) |
| 14 | `entity/CompanionEntity.tick` | The `MOVEMENT` log wrote two files per block on the server thread | Sampled every 100 ticks instead of every block |
| 15 | `client/CompanionRenderer` | `HumanoidMobRenderer` does not add the armor layer → armor invisible | `HumanoidArmorLayer` with the standard player geometry + ModelSet |
| 16 | `advanced/AdaptiveCombatController.chooseWeapon` | With no nearby weapon, the bow stayed in hand and it fought by punching with it | The bow is swapped out of hand at close combat range (fists instead) |
| 17 | `advanced/CompanionMeleeGoal.handleShield` | Raising the shield when the strike is ready cancels the counter-strike (`isUsingItem` is an attack condition) | The shield is not raised on the tick the strike is ready and in range |
| 18 | `debug/AuditLog` usage sites | Continuous I/O pressure from MOVEMENT (handled in #14) | — |

## 2) Medium bugs

| # | File | Problem | Fix |
|---|------|---------|-----|
| 19 | `entity/CompanionItemHandler.accept` | A "can't eat" message on any drop pickup, and raw meat eaten instead of cooked | The message only on player hand-off; cooked food preferred when choosing what to eat (raw is a last-resort plan with a score penalty) |
| 20 | `tool/impl/CombatTargetTool` | Wool hunting killed already-sheared sheep (`isSheared`) | Sheared sheep excluded from selection |
| 21 | `tool/impl/SmeltItemTool` | One item + one fuel per job → 7/8 of the coal wasted | Full batch: `burnTime/200` items per fuel unit (coal = 8) in a single strike |
| 22 | `advanced/StructureTravelController.issueLeg` | The waypoint taken at the companion's own height failed on the first hill/unloaded chunk and the whole trip was cancelled | Height taken from the loaded chunk surface; a failed leg → retry after 5 seconds instead of cancelling the trip |
| 23 | `task/TaskManager.move` | `cancelCurrentTask` before the navigation request → a rejected request lost the original task | Navigation requested first; cancellation only after acceptance |
| 24 | `autonomy/NavigationArbiter` | Up to 16 path computations per request with `FollowOwnerGoal` every 10 ticks | At most 4 computations per search |
| 25 | `tool/impl/LightAreaTool` | `findBlockSlot` returned -2 (in hand), treated as "not found", then `getItem(-2)` → FAILED | -2 accepted: reads the main hand |
| 26 | `tool/impl/DepositItemTool` + `TakeFromContainerTool` | `getBlockEntity` for a double chest returned only half | `ChestBlock.getContainer` returns the full chest (54 slots) |
| 27 | `ForgeIntegration /npcmind come` | Executed "follow me" instead of "come here" | Routed to `come here` (approach then stop) |
| 28 | `ForgeIntegration /npcmind spawn` | Did not prevent multiple companions per owner | Refused when a live owned companion exists (in any dimension) |
| 29 | `ForgeIntegration.nearestOwned` | Counted every entity in every dimension, then filtered | Dimension filtered before the entity loop |
| 30 | `ForgeIntegration.onEntityLeaveLevel` | `purge` even on chunk unload or dimension change → pending crafts and hunts lost | Purge only on `KILLED`/`DISCARDED` |
| 31 | `ForgeIntegration` | No listener for companion damage → CombatLearning statistics always zero | `damageTaken` in `LivingHurtEvent` + `damageDealt` in `LivingDamageEvent` |
| 32 | `state/CompanionRespawnController` | Respawned as "Steve" and lost permissions | Name + permission snapshot saved on death and restored on respawn (new `CompanionWorldData`) |
| 33 | `advanced/WaterBucketClutch.tickPlacedWater` | Only the source was removed → flowing water remained around it | Non-source water cleaned in a 5×3×5 area after removal |
| 34 | `task/TaskManager.BuildTask` | Cells with no supporting face were skipped permanently → houses with gaps | An automatic second pass over the skipped cells after the first pass completes |
| 35 | `core/SkillLibrary.retrieve` | `limit(5)` applied before the query filter → "none" despite a matching skill | Filter first, then the cap |
| 36 | `tool/impl/StructureSearchTool.normalize` | `temple` collapsed to `desert_pyramid`, leaving the four-temple branch unreachable | `temple` stays a standalone identifier that benefits from the four-variant branch |
| 37 | `advanced/InventoryIntelligence` + `collectNearbyDrops` | Surplus dropped, then picked up again by AUTO_PICKUP → a loop | A "recently dropped" record (1200 ticks) prevents re-pickup |
| 38 | `agent/InternalToolExecutor` | The repeated-failure guard was inactive (`AgentMemory.failures`/`MAX_REPEATED_FAILURES` had no reader) | A real gate: the same tool+arguments failing 3 times (TTL 30s) → `BLOCKED repeated_failure`; success clears it |

## 3) Performance and chunk loading

| # | File | Problem | Fix |
|---|------|---------|-----|
| 39 | `autonomy/MaintenanceController.scan` | 65³ ≈ 274 thousand `getBlockEntity` calls in one tick, potentially forcing chunk loads | One pass over the loaded block entities (`getAllBlockEntities`) with distance filtering |
| 40 | `advanced/BaseSiteEvaluator` | Radius 48 + a villager query per column | Radius 24 + a single villager query for the area + `hasChunkAt` before heightmaps |
| 41 | `core/CompanionKernel` → `advanced/ThreatMonitor` | `ThreatMonitor.tick` was never called (dead code) | Wired into the loop core with an internal 20-tick gate |

## 4) Un-wired dead code

- **Now wired**: `ThreatMonitor` (#41), `PlanExecutionController.isBlocked` (a gate at the start of `AgentEngine.loop`), `AgentMemory.failures` (#38), `CompanionState.hunger` (grows as food drops, drops on saturation).
- **Documented as public API for host mods** (no intentional internal caller): `WorldContext.build`, `ImitationMemory.record`, `MathEngine`, `NavigationController.move`, `AlternativePlanner.instruction`, `FarmAreaTool.consumeSeed/findSeed`, `TillAndPlantTool.consumeSeedFor`, `TaskManager.tunnel` — removing them would break the public surface; using them internally would require new features outside the repair scope.

## 5) Items deliberately deferred (need in-game testing)

- Bow strike balance at close range / the ranged limit — fine combat behavior that can only be measured in a real world.
- A TTL cache for `hasNearbyBed/Block` — performance is currently acceptable (a spike every 5 seconds) and will be measured before complicating it.

## 6) Verification performed and its limits

- ✅ Comprehensive static check of 160 Java files (brace/string balance, import availability, bad patterns): **0 errors**.
- ✅ `tools/verify_repair_regressions.py`: **272 checks, 0 errors** (updated for the 1.1.15 expectations).
- ✅ `tools/verify_mit_release.py`: **0 errors**.
- ✅ `tools/source_audit.py`: 59 registered tools, 0 errors.
- ⚠️ No real `./gradlew clean build` was executed (an environment without Forge dependencies) — to be run locally before publishing.

## 7) Additional behavior improvements (items 42–44)

- `CombatTargetTool` validates target liveness and ownership before named-target attacks, protecting the owner, protected players, companions, and owned pets. Explicit owner sparring remains a separate mode.
- `BodyguardController` checks the owner's recent attacker as well as the mob's current target, so ranged attackers that clear their target after a hit are still recognized.
- `ExplorationController` adds bounded weighted variation and remembers recent destinations, reducing repeated attempts to reach a blocked candidate.

## 8) Verification after these behavior improvements

- The static check and regression suite passed for that historical round.
- The checks validate source structure only; they do not substitute for a Java/Forge build or in-game test.

## 9) Packaged build review (items 45 to 47)

Responding to an independent inspection report of the packaged build, the two statically-repairable items were fixed and the environmental item justified:

| # | File | Problem | Fix |
|---|------|---------|-----|
| 45 | `ForgeIntegration.onLivingDeath` + `tool/impl/CombatTargetTool` | The death handler required `getTarget() == dead` literally, and vanilla targets may clear before the kill lands (out of tracking range, re-selection, damage interruption), so kills from `kill 3 sheep` were undercounted — and `hasActiveHunt` discarded a sound plan merely because `getTarget()` became null | A short-lived "last hunt target" record (`LAST_HUNT_TARGET`, TTL 200 ticks) filled by `trackHuntTarget` at hunt start and target sequencing; the death handler accepts `getTarget()==dead` **or** `isRecentHuntTarget(c,dead)`; `hasActiveHunt` keeps the plan as long as the planned target is alive within the tracking window; and `clearHunt` clears the record with the plan. A kill cannot be counted for a companion that never hunted that entity, because the record is owned exclusively by the hunting tool |
| 46 | `docs/FIXES_v1.1.15.md` | Historical documentation no longer matched the current license-file layout | Reworded the note to describe behavior and verification without a stale file pointer |
| 47 | `gradle/wrapper/gradle-wrapper.properties` + `README.md` | Gradle 8.1.1 fails to start on Java 21 (`major version 65`) — an environmental item, not a source bug: the `toolchain Java 17` is already in build.gradle | The constraint documented where it is read: a comment in the wrapper properties (8.1.1 pinned intentionally for ForgeGradle [6.0,6.2) compatibility — upgrading the wrapper without local testing is a risk) and a "Building" section in the README with `JAVA_HOME` setup commands per OS |

## 10) Verification after the packaged build review

- ✅ `scripts/static_check.py`: 160 main files — 0 errors.
- ✅ `tools/verify_repair_regressions.py`: 272 checks, 0 errors.
- ✅ `tools/verify_mit_release.py`: 0 errors.
- ✅ `tools/source_audit.py`: 59 tools, 0 errors.
- ⚠️ A real `clean test build` requires an actual JDK 17 (see item 47) and a real Forge world for the runtime tests documented above.
