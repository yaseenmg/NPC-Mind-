# NPC Mind — Developer Library for Forge 1.20.1

**NPC Mind 1.3.1** is a developer library for reusable NPC intelligence on Minecraft Forge 1.20.1. **It is not a mod**: the jar has no `@Mod` entrypoint, no `mods.toml` and no `pack.mcmeta` — your mod owns the mod identity and activates the library with explicit calls. Use the generic entity API for your own NPC types, or opt into NPC Mind's ready-made `CompanionEntity` runtime.

**Project maintainer:** Yaseen.

It provides memory, observations, planning, validated tool execution, navigation, tasks, combat, crafting, farming, building, containers, exploration, and persistent state. It runs without a hosted AI server, remote inference API, or API key.

## Included

- Reusable `CompanionEntity` base NPC with persistent state and inventory.
- `AgentEngine` for local route, execute, and verify cycles.
- `ToolRegistry`, `AITool`, and `ToolContract` for registering and validating tools.
- Entity-agnostic `PlayerActionRules` exposed through `NPCMindAPI` for server-side reach and collision-line-of-sight checks on any `LivingEntity`.
- Optional `PlayerLikeState` and `PlayerLikeExperience` APIs for persistent food/exhaustion/regeneration/XP emulation on any `LivingEntity`; host mods own their tick scheduling and synchronization.
- Bounded environmental survival reflexes: fall-detection water-bucket clutch, safe source-water bucket filling, local visual bed-sleep simulation, and stuck-search work limits.
- Permission-gated ender-pearl throw tool with a cancellable six-tick wind-up and cooldown.
- Background AI throttling for observation, autonomy, routine and suggestion passes; emergency survival/combat remain unthrottled.
- Bounded episodic memory and persistent world-NBT storage.
- Server-thread execution, deterministic navigation, survival reflexes, combat, crafting, farming, building, container operations, exploration, and structure search.
- Configurable pickup, memory limits, respawn, and following behavior.
- Local command routing with honest clarification for unsupported requests.
- Entity-agnostic tool registry and deterministic command intents that operate on any host-owned `LivingEntity`, independent of `CompanionEntity`.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.10 or newer in the 47.x line
- Java 17
- A host mod (the library cannot be dropped into `mods/` alone)

### Mandatory dependency for host mods

NPC Mind is a **library**, not a Forge mod. Forge will never detect it as a required
mod by itself. Any host mod that uses this library **must** declare the dependency
explicitly:

1. **Dev + compile**: always use `implementation fg.deobf('com.yaseen:npc-mind-forge-1.20.1:1.3.1')` (or the JitPack / mavenLocal coordinates you publish).
2. **Production (recommended)**: enable `jarJar` and embed the reobfuscated library inside your mod jar so players install only one file.
3. **Production (separate jars)**: if you ship the library as its own file in `mods/`, your host mod's `mods.toml` must list a mandatory dependency on whatever mod-id you give the library bundle, for example:

```toml
[[dependencies.your_host_mod_id]]
    modId="npcmind"
    mandatory=true
    versionRange="[1.3.1,)"
    ordering="AFTER"
```

Without one of these two production paths the library classes will be missing at runtime and the host mod will crash on load.

## Quick start — add the library to your mod

1. Publish the library locally once (`./gradlew publish` inside this tree), then depend on it from your host mod:

```gradle
implementation fg.deobf('com.yaseen:npc-mind-forge-1.20.1:1.3.1')
```

2. Bootstrap from your `@Mod` constructor (complete template in [`docs/examples/HostModExample.java`](docs/examples/HostModExample.java)):

```java
IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
NPCMindLibrary.bootstrap(modBus);      // entities, menus, attributes, config
NPCMindLibrary.registerIntegration();  // /npcmind commands, tick drivers, chat routing
NPCMindLibrary.registerClient(modBus); // renderers + screens (no-op on dedicated servers)
```

3. Ship the reobfuscated library jar inside your mod with `jarJar`, or distribute it as a declared runtime dependency. See [`docs/examples/host-mod/build.gradle.snippet`](docs/examples/host-mod/build.gradle.snippet).

**Migrating from 1.1.x (which shipped as a mod)?** See [`docs/LIBRARY_CONVERSION_v1.2.0.md`](docs/LIBRARY_CONVERSION_v1.2.0.md) — the `@Mod` class, `mods.toml` and run configurations were removed; behavior and the `npcmind:` registry namespace are unchanged.

### Consumption channels

| Channel | Coordinates | Notes |
|---------|-------------|-------|
| Project-local repo (default) | `com.yaseen:npc-mind-forge-1.20.1:1.3.1` from `build/repo` after `./gradlew publish` | Works today, zero setup |
| JitPack (recommended for a public release) | `com.github.YOUR_GITHUB_USERNAME:NPCMind:v1.3.1` from `https://jitpack.io` | Builds from a GitHub tag; `jitpack.yml` pins JDK 17 for the Gradle 8.1.1 wrapper |
| `mavenLocal()` | `./gradlew publishToMavenLocal` | For quick local host-mod development |
| Maven Central / GitHub Packages | — | Optional later step; requires account and signing setup |

Whichever channel you pick, consume the artifact with `fg.deobf(...)` at dev time (see [`docs/examples/host-mod/build.gradle.snippet`](docs/examples/host-mod/build.gradle.snippet)). The publication carries sources and Javadoc JARs, so signatures resolve directly in your IDE.

### Generic NPC developer API

The generic path does not require `NPCMindLibrary.bootstrap` or NPC Mind's companion entity.
Register an `EntityAITool`, add validated phrase routes, then submit a command from your own
server-side authorization/tick handler:

```java
import com.yaseen.npcmind.api.EntityAITool;
import com.yaseen.npcmind.api.NPCMindAPI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import org.jetbrains.annotations.Nullable;

NPCMindAPI.registerEntityTool(new EntityAITool() {
    public String name() { return "example:follow_owner"; }
    public String description() { return "Follow the controller"; }
    public String schema() { return ""; }
    public String execute(LivingEntity actor, @Nullable ServerPlayer controller, String args) {
        if (!(actor instanceof PathfinderMob mob) || controller == null) return "FAILED unsupported_actor";
        mob.getNavigation().moveTo(controller, 1.1D);
        return "STARTED follow";
    }
});
NPCMindAPI.registerEntityIntent(new String[]{"follow me"},
        "example:follow_owner", "{}", "host follow intent");
String result = NPCMindAPI.submitEntityCommand(myNpc, player, "follow me");
```

Tool names/aliases share a process-wide library registry, so use namespaced unique identifiers.
Authorization and ownership checks belong to the host mod; tool calls must run on the logical
server thread. The route matcher is deterministic and does not include an LLM. Hosts may run their
own planner and call `executeEntityTool` with schema-validated arguments. See
[`docs/DEVELOPER_API.md`](docs/DEVELOPER_API.md) for the full contract.

### API stability

Host mods must only use the stable surface: `com.yaseen.npcmind.api`, the tool contract trio (`AITool`, `ToolContract`, `JsonArgs`) and `NPCMindLibrary`. Everything else is marked `@Internal` and may change without notice. The full policy — versioning, deprecation, nullability contract, enforcement gates — is [`docs/API_STABILITY.md`](docs/API_STABILITY.md); API-visible changes per release are in [`CHANGELOG.md`](CHANGELOG.md).

## Building

```bash
./gradlew clean build
```

**Run Gradle itself on JDK 17.** The project's Java toolchain compiles with Java 17, but the Gradle
daemon runs on whatever `java` is on your `PATH`. Gradle 8.1.1 (the pinned wrapper version, kept for
ForgeGradle 6 compatibility) cannot start on Java 21+ and fails with `Unsupported class file major
version 65`. Point `JAVA_HOME` at a JDK 17 installation before building:

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-17
# Windows (PowerShell)
$env:JAVA_HOME = "C:\path\to\jdk-17"
```

The library jar is written to `build/libs/npc-mind-1.3.1.jar` after a successful build. The Maven publication coordinate is `com.yaseen:npc-mind-forge-1.20.1:1.3.1`; `./gradlew publish` publishes the jar, sources and Javadoc to the project-local repository under `build/repo` (not to a public repository). All versions live in `gradle.properties` — bump `mod_version` there, keep `NPCMindAPI.API_VERSION` and the pinned version in `ApiCompatibilityTest` in sync, and record the change in `CHANGELOG.md`.

## Verification

```bash
./gradlew clean test build
python tools/check_api_surface.py
python tools/source_audit.py
python tools/verify_repair_regressions.py
python tools/verify_mit_release.py
```

The same set runs on every push and pull request through GitHub Actions (`.github/workflows/build.yml`). Contributing? Start with [`CONTRIBUTING.md`](CONTRIBUTING.md); report security issues privately via [`SECURITY.md`](SECURITY.md).

Static checks do not replace a Forge compile or an in-game test. A successful release still requires Java 17, access to the configured Gradle distribution and Forge Maven repositories, and a Forge 1.20.1 test world.

## Movement and interaction hardening

- Each block/item interaction receives its own `FakePlayer` context, while an online owner's profile remains available to permission/claim integrations.
- Follow and combat paths pass through bounded, loaded-corridor-aware navigation requests; following replans at a controlled cadence and no longer auto-jumps while sprinting.
- Stuck recovery uses one corrective movement mechanism at a time; combat pursuit checks destination changes and rate-limits navigation requests through the shared lease.
- Mining respects reserved tools, rejects zero-speed tool scoring, and checks whether a safe stand position can actually see a target before walking toward a block hidden behind a non-natural obstruction.
- The visibility edge tolerance, Fire Aspect timing, Arabic sentence splitting, and combat strafe timing were aligned with their intended behavior.

The static regression script checks source markers, not runtime behavior. A Java/Forge build and in-game tests remain mandatory before publishing a binary release.

## Built-in commands

- `/npcmind spawn` creates a companion owned by the command player.
- `/npcmind ask <message>` sends a command to the nearest owned companion.
- `/npcmind follow true|false`, `/npcmind come`, and `/npcmind stop` control following and current work.
- `/npcmind inventory` opens the companion inventory GUI when nearby.
- `/npcmind name <name>` changes the persistent display name.
- `/npcmind transfer <online-player>` transfers ownership to an online player in the same dimension.
- Chat control uses `!npc <command>` by default; ordinary messages beginning with other text are not intercepted.

Spawning searches for a collision-free standable position beside the player, and blocks manual spawning while an auto-respawn is already pending. Following uses bounded navigation and may recover a companion more than 48 blocks behind by trying safe, loaded positions at the owner's current Y-level (including caves/buildings) before falling back to the surface. Cross-dimension travel remains the responsibility of a host mod.

## Using NPC Mind from another mod

Extend the reusable base NPC or integrate a custom NPC with the public runtime:

```java
public final class MyNpc extends CompanionEntity {
    public MyNpc(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }
}
```

To expose a custom capability, implement `AITool` and register it through `NPCMindAPI`. Tool contracts reject malformed arguments before execution. World access and mutation must occur on the server thread, and tools should verify actual postconditions before reporting success. See [the developer API guide](docs/DEVELOPER_API.md).

## Local interactions

Block placement uses vanilla item interaction, mining progresses over ticks with tool-dependent speed and drops, and farming uses vanilla item interactions. The controllers check loaded areas, reach, line of sight, and world changes before committing inventory effects. Combat has a final shared line-of-sight guard, so direct attack calls cannot bypass wall checks. Sprinting no longer causes automatic repeated jumping; jumps are reserved for actual movement collisions. Mining can clear tagged natural terrain blockers before the requested block, while refusing to automatically mine arbitrary player-built walls or containers. No external inference or hosted service is required.

**Player identity limitation:** `CompanionEntity` remains a normal server-side mob, not a `ServerPlayer` inserted into the server player list. This avoids corrupting login, connection, player-list, and respawn lifecycle invariants. NPC Mind emulates selected player rules through server-side interaction contexts. The new generic reach/visibility helpers work with any `LivingEntity`; the full owner-gated command runtime and companion inventory still require the NPC Mind companion integration unless a host mod supplies its own adapter.

## Project structure

- `agent/` — orchestration, memory, decisions, and tool runtime.
- `tool/` — contracts, registry, validation, and Minecraft tools.
- `memory/` and `state/` — persistent memory, skills, permissions, goals, and world state.
- `task/`, `autonomy/`, and `advanced/` — execution, navigation, survival, combat, and observations.
- `entity/` — reusable NPC entity and inventory/state integration.
- `core/`, `crafting/`, and `security/` — snapshots, recipes, inventory operations, and action safety.

## License

NPC Mind source material for which the maintainer holds the necessary rights is licensed under **MIT**; see the root `LICENSE`. The Gradle Wrapper JAR retains its Apache 2.0 terms; the license text is included at `gradle/wrapper/LICENSE-APACHE-2.0.txt`.


## Survival progression and resource collection

- Added a deterministic `SURVIVAL` autonomy mode for starter progression: wood/planks, crafting table, pickaxe upgrades, sword, furnace, bed, food cooking, torches and an initial iron-smelting step. Enable it with `survival mode` while autonomous routines are desired.
- Added counted animal hunts (`kill 3 sheep`, `hunt 2 cows`) chained from actual `LivingDeathEvent` callbacks, with temporary short-range pickup so vanilla drops are collected between targets.
- Added bed crafting through the live crafting recipe manager. It requires three wool items of one color and three planks; if wool is missing, the planner hunts sheep and waits for their normal vanilla drops instead of manufacturing items.
- Added named target commands for common entities, persistent teaching triggers, animal-species world observations, and a time-bounded recent-attacker check so an old attacker does not indefinitely interrupt work.
- Static regression script now covers these new code paths. The current 1.3.1 build and verification status are recorded in `BUILD_RESULT.md`; in-world behavior still requires a manual test world.


## Task continuity and building fixes

- Added a bounded pending-craft continuation so real mining, hunting, furnace jobs, and crafting-table placement can finish before the requested recipe is attempted again.
- Validated bed color against three wool items of that exact color; the Survival selector now prefers a color already in inventory (or the nearest sheep's color) rather than always requesting a white bed.
- Survival mode tries to place a crafted bed using the real block-item placement path and avoids repeatedly crafting one when a bed block is already nearby. This does not make a mob entity sleep or set a player's respawn point.
- Named-target routing accepts articles such as “attack a zombie” and more multi-word mob names; the matching boundary handles Arabic names instead of relying on an ASCII word boundary.
- Buried natural resources remain candidate targets so the existing access-path logic can reach them.

Static checks are not a substitute for a Forge compile or an in-world test. This source package should be built with Java 17 and Gradle/Forge dependencies available, then checked against the verification limits documented in `docs/MIT_RELEASE_AUDIT_v1.1.13.md`.

- House patterns enforce a doorway-compatible footprint; if an unreserved real door is already available, its placement is queued after the shell. If no door is available, the task result explicitly reports that the doorway remains open. Automatic door crafting, window placement, and hostile-proof validation are not yet implemented.


## Additional survival behavior and API extensions

- Added a bounded water-bucket clutch reflex for fast falls. It checks loaded chunks, reach, replaceability, permission/claim placement checks, and converts actual bucket items. It is an emergency heuristic, not a guarantee against every fall or dimension.
- Added local visual sleep at an available unoccupied nearby bed at night. This does not skip world time, set a respawn point, or turn the companion into a `ServerPlayer`; unreachable beds time out instead of owning movement forever.
- Improved stuck-recovery work budgeting so at most four candidate columns are evaluated per tick and callers can supply avoided stand positions.
- Added a `fill_bucket` tool restricted to standalone source-water blocks with a line-of-sight/reach check, and a permission-gated `throw_ender_pearl` tool with real projectile creation and a cancellable wind-up.
- Added per-companion throttling for non-critical controller passes. Combat and emergency survival reflexes remain full rate.
- Exposed `PlayerLikeExperience` / `PlayerLikeState` for host mods, plus `manage_player_state` and opt-in `collect_xp` companion tools. See `docs/DEVELOPER_API.md`.

The 1.3.1 Forge compile, JUnit tests, static verification gates, and JAR build have passed. Maven publication and in-game behavior still require separate release and gameplay testing.


## Documentation map and reading order

Read documents in this order: start with this README; use the developer and generic-NPC guides for integration; consult API stability and security before publishing; then use contributor/build guidance; finally consult the historical release records when investigating older behavior. The README is the concise entry point; focused references and historical logs remain separate so important policy is not buried in one oversized file.

| Priority | Markdown file | What it covers |
|---|---|---|
| 1 | `README.md` | Project overview, quick start, build/verification instructions, and links to all other guides. |
| 2 | `docs/DEVELOPER_API.md` | Stable API contracts, library consumption, and integration examples. |
| 2 | `docs/GENERIC_NPC_FRAMEWORK.md` | Entity-agnostic tool registration, command routing, planner boundary, and host responsibilities. |
| 2 | `docs/API_STABILITY.md` | Stable surface, nullability, semantic versioning, deprecation, and compatibility gates. |
| 3 | `SECURITY.md` | Security scope, reporting process, response targets, and supported versions. |
| 3 | `CONTRIBUTING.md` | Java/Gradle setup, required checks, coding conventions, and contribution workflow. |
| 3 | `docs/ARCHITECTURE_OVERVIEW.md` | Package boundaries and major runtime subsystems. |
| 3 | `CHANGELOG.md` | API- and packaging-visible changes by release. |
| 3 | `BUILD_RESULT.md` | Build artifacts and verification results for NPC Mind 1.3.1. |
| 4 | `AUTHORS.md` | Project maintainer/author. |
| 4 | `COPYRIGHT.md` | Copyright ownership summary and license boundary. |
| 5 | `docs/LIBRARY_CONVERSION_v1.2.0.md` | Historical transition from the original standalone mod packaging to a dependency library. |
| 5 | `docs/FEATURES_v1.1.17.md` | Historical notes on the six feature systems added in that release. |
| 5 | `docs/FIXES_v1.1.14.md` | Detailed fixes and verification notes from v1.1.14. |
| 5 | `docs/FIXES_v1.1.15.md` | Detailed logic, tooling, performance, and repair notes from v1.1.15. |
| 5 | `docs/FIXES_v1.1.16.md` | Detailed fixes and verification notes from v1.1.16. |
| 5 | `docs/FIXES_v1.1.18.md` | Historical source-documentation cleanup and verification summary. |
| 5 | `docs/FIXES_v1.1.19.md` | Review fixes for work scheduling, ownership state, and combat/alert behavior. |
| 5 | `docs/MIT_RELEASE_AUDIT_v1.1.13.md` | Historical release-license checklist and limits of automated verification. |
| 5 | `docs/PLAYER_PARITY_PATCHLOG_v1.1.6.md` | Historical notes on player-parity behavior and validation. |

All Markdown documentation is maintained in English. The project license is the root `LICENSE` (MIT); the bundled wrapper's Apache 2.0 text is at `gradle/wrapper/LICENSE-APACHE-2.0.txt`.
