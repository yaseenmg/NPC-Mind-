# NPC Mind v1.1.17 — Feature Notes

This release added six systems to the NPC Mind companion runtime and expanded its diagnostics and survival behavior. The notes below describe the implementation and its boundaries.

## Implemented systems

### 1. `autonomy/CompanionWorkBudget` — server-wide decision load balancing

- A global per-tick cap controls how many companions may begin an expensive autonomy or exploration decision. The load-shedding ladder is `≤25ms → 3`, `≤35ms → 2`, `≤45ms → 1`, and `>45ms → 0` new grants; current holders may finish.
- A bounded rotation queue (32 entries) shares work fairly, with release in `finally` blocks.
- Survival, combat, emergency, task, sleep and threat-monitor ticks remain unrestricted.
- The budget is integrated into `CompanionKernel.tick` for autonomy and exploration phases.

### 2. `CombatAssessor.threatScore` and `selectBest` — target selection

- Threat scoring considers health, absorption, armor, weapon damage, enchantments, shields and ranged capability.
- A current attacker of the companion or owner receives priority over an uninterested target.
- A stickiness bonus reduces oscillation between nearby targets.
- Autonomous target acquisition uses the score rather than distance alone.

### 3. Crafting audit

The crafting path uses the live recipe manager, simulates input consumption on a copy, handles recipe remnants, checks output space before consuming materials, and bounds depth and attempts. This release required no behavior change to that path.

### 4. `BuildBlueprintTool` — structured blueprint placement

- Per-symbol block roles can be supplied through the `blocks` argument; the legacy `block` argument still defines `#`.
- Optional `-` cells are placed last and may be skipped when support rules prevent placement.
- Required material counts are checked before queueing a build.
- Placement is ordered bottom-up, and queued build groups can follow an active group without cancelling it.

### 5. `CompanionAlertManager` — teammate assistance

- Damage from a hostile entity creates a dimension-scoped alert with a 200-tick lifetime.
- An eligible idle companion may assist a teammate when the threat is still attacking and within the configured radius.
- Expired or defeated threats are pruned; the system ignores self-alerts and protected allies.

### 6. `GoalTrace` and `inspect_ai` — in-game diagnostics

- Goal phases are stored only when their state/detail signature changes; the trace keeps the last eight transitions in memory.
- The kernel reports survival, combat, work, sleep, assistance, and work-budget phases.
- The read-only `inspect_ai` tool reports the current phase, task, combat target, work budget and alert state.

## Wiring and cleanup

`CompanionCleanup.purge` clears the work-budget, alert and trace state for removed companions. `ToolRegistry` registers `InspectAITool`; the source audit checks registered tool-name uniqueness.

## Verification and limitations

Historical checks for this release covered source markers, registered tools and static regressions. They did not replace a Java/Forge build or in-game test. Current verification requirements are listed in `README.md` and `CONTRIBUTING.md`.

## Backlog recorded at the time

Potential follow-up work included optional chest/farm protection, improved escape and building-site checks, fishing, progression-aware equipment, obstacle handling, and more advanced house placement. These items were not part of v1.1.17.
