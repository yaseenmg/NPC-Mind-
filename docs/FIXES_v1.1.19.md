# NPC Mind v1.1.19 — Review Round Fixes (Items 60–68)

Fourth external review round against v1.1.18 (full 191-file source read, re-verification of
fixes 48–59, plus a targeted finding on the new work-budget queue). Every finding was
reproduced against the source before fixing. Items reviewed and deliberately left unchanged
are documented at the end.

## 🔴 Critical

### 60. CompanionWorkBudget — a vanished requester could pin the rotation head forever
**Files:** `autonomy/CompanionWorkBudget.java`, `core/CompanionKernel.java`,
`src/test/java/com/yaseen/npcmind/autonomy/CompanionWorkBudgetTest.java` (new)

The rotation queue held a bare companion UUID until `release`/`purge` ran. A companion that
entered the queue and then stopped reaching `tryLease` — autonomy mode changed, a long task
kept the kernel on the work path, exploration disabled, entity removed without the cleanup
hook — left its UUID stranded in the queue. Because the grant requires being at the head,
every companion behind the stranded entry starved indefinitely even with capacity available.

Fix, exactly as the review proposed:
- Each queue entry now carries a `lastSeenTick` heartbeat, refreshed on every `tryLease`
  request (in place — the FIFO position is never reordered).
- `evictStaleLocked` runs once per server tick inside `tryLease` and evicts every entry that
  has not requested a lease for `ENTRY_TTL_TICKS = 60` (3 s; generous against the 5-tick
  autonomy throttle, fast enough that a vanished companion cannot starve the queue).
- Waiting for a turn and holding a grant stay separate states: a temporary rejection
  (capacity, not-your-turn, MSPT shedding) never leaves a permanent claim — under shedding
  the queue simply self-expires and rebuilds when capacity returns.
- `CompanionKernel` now releases the slot immediately when a long task starts (work-active
  path) instead of waiting out the TTL. Death/removal keeps going through
  `CompanionCleanup.purge` as before.
- New pure `RotationQueue` class makes the policy unit-testable without a Minecraft server;
  `CompanionWorkBudgetTest` covers the exact review scenario (A requests, B waits, A stops
  requesting → B gets the lease) plus heartbeat ordering, TTL scoping, release rotation and
  the 32-entry bound.

### 61. PlayerLikeState — `setFoodLevel` still clamped saturation (item 55 incomplete)
**File:** `api/PlayerLikeState.java`

Item 55 removed the clamp at the `setFoodLevel` call site, but `writeState` still routed every
write through `clampSaturation(saturation, food)` — and so did `read`, `tick` and
`addExhaustion` on the read side. Writing food=2 over a saved saturation=10 silently collapsed
the saturation to 2, which vanilla `FoodData.setFood(int)` never does (it changes only the
food value). `clampSaturation` now applies the physical 0..20 bound only, on every read and
write path; `consumeFood` keeps its explicit `min(nextFood, …)` because that is the vanilla
`eat()` parity rule for eating, not a food-write side effect.

### 62. NBT load did not register the companion in the live-companion directory
**File:** `entity/CompanionEntity.java` (`readAdditionalSaveData`)

Loading a companion from disk assigned `owner` directly, bypassing `setOwner`, so
`CompanionRespawnController.LIVE_BY_OWNER` stayed empty for that owner after a server restart
or chunk reload. If a pending respawn from before the restart was still saved, the auto
respawner saw `ref == null` → "no live companion" and spawned a duplicate while the loaded
companion was alive. `readAdditionalSaveData` now routes through `setOwner(tag.getUUID(OWNER_TAG))`,
which registers the directory entry on the server side (client-side sync is unaffected — the
register call is already guarded by `!level().isClientSide()`).

### 63. Ownership transfer left the old owner registered as owning a live companion
**Files:** `entity/CompanionEntity.java` (`setOwner`), `ForgeIntegration.java` (transfer)

`/npcmind transfer` called `setOwner(newOwner)`, which registered the new owner but left the
old owner's `LIVE_BY_OWNER` entry pointing at the same companion. The entry was only cleaned
up lazily, on the next `hasLiveCompanion` probe. `setOwner` now detects an ownership change
(`previous != null && !previous.equals(id)`) and unregisters the companion from the directory
before re-registering it under the new owner. Fixing it inside `setOwner` (instead of only at
the transfer call site) covers every current and future ownership-change path.

## 🟠 Medium

### 64. FollowOwnerGoal.teleportTo did not reset the sprint-exhaustion anchor
**File:** `entity/CompanionEntity.java` (`FollowOwnerGoal.teleportTo`)

The forced follow recovery (>48 blocks) moved the entity but left `lastSprintExhaustionPosition`
stale, so the first `aiStep` measured the entire teleport as sprint distance and charged an
unearned exhaustion spike. `teleportTo` now calls `mob.resetSprintExhaustionAnchor()`, matching
the ender-pearl path (item 54).

### 65. CriticEngine allow-list never matched the real craft success literals
**Files:** `core/CriticEngine.java`, `tool/impl/CraftItemTool.java` (lines 104, 723)

The allow-list checked the single token `already_have_output`, but the tool actually emits
`SUCCESS already_have=N output=X` (space-separated), and `SUCCESS crafting_table_already_nearby`
was not listed at all. The critic therefore judged a genuinely successful
"already have the item" craft as `no_world_progress`: `SkillLibrary` recorded a false tool
failure, the audit log showed `VERIFY FAILED`, and repeating an already-satisfied request
could produce a false "I couldn't finish that" message. The allow-list now matches
`already_have` (which also covers the internal `already_have_output` reason token) and
`crafting_table_already_nearby`.

### 66. CompanionAlertManager — iteration-unsafe pruning and over-broad team radius
**File:** `advanced/CompanionAlertManager.java`

Two review findings, both fixed:
- **Radius scoping:** the extended 48-block assist radius applied to any helper with a
  non-blank teamId, even for alerts raised by a member of a *different* team. `Alert` now
  stores the reporter's `teamId`, and the extended radius is granted only when the helper's
  teamId equals the reporter's teamId (both non-blank). Solitary companions keep the 24-block
  radius in all cases.
- **Pruning:** expired/dead alerts were removed via `map.remove` during iteration. That is
  safe for `ConcurrentHashMap` but can skip entries the iterator already produced. The scan
  now collects the entries to prune and removes them after the loop with the same
  conditional two-argument `remove(key, value)` semantics.

### 67. hasHealingItems counted every edible item as reliable healing
**File:** `advanced/CombatAssessor.java`

Any edible stack — including rotten flesh — raised the accepted-loss budget by 0.10, making
the assessor over-confident. Food is now classified: golden/enchanted golden apples always
count as combat healing; other food counts only when it restores at least 3 nutrition and
carries no `MobEffectCategory.HARMFUL` effect (excludes rotten flesh, spider eyes, poisonous
potatoes, raw chicken, pufferfish and other snack-tier/risky food).

## ✅ Verified — no change required

- **Alert raise-side ally filter, WorkBudget try/finally release paths, threatScore NPE
  safety, GoalTrace signature-only logging, ToolSelectionRouter word boundaries, Arabic
  normalization, named-attack regex bounds, single-pass tool routing** — re-checked in this
  round, all sound (review errors #7/#8 and the clean-filters list confirmed).
- **CombatAssessor.rawDamage static estimates (review note 2):** kept intentionally
  conservative; documented in-source that the numbers are a sizing input for the assess
  budget, never a win guarantee.
- **GoalTrace memory-only log (review note 4):** intentional anti-bloat design; the
  `inspect_ai` tool already exports a live snapshot on player demand.
- **SkillLibrary death snapshot with no prior tool use (review error #5):** correct behavior —
  nothing has been learned yet, so nothing is restored; the owner-keyed snapshot path itself
  was re-verified end to end (die → save → respawn → restore → persist).

## Verification

- `tools/verify_repair_regressions.py` — extended with 28 new v1.1.19 markers (items 60–68);
  300 checks total, 0 errors.
- `tools/verify_mit_release.py` — pass (MIT metadata, library packaging, and version strings
  consistent at 1.1.19).
- `tools/source_audit.py` — pass (164 Java files, 60 tools registered, executor wiring intact).
- `./gradlew test` — `AITickThrottleTest` + new `CompanionWorkBudgetTest` (6 tests, including
  the review-requested stuck-rotation scenario).
