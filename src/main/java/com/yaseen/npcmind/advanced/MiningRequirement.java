package com.yaseen.npcmind.advanced;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.yaseen.npcmind.api.Internal;

/**
 *
 * <p>The old miner tried every block with whatever was in hand. For a block that
 * {@link BlockState#requiresCorrectToolForDrops()} (obsidian, ancient debris, diamond ore, iron ore,
 * redstone ore...) a wrong-tier pickaxe produces <b>no drops at all</b>, so the companion happily
 * ground for the full 500-tick timeout, destroyed the block and returned with nothing. Altoclef's
 * enum answers "what is the cheapest pickaxe that actually works on this block" up front, which lets
 * the miner refuse immediately and tell the planner to craft or fetch the right tool.
 */
@Internal
public enum MiningRequirement {
    HAND(null, 0),
    WOOD(Items.WOODEN_PICKAXE, 1),
    STONE(Items.STONE_PICKAXE, 2),
    IRON(Items.IRON_PICKAXE, 3),
    DIAMOND(Items.DIAMOND_PICKAXE, 4);

    private final Item minPickaxe;
    private final int rank;

    MiningRequirement(Item minPickaxe, int rank) {
        this.minPickaxe = minPickaxe;
        this.rank = rank;
    }

    public Item getMinimumPickaxe() {
        return minPickaxe;
    }

    public int getRank() {
        return rank;
    }

    /** Cheapest requirement that yields drops for {@code block}. Mirrors Altoclef exactly. */
    public static MiningRequirement forBlock(Block block) {
        BlockState state = block.defaultBlockState();
        if (!state.requiresCorrectToolForDrops()) return HAND;
        for (MiningRequirement req : values()) {
            if (req == HAND) continue;
            if (req.minPickaxe.isCorrectToolForDrops(state)) return req;
        }
        // Netherite is never strictly required anywhere in vanilla; diamond is the ceiling.
        return DIAMOND;
    }

    /** Rank of a pickaxe stack, or 0 for anything that is not a usable pickaxe. */
    public static int rankOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item == Items.NETHERITE_PICKAXE) return 5;
        if (item == Items.DIAMOND_PICKAXE) return 4;
        if (item == Items.IRON_PICKAXE) return 3;
        if (item == Items.STONE_PICKAXE) return 2;
        // Golden pickaxes mine at wood level despite their speed.
        if (item == Items.GOLDEN_PICKAXE || item == Items.WOODEN_PICKAXE) return 1;
        return 0;
    }

    /** True when {@code stack} can harvest blocks of this requirement with drops. */
    public boolean satisfiedBy(ItemStack stack) {
        return rankOf(stack) >= rank;
    }
}
