# NPC Mind v1.1.16 — Fix Log (fourth round)

Responding to the comprehensive manual review of v1.1.15 (160+ files). This round addresses **4 confirmed operational bugs**, **4 medium bugs**, and **2 performance issues**, and documents the review items accepted as-is. Numbering continues the `FIXES_v1.1.15.md` series (through #47).

## 1) Critical and operational bugs

| # | File | Problem | Fix |
|---|------|---------|-----|
| 48 | `task/TaskManager.TillTask.tick` | **Planting never happened after a successful till**: `st` was read before `useHoe` and not re-read after it, so the second check `!st.is(FARMLAND)` compared against the old soil (GRASS/DIRT) and entered the `skipped++` branch for every freshly tilled cell — `till_and_plant` tilled but never planted | `st = level.getBlockState(pos)` re-read immediately after the hoe interaction, with a comment explaining why the snapshot goes stale |
| 49 | `tool/impl/LightAreaTool` | The final guard `if(slot < 0)` forgot the `-2` (main hand) convention that the first check honored — so "light up this area" failed while the torch was in hand | `if(slot < 0 && slot != -2)` with a comment documenting the convention |
| 50 | `tool/impl/GatherBlockTool` | `break` on the first `mine` failure abandoned all `hiddenHits` after one failed exposed hit (protection/water/anchor) even though they were mineable via the access path | The failed candidate is skipped and the loop continues with a **3-consecutive-failure** budget, then stops; the failure counter resets on every success |
| 51 | `core/SkillLibrary` + `state/CompanionWorldData` + `entity/CompanionEntity.die` + `state/CompanionRespawnController` | `tool_skills` statistics (`ok=/fail=`) were saved under the **companion's UUID**, which changes on every respawn — effectively wiped after each death despite remaining orphaned in the world data | Ownership snapshot: `die()` records `getToolSkills(companion)` under a `tool_skills_snapshot` key in the **owner's** record; `restoreFromOwnerSnapshot` (public, with a shared parser `parseInto`) builds the new companion's store on respawn and pins it under the new UUID; empty snapshots are ignored, so first spawns are unaffected |

## 2) Medium bugs

| # | File | Problem | Fix |
|---|------|---------|-----|
| 52 | `tool/impl/BuildPatternTool` | `slot == -2` ignored in the generic name-resolution path → `FAILED block_not_found_in_inventory_or_registry` while the material was in hand | `-2` reads the main hand like the other tools |
| 53 | `tool/impl/BuildPatternTool.mostAbundantBlockId` | The main hand was never checked; an equipped builder on a task carries all of its material in hand → `no_building_block_in_inventory` | The hand joins the candidate list before the inventory slots |
| 54 | `entity/CompanionEnderpearl.onHit` + `entity/CompanionEntity` | The ender pearl teleported the companion without stopping the sprint: the first `aiStep` after landing measured the whole jump against the pre-teleport anchor → a massive `addFoodExhaustion(moved*0.1)` (30 blocks = 3.0 exhaustion in one hit) | `living.setSprinting(false)` after the teleport + a new `resetSprintExhaustionAnchor()` on `CompanionEntity` (the owner-follow path already stopped the sprint but never reset the anchor) |
| 55 | `api/PlayerLikeState.setFoodLevel` | Clamping `saturation` to the new `food` deviates from vanilla: `FoodData.setFood(int)` only changes the food — external writes were destroying stored saturation | The clamp removed on both paths (entity/tag); only the physical 0..20 bound remains. The internal `CompanionEntity.setFoodSaturation` constant is unchanged |

## 3) Performance

| # | File | Problem | Fix |
|---|------|---------|-----|
| 56 | `core/CompanionKernel.hasImmediateThreat` | Three AABB sweeps (creepers 7, enemies 12, owner 16) every tick during active work | A 10-tick result cache for the expensive checks; O(1) checks (`getTarget`/`getLastHurtByMob`) stay live without a cache so a fresh strike is seen on the same tick; `clearScanCache` from `CompanionCleanup.purge` |
| 57 | `state/CompanionRespawnController.hasLiveCompanion` | `getAllLevels × getAllEntities` every 20 ticks per player (~100k iterations on large servers) | A light live index `owner → (companionId, dim)` filled from `setOwner` and cleared from `purge`; the check is now O(#levels) map lookups; stale entries self-clean while the "loaded entities only" behavior is preserved |
| 58 | `entity/CompanionEntity.say` (owner offline) | An unbounded `LOG.info` record: `AgentEngine` failure loops could produce lines every tick | A 200-tick (10 second) rate limit per companion, with the map cleaned in `purge` |

## 4) Documentation (review notes)

| # | Location | Improvement |
|---|----------|-------------|
| 59 | `api/NPCMindAPI.grantExperience` | Documented that the `isSameThread` guard silently returns `false` off the server thread, and that host mods must schedule the call via `server.execute(...)` |

## 5) Review items accepted as-is (inspected, no change)

- **#27 `attackTicker` starts at the maximum**: intentional — parity with a player striking immediately when engagement starts; changing it alters combat feel and needs in-game measurement.
- **#29 `collectNearbyDrops` has no visible suction**: the pickup is instant on the same tick, so no displacement is ever seen; any "suction movement" requires a deferred task and a visual path — out of repair scope, useful as a future idea.
- **#31/#32 safety of `checkpointResult(null)` and `takeLastResult`**: verified correct as-is.
- **#38 `HarvestTask` and `owner_offline`**: an immediate `DENIED` without incrementing `skipped` is the correct behavior.
- **#7 note on sequential door construction (`buildWithFinisher`)**: verified — the door comes after the walls, in the right order.

## 6) Verification performed

- ✅ `scripts/static_check.py`: 160 main files — 0 errors.
- ✅ `tools/verify_repair_regressions.py`: 272 checks, 0 errors (version bumped to 1.1.16).
- ✅ `tools/verify_mit_release.py`: 0 errors.
- ✅ `tools/source_audit.py`: 59 tools, 0 errors.
- ⚠️ A real `clean test build` requires an actual JDK 17 (documented in the README, item 47 of the v1.1.15 round) — to be run locally before publishing, followed by the farming test (`till_and_plant` on grass): it must now plant immediately after tilling.
