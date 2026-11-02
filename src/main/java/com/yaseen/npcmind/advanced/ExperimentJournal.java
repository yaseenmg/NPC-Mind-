package com.yaseen.npcmind.advanced;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import java.util.ArrayDeque;
import java.util.Deque;
import com.yaseen.npcmind.api.Internal;

/** Records verified experiments instead of allowing the AI to treat guesses as facts. */
@Internal
public final class ExperimentJournal {
    private final Deque<String> verified=new ArrayDeque<>();
    private final Deque<String> unknown=new ArrayDeque<>();
    public void verified(String claim){if(claim==null||claim.isBlank())return;verified.addLast(claim);while(verified.size()>128)verified.removeFirst();}
    public void unknown(String claim){if(claim==null||claim.isBlank())return;unknown.addLast(claim);while(unknown.size()>128)unknown.removeFirst();}
    public String summary(){return "verified="+verified.stream().skip(Math.max(0,verified.size()-24)).toList()+" unknown="+unknown.stream().skip(Math.max(0,unknown.size()-16)).toList();}
    public void save(CompoundTag tag){ListTag a=new ListTag();for(String s:verified)a.add(StringTag.valueOf(s));ListTag b=new ListTag();for(String s:unknown)b.add(StringTag.valueOf(s));tag.put("NPCMindVerified",a);tag.put("NPCMindUnknown",b);}
    public void load(CompoundTag tag){verified.clear();unknown.clear();ListTag a=tag.getList("NPCMindVerified",8);for(int i=0;i<a.size();i++)verified(a.getString(i));ListTag b=tag.getList("NPCMindUnknown",8);for(int i=0;i<b.size();i++)unknown(b.getString(i));}
}
