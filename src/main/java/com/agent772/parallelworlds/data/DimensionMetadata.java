package com.agent772.parallelworlds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks metadata for a single exploration dimension: creation time,
 * last access, visitor set, and total visit count.
 */
public final class DimensionMetadata {

    private final long createdTime;
    private long lastAccessTime;
    private final Set<UUID> accessedBy;
    private int totalVisits;

    public DimensionMetadata(long createdTime) {
        this.createdTime = createdTime;
        this.lastAccessTime = createdTime;
        this.accessedBy = new HashSet<>();
        this.totalVisits = 0;
    }

    private DimensionMetadata(long createdTime, long lastAccessTime, Set<UUID> accessedBy, int totalVisits) {
        this.createdTime = createdTime;
        this.lastAccessTime = lastAccessTime;
        this.accessedBy = accessedBy;
        this.totalVisits = totalVisits;
    }

    public void recordAccess(UUID playerId) {
        lastAccessTime = System.currentTimeMillis();
        accessedBy.add(playerId);
        totalVisits++;
    }

    public void reset() {
        lastAccessTime = System.currentTimeMillis();
        accessedBy.clear();
        totalVisits = 0;
    }

    public long getCreatedTime() { return createdTime; }
    public long getLastAccessTime() { return lastAccessTime; }
    public Set<UUID> getAccessedBy() { return Set.copyOf(accessedBy); }
    public int getTotalVisits() { return totalVisits; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("created", createdTime);
        tag.putLong("lastAccess", lastAccessTime);
        tag.putInt("totalVisits", totalVisits);
        ListTag visitors = new ListTag();
        for (UUID uuid : accessedBy) {
            visitors.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put("accessedBy", visitors);
        return tag;
    }

    public static DimensionMetadata fromNbt(CompoundTag tag) {
        long created = tag.getLong("created");
        long lastAccess = tag.getLong("lastAccess");
        int visits = tag.getInt("totalVisits");
        Set<UUID> visitors = new HashSet<>();
        ListTag visitorList = tag.getList("accessedBy", Tag.TAG_STRING);
        for (int i = 0; i < visitorList.size(); i++) {
            try {
                visitors.add(UUID.fromString(visitorList.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed UUIDs
            }
        }
        return new DimensionMetadata(created, lastAccess, visitors, visits);
    }
}
