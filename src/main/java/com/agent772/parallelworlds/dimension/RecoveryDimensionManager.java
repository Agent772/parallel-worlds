package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.accessor.IServerDimensionAccessor;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages temporarily-loaded <em>recovery</em> dimensions.
 *
 * <p>Recovery dims are:
 * <ul>
 *   <li>Invisible to non-admin players (not in DimensionRegistrar, not synced)</li>
 *   <li>Excluded from portal cycling and /pw player commands</li>
 *   <li>Auto-unloaded when empty for {@code recoveryAutoUnloadDelaySec} seconds</li>
 *   <li>Not persisted across server restarts — all recovery state is transient</li>
 * </ul>
 */
public final class RecoveryDimensionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static RecoveryDimensionManager INSTANCE;

    /** Keys currently loaded as recovery dims. */
    private final Set<ResourceKey<Level>> recoveryDimensions = ConcurrentHashMap.newKeySet();

    /**
     * Accumulated ticks each recovery dim has been continuously empty.
     * Reset to 0 when players enter; incremented by 20 every second the dim stays empty.
     */
    private final Map<ResourceKey<Level>, Integer> emptyTickAccumulator = new ConcurrentHashMap<>();

    private RecoveryDimensionManager() {}

    public static void initialize() {
        INSTANCE = new RecoveryDimensionManager();
    }

    public static RecoveryDimensionManager getInstance() {
        if (INSTANCE == null) {
            initialize();
        }
        return INSTANCE;
    }

    public static void cleanupOnShutdown() {
        INSTANCE = null;
    }

    // ── Query ──

    /** Returns true if the given key belongs to a currently-loaded recovery dim. */
    public boolean isRecoveryDimension(ResourceKey<Level> key) {
        return key != null && recoveryDimensions.contains(key);
    }

    /** Unmodifiable snapshot of all currently-loaded recovery dim keys. */
    public Set<ResourceKey<Level>> getRecoveryKeys() {
        return Collections.unmodifiableSet(recoveryDimensions);
    }

    /**
     * Returns a map of loaded recovery dim keys → current player count in each.
     */
    public Map<ResourceKey<Level>, Integer> getStatus(MinecraftServer server) {
        Map<ResourceKey<Level>, Integer> result = new LinkedHashMap<>();
        for (ResourceKey<Level> key : recoveryDimensions) {
            ServerLevel level = server.getLevel(key);
            result.put(key, level != null ? level.players().size() : 0);
        }
        return result;
    }

    // ── Load ──

    /**
     * Load a recovery dimension from disk into the running server using the existing
     * on-disk folder key so Minecraft serves the stored region files.
     *
     * <p>Uses seed {@code 0} — the chunk generator is only needed to satisfy the
     * ServerLevel constructor; actual terrain comes from the region files on disk.
     *
     * @return the loaded {@link ServerLevel}, or {@code null} on failure
     */
    public ServerLevel loadRecovery(MinecraftServer server, RecoveryScanner.RecoveryCandidate candidate) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, candidate.dimensionId());

        if (recoveryDimensions.contains(key)) {
            LOGGER.warn("Recovery dimension {} is already loaded", candidate.folderName());
            return server.getLevel(key);
        }

        if (DimensionRegistrar.getExplorationDimensionIds().contains(candidate.dimensionId())) {
            LOGGER.warn("Dimension {} is currently active — cannot load as recovery", candidate.folderName());
            return null;
        }

        // Find a suitable base dimension to clone the chunk generator / dim-type from
        ResourceLocation baseDimLoc = resolveBaseDimension(server, candidate.dimType());

        ServerLevel level = null;
        try {
            // Seed 0: terrain is already on disk; the generator seed doesn't matter here
            level = DimensionFactory.createExplorationDimensionWithKey(
                    server, baseDimLoc, candidate.dimensionId(), 0L);
        } catch (Exception e) {
            LOGGER.error("Exception while loading recovery dimension {}", candidate.folderName(), e);
        }

        if (level != null) {
            recoveryDimensions.add(key);
            emptyTickAccumulator.put(key, 0);
            LOGGER.info("Loaded recovery dimension: {} (gen cloned from {})", candidate.folderName(), baseDimLoc);
        } else {
            LOGGER.error("Failed to load recovery dimension: {}", candidate.folderName());
        }

        return level;
    }

    // ── Unload ──

    /**
     * Evacuate all players from the recovery dim and remove it from the server.
     * Does <em>not</em> delete the on-disk folder.
     *
     * @return {@code true} if the dim was loaded and has been unloaded; {@code false} if
     *         the key was not a recovery dim
     */
    public boolean unloadRecovery(MinecraftServer server, ResourceKey<Level> key) {
        if (!recoveryDimensions.contains(key)) {
            return false;
        }

        // Evacuate first, before removing from tracking so DimensionUtils still recognises it
        ServerLevel level = server.getLevel(key);
        int evacuated = 0;
        if (level != null) {
            List<ServerPlayer> players = new ArrayList<>(level.players());
            evacuated = players.size();
            for (ServerPlayer player : players) {
                try {
                    TeleportHandler.evacuatePlayer(player);
                } catch (Exception e) {
                    LOGGER.error("Failed to evacuate {} from recovery dim {}",
                            player.getName().getString(), key.location(), e);
                }
            }
        }

        recoveryDimensions.remove(key);
        emptyTickAccumulator.remove(key);

        try {
            ((IServerDimensionAccessor) server).pw$removeRuntimeDimension(key);
        } catch (Exception e) {
            LOGGER.error("Failed to remove recovery dimension {} from server", key.location(), e);
        }

        LOGGER.info("Unloaded recovery dimension: {} (evacuated {} player(s))",
                key.location(), evacuated);
        return true;
    }

    // ── Auto-unload tick ──

    /**
     * Called every server tick from {@code ParallelWorlds.onServerTick}.
     * Performs the empty-dimension auto-unload check once per second (every 20 ticks).
     */
    public void tick(MinecraftServer server) {
        if (recoveryDimensions.isEmpty()) return;

        // Only run the full check at the start of each second
        if ((server.getTickCount() % 20) != 0) return;

        int thresholdTicks = PWConfig.getRecoveryAutoUnloadDelaySec() * 20;

        for (ResourceKey<Level> key : new ArrayList<>(recoveryDimensions)) {
            ServerLevel level = server.getLevel(key);
            if (level == null) {
                // Level disappeared unexpectedly — clean up tracking
                recoveryDimensions.remove(key);
                emptyTickAccumulator.remove(key);
                continue;
            }

            int playerCount = level.players().size();
            if (playerCount == 0) {
                // Accumulate 20 ticks (1 second) of empty time
                int accumulated = emptyTickAccumulator.merge(key, 20, Integer::sum);
                if (accumulated >= thresholdTicks) {
                    int delaySec = PWConfig.getRecoveryAutoUnloadDelaySec();
                    LOGGER.info("Auto-unloading empty recovery dimension {} (empty for {} seconds)",
                            key.location(), delaySec);
                    server.getPlayerList().getPlayers().stream()
                            .filter(p -> p.hasPermissions(2))
                            .forEach(p -> p.sendSystemMessage(
                                    Component.translatable("parallelworlds.admin.recovery.auto_unload",
                                            key.location().getPath(), delaySec)
                                    .withStyle(ChatFormatting.YELLOW)));
                    unloadRecovery(server, key);
                }
            } else {
                // Players present — reset the empty counter
                emptyTickAccumulator.put(key, 0);
            }
        }
    }

    // ── Helpers ──

    /**
     * Resolve the active base dimension to clone the chunk generator / dimension type from.
     * Matches by the {@code dimType} string embedded in the folder name (e.g. {@code "overworld"}).
     *
     * <p>Priority:
     * <ol>
     *   <li>Exact {@code minecraft:dimType} match in the server's loaded levels</li>
     *   <li>Any loaded level whose path equals {@code dimType} (modded dims)</li>
     *   <li>Overworld as a universal fallback — terrain still loads from disk</li>
     * </ol>
     */
    private static ResourceLocation resolveBaseDimension(MinecraftServer server, String dimType) {
        // Priority 1: vanilla namespace
        ResourceLocation vanilla = ResourceLocation.fromNamespaceAndPath("minecraft", dimType);
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, vanilla)) != null) {
            return vanilla;
        }

        // Priority 2: any loaded level whose path matches
        for (ResourceKey<Level> lk : server.levelKeys()) {
            if (lk.location().getPath().equals(dimType)) {
                return lk.location();
            }
        }

        // Priority 3: overworld fallback (can still load on-disk terrain)
        LOGGER.warn("Could not match base dimension for type '{}' — cloning generator from overworld", dimType);
        return ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    }
}
