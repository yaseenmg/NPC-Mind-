package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.yaseen.npcmind.api.Internal;

/** Persistent, resumable project with explicit real-world steps. */
@Internal
public final class LongTermGoal {
    private String title="";
    private String plan="";
    private String status="IDLE";
    private int step;
    private long created;
    private final List<String> steps=new ArrayList<>();
    private long verifiedBaseline;

    public void start(String t,String p,long time){ title=t==null?"":t; plan=p==null?"":p; status="ACTIVE"; step=0; created=time; verifiedBaseline=0L; steps.clear(); }
    public void setSteps(List<String> newSteps){ steps.clear(); if(newSteps!=null) for(String s:newSteps) if(s!=null&&!s.isBlank()&&steps.size()<32) steps.add(s.trim()); step=Math.min(step,Math.max(0,steps.size()-1)); }
    public void stepDone(){ if(!"ACTIVE".equals(status)) return; step++; if(!steps.isEmpty()&&step>=steps.size()) status="COMPLETED"; }
    public void complete(){status="COMPLETED";}
    public void cancel(){status="CANCELLED";}
    public boolean active(){return "ACTIVE".equals(status);}
    public int step(){return step;}
    public String currentStep(){return step>=0&&step<steps.size()?steps.get(step):"";}
    public List<String> steps(){return Collections.unmodifiableList(steps);}
    public long verifiedBaseline(){return verifiedBaseline;}
    public void verifiedBaseline(long value){verifiedBaseline=Math.max(0L,value);}
    public String summary(){return "status="+status+",title="+title+",step="+step+",current="+currentStep()+",steps="+steps+",plan="+plan+",verifiedBaseline="+verifiedBaseline;}
    public void save(CompoundTag tag){
        CompoundTag r=new CompoundTag(); r.putString("title",title); r.putString("plan",plan); r.putString("status",status); r.putInt("step",step); r.putLong("created",created); r.putLong("verifiedBaseline",verifiedBaseline);
        ListTag list=new ListTag(); for(String s:steps) list.add(StringTag.valueOf(s)); r.put("steps",list); tag.put("NPCMindGoal",r);
    }
    public void load(CompoundTag tag){
        if(!tag.contains("NPCMindGoal",10))return; CompoundTag r=tag.getCompound("NPCMindGoal"); title=r.getString("title"); plan=r.getString("plan"); status=r.getString("status"); step=r.getInt("step"); created=r.getLong("created"); verifiedBaseline=r.getLong("verifiedBaseline");
        steps.clear(); ListTag list=r.getList("steps",8); for(int i=0;i<list.size()&&steps.size()<32;i++) steps.add(list.getString(i));
    }
}
