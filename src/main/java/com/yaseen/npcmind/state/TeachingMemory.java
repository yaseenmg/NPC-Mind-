package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import com.yaseen.npcmind.api.Internal;

/** Player-defined voice/chat triggers persisted with the real companion entity. */
@Internal
public final class TeachingMemory {
    private static final String ROOT="NPCMindTriggers";
    private final Map<String,String> triggers=new LinkedHashMap<>();
    public void teach(String phrase,String instruction){if(phrase==null||instruction==null||phrase.isBlank()||instruction.isBlank())return;triggers.put(norm(phrase),instruction);while(triggers.size()>64)triggers.remove(triggers.keySet().iterator().next());}
    public String resolve(String phrase){if(phrase==null)return null;return triggers.get(norm(phrase));}
    public String summary(){if(triggers.isEmpty())return "none";return triggers.toString();}
    private static String norm(String s){return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    public void save(CompoundTag tag){ListTag list=new ListTag();for(var e:triggers.entrySet()){CompoundTag t=new CompoundTag();t.putString("phrase",e.getKey());t.putString("instruction",e.getValue());list.add(t);}tag.put(ROOT,list);}
    public void load(CompoundTag tag){triggers.clear();if(!tag.contains(ROOT,9))return;ListTag list=tag.getList(ROOT,10);for(int i=0;i<list.size()&&i<64;i++){CompoundTag t=list.getCompound(i);teach(t.getString("phrase"),t.getString("instruction"));}}
}
