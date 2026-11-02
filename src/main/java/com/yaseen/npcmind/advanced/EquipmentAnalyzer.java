package com.yaseen.npcmind.advanced;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.Enchantments;
import com.yaseen.npcmind.api.Internal;

/** Scores real ItemStacks, including modded items, without assuming a vanilla class. */
@Internal
public final class EquipmentAnalyzer {
    public static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        String id = stack.getItem().builtInRegistryHolder().key().location().toString();
        StringBuilder b = new StringBuilder();
        b.append(id).append(" name=").append(stack.getHoverName().getString());
        b.append(" count=").append(stack.getCount());
        b.append(" damage=").append(stack.getDamageValue()).append('/').append(stack.getMaxDamage());
        b.append(" enchants=").append(EnchantmentHelper.getEnchantments(stack).size());
        b.append(" sharpness=").append(EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, stack));
        b.append(" energy=").append(EnergyInspector.item(stack));
        if (stack.getItem() instanceof ArmorItem armor) b.append(" armor=" ).append(armor.getDefense());
        if (stack.getItem() instanceof ShieldItem) b.append(" shield=true");
        if (stack.getItem() instanceof SwordItem) b.append(" sword=true");
        if (stack.getItem() instanceof DiggerItem) b.append(" digger=true");
        if (stack.getItem() instanceof ProjectileWeaponItem) b.append(" projectile=true");
        if (stack.isEdible()) { var food = stack.getFoodProperties(null); b.append(" edible=true"); if (food != null) b.append(" food=").append(food.getNutrition()); }
        return b.toString();
    }

    /** True for actual combat weapons, while deliberately excluding mining/farming tools.
     * Modded melee weapons are accepted when they expose a real ATTACK_DAMAGE attribute.
     */
    public static boolean isCombatWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof ProjectileWeaponItem) return true;
        if (item instanceof DiggerItem || item instanceof ShieldItem || item instanceof ArmorItem) return false;
        for (var modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(Attributes.ATTACK_DAMAGE)) {
            if (modifier.getAmount() > 0.0D) return true;
        }
        return false;
    }

    public static double combatScore(ItemStack stack, boolean ranged) {
        if (stack == null || stack.isEmpty()) return -1;
        double score = 0;
        if (stack.getItem() instanceof SwordItem) score += 10;
        if (stack.getItem() instanceof AxeItem) score += 8;
        if (stack.getItem() instanceof ProjectileWeaponItem) score += ranged ? 12 : 4;
        if (stack.getItem() instanceof ShieldItem) score += 5;
        if (stack.getMaxDamage() > 0) score += 3.0 * (1.0 - ((double)stack.getDamageValue()/stack.getMaxDamage()));
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, stack) * 2.0;
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, stack) * 0.5;
        var attributes = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (var entry : attributes.entries()) {
            Attribute attribute = entry.getKey();
            double amount = entry.getValue().getAmount();
            if (attribute == Attributes.ATTACK_DAMAGE) score += Math.max(0.0, amount) * 1.5;
            if (attribute == Attributes.ATTACK_SPEED) score += Math.max(0.0, amount) * 0.15;
        }
        var energy = stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).orElse(null);
        if (energy != null) score += Math.min(5.0, energy.getEnergyStored() / (double)Math.max(1, energy.getMaxEnergyStored()) * 5.0);
        return score;
    }


    public static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) return -1;
        if (!(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) return -1;
        double score = armor.getDefense() * 2.0 + armor.getToughness();
        for (var entry : stack.getAttributeModifiers(slot).entries()) {
            if (entry.getKey() == Attributes.ARMOR || entry.getKey() == Attributes.ARMOR_TOUGHNESS) {
                score += Math.max(0.0, entry.getValue().getAmount());
            }
        }
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, stack) * 1.5;
        if (stack.getMaxDamage() > 0) score += 2.0 * (1.0 - ((double) stack.getDamageValue() / stack.getMaxDamage()));
        return score;
    }

    public static String armorSummary(net.minecraft.world.entity.LivingEntity e) {
        StringBuilder b = new StringBuilder();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = e.getItemBySlot(slot);
            b.append(slot.getName()).append('=').append(describe(s)).append('\n');
        }
        return b.toString();
    }
    private EquipmentAnalyzer() {}
}
