# NPC Mind 1.3.1 Build and Security Report

## Build status

- Minecraft: 1.20.1
- Forge: 47.4.10
- Gradle: 8.1.1 wrapper
- JDK: 17
- `clean test build`: passed.
- JUnit tests: 22 tests passed, including the new server-thread guard, bounded worker-pool, and `ToolContract` behavior tests.
- Python network-policy tests: 5 passed.

## Security and reliability changes

- Public entity/world action helpers now verify the logical server thread before accessing live entity or level state. Off-thread mutations reject safely; player-like snapshot reads return neutral defaults without reading entity NBT.
- `NPCMindActions`, reach/line-of-sight checks, XP reads/grants, and companion command submission enforce the same server-thread policy.
- `AgentEngine` now has a hard limit of four daemon workers and a 64-command bounded queue. Rejected commands are not started and receive a clear busy response; blocking work is never run inline on the server thread. Canceled queued tasks are removed.
- `source_audit.py` now checks direct Java networking imports/references (including URL, sockets, Java HTTP/WebSocket and common client libraries) and common dynamic loading/reflection paths. Five fixture-based tests cover positives, benign URIs, comments and string literals.

## Validation gates

- API surface check: passed; no unmarked public top-level types.
- Source audit: passed; 169 production Java files, 60 registered tools, zero audit errors.
- Repair regression gate: 401 checks passed.
- MIT release gate: passed.
- Source archive and JAR integrity checks are run for the delivered files.

The source scanner is a policy gate, not a runtime sandbox. It cannot prove that third-party dependencies, native code, dynamically supplied plugins, or arbitrary runtime behavior never access the network. No full Forge in-world headless test was run; gameplay still needs validation in a test world.
