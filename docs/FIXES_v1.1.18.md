# NPC Mind v1.1.18 — Documentation and Source Hygiene

This historical release note records a comment and documentation cleanup. Runtime behavior and executable logic were not changed in that round.

## Changes recorded

Comments were standardized around server work budgeting, teammate alerts, combat scoring, bodyguard target detection, exploration selection, diagnostics, and blueprint placement. Documentation examples were rewritten as concise descriptions of the implemented behavior. Version and license metadata were checked alongside the source cleanup.

## Licensing and verification

The project license is the MIT License in the root `LICENSE`. The bundled Gradle Wrapper retains its separate Apache License 2.0 terms; the current license text is stored at `gradle/wrapper/LICENSE-APACHE-2.0.txt`. The MIT label applies only to material the maintainer is entitled to distribute.

The original round's static checks reported no source-marker or registered-tool errors. Static checks do not replace compilation, runtime testing, or a review of rights and license obligations. For the current verification commands, see `README.md` and `CONTRIBUTING.md`.
