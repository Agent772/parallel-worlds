package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.accessor.IServerDimensionAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;

/**
 * Creates exploration dimensions at server startup using runtime registry manipulation.
 * Clones chunk generators and dimension types from source dimensions to preserve
 * all modded world generation (biomes, structures, features, ores, noise settings).
 */
public final class DimensionFactory {
    private static final Logger LOGGER = LogUtils.getLogger();

    private DimensionFactory() {}

    /**
     * Create an exploration copy of the given base dimension with a custom seed.
     *
     * @return the new ServerLevel, or null on failure
     */
    public static ServerLevel createExplorationDimension(MinecraftServer server,
                                                          ResourceLocation baseDimensionLocation,
                                                          long seed) {
        try {
            String baseName = baseDimensionLocation.getPath();

            // Resolve DimensionType from the source dimension (preserves modded types)
            Holder<DimensionType> dimTypeHolder = resolveSourceDimensionType(server, baseDimensionLocation);

            // Allocate a fresh ID: always higher than last-used, with a disk-existence check
            // as a crash-proof fallback in case the counter file was not saved on previous exit.
            long dimensionId = DimensionCounter.allocateFreshId(baseDimensionLocation, server);
            ResourceKey<Level> explorationKey = ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID,
                            "pw_" + baseName + "_" + dimensionId)
            );

            return registerDimensionWithKey(server, baseDimensionLocation, explorationKey, dimTypeHolder, seed);
        } catch (Exception e) {
            LOGGER.error("Failed to create exploration dimension for {}", baseDimensionLocation, e);
            return null;
        }
    }

    /**
     * Re-register an exploration dimension using an existing key (no counter increment).
     * Used on server restart in PERSIST mode to reload a dimension whose data is still on disk.
     *
     * @return the ServerLevel, or null on failure
     */
    public static ServerLevel createExplorationDimensionWithKey(MinecraftServer server,
                                                                 ResourceLocation baseDimensionLocation,
                                                                 ResourceLocation existingExplorationKey,
                                                                 long seed) {
        try {
            Holder<DimensionType> dimTypeHolder = resolveSourceDimensionType(server, baseDimensionLocation);
            ResourceKey<Level> explorationKey = ResourceKey.create(Registries.DIMENSION, existingExplorationKey);
            return registerDimensionWithKey(server, baseDimensionLocation, explorationKey, dimTypeHolder, seed);
        } catch (Exception e) {
            LOGGER.error("Failed to re-register existing exploration dimension {} for {}",
                    existingExplorationKey, baseDimensionLocation, e);
            return null;
        }
    }

    /** Shared dimension-registration logic used by both creation paths. */
    private static ServerLevel registerDimensionWithKey(MinecraftServer server,
                                                         ResourceLocation baseDimensionLocation,
                                                         ResourceKey<Level> explorationKey,
                                                         Holder<DimensionType> dimTypeHolder,
                                                         long seed) {
        // Register seed and set ThreadLocal context BEFORE chunk generator creation
        ExplorationSeedManager.registerDimensionSeed(explorationKey, seed);
        ExplorationSeedManager.setCurrentDimension(explorationKey);

        try {
            // Clone the generator from the source dimension (preserves all modded worldgen)
            ChunkGenerator chunkGenerator = cloneChunkGenerator(server, baseDimensionLocation);
            LOGGER.info("Cloned {} generator from {} for {} with seed {}",
                    chunkGenerator.getClass().getSimpleName(), baseDimensionLocation,
                    explorationKey.location(), seed);

            // Delegate to mixin on MinecraftServer
            ServerLevel level = ((IServerDimensionAccessor) server)
                    .pw$createRuntimeDimension(explorationKey, dimTypeHolder, chunkGenerator, seed);

            if (level != null) {
                LOGGER.info("Successfully created exploration dimension: {} seed: {}",
                        explorationKey.location(), seed);
            }
            return level;
        } catch (Exception e) {
            LOGGER.error("Failed to register dimension with key {} for {}",
                    explorationKey.location(), baseDimensionLocation, e);
            return null;
        } finally {
            ExplorationSeedManager.clearCurrentDimension();
        }
    }

    // ── Clone chunk generator from source dimension ──

    private static ChunkGenerator cloneChunkGenerator(MinecraftServer server,
                                                       ResourceLocation baseDimension) {
        // 1. Try to get the source dimension's ServerLevel
        ResourceKey<Level> sourceKey = ResourceKey.create(Registries.DIMENSION, baseDimension);
        ServerLevel sourceLevel = server.getLevel(sourceKey);

        if (sourceLevel != null) {
            return sourceLevel.getChunkSource().getGenerator();
        }

        // 2. Fallback: look up the LevelStem from registry (for early-startup edge cases)
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, baseDimension);

        if (stemRegistry.containsKey(stemKey)) {
            LevelStem stem = stemRegistry.get(stemKey);
            if (stem != null) {
                return stem.generator();
            }
        }

        // 3. Last resort: log error and throw
        throw new IllegalStateException("Cannot find source dimension to clone generator: " + baseDimension);
    }

    // ── Resolve DimensionType from source dimension ──

    private static Holder<DimensionType> resolveSourceDimensionType(MinecraftServer server,
                                                                     ResourceLocation baseDimension) {
        // 1. Try LevelStem registry (contains DimensionType holders with full mod data)
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, baseDimension);

        if (stemRegistry.containsKey(stemKey)) {
            LevelStem stem = stemRegistry.get(stemKey);
            if (stem != null) {
                return stem.type();
            }
        }

        // 2. Fallback: try loading directly from DimensionType registry
        Registry<DimensionType> dimTypeRegistry = server.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<DimensionType> dimTypeKey = ResourceKey.create(Registries.DIMENSION_TYPE, baseDimension);

        if (dimTypeRegistry.containsKey(dimTypeKey)) {
            return dimTypeRegistry.getHolderOrThrow(dimTypeKey);
        }

        // 3. Last resort: overworld
        LOGGER.warn("Could not resolve DimensionType for {}, falling back to overworld", baseDimension);
        return dimTypeRegistry.getHolderOrThrow(
                ResourceKey.create(Registries.DIMENSION_TYPE,
                        ResourceLocation.withDefaultNamespace("overworld")));
    }
}
