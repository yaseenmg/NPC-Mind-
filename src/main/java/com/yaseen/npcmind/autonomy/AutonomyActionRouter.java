package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.agent.InternalToolExecutor;
import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.entity.animal.Sheep;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Deterministic low-level router for autonomy. It is deliberately independent of any external model: local autonomy remains available
 * without network services or model credentials.
 * One safe action is selected only after the kernel has released combat/task ownership.
 */
@Internal
public final class AutonomyActionRouter {
    private static final Map<UUID, Long> NEXT_ALLOWED = new ConcurrentHashMap<>();
    private static final long COOLDOWN = 100L;

    public static boolean tick(CompanionEntity c, ServerPlayer owner) {
        if (c == null || owner == null || c.level().isClientSide() || !c.getAutonomy().routine()) return false;
        long now = owner.serverLevel().getGameTime();
        if (now < NEXT_ALLOWED.getOrDefault(c.getUUID(), Long.MIN_VALUE)) return false;
        NEXT_ALLOWED.put(c.getUUID(), now + COOLDOWN);

        // Survival is handled above this router, but food is a useful autonomous action
        // even when no hostile target exists.
        if (c.getFoodLevel() <= 8 && c.startEatingFromInventory()) {
            c.getAutonomy().routineDone("eat_food", now);
            return true;
        }

        String tool;
        String args;
        switch (c.getAutonomy().mode()) {
            case MINER -> {
                tool = "gather_resources";
                args = "{\"resource\":\"stone\",\"count\":8}";
            }
            case FARMER -> {
                tool = "farm_area";
                args = "{\"radius\":6,\"replant\":true}";
            }
            case SURVIVAL -> {
                String[] plan = chooseSurvivalAction(c, owner);
                if (plan == null) return false;
                tool = plan[0];
                args = plan[1];
            }
            case ENGINEER -> {
                // Engineer mode first acquires starter material; construction tools can then
                // use the real inventory instead of repeatedly failing for missing blocks.
                tool = c.getCompanionInventory().countItem(Items.OAK_PLANKS) < 16
                        ? "gather_resources" : "inspect_companion_inventory";
                args = tool.equals("gather_resources")
                        ? "{\"resource\":\"wood\",\"count\":8}" : "{}";
            }
            case FOLLOW -> {
                tool = "follow_owner";
                args = "{\"enabled\":true}";
            }
            default -> {
                return false;
            }
        }

        String actionId = "autonomy-" + UUID.randomUUID();
        var result = InternalToolExecutor.invoke(c, owner, tool, args, actionId).result();
        c.getAutonomy().routineDone("tool=" + tool + " result=" + result, now);
        // No per-action chat line here: the router runs every 5 seconds in survival mode and a
        // "Status:" message per step flooded the owner's chat. routineDone + the periodic
        // autonomy notice already keep the owner informed.
        if (result != null && result.startsWith("STARTED")) return true;
        // A failed deterministic probe is retained in local state for inspection. Do not
        // invent a fallback action or spam the same failed probe every tick.
        return result != null && (result.startsWith("SUCCESS") || result.startsWith("RESULT"));
    }

    /**
     * A deterministic early-to-mid Survival progression. Each call chooses one unmet prerequisite,
     * then waits for that real Minecraft action to finish before selecting the next step. It never
     * inserts fake items or assumes a recipe/resource exists without checking the live inventory.
     */
    private static String[] chooseSurvivalAction(CompanionEntity c, ServerPlayer owner) {
        if (c.getFoodLevel() <= 12 && hasEdible(c)) return new String[]{"manage_autonomy", "{\"action\":\"status\",\"eat\":true}"};
        // Hunger 9-12 with nothing edible must trigger a hunt: the old `<= 8` threshold made the
        // companion fall through to wood gathering while it was starving with no food at all.
        if (c.getFoodLevel() <= 12 && !hasEdible(c)) {
            return new String[]{"combat_target", "{\"mode\":\"nearest_animal\",\"count\":2}"};
        }

        if (!hasItem(c, Items.OAK_LOG) && !hasAnySuffix(c, "_log") && !hasAnySuffix(c, "_stem")
                && !hasAnySuffix(c, "_planks")) {
            return new String[]{"gather_wood", "{\"count\":8}"};
        }
        if (!hasNearbyBlock(c, Blocks.CRAFTING_TABLE, 4) && !hasItem(c, Items.CRAFTING_TABLE)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:crafting_table\",\"count\":1}"};
        }
        if (!hasNearbyBlock(c, Blocks.CRAFTING_TABLE, 4) && hasItem(c, Items.CRAFTING_TABLE)) {
            return new String[]{"place_block", "{\"block\":\"crafting_table\",\"count\":1}"};
        }
        if (!hasAnyTool(c, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:wooden_pickaxe\",\"count\":1}"};
        }
        if (!hasAnyTool(c, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)
                && c.getCompanionInventory().countItem(Items.COBBLESTONE) < 3) {
            return new String[]{"gather_block", "{\"block\":\"stone\",\"count\":12}"};
        }
        if (!hasAnyTool(c, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:stone_pickaxe\",\"count\":1}"};
        }
        if (!hasAnyTool(c, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:stone_sword\",\"count\":1}"};
        }
        if (!hasNearbyBlock(c, Blocks.FURNACE, 6) && !hasItem(c, Items.FURNACE)
                && c.getCompanionInventory().countItem(Items.COBBLESTONE) >= 8) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:furnace\",\"count\":1}"};
        }
        if (!hasNearbyBlock(c, Blocks.FURNACE, 6) && hasItem(c, Items.FURNACE)) {
            return new String[]{"place_block", "{\"block\":\"furnace\",\"count\":1}"};
        }
        // Place an already-crafted bed before spending more resources. Bed placement itself is a
        // real BlockItem interaction, so Minecraft validates the two-block footprint and consumes
        // the item normally. This cannot set a player's respawn point because this entity is not a Player.
        String heldBed = bedItemId(c);
        if (heldBed != null && !hasNearbyBed(c, 16)) {
            return new String[]{"place_block", "{\"block\":\"" + heldBed + "\",\"count\":1}"};
        }
        if (!hasAnyItemSuffix(c, "_bed") && !hasNearbyBed(c, 16)
                && (hasThreeSameColorWool(c) || hasNearbyAnimal(c, "sheep", 16))) {
            String outputBed = preferredBedOutput(c);
            return new String[]{"craft_item", "{\"output\":\"" + outputBed + "\",\"count\":1}"};
        }
        if (hasRawFood(c) && hasNearbyCookingStation(c, 8)) {
            return new String[]{"cook_food", "{\"output\":\"food\"}"};
        }
        if (c.getCompanionInventory().countItem(Items.TORCH) < 8
                && (hasItem(c, Items.COAL) || hasItem(c, Items.CHARCOAL)) && hasItem(c, Items.STICK)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:torch\",\"count\":8}"};
        }
        if (c.getCompanionInventory().countItem(Items.TORCH) < 8
                && !hasItem(c, Items.COAL) && !hasItem(c, Items.CHARCOAL)
                && hasNearbyBlock(c, Blocks.FURNACE, 8) && (hasAnySuffix(c, "_log") || hasItem(c, Items.OAK_LOG))) {
            return new String[]{"smelt_item", "{\"type\":\"smelting\",\"output\":\"minecraft:charcoal\"}"};
        }
        if (c.getCompanionInventory().countItem(Items.TORCH) < 8
                && !hasItem(c, Items.COAL) && !hasItem(c, Items.CHARCOAL)) {
            return new String[]{"gather_block", "{\"block\":\"coal_ore\",\"count\":4}"};
        }
        // Begin the first iron tier only when an actual nearby/resource input exists. CraftItemTool
        // may then acquire and smelt the missing real ingredients through its bounded continuation.
        boolean furnaceReady = hasNearbyBlock(c, Blocks.FURNACE, 8);
        boolean nearbyIron = hasNearbyBlock(c, Blocks.IRON_ORE, 10) || hasNearbyBlock(c, Blocks.DEEPSLATE_IRON_ORE, 10);
        boolean ironInputAvailable = hasItem(c, Items.RAW_IRON) || hasItem(c, Items.IRON_INGOT) || nearbyIron;
        if (furnaceReady && ironInputAvailable && !hasAnyTool(c, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)
                && hasAnyTool(c, Items.STONE_PICKAXE)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_pickaxe\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.SHIELD)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:shield\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.IRON_SWORD)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_sword\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.IRON_HELMET)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_helmet\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.IRON_CHESTPLATE)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_chestplate\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.IRON_LEGGINGS)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_leggings\",\"count\":1}"};
        }
        if (furnaceReady && ironInputAvailable && hasItem(c, Items.IRON_PICKAXE) && !hasItem(c, Items.IRON_BOOTS)) {
            return new String[]{"craft_item", "{\"output\":\"minecraft:iron_boots\",\"count\":1}"};
        }
        if (hasItem(c, Items.RAW_IRON) && hasFuel(c) && furnaceReady) {
            return new String[]{"smelt_item", "{\"type\":\"smelting\",\"output\":\"minecraft:iron_ingot\"}"};
        }
        // If the starter loop is stable, the controller yields rather than repeatedly issuing a
        // destructive or unbounded task. The owner can switch to Miner/Farmer/Engineer modes.
        return null;
    }

    private static boolean hasItem(CompanionEntity c, Item item) {
        // Gear moved out of the carried inventory by combat/loadout management still counts
        // as owned equipment; otherwise Survival would repeatedly craft duplicate armor.
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (c.getItemBySlot(slot).is(item)) return true;
        }
        return c.getCompanionInventory().countItem(item) > 0;
    }
    private static boolean hasAnyTool(CompanionEntity c, Item... items) {
        for (Item item : items) if (hasItem(c, item)) return true;
        return false;
    }
    private static boolean hasAnySuffix(CompanionEntity c, String suffix) {
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().builtInRegistryHolder().key().location().getPath().endsWith(suffix)) return true;
        }
        return false;
    }
    private static boolean hasAnyItemSuffix(CompanionEntity c, String suffix) {
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            var equipped = c.getItemBySlot(slot);
            if (!equipped.isEmpty() && equipped.getItem().builtInRegistryHolder().key().location().getPath().endsWith(suffix)) return true;
        }
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().builtInRegistryHolder().key().location().getPath().endsWith(suffix)) return true;
        }
        return false;
    }
    private static boolean hasEdible(CompanionEntity c) {
        if (!c.getMainHandItem().isEmpty() && c.getMainHandItem().isEdible()) return true;
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (!stack.isEmpty() && stack.isEdible()) return true;
        }
        return false;
    }
    private static String bedItemId(CompanionEntity c) {
        if (!c.getMainHandItem().isEmpty()) {
            var key = c.getMainHandItem().getItem().builtInRegistryHolder().key().location();
            if (key.getPath().endsWith("_bed")) return key.toString();
        }
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var key = stack.getItem().builtInRegistryHolder().key().location();
            if (key.getPath().endsWith("_bed")) return key.toString();
        }
        return null;
    }

    /** Choose a bed matching wool already collected, or the color of a nearby sheep. */
    private static String preferredBedOutput(CompanionEntity c) {
        java.util.Set<String> woolColors = new java.util.LinkedHashSet<>();
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String path = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (path.endsWith("_wool")) woolColors.add(path.substring(0, path.length() - 5));
        }
        java.util.Map<String, Integer> availableWool = new java.util.HashMap<>();
        for (String color : woolColors) {
            availableWool.put(color, ResourceLedger.available(c, "minecraft:" + color + "_wool"));
        }
        String bestColor = availableWool.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse("white");
        if (availableWool.values().stream().anyMatch(count -> count > 0)) return "minecraft:" + bestColor + "_bed";
        var nearby = c.level().getEntitiesOfClass(Sheep.class, c.getBoundingBox().inflate(16), Sheep::isAlive)
                .stream().min(java.util.Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
        if (nearby != null) return "minecraft:" + nearby.getColor().getName() + "_bed";
        return "minecraft:white_bed";
    }

    private static boolean hasNearbyBed(CompanionEntity c, int radius) {
        BlockPos base = c.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -radius; dz <= radius; dz++) {
            BlockPos pos = base.offset(dx, dy, dz);
            if (c.level().isLoaded(pos) && c.level().getBlockState(pos).getBlock() instanceof BedBlock) return true;
        }
        return false;
    }

    private static boolean hasThreeSameColorWool(CompanionEntity c) {
        java.util.Set<String> woolIds = new java.util.HashSet<>();
        for (int i = 0; i < c.getCompanionInventory().getContainerSize(); i++) {
            var stack = c.getCompanionInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String path = stack.getItem().builtInRegistryHolder().key().location().getPath();
            if (path.endsWith("_wool")) woolIds.add("minecraft:" + path);
        }
        return woolIds.stream().anyMatch(id -> ResourceLedger.available(c, id) >= 3);
    }
    private static boolean hasFuel(CompanionEntity c) {
        return hasItem(c, Items.COAL) || hasItem(c, Items.CHARCOAL) || hasItem(c, Items.OAK_PLANKS)
                || hasAnySuffix(c, "_planks") || hasAnySuffix(c, "_log") || hasItem(c, Items.STICK);
    }
    private static boolean hasNearbyAnimal(CompanionEntity c, String species, int radius) {
        var box = c.getBoundingBox().inflate(radius);
        return !c.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, box,
                animal -> animal.isAlive() && animal.getType().builtInRegistryHolder().key().location().getPath().equals(species)).isEmpty();
    }
    private static boolean hasRawFood(CompanionEntity c) {
        return hasItem(c, Items.BEEF) || hasItem(c, Items.PORKCHOP) || hasItem(c, Items.MUTTON)
                || hasItem(c, Items.CHICKEN) || hasItem(c, Items.COD) || hasItem(c, Items.SALMON)
                || hasItem(c, Items.POTATO);
    }
    private static boolean hasNearbyBlock(CompanionEntity c, net.minecraft.world.level.block.Block block, int radius) {
        BlockPos base = c.blockPosition();
        for (int dx=-radius; dx<=radius; dx++) for (int dy=-2; dy<=2; dy++) for (int dz=-radius; dz<=radius; dz++) {
            BlockPos pos = base.offset(dx,dy,dz);
            if (c.level().isLoaded(pos) && c.level().getBlockState(pos).is(block)) return true;
        }
        return false;
    }
    private static boolean hasNearbyCookingStation(CompanionEntity c, int radius) {
        return hasNearbyBlock(c, Blocks.FURNACE, radius) || hasNearbyBlock(c, Blocks.SMOKER, radius) || hasNearbyBlock(c, Blocks.BLAST_FURNACE, radius);
    }

    public static void clear(UUID id) { NEXT_ALLOWED.remove(id); }
    private AutonomyActionRouter() {}
}
