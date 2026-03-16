package com.agent772.parallelworlds.accessor;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;

/**
 * Accessor interface for MinecraftServer mixin —
 * provides runtime dimension creation and removal.
 */
public interface IServerDimensionAccessor {

    ServerLevel pw$createRuntimeDimension(ResourceKey<Level> dimensionKey,
                                          Holder<DimensionType> dimensionType,
                                          ChunkGenerator chunkGenerator,
                                          long seed);

    void pw$removeRuntimeDimension(ResourceKey<Level> dimensionKey);

    Map<ResourceKey<Level>, ServerLevel> pw$getRuntimeLevels();

    void pw$cleanupAllRuntimeDimensions();
}
