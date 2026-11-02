package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import java.util.EnumMap;
import com.yaseen.npcmind.api.Internal;

/** Explicit safety gates for world-changing AI actions. */
@Internal
public final class ActionPermissions {
    public enum Action { BUILD, MINE, FARM, TRADE, CRAFT, CONSUME_RARE, DROP, DESTROY }
    private final EnumMap<Action,Boolean> values=new EnumMap<>(Action.class);
    public ActionPermissions(){for(Action a:Action.values())values.put(a,true);values.put(Action.CONSUME_RARE,false);values.put(Action.DROP,false);values.put(Action.DESTROY,false);}
    public boolean allowed(Action a){return values.getOrDefault(a,false);}
    public void set(Action a,boolean v){values.put(a,v);}
    public String summary(){StringBuilder b=new StringBuilder();for(Action a:Action.values())b.append(a.name().toLowerCase()).append('=').append(allowed(a)).append(' ');return b.toString().trim();}
    public void save(CompoundTag tag){CompoundTag r=new CompoundTag();for(Action a:Action.values())r.putBoolean(a.name(),allowed(a));tag.put("NPCMindPermissions",r);}
    public void load(CompoundTag tag){if(!tag.contains("NPCMindPermissions",10))return;CompoundTag r=tag.getCompound("NPCMindPermissions");for(Action a:Action.values())if(r.contains(a.name()))values.put(a,r.getBoolean(a.name()));}
}
