package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;
import com.yaseen.npcmind.api.Internal;

/**
 * World-persistent lifecycle data for companions. Unlike Player persistent NBT,
 * this survives the owner being offline or dead when the companion dies.
 */
@Internal
public final class CompanionWorldData extends SavedData {
    private static final String DATA_NAME = "npcmind_companion_lifecycle";
    private static final String RECORDS = "records";
    private final CompoundTag records = new CompoundTag();

    public CompanionWorldData() {}

    public static CompanionWorldData load(CompoundTag tag) {
        CompanionWorldData data = new CompanionWorldData();
        if (tag.contains(RECORDS, 10)) data.records.merge(tag.getCompound(RECORDS));
        return data;
    }

    public static CompanionWorldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CompanionWorldData::load, CompanionWorldData::new, DATA_NAME);
    }

    private CompoundTag record(UUID owner) {
        String key = owner.toString();
        if (!records.contains(key, 10)) records.put(key, new CompoundTag());
        return records.getCompound(key);
    }

    public void saveRespawnPoint(UUID owner, ResourceLocation dimension, long x, long y, long z) {
        CompoundTag r = record(owner);
        r.putString("dimension", dimension.toString());
        r.putLong("x", x); r.putLong("y", y); r.putLong("z", z);
        setDirty();
    }

    public void markPending(UUID owner) { markPending(owner, 0L); }

    public void markPending(UUID owner, long delayMillis) {
        CompoundTag r = record(owner);
        r.putBoolean("pending", true);
        r.putLong("respawnAt", System.currentTimeMillis() + Math.max(0L, delayMillis));
        setDirty();
    }

    public boolean isPending(UUID owner) {
        return owner != null && records.contains(owner.toString(), 10)
                && records.getCompound(owner.toString()).getBoolean("pending");
    }

    public boolean isRespawnReady(UUID owner) {
        if (!isPending(owner)) return false;
        return System.currentTimeMillis() >= records.getCompound(owner.toString()).getLong("respawnAt");
    }

    public CompoundTag getRecordCopy(UUID owner) {
        if (!records.contains(owner.toString(), 10)) return new CompoundTag();
        return records.getCompound(owner.toString()).copy();
    }

    /**
     * SkillProgression snapshot store (key "skills" inside the owner record). Written by
     * {@code CompanionEntity#die} and read by {@code CompanionRespawnController}.
     */
    public void saveSkills(UUID owner, CompoundTag skills) {
        saveSkills(owner, null, skills);
    }

    /**
     * Per-companion variant. Two companions owned by the same player previously shared one
     * owner-keyed {@code skills} tag, so the second one to die overwrote the first one's
     * progression. Snapshots are now namespaced by the dying companion's UUID while the
     * legacy owner-keyed tag is still written as a fallback for older saves.
     */
    public void saveSkills(UUID owner, UUID companionId, CompoundTag skills) {
        if (owner == null || skills == null) return;
        CompoundTag r = record(owner);
        if (companionId == null) {
            r.put("skills", skills.copy());
        } else {
            CompoundTag by = r.contains("skills_by_companion", 10)
                    ? r.getCompound("skills_by_companion") : new CompoundTag();
            by.put(companionId.toString(), skills.copy());
            r.put("skills_by_companion", by);
            r.put("skills", skills.copy());
        }
        setDirty();
    }

    public CompoundTag getSkills(UUID owner) {
        return getSkills(owner, null);
    }

    public CompoundTag getSkills(UUID owner, UUID companionId) {
        if (owner == null || !records.contains(owner.toString(), 10)) return new CompoundTag();
        CompoundTag r = records.getCompound(owner.toString());
        if (companionId != null && r.contains("skills_by_companion", 10)) {
            CompoundTag by = r.getCompound("skills_by_companion");
            if (by.contains(companionId.toString(), 10)) return by.getCompound(companionId.toString()).copy();
        }
        return r.contains("skills", 10) ? r.getCompound("skills").copy() : new CompoundTag();
    }

    /** UUID of the companion whose death produced the pending respawn snapshot. */
    public void saveDeadCompanionId(UUID owner, UUID companionId) {
        if (owner == null || companionId == null) return;
        record(owner).putUUID("dead_companion", companionId);
        setDirty();
    }

    public UUID getDeadCompanionId(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return null;
        CompoundTag r = records.getCompound(owner.toString());
        return r.hasUUID("dead_companion") ? r.getUUID("dead_companion") : null;
    }

    /**
     * Dedicated store for the procedural {@code SkillLibrary} tool statistics (key
     * "tool_skills"). It previously shared the "skills" key with the SkillProgression
     * snapshot above — two different formats under one key, each overwrite destroying the
     * other system's data (the death snapshot was lost on the next recorded craft/mine).
     */
    public void saveToolSkills(UUID owner, CompoundTag toolSkills) {
        if (owner == null || toolSkills == null) return;
        record(owner).put("tool_skills", toolSkills.copy());
        setDirty();
    }

    public CompoundTag getToolSkills(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return new CompoundTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("tool_skills", 10) ? r.getCompound("tool_skills").copy() : new CompoundTag();
    }

    /**
     * Death snapshot of the tool-skills store. {@link #saveToolSkills} is keyed by the
     * companion's own UUID, which changes on every respawn — so the statistics were lost
     * even though they persist perfectly across world reloads. At death the dying
     * companion's store is mirrored into its owner's record; respawn seeds the new
     * companion from this snapshot.
     */
    public void saveToolSkillsSnapshot(UUID owner, CompoundTag toolSkills) {
        if (owner == null || toolSkills == null) return;
        record(owner).put("tool_skills_snapshot", toolSkills.copy());
        setDirty();
    }

    public CompoundTag getToolSkillsSnapshot(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return new CompoundTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("tool_skills_snapshot", 10) ? r.getCompound("tool_skills_snapshot").copy() : new CompoundTag();
    }


    public void saveExperience(UUID owner, int level, float progress, int total) {
        if (owner == null) return;
        CompoundTag xp = new CompoundTag();
        xp.putInt("level", Math.max(0, Math.min(21862, level)));
        xp.putFloat("progress", Float.isFinite(progress) ? Math.max(0.0F, Math.min(0.999999F, progress)) : 0.0F);
        xp.putInt("total", Math.max(0, total));
        record(owner).put("experience", xp);
        setDirty();
    }

    public CompoundTag getExperience(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return new CompoundTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("experience", 10) ? r.getCompound("experience").copy() : new CompoundTag();
    }

    /** Custom companion name persisted at death so respawn does not reset it to "Steve". */
    public void saveCustomName(UUID owner, String name) {
        if (owner == null) return;
        record(owner).putString("name", name == null ? "" : name);
        setDirty();
    }

    public String customName(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return "";
        return records.getCompound(owner.toString()).getString("name");
    }

    /** Permission snapshot persisted at death and restored on respawn. */
    public void savePermissionSnapshot(UUID owner, CompoundTag permissions) {
        if (owner == null || permissions == null) return;
        record(owner).put("permissions", permissions.copy());
        setDirty();
    }

    public CompoundTag getPermissionSnapshot(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return new CompoundTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("permissions", 10) ? r.getCompound("permissions").copy() : new CompoundTag();
    }

    public void saveInventory(UUID owner, ListTag inventory) {
        record(owner).put("inventory", inventory.copy());
        setDirty();
    }

    public void clearInventory(UUID owner) {
        if (owner == null) return;
        CompoundTag r = record(owner);
        r.remove("inventory");
        setDirty();
    }

    public ListTag getInventory(UUID owner) {
        if (!records.contains(owner.toString(), 10)) return new ListTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("inventory", 9) ? r.getList("inventory", 10).copy() : new ListTag();
    }

    public ResourceLocation dimension(UUID owner) {
        CompoundTag r = getRecordCopy(owner);
        String id = r.getString("dimension");
        return id.isBlank() ? null : ResourceLocation.tryParse(id);
    }

    public long x(UUID owner) { return getRecordCopy(owner).getLong("x"); }
    public long y(UUID owner) { return getRecordCopy(owner).getLong("y"); }
    public long z(UUID owner) { return getRecordCopy(owner).getLong("z"); }

    public void clearPending(UUID owner) {
        if (owner == null || !records.contains(owner.toString(), 10)) return;
        // Do not remove the entire owner record: skill progress and inventory data may share it.
        CompoundTag r = records.getCompound(owner.toString());
        r.remove("pending");
        r.remove("respawnAt");
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put(RECORDS, records.copy());
        return tag;
    }
}
