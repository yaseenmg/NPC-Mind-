package com.yaseen.npcmind.client;
import com.yaseen.npcmind.entity.ModEntities;

import com.yaseen.npcmind.menu.ModMenus;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.yaseen.npcmind.api.Internal;

/**
 * Client-only registration kept out of common/server classes to preserve dedicated-server safety.
 *
 * <p><b>Library note (v1.2.0):</b> this class is no longer annotation-scanned — the project
 * ships as a developer library. The host mod activates it on the client through
 * {@code NPCMindLibrary.registerClient(modEventBus)}, which guards by dist and then calls
 * {@link #register(IEventBus)}. Never reference this class from common code paths.</p>
 */
@Internal
public final class ClientRendering {
    /** Register this class on the host mod's MOD-lifecycle event bus (client dist only). */
    public static void register(IEventBus hostModEventBus) {
        hostModEventBus.register(ClientRendering.class);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.COMPANION.get(), CompanionRenderer::new);
        // The companion pearl keeps the vanilla thrown-item look (an ender pearl sprite).
        event.registerEntityRenderer(ModEntities.COMPANION_ENDER_PEARL.get(), ThrownItemRenderer::new);
    }

    /**
     * Menu screens register inside FMLClientSetupEvent + MenuScreens.register. This is the
     * pattern that exists on every Forge 1.20.1 build, unlike the newer screen-registry event
     * whose availability differs between Forge builds.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                net.minecraft.client.gui.screens.MenuScreens.register(
                        ModMenus.COMPANION_INVENTORY.get(), CompanionInventoryScreen::new));
    }

    private ClientRendering() {}
}
