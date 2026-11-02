package com.yaseen.npcmind.state;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;
import com.yaseen.npcmind.api.Internal;

/** Persistent memory of observations made from the real loaded Minecraft world. */
@Internal
public final class WorldMemory {
    private static final String ROOT="NPCMindWorldMemory";
    public record Observation(String kind, String dimension, BlockPos pos, String note, long gameTime) {}
    private final Deque<Observation> observations=new ArrayDeque<>();
    private final Set<String> visitedChunks=new HashSet<>();
    private final Deque<String> failures=new ArrayDeque<>();
    private final Deque<String> successes=new ArrayDeque<>();

    public void observe(String kind,String dimension,BlockPos pos,String note,long time){
        if(pos==null)return; observations.addLast(new Observation(kind,dimension,pos,note==null?"":note,time));
        while(observations.size()>256)observations.removeFirst();
        visitedChunks.add(dimension+":"+(pos.getX()>>4)+":"+(pos.getZ()>>4));
        while(visitedChunks.size()>4096){Iterator<String> it=visitedChunks.iterator();if(it.hasNext()){it.next();it.remove();}}
    }
    public void success(String text){if(text==null||text.isBlank())return;successes.addLast(text);while(successes.size()>64)successes.removeFirst();}
    public void failure(String text){if(text==null||text.isBlank())return;failures.addLast(text);while(failures.size()>64)failures.removeFirst();}
    public boolean visited(String dimension,BlockPos p){return visitedChunks.contains(dimension+":"+(p.getX()>>4)+":"+(p.getZ()>>4));}
    public String summary(){
        StringBuilder b=new StringBuilder("observations=");
        int n=0; for(Observation o:observations){if(n++>=24)break;b.append('[').append(o.kind()).append('@').append(o.dimension()).append(':').append(o.pos().getX()).append(',').append(o.pos().getY()).append(',').append(o.pos().getZ()).append(" note=").append(o.note()).append(']');}
        b.append(" visited_chunks=").append(visitedChunks.size());
        b.append(" recent_successes=").append(successes); b.append(" recent_failures=").append(failures); return b.toString();
    }
    public void save(CompoundTag tag){
        CompoundTag r=new CompoundTag(); ListTag list=new ListTag();
        int i=0; for(Observation o:observations){CompoundTag x=new CompoundTag();x.putString("kind",o.kind());x.putString("dimension",o.dimension());x.putInt("x",o.pos().getX());x.putInt("y",o.pos().getY());x.putInt("z",o.pos().getZ());x.putString("note",o.note());x.putLong("time",o.gameTime());list.add(x);if(++i>=256)break;} r.put("observations",list);
        ListTag chunks=new ListTag();for(String s:visitedChunks)chunks.add(net.minecraft.nbt.StringTag.valueOf(s));r.put("visited",chunks);
        ListTag fs=new ListTag();for(String s:failures)fs.add(net.minecraft.nbt.StringTag.valueOf(s));r.put("failures",fs);
        ListTag ss=new ListTag();for(String s:successes)ss.add(net.minecraft.nbt.StringTag.valueOf(s));r.put("successes",ss);tag.put(ROOT,r);
    }
    public void load(CompoundTag tag){
        observations.clear();visitedChunks.clear();failures.clear();successes.clear();if(!tag.contains(ROOT,10))return;CompoundTag r=tag.getCompound(ROOT);
        ListTag list=r.getList("observations",10);for(int i=0;i<list.size();i++){CompoundTag x=list.getCompound(i);observe(x.getString("kind"),x.getString("dimension"),new BlockPos(x.getInt("x"),x.getInt("y"),x.getInt("z")),x.getString("note"),x.getLong("time"));}
        ListTag ch=r.getList("visited",8);for(int i=0;i<ch.size();i++)visitedChunks.add(ch.getString(i));
        ListTag fs=r.getList("failures",8);for(int i=0;i<fs.size();i++)failure(fs.getString(i));
        ListTag ss=r.getList("successes",8);for(int i=0;i<ss.size();i++)success(ss.getString(i));
    }
}
