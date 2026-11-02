package com.yaseen.npcmind.knowledge;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;
import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/** Reads the live Forge/Minecraft registries. No static modpack database is used. */
@Internal
public final class ModpackKnowledge {
    public static String installedMods() {
        return ModList.get().getMods().stream().map(m -> m.getModId()).sorted().distinct().reduce((a,b) -> a + "," + b).orElse("");
    }

    public static String itemInfo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return stack.getHoverName().getString() + " id=" + id + " mod=" + (id == null ? "unknown" : id.getNamespace());
    }

    public static String blockInfo(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return state.getBlock().getName().getString() + " id=" + id + " mod=" + (id == null ? "unknown" : id.getNamespace());
    }

    public static String search(String kind, String query, int limit) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("REGISTRY_SEARCH kind=").append(k).append(" query=").append(q).append('\n');
        int[] count = {0};
        if (k.equals("item")) ForgeRegistries.ITEMS.getEntries().forEach(e -> append(out, e.getKey().location(), e.getValue().getDescription().getString(), q, limit, count));
        else if (k.equals("block")) ForgeRegistries.BLOCKS.getEntries().forEach(e -> append(out, e.getKey().location(), e.getValue().getName().getString(), q, limit, count));
        else if (k.equals("entity")) ForgeRegistries.ENTITY_TYPES.getEntries().forEach(e -> append(out, e.getKey().location(), e.getValue().toString(), q, limit, count));
        else if (k.equals("fluid")) ForgeRegistries.FLUIDS.getEntries().forEach(e -> append(out, e.getKey().location(), e.getValue().toString(), q, limit, count));
        else return "RESULT error=unknown_registry_kind";
        return out.toString();
    }

    private static void append(StringBuilder out, ResourceLocation id, String name, String q, int limit, int[] count) {
        if (count[0] >= limit) return;
        String s = id + " " + name;
        if (q.isBlank() || s.toLowerCase(Locale.ROOT).contains(q)) { out.append(s).append('\n'); count[0]++; }
    }
    private ModpackKnowledge() {}
}
