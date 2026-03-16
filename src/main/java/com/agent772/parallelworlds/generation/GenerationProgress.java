package com.agent772.parallelworlds.generation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe progress tracker for chunk pre-generation tasks.
 * Tracks chunks processed, failure retries, timing, and status.
 */
public class GenerationProgress {

    public enum Status {
        RUNNING, PAUSED, COMPLETED, ERROR
    }

    private final AtomicLong chunksGenerated = new AtomicLong(0);
    private final AtomicLong chunksActuallyGenerated = new AtomicLong(0);
    private final ConcurrentHashMap<ChunkPos, Integer> failedChunkRetries = new ConcurrentHashMap<>();
    private volatile Status status = Status.RUNNING;
    private final SpiralIterator spiralIterator;
    private final long startTimeMs;

    public GenerationProgress(SpiralIterator spiralIterator) {
        this.spiralIterator = spiralIterator;
        this.startTimeMs = System.currentTimeMillis();
    }

    private GenerationProgress(SpiralIterator spiralIterator, long startTimeMs) {
        this.spiralIterator = spiralIterator;
        this.startTimeMs = startTimeMs;
    }

    public void incrementChunksGenerated() {
        chunksGenerated.incrementAndGet();
    }

    public void incrementChunksActuallyGenerated() {
        chunksActuallyGenerated.incrementAndGet();
    }

    public long getChunksGenerated() {
        return chunksGenerated.get();
    }

    public long getChunksActuallyGenerated() {
        return chunksActuallyGenerated.get();
    }

    public int recordFailure(ChunkPos pos) {
        return failedChunkRetries.merge(pos, 1, Integer::sum);
    }

    public int getFailureCount(ChunkPos pos) {
        return failedChunkRetries.getOrDefault(pos, 0);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public SpiralIterator getSpiralIterator() {
        return spiralIterator;
    }

    public double getProgressPercent() {
        long total = spiralIterator.getTotalChunks();
        if (total == 0) return 100.0;
        return (chunksGenerated.get() * 100.0) / total;
    }

    /**
     * @return chunks generated per minute
     */
    public double getGenerationRate() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed <= 0) return 0.0;
        return chunksGenerated.get() * 60_000.0 / elapsed;
    }

    /**
     * @return estimated minutes remaining based on current rate
     */
    public double getEstimatedMinutesRemaining() {
        double rate = getGenerationRate();
        if (rate <= 0) return -1.0;
        long remaining = spiralIterator.getTotalChunks() - chunksGenerated.get();
        if (remaining <= 0) return 0.0;
        return remaining / rate;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("chunksGenerated", chunksGenerated.get());
        tag.putLong("chunksActuallyGenerated", chunksActuallyGenerated.get());
        tag.putString("status", status.name());
        tag.put("spiralIterator", spiralIterator.toNbt());
        tag.putLong("startTimeMs", startTimeMs);

        // Failed chunks as list of compound tags
        ListTag failedTag = new ListTag();
        failedChunkRetries.forEach((pos, retries) -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.x);
            entry.putInt("z", pos.z);
            entry.putInt("retries", retries);
            failedTag.add(entry);
        });
        tag.put("failedChunks", failedTag);

        return tag;
    }

    public static GenerationProgress fromNbt(CompoundTag tag) {
        SpiralIterator iterator = SpiralIterator.fromNbt(tag.getCompound("spiralIterator"));
        long startTimeMs = tag.getLong("startTimeMs");

        GenerationProgress progress = new GenerationProgress(iterator, startTimeMs);
        progress.chunksGenerated.set(tag.getLong("chunksGenerated"));
        progress.chunksActuallyGenerated.set(tag.getLong("chunksActuallyGenerated"));

        try {
            progress.status = Status.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            progress.status = Status.PAUSED;
        }

        // Load failed chunks
        if (tag.contains("failedChunks", Tag.TAG_LIST)) {
            ListTag failedTag = tag.getList("failedChunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < failedTag.size(); i++) {
                CompoundTag entry = failedTag.getCompound(i);
                ChunkPos pos = new ChunkPos(entry.getInt("x"), entry.getInt("z"));
                progress.failedChunkRetries.put(pos, entry.getInt("retries"));
            }
        }

        return progress;
    }
}
