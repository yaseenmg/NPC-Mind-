package com.yaseen.npcmind.api;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API surface compatibility gate ({@code docs/API_STABILITY.md}).
 *
 * <p>The stable surface is {@code com.yaseen.npcmind.api}, the stable tool contract
 * ({@code AITool}, {@code ToolContract}, {@code JsonArgs}) and the {@code NPCMindLibrary}
 * bootstrap facade. This test fails the build when a stable method is removed or the
 * published version drifts, so an accidental breaking change cannot be released silently.</p>
 *
 * <p><b>Deliberately bootstrap-free:</b> the test never invokes Minecraft-touching code
 * (constructing built-in tools loads Minecraft registry classes), so it runs in a bare JUnit
 * JVM. Method presence is asserted by reflection over declared names; the version is read
 * through the reflected field so the expected literal cannot be erased by compile-time
 * constant inlining.</p>
 */
class ApiCompatibilityTest {
    /** Keep in sync with gradle.properties (mod_version) and NPCMindAPI.API_VERSION. */
    private static final String EXPECTED_API_VERSION = "1.3.1";

    @Test
    void apiVersionMatchesBuildVersion() {
        Class<?> api = assertDoesNotThrow(
                () -> Class.forName("com.yaseen.npcmind.api.NPCMindAPI"),
                "NPCMindAPI is part of the stable surface and must exist");
        Object version = assertDoesNotThrow(() -> api.getField("API_VERSION").get(null));
        assertEquals(EXPECTED_API_VERSION, version,
                "API version drifted from gradle.properties mod_version — bump both together "
                + "and record the change in CHANGELOG.md");
    }

    @Test
    void stableApiMethodNamesExist() throws Exception {
        Class<?> api = Class.forName("com.yaseen.npcmind.api.NPCMindAPI", false, getClass().getClassLoader());
        Set<String> names = Arrays.stream(api.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        List<String> required = List.of(
                "registerTool", "enableOnlyPacks", "enableOnlyTools", "setToolEnabled",
                "enabledToolNames", "registerEntityTool", "registerEntityIntent", "resolveEntityCommand",
                "executeEntityTool", "submitEntityCommand", "entityToolNames",
                "registerIntent", "canReachPoint", "canReachEntity", "experience", "grantExperience",
                "playerLikeState", "tickPlayerLikeState", "addPlayerLikeExhaustion",
                "setPlayerLikeFoodLevel", "consumePlayerLikeFood", "tools", "enabledTools",
                "resolveCommand", "isBusy", "submitCommand");
        List<String> missing = required.stream().filter(name -> !names.contains(name)).toList();
        assertTrue(missing.isEmpty(),
                () -> "Stable API methods removed without a MAJOR version bump: " + missing);
    }

    @Test
    void stableClassesExist() {
        List<String> stableClasses = List.of(
                "com.yaseen.npcmind.api.NPCMindAPI",
                "com.yaseen.npcmind.api.EntityAITool",
                "com.yaseen.npcmind.api.EntityToolRegistry",
                "com.yaseen.npcmind.api.NPCMindActions",
                "com.yaseen.npcmind.api.PlayerLikeExperience",
                "com.yaseen.npcmind.api.PlayerLikeState",
                "com.yaseen.npcmind.api.ToolPack",
                "com.yaseen.npcmind.api.Internal",
                "com.yaseen.npcmind.NPCMindLibrary",
                "com.yaseen.npcmind.tool.AITool",
                "com.yaseen.npcmind.tool.ToolContract",
                "com.yaseen.npcmind.tool.JsonArgs");
        for (String name : stableClasses) {
            assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()),
                    () -> "Stable API class missing: " + name);
        }
    }

    @Test
    void internalMarkerUsesClassRetention() {
        Retention retention = Internal.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.CLASS, retention != null ? retention.value() : null,
                "@Internal must stay CLASS-retained: recorded for IDEs/bytecode tools, "
                + "no runtime reflection cost");
    }
}
