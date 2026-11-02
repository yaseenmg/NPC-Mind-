# Security Policy

## Scope

NPC Mind is a **local** NPC intelligence library for Minecraft Forge 1.20.1: it executes
tools directly against a server world on the logical server thread, using per-interaction
`FakePlayer` contexts. It contains **no** network clients (no HTTP, no sockets, no remote
inference) — `tools/source_audit.py` fails the build on any network-client reference, and
that gate is part of CI.

The following classes of issues are in scope for a security report:

- **Permission/ownership bypass** — a player causing an action on a companion they do not
  own (`/npcmind` command tree, `NPCMindAPI.submitCommand`, chat routing).
- **World-mutation escape** — a tool placing, breaking, or interacting outside its declared
  reach/permission checks (claim/protection integrations, unbreakable blocks, unloaded
  chunks, cross-dimension targets).
- **Item or XP duplication** — any path where inventory, container, crafting, or experience
  effects are applied without a matching real source, or applied twice.
- **`FakePlayer` abuse** — interaction contexts leaking an owner profile in a way that
  defeats protection mods, or outliving their single interaction.
- **Server-thread violations** — world access off the server thread through any public API.
- **Crash / hang** — crafted command, tool argument, or NBT input that crashes the server,
  deadlocks a worker, or grows memory without bound (memory limits are documented in the
  config).

Out of scope: game-balance opinions, behaviors that are already documented limitations
(for example, the companion is a server-side mob, not a `ServerPlayer`), and anything
requiring operator/game access on the host machine.

## Reporting a vulnerability

Please do **not** open a public GitHub issue for security reports.

1. Preferred: open a private **GitHub Security Advisory** against this repository.
2. Alternatively, contact the maintainer (Yaseen) through the contact channel listed in the
   repository profile.

Include: affected version (`gradle.properties` / jar version), a reproduction sequence
(commands, chat input, tool arguments, or NBT), and your assessment of impact. A save or
minimal test setup that demonstrates the issue shortens triage considerably.

## Response targets

- Acknowledgement: within 7 days.
- Triage decision (accepted / out of scope) with rationale: within 14 days.
- Fix or mitigation for accepted issues: targeted for the next PATCH or MINOR release,
  credited in `CHANGELOG.md` unless the reporter prefers anonymity.

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.2.x (developer-library line) | Yes |
| 1.1.x (standalone mod line) | Security fixes on best effort; upgrade to 1.2.x recommended |

## Built-in hardening you can rely on

- Every public mutation path is server-thread guarded (`isSameThread()` checks return
  `DENIED` / `false` rather than acting off-thread).
- Tools validate arguments through `ToolContract` before execution and verify real world
  postconditions before reporting success (`CriticEngine` treats world diffs, not tool
  output, as the final authority).
- Permission checks and the resource ledger (`ResourceLedger.owns`) gate reserved blocks,
  containers and placed items across the tool surface.
- Spawning is one-live-companion-per-owner via the live directory, including after NBT load
  and ownership transfer.
