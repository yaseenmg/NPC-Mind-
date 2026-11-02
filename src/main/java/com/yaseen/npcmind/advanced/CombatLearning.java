package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.SkillProgression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import com.yaseen.npcmind.api.Internal;

/** Persistent, bounded combat experience based only on observed real encounters. */
@Internal
public final class CombatLearning {
    private static final String ROOT = "NPCMindCombatLearning";
    private final CompanionEntity owner;
    private int fights, wins, retreats;
    private double damageTaken, damageDealt;
    private final Map<String, Integer> threat = new HashMap<>();

    public CombatLearning(CompanionEntity owner) { this.owner = owner; }
    public void encounter(Entity e) {
        if (e != null) threat.merge(e.getType().builtInRegistryHolder().key().location().toString(), 1, Integer::sum);
    }
    public void damageTaken(double v) { damageTaken += Math.max(0, v); owner.getCompanionState().dangerEvent(); }
    public void damageDealt(double v) { damageDealt += Math.max(0, v); }
    public void fight() { fights++; }
    public void win() { wins++; owner.getSkills().add(SkillProgression.Skill.COMBAT, 25); owner.getCompanionState().positiveEvent(); }
    public void observeFight(Entity e) { fight(); encounter(e); }
    public void retreat() { retreats++; }
    public int fights() { return fights; }
    public int wins() { return wins; }
    public int retreats() { return retreats; }
    public String summary() {
        return "fights=" + fights + ",wins=" + wins + ",retreats=" + retreats
                + ",damageTaken=" + Math.round(damageTaken) + ",damageDealt=" + Math.round(damageDealt)
                + ",threats=" + threat;
    }
    public void save(CompoundTag tag) {
        CompoundTag r = new CompoundTag();
        r.putInt("fights", fights); r.putInt("wins", wins); r.putInt("retreats", retreats);
        r.putDouble("taken", damageTaken); r.putDouble("dealt", damageDealt);
        CompoundTag t = new CompoundTag(); threat.forEach(t::putInt); r.put("threat", t); tag.put(ROOT, r);
    }
    public void load(CompoundTag tag) {
        if (!tag.contains(ROOT)) return;
        CompoundTag r = tag.getCompound(ROOT);
        fights = r.getInt("fights"); wins = r.getInt("wins"); retreats = r.getInt("retreats");
        damageTaken = r.getDouble("taken"); damageDealt = r.getDouble("dealt"); threat.clear();
        CompoundTag t = r.getCompound("threat"); for (String k : t.getAllKeys()) threat.put(k, t.getInt(k));
    }

    /** Only treat entities that have actually harmed the companion/owner as autonomous threats. */
    public static boolean dangerous(CompanionEntity companion, Entity entity) {
        if (entity == null || entity == companion || !entity.isAlive()) return false;
        if (entity instanceof Player player) return false;
        if (entity instanceof Mob mob) {
            // LastHurtByMob is historical and can remain set long after combat ended.
            // Autonomous combat must use the mob's CURRENT target instead. Actual damage
            // events are handled by ServerEvents and immediately set the companion target.
            if (mob.getTarget() == companion) return true;
            Player owner = companion.getOwnerPlayer();
            if (owner != null && mob.getTarget() == owner) return true;
        }
        return false;
    }

    /** Compatibility helper for callers that only have an Entity. */
    public static boolean dangerous(Entity e) {
        return e instanceof Monster || (e instanceof Mob m && m.getLastHurtByMob() != null);
    }
}
