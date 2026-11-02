package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import com.yaseen.npcmind.api.Internal;

/** Stores observations of player demonstrations as compact, real item/block snapshots. */
@Internal
public final class ImitationMemory {
    private static final String KEY="NPCMindImitations";
    public static void record(CompanionEntity c,String label,ItemStack[] observed,String result) {
        CompoundTag root=c.getPersistentData(); ListTag list=root.getList(KEY,10); CompoundTag e=new CompoundTag();e.putString("label",label);e.putString("result",result==null?"":result);ListTag items=new ListTag();if(observed!=null)for(ItemStack s:observed)if(s!=null&&!s.isEmpty())items.add(s.save(new CompoundTag()));e.put("items",items);list.add(e);while(list.size()>32)list.remove(0);root.put(KEY,list);
    }
    public static String summary(CompanionEntity c){ListTag list=c.getPersistentData().getList(KEY,10);StringBuilder b=new StringBuilder();for(int i=Math.max(0,list.size()-8);i<list.size();i++){CompoundTag e=list.getCompound(i);b.append(e.getString("label")).append(" => ").append(e.getString("result")).append('\n');}return b.toString();}
    private ImitationMemory(){}
}
