package com.yaseen.npcmind;

import com.yaseen.npcmind.api.NPCMindAPI;
import com.yaseen.npcmind.entity.CompanionAttributes;
import com.yaseen.npcmind.entity.ModEntities;
import com.yaseen.npcmind.menu.ModMenus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Explicit bootstrap facade for host mods.
 *
 * <p><b>Since v1.2.0 NPC Mind ships as a developer library, not as a Forge mod.</b> There is no
 * {@code @Mod} entrypoint, no {@code mods.toml} and no {@code pack.mcmeta} in the library jar;
 * the host mod owns the mod identity. Activate the library explicitly from the host mod:</p>
 *
 * <pre>{@code
 * @Mod("myhostmod")
 * public final class MyHostMod {
 *     public MyHostMod() {
 *         IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
 *         NPCMindLibrary.bootstrap(modBus);      // 1) entities, menus, attributes, config
 *         NPCMindLibrary.registerIntegration();  // 2) commands, tick drivers, chat routing
 *         NPCMindLibrary.registerClient(modBus); // 3) client renderers + screens (guarded)
 *     }
 * }</pre>
 *
 * <p>Ordering notes: {@link #bootstrap} must run before registry events fire — calling it in
 * the host mod constructor is correct. {@link #registerIntegration} may run at any point
 * before players interact (constructor or common setup). All three methods are idempotent and
 * safe to call once per launch; double invocation is ignored and logged.</p>
 */
public final class NPCMindLibrary {
    private static volatile boolean bootstrapped = false;
    private static volatile boolean integrationRegistered = false;
    private static volatile boolean clientRegistered = false;

    private NPCMindLibrary() {}

    /**
     * Step 1 — register everything that must exist before registry events fire, on the
     * <b>host mod's</b> mod event bus: the companion entity type, the companion pearl
     * projectile, the companion inventory menu, entity attributes and the COMMON config spec.
     *
     * <p>Registry names stay namespaced under {@code npcmind:} so saved worlds and existing
     * integrations keep working. The config file is generated under the <b>host mod's</b> id
     * (for example {@code myhostmod-common.toml}) because the active mod container belongs to
     * the host; config keys and defaults are unchanged.</p>
     */
    public static synchronized void bootstrap(IEventBus hostModEventBus) {
        if (hostModEventBus == null) {
            throw new IllegalArgumentException("NPC Mind bootstrap requires the host mod event bus");
        }
        if (bootstrapped) {
            NPCMind.LOG.debug("NPC Mind library already bootstrapped; ignoring duplicate call.");
            return;
        }
        bootstrapped = true;
        ModEntities.ENTITY_TYPES.register(hostModEventBus);
        ModMenus.MENUS.register(hostModEventBus);
        hostModEventBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(ModEntities.COMPANION.get(), CompanionAttributes.create().build()));
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NPCMind.LOG.info("NPC Mind library {} bootstrapped: entities, menus, attributes and config registered.",
                NPCMindAPI.API_VERSION);
    }

    /**
     * Step 2 — activate the server integration on the global Forge bus: the
     * {@code /npcmind} command tree, the per-tick task/respawn drivers, optional
     * {@code !npc} chat routing and the death/cleanup bookkeeping hooks.
     * Idempotent: a second call is ignored.
     */
    public static synchronized void registerIntegration() {
        if (integrationRegistered) {
            NPCMind.LOG.debug("NPC Mind integration already registered; ignoring duplicate call.");
            return;
        }
        MinecraftForge.EVENT_BUS.register(ForgeIntegration.class);
        integrationRegistered = true;
        NPCMind.LOG.info("NPC Mind server integration registered (commands, tick drivers, chat routing).");
    }

    /** True after {@link #registerIntegration} ran at least once. */
    public static boolean isIntegrationRegistered() { return integrationRegistered; }

    /**
     * Step 3 — client-side rendering and menu screens. Call from the host mod's client setup
     * path with the host mod event bus. The call is ignored on a dedicated server; the host
     * normally guards it with {@code FMLEnvironment.dist.isClient()} (or {@link net.minecraftforge.fml.DistExecutor})
     * but the library re-checks the current dist as a safety net.
     */
    public static void registerClient(IEventBus hostModEventBus) {
        if (hostModEventBus == null) {
            throw new IllegalArgumentException("NPC Mind client registration requires the host mod event bus");
        }
        if (!FMLEnvironment.dist.isClient()) {
            NPCMind.LOG.warn("NPC Mind registerClient called on a dedicated server; ignored.");
            return;
        }
        if (clientRegistered) {
            NPCMind.LOG.debug("NPC Mind client rendering already registered; ignoring duplicate call.");
            return;
        }
        clientRegistered = true;
        // Class lives in the client package; only referenced behind the dist guard above so a
        // dedicated server never loads client-only classes.
        com.yaseen.npcmind.client.ClientRendering.register(hostModEventBus);
        NPCMind.LOG.info("NPC Mind client rendering registered.");
    }

    /** True after {@link #registerClient} ran at least once on this JVM. */
    public static boolean isClientRegistered() { return clientRegistered; }
}
