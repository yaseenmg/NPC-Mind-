package com.yaseen.npcmind.advanced;

import com.yaseen.npcmind.entity.CompanionEntity;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import com.yaseen.npcmind.api.Internal;

/** Conservative automatic inventory housekeeping: only clearly obsolete equipment is discarded. */
@Internal
public final class InventoryIntelligence {
    private static final int PERIOD = 200;
    /** Item types this companion dropped as junk recently -> game time of the drop. Read by the
     * drop pickup loop so junk is not vacuumed straight back (drop -> AUTO_PICKUP -> drop loop). */
    private static final Map<UUID, Map<String, Long>> RECENT_DROPS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RECENT_DROP_TTL_TICKS = 1200L;

    public static void noteDropped(CompanionEntity c, ItemStack stack) {
        if (c == null || stack == null || stack.isEmpty()) return;
        String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        RECENT_DROPS.computeIfAbsent(c.getUUID(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(id, c.level().getGameTime());
    }

    public static boolean recentlyDropped(CompanionEntity c, ItemStack stack) {
        if (c == null || stack == null || stack.isEmpty()) return false;
        Map<String, Long> drops = RECENT_DROPS.get(c.getUUID());
        if (drops == null || drops.isEmpty()) return false;
        String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        Long at = drops.get(id);
        if (at == null) return false;
        if (c.level().getGameTime() - at > RECENT_DROP_TTL_TICKS) { drops.remove(id, at); return false; }
        return true;
    }

    public static void clear(UUID id) { if (id != null) RECENT_DROPS.remove(id); }

    public static void tick(CompanionEntity c) {
        if (c.level().isClientSide() || c.tickCount % PERIOD != 0 || c.getTarget() != null || c.isUsingItem()) return;
        Container inv=c.getCompanionInventory();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET}) {
            ItemStack equipped=c.getItemBySlot(slot);
            double best=EquipmentAnalyzer.armorScore(equipped,slot);
            for(int i=0;i<inv.getContainerSize();i++) {
                ItemStack s=inv.getItem(i);
                if(s.isEmpty() || !(s.getItem() instanceof ArmorItem)) continue;
                double score=EquipmentAnalyzer.armorScore(s,slot);
                // Keep an actually useful spare, but remove obviously obsolete unenchanted armor.
                if(score >= 0 && best > score + 2.0 && EnchantmentHelper.getEnchantments(s).isEmpty()
                        && isInventoryFull(inv)) {
                    c.spawnAtLocation(s.copy()); InventoryIntelligence.noteDropped(c, s); inv.setItem(i,ItemStack.EMPTY);
                }
            }
        }
        // Duplicate combat tools: keep the two best melee weapons and one best ranged weapon.
        int best1=-1,best2=-1; double s1=-1,s2=-1;
        int ranged=-1; double rs=-1;
        for(int i=0;i<inv.getContainerSize();i++) {
            ItemStack s=inv.getItem(i); if(s.isEmpty())continue;
            // Mining/farming tools are not disposable combat duplicates. Only actual swords
            // participate in combat-tool pruning; a pickaxe/axe/shovel may be needed later.
            if(s.getItem() instanceof SwordItem) {
                double v=EquipmentAnalyzer.combatScore(s,false);
                if(v>s1){s2=s1;best2=best1;s1=v;best1=i;} else if(v>s2){s2=v;best2=i;}
            }
            if(s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) {
                double v=EquipmentAnalyzer.combatScore(s,true);
                if(v>rs){rs=v;ranged=i;}
            }
        }
        // Keep a small reserve: a player-like companion should not throw away the only spare
        // weapon. Only discard a third+ unenchanted duplicate when there are already at least
        // two useful alternatives of the same combat role.
        int swordCount = 0, rangedCount = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof SwordItem) swordCount++;
            if (s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) rangedCount++;
        }
        if (isInventoryFull(inv)) for(int i=0;i<inv.getContainerSize();i++) {
            if(i==best1||i==best2||i==ranged)continue;
            ItemStack s=inv.getItem(i); if(s.isEmpty()||EnchantmentHelper.getEnchantments(s).size()>0)continue;
            if(s.getItem() instanceof SwordItem && swordCount > 2) {
                c.spawnAtLocation(s.copy()); InventoryIntelligence.noteDropped(c, s); inv.setItem(i,ItemStack.EMPTY); swordCount--;
            } else if((s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem) && rangedCount > 1) {
                c.spawnAtLocation(s.copy()); InventoryIntelligence.noteDropped(c, s); inv.setItem(i,ItemStack.EMPTY); rangedCount--;
            }
        }
        inv.setChanged();
    }
    private static boolean isInventoryFull(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).isEmpty()) return false;
        return true;
    }
    private InventoryIntelligence(){}
}
