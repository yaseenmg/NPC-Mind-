package com.yaseen.npcmind.memory;

import com.yaseen.npcmind.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.yaseen.npcmind.api.Internal;

@Internal
public final class MemoryStore {
    private static final Map<UUID, Deque<String>> MEMORY = new ConcurrentHashMap<>();

    private static Path directory(ServerPlayer player) {
        return player.serverLevel().getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("npcmind_memory");
    }

    public static void add(ServerPlayer player, String text) {
        if (!Config.PERSISTENT_MEMORY.get()) return;
        Deque<String> q = MEMORY.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(text);
            while (q.size() > Config.MEMORY_LIMIT.get()) q.removeFirst();
        }
    }

    public static String context(ServerPlayer player) {
        Deque<String> q = MEMORY.get(player.getUUID());
        if (q == null) return "";
        synchronized (q) {
            return String.join("\n", q);
        }
    }

    public static void load(ServerPlayer player) {
        if (!Config.PERSISTENT_MEMORY.get()) return;
        try {
            Path file = directory(player).resolve(player.getUUID() + ".dat");
            if (!Files.exists(file)) return;
            CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(file.toFile());
            ListTag list = tag.getList("memory", 8);
            Deque<String> q = new ArrayDeque<>();
            for (int i = 0; i < list.size(); i++) q.addLast(list.getString(i));
            while (q.size() > Config.MEMORY_LIMIT.get()) q.removeFirst();
            MEMORY.put(player.getUUID(), q);
        } catch (Exception failure) {
            com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] could not load persistent memory for player {}", player.getUUID(), failure);
        }
    }

    public static void save(ServerPlayer player) {
        if (!Config.PERSISTENT_MEMORY.get()) return;
        try {
            Files.createDirectories(directory(player));
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            Deque<String> q = MEMORY.get(player.getUUID());
            if (q != null) {
                synchronized (q) {
                    for (String value : q) list.add(StringTag.valueOf(value));
                }
            }
            tag.put("memory", list);
            Path target = directory(player).resolve(player.getUUID() + ".dat");
            Path temp = directory(player).resolve(player.getUUID() + ".dat.tmp");
            // Persist through an atomic file replacement so a server crash cannot leave
            // a partially written memory file.
            net.minecraft.nbt.NbtIo.writeCompressed(tag, temp.toFile());
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            com.yaseen.npcmind.NPCMind.LOG.warn("[NPCMind] could not save persistent memory for player {}", player.getUUID(), failure);
        }
    }

    /** Remove the in-memory session after persistence so long-running servers do not retain every UUID forever. */
    public static void unload(ServerPlayer player) {
        if (player != null) MEMORY.remove(player.getUUID());
    }

    private MemoryStore() {}
}
