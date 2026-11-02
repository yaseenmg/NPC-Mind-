# NPC Mind Architecture Overview

NPC Mind uses a central controller and task-oriented execution model. `CompanionKernel` coordinates ongoing behavior, `TaskManager` owns long-running world actions, and `ToolRegistry` exposes validated operations for host integrations.

Navigation requests pass through `NavigationArbiter` so movement controllers can respect ownership and priority. Resource, inventory, crafting, and world-action layers verify preconditions and actual outcomes before a task reports completion.

The library is local-only by default and does not require a hosted inference service or remote API key. Host mods can extend behavior through the public API and registered `AITool` implementations.

Project material for which the maintainer holds the necessary rights is licensed under the root MIT `LICENSE`. The bundled Gradle Wrapper retains its Apache 2.0 terms in `gradle/wrapper/LICENSE-APACHE-2.0.txt`.
