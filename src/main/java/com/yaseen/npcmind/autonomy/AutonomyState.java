package com.yaseen.npcmind.autonomy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import java.util.*;
import com.yaseen.npcmind.api.Internal;

/** Persistent high-level autonomy state. Every entry is a rule/state value, not fake world data. */
@Internal
public final class AutonomyState {
    public enum Mode { FOLLOW, BODYGUARD, EXPLORER, ENGINEER, MINER, FARMER, SURVIVAL, IDLE }
    private Mode mode = Mode.FOLLOW;
    private boolean independentRoutine = false;
    private boolean proactiveSuggestions = true;
    private String lastRoutineAction = "";
    private long lastRoutineTime = Long.MIN_VALUE;
    private final Deque<String> preferences = new ArrayDeque<>();
    private final Map<String,Integer> style = new LinkedHashMap<>();
    private final Map<String,Integer> reservations = new LinkedHashMap<>();

    public Mode mode(){return mode;}
    public void mode(Mode m){if(m!=null)mode=m;}
    public boolean routine(){return independentRoutine;}
    public void routine(boolean v){independentRoutine=v;}
    public boolean suggestions(){return proactiveSuggestions;}
    public void suggestions(boolean v){proactiveSuggestions=v;}
    public String lastRoutineAction(){return lastRoutineAction;}
    public long lastRoutineTime(){return lastRoutineTime;}
    public void routineDone(String action,long time){lastRoutineAction=action==null?"":action;lastRoutineTime=time;}
    public void preference(String p){if(p==null||p.isBlank())return;preferences.remove(p);preferences.addLast(p);while(preferences.size()>32)preferences.removeFirst();}
    public void style(String key,int delta){if(key==null||key.isBlank())return;style.put(key,Math.max(-100,Math.min(100,style.getOrDefault(key,0)+delta)));}
    public void reserve(String item,int count){if(item==null||item.isBlank()||count<=0)return;reservations.put(item,reservations.getOrDefault(item,0)+count);}
    public void release(String item,int count){if(item==null||count<=0)return;int n=reservations.getOrDefault(item,0)-count;if(n<=0)reservations.remove(item);else reservations.put(item,n);}
    public int reserved(String item){return reservations.getOrDefault(item,0);}
    public String summary(){return "mode="+mode+",routine="+independentRoutine+",suggestions="+proactiveSuggestions+",lastRoutine="+lastRoutineAction+",preferences="+preferences+",style="+style+",reservations="+reservations;}
    public void save(CompoundTag tag){CompoundTag r=new CompoundTag();r.putString("mode",mode.name());r.putBoolean("routine",independentRoutine);r.putBoolean("suggestions",proactiveSuggestions);r.putString("last",lastRoutineAction);r.putLong("lastTime",lastRoutineTime);ListTag p=new ListTag();for(String s:preferences)p.add(StringTag.valueOf(s));r.put("prefs",p);CompoundTag st=new CompoundTag();style.forEach(st::putInt);r.put("style",st);CompoundTag rs=new CompoundTag();reservations.forEach(rs::putInt);r.put("reservations",rs);tag.put("NPCMindAutonomy",r);}
    public void load(CompoundTag tag){if(!tag.contains("NPCMindAutonomy",10))return;CompoundTag r=tag.getCompound("NPCMindAutonomy");try{mode=Mode.valueOf(r.getString("mode"));}catch(IllegalArgumentException invalidSavedMode){com.yaseen.npcmind.NPCMind.LOG.debug("[NPCMind] ignoring unknown saved autonomy mode '{}'",r.getString("mode"));}independentRoutine=r.getBoolean("routine");proactiveSuggestions=!r.contains("suggestions")||r.getBoolean("suggestions");lastRoutineAction=r.getString("last");lastRoutineTime=r.getLong("lastTime");preferences.clear();ListTag p=r.getList("prefs",8);for(int i=0;i<p.size();i++)preference(p.getString(i));style.clear();CompoundTag st=r.getCompound("style");for(String k:st.getAllKeys())style.put(k,st.getInt(k));reservations.clear();CompoundTag rs=r.getCompound("reservations");for(String k:rs.getAllKeys())reservations.put(k,rs.getInt(k));}
}
