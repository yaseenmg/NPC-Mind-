# Generic NPC Developer Framework

NPC Mind 1.3.1 provides an entity-agnostic developer framework with two integration tracks:

1. **Generic host NPC track:** use your own `LivingEntity` / `Mob` subclass and the entity-agnostic APIs. This does not register or spawn `CompanionEntity` and does not require calling `NPCMindLibrary.bootstrap`.
2. **Bundled companion track:** call `NPCMindLibrary` to register NPC Mind's ready-made entity, inventory, tools, commands, tick drivers and renderers.

## Responsibilities

NPC Mind supplies a stable tool contract, JSON schema validation, deterministic alias routing, server-thread checks, and reusable helpers for reach/visibility, optional player-like food/exhaustion and XP. The host mod remains authoritative for entity registration, entity ticking, ownership/control authorization, memory/state persistence, network synchronization, animations, AI model/provider selection and concrete world actions.

## Generic tool and command route

Register unique, namespaced tool names and aliases during host setup. Phrase arguments are fixed JSON objects validated against the tool's schema before the route is accepted. The route matcher normalizes case, punctuation and Arabic diacritics, and chooses the most specific matching phrase.

```java
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

After applying the host's own authorization policy on the logical server thread:

```java
String result = NPCMindAPI.submitEntityCommand(myNpc, player, "follow me");
```

`resolveEntityCommand` only returns a route and has no world side effects. `executeEntityTool` dispatches a planner-produced tool name and arguments directly after the same schema and thread checks. Pass a null controller for autonomous or unowned NPCs; tool implementations must handle that case. For commands that need no natural-language alias, call `executeEntityTool` directly.

## Planner / AI provider

The generic router is deliberately deterministic; the library does not embed an LLM or silently send data to a service. A host may add its own planner or provider. The safe integration shape is:

1. Collect only the context the host is allowed to expose.
2. Ask the host-owned planner for a tool name and JSON arguments (or resolve a registered phrase route locally).
3. Apply host authorization and policy checks for the actor and controller.
4. Call `executeEntityTool` on the logical server thread.
5. Verify the resulting world state in the host tool and return an honest `SUCCESS`, `PARTIAL`, `FAILED` or `STARTED` result.

The library validates the declared schema and execution thread but cannot guarantee that a host tool's implementation is safe. Never execute world mutations on a worker thread, trust an external model's authorization claims, or report success without checking postconditions.

## Current scope

The framework is entity-agnostic at its public tool-routing boundary, not a drop-in universal autonomous brain. The host still implements its NPC-specific movement/combat/tool logic and schedules decisions. NPC Mind's bundled `AgentEngine`, inventory and long-running task controllers remain companion-specific. This boundary lets host developers reuse the contracts and safety helpers without inheriting the bundled companion entity lifecycle.
