package com.yaseen.npcmind.api;

import java.util.Set;

/**
 * Optional groups of built-in NPC Mind tools. A host mod can enable only the features it uses;
 * selecting a pack changes the exposed/executable tool surface, not the host mod's dependencies.
 */
public enum ToolPack {
    BASIC(Set.of(
            "describe_location", "inspect_inventory", "inspect_companion_inventory",
            "task_status", "inspect_nearby_entities", "inspect_look_block",
            "inspect_equipment", "inspect_ai_state", "manage_player_state")),
    /** Player-like hunger, air, freezing and XP state, plus bounded XP-orb pickup. */
    PLAYER_STATE(Set.of("manage_player_state", "collect_xp")),
    NAVIGATION(Set.of(
            "follow_owner", "move_to", "smart_navigate", "search_structure", "explore_mode",
            "inspect_ai_state")),
    COMBAT(Set.of(
            "combat_target", "combat_status", "bodyguard_mode", "prepare_loadout", "throw_ender_pearl", "fill_bucket",
            "inspect_equipment", "inspect_companion_inventory", "inspect_permissions")),
    GATHERING(Set.of(
            "gather_block", "gather_resources", "gather_wood", "mine_block", "smart_vein_mining",
            "inspect_resource_ledger", "reserve_resource", "inspect_registry")),
    CRAFTING(Set.of("craft_item", "inspect_recipes", "smelt_item", "cook_food")),
    BUILDING(Set.of(
            "build_blueprint", "build_pattern", "place_block", "light_area", "evaluate_base_site",
            "inspect_base_maintenance")),
    FARMING(Set.of("farm_area", "till_and_plant")),
    STORAGE(Set.of(
            "inspect_container", "find_nearby_container", "deposit_item", "take_from_container",
            "inspect_companion_inventory", "inspect_inventory")),
    KNOWLEDGE(Set.of(
            "remember", "teach_trigger", "inspect_world_memory", "record_plan_failure",
            "record_verification", "manage_long_term_goal", "daily_report")),
    EXPLORATION(Set.of("explore_mode", "search_structure", "evaluate_base_site", "describe_location")),
    VILLAGERS(Set.of("inspect_villager_trades", "trade_with_villager")),
    TEAM(Set.of("manage_companion_team", "bodyguard_mode")),
    MAINTENANCE(Set.of(
            "inspect_base_maintenance", "inspect_ai_state", "inspect_permissions",
            "inspect_resource_ledger")),
    AUTONOMY(Set.of("manage_autonomy", "manage_long_term_goal", "daily_report")),
    MOD_INTEGRATION(Set.of("inspect_forge_energy", "inspect_registry")),
    /** A composed starter profile, not a mandatory runtime mode. */
    SURVIVAL(Set.of(
            "describe_location", "inspect_inventory", "inspect_companion_inventory", "task_status",
            "inspect_nearby_entities", "inspect_look_block", "inspect_equipment", "inspect_ai_state",
            "manage_player_state", "collect_xp", "follow_owner", "move_to", "smart_navigate", "combat_target", "combat_status", "throw_ender_pearl", "fill_bucket",
            "bodyguard_mode", "prepare_loadout", "gather_block", "gather_resources", "gather_wood",
            "mine_block", "smart_vein_mining", "inspect_resource_ledger", "reserve_resource",
            "craft_item", "inspect_recipes", "smelt_item", "cook_food", "build_blueprint",
            "build_pattern", "place_block", "light_area", "evaluate_base_site", "farm_area",
            "till_and_plant", "inspect_container", "find_nearby_container", "deposit_item",
            "take_from_container", "manage_autonomy", "explore_mode", "search_structure",
            "remember", "inspect_world_memory", "inspect_permissions")),
    /** All current built-in tools. Prefer explicit packs for new integrations. */
    ALL(Set.of());

    private final Set<String> toolNames;
    ToolPack(Set<String> toolNames) { this.toolNames = toolNames; }
    public Set<String> toolNames() { return toolNames; }
}
