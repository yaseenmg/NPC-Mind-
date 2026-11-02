# Contributing to NPC Mind

Thank you for improving the library. This document covers the environment, the gates your
change must pass, and the conventions that keep the stable surface trustworthy.

## 1. Environment

- **JDK 17 only for Gradle itself.** The wrapper is pinned to Gradle 8.1.1 (required by
  ForgeGradle 6) and fails on Java 21+ with `Unsupported class file major version 65`. Point
  `JAVA_HOME` at a JDK 17 installation before running anything.
- Minecraft 1.20.1 / Forge 47.4.10 (`gradle.properties` is the single source of truth).
- Python 3 only for the static verification tools — they are dependency-free on purpose.

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew clean test build
```

The build produces `build/libs/npc-mind-<version>.jar` plus sources and Javadoc JARs, and
reobfuscates the main jar to SRG names (`jar.finalizedBy('reobfJar')`). Do not remove that
line — a non-reobfuscated jar crashes outside the dev environment.

## 2. Gates every change must pass

Run all of them before opening a PR; CI (`.github/workflows/build.yml`) runs the same set:

```bash
./gradlew test                          # unit tests + ApiCompatibilityTest surface gate
python tools/check_api_surface.py       # @Internal coverage of the non-stable surface
python tools/source_audit.py            # tool registry consistency, no network clients
python tools/verify_repair_regressions.py  # fix-specific source markers
python tools/verify_mit_release.py      # MIT metadata, versions, external-name hygiene
```

A change that touches the stable surface must also update
`src/test/java/com/yaseen/npcmind/api/ApiCompatibilityTest.java` (surface list and/or pinned
version) — that is intentional friction, see `docs/API_STABILITY.md`.

## 3. Conventions

### Versioning
- Bump `mod_version` in `gradle.properties`, `NPCMindAPI.API_VERSION`, and
  `EXPECTED_API_VERSION` in `ApiCompatibilityTest` **together, in the same commit**.
- Record user-visible changes in `CHANGELOG.md` under the right SemVer heading.

### The stable surface
- Add new capabilities to `NPCMindAPI` / the api package rather than widening an internal
  class; keep the api package free of implementation logic.
- Every new top-level `public` type outside the stable surface gets `@Internal` plus the
  import (see `com.yaseen.npcmind.api.Internal`); `check_api_surface.py` will fail otherwise.
- Never call `ToolRegistry` / `EntityToolRegistry` directly from host-facing examples — go
  through `NPCMindAPI`.

### Tool implementations
- Do world access and mutation on the logical server thread; verify actual postconditions
  before reporting `SUCCESS`; return explicit `SUCCESS` / `PARTIAL` / `FAILED` / `DENIED`
  outcomes with machine-readable reasons.
- Keep `name()` stable and unique, and keep `schema()` aligned with what `execute()` accepts.
- No network clients — `source_audit.py` fails on any `java.net.http`, socket-based HTTP
  client, or WebSocket reference. NPC Mind is local and deterministic by design.

### Source hygiene
- UTF-8 everywhere; Java 17 syntax is available.
- Do not copy third-party implementation code unless its license permits redistribution; retain any
  required attribution and license text in the relevant source distribution. The bundled Gradle
  Wrapper license is preserved at `gradle/wrapper/LICENSE-APACHE-2.0.txt`.
- New third-party dependencies must have their license terms reviewed and documented; prefer
  compile-only dependencies whenever possible.

## 4. Commit messages and PRs

- One logical change per commit; the commit message states the *why*, not just the *what*
  (for example: "work-budget: evict stale queue entries so a vanished requester cannot pin
  the rotation head").
- PRs describe: motivation, the gates that were run, and any stable-surface impact
  (ideally "none" — otherwise justify the SemVer bump).
- If the change fixes a review finding, reference the item number from the relevant
  `docs/FIXES_*.md` round.

## 5. Reporting issues

Behavior bugs need a reproduction path (command sequence, expected vs actual). Security
issues follow the private disclosure process in [`SECURITY.md`](SECURITY.md) — please do not
open public issues for them.
