package com.yaseen.npcmind.state;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.yaseen.npcmind.api.Internal;


/** Persistent, server-side emotional/needs state derived only from real Minecraft state. */
@Internal
public final class CompanionState {
    private static final String ROOT = "NPCMindState";
    private int happiness = 50;
    private int stress = 0;
    private int fear = 0;
    private int boredom = 0;
    private int fatigue = 0;
    private int hunger = 0;
    private long lastAutonomyTick = Long.MIN_VALUE;
    private String lastNotice = "";

    public void tick(CompanionEntity c) {
        if (c.level().isClientSide() || c.tickCount % 20 != 0) return;
        Level level = c.level();
        lastDimension = level.dimension().location().toString();
        lastWeather = level.isThundering() ? "thunder" : (level.isRaining() ? "rain" : "clear");
        int danger = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(10), x -> x != c && x.isAlive())) {
            // Only monsters that are actually hunting this companion or its owner are an active
            // threat. Counting every nearby Monster inflated fear for an idle zombie in a cave.
            if (e instanceof Monster monster) {
                LivingEntity victim = monster.getTarget();
                if (victim == c) danger++;
                else if (victim != null && c.getOwnerPlayer() != null && victim == c.getOwnerPlayer()) danger++;
            }
        }
        int dimensionDanger = c.level().dimension() == Level.NETHER ? 15 : (c.level().dimension() == Level.END ? 10 : 0);
        int weatherStress = c.level().isThundering() ? 12 : (c.level().isRaining() ? 4 : 0);
        fear = clamp(danger * 18 + dimensionDanger + weatherStress + (c.getHealth() <= c.getMaxHealth() * .30 ? 35 : 0), 0, 100);
        stress = clamp((int)Math.round(fear * .65 + (c.getHealth() < c.getMaxHealth() * .55 ? 25 : 0)), 0, 100);
        if (c.getHealth() < c.getMaxHealth() * .45) fatigue = clamp(fatigue + 1, 0, 100);
        else fatigue = clamp(fatigue - 1, 0, 100);
        if (c.getCompanionInventory().getContainerSize() > 0 && inventoryFull(c)) stress = clamp(stress + 10, 0, 100);
        boredom = clamp(boredom + 1, 0, 100);
        if (danger > 0) boredom = Math.max(0, boredom - 3);
        // hunger was written only by eatEvent() and never increased, so the "hunger" stat
        // drifted to 0 forever. Grow it while the food bar is low, ease it when well-fed.
        if (c.getFoodLevel() <= 8) hunger = clamp(hunger + 2, 0, 100);
        else if (c.getFoodLevel() >= 18 && hunger > 0) hunger = clamp(hunger - 1, 0, 100);
        if (c.tickCount % 1200 == 0) happiness = clamp(happiness + (fear == 0 ? 1 : -1), 0, 100);
        if (c.level().dimension() == Level.NETHER && c.getHealth() < c.getMaxHealth() * .55) stress = clamp(stress + 5, 0, 100);
        if (c.level().dimension() == Level.END && danger > 0) fear = clamp(fear + 5, 0, 100);
    }

    public String mood() {
        if (fear >= 70) return "afraid";
        if (stress >= 65) return "stressed";
        if (fatigue >= 70) return "tired";
        if (happiness >= 75) return "happy";
        if (boredom >= 70) return "bored";
        return "calm";
    }

    public String styleSpeech(String text) {
        if (text == null || text.isBlank()) return text;
        if (fear >= 75 || stress >= 80) return text.replace("😄", "").replace("😂", "").trim();
        if (happiness >= 82 && fear < 30 && stress < 35) {
            if (!text.contains("😄") && !text.contains("😂") && text.length() < 90) return text + " 😄";
        }
        return text;
    }

    public String snapshot() {
        return "mood=" + mood() + ",happiness=" + happiness + ",stress=" + stress + ",fear=" + fear
                + ",boredom=" + boredom + ",fatigue=" + fatigue + ",hunger=" + hunger;
    }

    public String autonomyMessage(CompanionEntity c) {
        // BUG FIX: lastAutonomyTick starts at Long.MIN_VALUE and `gameTime - Long.MIN_VALUE`
        // overflows to a negative value, so this guard was always true and the companion never
        // spoke an autonomy message. Only apply the cooldown once a notice has actually fired.
        if (lastAutonomyTick != Long.MIN_VALUE && c.level().getGameTime() - lastAutonomyTick < 240) return null;
        String msg = null;
        if (fear >= 75) msg = "I'm scared — there are too many monsters around.";
        else if (c.getHealth() <= c.getMaxHealth() * .35 && !hasHealingFood(c)) msg = "I'm badly hurt and I have no food to heal. I need help.";
        else if (inventoryFull(c)) msg = "My inventory is full. I can't pick anything else up.";
        else if (c.level().isNight() && fear > 35) msg = "It's dark and I don't feel safe out here.";
        else if (boredom >= 85) msg = "I'm getting bored. Give me something to do?";
        if (msg != null) { lastAutonomyTick = c.level().getGameTime(); lastNotice = msg; }
        return msg;
    }

    private static boolean hasHealingFood(CompanionEntity c) {
        for (int i=0;i<c.getCompanionInventory().getContainerSize();i++) {
            ItemStack s=c.getCompanionInventory().getItem(i);
            if (!s.isEmpty() && (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.isEdible())) return true;
        }
        return false;
    }
    private static boolean inventoryFull(CompanionEntity c) {
        for (int i=0;i<c.getCompanionInventory().getContainerSize();i++) if (c.getCompanionInventory().getItem(i).isEmpty()) return false;
        return true;
    }
    private static int clamp(int x,int a,int b){return Math.max(a,Math.min(b,x));}
    public void positiveEvent(){ happiness=clamp(happiness+5,0,100); stress=clamp(stress-4,0,100); boredom=clamp(boredom-5,0,100); }
    public void dangerEvent(){ fear=clamp(fear+10,0,100); stress=clamp(stress+8,0,100); }
    public void eatEvent(){ hunger=clamp(hunger-25,0,100); happiness=clamp(happiness+3,0,100); }
    public String environmentMood(){return "dimension="+lastDimension+",weather="+lastWeather;}
    private String lastDimension="unknown"; private String lastWeather="clear";

    public void save(CompoundTag tag){
        CompoundTag r=new CompoundTag(); r.putInt("happiness",happiness); r.putInt("stress",stress); r.putInt("fear",fear);
        r.putInt("boredom",boredom); r.putInt("fatigue",fatigue); r.putInt("hunger",hunger); r.putString("lastNotice",lastNotice); r.putString("lastDimension",lastDimension); r.putString("lastWeather",lastWeather); r.putLong("lastAutonomyTick",lastAutonomyTick); tag.put(ROOT,r);
    }
    public void load(CompoundTag tag){
        if(!tag.contains(ROOT))return; CompoundTag r=tag.getCompound(ROOT);
        happiness=r.getInt("happiness"); stress=r.getInt("stress"); fear=r.getInt("fear"); boredom=r.getInt("boredom"); fatigue=r.getInt("fatigue"); hunger=r.getInt("hunger"); lastNotice=r.getString("lastNotice"); lastDimension=r.getString("lastDimension"); lastWeather=r.getString("lastWeather");
        lastAutonomyTick = r.contains("lastAutonomyTick") ? r.getLong("lastAutonomyTick") : Long.MIN_VALUE;
    }
}
