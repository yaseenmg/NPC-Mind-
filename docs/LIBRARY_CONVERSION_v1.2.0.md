# Library Conversion — v1.2.0 (mod → developer library)

NPC Mind 1.2.0 is the first release that ships **purely as a developer library**. The
intelligence, tools, controllers and behaviors are unchanged; only the packaging and the
activation model changed.

## What was removed

| Removed | Why |
|---------|-----|
| `@Mod` entrypoint in `NPCMind` | A library must not auto-load; the host mod owns the mod identity. `NPCMind` remains as the `MOD_ID` namespace + shared `LOG` holder. |
| `src/main/resources/META-INF/mods.toml` | The jar is not a mod anymore. A host-mod template lives in `docs/examples/host-mod/mods.toml`. |
| `src/main/resources/pack.mcmeta` | No mod resources → no resource-pack metadata needed. |
| `minecraft { runs { … } }` + `copyIdeResources` | Run configurations only make sense for a playable mod. Host mods run their own dev client. |
| Annotation scanning (`@Mod.EventBusSubscriber`) on `ForgeIntegration` and `ClientRendering` | Annotation scanning requires a mod container. Both classes are now registered explicitly by the host. |

## What was added

- **`NPCMindLibrary`** — explicit, idempotent bootstrap facade with exactly three calls:
  1. `bootstrap(IEventBus hostModEventBus)` — entity types (companion + pearl), inventory
     menu, entity attributes, COMMON config. Must run before registry events, i.e. from the
     host mod constructor.
  2. `registerIntegration()` — `/npcmind` commands, server-tick task/respawn drivers,
     optional `!npc` chat routing, death/cleanup hooks (global Forge bus).
  3. `registerClient(IEventBus hostModEventBus)` — renderers and menu screens; internal
     dist guard makes the call a no-op on dedicated servers.
- **`withJavadocJar()`** in `build.gradle` and a Maven publication
  (`com.yaseen:npc-mind-forge-1.20.1:1.2.0`) — the library is consumed like any other
  dependency.
- **Host-mod example kit** in `docs/examples/`: a complete `HostModExample.java`, a
  `mods.toml` template and a `build.gradle.snippet` with `fg.deobf` + `jarJar` patterns.
- Reobfuscation (`jar.finalizedBy('reobfJar')`) is retained so the published jar works at
  production runtime (SRG names), exactly like any Forge 1.20.1 mod jar.

## Breaking changes for hosts

1. **Dropping the jar into `mods/` alone no longer does anything** (and is no longer the
   intended use). A host mod must call the three bootstrap methods.
2. **Chat command registration, tick drivers and cleanup hooks are opt-in** through
   `registerIntegration()`. A host that drives `CompanionEntity` entirely itself may skip it.
3. **The config file name changes** from `npcmind-common.toml` to
   `<hostmodid>-common.toml` (the active mod container belongs to the host). Keys, types and
   defaults are identical; existing values must be migrated by hand once.
4. Entity/menu registry names still use the `npcmind:` namespace, so worlds saved with
   1.1.x companions keep loading.

## Unchanged

- `CompanionEntity` behavior, tools, permissions, tasks, autonomy, work budget, alert
  manager, respawn directory and all v1.1.x fixes (items 1–68).
- `NPCMindAPI` integration surface for tools, intents, reach helpers and player-like state.
- Registry namespace `npcmind:` for entities and menus.
- Java 17 toolchain, Forge 1.20.1 / 47.4.10, Gradle wrapper 8.1.1 (do not run Gradle on 21+).

## Hardening round (library engineering items 69–78)

Follow-up engineering pass that turns the converted codebase into a *professional* library:
version discipline, declared contracts, mechanical gates and governance documents. Items
continue the numbering of `docs/FIXES_v1.1.19.md`.

### 69. `gradle.properties` — single version source
`mod_version`, `mod_group`, `minecraft_version`, `forge_version` and dependency versions now
live exactly once. `build.gradle` wires them via `project.<property>`; `verify_mit_release.py`
cross-checks the properties file instead of the old in-build literal. Bumping a version now
touches: `gradle.properties`, `NPCMindAPI.API_VERSION`, `EXPECTED_API_VERSION` in
`ApiCompatibilityTest`, and `CHANGELOG.md` — nothing else.

### 70. Jar manifest attributes
`Implementation-Title/Version/Vendor`, `Specification-Title/Vendor/Version` and
`Automatic-Module-Name: com.yaseen.npcmind` are set on the library jar. The module name gives
the jar a stable identity if a consumer ever places it on the module path; a real
`module-info.java` was deliberately deferred (see `docs/API_STABILITY.md` §7).

### 71. POM declares its platform as `provided`
The published POM now contains explicit `<dependencies>`: `net.minecraftforge:forge` and
`org.jetbrains:annotations`, both `provided`. Consumers' ForgeGradle builds supply
Minecraft/Forge themselves, and no annotation library leaks into runtime classpaths.

### 72. `@Internal` marker across the non-stable surface
New `com.yaseen.npcmind.api.@Internal` annotation (`@Documented`, `RetentionPolicy.CLASS` —
recorded for IDEs and bytecode tools, zero runtime reflection cost). Applied to every
top-level public type outside the stable surface: **153 files marked**, stable files
untouched. Enforced by the new `tools/check_api_surface.py` gate (also run in CI), which
fails on any public type without the marker and on any stable file that gains one. The
stable surface is: `api/*` (12 files incl. the annotation itself), `tool/AITool`,
`tool/ToolContract`, `tool/JsonArgs`, and `NPCMindLibrary`.

### 73. Nullability contract on `NPCMindAPI`
The whole stable surface is annotated with `org.jetbrains.annotations` `@NotNull` /
`@Nullable`: never-null returns (empty collections/Optionals/`false`/`DENIED` strings
instead), explicitly tolerated null parameters (`enableOnlyPacks`, `enableOnlyTools`,
`executeEntityTool` actor/controller, `grantExperience`, `isBusy`, `submitCommand`,
`resolveCommand`). `org.jetbrains:annotations:24.1.0` is a `compileOnly` dependency and
`provided` in the POM; it is not bundled in the published runtime JAR.

### 74. `ApiCompatibilityTest` — bootstrap-free surface gate
New JUnit test (`src/test/.../api/ApiCompatibilityTest.java`) that pins the stable surface:
method-name presence over `NPCMindAPI` via reflection, existence of every stable class, the
pinned `EXPECTED_API_VERSION` read through the reflected field (immune to compile-time
constant inlining), and the `@Internal` retention policy. It deliberately invokes nothing
that touches Minecraft registry initialization, so it runs in a bare JUnit JVM and fails the
build the moment a stable method is removed or a version drifts.

### 75. `docs/API_STABILITY.md`
Normative policy: the stable-surface table, the `@Internal` rule, SemVer mapping (no
breaking changes while at `1.x`), deprecation cycles, the nullability contract, the three
enforcement gates, and the recorded rationale for deferring the multi-project `api`/`impl`
split and `module-info.java`.

### 76. Developer governance documents
`CHANGELOG.md` (API/packaging-focused, covers 1.1.14 → 1.2.0), `CONTRIBUTING.md` (JDK 17
constraint, gate list, conventions, version-bump checklist), `SECURITY.md` (scope:
permission bypass, world-mutation escape, duplication, `FakePlayer` abuse, thread
violations; private disclosure process; response targets; supported versions).

### 77. CI and JitPack
`.github/workflows/build.yml`: JDK 17 (Temurin) + `./gradlew --no-daemon clean test build`
+ all four static gates + artifact upload, with a Gradle cache. `jitpack.yml` pins
`openjdk17` for JitPack builds (the Gradle 8.1.1 wrapper requirement) and publishes to
Maven Local inside the JitPack environment. Consumption channels (local `build/repo`,
`mavenLocal()`, JitPack, future Central/GitHub Packages) are documented in `README.md` and
`docs/DEVELOPER_API.md`.

### 78. Verification upkeep
`verify_repair_regressions.py` grew a v1.2.0 hardening section (version source, manifest,
POM wiring, `@Internal`, nullability, test gate, governance docs, CI) and had three markers
updated for the annotated signatures; the suite now runs **384** checks. `source_audit.py`
reports 166 Java files, 60 built-in tools, all unique. Dependency scope is kept narrow:
the annotations artifact is compile-only/provided and is not bundled in the runtime JAR.
