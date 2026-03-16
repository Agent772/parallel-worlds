package com.agent772.parallelworlds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player exploration statistics: dimension visit counts and total
 * exploration time. Serialized as part of PWSavedData.
 */
public final class PlayerExplorationStats {

    private final UUID playerId;
    private final Map<ResourceLocation, Integer> dimensionVisits;
    private long totalExplorationTimeMs;
    private int chunksLoaded;

    // Transient — not serialized, used to track current visit duration
    private transient long currentVisitStart;

    public PlayerExplorationStats(UUID playerId) {
        this.playerId = playerId;
        this.dimensionVisits = new HashMap<>();
        this.totalExplorationTimeMs = 0;
        this.chunksLoaded = 0;
        this.currentVisitStart = 0;
    }

    private PlayerExplorationStats(UUID playerId, Map<ResourceLocation, Integer> dimensionVisits,
                                   long totalExplorationTimeMs, int chunksLoaded) {
        this.playerId = playerId;
        this.dimensionVisits = dimensionVisits;
        this.totalExplorationTimeMs = totalExplorationTimeMs;
        this.chunksLoaded = chunksLoaded;
        this.currentVisitStart = 0;
    }

    public void recordVisit(ResourceLocation dimension) {
        dimensionVisits.merge(dimension, 1, Integer::sum);
    }

    public void startVisit() {
        currentVisitStart = System.currentTimeMillis();
    }

    public void endVisit() {
        if (currentVisitStart > 0) {
            totalExplorationTimeMs += System.currentTimeMillis() - currentVisitStart;
            currentVisitStart = 0;
        }
    }

    public UUID getPlayerId() { return playerId; }
    public Map<ResourceLocation, Integer> getDimensionVisits() { return Map.copyOf(dimensionVisits); }
    public long getTotalExplorationTimeMs() { return totalExplorationTimeMs; }
    public int getVisitsTo(ResourceLocation dimension) { return dimensionVisits.getOrDefault(dimension, 0); }
    public int getChunksLoaded() { return chunksLoaded; }
    public void incrementChunksLoaded() { chunksLoaded++; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("playerId", playerId.toString());
        tag.putLong("totalTimeMs", totalExplorationTimeMs);
        tag.putInt("chunksLoaded", chunksLoaded);
        ListTag visitsList = new ListTag();
        dimensionVisits.forEach((dim, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", dim.toString());
            entry.putInt("count", count);
            visitsList.add(entry);
        });
        tag.put("visits", visitsList);
        return tag;
    }

    public static PlayerExplorationStats fromNbt(CompoundTag tag) {
        UUID id;
        try {
            id = UUID.fromString(tag.getString("playerId"));
        } catch (IllegalArgumentException e) {
            return null; // Skip malformed entries
        }
        long totalTime = tag.getLong("totalTimeMs");
        int chunks = tag.getInt("chunksLoaded");
        Map<ResourceLocation, Integer> visits = new HashMap<>();
        ListTag visitsList = tag.getList("visits", Tag.TAG_COMPOUND);
        for (int i = 0; i < visitsList.size(); i++) {
            CompoundTag entry = visitsList.getCompound(i);
            try {
                ResourceLocation dim = ResourceLocation.parse(entry.getString("dim"));
                int count = entry.getInt("count");
                visits.put(dim, count);
            } catch (Exception ignored) {
                // Skip malformed entries
            }
        }
        return new PlayerExplorationStats(id, visits, totalTime, chunks);
    }
}
