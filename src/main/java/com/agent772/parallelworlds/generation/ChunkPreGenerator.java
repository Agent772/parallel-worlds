package com.agent772.parallelworlds.generation;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.generation.async.AsyncChunkWorkerPool;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active pre-generation tasks. Called every server tick
 * to advance chunk generation with TPS-aware throttling.
 */
public class ChunkPreGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<ResourceKey<Level>, GenerationTask> activeTasks = new ConcurrentHashMap<>();
    private AsyncChunkWorkerPool workerPool;

    /**
     * Set the async worker pool. Tasks created after this call will use it.
     */
    public void setWorkerPool(AsyncChunkWorkerPool pool) {
        this.workerPool = pool;
        // Update existing tasks
        for (GenerationTask task : activeTasks.values()) {
            task.setWorkerPool(pool);
        }
    }

    public String startGeneration(MinecraftServer server, ResourceKey<Level> dim, int radius) {
        if (activeTasks.containsKey(dim)) {
            return "Pre-generation already running for " + dim.location();
        }

        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return "Dimension " + dim.location() + " not found";
        }

        ChunkPos center = new ChunkPos(level.getSharedSpawnPos());
        GenerationTask task = new GenerationTask(dim, radius, center);
        if (workerPool != null) {
            task.setWorkerPool(workerPool);
        }
        activeTasks.put(dim, task);

        long totalChunks = (long) (2 * radius + 1) * (2 * radius + 1);
        LOGGER.info("Started pre-generation for {} with radius {} ({} chunks)", dim.location(), radius, totalChunks);
        return String.format("Started pre-generation for %s: %d chunks (radius %d)", dim.location(), totalChunks, radius);
    }

    public String stopGeneration(ResourceKey<Level> dim, MinecraftServer server) {
        GenerationTask task = activeTasks.remove(dim);
        if (task == null) {
            return "No active pre-generation for " + dim.location();
        }

        ServerLevel level = server.getLevel(dim);
        if (level != null) {
            task.cancel(level);
        }

        LOGGER.info("Stopped pre-generation for {}", dim.location());
        return "Stopped pre-generation for " + dim.location();
    }

    public String pauseGeneration(ResourceKey<Level> dim) {
        GenerationTask task = activeTasks.get(dim);
        if (task == null) {
            return "No active pre-generation for " + dim.location();
        }
        task.pause();
        return "Paused pre-generation for " + dim.location();
    }

    public String resumeGeneration(ResourceKey<Level> dim) {
        GenerationTask task = activeTasks.get(dim);
        if (task == null) {
            return "No active pre-generation for " + dim.location();
        }
        task.resume();
        return "Resumed pre-generation for " + dim.location();
    }

    public void stopAll(MinecraftServer server) {
        for (var entry : activeTasks.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level != null) {
                entry.getValue().cancel(level);
            }
        }
        activeTasks.clear();
    }

    /**
     * Called every server tick to advance all active tasks.
     * Enforces a global time budget across all dimensions so that
     * multiple concurrent pre-gen tasks share the configured tick budget
     * rather than each consuming it independently.
     */
    public void tick(MinecraftServer server) {
        if (activeTasks.isEmpty()) return;

        long globalStartNanos = System.nanoTime();
        long globalBudgetNanos = PWConfig.getPregenMaxTickMs() * 1_000_000L;

        var iterator = activeTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            GenerationTask task = entry.getValue();

            if (task.isComplete()) {
                iterator.remove();
                continue;
            }

            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                LOGGER.warn("Dimension {} no longer exists, removing pregen task", entry.getKey().location());
                iterator.remove();
                continue;
            }

            long remainingNanos = globalBudgetNanos - (System.nanoTime() - globalStartNanos);
            if (remainingNanos <= 0) break;

            task.processNextBatch(level, remainingNanos);
        }
    }

    /**
     * Resume tasks from saved data after a restart.
     */
    public void resumeSavedTasks(MinecraftServer server) {
        PWSavedData data = PWSavedData.get(server);
        ListTag taskList = data.getPregenTasks();

        if (taskList == null || taskList.isEmpty()) return;

        for (int i = 0; i < taskList.size(); i++) {
            try {
                GenerationTask task = GenerationTask.fromNbt(taskList.getCompound(i));
                if (task.getProgress().getStatus() != GenerationProgress.Status.COMPLETED) {
                    ServerLevel level = server.getLevel(task.getDimensionKey());
                    if (level != null) {
                        activeTasks.put(task.getDimensionKey(), task);
                        LOGGER.info("Resumed pregen task for {} at {}%",
                                task.getDimensionKey().location(),
                                String.format("%.1f", task.getProgress().getProgressPercent()));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to resume pregen task from saved data", e);
            }
        }

        // Clear saved tasks after loading
        data.savePregenTasks(new ListTag());
    }

    public String getStatus(ResourceKey<Level> dim) {
        GenerationTask task = activeTasks.get(dim);
        if (task == null) {
            return "No active pre-generation for " + dim.location();
        }

        GenerationProgress p = task.getProgress();
        return String.format("%s: %s — %.1f%% (%d/%d chunks, %d new) | %.0f chunks/min | ETA: %.1f min",
                dim.location(),
                p.getStatus().name(),
                p.getProgressPercent(),
                p.getChunksGenerated(),
                p.getSpiralIterator().getTotalChunks(),
                p.getChunksActuallyGenerated(),
                p.getGenerationRate(),
                p.getEstimatedMinutesRemaining());
    }

    public boolean hasActiveTasks() {
        return !activeTasks.isEmpty();
    }

    /**
     * Save all task progress and cancel. Called on server shutdown.
     */
    public void shutdown(MinecraftServer server) {
        if (activeTasks.isEmpty()) return;

        // Save progress to PWSavedData
        PWSavedData data = PWSavedData.get(server);
        ListTag taskList = new ListTag();
        for (GenerationTask task : activeTasks.values()) {
            if (!task.isComplete()) {
                task.pause(); // mark as paused so it resumes on next start
                taskList.add(task.toNbt());
            }
        }
        data.savePregenTasks(taskList);

        // Cancel all tasks
        stopAll(server);
        LOGGER.info("Saved {} pregen tasks for next startup", taskList.size());
    }
}
