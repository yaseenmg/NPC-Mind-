package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.autonomy.ResourceLedger;
import com.yaseen.npcmind.crafting.ResourcePlanner;
import com.yaseen.npcmind.crafting.InventoryOps;
import com.yaseen.npcmind.task.TaskManager;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Deterministic server-side crafting engine.
 *
 * <p>The old implementation placed every recipe ingredient in consecutive slots. That is not
 * valid for shaped recipes such as sticks (vertical pattern), pickaxes, swords, etc. This version
 * uses the live RecipeManager, preserves shaped recipe coordinates, handles shapeless recipes,
 * recursively crafts intermediate ingredients, and commits inventory changes only after a full
 * simulation succeeds.</p>
 */
@Internal
public final class CraftItemTool implements AITool {
    private static final int MAX_RECIPE_DEPTH = 12;
    private static final int MAX_RECIPE_ATTEMPTS = 128;
    private static final int CRAFT_GRID = 3;
    private static final long RECIPE_CACHE_TTL_MS = 60_000L;
    private record CachedRecipes(long createdAt, List<CraftingRecipe> recipes) {}
    private static final Map<RecipeManager, Map<Item, CachedRecipes>> RECIPE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Persistent-in-memory continuation for multi-step crafting. A real-world prerequisite can
     * take many ticks (mine logs, hunt sheep, pick up drops, smelt an ingot). Without this queue,
     * the first STARTED result stranded the top-level craft goal and the user had to repeat it.
     */
    private record PendingCraft(String outputId, int requested, UUID ownerId, long notBeforeTick,
                                long startedAtTick, int retries) {}
    private static final Map<UUID, PendingCraft> PENDING = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_RETRIES = 16;
    private static final long PENDING_LIFETIME_TICKS = 20L * 60L * 10L;

    /** Clear recipe metadata after datapack/resource reloads so modded recipes never stay stale. */
    public static void clearRecipeCache() {
        synchronized (RECIPE_CACHE) { RECIPE_CACHE.clear(); }
    }

    @Override public String name() { return "craft_item"; }

    @Override public String description() {
        return "Craft an item from the live server RecipeManager using the companion's real inventory. "
                + "Supports shaped and shapeless recipes, recursive intermediate ingredients, recipe tags, "
                + "modded/datapack recipes, crafting-table requirements, and verified inventory commits.";
    }

    @Override public String schema() { return "{\"output\":\"item_id_or_name\",\"count\":number(optional)}"; }

    @Override
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        if (!companion.getPermissions().allowed(ActionPermissions.Action.CRAFT)) return "DENIED permission=craft";
        if (player == null || player.serverLevel() == null) return "FAILED no_server_context";

        String query = JsonArgs.string(arguments, "output");
        query = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        int requested = Math.max(1, Math.min(64, JsonArgs.integer(arguments, "count", 1)));
        if (query.isBlank()) return "FAILED output_is_required";

        Item target = resolveItem(query, companion.getCompanionInventory());
        if (target == null) return "FAILED no_live_item_matches output=" + query;

        int before = countItem(companion.getCompanionInventory(), target);
        if (before >= requested) {
            return "SUCCESS already_have=" + before + " output=" + id(target);
        }

        // BedRecipe is a vanilla special recipe: its result depends on the wool color and its
        // getResultItem() is not a reliable way to discover a fixed output. Handle it through the
        // live RecipeManager and a real 3x3 crafting grid instead of treating beds as unsupported.
        if (target instanceof BedItem || query.equals("bed") || query.equals("beds") || query.equals("minecraft:bed")) {
            String bed = craftBed(companion, player, target, requested);
            if (bed != null) {
                int bedAfter = countItem(companion.getCompanionInventory(), target);
                if (bed.startsWith("STARTED")) {
                    registerPending(companion, player, target, requested);
                } else if (bed.startsWith("SUCCESS") && bedAfter >= requested) {
                    clearPendingIfMatches(companion.getUUID(), id(target));
                } else {
                    clearPendingIfMatches(companion.getUUID(), id(target));
                    int gainedBeds = Math.max(0, bedAfter - before);
                    if (gainedBeds > 0 && bedAfter < requested) {
                        return "PARTIAL crafted=" + gainedBeds + " output=" + id(target)
                                + " requested=" + requested + " reason=" + bed;
                    }
                }
                return bed;
            }
        }

        CraftResult result = craftRecursive(companion, player, target, requested, new HashSet<>(), 0);
        int after = countItem(companion.getCompanionInventory(), target);
        int gained = Math.max(0, after - before);

        if (result.started) {
            registerPending(companion, player, target, requested);
            return "STARTED craft_plan output=" + id(target) + " phase=" + result.reason;
        }
        if (gained >= requested - before) {
            clearPendingIfMatches(companion.getUUID(), id(target));
            return "SUCCESS crafted=" + gained + " output=" + id(target) + " steps=" + result.steps;
        }
        if (gained > 0) {
            clearPendingIfMatches(companion.getUUID(), id(target));
            return "PARTIAL crafted=" + gained + " output=" + id(target) + " steps=" + result.steps + " reason=" + result.reason;
        }
        clearPendingIfMatches(companion.getUUID(), id(target));
        return "FAILED " + result.reason + " output=" + id(target);
    }

    private static CraftResult craftRecursive(CompanionEntity companion, ServerPlayer player,
                                              Item target, int requested, Set<Item> active, int depth) {
        Container inv = companion.getCompanionInventory();
        int existing = countItem(inv, target);
        if (existing >= requested) return new CraftResult(0, 0, "already_have_output", false);
        if (depth >= MAX_RECIPE_DEPTH) return new CraftResult(0, 0, "recipe_depth_limit=" + MAX_RECIPE_DEPTH, false);
        if (!active.add(target)) return new CraftResult(0, 0, "recipe_cycle=" + id(target), false);

        int crafted = 0;
        int steps = 0;
        String reason = "missing_real_ingredients";
        try {
            while (countItem(inv, target) < requested && steps < MAX_RECIPE_ATTEMPTS) {
                List<CraftingRecipe> recipes = findRecipes(player, target, inv);
                if (recipes.isEmpty()) { reason = "no_live_crafting_recipe"; break; }

                CraftingRecipe chosen = null;
                List<Integer> slots = null;
                String lastFailure = "no_recipe_was_craftable";

                for (CraftingRecipe recipe : recipes) {
                    if (!recipe.canCraftInDimensions(CRAFT_GRID, CRAFT_GRID)) {
                        lastFailure = "recipe_grid_too_large=" + recipe.getId();
                        continue;
                    }
                    if (requiresTable(recipe) && !hasNearbyCraftingTable(companion, 4)) {
                        String table = ensureCraftingTable(companion, player, active, depth);
                        if (table != null && table.startsWith("STARTED")) {
                            return new CraftResult(crafted, steps, table, true);
                        }
                        if (table == null || !table.startsWith("SUCCESS")) {
                            lastFailure = table == null ? "crafting_table_setup_failed" : table;
                            continue;
                        }
                    }

                    List<Integer> candidate = chooseIngredientSlots(recipe, inv, companion);
                    if (candidate != null) {
                        chosen = recipe;
                        slots = candidate;
                        break;
                    }

                    IngredientNeed missing = firstMissingIngredient(recipe, inv, companion);
                    if (missing == null) continue;

                    // A missing ingredient is not automatically a crafting failure.
                    // First ask the physical resource layer to acquire it from the world. This is
                    // what turns "craft sword" into gather -> workstation -> smelt -> craft.
                    ResourcePlanner.Decision acquisition = ResourcePlanner.acquire(companion, player, missing.ingredient);
                    if (acquisition.started()) {
                        return new CraftResult(crafted, steps, acquisition.result(), true);
                    }
                    if (!acquisition.result().isBlank() && (acquisition.result().startsWith("FAILED") || acquisition.result().startsWith("DENIED") || acquisition.result().startsWith("ERROR"))) {
                        lastFailure = acquisition.result();
                    }

                    Item intermediate = chooseCraftableItem(missing.ingredient, player, active, depth);
                    if (intermediate == null) {
                        lastFailure = "missing_ingredient=" + describeIngredient(missing.ingredient);
                        continue;
                    }
                    CraftResult sub = craftRecursive(companion, player, intermediate, 1, active, depth + 1);
                    steps += sub.steps + (sub.crafted > 0 ? 1 : 0);
                    if (sub.started) {
                        return new CraftResult(crafted, steps, "intermediate=" + id(intermediate) + " " + sub.reason, true);
                    }
                    if (sub.crafted > 0) {
                        candidate = chooseIngredientSlots(recipe, inv, companion);
                        if (candidate != null) {
                            chosen = recipe;
                            slots = candidate;
                            break;
                        }
                    }
                    lastFailure = "cannot_craft_intermediate=" + id(intermediate) + " (" + sub.reason + ")";
                }

                if (chosen == null || slots == null) {
                    reason = lastFailure;
                    break;
                }

                String direct = craftOnce(companion, player, chosen, slots);
                if (!direct.startsWith("SUCCESS")) {
                    reason = direct;
                    break;
                }
                ItemStack output = chosen.getResultItem(player.serverLevel().registryAccess());
                crafted += Math.max(1, output.getCount());
                steps++;
            }
        } finally {
            active.remove(target);
        }
        if (steps >= MAX_RECIPE_ATTEMPTS && countItem(inv, target) < requested) {
            reason = "craft_attempt_limit=" + MAX_RECIPE_ATTEMPTS;
        }
        return new CraftResult(crafted, steps, reason, false);
    }

    /**
     * Crafts one bed using three wool items of the same color and three planks. If wool is short,
     * it starts a real sheep hunt; Minecraft's death loot supplies the wool/mutton naturally.
     * Returns null only when the request is not a bed request (the normal recipe planner should run).
     */
    private static String craftBed(CompanionEntity companion, ServerPlayer player, Item targetBed, int requested) {
        Container inv = companion.getCompanionInventory();
        if (!hasNearbyCraftingTable(companion, 4)) {
            String table = ensureCraftingTable(companion, player, new HashSet<>(), 0);
            if (table != null && table.startsWith("STARTED")) return "STARTED bed_crafting_table=" + table;
            if (table == null || !table.startsWith("SUCCESS")) {
                return table == null ? "FAILED crafting_table_required_for_bed" : table;
            }
        }

        Map<Item, Integer> woolCounts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String path = id(stack.getItem()).substring(id(stack.getItem()).indexOf(':') + 1);
            if (path.endsWith("_wool")) woolCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        // Reservations are part of the library's resource contract: do not count wool reserved
        // for a different task as available for this bed.
        woolCounts.replaceAll((item, count) -> ResourceLedger.available(companion, id(item)));
        String targetPath = id(targetBed).substring(id(targetBed).indexOf(':') + 1);
        String requiredColor = targetPath.endsWith("_bed") ? targetPath.substring(0, targetPath.length() - 4) : "white";
        if (requiredColor.isBlank() || requiredColor.equals("bed")) requiredColor = "white";
        Item requiredWool = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", requiredColor + "_wool"));
        Item wool = requiredWool != null && woolCounts.getOrDefault(requiredWool, 0) >= 3 ? requiredWool : null;
        String preferredColor = requiredColor;
        if (wool == null) {
            int currentlyAvailable = woolCounts.getOrDefault(requiredWool, 0);
            int missingWool = Math.max(1, 3 - currentlyAvailable);
            String huntArgs = "{\"mode\":\"nearest_animal\",\"animal\":\"sheep\",\"color\":\"" + preferredColor
                    + "\",\"count\":" + missingWool + "}";
            String hunt = InternalToolExecutor.invoke(companion, player, "combat_target", huntArgs,
                    "internal-" + java.util.UUID.randomUUID()).result();
            if (hunt != null && hunt.startsWith("STARTED")) return "STARTED bed_materials=" + hunt;
            return "FAILED bed_requires_three_" + preferredColor + "_wool_items; " + (hunt == null ? "no_matching_sheep_found" : hunt);
        }

        List<Integer> woolSlots = takeSlots(inv, wool, 3, companion);
        List<Integer> plankSlots = findPlankSlots(inv, 3, companion);
        if (plankSlots == null) {
            String made = InternalToolExecutor.invoke(companion, player, "craft_item",
                    "{\"output\":\"minecraft:oak_planks\",\"count\":3}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            if (made != null && made.startsWith("STARTED")) return "STARTED bed_planks=" + made;
            if (made == null || !made.startsWith("SUCCESS")) return "FAILED bed_requires_three_planks; " + made;
            plankSlots = findPlankSlots(inv, 3, companion);
        }
        if (woolSlots == null || plankSlots == null) return "FAILED bed_ingredients_changed_during_planning";

        CraftingContainer grid = new TransientCraftingContainer(DUMMY_MENU, CRAFT_GRID, CRAFT_GRID);
        for (int i = 0; i < 3; i++) {
            grid.setItem(i, oneOf(inv.getItem(woolSlots.get(i))));
            grid.setItem(3 + i, oneOf(inv.getItem(plankSlots.get(i))));
        }
        // Forge 1.20.1: RecipeManager.getRecipeFor returns Optional<CraftingRecipe> (the Recipe
        // interface still carries its own id); the RecipeHolder wrapper only exists in 1.20.2+.
        var recipeMatch = player.serverLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, player.serverLevel());
        if (recipeMatch.isEmpty() || !recipeMatch.get().getId().getPath().contains("bed")) {
            return "FAILED live_bed_recipe_not_found_for_inventory_color";
        }
        CraftingRecipe recipe = recipeMatch.get();
        ItemStack output = recipe.assemble(grid, player.serverLevel().registryAccess()).copy();
        if (output.isEmpty()) return "FAILED live_bed_recipe_returned_empty_output";
        if (output.getItem() != targetBed) {
            return "FAILED bed_color_mismatch requested=" + id(targetBed) + " actual=" + id(output.getItem());
        }

        SimpleContainer simulated = copyInventory(inv);
        for (int slot : woolSlots) simulated.removeItem(slot, 1);
        for (int slot : plankSlots) simulated.removeItem(slot, 1);
        if (!InventoryOps.add(simulated, output.copy())) return "FAILED inventory_full_for_bed_output";
        for (ItemStack remainder : recipe.getRemainingItems(grid)) {
            if (!remainder.isEmpty() && !InventoryOps.add(simulated, remainder.copy())) return "FAILED inventory_full_for_bed_remainder";
        }
        for (int slot : woolSlots) inv.removeItem(slot, 1);
        for (int slot : plankSlots) inv.removeItem(slot, 1);
        if (!InventoryOps.add(inv, output.copy())) return "FAILED bed_commit_output_insert";
        for (ItemStack remainder : recipe.getRemainingItems(grid)) if (!remainder.isEmpty()) InventoryOps.add(inv, remainder.copy());
        inv.setChanged();
        companion.getSkills().add(SkillProgression.Skill.CRAFTING, 8);
        companion.getCompanionState().positiveEvent();
        companion.getCraftingKnowledge().recordSuccess(recipe.getId().toString(), output.getCount());
        if (countItem(inv, targetBed) < requested) {
            String more = craftBed(companion, player, targetBed, requested);
            if (more != null && (more.startsWith("STARTED") || more.startsWith("FAILED") || more.startsWith("DENIED"))) return more;
        }
        return "SUCCESS crafted=" + id(output.getItem()) + " recipe=" + recipe.getId();
    }

    private static List<Integer> takeSlots(Container inv, Item item, int count, CompanionEntity companion) {
        List<Integer> slots = new ArrayList<>();
        Map<Integer, Integer> used = new HashMap<>();
        for (int n = 0; n < count; n++) {
            int found = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                int already = used.getOrDefault(i, 0);
                if (!stack.isEmpty() && stack.is(item) && stack.getCount() > already
                        && ResourceLedger.canConsume(companion, stack, already + 1)) { found = i; used.put(i, already + 1); break; }
            }
            if (found < 0) return null;
            slots.add(found);
        }
        return slots;
    }

    private static List<Integer> findPlankSlots(Container inv, int count, CompanionEntity companion) {
        List<Integer> slots = new ArrayList<>();
        Map<Integer, Integer> used = new HashMap<>();
        for (int n = 0; n < count; n++) {
            int found = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                int already = used.getOrDefault(i, 0);
                if (stack.isEmpty() || stack.getCount() <= already || !id(stack.getItem()).endsWith("_planks")
                        || !ResourceLedger.canConsume(companion, stack, already + 1)) continue;
                found = i; used.put(i, already + 1); break;
            }
            if (found < 0) return null;
            slots.add(found);
        }
        return slots;
    }

    private static List<CraftingRecipe> findRecipes(ServerPlayer player, Item target, Container inventory) {
        RecipeManager manager = player.serverLevel().getRecipeManager();
        long now = System.currentTimeMillis();
        List<CraftingRecipe> out;
        synchronized (RECIPE_CACHE) {
            Map<Item, CachedRecipes> byItem = RECIPE_CACHE.computeIfAbsent(manager, ignored -> new HashMap<>());
            CachedRecipes cached = byItem.get(target);
            if (cached == null || now - cached.createdAt() > RECIPE_CACHE_TTL_MS) {
                List<CraftingRecipe> discovered = new ArrayList<>();
                for (CraftingRecipe recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
                    ItemStack result = recipe.getResultItem(player.serverLevel().registryAccess());
                    if (!result.isEmpty() && result.getItem() == target && !recipe.isSpecial()) discovered.add(recipe);
                }
                cached = new CachedRecipes(now, List.copyOf(discovered));
                byItem.put(target, cached);
            }
            out = new ArrayList<>(cached.recipes());
        }
        // Cache only recipe identity; item availability changes with every craft, so score candidates
        // against the live inventory each time instead of caching a stale choice.
        out.sort((a, b) -> {
            int sa = recipeAvailabilityScore(a, inventory);
            int sb = recipeAvailabilityScore(b, inventory);
            if (sa != sb) return Integer.compare(sb, sa);
            return a.getId().toString().compareTo(b.getId().toString());
        });
        return out;
    }

    private static String craftOnce(CompanionEntity companion, ServerPlayer player,
                                    CraftingRecipe recipe, List<Integer> slots) {
        Container inv = companion.getCompanionInventory();
        if (requiresTable(recipe) && !hasNearbyCraftingTable(companion, 4)) {
            return "FAILED crafting_table_required recipe=" + recipe.getId();
        }

        CraftingContainer grid = new TransientCraftingContainer(DUMMY_MENU, CRAFT_GRID, CRAFT_GRID);
        fillGrid(recipe, slots, inv, grid);
        if (!recipe.matches(grid, player.serverLevel())) {
            return "FAILED recipe_layout_not_matched=" + recipe.getId();
        }

        ItemStack actual = recipe.assemble(grid, player.serverLevel().registryAccess()).copy();
        if (actual.isEmpty()) return "FAILED empty_assembled_output=" + recipe.getId();

        // Full transaction simulation: consume inputs, add output and recipe remainders. No live
        // inventory mutation happens until every operation fits.
        SimpleContainer simulated = copyInventory(inv);
        for (int slot : slots) if (slot >= 0) simulated.removeItem(slot, 1);
        if (!InventoryOps.add(simulated, actual)) return "FAILED inventory_full_for_output=" + id(actual.getItem());
        for (ItemStack remainder : recipe.getRemainingItems(grid)) {
            if (!remainder.isEmpty() && !InventoryOps.add(simulated, remainder.copy())) {
                return "FAILED inventory_full_for_remainder=" + id(remainder.getItem());
            }
        }

        // Commit atomically from the already validated simulation.
        for (int slot : slots) if (slot >= 0) inv.removeItem(slot, 1);
        if (!InventoryOps.add(inv, actual)) return "FAILED commit_output_insert=" + id(actual.getItem());
        for (ItemStack remainder : recipe.getRemainingItems(grid)) {
            if (!remainder.isEmpty() && !InventoryOps.add(inv, remainder.copy())) {
                // Should be unreachable because the simulation above passed; report loudly rather
                // than pretending the craft succeeded.
                return "FAILED commit_remainder_insert=" + id(remainder.getItem());
            }
        }
        inv.setChanged();
        companion.getSkills().add(SkillProgression.Skill.CRAFTING, 8);
        companion.getCompanionState().positiveEvent();
        companion.getCraftingKnowledge().recordSuccess(recipe.getId().toString(), Math.max(1, actual.getCount()));
        return "SUCCESS crafted=" + id(actual.getItem()) + " recipe=" + recipe.getId();
    }

    private static void fillGrid(CraftingRecipe recipe, List<Integer> slots, Container inv, CraftingContainer grid) {
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int recipeIndex = y * w + x;
                    if (recipeIndex >= ingredients.size()) continue;
                    int invSlot = slots.get(recipeIndex);
                    if (invSlot >= 0) grid.setItem(y * CRAFT_GRID + x, oneOf(inv.getItem(invSlot)));
                }
            }
        } else {
            // Shapeless recipes may use any cells. We keep them packed in the first cells.
            for (int i = 0; i < ingredients.size() && i < 9; i++) {
                int invSlot = slots.get(i);
                if (invSlot >= 0) grid.setItem(i, oneOf(inv.getItem(invSlot)));
            }
        }
    }

    private static List<Integer> chooseIngredientSlots(CraftingRecipe recipe, Container inv, CompanionEntity companion) {
        List<Integer> result = new ArrayList<>();
        Map<Integer, Integer> used = new HashMap<>();
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) { result.add(-1); continue; }
            int found = -1;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int alreadyUsed = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > alreadyUsed && ingredient.test(stack)
                        && ResourceLedger.canConsume(companion, stack, alreadyUsed + 1)) {
                    found = slot;
                    used.put(slot, alreadyUsed + 1);
                    break;
                }
            }
            if (found < 0) return null;
            result.add(found);
        }
        // A shaped recipe's ingredient list already contains its empty cells. For shapeless recipes
        // there are no empty cells and packing is correct.
        while (result.size() < 9) result.add(-1);
        return result;
    }

    private static IngredientNeed firstMissingIngredient(CraftingRecipe recipe, Container inv, CompanionEntity companion) {
        Map<Integer, Integer> used = new HashMap<>();
        var ingredients = recipe.getIngredients();
        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int consumed = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > consumed && ingredient.test(stack)
                        && ResourceLedger.canConsume(companion, stack, consumed + 1)) {
                    used.put(slot, consumed + 1);
                    found = true;
                    break;
                }
            }
            if (!found) return new IngredientNeed(ingredient);
        }
        return null;
    }

    private static Item chooseCraftableItem(net.minecraft.world.item.crafting.Ingredient ingredient,
                                            ServerPlayer player, Set<Item> active, int depth) {
        ItemStack[] choices = ingredient.getItems();
        Item best = null;
        for (ItemStack choice : choices) {
            Item item = choice.getItem();
            // Never invent a reverse recipe for a raw resource. For example, Minecraft has a
            // recipe iron_block -> iron_ingot, but that does NOT mean an absent iron ingot should
            // be obtained by first crafting an iron block. The old planner did exactly that and
            // produced errors such as "cannot_craft_intermediate=minecraft:diamond_block" when
            // asked for a sword. Raw resources must be gathered/smelled by the higher-level task
            // planner instead of recursively reversing compression recipes.
            if (active.contains(item) || isRawResource(item)) continue;
            if (!findRecipes(player, item, null).isEmpty()) {
                if (best == null || id(item).compareTo(id(best)) < 0) best = item;
            }
        }
        return best;
    }

    private static boolean isRawResource(Item item) {
        String p = id(item);
        return p.equals("minecraft:diamond") || p.equals("minecraft:emerald")
                || p.equals("minecraft:iron_ingot") || p.equals("minecraft:gold_ingot")
                || p.equals("minecraft:copper_ingot") || p.equals("minecraft:netherite_ingot")
                || p.equals("minecraft:coal") || p.equals("minecraft:raw_iron")
                || p.equals("minecraft:raw_gold") || p.equals("minecraft:raw_copper")
                || p.endsWith("_log") || p.endsWith("_stem");
    }

    private static String describeIngredient(net.minecraft.world.item.crafting.Ingredient ingredient) {
        ItemStack[] choices = ingredient.getItems();
        if (choices.length == 0) return "unknown";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(choices.length, 4); i++) {
            if (i > 0) b.append('|');
            b.append(id(choices[i].getItem()));
        }
        return b.toString();
    }

    /** True while an asynchronous real-world prerequisite is still part of a craft request. */
    public static boolean hasPending(UUID companionId) { return companionId != null && PENDING.containsKey(companionId); }

    /** Release queued continuation state when a companion dies, despawns, or changes owner identity. */
    public static void clearPending(UUID companionId) { if (companionId != null) PENDING.remove(companionId); }

    private static void clearPendingIfMatches(UUID companionId, String outputId) {
        if (companionId == null || outputId == null) return;
        PENDING.computeIfPresent(companionId, (id, pending) -> pending.outputId().equals(outputId) ? null : pending);
    }

    private static void registerPending(CompanionEntity c, ServerPlayer player, Item target, int requested) {
        if (c == null || player == null || target == null) return;
        String outputId = id(target);
        long now = c.tickCount;
        PENDING.compute(c.getUUID(), (id, prior) -> {
            int retries = prior != null && prior.outputId().equals(outputId) ? prior.retries() : 0;
            long startedAt = prior != null && prior.outputId().equals(outputId) ? prior.startedAtTick() : now;
            return new PendingCraft(outputId, Math.max(1, Math.min(64, requested)), player.getUUID(), now + 40L, startedAt, retries);
        });
    }

    /**
     * Advances a pending craft only on the server thread. It waits for navigation/mining/building,
     * target combat, drop pickup, and furnace collection to finish, then re-runs the live recipe
     * planner. Retry limits prevent a missing resource or impossible recipe from looping forever.
     */
    public static void tickPending(CompanionEntity c) {
        if (c == null || c.level().isClientSide()) return;
        PendingCraft pending = PENDING.get(c.getUUID());
        if (pending == null) return;
        long now = c.tickCount;
        if (now - pending.startedAtTick() > PENDING_LIFETIME_TICKS || pending.retries() >= MAX_PENDING_RETRIES) {
            if (PENDING.remove(c.getUUID(), pending)) c.say("I stopped the crafting plan because its resource steps did not finish. Check my inventory and nearby resources, then retry the craft request.");
            return;
        }
        if (now < pending.notBeforeTick()) return;
        if (TaskManager.isActive(c) || c.hasActiveSmeltJob() || com.yaseen.npcmind.tool.impl.CombatTargetTool.hasActiveHunt(c) || (c.getTarget() != null && c.getTarget().isAlive())) return;
        if (!(c.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(pending.ownerId());
        if (owner == null || !owner.isAlive() || owner.level() != c.level()) return;

        Item target = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(pending.outputId()));
        if (target == null || target == Items.AIR) {
            PENDING.remove(c.getUUID(), pending);
            c.say("I stopped the pending craft because its output item no longer exists in the active registry.");
            return;
        }
        if (countItem(c.getCompanionInventory(), target) >= pending.requested()) {
            PENDING.remove(c.getUUID(), pending);
            c.say("Crafting complete: " + pending.requested() + " " + pending.outputId() + ".");
            return;
        }

        PendingCraft retried = new PendingCraft(pending.outputId(), pending.requested(), pending.ownerId(), now + 40L,
                pending.startedAtTick(), pending.retries() + 1);
        PENDING.replace(c.getUUID(), pending, retried);
        String args = "{\"output\":\"" + pending.outputId() + "\",\"count\":" + pending.requested() + "}";
        String result;
        try {
            result = new CraftItemTool().execute(c, owner, args);
        } catch (Throwable throwable) {
            result = "ERROR " + throwable.getClass().getSimpleName();
        }
        if (result == null) result = "ERROR null_craft_continuation_result";
        if (result.startsWith("SUCCESS") && countItem(c.getCompanionInventory(), target) >= pending.requested()) {
            PENDING.remove(c.getUUID());
            c.say("Crafting complete: " + pending.requested() + " " + pending.outputId() + ".");
        } else if (result.startsWith("FAILED") || result.startsWith("DENIED") || result.startsWith("ERROR")) {
            PENDING.remove(c.getUUID());
            c.say("I could not finish crafting " + pending.outputId() + ": " + result + ".");
        } else if (!result.startsWith("STARTED") && !result.startsWith("PARTIAL")) {
            PENDING.remove(c.getUUID());
            c.say("The crafting plan stopped without a verified result: " + result + ".");
        }
    }

    private static Item resolveItem(String query, Container inv) {
        ResourceLocation exact = ResourceLocation.tryParse(query);
        if (exact != null && BuiltInRegistries.ITEM.containsKey(exact)) return BuiltInRegistries.ITEM.get(exact);

        // Generic "bed" uses an available wool color when possible; otherwise defaults to white.
        // Explicit registry IDs such as minecraft:red_bed are handled by the exact lookup above.
        if (query.equals("bed") || query.equals("beds")) {
            Map<String, Integer> woolCounts = new HashMap<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String path = id(stack.getItem()).substring(id(stack.getItem()).indexOf(':') + 1);
                    if (path.endsWith("_wool")) woolCounts.merge(path.substring(0, path.length() - 5), stack.getCount(), Integer::sum);
                }
            }
            String color = woolCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("white");
            ResourceLocation bedId = new ResourceLocation("minecraft", color + "_bed");
            if (BuiltInRegistries.ITEM.containsKey(bedId)) return BuiltInRegistries.ITEM.get(bedId);
            return Items.WHITE_BED;
        }
        // Natural-language aliases are inventory-aware. If the companion owns birch/spruce/etc.
        // planks, "planks" should not force oak.
        if (query.equals("planks") || query.equals("wood_planks")) {
            Item best = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null && key.getPath().endsWith("_planks") && (best == null || id(stack.getItem()).compareTo(id(best)) < 0)) best = stack.getItem();
            }
            if (best != null) return best;
            return BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "oak_planks"));
        }
        if (query.equals("wood")) {
            Item best = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null && key.getPath().endsWith("_log") && (best == null || id(stack.getItem()).compareTo(id(best)) < 0)) best = stack.getItem();
            }
            return best != null ? best : BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "oak_log"));
        }
        if (query.equals("stick") || query.equals("sticks")) return BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "stick"));

        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(key);
            if (key.getPath().equals(query) || item.getDescription().getString().toLowerCase(Locale.ROOT).equals(query)) return item;
        }
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(key);
            if (key.getPath().contains(query)) return item;
        }
        return null;
    }

    private static int recipeAvailabilityScore(CraftingRecipe recipe, Container inv) {
        if (inv == null) return 0;
        int score = 0;
        Map<Integer, Integer> used = new HashMap<>();
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                int usedCount = used.getOrDefault(slot, 0);
                if (!stack.isEmpty() && stack.getCount() > usedCount && ingredient.test(stack)) {
                    used.put(slot, usedCount + 1);
                    score++;
                    found = true;
                    break;
                }
            }
            if (!found) score -= 3;
        }
        return score;
    }

    private static boolean requiresTable(CraftingRecipe recipe) {
        return !recipe.canCraftInDimensions(2, 2);
    }

    /** Ensure a real crafting table exists within interaction range before a 3x3 craft. */
    private static String ensureCraftingTable(CompanionEntity c, ServerPlayer player, Set<Item> active, int depth) {
        if (hasNearbyCraftingTable(c, 4)) return "SUCCESS crafting_table_already_nearby";
        Item table = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", "crafting_table"));
        if (table == null) return "FAILED crafting_table_item_missing";
        if (countItem(c.getCompanionInventory(), table) < 1) {
            if (depth >= MAX_RECIPE_DEPTH - 1 || active.contains(table)) return "FAILED cannot_recursively_craft_crafting_table";
            CraftResult made = craftRecursive(c, player, table, 1, active, depth + 1);
            if (made.started) return "STARTED crafting_table_prerequisite=" + made.reason;
            if (countItem(c.getCompanionInventory(), table) < 1) {
                return "FAILED crafting_table_missing reason=" + made.reason;
            }
        }
        String placed = InternalToolExecutor.invoke(c, player, "place_block", "{\"block\":\"crafting_table\",\"count\":1}", "internal-" + java.util.UUID.randomUUID()).result();
        if (placed != null && placed.startsWith("STARTED")) return "STARTED place_crafting_table=" + placed;
        if (placed != null && placed.startsWith("SUCCESS") && hasNearbyCraftingTable(c, 4)) return placed;
        return "FAILED cannot_place_crafting_table result=" + placed;
    }

    private static boolean hasNearbyCraftingTable(CompanionEntity c, int radius) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos base = c.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -radius; dz <= radius; dz++) {
            pos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
            if (!c.level().isLoaded(pos)) continue;
            if (c.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static int countItem(Container inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) total += inv.getItem(i).getCount();
        return total;
    }

    private static SimpleContainer copyInventory(Container inv) {
        SimpleContainer copy = new SimpleContainer(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) copy.setItem(i, inv.getItem(i).copy());
        return copy;
    }

    private static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    private static ItemStack oneOf(ItemStack stack) { ItemStack copy = stack.copy(); copy.setCount(1); return copy; }

    private record IngredientNeed(net.minecraft.world.item.crafting.Ingredient ingredient) {}
    private record CraftResult(int crafted, int steps, String reason, boolean started) {}

    private static final AbstractContainerMenu DUMMY_MENU = new AbstractContainerMenu(MenuType.CRAFTING, -1) {
        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return false; }
    };
}
