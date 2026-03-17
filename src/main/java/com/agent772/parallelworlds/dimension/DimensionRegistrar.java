package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.agent772.parallelworlds.data.DimensionMetadata;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.dimension.SeedStore;
import com.agent772.parallelworlds.portal.PortalBuilder;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
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
    /** Dims created fresh (not reused) during this server start — used to auto-start pregen. */
    private final Set<ResourceKey<Level>> freshlyCreatedKeys = new HashSet<>();

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
        // Snapshot "now" once so all dims get the same registeredAt timestamp this startup.
        long now = SeedManager.currentEpochSecond();

        for (String baseDimStr : enabled) {
            try {
                ResourceLocation baseDim = ResourceLocation.parse(baseDimStr);

                // SeedStore is the single source of truth for seeds.
                // initializeAndRotate() guarantees every enabled dim has a seed.
                long seed = SeedStore.getSeed(baseDim)
                        .orElseThrow(() -> new IllegalStateException(
                                "SeedStore has no seed for " + baseDim + " — initializeAndRotate() must be called first"));

                // Timestamp comparison: if the seed is newer than the last time this
                // dimension was registered, the dimension must be recreated with the new seed.
                long seedCreatedAt = SeedStore.getSeedCreatedAt(baseDim);
                long dimRegisteredAt = savedData.getDimensionRegisteredAt(baseDim);
                boolean shouldReuse = isPersistMode
                        && dimRegisteredAt > 0
                        && seedCreatedAt <= dimRegisteredAt;

                if (isPersistMode) {
                    if (dimRegisteredAt <= 0) {
                        LOGGER.info("[DimensionRegistrar] {} — PERSIST mode, no saved registration → RECREATE (first run)",
                                baseDim);
                    } else if (seedCreatedAt > dimRegisteredAt) {
                        LOGGER.info("[DimensionRegistrar] {} — PERSIST mode, seed rotated: seedCreatedAt={} > dimRegisteredAt={} → RECREATE",
                                baseDim, SeedManager.formatEpoch(seedCreatedAt), SeedManager.formatEpoch(dimRegisteredAt));
                    } else {
                        LOGGER.info("[DimensionRegistrar] {} — PERSIST mode, seed unchanged: seedCreatedAt={}, dimRegisteredAt={} → REUSE",
                                baseDim, SeedManager.formatEpoch(seedCreatedAt), SeedManager.formatEpoch(dimRegisteredAt));
                    }
                } else {
                    LOGGER.info("[DimensionRegistrar] {} — REGENERATE mode → RECREATE (always fresh, dimRegisteredAt={})",
                            baseDim, SeedManager.formatEpoch(dimRegisteredAt));
                }

                // In persist mode with an unchanged seed, reuse the same exploration key so
                // existing world data on disk is loaded.  Otherwise create a fresh one.
                Optional<ResourceLocation> savedKey = shouldReuse
                        ? savedData.getSavedDimensionKey(baseDim)
                        : Optional.empty();

                // Remember the previous key for client mod-data cleanup before overwriting it.
                Optional<ResourceLocation> prevKey = savedKey.isPresent()
                        ? Optional.empty()   // reusing — nothing to clean
                        : savedData.getSavedDimensionKey(baseDim);

                ServerLevel level;
                if (savedKey.isPresent()) {
                    LOGGER.info("Reusing existing exploration dimension key {} for {}",
                            savedKey.get(), baseDim);
                    level = DimensionFactory.createExplorationDimensionWithKey(
                            server, baseDim, savedKey.get(), seed);
                } else {
                    level = DimensionFactory.createExplorationDimension(server, baseDim, seed);
                }

                if (level != null) {
                    ResourceKey<Level> explorationKey = level.dimension();
                    runtimeDimensions.put(explorationKey, level);
                    dimensionMappings.put(baseDim, explorationKey);
                    dimensionSeeds.put(explorationKey, seed);

                    // Queue old key for client mod-data cleanup (Xaero, JourneyMap, etc.).
                    prevKey.ifPresent(old -> {
                        if (!old.equals(explorationKey.location())) {
                            savedData.addPendingCleanup(old.getPath());
                            LOGGER.info("Queued old dimension {} for client mod-data cleanup", old);
                        }
                    });

                    // Persist the exploration key so PERSIST mode can reload it next restart.
                    savedData.saveDimensionKey(baseDim, explorationKey.location());
                    if (!shouldReuse) {
                        // Record when this dimension was (re)created so the next startup
                        // can compare against seed.createdAt to detect a pending rotation.
                        savedData.saveDimensionRegisteredAt(baseDim, now);
                    }
                    DimensionMetadata meta = savedData.getDimensionMetadata(explorationKey.location())
                            .orElse(null);
                    if (meta == null || !shouldReuse) {
                        savedData.recordDimensionAccess(explorationKey.location(), null);
                    }

                    LOGGER.info("Created exploration dimension {} (seed {})", explorationKey.location(), seed);

                    if (!shouldReuse) {
                        freshlyCreatedKeys.add(explorationKey);
                    }

                    // Pre-build the entry portal at server startup for freshly created dims.
                    // Doing this in a deferred TickTask means the tick loop is live, so
                    // the blocking getChunk(FULL) inside resolveSpawnPosition can drain chunk
                    // worker results normally.  The 15-second generation cost moves to tick 1
                    // (no players connected yet) instead of hitting the first player to enter.
                    // PWSavedData.getExplorationPortalPos() will find the saved portal, so
                    // PWPortalBlock skips resolveSpawnPosition entirely on first entry.
                    if (!shouldReuse) {
                        final ServerLevel warmLevel = level;
                        final ResourceKey<Level> warmKey = explorationKey;
                        server.tell(new TickTask(server.getTickCount() + 1, () -> {
                            try {
                                PWSavedData warmData = PWSavedData.get(server);
                                // Only build if not already saved (guard against duplicate tasks)
                                if (warmData.getExplorationPortalPos(warmKey.location()).isPresent()) {
                                    return;
                                }
                                LOGGER.info("Pre-building entry portal for {} (generating spawn chunk)", warmKey.location());
                                BlockPos safePos = TeleportHandler.resolveSpawnPosition(warmLevel);
                                BlockPos portalPos = PortalBuilder.buildPortal(warmLevel, safePos, Direction.Axis.Z);
                                warmData.saveExplorationPortal(warmKey.location(), portalPos, Direction.Axis.Z);
                                LOGGER.info("Pre-built entry portal for {} at {}", warmKey.location(), portalPos);
                            } catch (Exception e) {
                                LOGGER.error("Failed to pre-build entry portal for {}", warmKey.location(), e);
                            }
                        }));
                    }
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

    /** Dims that were freshly created (not reused) this server start. Used to auto-start pregen. */
    public Set<ResourceKey<Level>> getFreshlyCreatedKeys() {
        return Collections.unmodifiableSet(freshlyCreatedKeys);
    }

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
