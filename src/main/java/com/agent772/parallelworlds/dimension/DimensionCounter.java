package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists per-dimension-type counters so each exploration dimension gets a unique,
 * sequential name like {@code pw_overworld_0}, {@code pw_overworld_1}, etc.
 */
public final class DimensionCounter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String COUNTER_FILE = "pw_dimension_counters.dat";
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static Path counterPath;
    private static volatile boolean dirty;

    private DimensionCounter() {}

    public static void initialize(MinecraftServer server) {
        counterPath = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(COUNTER_FILE);
        try {
            if (Files.exists(counterPath)) {
                for (String line : Files.readAllLines(counterPath)) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        COUNTERS.put(parts[0].trim(), new AtomicLong(Long.parseLong(parts[1].trim())));
                        LOGGER.info("Loaded counter {}: {}", parts[0].trim(), parts[1].trim());
                    }
                }
            } else {
                Files.createDirectories(counterPath.getParent());
                save();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load dimension counters, starting from 0", e);
            COUNTERS.clear();
        }
    }

    public static long getNextDimensionId(ResourceLocation baseDimension) {
        String type = baseDimension.getPath();
        AtomicLong counter = COUNTERS.computeIfAbsent(type, k -> new AtomicLong(0));
        long id = counter.getAndIncrement();
        dirty = true;
        return id;
    }

    /**
     * Allocate a fresh ID for REGENERATE_EACH_RESTART mode.
     * <p>
     * Reads the last-used (current) ID, advances by 1, then keeps advancing while
     * that folder already exists on disk — this is the crash-proof safety net:
     * even if the counter file was lost entirely, the disk scan will find a clean slot.
     * <p>
     * Saves the allocated ID as the new <em>current</em> (not current+1), so the
     * file always reflects the dimension that is actually in use.
     *
     * @return the dimension number to use (e.g., 1 for {@code pw_overworld_1})
     */
    public static long allocateFreshId(ResourceLocation baseDimension, MinecraftServer server) {
        String type = baseDimension.getPath();
        // No entry means nothing was ever created → treat as -1 so candidate starts at 0.
        long current = COUNTERS.containsKey(type) ? COUNTERS.get(type).get() : -1L;
        long candidate = current + 1;

        // Disk-safety: skip past any IDs whose folders already exist (e.g. after a crash
        // where the counter file was not updated before shutdown).
        Path dimRoot = server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(ParallelWorlds.MOD_ID);
        while (Files.isDirectory(dimRoot.resolve("pw_" + type + "_" + candidate))) {
            LOGGER.warn("Dimension folder pw_{}_{} already exists on disk, advancing to next ID",
                    type, candidate);
            candidate++;
        }

        // Store candidate as the new current (the ID now in use), not candidate+1.
        COUNTERS.put(type, new AtomicLong(candidate));
        dirty = true;
        LOGGER.info("Allocated fresh dimension ID {} for {} (previous: {})", candidate, type, current);
        return candidate;
    }

    public static long getCurrentCounter(ResourceLocation baseDimension) {
        AtomicLong counter = COUNTERS.get(baseDimension.getPath());
        return counter != null ? counter.get() : 0;
    }

    public static void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public static void resetCounters() {
        COUNTERS.clear();
        dirty = true;
        save();
        LOGGER.info("All dimension counters reset");
    }

    public static void resetCounter(ResourceLocation baseDimension) {
        COUNTERS.remove(baseDimension.getPath());
        dirty = true;
        save();
        LOGGER.info("Counter for {} reset", baseDimension.getPath());
    }

    public static Map<String, Long> getAllCounters() {
        Map<String, Long> result = new HashMap<>();
        COUNTERS.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    private static void save() {
        if (counterPath == null) return;
        try {
            Files.createDirectories(counterPath.getParent());
            StringBuilder sb = new StringBuilder();
            COUNTERS.forEach((type, counter) -> sb.append(type).append('=').append(counter.get()).append('\n'));
            Files.writeString(counterPath, sb.toString());
            dirty = false;
            LOGGER.debug("Saved dimension counters");
        } catch (IOException e) {
            LOGGER.error("Failed to save dimension counters", e);
        }
    }
}
