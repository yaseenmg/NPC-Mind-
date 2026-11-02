package com.yaseen.npcmind.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry for generic entity tools. Independent of the bundled CompanionEntity tool catalogue. */
public final class EntityToolRegistry {
    private static final Map<String, EntityAITool> TOOLS = new ConcurrentHashMap<>();
    private EntityToolRegistry() {}

    public static void register(EntityAITool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank())
            throw new IllegalArgumentException("Entity tool must have a non-blank name");
        EntityAITool previous = TOOLS.putIfAbsent(tool.name(), tool);
        if (previous != null && previous != tool)
            throw new IllegalArgumentException("Entity tool name already registered: " + tool.name());
    }

    public static EntityAITool get(String name) {
        return name == null ? null : TOOLS.get(name.trim());
    }

    public static boolean contains(String name) { return get(name) != null; }

    public static Collection<EntityAITool> all() {
        return TOOLS.values().stream().sorted(Comparator.comparing(EntityAITool::name)).toList();
    }
}
