package com.yaseen.npcmind.tool.impl;
import com.yaseen.npcmind.tool.JsonArgs;
import com.yaseen.npcmind.autonomy.ResourceLedger;import com.yaseen.npcmind.entity.CompanionEntity;import com.yaseen.npcmind.tool.AITool;import net.minecraft.server.level.ServerPlayer;
import com.yaseen.npcmind.api.Internal;
@Internal
public final class ReserveResourceTool implements AITool{
 public String name(){return "reserve_resource";} public String description(){return "Reserve real companion-inventory resources for a long-term project; never creates or removes items.";} public String schema(){return "{\"item\":\"namespace:item\",\"count\":number,\"action\":\"reserve|release\"}";}
 public String execute(CompanionEntity c,ServerPlayer p,String a){String id=MoveToTool.str(a,"item"),act=MoveToTool.str(a,"action");Integer n=num(a,"count");if(id==null||n==null||n<=0)return "ERROR item_and_positive_count_required";if("release".equals(act)){c.getAutonomy().release(id,n);return "RESULT released="+n+" item="+id;}if(ResourceLedger.available(c,id)<n)return "FAILED insufficient_unreserved_real_stock available="+ResourceLedger.available(c,id);c.getAutonomy().reserve(id,n);return "RESULT reserved="+n+" item="+id+" available_after="+ResourceLedger.available(c,id);}
 private Integer num(String s,String n){return JsonArgs.integer(s,n);}}
