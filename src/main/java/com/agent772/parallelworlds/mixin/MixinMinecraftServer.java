package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.accessor.IServerDimensionAccessor;
import com.agent772.parallelworlds.accessor.IWorldDataAccessor;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
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
    @Unique private final Map<ResourceKey<Level>, LevelStem> pw$levelStems = new ConcurrentHashMap<>();
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

            // Store level stem in PW-internal map instead of the vanilla LEVEL_STEM
            // registry to prevent noise settings corruption in level.dat (fixes #5)
            pw$levelStems.put(dimensionKey, levelStem);

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

            // Save original world seed before constructing ServerLevel to prevent
            // the exploration seed from leaking into PrimaryLevelData (fixes #1)
            WorldOptions originalOptions = worldData.worldGenOptions();
            long originalSeed = originalOptions.seed();

            // Create ServerLevel
            ServerLevel newLevel = new ServerLevel(
                    server, executor, storageSource, levelData,
                    dimensionKey, levelStem, progressListener,
                    false, seed, List.of(), true, null
            );

            // Clear context immediately
            ExplorationSeedManager.clearCurrentDimension();

            // Restore original seed if it was modified during ServerLevel construction
            if (worldData.worldGenOptions().seed() != originalSeed) {
                pw$LOGGER.warn("Seed leak detected after creating {}! " +
                                "Global seed was changed from {} to {} — restoring original",
                        dimensionKey.location(), originalSeed, worldData.worldGenOptions().seed());
                if (worldData instanceof IWorldDataAccessor accessor) {
                    accessor.pw$setWorldOptions(originalOptions);
                } else {
                    pw$LOGGER.error("Cannot restore seed: WorldData is not accessible via mixin");
                }
            }

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

            // Notify all mods (e.g. Distant Horizons, JourneyMap) that a new level is live.
            NeoForge.EVENT_BUS.post(new LevelEvent.Load(newLevel));

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

        // Evacuate players — use TeleportHandler so the full priority logic applies
        // (bed → entry position → world-spawn surface via heightmap).
        // This fallback only runs for players still in the level after evacuateAllPlayers().
        List<net.minecraft.server.level.ServerPlayer> playersInLevel = new ArrayList<>(level.players());
        if (overworld != null) {
            playersInLevel.forEach(player -> {
                try {
                    TeleportHandler.evacuatePlayer(player);
                } catch (Exception e) {
                    pw$LOGGER.error("Failed to evacuate {}", player.getName().getString(), e);
                    // Last resort: surface heightmap to avoid landing inside terrain.
                    try {
                        var spawnXZ = overworld.getSharedSpawnPos();
                        var surface = overworld.getHeightmapPos(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnXZ);
                        player.teleportTo(overworld,
                                surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5, 0, 0);
                    } catch (Exception ex) {
                        pw$LOGGER.error("Last-resort evacuation also failed for {}",
                                player.getName().getString(), ex);
                    }
                }
            });
        }

        // Close level
        try { level.close(); } catch (Exception e) {
            pw$LOGGER.error("Error closing dimension: {}", dimensionKey.location(), e);
        }

        // Notify mods that this level is going away before we remove it from the map.
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));

        levels.remove(dimensionKey);
        pw$levelStems.remove(dimensionKey);
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

}
