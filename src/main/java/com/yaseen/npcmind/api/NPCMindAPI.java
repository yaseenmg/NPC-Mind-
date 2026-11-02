package com.yaseen.npcmind.api;

import com.yaseen.npcmind.agent.AgentEngine;
import com.yaseen.npcmind.agent.ToolSelectionRouter;
import com.yaseen.npcmind.core.ServerThreadGuard;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.ToolRegistry;
import com.yaseen.npcmind.tool.ToolContract;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Nullability contract (docs/API_STABILITY.md):
// - @NotNull on a return type: the method never returns null (it returns an empty
//   collection/Optional/false/DENIED string instead).
// - @NotNull on a parameter: null is not a legal value; callers must not pass it.
// - @Nullable on a parameter: null is explicitly tolerated and handled (documented per method).
// Parameters left unannotated are primitives or varargs-free value types whose nullability
// is not part of the contract.

/**
 * Public, local-only integration surface for other Minecraft mods.
 *
 * <p>Host mods may register an {@link AITool}, add one or more deterministic keyword intents,
 * inspect the installed tool catalogue, and submit an owner command. This API contains no HTTP
 * client, remote AI provider, key handling or server dependency. A host mod that wants its own
 * optional language model must own that integration separately.</p>
 */
public final class NPCMindAPI {
    public static final String API_VERSION = "1.3.1";

    /** Immutable metadata for a registered tool; schema uses NPC Mind's documented compact DSL. */
    public record ToolInfo(String name, String description, String schema) {}

    /** A deterministic host-defined route for an entity-agnostic tool. */
    public record EntityCommandRoute(String tool, String arguments, String reason) {}

    private NPCMindAPI() {}

    /** Register a tool during mod setup before it can be selected by an intent. Duplicate names are rejected. */
    public static void registerTool(@NotNull AITool tool) {
        ToolRegistry.registerExternal(tool);
    }

    /** Activate only the requested built-in packs; a null array or null elements are ignored. Use this during common setup before commands arrive. */
    public static void enableOnlyPacks(@Nullable ToolPack... packs) {
        java.util.EnumSet<ToolPack> selected = java.util.EnumSet.noneOf(ToolPack.class);
        if (packs != null) for (ToolPack pack : packs) if (pack != null) selected.add(pack);
        ToolRegistry.enableOnlyPacks(selected);
    }

    /** Activate only the requested individual tools; a null array or null/blank names are ignored. */
    public static void enableOnlyTools(@Nullable String... toolNames) {
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        if (toolNames != null) for (String name : toolNames) if (name != null && !name.isBlank()) selected.add(name.trim());
        ToolRegistry.enableOnlyTools(selected);
    }

    /** Enable or disable one bundled tool by stable name. */
    public static void setToolEnabled(@NotNull String toolName, boolean enabled) {
        if (enabled) ToolRegistry.enableTool(toolName); else ToolRegistry.disableTool(toolName);
    }

    public static @NotNull java.util.Set<String> enabledToolNames() { return ToolRegistry.enabledToolNames(); }

    /** Register a tool that works with any host-selected LivingEntity actor. Tool names must be unique. */
    public static void registerEntityTool(@NotNull EntityAITool tool) { EntityToolRegistry.register(tool); }

    /** Register fixed, schema-validated command aliases for an entity tool. */
    public static void registerEntityIntent(@NotNull String[] phrases, @NotNull String toolName,
                                            @Nullable String jsonArguments, @Nullable String reason) {
        EntityIntentRegistry.register(phrases, toolName, jsonArguments, reason);
    }

    /** Resolve host-defined aliases without reading or mutating world state. */
    public static @NotNull Optional<EntityCommandRoute> resolveEntityCommand(@Nullable String command) {
        return Optional.ofNullable(EntityIntentRegistry.resolve(command));
    }

    /**
     * Execute a registered generic entity tool. Call after the host mod's own authorization checks
     * and on the logical server thread. The controller is optional for autonomous NPCs; NPC Mind
     * cannot infer ownership or permission rules for an arbitrary host entity.
     */
    public static @NotNull String executeEntityTool(@Nullable LivingEntity actor, @Nullable ServerPlayer controller,
                                                    @NotNull String toolName, @Nullable String arguments) {
        if (actor == null) return "DENIED missing_actor";
        if (!ServerThreadGuard.isOnServerThread(actor)) return "DENIED must_run_on_server_thread";
        if (!actor.isAlive()) return "DENIED actor_not_available_on_server";
        if (controller != null && actor.level() != controller.level()) return "DENIED server_level_mismatch";
        if (!(actor.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || serverLevel.getServer() == null || !serverLevel.getServer().isSameThread()) {
            return "DENIED must_run_on_server_thread";
        }
        EntityAITool tool = EntityToolRegistry.get(toolName);
        if (tool == null) return "FAILED unknown_entity_tool=" + toolName;
        ToolContract.Validation validation = ToolContract.validateSchema(tool.schema(), arguments);
        if (!validation.ok()) return "FAILED invalid_arguments " + validation.reason();
        try {
            String result = tool.execute(actor, controller, validation.normalized().toString());
            return result == null ? "TOOL_ERROR null_result" : result;
        } catch (Throwable error) {
            com.yaseen.npcmind.NPCMind.LOG.error("[NPCMind] external entity tool '{}' failed for actor {} controlled by {}",
                    toolName, actor.getUUID(), controller == null ? "autonomous" : controller.getUUID(), error);
            return "TOOL_ERROR " + error.getClass().getSimpleName();
        }
    }

    /** Resolve and execute one host-defined local command route. This does not call a remote AI
     * model or authorize the caller; the host must establish control/permissions before invocation.
     */
    public static @NotNull String submitEntityCommand(@Nullable LivingEntity actor,
                                                       @Nullable ServerPlayer controller,
                                                       @Nullable String command) {
        if (actor == null) return "DENIED missing_actor";
        EntityCommandRoute route = EntityIntentRegistry.resolve(command);
        if (route == null) return "FAILED no_matching_entity_intent";
        return executeEntityTool(actor, controller, route.tool(), route.arguments());
    }

    public static @NotNull List<String> entityToolNames() {
        return EntityToolRegistry.all().stream().map(EntityAITool::name).toList();
    }

    /**
     * Route normalized keywords to an existing local tool and fixed, schema-validated JSON args.
     * More-specific host intents are checked before built-in fallback rules.
     */
    public static void registerIntent(@NotNull String[] keywords, @NotNull String toolName,
                                      @NotNull String jsonArguments, @NotNull String reason) {
        ToolSelectionRouter.registerIntent(keywords, toolName, jsonArguments, reason);
    }

    /**
     * Entity-agnostic vanilla-style reach/line-of-sight preflight for blocks or interaction points.
     * This utility can be used by a host mod's own Mob, animal, boss, or custom NPC. It only checks
     * legality; the host remains responsible for invoking the item's real interaction and verifying
     * the resulting world state. Call on the logical server.
     */
    public static boolean canReachPoint(@NotNull LivingEntity actor, @NotNull Vec3 point, double reach) {
        return com.yaseen.npcmind.rules.PlayerActionRules.canReachPoint(actor, point, reach);
    }

    /** Entity-agnostic reach and collision-line-of-sight check for combat or interaction targets. */
    public static boolean canReachEntity(@NotNull LivingEntity actor, @NotNull LivingEntity target, double reach) {
        return com.yaseen.npcmind.rules.PlayerActionRules.canReachEntity(actor, target, reach);
    }

    /** Read optional, locally persisted player-like XP state for any LivingEntity. */
    public static @NotNull PlayerLikeExperience.Snapshot experience(@NotNull LivingEntity actor) {
        return PlayerLikeExperience.read(actor);
    }

    /**
     * Grant vanilla-curve XP points to any LivingEntity. Must be called on the server
     * thread: the guard {@code getServer().isSameThread()} silently returns {@code false}
     * when invoked from another thread (review note #25). Host mods that compute work on
     * worker threads should schedule this call through {@code server.execute(...)}.
     */
    public static boolean grantExperience(@Nullable LivingEntity actor, int points) {
        if (!ServerThreadGuard.isOnServerThread(actor)) return false;
        return PlayerLikeExperience.grant(actor, points);
    }

    /** Read optional player-like food/health/environment/XP state for any LivingEntity. */
    public static @NotNull PlayerLikeState.Snapshot playerLikeState(@NotNull LivingEntity actor) {
        return PlayerLikeState.read(actor);
    }

    /** Tick opt-in player-like hunger/regen for a generic LivingEntity; call once per server tick. */
    public static boolean tickPlayerLikeState(@NotNull LivingEntity actor) {
        return PlayerLikeState.tick(actor);
    }

    /** Add player-scaled exhaustion (4 points consume one saturation or food point). */
    public static boolean addPlayerLikeExhaustion(@NotNull LivingEntity actor, float amount) {
        return PlayerLikeState.addExhaustion(actor, amount);
    }

    /** Set a generic NPC's player-like food bar without forcing an inventory implementation. */
    public static boolean setPlayerLikeFoodLevel(@NotNull LivingEntity actor, int foodLevel) {
        return PlayerLikeState.setFoodLevel(actor, foodLevel);
    }

    /** Apply nutrition and saturation after a host tool accepts food consumption. */
    public static boolean consumePlayerLikeFood(@NotNull LivingEntity actor, int nutrition, float saturationModifier) {
        return PlayerLikeState.consumeFood(actor, nutrition, saturationModifier);
    }

    /** Return every installed tool, including optional tools currently disabled by the host mod. */
    public static @NotNull List<ToolInfo> tools() {
        return ToolRegistry.registeredTools().stream()
                .map(tool -> new ToolInfo(tool.name(), tool.description(), tool.schema()))
                .toList();
    }

    /** Return only tools that are currently active and may be selected/executed. */
    public static @NotNull List<ToolInfo> enabledTools() {
        return ToolRegistry.all().stream()
                .map(tool -> new ToolInfo(tool.name(), tool.description(), tool.schema()))
                .toList();
    }

    /** Resolve a command locally without executing it or reading world state; a null/blank command yields an empty Optional. */
    public static @NotNull Optional<ToolSelectionRouter.Route> resolveCommand(@Nullable String command) {
        ToolSelectionRouter.Route route = ToolSelectionRouter.first(command);
        return route != null && ToolRegistry.isEnabled(route.tool()) ? Optional.of(route) : Optional.empty();
    }

    /** True when this companion currently has a command worker running; null yields false. */
    public static boolean isBusy(@Nullable CompanionEntity companion) {
        return companion != null && AgentEngine.isBusy(companion.getUUID());
    }


    /** True when a worker is registered for a companion UUID; null yields false. */
    public static boolean isBusy(@Nullable UUID companionId) {
        return companionId != null && AgentEngine.isBusy(companionId);
    }

    /**
     * Submit a command through the same validated, owner-gated, local execution path used by chat.
     * Returns false for invalid parameters, client-side calls, another level, or a non-owner.
     * Call this from the server thread.
     */
    public static boolean submitCommand(@Nullable CompanionEntity companion, @Nullable ServerPlayer owner, @Nullable String command) {
        if (companion == null || owner == null || command == null || command.isBlank()) return false;
        if (!ServerThreadGuard.isOnServerThread(companion)) return false;
        if (companion.level().isClientSide() || companion.level() != owner.level()) return false;
        if (!owner.getUUID().equals(companion.getOwner())) return false;
        companion.ask(command);
        return true;
    }
}
