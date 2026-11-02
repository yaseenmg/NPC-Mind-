# API Stability Policy

NPC Mind is consumed by host mods as a dependency, so its public surface follows explicit
stability rules. This document is normative: when the code and this policy disagree, either
the code is fixed or the policy change ships in `CHANGELOG.md` with a migration note.

## 1. The stable surface

Only the following may be called from host mods and are covered by the compatibility
guarantees below:

| Group | Classes |
|-------|---------|
| Integration API | `com.yaseen.npcmind.api.NPCMindAPI`, `NPCMindAPI.ToolInfo`, `NPCMindAPI.EntityCommandRoute` |
| Entity-agnostic tool API | `com.yaseen.npcmind.api.EntityAITool`, `com.yaseen.npcmind.api.EntityToolRegistry` |
| Player-like state API | `com.yaseen.npcmind.api.PlayerLikeState`, `com.yaseen.npcmind.api.PlayerLikeExperience`, `com.yaseen.npcmind.api.NPCMindActions` |
| Pack selection | `com.yaseen.npcmind.api.ToolPack` |
| Stable tool contract | `com.yaseen.npcmind.tool.AITool`, `com.yaseen.npcmind.tool.ToolContract`, `com.yaseen.npcmind.tool.JsonArgs` |
| Bootstrap facade | `com.yaseen.npcmind.NPCMindLibrary` |

Data-level guarantees beyond class signatures:

- Entity and menu registry names stay namespaced under `npcmind:` so saved worlds and
  existing integrations keep loading across releases.
- `NPCMindAPI.API_VERSION` is a string constant; bump it together with the Gradle
  `mod_version` in `gradle.properties`.
- `EntityAITool.execute` is invoked only on the logical server thread. Its controller may be
  null for an autonomous host NPC; tool implementations must document/handle that case.
- Entity tool names and intent phrases are process-wide. Hosts must namespace tool IDs and avoid
  duplicate aliases. NPC Mind does not infer ownership or grant host-side authorization.
- Generic entity command routing is deterministic. It does not imply a bundled LLM or planner.
- Public helpers that read or mutate live `LivingEntity`/world state require the logical server
  thread. Mutators reject off-thread calls; state snapshot readers return neutral snapshots rather
  than touching entity data. Pure catalogue and intent resolution methods remain thread-independent.

## 2. Everything else is internal

Any class outside the table above — `CompanionKernel`, `TaskManager`, `AgentEngine`,
`ToolRegistry`, `NavigationArbiter`, the autonomy controllers, all built-in tool
implementations, … — carries the `com.yaseen.npcmind.api.@Internal` marker. `@Internal`
types:

- may be renamed, reshaped, moved between packages, or removed in **any** release,
  including patch releases;
- receive no deprecation cycle and no migration notes;
- are excluded from the compatibility test gate (`ApiCompatibilityTest`) and from the
  surface gate (`tools/check_api_surface.py`).

The marker uses `@Retention(RetentionPolicy.CLASS)`: it is recorded in the class file so
IDEs and bytecode tools can surface it, with no runtime reflection cost.

Tool registration, pack selection and intent registration must go through `NPCMindAPI`,
never through `ToolRegistry` directly.

## 3. Versioning

Releases follow Semantic Versioning 2.0.0 as applied to the stable surface:

- **MAJOR** — breaking change to the stable surface: removed or reshaped stable method,
  changed signature, changed semantics of a documented return value. (For as long as the
  project stays at `1.x`, breaking changes are forbidden on the stable surface; a genuine
  break forces a `2.0.0` line.)
- **MINOR** — new stable API additions, new built-in tools, new packs, new `ToolPack`
  members. Host mods compiled against an older MINOR keep working.
- **PATCH** — bug fixes and internal refactors with no stable-surface change.

Releases 1.2.0 and 1.3.0 record packaging and generic-API expansion; 1.3.1 records
server-thread and worker-capacity hardening in `CHANGELOG.md`.

## 4. Deprecation

- A stable method is never removed or silently broken. It is annotated `@Deprecated` with
  a Javadoc note naming the replacement.
- A deprecated method survives at least one MINOR release cycle before removal, and its
  removal is listed under "Removed" in `CHANGELOG.md` of the release that removes it.
- Behavior fixes that change observable tool output are documented in `CHANGELOG.md` under
  "Changed", not silently slipped in.

## 5. Nullability contract

Stable methods use `org.jetbrains.annotations` (`@NotNull` / `@Nullable`), compile-only
and `provided` in the published POM:

- `@NotNull` return: the method never returns null — it returns an empty collection,
  an empty `Optional`, `false`, or a `DENIED`/`FAILED` string instead.
- `@NotNull` parameter: null is not a legal value.
- `@Nullable` parameter: null is explicitly tolerated and handled; Javadoc documents behavior.

## 6. Enforcement

| Gate | Command | Catches |
|------|---------|---------|
| Surface gate | `python tools/check_api_surface.py` | public type without `@Internal`, marker drift on stable files |
| Compatibility test | `./gradlew test` (`ApiCompatibilityTest`) | removed stable method/class, version drift |
| Release guard | `python tools/verify_mit_release.py` | version literals drifting across build metadata |

CI (`.github/workflows/build.yml`) runs the gates plus the Gradle build and test suite.

## 7. Deferred structural options

- **Multi-project `api` / `impl` split.** The single jar plus `@Internal` and `check_api_surface.py`
  currently provide the contract with less build risk; revisit if API dependencies must not leak.
- **`module-info.java` (JPMS).** Forge 1.20.1 runs on the classpath, so an untested module
  descriptor on a reobfuscated jar is intentionally deferred. `Automatic-Module-Name` is set.
