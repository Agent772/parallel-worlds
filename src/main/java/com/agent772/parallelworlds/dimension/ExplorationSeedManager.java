package com.agent772.parallelworlds.dimension;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages per-dimension seed registration and ThreadLocal context
 * so that MixinRandomState can intercept seed values during ServerLevel construction.
 */
public final class ExplorationSeedManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, Long> DIMENSION_SEEDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ResourceKey<Level>> CURRENT_DIMENSION = new ThreadLocal<>();
    private static final AtomicBoolean WORLD_OPTIONS_WRITE_GUARD = new AtomicBoolean(false);
    private static volatile WorldOptions ORIGINAL_WORLD_OPTIONS = null;

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
        disableWorldOptionsGuard();
    }

    // ── WorldOptions write guard ──

    /**
     * Enable the write guard, snapshotting the original WorldOptions.
     * While active, any attempt to overwrite PrimaryLevelData.worldOptions
     * with a different seed will be blocked by MixinPrimaryLevelData.
     */
    public static void enableWorldOptionsGuard(WorldOptions original) {
        ORIGINAL_WORLD_OPTIONS = original;
        WORLD_OPTIONS_WRITE_GUARD.set(true);
        LOGGER.info("WorldOptions write guard enabled (seed={})", original.seed());
    }

    /**
     * Disable the write guard and clear the snapshot.
     */
    public static void disableWorldOptionsGuard() {
        WORLD_OPTIONS_WRITE_GUARD.set(false);
        ORIGINAL_WORLD_OPTIONS = null;
    }

    public static boolean isWorldOptionsGuardActive() {
        return WORLD_OPTIONS_WRITE_GUARD.get();
    }

    public static WorldOptions getOriginalWorldOptions() {
        return ORIGINAL_WORLD_OPTIONS;
    }
}
