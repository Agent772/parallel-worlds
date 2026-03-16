package com.agent772.parallelworlds.generation.async;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Predicts which chunks a player will need based on their movement direction
 * and pre-submits them to the async worker pool for noise computation.
 * Only operates in exploration dimensions.
 *
 * Checked every 10 ticks (~0.5s). Low overhead.
 */
public final class AsyncChunkHint {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final double MIN_MOVEMENT_BLOCKS = 8.0;

    private static AsyncChunkWorkerPool workerPool;
    private static final Map<UUID, PlayerTracker> trackers = new ConcurrentHashMap<>();

    private AsyncChunkHint() {}

    public static void setWorkerPool(AsyncChunkWorkerPool pool) {
        workerPool = pool;
    }

    /**
     * Called every server tick from the tick handler.
     * Only processes every CHECK_INTERVAL_TICKS.
     */
    public static void tick(long tickCount) {
        if (workerPool == null) return;
        if (!PWConfig.isAsyncChunkGenEnabled()) return;
        if (!PWConfig.isAsyncChunkHintsEnabled()) return;
        if (tickCount % CHECK_INTERVAL_TICKS != 0) return;
    }

    /**
     * Called from a player tick event to track movement and submit hints.
     */
    public static void onPlayerTick(ServerPlayer player) {
        if (workerPool == null) return;
        if (!PWConfig.isAsyncChunkGenEnabled()) return;
        if (!PWConfig.isAsyncChunkHintsEnabled()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!DimensionUtils.isExplorationDimension(level.dimension())) return;

        UUID playerId = player.getUUID();
        PlayerTracker tracker = trackers.computeIfAbsent(playerId, id -> new PlayerTracker(player.position()));

        // Only check every CHECK_INTERVAL_TICKS
        tracker.tickCounter++;
        if (tracker.tickCounter < CHECK_INTERVAL_TICKS) return;
        tracker.tickCounter = 0;

        Vec3 currentPos = player.position();
        double dx = currentPos.x - tracker.lastPos.x;
        double dz = currentPos.z - tracker.lastPos.z;
        double distanceMoved = Math.sqrt(dx * dx + dz * dz);

        if (distanceMoved < MIN_MOVEMENT_BLOCKS) return;

        // Update tracker
        tracker.lastPos = currentPos;

        // Predict chunks in movement direction
        int hintDistance = PWConfig.getAsyncHintDistance();
        ChunkPos currentChunk = player.chunkPosition();
        List<ChunkPos> predicted = predictChunks(currentChunk, dx, dz, distanceMoved, hintDistance);

        int submitted = 0;
        for (ChunkPos pos : predicted) {
            if (workerPool.isFull()) break;
            if (AsyncChunkWorkerPool.hasCachedResult(level.dimension(), pos)) continue;
            if (isChunkLoaded(level, pos)) continue;

            ChunkComputeRequest request = ChunkComputeRequest.fromLevel(level, pos);
            if (request != null && workerPool.submit(request)) {
                submitted++;
            }
        }

        if (submitted > 0) {
            LOGGER.debug("AsyncChunkHint: submitted {} chunks ahead of player {} in {}",
                    submitted, player.getName().getString(), level.dimension().location());
        }
    }

    /**
     * Predict chunks in a cone ahead of the player's movement direction.
     */
    static List<ChunkPos> predictChunks(ChunkPos current, double dx, double dz, double distance,
                                        int hintDistance) {
        List<ChunkPos> chunks = new ArrayList<>();

        // Normalize movement direction
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return chunks;
        double ndx = dx / len;
        double ndz = dz / len;

        // Convert to chunk-scale direction
        for (int ahead = 1; ahead <= hintDistance; ahead++) {
            // Center chunk in movement direction
            int cx = current.x + Mth.floor(ndx * ahead);
            int cz = current.z + Mth.floor(ndz * ahead);
            chunks.add(new ChunkPos(cx, cz));

            // Side chunks (perpendicular to movement) for wider coverage
            if (ahead <= 2) {
                int sx = Mth.floor(-ndz); // perpendicular
                int sz = Mth.floor(ndx);
                if (sx != 0 || sz != 0) {
                    chunks.add(new ChunkPos(cx + sx, cz + sz));
                    chunks.add(new ChunkPos(cx - sx, cz - sz));
                }
            }
        }

        return chunks;
    }

    private static boolean isChunkLoaded(ServerLevel level, ChunkPos pos) {
        return level.getChunkSource().hasChunk(pos.x, pos.z);
    }

    /**
     * Remove tracker when player disconnects.
     */
    public static void onPlayerLogout(UUID playerId) {
        trackers.remove(playerId);
    }

    /**
     * Clear all state. Called on server shutdown.
     */
    public static void clearAll() {
        trackers.clear();
        workerPool = null;
    }

    private static class PlayerTracker {
        Vec3 lastPos;
        int tickCounter;

        PlayerTracker(Vec3 pos) {
            this.lastPos = pos;
            this.tickCounter = 0;
        }
    }
}
