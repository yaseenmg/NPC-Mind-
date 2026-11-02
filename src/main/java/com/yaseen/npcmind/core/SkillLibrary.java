package com.yaseen.npcmind.core;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.state.CompanionWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

/** Safe procedural skill memory. Skills are descriptions of existing tools, never arbitrary code execution. */
@Internal
public final class SkillLibrary {
    private static final Map<UUID, Map<String, Skill>> DATA = new ConcurrentHashMap<>();
    public record Skill(String name, String recipe, int successes, int failures, double score, long lastUsed) {
        public Skill(String name, String recipe, int successes, int failures) { this(name, recipe, successes, failures, score(successes, failures), System.currentTimeMillis()); }
        private static double score(int ok, int fail) { return (ok + 1.0) / (ok + fail + 2.0); }
    }

    /** Drops in-memory skill state for a companion that died or was removed (leak fix). */
    public static void clear(UUID id) { if (id != null) DATA.remove(id); }

    public static void recordSuccess(CompanionEntity c, String name, String recipe) {
        if (name == null || name.isBlank()) return;
        DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>()).compute(name, (n, old) ->
                new Skill(n, recipe == null ? "" : recipe, old == null ? 1 : old.successes() + 1, old == null ? 0 : old.failures()));
        persist(c);
    }
    public static void recordFailure(CompanionEntity c, String name, String reason) {
        if (name == null || name.isBlank()) return;
        DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>()).compute(name, (n, old) ->
                new Skill(n, old == null ? "" : old.recipe(), old == null ? 0 : old.successes(), old == null ? 1 : old.failures() + 1));
        persist(c);
    }
    public static String retrieve(CompanionEntity c, String query) {
        load(c);
        Map<String, Skill> m = DATA.get(c.getUUID());
        if (m == null || m.isEmpty()) return "none";
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        // Filter by query FIRST, then apply the top-5 cap. The old order limited before filtering,
        // so a matching skill ranked 6th by score returned "none" even though it existed.
        m.values().stream().filter(s -> q.isEmpty()
                        || s.name().toLowerCase(Locale.ROOT).contains(q)
                        || s.recipe().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparingDouble((Skill s) -> s.score()).reversed().thenComparing(Comparator.comparingLong((Skill s) -> s.lastUsed()).reversed())).limit(5).forEach(s -> {
            b.append(s.name()).append(" [ok=").append(s.successes()).append(", fail=").append(s.failures()).append("] score=").append(String.format(Locale.ROOT, "%.2f", s.score())).append(" ").append(s.recipe()).append('\n');
        });
        return b.length() == 0 ? "none" : b.toString();
    }

    private static void load(CompanionEntity c) {
        if (c == null || !(c.level() instanceof ServerLevel level)) return;
        Map<String, Skill> existing = DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>());
        if (!existing.isEmpty()) return;
        // Dedicated "tool_skills" store; persist() writes the same shape ("format" + "entries").
        // Falls back to the legacy "skills" entry for worlds saved by v1.1.12/13 so existing
        // tool statistics are not lost after the store separation.
        CompoundTag root = CompanionWorldData.get(level).getToolSkills(c.getUUID());
        if (!root.contains("entries", 9)) {
            CompoundTag legacy = CompanionWorldData.get(level).getSkills(c.getUUID());
            if (legacy.contains("entries", 9)) root = legacy;
        }
        parseInto(existing, root);
    }

    /** Shared store-format parser ("format" int + "entries" list of per-skill tags). */
    private static void parseInto(Map<String, Skill> target, CompoundTag root) {
        ListTag list = root.contains("entries", 9) ? root.getList("entries", 10) : new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String name = t.getString("name");
            if (name.isBlank()) continue;
            target.put(name, new Skill(name, t.getString("recipe"), t.getInt("successes"), t.getInt("failures"), t.getDouble("score"), t.getLong("lastUsed")));
        }
    }

    /**
     * Seed a freshly created companion from the owner-keyed death snapshot
     * ({@code CompanionWorldData.getToolSkillsSnapshot}). Respawn recreates the entity
     * with a brand-new UUID, so the per-companion store starts empty and the old
     * statistics were effectively wiped on every death. Empty snapshots are ignored so
     * first spawns stay unaffected.
     */
    public static void restoreFromOwnerSnapshot(CompanionEntity c, CompoundTag snapshot) {
        if (c == null || snapshot == null || !snapshot.contains("entries", 9)) return;
        Map<String, Skill> existing = DATA.computeIfAbsent(c.getUUID(), k -> new ConcurrentHashMap<>());
        if (!existing.isEmpty()) return;
        parseInto(existing, snapshot);
        persist(c);
    }

    private static void persist(CompanionEntity c) {
        if (c == null || !(c.level() instanceof ServerLevel level)) return;
        Map<String, Skill> m = DATA.get(c.getUUID());
        if (m == null) return;
        ListTag list = new ListTag();
        for (Skill s : m.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("name", s.name()); t.putString("recipe", s.recipe());
            t.putInt("successes", s.successes()); t.putInt("failures", s.failures());
            t.putDouble("score", s.score()); t.putLong("lastUsed", s.lastUsed());
            list.add(t);
        }
        // saveToolSkills keeps the SkillLibrary statistics in their own record key so the
        // SkillProgression death snapshot under "skills" is never overwritten by this side.
        CompoundTag root = new CompoundTag();
        root.putInt("format", 1);
        root.put("entries", list);
        CompanionWorldData.get(level).saveToolSkills(c.getUUID(), root);
    }

    private SkillLibrary() {}
}

