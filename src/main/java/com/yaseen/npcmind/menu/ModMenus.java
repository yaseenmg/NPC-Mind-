package com.yaseen.npcmind.menu;

import com.yaseen.npcmind.NPCMind;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.yaseen.npcmind.api.Internal;

/** Menu registrations for the standalone NPC Mind mod. */
@Internal
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, NPCMind.MOD_ID);

    public static final RegistryObject<MenuType<CompanionInventoryMenu>> COMPANION_INVENTORY =
            MENUS.register("companion_inventory", () -> IForgeMenuType.create(CompanionInventoryMenu::new));

    private ModMenus() {}
}
