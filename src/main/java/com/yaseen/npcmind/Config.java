package com.yaseen.npcmind;

import net.minecraftforge.common.ForgeConfigSpec;
import com.yaseen.npcmind.api.Internal;

/** Local-only companion configuration. No remote AI provider or API key is required. */
@Internal
public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue PERSISTENT_MEMORY;
    public static final ForgeConfigSpec.IntValue MEMORY_LIMIT;
    public static final ForgeConfigSpec.IntValue CONTEXT_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_NEARBY_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_CONTROL;
    public static final ForgeConfigSpec.ConfigValue<String> CHAT_PREFIX;
    public static final ForgeConfigSpec.BooleanValue AUTO_PICKUP;
    public static final ForgeConfigSpec.BooleanValue AUTO_RESPAWN;
    public static final ForgeConfigSpec.IntValue RESPAWN_DELAY_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("companion");
        PERSISTENT_MEMORY = b.define("persistentMemory", true);
        MEMORY_LIMIT = b.defineInRange("memoryLimit", 100, 10, 1000);
        CONTEXT_RADIUS = b.defineInRange("contextRadius", 16, 4, 64);
        MAX_NEARBY_ENTITIES = b.defineInRange("maxNearbyEntities", 24, 4, 100);
        ENABLE_CHAT_CONTROL = b.define("enableChatControl", true);
        // Require an explicit command prefix to avoid eating ordinary chat that starts with '!'.
        CHAT_PREFIX = b.define("chatPrefix", "!npc");
        AUTO_PICKUP = b.define("autoPickup", false);
        AUTO_RESPAWN = b.define("autoRespawn", true);
        RESPAWN_DELAY_SECONDS = b.defineInRange("respawnDelaySeconds", 5, 0, 300);
        b.pop();
        SPEC = b.build();
    }

    private Config() {}
}
