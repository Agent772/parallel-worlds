package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.accessor.IRegistryAccessor;
import com.agent772.parallelworlds.accessor.IServerDimensionAccessor;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements IServerDimensionAccessor {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();

    @Shadow @Final private Map<ResourceKey<Level>, ServerLevel> levels;
    @Shadow @Final protected LevelStorageSource.LevelStorageAccess storageSource;
    @Shadow @Final private Executor executor;
    @Shadow @Final private WorldData worldData;

    @Unique private final Map<ResourceKey<Level>, ServerLevel> pw$runtimeLevels = new ConcurrentHashMap<>();
    @Unique private final List<BorderChangeListener> pw$borderListeners = new ArrayList<>();

    @Override
    @Unique
    public ServerLevel pw$createRuntimeDimension(ResourceKey<Level> dimensionKey,
                                                  Holder<DimensionType> dimTypeHolder,
                                                  ChunkGenerator chunkGenerator,
                                                  long seed) {
        MinecraftServer server = (MinecraftServer) (Object) this;

        if (dimensionKey == null || dimTypeHolder == null || chunkGenerator == null) {
            pw$LOGGER.error("Cannot create dimension with null parameters");
            return null;
        }

        if (levels.containsKey(dimensionKey)) {
            pw$LOGGER.warn("Dimension {} already exists", dimensionKey.location());
            return levels.get(dimensionKey);
        }

        try {
            // Create LevelStem using the resolved DimensionType holder from DimensionFactory
            LevelStem levelStem = new LevelStem(dimTypeHolder, chunkGenerator);

            // Register level stem in registry
            pw$registerLevelStem(server, dimensionKey, levelStem);

            // Derive level data from overworld
            ServerLevelData levelData = pw$createLevelData(server, dimensionKey);

            // No-op progress listener for runtime dimensions
            ChunkProgressListener progressListener = new ChunkProgressListener() {
                @Override public void updateSpawnPos(ChunkPos chunkPos) {}
                @Override public void onStatusChange(ChunkPos chunkPos, ChunkStatus chunkStatus) {}
                @Override public void start() {}
                @Override public void stop() {}
            };

            // Set seed context
            ExplorationSeedManager.setCurrentDimension(dimensionKey);
            ExplorationSeedManager.registerDimensionSeed(dimensionKey, seed);
            pw$LOGGER.info("Creating ServerLevel for {} with seed {}", dimensionKey.location(), seed);

            // Create ServerLevel
            ServerLevel newLevel = new ServerLevel(
                    server, executor, storageSource, levelData,
                    dimensionKey, levelStem, progressListener,
                    false, seed, List.of(), true, null
            );

            // Clear context immediately
            ExplorationSeedManager.clearCurrentDimension();

            // Safety cleanup on next tick
            server.execute(ExplorationSeedManager::clearCurrentDimension);

            // Border listener / fixed border
            try {
                int borderDiameter = PWConfig.getExplorationBorderDiameter();
                ServerLevel overworld = server.overworld();
                if (borderDiameter < 0) {
                    // -1 = inherit overworld border (delegate changes in real time)
                    if (overworld != null) {
                        BorderChangeListener listener =
                                new BorderChangeListener.DelegateBorderChangeListener(newLevel.getWorldBorder());
                        overworld.getWorldBorder().addListener(listener);
                        pw$borderListeners.add(listener);
                    }
                } else {
                    // Fixed diameter — matches the unit used by /worldborder; do NOT delegate
                    // from overworld so main-world border changes don't bleed through.
                    newLevel.getWorldBorder().setCenter(0, 0);
                    newLevel.getWorldBorder().setSize(borderDiameter);
                    pw$LOGGER.info("Set fixed world border on {} — {}x{} blocks ({} block radius)",
                            dimensionKey.location(), borderDiameter, borderDiameter, borderDiameter / 2);
                }
            } catch (Exception e) {
                pw$LOGGER.warn("Failed to setup border for {}", dimensionKey.location(), e);
            }

            levels.put(dimensionKey, newLevel);
            pw$runtimeLevels.put(dimensionKey, newLevel);

            pw$LOGGER.info("Successfully created runtime dimension: {}", dimensionKey.location());
            return newLevel;

        } catch (Exception e) {
            pw$LOGGER.error("Failed to create runtime dimension: {}", dimensionKey.location(), e);
            pw$runtimeLevels.remove(dimensionKey);
            levels.remove(dimensionKey);
            return null;
        }
    }

    @Override
    @Unique
    public void pw$removeRuntimeDimension(ResourceKey<Level> dimensionKey) {
        ServerLevel level = pw$runtimeLevels.remove(dimensionKey);
        if (level == null) return;

        MinecraftServer server = (MinecraftServer) (Object) this;
        ServerLevel overworld = server.overworld();

        // Evacuate players
        if (overworld != null) {
            new ArrayList<>(level.players()).forEach(player -> {
                try {
                    var spawn = overworld.getSharedSpawnPos();
                    player.teleportTo(overworld,
                            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
                } catch (Exception e) {
                    pw$LOGGER.error("Failed to evacuate {}", player.getName().getString(), e);
                }
            });
        }

        // Close level
        try { level.close(); } catch (Exception e) {
            pw$LOGGER.error("Error closing dimension: {}", dimensionKey.location(), e);
        }

        levels.remove(dimensionKey);
        pw$cleanupRegistryEntries(server, dimensionKey);
        pw$LOGGER.info("Removed runtime dimension: {}", dimensionKey.location());
    }

    @Override
    @Unique
    public Map<ResourceKey<Level>, ServerLevel> pw$getRuntimeLevels() {
        return new HashMap<>(pw$runtimeLevels);
    }

    @Override
    @Unique
    public void pw$cleanupAllRuntimeDimensions() {
        new ArrayList<>(pw$runtimeLevels.keySet()).forEach(this::pw$removeRuntimeDimension);
        pw$LOGGER.info("Cleaned up all runtime dimensions");
    }

    // ── Helpers ──

    @Unique
    @SuppressWarnings("unchecked")
    private void pw$registerLevelStem(MinecraftServer server,
                                       ResourceKey<Level> dimensionKey,
                                       LevelStem levelStem) {
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, dimensionKey.location());

        if (stemRegistry.containsKey(stemKey)) return;

        if (stemRegistry instanceof IRegistryAccessor<?>) {
            IRegistryAccessor<LevelStem> accessor = (IRegistryAccessor<LevelStem>) stemRegistry;
            try {
                accessor.pw$registerRuntime(stemKey, levelStem);
                pw$LOGGER.info("Registered level stem: {}", stemKey.location());
            } catch (Exception e) {
                pw$LOGGER.warn("Failed to register level stem {}: {}", stemKey.location(), e.getMessage());
            }
        } else {
            pw$LOGGER.warn("Stem registry lacks mixin — skipping registration for {}", stemKey.location());
        }
    }

    @Unique
    private ServerLevelData pw$createLevelData(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        ServerLevel overworld = server.overworld();
        ServerLevelData overworldData = (ServerLevelData) overworld.getLevelData();

        return new DerivedLevelData(worldData, overworldData) {
            @Override
            public String getLevelName() {
                return dimensionKey.location().toString();
            }
        };
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void pw$cleanupRegistryEntries(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        try {
            Registry<LevelStem> stemReg = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
            ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, dimensionKey.location());
            if (stemReg instanceof IRegistryAccessor<?>) {
                ((IRegistryAccessor<LevelStem>) stemReg).pw$removeRuntimeEntry(stemKey);
            }
        } catch (Exception e) {
            pw$LOGGER.error("Failed to cleanup registry for {}", dimensionKey.location(), e);
        }
    }
}
