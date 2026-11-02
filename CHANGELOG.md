# Changelog

All notable **API- and packaging-visible** changes to NPC Mind. Internal correctness fixes
that do not change the stable surface are summarized under "Fixed"; the exhaustive
item-by-item repair log lives in `docs/FIXES_*.md`.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
Semantic Versioning 2.0 as scoped in [`docs/API_STABILITY.md`](docs/API_STABILITY.md).

## [1.3.1] — Security and concurrency hardening

### Fixed
- Public entity/world helpers now reject off-thread calls; player-like state readers return neutral snapshots without reading entity NBT from a worker thread.
- The local command engine now uses at most four daemon workers and a bounded queue of 64 commands. Saturation rejects work with a clear response instead of creating unbounded threads or running blocking work on the server thread.
- Cancelled queued command tasks are removed from the executor queue.
- The source audit now checks common Java URL, socket, HTTP/WebSocket, and popular client APIs, plus common dynamic class-loading/reflection paths; fixture tests cover detections and benign URI/comment/string cases.
- Added behavioral tests for server-thread guard policy, bounded-worker rejection, and tool argument validation.
- Eating race with vanilla `completeUsingItem`: when the companion loses the tick race, only custom food/saturation numbers are applied. `finishUsingItem` is no longer called a second time, so golden-apple / honey-bottle status effects and container remainders cannot double.
- `AuditLog`: define missing `MAX_LOCKS` constant (256) so the idle lock-pruning path compiles.

### Notes
- Java API signatures are unchanged. Off-thread world/entity access, which was outside the documented contract, now fails safely.
- Static source scanning is a policy gate, not a runtime sandbox; dependencies and native code still require separate review.
- README now states the mandatory host-mod dependency requirement explicitly (library is not a Forge mod; host must use jarJar or a mandatory `mods.toml` dependency).

## [1.3.0] — Entity-agnostic developer framework

### Added
- Host-defined generic entity tools can be registered, routed from deterministic English or Arabic phrase aliases, and submitted against any live `LivingEntity` through `NPCMindAPI` without constructing the bundled `CompanionEntity`.
- Autonomous/unowned NPC tools may receive a null controller; host mods retain responsibility for ownership, authorization, tool behavior, AI/provider selection and tick scheduling.
- Alias registration validates fixed JSON arguments against tool schemas, rejects duplicate aliases and resolves the most specific phrase first.
- Unit tests cover generic tool/intent registration, command resolution and schema rejection.

### Notes
- Additive API release. The bundled `CompanionEntity` and existing `AITool`/`NPCMindLibrary` integration remain available as an optional ready-made companion runtime.
- Generic routing is deterministic and local, not an embedded language model. A host may connect its own planner/provider and call `executeEntityTool` with validated arguments.

## [1.2.0] — Library packaging

### Breaking (packaging, not the Java surface)
- NPC Mind ships as a **developer library**, not a Forge mod: no `@Mod` entrypoint, no
  `META-INF/mods.toml`, no `pack.mcmeta`, no run configurations. Dropping the jar into
  `mods/` alone does nothing.
- Activation is explicit through the new `NPCMindLibrary` facade:
  `bootstrap(IEventBus)` → `registerIntegration()` → `registerClient(IEventBus)`.
- The COMMON config file is generated as `<hostmodid>-common.toml` instead of
  `npcmind-common.toml` (keys, types and defaults unchanged; migrate values once).
- `ForgeIntegration` / `ClientRendering` are no longer annotation-scanned; the facade
  registers them explicitly.

### Added
- `NPCMindLibrary` bootstrap facade — idempotent, dist-guarded client step.
- `withJavadocJar()` on the Gradle build; the publication now carries sources + Javadoc.
- `com.yaseen.npcmind.api.@Internal` marker: every public type outside the stable surface
  is marked, enforced by `tools/check_api_surface.py`.
- `@NotNull` / `@Nullable` contract annotations on the whole `NPCMindAPI` surface
  (`org.jetbrains:annotations`, compile-only, `provided` in the POM).
- Published POM declares Minecraft/Forge and the annotations as `provided` dependencies.
- Jar manifest attributes (`Implementation-*`, `Specification-*`, `Automatic-Module-Name`).
- `gradle.properties` as the single source of truth for `mod_version`, `minecraft_version`,
  `forge_version` and dependency versions.
- Host-mod example kit: `docs/examples/HostModExample.java`, `mods.toml` template,
  `build.gradle.snippet` (fg.deobf + jarJar patterns).
- CI workflow (`.github/workflows/build.yml`) and `jitpack.yml` (JDK 17) for JitPack builds.
- Developer governance docs: `API_STABILITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`,
  `SECURITY.md`.

### Removed
- `src/main/resources/META-INF/mods.toml` and `pack.mcmeta` from the library jar.
- `minecraft { runs { … } }` / `copyIdeResources` dev-run configuration.

### Docs
- All round logs under `docs/` are maintained in English and summarize implementation and
  verification history. The bundled wrapper's Apache license text is kept at
  `gradle/wrapper/LICENSE-APACHE-2.0.txt`.

## [1.1.19] — Review round fixes (items 60–68)

### Fixed
- `CompanionWorkBudget`: rotation-queue entries now carry a `lastSeenTick` heartbeat and
  self-expire after 60 ticks, so a vanished requester can no longer pin the queue head and
  starve every other companion.
- `PlayerLikeState`: `setFoodLevel` no longer clamps a stored saturation higher than the new
  food level, restoring vanilla `FoodData.setFood` semantics.
- `CompanionEntity`: NBT loading registers the companion in the live-by-owner directory, and
  ownership transfer unregisters the old owner — duplicate-spawn protection can no longer be
  bypassed by save/load or `/npcmind transfer`.
- `FollowOwnerGoal` teleport re-anchors sprint exhaustion (no phantom exhaustion spike on the
  first frame after a rescue teleport).
- `CriticEngine` allow-list matches the real `SUCCESS already_have=N output=X` literals of
  `CraftItemTool` (the old `already_have_output` token never matched the actual output).

## [1.1.18] — Feature completion (items 48–59)

### Added
- Built-in tools: `build_blueprint` (schematic-style placement), `inspect_ai` (goal-trace
  introspection backed by `GoalTrace`), `throw_ender_pearl` exposure hardening via
  `CompanionEnderpearl`.

### Fixed
- `TillTask` re-reads its state each pass; `LightAreaTool` accepts slot `-2`;
  `GatherBlockTool` gained a consecutive-failure budget of 3.

## [1.1.17] — New capability wave

### Added
- `CompanionWorkBudget` (server-tick fair-share leasing with MSPT shedding) and
  `CompanionAlertManager` (team-scoped alerts) — internal controllers surfaced through the
  autonomy pipeline, not through the stable API.
- `BuildBlueprintTool`, `InspectAITool` + `GoalTrace`, ender-pearl throw tool with
  cancellable wind-up and cooldown.

## [1.1.16] — Tool hardening

### Fixed
- Till loop re-reads tile state; light-area slot handling; gather consecutive-failure budget.
- No stable-surface change.

## [1.1.15] — API widening (items 1–47)

### Added
- Entity-agnostic tool API: `EntityAITool` + `EntityToolRegistry` and
  `NPCMindAPI.executeEntityTool` for host-owned actors.
- `PlayerLikeState` / `PlayerLikeExperience` / `NPCMindActions` helpers exposed through
  `NPCMindAPI` (read, tick, exhaustion, food, XP).
- `ToolPack` opt-in pack selection: `enableOnlyPacks`, `enableOnlyTools`, `setToolEnabled`,
  `enabledToolNames`.

## [1.1.14] — Stabilization pass

### Fixed
- 23 correctness fixes across navigation, permission checks, task lifecycle and tool
  postcondition verification. No stable-surface change.
