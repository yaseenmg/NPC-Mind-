package com.yaseen.npcmind.crafting;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * Count-aware resource acquisition layer.
 *
 * The previous crafting implementation was a recipe executor. It could recurse through recipes,
 * but when a recipe reached a real-world resource (iron/diamond/log/etc.) it had no acquisition
 * strategy. This class is deliberately deterministic: the recipe planner asks for a missing
 * ingredient and this layer chooses a physical Minecraft operation (mine, craft workstation,
 * place workstation, collect fuel, or smelt). No synthetic items are ever created.
 *
 * It returns STARTED when a long-running world operation has been queued. AgentEngine waits for
 * that operation, re-observes the world, and re-enters the same craft goal.
 */
@Internal
public final class ResourcePlanner {
    public record Decision(boolean started, String result) {
        public static Decision started(String result) { return new Decision(true, result); }
        public static Decision blocked(String result) { return new Decision(false, result); }
        public static Decision none() { return new Decision(false, ""); }
    }

    private ResourcePlanner() {}

    public static Decision acquire(CompanionEntity c, ServerPlayer p, Ingredient ingredient) {
        if (c == null || p == null || ingredient == null || ingredient.isEmpty()) return Decision.none();
        ItemStack[] choices = ingredient.getItems();
        if (choices.length == 0) return Decision.blocked("missing_ingredient=unknown");

        // This method is called only after the recipe matcher proved that at least one ingredient
        // unit is missing. Do NOT return merely because one item of that type exists: recipes such
        // as a bed need three wool, and the planner must acquire the remaining units one at a time.
        // Tags can contain many alternatives. Prefer an existing candidate family, but continue
        // acquisition when the caller reports a missing unit.
        for (ItemStack choice : choices) {
            Decision d = acquireItem(c, p, choice.getItem());
            if (d.started || !d.result.isBlank()) return d;
        }
        return Decision.blocked("missing_real_resource=" + describe(choices));
    }

    private static Decision acquireItem(CompanionEntity c, ServerPlayer p, Item item) {
        String id = id(item);
        String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        Container inv = c.getCompanionInventory();

        if (isLog(path)) {
            if (!c.getPermissions().allowed(ActionPermissions.Action.MINE)) return Decision.blocked("DENIED permission=mine");
            String r = InternalToolExecutor.invoke(c, p, "gather_wood", "{\"count\":8}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "logs");
        }
        // Vanilla sheep drop wool as well as mutton when killed. Acquire real drops through normal
        // combat/loot; never synthesize wool or meat into the inventory. CraftItemTool re-enters
        // this planner after each kill until the recipe's missing ingredient is satisfied.
        if (path.endsWith("_wool")) {
            String color = path.substring(0, path.length() - "_wool".length());
            String r = InternalToolExecutor.invoke(c, p, "combat_target",
                    "{\"mode\":\"nearest_animal\",\"animal\":\"sheep\",\"color\":\"" + color + "\"}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "hunt_" + color + "_sheep_for_wool");
        }
        if (path.equals("mutton") || path.equals("cooked_mutton")) {
            String r = InternalToolExecutor.invoke(c, p, "combat_target",
                    "{\"mode\":\"nearest_animal\",\"animal\":\"sheep\"}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "hunt_sheep_for_" + path);
        }
        if (path.equals("beef") || path.equals("cooked_beef") || path.equals("leather")) {
            String r = InternalToolExecutor.invoke(c, p, "combat_target",
                    "{\"mode\":\"nearest_animal\",\"animal\":\"cow\"}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "hunt_cow_for_" + path);
        }
        if (path.equals("porkchop") || path.equals("cooked_porkchop")) {
            String r = InternalToolExecutor.invoke(c, p, "combat_target",
                    "{\"mode\":\"nearest_animal\",\"animal\":\"pig\"}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "hunt_pig_for_" + path);
        }
        if (path.equals("chicken") || path.equals("cooked_chicken") || path.equals("feather")) {
            String r = InternalToolExecutor.invoke(c, p, "combat_target",
                    "{\"mode\":\"nearest_animal\",\"animal\":\"chicken\"}",
                    "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "hunt_chicken_for_" + path);
        }

        if (path.equals("stick")) {
            // Crafting the stick itself is deterministic and immediate once planks exist. If
            // planks are absent, recurse to the plank acquisition below.
            Item planks = byId("minecraft:oak_planks");
            if (countAnyPlanks(inv) == 0) {
                Decision d = acquireItem(c, p, planks);
                if (d.started || !d.result.isBlank()) return d;
            }
            String r = InternalToolExecutor.invoke(c, p, "craft_item", "{\"output\":\"minecraft:stick\",\"count\":4}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "sticks");
        }
        if (path.endsWith("_planks")) {
            if (countAnyLogs(inv) == 0) {
                String r = InternalToolExecutor.invoke(c, p, "gather_wood", "{\"count\":4}", "internal-" + java.util.UUID.randomUUID()).result();
                return from(r, "logs_for_planks");
            }
            String r = InternalToolExecutor.invoke(c, p, "craft_item", "{\"output\":\"" + id + "\",\"count\":4}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "planks");
        }

        if (path.equals("cobblestone") || path.equals("stone")) {
            String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"stone\",\"count\":12}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, "stone");
        }

        if (isOreDrop(path)) {
            String ore = oreForDrop(path);
            if (ore != null) {
                String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"" + ore + "\",\"count\":6}", "internal-" + java.util.UUID.randomUUID()).result();
                if (r != null && r.startsWith("STARTED")) return Decision.started("STARTED acquire=" + path + " via=" + r);
                if (hasRawFor(path, inv)) return ensureSmelt(c, p, path);
                return from(r, path + "_ore");
            }
        }

        // Raw iron/gold/copper can be smelted directly. If the recipe needs an ingot but the
        // companion has ore rather than raw material, mine first and return on the next cycle.
        if (path.equals("iron_ingot") || path.equals("gold_ingot") || path.equals("copper_ingot")) {
            if (hasRawFor(path, inv)) return ensureSmelt(c, p, path);
            String ore = oreForDrop(path);
            String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"" + ore + "\",\"count\":6}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, path + "_ore");
        }

        if (path.equals("coal") || path.equals("redstone") || path.equals("lapis_lazuli") || path.equals("diamond") || path.equals("emerald")) {
            String ore = path + "_ore";
            String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"" + ore + "\",\"count\":4}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, path + "_ore");
        }

        // A final generic attempt for common natural blocks/items. We intentionally do not use
        // creative inventory APIs or direct ItemStack insertion here.
        if (path.contains("sand") || path.contains("gravel") || path.contains("clay") || path.contains("dirt")) {
            String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"" + path + "\",\"count\":8}", "internal-" + java.util.UUID.randomUUID()).result();
            return from(r, path);
        }
        return Decision.none();
    }

    private static Decision ensureSmelt(CompanionEntity c, ServerPlayer p, String outputPath) {
        BlockPos furnace = findFurnace(c, 8);
        if (furnace == null) {
            // Build a real furnace from real cobblestone. If cobblestone is missing, acquire it
            // first; the next planner cycle will resume here.
            String made = InternalToolExecutor.invoke(c, p, "craft_item", "{\"output\":\"minecraft:furnace\",\"count\":1}", "internal-" + java.util.UUID.randomUUID()).result();
            if (made != null && made.startsWith("SUCCESS")) {
                String placed = InternalToolExecutor.invoke(c, p, "place_block", "{\"block\":\"furnace\",\"count\":1}", "internal-" + java.util.UUID.randomUUID()).result();
                if (placed != null && placed.startsWith("SUCCESS")) {
                    furnace = findFurnace(c, 8);
                } else {
                    return from(placed, "place_furnace");
                }
            } else if (made != null && made.startsWith("FAILED") && count(c.getCompanionInventory(), byId("minecraft:cobblestone")) == 0) {
                String r = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"stone\",\"count\":12}", "internal-" + java.util.UUID.randomUUID()).result();
                return from(r, "cobblestone_for_furnace");
            } else {
                return from(made, "furnace");
            }
        }
        if (furnace == null) return Decision.blocked("missing_real_furnace");

        if (!hasFuel(c.getCompanionInventory())) {
            String coal = InternalToolExecutor.invoke(c, p, "gather_block", "{\"block\":\"coal_ore\",\"count\":4}", "internal-" + java.util.UUID.randomUUID()).result();
            if (coal != null && coal.startsWith("STARTED")) return Decision.started("STARTED fuel_collection=" + coal);
            // Wood itself is valid furnace fuel and is much more available than coal in a new world.
            String wood = InternalToolExecutor.invoke(c, p, "gather_wood", "{\"count\":3}", "internal-" + java.util.UUID.randomUUID()).result();
            if (wood != null && wood.startsWith("STARTED")) return Decision.started("STARTED fuel_collection=" + wood);
            return Decision.blocked("missing_furnace_fuel");
        }

        String r = InternalToolExecutor.invoke(c, p, "smelt_item", "{\"type\":\"smelting\",\"output\":\"minecraft:" + outputPath + "\",\"x\":" + furnace.getX() + ",\"y\":" + furnace.getY() + ",\"z\":" + furnace.getZ() + "}", "internal-" + java.util.UUID.randomUUID()).result();
        return from(r, "smelt_" + outputPath);
    }

    private static Decision from(String result, String phase) {
        if (result == null || result.isBlank()) return Decision.none();
        if (result.startsWith("STARTED")) return Decision.started("STARTED phase=" + phase + " " + result);
        if (result.startsWith("SUCCESS")) return Decision.none();
        if (result.startsWith("FAILED") || result.startsWith("DENIED") || result.startsWith("ERROR")) return Decision.blocked(result);
        return Decision.none();
    }

    private static boolean isOreDrop(String path) {
        return path.equals("iron_ingot") || path.equals("gold_ingot") || path.equals("copper_ingot")
                || path.equals("coal") || path.equals("redstone") || path.equals("lapis_lazuli")
                || path.equals("diamond") || path.equals("emerald");
    }

    private static String oreForDrop(String path) {
        return switch (path) {
            case "iron_ingot" -> "iron_ore";
            case "gold_ingot" -> "gold_ore";
            case "copper_ingot" -> "copper_ore";
            case "coal" -> "coal_ore";
            case "redstone" -> "redstone_ore";
            case "lapis_lazuli" -> "lapis_ore";
            case "diamond" -> "diamond_ore";
            case "emerald" -> "emerald_ore";
            default -> null;
        };
    }

    private static boolean hasRawFor(String ingotPath, Container inv) {
        String raw = switch (ingotPath) {
            case "iron_ingot" -> "minecraft:raw_iron";
            case "gold_ingot" -> "minecraft:raw_gold";
            case "copper_ingot" -> "minecraft:raw_copper";
            default -> "";
        };
        return !raw.isBlank() && count(inv, byId(raw)) > 0;
    }

    private static boolean hasFuel(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && net.minecraftforge.common.ForgeHooks.getBurnTime(s, net.minecraft.world.item.crafting.RecipeType.SMELTING) > 0) return true;
        }
        return false;
    }

    private static BlockPos findFurnace(CompanionEntity c, int radius) {
        if (!(c.level() instanceof ServerLevel level)) return null;
        BlockPos b = c.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(b.offset(-radius, -2, -radius), b.offset(radius, 2, radius))) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FURNACE)) return pos.immutable();
        }
        return null;
    }

    private static boolean isLog(String path) { return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood"); }
    private static int count(Container inv, Item item) { if (item == null) return 0; int n=0; for (int i=0;i<inv.getContainerSize();i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount(); return n; }
    private static int countAnyLogs(Container inv) { int n=0; for(int i=0;i<inv.getContainerSize();i++){String p=id(inv.getItem(i).getItem()).substring(id(inv.getItem(i).getItem()).indexOf(':')+1); if(isLog(p)) n+=inv.getItem(i).getCount();} return n; }
    private static int countAnyPlanks(Container inv) { int n=0; for(int i=0;i<inv.getContainerSize();i++){String p=id(inv.getItem(i).getItem()).substring(id(inv.getItem(i).getItem()).indexOf(':')+1); if(p.endsWith("_planks")) n+=inv.getItem(i).getCount();} return n; }
    private static Item byId(String id) { net.minecraft.resources.ResourceLocation rl=net.minecraft.resources.ResourceLocation.tryParse(id); return rl==null?null:net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl); }
    private static String id(Item item) { if(item==null)return "unknown"; var k=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item); return k==null?"unknown":k.toString(); }
    private static String describe(ItemStack[] a){StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(a.length,5);i++){if(i>0)b.append('|');b.append(id(a[i].getItem()));}return b.toString();}
}
