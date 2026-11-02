package com.yaseen.npcmind.tool.impl;

import net.minecraft.world.phys.Vec3;
import com.mojang.datafixers.util.Pair;
import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.advanced.StructureTravelController;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import com.yaseen.npcmind.api.Internal;

/**
 * Deterministic structure locator for Forge 1.20.1.
 * Uses the LIVE server Structure registry + ChunkGenerator locator, not a hard-coded coordinate list.
 * This covers vanilla structures and also works with datapack/modded structures when the caller
 * supplies a registered structure id.
 */
@Internal
public final class StructureSearchTool implements AITool {
    private record Spec(String id, String arabic, net.minecraft.resources.ResourceKey<Structure> key) {}

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    private static final Map<String, List<net.minecraft.resources.ResourceKey<Structure>>> VARIANTS = new HashMap<>();
    // Per-companion last result. Written from the planner worker thread and read from the server
    // thread, so it must be concurrent (it used to be a plain HashMap - a data race).
    private static final Map<UUID, BlockPos> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private record PendingDiscovery(String id, int radius) {}
    private static final Map<UUID, PendingDiscovery> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        add("village", "قرية", BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT,
                BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA);
        add("stronghold", "حصن", BuiltinStructures.STRONGHOLD);
        add("mineshaft", "منجم", BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA);
        add("ancient_city", "مدينة قديمة", BuiltinStructures.ANCIENT_CITY);
        add("fortress", "حصن النيثر", BuiltinStructures.FORTRESS);
        add("bastion", "باستيون", BuiltinStructures.BASTION_REMNANT);
        add("end_city", "مدينة الإند", BuiltinStructures.END_CITY);
        add("mansion", "قصر الغابة", BuiltinStructures.WOODLAND_MANSION);
        add("monument", "نصب المحيط", BuiltinStructures.OCEAN_MONUMENT);
        add("jungle_temple", "معبد الأدغال", BuiltinStructures.JUNGLE_TEMPLE);
        add("desert_pyramid", "هرم الصحراء", BuiltinStructures.DESERT_PYRAMID);
        add("igloo", "كوخ الثلج", BuiltinStructures.IGLOO);
        add("swamp_hut", "كوخ المستنقع", BuiltinStructures.SWAMP_HUT);
        add("outpost", "مخفر القراصنة", BuiltinStructures.PILLAGER_OUTPOST);
        add("shipwreck", "حطام سفينة", BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED);
        add("ocean_ruin", "أطلال محيطية", BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM);
        add("ruined_portal", "بوابة مهدمة", BuiltinStructures.RUINED_PORTAL_STANDARD, BuiltinStructures.RUINED_PORTAL_DESERT,
                BuiltinStructures.RUINED_PORTAL_JUNGLE, BuiltinStructures.RUINED_PORTAL_SWAMP,
                BuiltinStructures.RUINED_PORTAL_MOUNTAIN, BuiltinStructures.RUINED_PORTAL_OCEAN,
                BuiltinStructures.RUINED_PORTAL_NETHER);
        add("trail_ruins", "أطلال الدرب", BuiltinStructures.TRAIL_RUINS);
        add("buried_treasure", "كنز مدفون", BuiltinStructures.BURIED_TREASURE);
        add("nether_fossil", "أحفورة نيثر", BuiltinStructures.NETHER_FOSSIL);
    }

    private static void add(String id, String ar, net.minecraft.resources.ResourceKey<Structure>... keys) {
        // Multiple concrete vanilla variants are searched as one logical target.
        for (int i = 0; i < keys.length; i++) SPECS.putIfAbsent(id, new Spec(id, ar, keys[i]));
        // Variant keys are retained through a separate list below.
        VARIANTS.put(id, Arrays.asList(keys));
    }
    public String name() { return "search_structure"; }
    public String description() {
        return "Find a real generated Minecraft structure (village, dungeon/mineshaft, fortress, stronghold, ancient city, temple, monument, mansion, shipwreck, portal, etc.) using the live server structure registry, remember it, and navigate the companion there.";
    }
    public String schema() {
        return "{\"structure\":\"village|stronghold|mineshaft|fortress|bastion|end_city|ancient_city|mansion|monument|temple|jungle_temple|desert_pyramid|igloo|swamp_hut|shipwreck|ocean_ruin|ruined_portal|outpost|trail_ruins|buried_treasure|nether_fossil|dungeon|all\",\"radius\":number(optional),\"go\":boolean(optional)}";
    }

    public String execute(CompanionEntity c, ServerPlayer owner, String args) {
        String raw = JsonArgs.string(args, "structure");
        if (raw == null || raw.isBlank()) raw = "all";
        String id = normalize(raw);
        if ("trial_chambers".equals(id) || "trial".equals(id)) {
            return "FAILED unsupported_structure=trial_chambers; Minecraft 1.20.1 does not register Trial Chambers (added in 1.21).";
        }
        int radius = JsonArgs.integer(args, "radius", 10000);
        radius = Math.max(16, Math.min(radius, 10000));
        boolean go = !args.contains("\"go\":false") && !args.contains("\"go\" : false");
        if (!(c.level() instanceof ServerLevel level)) return "FAILED not_server_level";

        // Player-like discovery: chat commands may only use structures that are already
        // generated in loaded chunks. Do not reveal future structure coordinates from the
        // world seed just because the companion was asked to "find" one. Quest directors
        // intentionally use the separate static find(...) method below when they need
        // deterministic world-generation planning.
        Pair<BlockPos, Holder<Structure>> found = locateLoaded(level, c.blockPosition(), id, radius);
        if (found == null) {
            PENDING.put(c.getUUID(), new PendingDiscovery(id, radius));
            com.yaseen.npcmind.advanced.ExplorationController.set(c, true);
            return "STARTED structure_discovery structure=" + id + " radius=" + radius
                    + " mode=loaded_chunks_only; exploring until it loads.";
        }

        BlockPos pos = found.getFirst();
        String actual = found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse(id);
        LAST.put(c.getUUID(), pos);
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), pos,
                "found=" + actual + " requested=" + id, level.getGameTime());

        String move = go ? StructureTravelController.start(c, pos, id) : "RESULT no_navigation";
        return "SUCCESS structure=" + id + " actual=" + actual + " x=" + pos.getX() + " y=" + pos.getY() + " z=" + pos.getZ()
                + " distance=" + Math.round(Math.sqrt(c.position().distanceToSqr(Vec3.atCenterOf(pos)))) + " | " + move;
    }

    /** Called from the authoritative server tick. This is the player-like discovery loop:
     * exploration loads terrain naturally; only then can a generated StructureStart be recognized. */
    public static void pollDiscovery(CompanionEntity c) {
        if ((c.level().getGameTime() % 20L) != 0L) return;
        PendingDiscovery pending = PENDING.get(c.getUUID());
        if (pending == null || !(c.level() instanceof ServerLevel level)) return;
        Pair<BlockPos, Holder<Structure>> found = locateLoaded(level, c.blockPosition(), pending.id(), pending.radius());
        if (found == null) return;
        PENDING.remove(c.getUUID());
        com.yaseen.npcmind.advanced.ExplorationController.set(c, false);
        BlockPos pos = found.getFirst();
        LAST.put(c.getUUID(), pos);
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), pos,
                "discovered_by_exploration=" + found.getSecond().unwrapKey().map(k -> k.location().toString()).orElse(pending.id()), level.getGameTime());
        String move = StructureTravelController.start(c, pos, pending.id());
        // Speak in player language, not in internal result codes ("STARTED structure_target=...").
        int distanceBlocks = (int) Math.round(Math.sqrt(c.position().distanceToSqr(Vec3.atCenterOf(pos))));
        String message = "Found " + pending.id() + " at " + distanceBlocks + " blocks."
                + (move != null && move.startsWith("STARTED") ? " Heading there now." : "");
        c.say(message);
    }

    private static Pair<BlockPos, Holder<Structure>> locateLoaded(ServerLevel level, BlockPos origin, String id, int radius) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<net.minecraft.resources.ResourceKey<Structure>> keys = VARIANTS.get(id);
        if (keys == null && "dungeon".equals(id)) keys = VARIANTS.get("mineshaft");
        if (keys == null && "temple".equals(id)) keys = List.of(BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.JUNGLE_TEMPLE, BuiltinStructures.IGLOO, BuiltinStructures.SWAMP_HUT);
        if (keys == null && "all".equals(id)) {
            keys = new ArrayList<>();
            for (List<net.minecraft.resources.ResourceKey<Structure>> v : VARIANTS.values()) keys.addAll(v);
        }
        if (keys == null) return null;

        int chunkRadius = Math.max(1, Math.min(8, (radius + 15) >> 4));
        int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
        Pair<BlockPos, Holder<Structure>> best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int cx = ocx + dx, cz = ocz + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var key : keys) {
                    Optional<Holder.Reference<Structure>> holder = registry.getHolder(key);
                    if (holder.isEmpty()) continue;
                    var start = chunk.getStartForStructure(holder.get().value());
                    if (start == null || !start.isValid()) continue;
                    var box = start.getBoundingBox();
                    BlockPos center = box.getCenter();
                    BlockPos pos = center;
                    double d = pos.distSqr(origin);
                    if (d <= (double) radius * radius && d < bestD) {
                        bestD = d;
                        best = Pair.of(pos, holder.get());
                    }
                }
            }
        }
        return best;
    }

    /** Used by deterministic quest directors; no external model or service involved. */
    public static BlockPos find(CompanionEntity c, String structure, int radius) {
        if (!(c.level() instanceof ServerLevel level)) return null;
        String id = normalize(structure);
        // Cap the radius so the seed-based generator search cannot scan (and reveal) terrain
        // far beyond what the player has explored. Prefer structure starts that already sit
        // in loaded chunks, then fall back to the generator within the same 128-block cap.
        int clamped = Math.max(16, Math.min(radius, 128));
        Pair<BlockPos, Holder<Structure>> p = locateLoaded(level, c.blockPosition(), id, clamped);
        if (p == null) p = locate(level, c.blockPosition(), id, clamped);
        if (p == null) return null;
        LAST.put(c.getUUID(), p.getFirst());
        c.getWorldMemory().observe("structure", level.dimension().location().toString(), p.getFirst(),
                "quest_search=" + structure + " actual=" + p.getSecond().unwrapKey().map(k -> k.location().toString()).orElse("unknown"), level.getGameTime());
        return p.getFirst();
    }

    public static BlockPos last(CompanionEntity c) { return LAST.get(c.getUUID()); }

    /** Drops per-companion search state (leak fix for dead/removed companions). */
    public static void clear(UUID id) {
        if (id == null) return;
        LAST.remove(id);
        PENDING.remove(id);
    }

    private static Pair<BlockPos, Holder<Structure>> locate(ServerLevel level, BlockPos origin, String id, int radius) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<net.minecraft.resources.ResourceKey<Structure>> keys = VARIANTS.get(id);
        if (keys == null && id.contains(":")) {
            try {
                net.minecraft.resources.ResourceLocation rl = new net.minecraft.resources.ResourceLocation(id);
                Optional<Holder.Reference<Structure>> h = registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, rl));
                if (h.isPresent()) keys = List.of(h.get().unwrapKey().orElseThrow());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Invalid/absent registry identifiers are a normal lookup miss.
            } catch (RuntimeException ex) {
                com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] structure registry lookup failed for {}", id, ex);
            }
        }
        if (keys == null && "dungeon".equals(id)) keys = VARIANTS.get("mineshaft");
        if (keys == null && "temple".equals(id)) keys = List.of(BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.JUNGLE_TEMPLE, BuiltinStructures.IGLOO, BuiltinStructures.SWAMP_HUT);
        if (keys == null && "all".equals(id)) {
            keys = new ArrayList<>();
            for (List<net.minecraft.resources.ResourceKey<Structure>> v : VARIANTS.values()) keys.addAll(v);
            // Also include any additional structures supplied by datapacks/mods.
            for (var rl : registry.keySet()) {
                var k = net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, rl);
                if (!keys.contains(k)) keys.add(k);
            }
        }
        if (keys == null) return null;

        Pair<BlockPos, Holder<Structure>> best = null;
        double bestD = Double.MAX_VALUE;
        for (var key : keys) {
            Optional<Holder.Reference<Structure>> holder = registry.getHolder(key);
            if (holder.isEmpty()) continue;
            HolderSet<Structure> set = HolderSet.direct(holder.get());
            Pair<BlockPos, Holder<Structure>> p;
            try {
                p = level.getChunkSource().getGenerator().findNearestMapStructure(level, set, origin, radius, false);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                continue;
            } catch (RuntimeException ex) {
                com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] structure search failed for {}", key.location(), ex);
                continue;
            }
            if (p == null) continue;
            double d = p.getFirst().distSqr(origin);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static String normalize(String s) {
        String x = s.toLowerCase(Locale.ROOT).trim();
        // Strip filler words the player may prepend, then normalise separators.
        x = x.replaceAll("\\b(find|search|locate|look for|nearest|a|an|the)\\b", " ");
        x = x.replaceAll("[^a-z0-9_ ]", " ").trim().replace(' ', '_').replaceAll("_+", "_");
        return switch (x) {
            case "village", "villages" -> "village";
            case "stronghold" -> "stronghold";
            case "mineshaft", "mine", "dungeon" -> "mineshaft";
            case "nether_fortress", "fortress" -> "fortress";
            case "bastion" -> "bastion";
            case "ancient_city" -> "ancient_city";
            case "end_city" -> "end_city";
            case "mansion", "woodland_mansion" -> "mansion";
            case "monument", "ocean_monument" -> "monument";
            case "jungle_temple", "jungle temple", "jungle_pyramid" -> "jungle_temple";
            // "temple" keeps its own id so locateLoaded/locate can search all four temple
            // variants. Mapping it to desert_pyramid made the declared "temple" schema value
            // collapse to one branch and left the dedicated temple branch unreachable.
            case "desert_temple", "desert temple", "desert_pyramid", "pyramid" -> "desert_pyramid";
            case "temple" -> "temple";
            case "igloo" -> "igloo";
            case "swamp_hut", "witch_hut" -> "swamp_hut";
            case "ocean_ruin", "ruins" -> "ocean_ruin";
            case "shipwreck" -> "shipwreck";
            case "ruined_portal", "portal" -> "ruined_portal";
            case "outpost", "pillager_outpost" -> "outpost";
            case "trail_ruins" -> "trail_ruins";
            case "buried_treasure", "treasure" -> "buried_treasure";
            case "nether_fossil" -> "nether_fossil";
            case "all", "structures" -> "all";
            default -> x;
        };
    }
}
