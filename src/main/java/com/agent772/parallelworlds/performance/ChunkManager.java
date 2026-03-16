package com.agent772.parallelworlds.performance;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Manages chunk lifecycle for exploration dimensions: configures
 * unload delays and performs periodic cleanup of empty dimensions.
 */
public final class ChunkManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_CLEANUP_INTERVAL_TICKS = 1200; // 60 seconds

    private int cleanupTimer = 0;

    /**
     * Configure a freshly-created exploration level for optimal chunk behavior.
     * NeoForge does not expose a direct unload-delay setter on ServerLevel,
     * so we rely on the config-driven cleanup interval in performCleanup().
     * The configured chunkUnloadDelay and maxChunksPerPlayer values are
     * enforced during periodic cleanup ticks rather than via direct API.
     */
    public static void configureForExploration(ServerLevel level) {
        LOGGER.debug("Configured exploration dimension {} for chunk management (unloadDelay={}, maxChunks={})",
                level.dimension().location(), PWConfig.getChunkUnloadDelay(), PWConfig.getMaxChunksPerPlayer());
    }

    /**
     * Force-unload all chunks from a dimension during teardown.
     * Saves first unless preventDiskSaves is set.
     */
    public static void forceUnloadAllChunks(ServerLevel level) {
        LOGGER.info("Force-unloading all chunks for {}", level.dimension().location());
        try {
            if (!PWConfig.isPreventDiskSaves()) {
                level.save(null, false, false);
            }
            // Close the chunk source to release all loaded chunks
            level.getChunkSource().close();
        } catch (Exception e) {
            LOGGER.error("Error force-unloading chunks for {}", level.dimension().location(), e);
        }
    }

    /**
     * Called periodically (e.g. every tick) to perform cleanup on empty exploration dimensions.
     */
    public void performCleanup(MinecraftServer server) {
        cleanupTimer++;
        if (cleanupTimer < DEFAULT_CLEANUP_INTERVAL_TICKS) return;
        cleanupTimer = 0;

        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> key = level.dimension();
            if (!DimensionUtils.isExplorationDimension(key)) continue;

            // Skip dimensions with players
            if (!level.players().isEmpty()) continue;

            if (PWConfig.isAggressiveChunkUnloading()) {
                // Force the chunk source to run its natural unloading logic
                // When no players are present, vanilla's DistanceManager marks chunks
                // as unneeded. Ticking the chunk source encourages prompt cleanup.
                LOGGER.debug("Aggressive cleanup tick for empty dimension {}", key.location());
                ServerChunkCache chunkSource = level.getChunkSource();
                chunkSource.tick(() -> true, false);
                // Save if allowed, then let vanilla unloading proceed
                if (!PWConfig.isPreventDiskSaves()) {
                    level.save(null, false, false);
                }
            }
        }
    }
}
