package com.yaseen.npcmind.entity;

import com.yaseen.npcmind.NPCMind;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, NPCMind.MOD_ID);

    public static final RegistryObject<EntityType<CompanionEntity>> COMPANION =
            ENTITY_TYPES.register("companion", () ->
                    EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(32)
                            .build("npcmind:companion"));

    /** Vanilla-parity ender pearl that teleports any living owner (same size as the vanilla pearl). */
    public static final RegistryObject<EntityType<CompanionEnderpearl>> COMPANION_ENDER_PEARL =
            ENTITY_TYPES.register("companion_ender_pearl", () ->
                    EntityType.Builder.<CompanionEnderpearl>of(CompanionEnderpearl::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(4)
                            .updateInterval(20)
                            .build("npcmind:companion_ender_pearl"));

    private ModEntities() {}
}
