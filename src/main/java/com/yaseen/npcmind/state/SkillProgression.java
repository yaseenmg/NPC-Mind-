package com.yaseen.npcmind.state;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.EnumMap;
import java.util.Map;
import com.yaseen.npcmind.api.Internal;

/** Persistent skill XP earned only from real completed actions. */
@Internal
public final class SkillProgression {
    public enum Skill { MINING, COMBAT, CRAFTING, FARMING, EXPLORATION, SURVIVAL, BUILDING }
    private static final String ROOT="NPCMindSkills";
    private final Map<Skill,Integer> xp=new EnumMap<>(Skill.class);
    private int appliedSpeedLevel=-1;
    public SkillProgression(){for(Skill s:Skill.values())xp.put(s,0);}
    public void add(Skill s,int amount){if(amount<=0)return;xp.put(s,Math.min(1_000_000,xp.getOrDefault(s,0)+amount));}
    public int xp(Skill s){return xp.getOrDefault(s,0);}
    public int level(Skill s){return Math.min(100,(int)Math.sqrt(xp(s)/10.0));}
    public String summary(){StringBuilder b=new StringBuilder();for(Skill s:Skill.values())b.append(s.name().toLowerCase()).append("=lv").append(level(s)).append(" xp").append(xp(s)).append(' ');return b.toString().trim();}
    public void applyAttributes(CompanionEntity c){
        int lv=level(Skill.EXPLORATION);
        if(lv==appliedSpeedLevel)return;
        // Keep physical movement player-like. Exploration skill improves route planning and
        // recovery, not raw top speed; the previous +0.08 base bonus made sprinting too fast.
        AttributeInstance speed=c.getAttribute(Attributes.MOVEMENT_SPEED);
        if(speed!=null) speed.setBaseValue(.10D);
        appliedSpeedLevel=lv;
    }
    public void save(CompoundTag tag){CompoundTag r=new CompoundTag();for(Skill s:Skill.values())r.putInt(s.name(),xp(s));tag.put(ROOT,r);}
    public void load(CompoundTag tag){if(!tag.contains(ROOT))return;CompoundTag r=tag.getCompound(ROOT);for(Skill s:Skill.values())xp.put(s,r.getInt(s.name()));}
}
