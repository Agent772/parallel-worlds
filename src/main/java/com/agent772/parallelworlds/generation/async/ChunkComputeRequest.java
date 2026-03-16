package com.agent772.parallelworlds.generation.async;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Immutable request for async chunk noise computation.
 * Contains all data a worker needs — no live references to ServerLevel.
 * All referenced objects are immutable/frozen after server startup and safe to read from worker threads.
 */
public record ChunkComputeRequest(
        ResourceKey<Level> dimension,
        ChunkPos chunkPos,
        long seed,
        RegistryAccess registryAccess,
        NoiseGeneratorSettings noiseSettings,
        RandomState randomState,
        int minBuildHeight,
        int buildHeight
) {
    /**
     * Create a request from a ServerLevel. Must be called on the main thread.
     * Returns null if the level's generator is not noise-based.
     */
    public static ChunkComputeRequest fromLevel(ServerLevel level, ChunkPos chunkPos) {
        var generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            return null;
        }

        return new ChunkComputeRequest(
                level.dimension(),
                chunkPos,
                level.getSeed(),
                level.registryAccess(),
                noiseGen.generatorSettings().value(),
                level.getChunkSource().randomState(),
                level.dimensionType().minY(),
                level.dimensionType().height()
        );
    }
}
