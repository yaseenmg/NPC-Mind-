package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/**
 * Long-distance structure travel. TaskManager deliberately limits one navigation leg for safety;
 * this controller turns a 5k/10k block structure target into safe ~64-block legs and continues
 * automatically after each successful leg.
 */
@Internal
public final class StructureTravelController {
    private static final Map<UUID, Target> TARGETS = new ConcurrentHashMap<>();
    private static final double LEG = 64.0D;

    private static final class Target {
        final BlockPos target; final String structure; long lastCommand;
        Target(BlockPos target,String structure){this.target=target;this.structure=structure;}
    }

    public static String start(CompanionEntity c, BlockPos target, String structure) {
        if (!(c.level() instanceof ServerLevel)) return "FAILED not_server";
        if (target == null) return "FAILED missing_target";
        Target t=new Target(target,structure==null?"unknown":structure);
        TARGETS.put(c.getUUID(),t);
        return issueLeg(c,t);
    }

    public static void cancel(CompanionEntity c) { TARGETS.remove(c.getUUID()); }
    /** Removes all travel state for a companion that died or was removed (leak fix). */
    public static void clear(UUID id) { if (id != null) TARGETS.remove(id); }
    public static boolean active(CompanionEntity c) { return TARGETS.containsKey(c.getUUID()); }

    public static void tick(CompanionEntity c) {
        Target t=TARGETS.get(c.getUUID());
        if(t==null || c.level().isClientSide()) return;
        if(c.level().getGameTime()-t.lastCommand < 5) return;
        double d=c.distanceToSqr(t.target.getX()+.5,t.target.getY(),t.target.getZ()+.5);
        if(d<=9.0D){ TARGETS.remove(c.getUUID()); return; }
        if(TaskManager.isActive(c)) return;
        issueLeg(c,t);
    }

    private static String issueLeg(CompanionEntity c, Target t){
        double dx=t.target.getX()+.5-c.getX(), dz=t.target.getZ()+.5-c.getZ();
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d<=14){
            t.lastCommand=c.level().getGameTime();
            return TaskManager.move(c,t.target);
        }
        double scale=Math.min(LEG/d,1.0D);
        int x=(int)Math.floor(c.getX()+dx*scale);
        int z=(int)Math.floor(c.getZ()+dz*scale);
        // Use the loaded surface height at the waypoint when available. The old companion-Y
        // midpoint walked into the first hill and failed with no_safe_stand_near_target.
        BlockPos waypoint=new BlockPos(x, surfaceY(c, x, z), z);
        t.lastCommand=c.level().getGameTime();
        String r=TaskManager.move(c,waypoint);
        if(r.startsWith("FAILED")){
            // A single bad leg (unloaded chunk, momentary obstruction) used to cancel the whole
            // journey (TARGETS.remove). Keep the destination and retry on a later tick instead;
            // tick() re-issues legs while the target is still more than 3 blocks away.
            t.lastCommand=c.level().getGameTime()+100L; // back off 5 s before the next leg attempt
            return "PARTIAL leg_failed structure_target="+t.structure+" final="+t.target+" leg="+waypoint+" will_retry " + r;
        }
        return r+" structure_target="+t.structure+" final="+t.target;
    }

    /** Loaded-chunk surface height at a waypoint, falling back to the companion's own Y. */
    private static int surfaceY(CompanionEntity c, int x, int z){
        BlockPos column=new BlockPos(x,0,z);
        if(c.level() instanceof ServerLevel level && level.hasChunkAt(column)){
            return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        }
        return c.blockPosition().getY();
    }
    private StructureTravelController(){}
}
