package com.example.hostmod;

import com.yaseen.npcmind.NPCMindLibrary;
import com.yaseen.npcmind.api.NPCMindAPI;
import com.yaseen.npcmind.api.ToolPack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Minimal, complete host mod for the NPC Mind developer library (v1.2.0+).
 *
 * <p>NPC Mind itself is NOT a mod: it has no {@code @Mod} entrypoint, no {@code mods.toml}
 * and no {@code pack.mcmeta}. Your mod owns the mod identity, and this constructor is the
 * whole integration:</p>
 *
 * <ol>
 *   <li>{@link NPCMindLibrary#bootstrap} — companion entity type, pearl projectile, inventory
 *       menu, entity attributes and the COMMON config (must run before registry events, so the
 *       mod constructor is the right place).</li>
 *   <li>{@link NPCMindLibrary#registerIntegration} — {@code /npcmind} commands, the server-tick
 *       task/respawn drivers and optional {@code !npc} chat routing.</li>
 *   <li>{@link NPCMindLibrary#registerClient} — renderers and menu screens; safe to call from
 *       the common constructor because the library checks the current dist internally.</li>
 * </ol>
 *
 * <p>After bootstrap you may narrow or extend the tool surface before players interact
 * (see the commented lines): pick tool packs, disable individual tools, or register your own
 * {@code AITool}s through {@link NPCMindAPI}.</p>
 */
@Mod(ExampleHostMod.MOD_ID)
public final class ExampleHostMod {
    public static final String MOD_ID = "examplehost";

    public ExampleHostMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 1) Library registration: entity types, menus, attributes, config.
        NPCMindLibrary.bootstrap(modBus);

        // 2) Server integration: commands, tick drivers, chat routing.
        NPCMindLibrary.registerIntegration();

        // 3) Client rendering + screens (no-op on a dedicated server).
        NPCMindLibrary.registerClient(modBus);

        // ---- optional library configuration (all idempotent, all before player interaction) ----
        // Activate only a subset of the built-in tool packs:
        //   NPCMindAPI.enableOnlyPacks(ToolPack.CORE, ToolPack.BUILD);
        // Or activate individual tools only:
        //   NPCMindAPI.enableOnlyTools("gather_wood", "move_to", "inspect_ai");
        // Or register your own tool:
        //   NPCMindAPI.registerTool(new MyCustomTool());
    }
}
