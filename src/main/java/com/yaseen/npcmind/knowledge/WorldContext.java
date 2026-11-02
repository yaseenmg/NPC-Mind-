package com.yaseen.npcmind.knowledge;

import com.yaseen.npcmind.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class WorldContext {

    public static String build(ServerPlayer player) {
        int radius = Config.CONTEXT_RADIUS.get();
        BlockPos pos = player.blockPosition();

        StringBuilder out = new StringBuilder();
        out.append("REAL_SERVER_CONTEXT\n");
        out.append("dimension=").append(player.serverLevel().dimension().location()).append('\n');
        out.append("position=").append(pos.getX()).append(',')
                .append(pos.getY()).append(',').append(pos.getZ()).append('\n');
        out.append("game_time=").append(player.serverLevel().getGameTime()).append('\n');
        out.append("health=").append(String.format(Locale.ROOT, "%.1f", player.getHealth())).append('\n');
        out.append("food=").append(player.getFoodData().getFoodLevel()).append('\n');

        ItemStack main = player.getMainHandItem();
        out.append("main_hand=").append(ModpackKnowledge.itemInfo(main)).append('\n');
        out.append("off_hand=").append(ModpackKnowledge.itemInfo(player.getOffhandItem())).append('\n');

        out.append("inventory=");
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) out.append('[').append(i).append(':')
                    .append(ModpackKnowledge.itemInfo(stack)).append(" x").append(stack.getCount()).append(']');
        }
        out.append('\n');

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        String centerBlock = player.serverLevel().getBlockState(pos).getBlock().getDescriptionId();
        out.append("block_under_player=").append(centerBlock).append('\n');
        out.append("environment=").append(environment(player)).append('\n');

        List<Entity> nearby = player.serverLevel()
                .getEntities(player, player.getBoundingBox().inflate(radius))
                .stream()
                .filter(e -> e != player)
                .sorted(Comparator.comparingDouble((Entity e) -> player.distanceToSqr(e)))
                .limit(Config.MAX_NEARBY_ENTITIES.get())
                .collect(Collectors.toList());

        out.append("nearby_entities=");
        for (Entity entity : nearby) {
            out.append('[')
                    .append(entity.getType().builtInRegistryHolder().key().location())
                    .append(" name=").append(entity.getName().getString())
                    .append(" pos=").append(entity.blockPosition().getX()).append(',')
                    .append(entity.blockPosition().getY()).append(',')
                    .append(entity.blockPosition().getZ())
                    .append(']');
        }
        out.append('\n');

        out.append("installed_mod_namespaces=");
        out.append(ModpackKnowledge.installedMods());
        out.append('\n');

        return out.toString();
    }

    private static String environment(ServerPlayer p) {
        var level=p.serverLevel();
        BlockPos pos=p.blockPosition();
        StringBuilder b=new StringBuilder();
        b.append("daylight=").append(level.isDay()).append(',');
        b.append("raining=").append(level.isRaining()).append(',');
        b.append("thunder=").append(level.isThundering()).append(',');
        b.append("light=").append(level.getMaxLocalRawBrightness(pos)).append(',');
        b.append("fluid_at_feet=").append(level.getFluidState(pos).getType().builtInRegistryHolder().key().location()).append(',');
        b.append("fluid_below=").append(level.getFluidState(pos.below()).getType().builtInRegistryHolder().key().location()).append(',');
        b.append("effects=");
        for(var effect:p.getActiveEffects()) b.append(effect.getEffect().getDescriptionId()).append(':').append(effect.getAmplifier()).append(';');
        int danger=level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,p.getBoundingBox().inflate(12),e->e.isAlive()).size();
        b.append(",nearby_hostiles=").append(danger);
        boolean edge=level.getMinBuildHeight()>=pos.getY() || level.isEmptyBlock(pos.below(3));
        b.append(",possible_drop_risk=").append(edge);
        return b.toString();
    }
    private WorldContext() {}
}
