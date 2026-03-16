package com.agent772.parallelworlds.dimension;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-dimension seed registration and ThreadLocal context
 * so that MixinRandomState can intercept seed values during ServerLevel construction.
 */
public final class ExplorationSeedManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, Long> DIMENSION_SEEDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ResourceKey<Level>> CURRENT_DIMENSION = new ThreadLocal<>();

    private ExplorationSeedManager() {}

    public static void registerDimensionSeed(ResourceKey<Level> dimension, long seed) {
        DIMENSION_SEEDS.put(dimension, seed);
        LOGGER.info("Registered seed {} for dimension {}", seed, dimension.location());
    }

    public static Optional<Long> getSeedForDimension(ResourceKey<Level> dimension) {
        return Optional.ofNullable(DIMENSION_SEEDS.get(dimension));
    }

    public static long modifySeed(ResourceKey<Level> dimension, long originalSeed) {
        Long custom = DIMENSION_SEEDS.get(dimension);
        if (custom != null) {
            return custom;
        }
        // Fallback: derive from dimension name
        long modifier = dimension.location().hashCode() * 0x123456789L;
        return originalSeed ^ modifier;
    }

    public static boolean isExplorationDimension(ResourceKey<Level> dimension) {
        return DimensionUtils.isExplorationDimension(dimension);
    }

    // ── ThreadLocal context ──

    public static void setCurrentDimension(ResourceKey<Level> dimension) {
        CURRENT_DIMENSION.set(dimension);
        LOGGER.debug("Set dimension context: {}", dimension.location());
    }

    @SuppressWarnings("unchecked")
    public static ResourceKey<Level> getCurrentDimension() {
        return CURRENT_DIMENSION.get();
    }

    public static void clearCurrentDimension() {
        CURRENT_DIMENSION.remove();
        LOGGER.debug("Cleared dimension context");
    }

    public static Map<ResourceKey<Level>, Long> getAllSeeds() {
        return new ConcurrentHashMap<>(DIMENSION_SEEDS);
    }

    public static void clearAll() {
        DIMENSION_SEEDS.clear();
        CURRENT_DIMENSION.remove();
    }
}
