package com.agent772.parallelworlds.generation;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.generation.async.AsyncChunkWorkerPool;
import com.agent772.parallelworlds.generation.async.ChunkComputeRequest;
import com.agent772.parallelworlds.generation.async.ChunkComputeResult;
import com.agent772.parallelworlds.performance.MemoryMonitor;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-dimension pre-generation task that runs on the main server thread
 * with TPS-aware throttling and time-budgeted chunk loading.
 */
public class GenerationTask {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RETRIES = 3;
    private static final int TICKET_REMOVAL_DELAY_TICKS = 300;
    private static final int LOG_INTERVAL = 100;

    public static final TicketType<ChunkPos> PREGEN_TICKET =
            TicketType.create("pw_pregen", Comparator.comparingLong(ChunkPos::toLong), TICKET_REMOVAL_DELAY_TICKS);

    private final ResourceKey<Level> dimensionKey;
    private final GenerationProgress progress;
    private final ConcurrentLinkedQueue<ScheduledRemoval> scheduledRemovals = new ConcurrentLinkedQueue<>();
    private AsyncChunkWorkerPool workerPool;

    public GenerationTask(ResourceKey<Level> dimensionKey, int radius, ChunkPos center) {
        SpiralIterator iterator = new SpiralIterator(center.x, center.z, radius);
        this.dimensionKey = dimensionKey;
        this.progress = new GenerationProgress(iterator);
    }

    private GenerationTask(ResourceKey<Level> dimensionKey, GenerationProgress progress) {
        this.dimensionKey = dimensionKey;
        this.progress = progress;
    }

    /**
     * Set the async worker pool for this task. When set and async is enabled,
     * the task will pre-submit chunks to workers for noise computation.
     */
    public void setWorkerPool(AsyncChunkWorkerPool pool) {
        this.workerPool = pool;
    }

    /**
     * Process the next batch of chunks. Called once per server tick.
     * If async is enabled and a worker pool is available, chunks are submitted
     * to workers for pre-computation. The noise results are cached and used
     * by the mixin on fillFromNoise to skip the expensive noise step.
     *
     * @param level the dimension to generate chunks in
     * @param budgetNanos maximum nanoseconds this task may consume (shared global budget)
     */
    public void processNextBatch(ServerLevel level, long budgetNanos) {
        MinecraftServer server = level.getServer();
        long currentTick = server.getTickCount();

        // Always process ticket removals first, even when throttled
        processTicketRemovals(level, currentTick);

        if (progress.getStatus() == GenerationProgress.Status.PAUSED ||
                progress.getStatus() == GenerationProgress.Status.COMPLETED) {
            return;
        }

        // Check completion
        if (!progress.getSpiralIterator().hasNext()) {
            progress.setStatus(GenerationProgress.Status.COMPLETED);
            LOGGER.info("Pre-generation completed for {} — {} chunks processed ({} newly generated)",
                    dimensionKey.location(), progress.getChunksGenerated(), progress.getChunksActuallyGenerated());
            return;
        }

        // TPS throttling
        int chunksThisTick = calculateChunksThisTick(server);
        if (chunksThisTick <= 0) return;

        // Memory check — pauses new submissions but lets in-flight work complete
        if (MemoryMonitor.isMemoryPressureHigh()) {
            return;
        }

        boolean asyncEnabled = PWConfig.isAsyncChunkGenEnabled() && workerPool != null;

        // === PHASE 1: Submit chunks to async workers (if enabled) ===
        if (asyncEnabled) {
            submitNextChunksAsync(level);
        }

        // === PHASE 2: Generate chunks on main thread (time-budgeted) ===
        processNextBatchSync(level, budgetNanos, chunksThisTick, currentTick);
    }

    /**
     * Submit upcoming chunks to the async worker pool for noise pre-computation.
     * The workers compute terrain data in parallel, caching results.
     * When the sync path later generates these chunks via getChunk(FULL),
     * the mixin skips the noise step because cached data is available.
     */
    private void submitNextChunksAsync(ServerLevel level) {
        SpiralIterator iterator = progress.getSpiralIterator();
        int lookAhead = PWConfig.getPregenChunksPerTick() * 4; // look ahead several ticks

        List<ChunkPos> upcoming = iterator.peekAhead(lookAhead);
        for (ChunkPos pos : upcoming) {
            if (workerPool.isFull()) break;
            if (AsyncChunkWorkerPool.hasCachedResult(dimensionKey, pos)) continue;

            ChunkComputeRequest request = ChunkComputeRequest.fromLevel(level, pos);
            if (request != null) {
                workerPool.submit(request);
            }
        }
    }

    /**
     * Synchronous chunk generation path. Processes chunks on the main thread
     * with time budget enforcement. When async pre-computation is active,
     * the noise step will be skipped for chunks that have cached results
     * (handled transparently by the fillFromNoise mixin).
     */
    private void processNextBatchSync(ServerLevel level, long budgetNanos, int chunksThisTick, long currentTick) {
        long tickStartNanos = System.nanoTime();
        int processed = 0;

        SpiralIterator iterator = progress.getSpiralIterator();

        while (iterator.hasNext() && processed < chunksThisTick) {
            // Check time budget
            if (processed > 0 && (System.nanoTime() - tickStartNanos) >= budgetNanos) {
                break;
            }

            ChunkPos chunkPos = iterator.next();

            try {
                // Add ticket before loading
                level.getChunkSource().addRegionTicket(PREGEN_TICKET, chunkPos, 0, chunkPos);

                // Check if already fully generated
                var chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.EMPTY, true);
                if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL)) {
                    // Already generated, just count as processed
                    progress.incrementChunksGenerated();
                } else {
                    // Force full generation — if async is active, the noise step
                    // may be skipped via the fillFromNoise mixin cache
                    level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
                    progress.incrementChunksGenerated();
                    progress.incrementChunksActuallyGenerated();
                }

                processed++;
            } catch (Exception e) {
                int retries = progress.recordFailure(chunkPos);
                if (retries >= MAX_RETRIES) {
                    LOGGER.warn("Chunk {} failed {} times for {}, skipping",
                            chunkPos, retries, dimensionKey.location());
                    progress.incrementChunksGenerated(); // skip past it
                } else {
                    LOGGER.debug("Chunk {} failed (attempt {}) for {}",
                            chunkPos, retries, dimensionKey.location());
                }
                processed++;
            } finally {
                // Schedule ticket removal
                scheduledRemovals.add(new ScheduledRemoval(chunkPos, currentTick + TICKET_REMOVAL_DELAY_TICKS));
            }

            // Log progress periodically
            long generated = progress.getChunksGenerated();
            if (generated > 0 && generated % LOG_INTERVAL == 0) {
                LOGGER.info("Pre-gen {}: {}/{} ({}%) at {} chunks/min, ETA: {} min",
                        dimensionKey.location(),
                        generated, iterator.getTotalChunks(),
                        String.format("%.1f", progress.getProgressPercent()),
                        String.format("%.0f", progress.getGenerationRate()),
                        String.format("%.1f", progress.getEstimatedMinutesRemaining()));
            }
        }
    }

    private int calculateChunksThisTick(MinecraftServer server) {
        double tps = calculateTps(server);
        int configured = PWConfig.getPregenChunksPerTick();

        if (tps >= 19.5) return configured;
        if (tps >= 19.0) return Math.max(1, configured - 1);
        if (tps >= 18.0) return 1;
        return 0; // pause entirely
    }

    private double calculateTps(MinecraftServer server) {
        long avgNanos = server.getAverageTickTimeNanos();
        if (avgNanos <= 0) return 20.0;
        // TPS = 1 billion ns / avg tick time, capped at 20
        return Math.min(20.0, 1_000_000_000.0 / avgNanos);
    }

    private void processTicketRemovals(ServerLevel level, long currentTick) {
        while (!scheduledRemovals.isEmpty()) {
            ScheduledRemoval removal = scheduledRemovals.peek();
            if (removal == null || removal.removeAtTick > currentTick) break;

            scheduledRemovals.poll();
            try {
                level.getChunkSource().removeRegionTicket(PREGEN_TICKET, removal.chunkPos, 0, removal.chunkPos);
            } catch (Exception e) {
                LOGGER.debug("Failed to remove pregen ticket at {}", removal.chunkPos);
            }
        }
    }

    public boolean isComplete() {
        return progress.getStatus() == GenerationProgress.Status.COMPLETED;
    }

    public GenerationProgress getProgress() {
        return progress;
    }

    public ResourceKey<Level> getDimensionKey() {
        return dimensionKey;
    }

    public void pause() {
        if (progress.getStatus() == GenerationProgress.Status.RUNNING) {
            progress.setStatus(GenerationProgress.Status.PAUSED);
        }
    }

    public void resume() {
        if (progress.getStatus() == GenerationProgress.Status.PAUSED) {
            progress.setStatus(GenerationProgress.Status.RUNNING);
        }
    }

    /**
     * Cancel this task and clean up any remaining tickets.
     */
    public void cancel(ServerLevel level) {
        progress.setStatus(GenerationProgress.Status.COMPLETED);
        // Flush all remaining ticket removals
        long currentTick = level.getServer().getTickCount();
        ScheduledRemoval removal;
        while ((removal = scheduledRemovals.poll()) != null) {
            try {
                level.getChunkSource().removeRegionTicket(PREGEN_TICKET, removal.chunkPos, 0, removal.chunkPos);
            } catch (Exception e) {
                // ignore on shutdown
            }
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimensionKey.location().toString());
        tag.put("progress", progress.toNbt());
        return tag;
    }

    public static GenerationTask fromNbt(CompoundTag tag) {
        String dimStr = tag.getString("dimension");
        ResourceKey<Level> key = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(dimStr));
        GenerationProgress prog = GenerationProgress.fromNbt(tag.getCompound("progress"));
        return new GenerationTask(key, prog);
    }

    private record ScheduledRemoval(ChunkPos chunkPos, long removeAtTick) {}
}
