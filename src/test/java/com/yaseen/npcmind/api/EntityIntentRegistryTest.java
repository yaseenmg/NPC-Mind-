package com.yaseen.npcmind.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityIntentRegistryTest {
    @Test
    void hostCanRegisterGenericToolAndResolveEnglishAndArabicAliases() {
        String toolName = "host:move_" + UUID.randomUUID().toString().replace("-", "");
        NPCMindAPI.registerEntityTool(new EntityAITool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "Move the host NPC"; }
            @Override public String schema() { return "target: string"; }
            @Override public String execute(net.minecraft.world.entity.LivingEntity actor,
                                            net.minecraft.server.level.ServerPlayer controller,
                                            String arguments) { return "SUCCESS"; }
        });
        NPCMindAPI.registerEntityIntent(new String[]{"move to base", "اذهب إلى القاعدة"},
                toolName, "{\"target\":\"home\"}", "host test route");

        var english = NPCMindAPI.resolveEntityCommand("Please, MOVE to base!").orElseThrow();
        assertEquals(toolName, english.tool());
        assertEquals("{\"target\":\"home\"}", english.arguments());
        assertEquals("host test route", english.reason());

        var arabic = NPCMindAPI.resolveEntityCommand("اذهب إلى القاعدة الآن").orElseThrow();
        assertEquals(toolName, arabic.tool());
        assertTrue(NPCMindAPI.resolveEntityCommand("unrelated request").isEmpty());
        assertTrue(NPCMindAPI.resolveEntityCommand(null).isEmpty());
    }

    @Test
    void entityIntentRegistrationRejectsInvalidArgumentsAndUnknownTools() {
        assertThrows(IllegalArgumentException.class, () -> NPCMindAPI.registerEntityIntent(
                new String[]{"invalid host command"}, "npcmind_test_missing_tool", "{}", "test"));

        String toolName = "host:required_" + UUID.randomUUID().toString().replace("-", "");
        NPCMindAPI.registerEntityTool(new EntityAITool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "Requires a target"; }
            @Override public String schema() { return "target: string"; }
            @Override public String execute(net.minecraft.world.entity.LivingEntity actor,
                                            net.minecraft.server.level.ServerPlayer controller,
                                            String arguments) { return "SUCCESS"; }
        });
        assertThrows(IllegalArgumentException.class, () -> NPCMindAPI.registerEntityIntent(
                new String[]{"invalid required args"}, toolName, "{}", "test"));
    }
}
