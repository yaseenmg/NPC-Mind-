package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * Reads the live server recipe graph. It deliberately does not embed a vanilla recipe list so
 * datapacks and modded recipes are visible immediately after a server reload.
 */
@Internal
public final class InspectRecipesTool implements AITool {
    @Override public String name() { return "inspect_recipes"; }
    @Override public String description() {
        return "Inspect the live Minecraft RecipeManager. Supports crafting, smelting, blasting, "
                + "smoking, campfire, stonecutting, smithing and all registered recipe types; "
                + "use this before planning a craft when the output or workstation is uncertain.";
    }
    @Override public String schema() {
        return "{type:\"all|crafting|smelting|blasting|smoking|campfire|stonecutting|smithing\"(optional),output:\"item_id_or_name\"(optional),limit:number(optional)}";
    }

    @Override
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        String type = text(arguments, "type").toLowerCase(Locale.ROOT).trim();
        String query = text(arguments, "output").toLowerCase(Locale.ROOT).trim();
        int limit = Math.max(1, Math.min(128, integer(arguments, "limit", 32)));

        List<Recipe<?>> recipes = new ArrayList<>();
        if (type.isBlank() || type.equals("all")) {
            recipes.addAll(player.serverLevel().getRecipeManager().getRecipes());
        } else {
            RecipeType<?> recipeType = recipeType(type);
            if (recipeType == null) return "FAILED unsupported_recipe_type=" + type;
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<Recipe<?>> found = (List) player.serverLevel().getRecipeManager().getAllRecipesFor((RecipeType) recipeType);
            recipes.addAll(found);
        }

        recipes.sort(Comparator.comparing(r -> r.getId().toString()));
        StringBuilder out = new StringBuilder("LIVE_RECIPE_GRAPH\n");
        int found = 0;
        for (Recipe<?> recipe : recipes) {
            ItemStack result;
            try { result = recipe.getResultItem(player.serverLevel().registryAccess()); }
            catch (RuntimeException badRecipe) {
                com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] skipped recipe {} because its output could not be evaluated", recipe.getId(), badRecipe);
                continue;
            }
            if (result.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(result.getItem());
            String display = result.getHoverName().getString();
            String haystack = recipe.getId().toString().toLowerCase(Locale.ROOT) + " "
                    + (id == null ? "" : id.toString().toLowerCase(Locale.ROOT)) + " "
                    + display.toLowerCase(Locale.ROOT);
            if (!query.isBlank() && !haystack.contains(query)) continue;

            out.append("recipe=").append(recipe.getId())
                    .append(" type=").append(recipe.getType() == null ? "unknown" : recipe.getType())
                    .append(" special=").append(recipe.isSpecial()).append('\n');
            out.append("output=").append(id).append(" x").append(result.getCount())
                    .append(" name=").append(display).append('\n');
            out.append("ingredients=");
            for (var ingredient : recipe.getIngredients()) {
                var choices = ingredient.getItems();
                if (choices.length == 0) out.append("[]");
                else {
                    out.append('[');
                    for (int i = 0; i < choices.length; i++) {
                        if (i > 0) out.append('|');
                        out.append(BuiltInRegistries.ITEM.getKey(choices[i].getItem()));
                    }
                    out.append(']');
                }
                out.append(' ');
            }
            out.append('\n');
            if (++found >= limit) break;
        }
        out.append("count=").append(found).append(" total_loaded=").append(recipes.size());
        if (found == 0) out.append("\nNo matching recipe with a static output was found in the live recipe manager.");
        return "RESULT " + out;
    }

    private static RecipeType<?> recipeType(String type) {
        return switch (type) {
            case "crafting" -> RecipeType.CRAFTING;
            case "smelting" -> RecipeType.SMELTING;
            case "blasting" -> RecipeType.BLASTING;
            case "smoking" -> RecipeType.SMOKING;
            case "campfire", "campfire_cooking" -> RecipeType.CAMPFIRE_COOKING;
            case "stonecutting", "stonecutter" -> RecipeType.STONECUTTING;
            case "smithing", "smithing_transform", "smithing_trim" -> RecipeType.SMITHING;
            default -> null;
        };
    }

    private static String text(String json, String key) { return JsonArgs.string(json, key); }
    private static int integer(String json, String key, int fallback) { return JsonArgs.integer(json, key, fallback); }
}
