package com.agent772.parallelworlds.generation.async;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread pool that manages async chunk noise computation.
 * Workers compute terrain data off-thread; results are polled by the main thread.
 *
 * Results are also stored in a lookup cache keyed by (dimension, chunkPos)
 * so that the mixin on NoiseBasedChunkGenerator.fillFromNoise can skip
 * noise computation for pre-computed chunks.
 */
public class AsyncChunkWorkerPool {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ThreadPoolExecutor executor;
    private final int maxInFlight;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    private final Set<Long> submittedChunks = ConcurrentHashMap.newKeySet();

    // Cache for pre-computed results, keyed by dimension + chunkPos
    // Accessed from worker threads (write) and main/background threads via mixin (read + remove)
    private static final ConcurrentHashMap<CacheKey, ChunkComputeResult> resultCache = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    public AsyncChunkWorkerPool(int maxInFlight, int workerThreads) {
        this.maxInFlight = maxInFlight;

        // Cap auto-detected threads at 4 to avoid starving DH/JourneyMap/other
        // heavy thread pools on high-core-count machines (e.g. 16-core → 14 without cap).
        int threadCount = workerThreads > 0
                ? workerThreads
                : Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));

        LOGGER.info("Parallel Worlds: Async chunk worker pool configured ({} max threads, {} max in-flight) — threads created on demand",
                threadCount, maxInFlight);

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "PW-ChunkGen-" + counter.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };

        // Use a bounded queue matching maxInFlight so submitted tasks can queue behind
        // running ones without rejection while we're under the in-flight cap.
        // corePoolSize == maximumPoolSize so threads are created lazily (one per task)
        // up to the limit, then allowCoreThreadTimeOut kills them after 60s of idleness.
        // No threads are pre-spawned or permanently parked.
        this.executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(maxInFlight),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Submit a chunk for async noise computation.
     * Returns false if at capacity or already submitted.
     */
    public boolean submit(ChunkComputeRequest request) {
        if (shuttingDown) return false;
        if (inFlightCount.get() >= maxInFlight) return false;

        // Deduplicate: don't submit the same chunk twice
        long posKey = encodeCacheKey(request.dimension(), request.chunkPos());
        if (!submittedChunks.add(posKey)) return false;

        // Check if already cached
        CacheKey cacheKey = new CacheKey(request.dimension(), request.chunkPos());
        if (resultCache.containsKey(cacheKey)) {
            submittedChunks.remove(posKey);
            return false;
        }

        inFlightCount.incrementAndGet();
        ChunkComputeWorker worker = new ChunkComputeWorker(request);

        try {
            CompletableFuture.supplyAsync(worker::call, executor)
                    .whenComplete((result, throwable) -> {
                        inFlightCount.decrementAndGet();
                        submittedChunks.remove(posKey);

                        if (throwable != null) {
                            LOGGER.warn("Async chunk worker failed for {}: {}",
                                    request.chunkPos(), throwable.getMessage());
                        } else if (result != null && result.success()) {
                            resultCache.put(cacheKey, result);
                        }
                    });
            return true;
        } catch (RejectedExecutionException e) {
            inFlightCount.decrementAndGet();
            submittedChunks.remove(posKey);
            return false;
        }
    }

    /**
     * Look up a cached pre-computed result for a specific dimension + chunkPos.
     * Called by the mixin on fillFromNoise. Removes the result from cache on hit.
     */
    public static ChunkComputeResult pollCachedResult(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        return resultCache.remove(new CacheKey(dimension, chunkPos));
    }

    /**
     * Check if a result is cached for the given position (without removing it).
     */
    public static boolean hasCachedResult(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        return resultCache.containsKey(new CacheKey(dimension, chunkPos));
    }

    /**
     * Clear all cached results for a specific dimension (e.g., when dimension is unloaded).
     */
    public static void clearCacheForDimension(ResourceKey<Level> dimension) {
        resultCache.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension));
    }

    /**
     * Clear all cached results.
     */
    public static void clearAllCaches() {
        resultCache.clear();
    }

    public int getInFlightCount() {
        return inFlightCount.get();
    }

    public boolean isFull() {
        return inFlightCount.get() >= maxInFlight;
    }

    public int getCacheSize() {
        return resultCache.size();
    }

    /**
     * Shut down the pool gracefully. Called on server stop.
     */
    public void shutdown(long timeoutMs) {
        shuttingDown = true;
        LOGGER.info("Parallel Worlds: Shutting down async chunk worker pool ({} in-flight, {} cached)",
                inFlightCount.get(), resultCache.size());

        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Worker pool did not terminate in {}ms, forcing shutdown", timeoutMs);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        submittedChunks.clear();
        resultCache.clear();
    }

    private static long encodeCacheKey(ResourceKey<Level> dimension, ChunkPos pos) {
        return dimension.location().hashCode() * 31L + pos.toLong();
    }

    /**
     * Cache key combining dimension and chunk position.
     */
    private record CacheKey(ResourceKey<Level> dimension, ChunkPos chunkPos) {}
}
