package com.agent772.parallelworlds.generation.async;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * Result of async chunk noise computation, produced by a worker thread.
 * Applied to the world on the main server thread.
 */
public record ChunkComputeResult(
        ResourceKey<Level> dimension,
        ChunkPos chunkPos,
        boolean success,
        @Nullable String error,
        @Nullable PalettedContainer<BlockState>[] sections,
        @Nullable Heightmap.Types[] heightmapTypes,
        @Nullable long[][] heightmapData,
        long computeTimeNanos
) {
    /**
     * Create a successful result.
     */
    @SuppressWarnings("unchecked")
    public static ChunkComputeResult success(
            ResourceKey<Level> dimension,
            ChunkPos chunkPos,
            PalettedContainer<BlockState>[] sections,
            Heightmap.Types[] heightmapTypes,
            long[][] heightmapData,
            long computeTimeNanos
    ) {
        return new ChunkComputeResult(dimension, chunkPos, true, null, sections, heightmapTypes, heightmapData, computeTimeNanos);
    }

    /**
     * Create a failed result.
     */
    public static ChunkComputeResult failure(ResourceKey<Level> dimension, ChunkPos chunkPos, String error, long computeTimeNanos) {
        return new ChunkComputeResult(dimension, chunkPos, false, error, null, null, null, computeTimeNanos);
    }
}
