package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.agent772.parallelworlds.data.DimensionMetadata;
import com.agent772.parallelworlds.data.PWSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that orchestrates creation of all configured exploration dimensions
 * at server startup and tracks them at runtime.
 */
public final class DimensionRegistrar {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static DimensionRegistrar INSTANCE;

    private final Map<ResourceKey<Level>, ServerLevel> runtimeDimensions = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, ResourceKey<Level>> dimensionMappings = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, Long> dimensionSeeds = new ConcurrentHashMap<>();

    private DimensionRegistrar() {}

    public static void initialize() {
        INSTANCE = new DimensionRegistrar();
    }

    public static DimensionRegistrar getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("DimensionRegistrar not initialized");
        }
        return INSTANCE;
    }

    // ── Startup logic ──

    public void createDimensionsOnServerStart(MinecraftServer server) {
        LOGGER.info("Creating exploration dimensions...");
        List<String> enabled = PWConfig.getEnabledDimensions();
        PWSavedData savedData = PWSavedData.get(server);
        boolean isPersistMode = PWConfig.getPersistenceMode() == PWConfigSpec.PersistenceMode.PERSIST_UNTIL_ROTATION;

        // Determine if seed rotation is due
        boolean rotationDue = SeedManager.isRotationDue(savedData.getLastResetEpochSecond());
        if (rotationDue && PWConfig.isSeedRotationEnabled()) {
            LOGGER.info("Seed rotation is due — generating new seeds for all dimensions");
            savedData.clearAllSeeds();
            savedData.setLastResetEpochSecond(SeedManager.currentEpochSecond());
        }

        for (String baseDimStr : enabled) {
            try {
                ResourceLocation baseDim = ResourceLocation.parse(baseDimStr);

                // Decide seed: reuse saved or generate new
                long seed;
                Optional<Long> savedSeed = savedData.getSavedSeed(baseDim);

                if (isPersistMode && savedSeed.isPresent() && !rotationDue) {
                    // Persist mode + saved seed exists + no rotation due → reuse
                    seed = savedSeed.get();
                    LOGGER.info("Reusing saved seed {} for {}", seed, baseDim);
                } else {
                    // Generate new seed (fresh start, rotation, or REGENERATE_EACH_RESTART)
                    seed = SeedManager.generateSeed();
                    LOGGER.info("Generated new seed {} for {}", seed, baseDim);
                }

                ServerLevel level = DimensionFactory.createExplorationDimension(server, baseDim, seed);
                if (level != null) {
                    ResourceKey<Level> explorationKey = level.dimension();
                    runtimeDimensions.put(explorationKey, level);
                    dimensionMappings.put(baseDim, explorationKey);
                    dimensionSeeds.put(explorationKey, seed);

                    // Persist seed and metadata
                    savedData.saveSeed(baseDim, seed);
                    DimensionMetadata meta = savedData.getDimensionMetadata(explorationKey.location())
                            .orElse(null);
                    if (meta == null || rotationDue) {
                        // New metadata for fresh or rotated dimensions
                        savedData.recordDimensionAccess(explorationKey.location(), null);
                    }

                    LOGGER.info("Created exploration dimension {} (seed {})", explorationKey.location(), seed);
                } else {
                    LOGGER.error("Failed to create exploration dimension for {}", baseDim);
                }
            } catch (Exception e) {
                LOGGER.error("Error creating exploration dimension for: {}", baseDimStr, e);
            }
        }
        LOGGER.info("Finished creating {} exploration dimensions", runtimeDimensions.size());
    }

    // ── Lookups ──

    public Map<ResourceKey<Level>, ServerLevel> getRuntimeDimensions() {
        return Collections.unmodifiableMap(runtimeDimensions);
    }

    public Optional<Long> getDimensionSeed(ResourceKey<Level> key) {
        return Optional.ofNullable(dimensionSeeds.get(key));
    }

    public Optional<ResourceKey<Level>> getExplorationKey(ResourceLocation baseDim) {
        return Optional.ofNullable(dimensionMappings.get(baseDim));
    }

    public Optional<ResourceKey<Level>> getExplorationKey(ResourceKey<Level> baseDim) {
        return Optional.ofNullable(dimensionMappings.get(baseDim.location()));
    }

    public Optional<ServerLevel> getExplorationLevel(ResourceKey<Level> baseDim) {
        return getExplorationKey(baseDim).map(runtimeDimensions::get);
    }

    public boolean isExplorationDimension(ResourceKey<Level> key) {
        return dimensionMappings.containsValue(key);
    }

    /** base → exploration key map snapshot */
    public Map<ResourceKey<Level>, ResourceKey<Level>> getRegisteredDimensions() {
        Map<ResourceKey<Level>, ResourceKey<Level>> result = new HashMap<>();
        dimensionMappings.forEach((baseLoc, exploreKey) -> {
            ResourceKey<Level> baseKey = ResourceKey.create(Registries.DIMENSION, baseLoc);
            result.put(baseKey, exploreKey);
        });
        return Collections.unmodifiableMap(result);
    }

    public List<String> getDimensionInfo() {
        List<String> info = new ArrayList<>();
        dimensionMappings.forEach((baseLoc, exploreKey) ->
                info.add(baseLoc + " -> " + exploreKey.location()));
        return info;
    }

    /**
     * Look up an exploration ServerLevel directly by its exploration ResourceLocation.
     * For example, given "parallelworlds:pw_overworld_0", returns the ServerLevel.
     */
    public Optional<ServerLevel> getExplorationLevelByExplorationId(ResourceLocation explorationId) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, explorationId);
        return Optional.ofNullable(runtimeDimensions.get(key));
    }

    /**
     * Returns all active exploration dimension keys in config order.
     */
    public List<ResourceKey<Level>> getAllExplorationKeys() {
        List<String> enabled = PWConfig.getEnabledDimensions();
        if (enabled == null) return List.of();
        List<ResourceKey<Level>> result = new ArrayList<>();
        for (String dimStr : enabled) {
            ResourceLocation baseLoc = ResourceLocation.tryParse(dimStr);
            if (baseLoc != null) {
                ResourceKey<Level> exploreKey = dimensionMappings.get(baseLoc);
                if (exploreKey != null) {
                    result.add(exploreKey);
                }
            }
        }
        return result;
    }

    // ── Cleanup ──

    public void clearRuntimeDimensions() {
        runtimeDimensions.clear();
        dimensionMappings.clear();
        dimensionSeeds.clear();
        LOGGER.info("Cleared runtime dimension tracking");
    }

    // ── Static convenience ──

    public static boolean isExplorationDimensionStatic(ResourceKey<Level> key) {
        return INSTANCE != null && INSTANCE.isExplorationDimension(key);
    }

    public static Set<ResourceLocation> getExplorationDimensionIds() {
        if (INSTANCE == null) return Collections.emptySet();
        Set<ResourceLocation> ids = new HashSet<>();
        INSTANCE.dimensionMappings.values().forEach(k -> ids.add(k.location()));
        return ids;
    }

    public static void createExplorationDimensionsAtStartup(MinecraftServer server) {
        if (INSTANCE == null) initialize();
        INSTANCE.createDimensionsOnServerStart(server);
    }

    public static void cleanupOnShutdown() {
        if (INSTANCE != null) {
            INSTANCE.clearRuntimeDimensions();
            INSTANCE = null;
        }
    }
}
