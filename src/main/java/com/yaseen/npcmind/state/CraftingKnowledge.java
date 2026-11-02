package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Map;
import com.yaseen.npcmind.api.Internal;

/**
 * Persistent crafting memory. The recipe definitions themselves always come from the live
 * server RecipeManager; this class only remembers what this companion has successfully executed.
 */
@Internal
public final class CraftingKnowledge {
    private static final String ROOT = "NPCMindCraftingKnowledge";
    private static final int MAX_RECIPES = 2048;
    private final Map<String, Integer> successes = new LinkedHashMap<>();

    public void recordSuccess(String recipeId, int amount) {
        if (recipeId == null || recipeId.isBlank() || amount <= 0) return;
        successes.put(recipeId, Math.min(1_000_000, successes.getOrDefault(recipeId, 0) + amount));
        while (successes.size() > MAX_RECIPES) successes.remove(successes.keySet().iterator().next());
    }

    public int successes(String recipeId) { return successes.getOrDefault(recipeId, 0); }

    public boolean hasLearned(String recipeId) { return successes.containsKey(recipeId); }

    public String summary() {
        if (successes.isEmpty()) return "none";
        return "learned=" + successes.size() + " recipes";
    }

    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Integer> e : successes.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Recipe", e.getKey());
            row.putInt("Successes", e.getValue());
            list.add(row);
        }
        tag.put(ROOT, list);
    }

    public void load(CompoundTag tag) {
        successes.clear();
        if (!tag.contains(ROOT, 9)) return;
        ListTag list = tag.getList(ROOT, 10);
        for (int i = 0; i < list.size() && i < MAX_RECIPES; i++) {
            CompoundTag row = list.getCompound(i);
            String id = row.getString("Recipe");
            if (!id.isBlank()) successes.put(id, Math.max(0, row.getInt("Successes")));
        }
    }
}
