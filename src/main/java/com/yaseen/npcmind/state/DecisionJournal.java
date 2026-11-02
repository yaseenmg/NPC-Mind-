package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import java.util.ArrayDeque;
import java.util.Deque;
import com.yaseen.npcmind.api.Internal;

/** Small persistent cause->decision->outcome journal for real actions. */
@Internal
public final class DecisionJournal {
    private final Deque<String> entries=new ArrayDeque<>();
    /** Parallel sequence numbers aligned with {@link #entries}; lets hasSuccessAfter() restrict
     * its search to entries recorded AFTER the baseline instead of scanning the whole journal. */
    private final Deque<Long> sequences=new ArrayDeque<>();
    private long sequence;
    public void record(String cause,String decision,String outcome){sequence++;entries.addLast("cause="+cause+" decision="+decision+" outcome="+outcome);sequences.addLast(sequence);while(entries.size()>96){entries.removeFirst();sequences.removeFirst();}}
    public long sequence(){return sequence;}
    public boolean hasSuccessAfter(long baseline){
        if(sequence<=baseline)return false;
        java.util.Iterator<String> e=entries.iterator(); java.util.Iterator<Long> q=sequences.iterator();
        while(e.hasNext()&&q.hasNext()){
            long seq=q.next(); String entry=e.next();
            if(seq>baseline&&(entry.contains("outcome=SUCCESS")||entry.contains("outcome=RESULT")))return true;
        }
        return false;
    }
    public boolean hasRecentSuccess(){
        return entries.stream().skip(Math.max(0,entries.size()-12)).anyMatch(s -> s.contains("outcome=SUCCESS") || s.contains("outcome=RESULT"));
    }
    public String summary(){return String.join(" | ",entries.stream().skip(Math.max(0,entries.size()-20)).toList());}
    public void save(CompoundTag tag){ListTag l=new ListTag();for(String s:entries)l.add(StringTag.valueOf(s));tag.put("NPCMindDecisions",l); tag.putLong("NPCMindDecisionSequence",sequence);}
    public void load(CompoundTag tag){entries.clear();sequences.clear();sequence=tag.getLong("NPCMindDecisionSequence");if(!tag.contains("NPCMindDecisions",9))return;ListTag l=tag.getList("NPCMindDecisions",8);long first=sequence-l.size();for(int i=0;i<l.size();i++){entries.addLast(l.getString(i));sequences.addLast(first+i+1);}}
}
