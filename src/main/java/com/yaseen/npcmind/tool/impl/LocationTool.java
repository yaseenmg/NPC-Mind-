package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.tool.AITool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import com.yaseen.npcmind.api.Internal;

/** Real local-scene scan used when the companion is asked where it is. */
@Internal
public final class LocationTool implements AITool {
    @Override public String name() { return "describe_location"; }
    @Override public String description() { return "Describe the companion's current location: coordinates, dimension, biome and nearby landmarks."; }
    @Override public String schema() { return "{}"; }

    @Override
    public String execute(CompanionEntity c, ServerPlayer p, String a) {
        if (c.level().isClientSide()) return "FAILED client_side";
        Level level = c.level();
        BlockPos pos = c.blockPosition();
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        String dimension = level.dimension().location().toString();
        String weather = level.isThundering() ? "thunder" : (level.isRaining() ? "rain" : "clear");
        String surface = level.getBlockState(pos.below()).getBlock().getDescriptionId();
        String above = level.getBlockState(pos.above()).getBlock().getDescriptionId();
        StringBuilder b = new StringBuilder("REAL_LOCATION\n");
        b.append("exact_block_position=").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append('\n');
        b.append("exact_xyz=").append(String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", c.getX(), c.getY(), c.getZ())).append('\n');
        b.append("dimension=").append(dimension).append('\n');
        b.append("biome=").append(biome).append('\n');
        b.append("weather=").append(weather).append(", day=").append(level.isDay()).append('\n');
        b.append("light=").append(level.getMaxLocalRawBrightness(pos)).append('\n');
        b.append("surface_block=").append(surface).append(", block_above=").append(above).append('\n');
        b.append("nearby_hostiles=").append(level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, c.getBoundingBox().inflate(16), e -> e.isAlive()).size()).append('\n');
        LivingEntity nearest = level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(16), e -> e != c && e.isAlive())
                .stream().min(Comparator.comparingDouble(c::distanceToSqr)).orElse(null);
        if (nearest != null) {
            b.append("nearest_living=").append(nearest.getType().builtInRegistryHolder().key().location())
                    .append(" pos=").append(nearest.blockPosition().getX()).append(',').append(nearest.blockPosition().getY()).append(',').append(nearest.blockPosition().getZ())
                    .append(" distance=").append(String.format(java.util.Locale.ROOT, "%.1f", c.distanceTo(nearest))).append('\n');
        }
        // Eight compass samples are a cheap real "look around" rather than a fabricated description.
        int[][] dirs={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        String[] names={"north","north_east","east","south_east","south","south_west","west","north_west"};
        for(int i=0;i<dirs.length;i++) {
            BlockPos q=pos.offset(dirs[i][0]*3,0,dirs[i][1]*3);
            b.append(names[i]).append('=').append(level.getBlockState(q.below()).getBlock().getDescriptionId()).append('@').append(q.getX()).append(',').append(q.getZ()).append(';');
        }
        return "RESULT " + b;
    }
}
