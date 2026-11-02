# NPC Mind v1.1.6 — player-parity patch log

## Changes applied

1. **Isolation of interaction contexts:** each block/item interaction creates a fresh `FakePlayer`. Online-owner profile UUID is retained for claims/permissions; offline owner contexts use a stable companion-specific profile. This removes the shared cached context between multiple companions.
2. **Bounded entity navigation:** the `NavigationArbiter.request(..., Entity, ...)` overload now delegates to its bounded BlockPos path route, preserving loaded-corridor checks and waypoint fallback.
3. **Following cadence:** requests are refreshed on a 10-tick cadence or when the owner's block position shifts significantly; failed attempts are throttled. Sprint uses distance hysteresis (enter above 7 blocks, leave below 5) and no longer causes automatic bunny-hops. Collision jumps remain enabled.
4. **Stuck recovery:** the corrective action uses a direct movement impulse only; it no longer sets both `MoveControl` and direct velocity for the same step.
5. **Combat path ownership:** `CombatMovement` checks the navigation lease request result before retaining a path and only stops a stale route if combat still owns it.
6. **Visibility rules:** ray endpoint tolerance is 0.01 squared-distance units instead of 0.0001; arbitrary non-finite coordinates are rejected.
7. **Mining resources:** tool-selection and required-pickaxe checks respect the resource reservation ledger. A tool with zero destroy speed is not promoted to an ordinary tool score or vanilla hand-mining speed.
8. **Obstructed mining access:** if a non-natural blocker hides a target, the NPC validates a standable position with a clear line of sight before a long trip. It may navigate around a built wall but will not mine that wall automatically as a shortcut. Nether base stone is recognized via the 1.20.1 block tag.
9. **Threat defense:** hostile mobs targeting the owner are checked within a 16-block owner radius while a task is active; owner-threat checks are limited to the companion's current dimension.
10. **Combat polish:** strafe directions change less frequently, Arabic question marks are sentence boundaries during duplicate-sentence cleanup, and Fire Aspect timing uses four seconds per level.
11. **Cleanup:** removed duplicate natural terrain tag checks and updated the mod/API version to 1.1.6.

## Findings already fixed in v1.1.5 (not newly introduced here)

- Safe manual spawn points with no-collision and support-block checks.
- Cave/building-aware far-follow teleport search around the owner's Y level before surface fallback.
- Explicit unsupported response for Trial Chambers on Minecraft 1.20.1.
- Manual spawn blocked while automatic respawn is pending.
- Ownership transfer clears old-owner emergency/threat state and resets companion autonomy schedule.
- Eating state synchronization through entity data.

## Findings not applied verbatim

- **`createPath(BlockPos, int, int)` signature:** it exists in Minecraft/Forge 1.20.1, as documented in the 1.20.1 mappings API; replacing it based on a suspected missing overload would be incorrect.
- **Three durability per tool hit:** not applied. Java tool attacks consume two durability points per mob hit (swords consume one), and the current `DiggerItem ? 2 : 1` rule matches that behavior.
- **Fail immediately for every non-natural obstruction:** not applied blindly because that would also refuse targets reachable around a wall. The task now checks for a visible safe stand point and fails early only when no such approach exists.
- **Persistent registry of live companions across unloaded chunks:** still a known lifecycle edge case for owners with multiple companions. The current command guard blocks manual spawning while a respawn is pending, but it does not persist a set of every live companion UUID across unloaded chunks.
- The unused `FollowOwnerGoal.max` constructor field and the cross-level `TaskManager.tick` iteration cost remain low-priority cleanup/performance items.

## Verification

- `python tools/source_audit.py`: PASS — 146 Java files, 54 registered tools, 54 unique names, 0 errors.
- `python tools/verify_repair_regressions.py`: PASS — 107 static regression checks, 0 errors.
- Python syntax checks for the project audit/export scripts: PASS.
- ZIP archive integrity: checked after packaging.
- `./gradlew clean test build --stacktrace --no-daemon`: BLOCKED before Java compilation. Gradle wrapper could not resolve `services.gradle.org` (`UnknownHostException`). This environment has Java 21 only, not the requested Java 17 toolchain.
- No Forge game session or multiplayer claims/protection-mod test was possible. Static markers do not prove runtime behavior.

## Useful API evidence reviewed

- Forge 1.20.1 `PathNavigation` lists `createPath(BlockPos, int, int)`: https://mcstreetguy.github.io/ForgeJavaDocs/1.20.1-latest/net/minecraft/world/entity/ai/navigation/PathNavigation.html
- Forge 1.20.1 `FakePlayer` exposes `FakePlayer(ServerLevel, GameProfile)`: https://lexxie.dev/forge/1.20.1/net/minecraftforge/common/util/FakePlayer.html
- Java tool-hit durability rule (two for tools, one for swords): https://minecraft.wiki/w/Tutorial%3ADurability_management
