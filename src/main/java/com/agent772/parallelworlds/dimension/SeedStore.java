package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.config.PWConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for exploration dimension seeds.
 * <p>
 * Stored as {@code data/pw_seeds.dat} in the world root — a plain text file
 * separate from SavedData so it is written atomically at startup before any
 * dimension is created.  The file is the only place that decides what seed a
 * dimension uses; all other code just calls {@link #getSeed}.
 * <p>
 * <h3>File format (one entry per dimension):</h3>
 * <pre>
 *   minecraft:overworld=9876543210:1710000000
 *   #                   ^seed      ^createdAt (epoch-second)
 * </pre>
 * <h3>Rotation logic:</h3>
 * Each seed stores the epoch-second it was created. On startup the system
 * checks whether the next scheduled reset point (per config: daily/weekly/monthly)
 * has passed since that seed's creation time. If yes the seed is regenerated and
 * its {@code createdAt} is updated to now, which resets the clock for the next cycle.
 * <p>
 * This per-seed timestamp avoids the single-global-timestamp pitfall: newly
 * generated seeds start their rotation clock from the moment they are created,
 * so a fresh install never triggers an immediate spurious rotation the next restart.
 * <h3>Startup sequence (called via {@link #initializeAndRotate}):</h3>
 * <h3>Crash-safety (no boolean flags needed):</h3>
 * {@code DimensionRegistrar} stores a {@code dimensionRegisteredAt} timestamp in
 * {@code PWSavedData} each time it creates a new exploration dimension.  On every
 * startup it compares:
 * <pre>
 *   seed.createdAt  vs  dimension.registeredAt
 * </pre>
 * If the seed is newer the dimension was never recreated with that seed → recreate.
 * Because both values are plain timestamps written independently, a crash between the
 * two writes simply leaves them inconsistent, which is detected correctly on the next
 * startup without any extra flags.
 * <h3>Startup sequence (called via {@link #initializeAndRotate}):</h3>
 * <ol>
 *   <li>Load existing file (seeds + per-seed {@code createdAt} timestamps).</li>
 *   <li>For each enabled base dimension:
 *       <ul>
 *         <li>If seed is missing → generate new seed anchored to now.</li>
 *         <li>If seed exists and rotation is due ({@code isRotationDue(createdAt)}) → rotate.</li>
 *         <li>Otherwise keep the existing seed.</li>
 *       </ul>
 *   </li>
 *   <li>Write the file immediately so the new timestamps survive any subsequent crash.</li>
 * </ol>
 */
public final class SeedStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SEED_FILE = "pw_seeds.dat";

    /** Per-seed data: the seed value and when it was generated. */
    private record SeedEntry(long seed, long createdAt) {}

    private static Path seedPath;
    private static final Map<ResourceLocation, SeedEntry> seeds = new HashMap<>();

    private SeedStore() {}

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Load seeds from disk, rotate any that are due, write back immediately.
     * Must be called once at server startup before any dimension creation.
     */
    public static void initializeAndRotate(MinecraftServer server) {
        seedPath = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(SEED_FILE);

        seeds.clear();
        loadFromDisk();

        long now = SeedManager.currentEpochSecond();
        List<String> enabled = PWConfig.getEnabledDimensions();
        for (String dimStr : enabled) {
            ResourceLocation dim = ResourceLocation.tryParse(dimStr);
            if (dim == null) continue;
            SeedEntry existing = seeds.get(dim);
            if (existing == null) {
                // First time for this dimension — createdAt = now anchors the rotation clock.
                long seed = SeedManager.generateSeed();
                seeds.put(dim, new SeedEntry(seed, now));
                LOGGER.info("[SeedStore] {} — first run, generated seed {}, createdAt={}, nextReset={}",
                        dim, seed, SeedManager.formatEpoch(now), SeedManager.nextResetFormatted(now));
            } else if (SeedManager.isRotationDue(existing.createdAt())) {
                long seed = SeedManager.generateSeed();
                seeds.put(dim, new SeedEntry(seed, now));
                LOGGER.info("[SeedStore] {} — ROTATING: old createdAt={}, nextReset={} (past), new seed={}, new createdAt={}",
                        dim, SeedManager.formatEpoch(existing.createdAt()),
                        SeedManager.nextResetFormatted(existing.createdAt()),
                        seed, SeedManager.formatEpoch(now));
            } else {
                LOGGER.info("[SeedStore] {} — keeping seed={}, createdAt={}, nextReset={}",
                        dim, existing.seed(), SeedManager.formatEpoch(existing.createdAt()),
                        SeedManager.nextResetFormatted(existing.createdAt()));
            }
        }

        saveToDisk();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public static Optional<Long> getSeed(ResourceLocation baseDim) {
        SeedEntry e = seeds.get(baseDim);
        return e == null ? Optional.empty() : Optional.of(e.seed());
    }

    /** Returns the epoch-second when the seed for {@code baseDim} was created, or 0 if not loaded. */
    public static long getSeedCreatedAt(ResourceLocation baseDim) {
        SeedEntry e = seeds.get(baseDim);
        return e == null ? 0 : e.createdAt();
    }

// ── Admin: force rotation ─────────────────────────────────────────────────

    /**
     * Force-rotate a single dimension's seed (e.g., from an admin command).
     * Writes the file immediately.
     */
    public static long forceRotateSeed(ResourceLocation baseDim) {
        long seed = SeedManager.generateSeed();
        seeds.put(baseDim, new SeedEntry(seed, SeedManager.currentEpochSecond()));
        saveToDisk();
        LOGGER.info("Force-rotated seed for {} → {}", baseDim, seed);
        return seed;
    }

    /**
     * Force-rotate seeds for all enabled dimensions and reset each seed's creation timestamp.
     * Writes the file immediately.
     */
    public static void forceRotateAll() {
        long now = SeedManager.currentEpochSecond();
        for (String dimStr : PWConfig.getEnabledDimensions()) {
            ResourceLocation dim = ResourceLocation.tryParse(dimStr);
            if (dim == null) continue;
            long seed = SeedManager.generateSeed();
            seeds.put(dim, new SeedEntry(seed, now));
            LOGGER.info("Force-rotated seed for {} → {}", dim, seed);
        }
        saveToDisk();
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private static void loadFromDisk() {
        if (!Files.exists(seedPath)) {
            LOGGER.info("No seed store found at {}, will create on first save", seedPath);
            return;
        }
        try {
            for (String line : Files.readAllLines(seedPath)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].strip();
                String value = parts[1].strip();
                ResourceLocation dim = ResourceLocation.tryParse(key);
                if (dim == null) continue;
                // Format: seed:createdAt  (legacy: plain seed with no colon → treat createdAt as 0,
                // which causes isRotationDue to return true and assign a fresh timestamp)
                String[] seedParts = value.split(":", 2);
                long seed = Long.parseLong(seedParts[0].strip());
                long createdAt = seedParts.length > 1 ? Long.parseLong(seedParts[1].strip()) : 0L;
                seeds.put(dim, new SeedEntry(seed, createdAt));
            }
            LOGGER.info("Loaded seed store: {} seeds", seeds.size());
        } catch (IOException | NumberFormatException e) {
            LOGGER.error("Failed to read seed store, starting fresh", e);
            seeds.clear();
        }
    }

    private static void saveToDisk() {
        if (seedPath == null) return;
        try {
            Files.createDirectories(seedPath.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Parallel Worlds seed store — managed automatically, do not edit manually\n");
            sb.append("# Format: namespace:path=seed:createdAt\n");
            seeds.forEach((dim, entry) ->
                    sb.append(dim).append('=').append(entry.seed()).append(':').append(entry.createdAt()).append('\n'));
            // Write atomically via temp file to avoid corrupt reads on crash
            Path tmp = seedPath.resolveSibling(SEED_FILE + ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, seedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.debug("Seed store saved ({} seeds)", seeds.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save seed store", e);
        }
    }

    /** Called on server stopped to clear in-memory state. */
    public static void clearAll() {
        seeds.clear();
        seedPath = null;
    }
}
