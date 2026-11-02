package com.yaseen.npcmind.tool;

import com.yaseen.npcmind.tool.impl.InspectInventoryTool;
import com.yaseen.npcmind.tool.impl.InspectNearbyEntitiesTool;
import com.yaseen.npcmind.tool.impl.InspectLookBlockTool;
import com.yaseen.npcmind.tool.impl.LocationTool;
import com.yaseen.npcmind.tool.impl.InspectRegistryTool;
import com.yaseen.npcmind.tool.impl.RememberTool;
import com.yaseen.npcmind.tool.impl.TeachTriggerTool;
import com.yaseen.npcmind.tool.impl.FollowOwnerTool;
import com.yaseen.npcmind.tool.impl.MoveToTool;
import com.yaseen.npcmind.tool.impl.MineBlockTool;
import com.yaseen.npcmind.tool.impl.TaskStatusTool;
import com.yaseen.npcmind.tool.impl.InspectCompanionInventoryTool;
import com.yaseen.npcmind.tool.impl.InspectAITool;
import com.yaseen.npcmind.tool.impl.InspectContainerTool;
import com.yaseen.npcmind.tool.impl.DepositItemTool;
import com.yaseen.npcmind.tool.impl.TakeFromContainerTool;
import com.yaseen.npcmind.tool.impl.FindNearbyContainerTool;
import com.yaseen.npcmind.tool.impl.InspectRecipesTool;
import com.yaseen.npcmind.tool.impl.CraftItemTool;
import com.yaseen.npcmind.tool.impl.SmeltItemTool;
import com.yaseen.npcmind.tool.impl.InspectEquipmentTool;
import com.yaseen.npcmind.tool.impl.InspectEnergyTool;
import com.yaseen.npcmind.tool.impl.CombatStatusTool;
import com.yaseen.npcmind.tool.impl.InspectVillagerTradesTool;
import com.yaseen.npcmind.tool.impl.TradeWithVillagerTool;
import com.yaseen.npcmind.tool.impl.SmartVeinMiningTool;
import com.yaseen.npcmind.tool.impl.BuildPatternTool;
import com.yaseen.npcmind.tool.impl.BuildBlueprintTool;
import com.yaseen.npcmind.tool.impl.WorldMemoryTool;
import com.yaseen.npcmind.tool.impl.GoalTool;
import com.yaseen.npcmind.tool.impl.BodyguardTool;
import com.yaseen.npcmind.tool.impl.ExploreTool;
import com.yaseen.npcmind.tool.impl.StructureSearchTool;
import com.yaseen.npcmind.tool.impl.BaseSiteTool;
import com.yaseen.npcmind.tool.impl.RescueStatusTool;
import com.yaseen.npcmind.tool.impl.SmartNavigateTool;
import com.yaseen.npcmind.tool.impl.ResourceLedgerTool;
import com.yaseen.npcmind.tool.impl.ReserveResourceTool;
import com.yaseen.npcmind.tool.impl.LoadoutTool;
import com.yaseen.npcmind.tool.impl.MaintenanceTool;
import com.yaseen.npcmind.tool.impl.AutonomyTool;
import com.yaseen.npcmind.tool.impl.TeamTool;
import com.yaseen.npcmind.tool.impl.DailyReportTool;
import com.yaseen.npcmind.tool.impl.PlanAlternativeTool;
import com.yaseen.npcmind.tool.impl.FarmAreaTool;
import com.yaseen.npcmind.tool.impl.GatherWoodTool;
import com.yaseen.npcmind.tool.impl.GatherBlockTool;
import com.yaseen.npcmind.tool.impl.PlaceBlockTool;
import com.yaseen.npcmind.tool.impl.LightAreaTool;
import com.yaseen.npcmind.tool.impl.CookFoodTool;
import com.yaseen.npcmind.tool.impl.TillAndPlantTool;
import com.yaseen.npcmind.tool.impl.GatherResourcesTool;
import com.yaseen.npcmind.tool.impl.CombatTargetTool;
import com.yaseen.npcmind.tool.impl.GiveItemTool;
import com.yaseen.npcmind.tool.impl.ExperimentTool;
import com.yaseen.npcmind.tool.impl.ManagePlayerStateTool;
import com.yaseen.npcmind.tool.impl.CollectExperienceTool;
import com.yaseen.npcmind.tool.impl.ThrowEnderPearlTool;
import com.yaseen.npcmind.tool.impl.FillWaterBucketTool;
import com.yaseen.npcmind.tool.impl.PermissionTool;
import com.yaseen.npcmind.api.ToolPack;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Comparator;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class ToolRegistry {
    private static final Map<String, AITool> TOOLS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> BUILTIN_NAMES = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> EXTERNAL_NAMES = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> ENABLED_NAMES = ConcurrentHashMap.newKeySet();
    static {
        register(new InspectInventoryTool());
        register(new InspectNearbyEntitiesTool());
        register(new InspectLookBlockTool());
        register(new LocationTool());
        register(new InspectRegistryTool());
        register(new RememberTool());
        register(new TeachTriggerTool());
        register(new FollowOwnerTool());
        register(new MoveToTool());
        register(new MineBlockTool());
        register(new TaskStatusTool());
        register(new InspectCompanionInventoryTool());
        register(new InspectAITool());
        register(new InspectContainerTool());
        register(new DepositItemTool());
        register(new TakeFromContainerTool());
        register(new FindNearbyContainerTool());
        register(new InspectRecipesTool());
        register(new CraftItemTool());
        register(new SmeltItemTool());
        register(new InspectEquipmentTool());
        register(new InspectEnergyTool());
        register(new CombatStatusTool());
        register(new InspectVillagerTradesTool());
        register(new TradeWithVillagerTool());
        register(new SmartVeinMiningTool());
        register(new BuildPatternTool());
        register(new BuildBlueprintTool());
        register(new WorldMemoryTool());
        register(new GoalTool());
        register(new BodyguardTool());
        register(new ExploreTool());
        register(new StructureSearchTool());
        register(new BaseSiteTool());
        register(new RescueStatusTool());
        register(new SmartNavigateTool());
        register(new ResourceLedgerTool());
        register(new ReserveResourceTool());
        register(new LoadoutTool());
        register(new MaintenanceTool());
        register(new AutonomyTool());
        register(new TeamTool());
        register(new DailyReportTool());
        register(new PlanAlternativeTool());
        register(new FarmAreaTool());
        register(new GatherWoodTool());
        register(new GatherBlockTool());
        register(new PlaceBlockTool());
        register(new LightAreaTool());
        register(new CookFoodTool());
        register(new TillAndPlantTool());
        register(new GatherResourcesTool());
        register(new CombatTargetTool());
        register(new GiveItemTool());
        register(new ExperimentTool());
        register(new PermissionTool());
        register(new ManagePlayerStateTool());
        register(new CollectExperienceTool());
        register(new ThrowEnderPearlTool());
        register(new FillWaterBucketTool());
    }

    private static void register(AITool tool) {
        AITool previous = TOOLS.putIfAbsent(tool.name(), tool);
        if (previous != null) throw new IllegalStateException("Duplicate built-in NPC Mind tool: " + tool.name());
        BUILTIN_NAMES.add(tool.name());
        ENABLED_NAMES.add(tool.name()); // Backward-compatible default; host mods can narrow this explicitly.
    }

    /** Adds a host-mod capability to the shared catalog during common setup. */
    public static synchronized void registerExternal(AITool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("A tool with a non-blank name is required");
        }
        AITool previous = TOOLS.putIfAbsent(tool.name(), tool);
        if (previous != null && previous != tool) {
            throw new IllegalArgumentException("Tool name already registered: " + tool.name());
        }
        EXTERNAL_NAMES.add(tool.name());
        ENABLED_NAMES.add(tool.name()); // Registering an extension is an explicit opt-in.
    }

    /** Check both registration and activation; disabled built-ins cannot be resolved/executed. */
    public static boolean isEnabled(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (ENABLED_NAMES.contains(trimmed)) return true;
        String mapped = ALIASES.get(trimmed.toLowerCase(java.util.Locale.ROOT));
        return mapped != null && ENABLED_NAMES.contains(mapped);
    }

    public static boolean isRegistered(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        return TOOLS.containsKey(trimmed) || ALIASES.containsKey(trimmed.toLowerCase(java.util.Locale.ROOT));
    }

    /** Enable a single installed tool by its stable name. */
    public static synchronized void enableTool(String name) {
        String normalized = name == null ? "" : name.trim();
        if (!TOOLS.containsKey(normalized)) throw new IllegalArgumentException("Unknown NPC Mind tool: " + normalized);
        ENABLED_NAMES.add(normalized);
    }

    /** Disable a built-in or external tool without removing its implementation from the library JAR. */
    public static synchronized void disableTool(String name) {
        if (name == null) return;
        String normalized = name.trim();
        if (TOOLS.containsKey(normalized)) ENABLED_NAMES.remove(normalized);
    }

    /**
     * Replace the active built-in command surface with only the selected packs. Externally
     * registered tools remain enabled because registration is an explicit host-mod opt-in.
     * No selected packs means no built-in tool is active.
     */
    public static synchronized void enableOnlyPacks(java.util.Set<ToolPack> packs) {
        java.util.Set<ToolPack> selected = packs == null ? java.util.Set.of() : java.util.Set.copyOf(packs);
        java.util.Set<String> next = ConcurrentHashMap.newKeySet();
        next.addAll(EXTERNAL_NAMES);
        if (selected.contains(ToolPack.ALL)) {
            next.addAll(BUILTIN_NAMES);
        } else {
            for (ToolPack pack : selected) {
                if (pack == null) continue;
                if (pack == ToolPack.ALL) continue;
                next.addAll(pack.toolNames());
            }
            next.retainAll(TOOLS.keySet());
            next.addAll(EXTERNAL_NAMES);
        }
        ENABLED_NAMES.clear();
        ENABLED_NAMES.addAll(next);
    }

    /** Replace the active surface with named tools; this allows even finer-grained selection than packs. */
    public static synchronized void enableOnlyTools(java.util.Set<String> names) {
        java.util.Set<String> next = ConcurrentHashMap.newKeySet();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) continue;
                String normalized = name.trim();
                if (!TOOLS.containsKey(normalized)) throw new IllegalArgumentException("Unknown NPC Mind tool: " + normalized);
                next.add(normalized);
            }
        }
        // Match enableOnlyPacks: externally registered tools are an explicit host-mod opt-in and
        // must survive every narrowing call instead of disappearing without a trace.
        next.addAll(EXTERNAL_NAMES);
        ENABLED_NAMES.clear();
        ENABLED_NAMES.addAll(next);
    }

    public static synchronized void enableAllTools() {
        ENABLED_NAMES.clear();
        ENABLED_NAMES.addAll(TOOLS.keySet());
    }

    public static java.util.Set<String> enabledToolNames() {
        return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(ENABLED_NAMES));
    }

    /** Optional English aliases for common host-mod input. Duplicate aliases are harmless. */
    private static final Map<String, String> ALIASES = buildAliases();
    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new java.util.HashMap<>();
        aliases.putIfAbsent("attack", "combat_target");
        aliases.putIfAbsent("fight", "combat_target");
        aliases.putIfAbsent("follow", "follow_owner");
        aliases.putIfAbsent("come", "follow_owner");
        aliases.putIfAbsent("mine", "mine_block");
        aliases.putIfAbsent("explore", "explore_mode");
        aliases.putIfAbsent("search", "search_structure");
        aliases.putIfAbsent("loadout", "prepare_loadout");
        aliases.putIfAbsent("world_memory", "inspect_world_memory");
        aliases.putIfAbsent("xp", "manage_player_state");
        aliases.putIfAbsent("experience", "manage_player_state");
        return java.util.Collections.unmodifiableMap(aliases);
    }
    public static AITool get(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String key = TOOLS.containsKey(trimmed) ? trimmed : ALIASES.get(trimmed.toLowerCase(java.util.Locale.ROOT));
        return key == null || !ENABLED_NAMES.contains(key) ? null : TOOLS.get(key);
    }
    /** Immutable deterministic snapshot of the currently enabled tool surface. */
    public static Collection<AITool> all() {
        return ENABLED_NAMES.stream().map(TOOLS::get).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(AITool::name)).toList();
    }

    /** Immutable deterministic catalogue of all installed tools, including disabled optional tools. */
    public static Collection<AITool> registeredTools() {
        return TOOLS.values().stream().sorted(Comparator.comparing(AITool::name)).toList();
    }

    /** Returns a language-neutral catalogue. Host mods own localization and intent routing. */
    /**
     * Tools included in every exported tool catalogue: the minimum local surface needed to move, follow,
     * fight, mine, gather, build, search, inspect and report status. Everything else is added
     * only when the player's request mentions a matching keyword.
     */
    private static final java.util.Set<String> CORE_TOOLS = java.util.Set.of(
            "move_to", "smart_navigate", "follow_owner", "combat_target", "task_status",
            "describe_location", "inspect_equipment", "mine_block", "gather_resources",
            "search_structure", "place_block", "build_pattern", "inspect_inventory");

    /** keyword(s) -> extra tools. Keys are lower-case substrings separated by '|'. */
    private static final java.util.Map<String, java.util.List<String>> TOOL_KEYWORDS = java.util.Map.ofEntries(
            java.util.Map.entry("wood|log|tree|chop|lumber", java.util.List.of("gather_wood", "gather_block")),
            java.util.Map.entry("stone|ore|iron|gold|diamond|coal|copper|mine|dig|vein", java.util.List.of("smart_vein_mining", "gather_block")),
            java.util.Map.entry("build|wall|house|floor|tower|bridge|blueprint|pattern", java.util.List.of("build_blueprint", "light_area")),
            java.util.Map.entry("farm|crop|harvest|plant|till|seed|wheat|carrot|potato", java.util.List.of("farm_area", "till_and_plant")),
            java.util.Map.entry("cook|smelt|furnace|food|bake", java.util.List.of("cook_food", "smelt_item")),
            java.util.Map.entry("craft|recipe|make|brew", java.util.List.of("craft_item", "inspect_recipes")),
            java.util.Map.entry("chest|container|store|deposit|take|stash", java.util.List.of("find_nearby_container", "deposit_item", "take_from_container", "inspect_container")),
            java.util.Map.entry("fight|attack|kill|guard|protect|defend|combat", java.util.List.of("combat_status", "bodyguard_mode")),
            java.util.Map.entry("explore|search|find|structure|village|stronghold|fortress|monument", java.util.List.of("explore_mode")),
            java.util.Map.entry("equip|armor|weapon|tool|loadout|gear", java.util.List.of("prepare_loadout", "inspect_companion_inventory")),
            java.util.Map.entry("villager|trade|emerald", java.util.List.of("trade_with_villager", "inspect_villager_trades")),
            java.util.Map.entry("energy|forge energy|fe ", java.util.List.of("inspect_forge_energy")),
            java.util.Map.entry("remember|memory|recall", java.util.List.of("remember", "inspect_world_memory")),
            java.util.Map.entry("teach trigger|teach the bot|when i say|علّم البوت|علم البوت", java.util.List.of("teach_trigger")),
            java.util.Map.entry("base|home|site|shelter", java.util.List.of("evaluate_base_site", "inspect_base_maintenance")),
            java.util.Map.entry("give|bring|hand", java.util.List.of("give_item_to_owner")),
            java.util.Map.entry("permission|allow|deny", java.util.List.of("inspect_permissions")),
            java.util.Map.entry("autonomy|idle|plan|goal|routine", java.util.List.of("manage_autonomy", "manage_long_term_goal", "daily_report")),
            java.util.Map.entry("nearby|entities|mobs|look", java.util.List.of("inspect_nearby_entities", "inspect_look_block")),
            java.util.Map.entry("registry|block id|item id", java.util.List.of("inspect_registry")),
            java.util.Map.entry("team|companions", java.util.List.of("manage_companion_team")),
            java.util.Map.entry("reserve|ledger|resource", java.util.List.of("reserve_resource", "inspect_resource_ledger")));

    public static String definitionsFor(String message) {
        String s = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(CORE_TOOLS);
        boolean matched = false;
        for (var entry : TOOL_KEYWORDS.entrySet()) {
            boolean hit = false;
            for (String keyword : entry.getKey().split("\\|")) {
                if (s.contains(keyword)) { hit = true; break; }
            }
            if (hit) { selected.addAll(entry.getValue()); matched = true; }
        }
        // Safe fallback: if no keyword group matches, expose the full catalogue so a host integration
        // can still inspect and choose a capability.
        if (!matched) return definitions();
        StringBuilder b = new StringBuilder();
        for (AITool t : all()) {
            if (!selected.contains(t.name())) continue;
            b.append("TOOL ").append(t.name()).append('\n');
            b.append(t.description()).append('\n');
            b.append("ARGUMENTS ").append(t.schema()).append("\n\n");
        }
        return b.toString();
    }

    /** Export JSON Schema descriptors for the tools relevant to a message. This builds local data only;
     * NPC Mind does not send these descriptors to any provider or server. */
    public static com.google.gson.JsonArray jsonToolDefinitionsFor(String message) {
        String defs = definitionsFor(message);
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String line : defs.split("\\n")) {
            if (line.startsWith("TOOL ")) selected.add(line.substring(5).trim());
        }
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (AITool t : all()) {
            if (!selected.contains(t.name())) continue;
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
            fn.addProperty("name", t.name()); fn.addProperty("description", t.description()); fn.add("parameters", ToolContract.toJsonSchema(t)); fn.addProperty("strict", false);
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject(); wrapper.addProperty("type", "function"); wrapper.add("function", fn); out.add(wrapper);
        }
        return out;
    }

    /** Export the full local tool catalogue as JSON Schema data for host-mod integrations. */
    public static com.google.gson.JsonArray jsonToolDefinitions() {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (AITool t : all()) {
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", ToolContract.toJsonSchema(t)); fn.addProperty("strict", false);
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", fn);
            out.add(wrapper);
        }
        return out;
    }

    /** Compact local catalogue for debugging or host-mod integrations. */
    public static String compactCatalog() {
        StringBuilder b = new StringBuilder();
        for (AITool t : all()) {
            b.append(t.name()).append(" :: ").append(t.description()).append(" :: ").append(t.schema()).append('\n');
        }
        return b.toString();
    }

    public static String definitions() {
        StringBuilder b = new StringBuilder();
        for (AITool t : all()) {
            b.append("TOOL ").append(t.name()).append('\n');
            b.append(t.description()).append('\n');
            b.append("ARGUMENTS ").append(t.schema()).append("\n\n");
        }
        return b.toString();
    }
    private ToolRegistry() {}
}
