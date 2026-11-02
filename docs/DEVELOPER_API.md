# NPC Mind Local Developer API

NPC Mind targets Minecraft 1.20.1 and Forge 47.x. The generic entity-tool path and bundled companion command path are local and deterministic; neither requires a hosted AI server, remote inference API, or API key.

## Library quick start (v1.2.0+)

NPC Mind is a **developer library, not a mod**: your mod owns the `@Mod` entrypoint and
activates the library explicitly.

```gradle
// your build.gradle
implementation fg.deobf('com.yaseen:npc-mind-forge-1.20.1:1.3.1')
```

```java
// your @Mod class constructor
IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
NPCMindLibrary.bootstrap(modBus);      // 1) companion entity, pearl, menu, attributes, config
NPCMindLibrary.registerIntegration();  // 2) /npcmind commands + tick drivers + chat routing
NPCMindLibrary.registerClient(modBus); // 3) renderers/screens; no-op on dedicated servers
```

All three calls are idempotent. Registry names stay under the `npcmind:` namespace; the
config file is generated as `<yourmodid>-common.toml`. A full template host mod lives in
`docs/examples/HostModExample.java`; packaging/deployment patterns (jarJar vs runtime
dependency) are in `docs/examples/host-mod/build.gradle.snippet`, and the migration notes
from the old mod packaging are in `docs/LIBRARY_CONVERSION_v1.2.0.md`.

### Stable surface and @Internal

Only `com.yaseen.npcmind.api.*`, the tool contract trio (`AITool`, `ToolContract`,
`JsonArgs`) and `NPCMindLibrary` are stable — see [`API_STABILITY.md`](API_STABILITY.md)
for the versioning and deprecation policy. Every other public class in the jar (including
`ToolRegistry` and all built-in tool implementations) carries the
`com.yaseen.npcmind.api.@Internal` marker: it may change in any release without notice, so
route registrations through `NPCMindAPI` instead of calling internals. The same document
defines the `@NotNull` / `@Nullable` contract used across `NPCMindAPI`.

## Generic host NPC integration (v1.3.0+)

This path works with any live `LivingEntity` and does **not** require `NPCMindLibrary.bootstrap`,
the `npcmind:` entity registry, or `CompanionEntity`. The host mod owns its NPC entity, ticking,
player/entity authorization, memory, persistence, animation and network synchronization. NPC Mind
provides the stable tool contract, schema validation, deterministic phrase routing, thread guard
and a reusable player-like state/reach toolkit.

Register one entity-agnostic tool during host setup:

```java
import com.yaseen.npcmind.api.EntityAITool;
import com.yaseen.npcmind.api.NPCMindAPI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import org.jetbrains.annotations.Nullable;

NPCMindAPI.registerEntityTool(new EntityAITool() {
    @Override public String name() { return "example:follow_owner"; }
    @Override public String description() { return "Move this NPC toward its controller"; }
    @Override public String schema() { return ""; }
    @Override public String execute(LivingEntity actor, @Nullable ServerPlayer controller, String arguments) {
        if (!(actor instanceof PathfinderMob mob) || controller == null) return "FAILED unsupported_actor";
        mob.getNavigation().moveTo(controller, 1.1D);
        return "STARTED follow";
    }
});
NPCMindAPI.registerEntityIntent(new String[] { "follow me" },
        "example:follow_owner", "{}", "host follow intent");
```

In the host's own authorized server-side command/event handler:

```java
String result = NPCMindAPI.submitEntityCommand(myNpc, player, "follow me");
```

`registerEntityIntent` validates its fixed JSON arguments against the registered tool schema;
`resolveEntityCommand` can be used independently when the host wants to inspect a route or apply
its own policy first. `executeEntityTool` also supports planner-produced arguments for dynamic
commands. Pass `null` as controller for autonomous or unowned NPCs; the tool must handle that case.
All entity-tool execution must occur on the logical server thread. Host mods must authorize
ownership/control themselves before submission; the library cannot infer those rules for arbitrary
entities. Tool names and phrase aliases are process-wide, so namespace names and avoid ambiguous
aliases. Matching is deterministic; no language model is bundled. A host can use any optional
planner/provider it chooses and then dispatch the resulting validated tool call through this API.

## Bundled companion integration surface

Use `com.yaseen.npcmind.api.NPCMindAPI` from another mod compiled for the same Minecraft/Forge environment.

- `registerTool(AITool tool)`: register a capability implementing `AITool`. Duplicate names are rejected.
- `registerIntent(String[] keywords, String toolName, String jsonArguments, String reason)`: add deterministic keywords for a registered tool. Fixed JSON arguments are validated at registration time.
- `resolveCommand(String command)`: resolve a command without executing it or reading world state.
- `submitCommand(CompanionEntity companion, ServerPlayer owner, String command)`: submit through the owner-gated execution path. Call on the server thread.
- `tools()`: return an immutable snapshot of tool names, descriptions, and schemas.
- `isBusy(...)`: query a companion's command worker state.

## Reusing player-like rules with any entity

`NPCMindAPI.canReachPoint(LivingEntity, Vec3, reach)` and `NPCMindAPI.canReachEntity(LivingEntity, LivingEntity, reach)` are independent of `CompanionEntity`; use them with any custom NPC, mob, animal, or boss. They enforce eye reach and collision-based visibility but do not execute the interaction for you. Always call the real item/block interaction and verify the postcondition.

The complete built-in command worker (`submitCommand`) is currently companion-specific and owner-gated. An arbitrary existing entity does not become a real `ServerPlayer` merely by calling these utilities.


## Reusable player-like food and experience state

- `NPCMindAPI.playerLikeState(LivingEntity)` returns health, food, saturation, exhaustion, air, fire/freeze state, and XP snapshot.
- `NPCMindAPI.tickPlayerLikeState(LivingEntity)` advances opt-in hunger/natural regeneration for non-`CompanionEntity` living entities. Call it once per server tick only; it returns `false` for `CompanionEntity` so its built-in simulation is not double-ticked.
- `NPCMindAPI.addPlayerLikeExhaustion(entity, amount)`, `setPlayerLikeFoodLevel(entity, food)`, and `consumePlayerLikeFood(entity, nutrition, saturationModifier)` provide mutation helpers. They are server-thread guarded. A host mod must still perform the normal item-use/consumption interaction before applying nutrition.
- `NPCMindAPI.experience(entity)` reads player-like XP, and `grantExperience(entity, points)` grants positive XP points using the vanilla 1.20.1 level curve on the server thread. Generic entity state is stored in Forge persistent entity data; the host is responsible for client synchronization and deciding what grants XP.
- `manage_player_state` is an informational companion tool. `collect_xp` only enables a bounded pickup window for real nearby XP orbs; it does not fabricate XP.

## Maven publication and consumption channels

The Gradle publication is `com.yaseen:npc-mind-forge-1.20.1:1.3.1` (versions come from
`gradle.properties`). The publication carries the binary, sources and a Javadoc JAR, so
signatures and docs resolve in your IDE without opening the source tree.

| Channel | How |
|---------|-----|
| Project-local repo | Run `./gradlew publish` in this tree, then resolve `com.yaseen:npc-mind-forge-1.20.1:1.3.1` from `build/repo` |
| Local Maven cache | `./gradlew publishToMavenLocal`, resolve from `mavenLocal()` |
| JitPack | Push the tree to GitHub and tag `v1.2.0`; consumers add `maven { url 'https://jitpack.io' }` and depend on `com.github.YOUR_GITHUB_USERNAME:NPCMind:v1.3.1`. `jitpack.yml` pins JDK 17, which the Gradle 8.1.1 wrapper requires |
| Maven Central / GitHub Packages | Not configured; add credentials and signing if you want public publishing through them |

In the consumer build, wrap the dependency in `fg.deobf(...)` for development (the library
is reobfuscated to SRG names at build time, like any Forge 1.20.1 jar). The published POM
declares Minecraft/Forge and the annotations as `provided`: your ForgeGradle build supplies
them, and nothing is pulled in transitively at runtime.

This publication has not been executed in the library's own build environment (no Gradle
distribution access there); CI on your repository is the first place it will run end to end.

## Example

```java
NPCMindAPI.registerTool(new ScanNearbyOreTool());
NPCMindAPI.registerIntent(
    new String[] { "scan nearby copper", "find nearby copper" },
    "scan_nearby_ore",
    "{\"radius\":8}",
    "host-mod ore scan"
);
```

The example assumes `ScanNearbyOreTool` implements `AITool` and its schema accepts the `radius` number. Invalid arguments cause registration to throw `IllegalArgumentException`.

## Tool implementation notes

- Keep `name()` stable and unique.
- Keep `schema()` aligned with `execute()`; required parameters must be present in every route.
- Do world access and mutations on the server thread.
- Return explicit `SUCCESS`, `PARTIAL`, `FAILED`, or informational results.
- Validate world bounds, loaded chunks, distance, permissions, inventory availability, and actual postconditions.

## Build environment

The project targets Java 17, Forge 1.20.1, and Minecraft 1.20.1. A successful Gradle build and in-game integration test are required before release.
