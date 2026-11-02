# NPC Mind v1.1.13 — MIT Release Preparation Audit

## Release changes

- The version was updated in Gradle metadata, Forge metadata, and the public API version constant.
- The root `LICENSE` was set to the MIT License with copyright 2026 Yaseen. The project artifact's Forge metadata and Maven POM also declared MIT.
- Bed normalization, combat pursuit, loaded-corridor navigation, follow recovery, and stuck-recovery checks were reorganized or bounded.
- The controller/tool architecture, local-only configuration, and public API remained in place.

## Static checks and build limits

The source and regression checks examine tool registration, API markers, and known source patterns. They do not establish a legal conclusion, prove authorship, or replace Java/Forge compilation and in-game testing. A previous candidate could not run Gradle because the distribution download was unavailable. For the current 1.3.0 build and verification outcome, see `BUILD_RESULT.md`.

## Licensing boundary

The MIT License applies only to material for which the maintainer holds the necessary rights. Static checks, text searches, and similarity heuristics cannot establish that every included code, asset, or dependency is cleared for redistribution. Before publishing, verify ownership or permission for all distributed material and include any license terms required by bundled components. The Gradle Wrapper's Apache License 2.0 text is retained at `gradle/wrapper/LICENSE-APACHE-2.0.txt`.
