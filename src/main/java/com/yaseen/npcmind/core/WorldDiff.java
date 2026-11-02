package com.yaseen.npcmind.core;
import com.yaseen.npcmind.api.Internal;

/** Deterministic before/after diff; used by the critic instead of trusting tool return strings. */
@Internal
public record WorldDiff(double distanceMoved, float healthDelta, int foodDelta, int inventorySlotDelta,
                        boolean inventoryChanged, boolean taskFinished, String taskResult) {
    public static WorldDiff compare(WorldSnapshot before, WorldSnapshot after, String result) {
        double dx = after.position().distSqr(before.position());
        return new WorldDiff(Math.sqrt(dx), after.health() - before.health(), after.food() - before.food(),
                after.inventoryItems() - before.inventoryItems(),
                !java.util.Objects.equals(before.inventorySignature(), after.inventorySignature()),
                !after.taskStatus().startsWith("RUNNING") && !after.taskStatus().startsWith("STARTED"), result == null ? "" : result);
    }
}
