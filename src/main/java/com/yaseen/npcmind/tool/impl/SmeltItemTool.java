package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.autonomy.ResourceLedger;
import com.yaseen.npcmind.knowledge.ModpackKnowledge;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.state.ActionPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;
import com.yaseen.npcmind.api.Internal;

/**
 * Uses an actual furnace/smoker/blast-furnace block entity. No synthetic smelting is performed.
 * The tool loads a real input and real fuel into the real furnace and lets Minecraft tick it.
 */
@Internal
public final class SmeltItemTool implements AITool {
    @Override public String name() { return "smelt_item"; }
    @Override public String description() {
        return "Use a real loaded furnace, smoker or blast furnace. The companion must provide a real recipe input and real fuel; Minecraft performs the actual cooking and creates the real output.";
    }
    @Override public String schema() {
        return "{\"type\":\"smelting|blasting|smoking(optional)\",\"output\":string(optional),\"x\":number(optional),\"y\":number(optional),\"z\":number(optional)}";
    }

    @Override
    public String execute(CompanionEntity companion, ServerPlayer player, String arguments) {
        if(!companion.getPermissions().allowed(ActionPermissions.Action.CRAFT)) return "DENIED permission=craft";
        String type = text(arguments, "type").toLowerCase(Locale.ROOT);
        if (type.isBlank()) type = "smelting";
        String query = text(arguments, "output").toLowerCase(Locale.ROOT).trim();
        Integer x = MoveToTool.num(arguments, "x"), y = MoveToTool.num(arguments, "y"), z = MoveToTool.num(arguments, "z");
        if ((x != null || y != null || z != null) && (x == null || y == null || z == null)) return "ERROR coordinates_require_x_y_z";

        RecipeType<? extends AbstractCookingRecipe> recipeType = switch (type) {
            case "blasting" -> RecipeType.BLASTING;
            case "smoking" -> RecipeType.SMOKING;
            case "smelting" -> RecipeType.SMELTING;
            default -> null;
        };
        if (recipeType == null) return "FAILED unsupported real furnace mode=" + type;

        BlockPos pos;
        if (x == null || y == null || z == null) {
            pos = nearestCompatibleFurnace(companion, player, type);
            if (pos == null) return "FAILED no_accessible_" + type + "_furnace_nearby; supply x,y,z or place a compatible furnace within 8 blocks";
        } else {
            pos = new BlockPos(x, y, z);
        }
        if (!WorldActionSecurity.canAccessContainer(companion, player, pos)) return "DENIED companion must be within 8 blocks of the furnace and the owner must have permission to interact";
        if (!player.serverLevel().isLoaded(pos)) return "FAILED furnace position is not loaded";
        BlockEntity be = player.serverLevel().getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return "FAILED no real furnace/smoker/blast-furnace block entity at " + pos;

        String blockId = player.serverLevel().getBlockState(pos).getBlock().builtInRegistryHolder().key().location().toString();
        if (type.equals("blasting") && !blockId.equals("minecraft:blast_furnace")) return "FAILED requested blasting but real block is " + blockId;
        if (type.equals("smoking") && !blockId.equals("minecraft:smoker")) return "FAILED requested smoking but real block is " + blockId;
        if (type.equals("smelting") && !blockId.equals("minecraft:furnace")) return "FAILED requested smelting but real block is " + blockId;

        ItemStack input = findRecipeInput(companion.getCompanionInventory(), player, recipeType, query);
        if (input.isEmpty()) return "FAILED no real companion-inventory input matches a live recipe output=" + query;
        ItemStack fuel = findFuel(companion.getCompanionInventory(), furnace);
        if (fuel.isEmpty()) return "FAILED no real fuel is available in companion inventory";
        if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty()) return "FAILED real furnace input/fuel slots are occupied";
        if (!furnace.getItem(2).isEmpty()) return "FAILED real furnace output slot is occupied; empty it first";

        int inputSlot = findStackSlot(companion.getCompanionInventory(), input);
        int fuelSlot = findStackSlot(companion.getCompanionInventory(), fuel);
        if (inputSlot < 0 || fuelSlot < 0) return "FAILED real inventory changed while preparing furnace";
        if (inputSlot == fuelSlot && companion.getCompanionInventory().getItem(inputSlot).getCount() < 2)
            return "FAILED input_and_fuel_need_two_items_or_separate_stacks";

        if (!ResourceLedger.canConsume(companion, input, 1)) return "FAILED input is reserved for another project";
        if (!ResourceLedger.canConsume(companion, fuel, 1)) return "FAILED fuel is reserved for another project";
        // Load a full fuel-efficient batch: one coal cooks 8 items, so loading one item + one
        // fuel per job wasted 7/8 of every fuel unit and needed a separate job per ingot.
        int burnTicks = ForgeHooks.getBurnTime(fuel, recipeType);
        int itemsPerFuel = Math.max(1, burnTicks / 200);
        int availableInput = companion.getCompanionInventory().getItem(inputSlot).getCount()
                - (inputSlot == fuelSlot ? 1 : 0);
        int batch = Math.max(1, Math.min(itemsPerFuel, Math.max(0, availableInput)));
        if (batch == 0) return "FAILED input_and_fuel_need_two_items_or_separate_stacks";
        ItemStack batchInput = companion.getCompanionInventory().removeItem(inputSlot, batch);
        ItemStack oneFuel = companion.getCompanionInventory().removeItem(fuelSlot, 1);
        furnace.setItem(0, batchInput);
        furnace.setItem(1, oneFuel);
        furnace.setChanged();
        companion.getCompanionInventory().setChanged();
        companion.startSmeltJob(pos, outputIdForRecipe(player, recipeType, query));
        return "STARTED real_furnace=" + pos + " mode=" + type + " input=" + ModpackKnowledge.itemInfo(batchInput)
                + " batch=" + batch + " fuel=" + ModpackKnowledge.itemInfo(oneFuel);
    }

    /** Finds the nearest real, loaded furnace of the requested kind within the normal interaction radius. */
    private static BlockPos nearestCompatibleFurnace(CompanionEntity companion, ServerPlayer player, String type) {
        if (companion == null || player == null || companion.level() != player.level()) return null;
        BlockPos base = companion.blockPosition();
        return BlockPos.betweenClosedStream(base.offset(-8, -4, -8), base.offset(8, 4, 8))
                .filter(pos -> companion.level().isLoaded(pos))
                .filter(pos -> companion.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity)
                .filter(pos -> {
                    String id = companion.level().getBlockState(pos).getBlock().builtInRegistryHolder().key().location().toString();
                    return switch (type) {
                        case "blasting" -> id.equals("minecraft:blast_furnace");
                        case "smoking" -> id.equals("minecraft:smoker");
                        default -> id.equals("minecraft:furnace");
                    };
                })
                .filter(pos -> WorldActionSecurity.canAccessContainer(companion, player, pos))
                .min(java.util.Comparator.comparingDouble(pos -> companion.distanceToSqr(
                        pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)))
                .map(BlockPos::immutable).orElse(null);
    }

    private static String outputIdForRecipe(ServerPlayer player, RecipeType<? extends AbstractCookingRecipe> type, String query) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List<AbstractCookingRecipe> recipes = (java.util.List) player.serverLevel().getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (AbstractCookingRecipe recipe : recipes) {
            ItemStack out = recipe.getResultItem(player.serverLevel().registryAccess());
            if (out.isEmpty()) continue;
            String id = out.getItem().builtInRegistryHolder().key().location().toString().toLowerCase(Locale.ROOT);
            String name = out.getHoverName().getString().toLowerCase(Locale.ROOT);
            if ("food".equals(query)) { if (out.getItem().isEdible()) return id; continue; }
            if (query == null || query.isBlank() || id.contains(query) || name.contains(query)) return id;
        }
        return "";
    }

    private static ItemStack findRecipeInput(Container inv, ServerPlayer player,
                                              RecipeType<? extends AbstractCookingRecipe> type, String query) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List<AbstractCookingRecipe> recipes = (java.util.List) player.serverLevel().getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (AbstractCookingRecipe holder : recipes) {
            ItemStack output = holder.getResultItem(player.serverLevel().registryAccess());
            if (output.isEmpty()) continue;
            String hay = output.getItem().builtInRegistryHolder().key().location().toString().toLowerCase(Locale.ROOT)
                    + " " + output.getHoverName().getString().toLowerCase(Locale.ROOT);
            if ("food".equals(query)) { if (!output.getItem().isEdible()) continue; }
            else if (!query.isBlank() && !hay.contains(query)) continue;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && holder.getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findFuel(Container inv, AbstractFurnaceBlockEntity furnace) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static int findStackSlot(Container inv, ItemStack wanted) {
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i) == wanted) return i;
        for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && ItemStack.isSameItemSameTags(inv.getItem(i), wanted)) return i;
        return -1;
    }

    private static String text(String json, String key) { return JsonArgs.string(json,key); }

    public SmeltItemTool() {}
}
