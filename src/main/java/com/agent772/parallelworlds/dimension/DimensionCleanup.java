package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cleans up old exploration dimension folders based on retention config.
 */
public final class DimensionCleanup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern PW_FOLDER_PATTERN = Pattern.compile("pw_(\\w+)_(\\d+)");

    private DimensionCleanup() {}

    /**
     * Scan the {@code dimensions/parallelworlds/} folder, group by dimension type,
     * sort by modification time, and delete the oldest beyond the retention count.
     *
     * @return list of deleted dimension folder names (e.g. "pw_overworld_0")
     */
    public static List<String> cleanupOldDimensions(MinecraftServer server) {
        List<String> deleted = new ArrayList<>();
        int retention = PWConfig.getOldDimensionRetentionCount();
        if (retention < 0) {
            LOGGER.info("Dimension retention set to -1, keeping all old dimensions");
            return deleted;
        }

        Path dimensionsRoot = server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(ParallelWorlds.MOD_ID);

        if (!Files.isDirectory(dimensionsRoot)) {
            LOGGER.debug("No dimensions folder found, nothing to clean up");
            return deleted;
        }

        try (Stream<Path> dirs = Files.list(dimensionsRoot)) {
            // Group folders by dimension type (e.g. "overworld", "the_nether")
            Map<String, List<Path>> grouped = new HashMap<>();
            for (Path dir : dirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                String name = dir.getFileName().toString();
                var matcher = PW_FOLDER_PATTERN.matcher(name);
                if (matcher.matches()) {
                    grouped.computeIfAbsent(matcher.group(1), k -> new ArrayList<>()).add(dir);
                }
            }

            for (var entry : grouped.entrySet()) {
                List<Path> folders = entry.getValue();
                if (folders.size() <= retention) continue;

                // Sort oldest first by last-modified time
                folders.sort(Comparator.comparingLong(p -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { return 0L; }
                }));

                int toDelete = folders.size() - retention;
                for (int i = 0; i < toDelete; i++) {
                    Path folder = folders.get(i);
                    String folderName = folder.getFileName().toString();

                    // Skip folders currently loaded as recovery dims to prevent data loss
                    ResourceLocation recoveryId = ResourceLocation.fromNamespaceAndPath(
                            ParallelWorlds.MOD_ID, folderName);
                    ResourceKey<Level> recoveryKey = ResourceKey.create(Registries.DIMENSION, recoveryId);
                    if (RecoveryDimensionManager.getInstance().isRecoveryDimension(recoveryKey)) {
                        LOGGER.info("Skipping deletion of {} — currently loaded as recovery dimension", folderName);
                        continue;
                    }

                    LOGGER.info("Deleting old exploration dimension folder: {}", folder);
                    deleteRecursively(folder);
                    deleted.add(folderName);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to clean up old dimensions", e);
        }

        // Record deleted dimensions for client cleanup notifications
        if (!deleted.isEmpty()) {
            PWSavedData data = PWSavedData.get(server);
            deleted.forEach(data::addPendingCleanup);
        }

        return deleted;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            // Delete files first (reverse order), then directories
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOGGER.warn("Could not delete {}", p, e); }
            });
        }
    }
}
