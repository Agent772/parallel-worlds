package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the {@code dimensions/parallelworlds/} folder for exploration dimension
 * folders that exist on disk but are NOT currently registered as active runtime
 * dimensions in {@link DimensionRegistrar}.
 *
 * <p>Does <em>not</em> filter out folders that are already loaded as recovery dims;
 * callers are responsible for that additional filtering.
 */
public final class RecoveryScanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final Pattern PW_FOLDER_PATTERN = Pattern.compile("pw_(\\w+)_(\\d+)");

    private RecoveryScanner() {}

    /**
     * A dimension folder found on disk that is not currently an active exploration dim.
     *
     * @param folderName     e.g. {@code pw_overworld_3}
     * @param dimType        e.g. {@code overworld}
     * @param dimId          e.g. {@code 3}
     * @param dimensionId    {@code parallelworlds:pw_overworld_3}
     * @param lastModifiedMs last-modified timestamp of the folder in epoch millis
     */
    public record RecoveryCandidate(
            String folderName,
            String dimType,
            int dimId,
            ResourceLocation dimensionId,
            long lastModifiedMs
    ) {}

    /**
     * Return all dim folders on disk that are <em>not</em> currently active in
     * {@link DimensionRegistrar}, sorted newest-first by last-modified time.
     */
    public static List<RecoveryCandidate> scanInactiveDimensions(MinecraftServer server) {
        Path dimensionsRoot = server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(ParallelWorlds.MOD_ID);

        if (!Files.isDirectory(dimensionsRoot)) {
            return List.of();
        }

        Set<ResourceLocation> activeIds = DimensionRegistrar.getExplorationDimensionIds();
        List<RecoveryCandidate> candidates = new ArrayList<>();

        try (Stream<Path> dirs = Files.list(dimensionsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                String name = dir.getFileName().toString();
                Matcher matcher = PW_FOLDER_PATTERN.matcher(name);
                if (!matcher.matches()) continue;

                String dimType = matcher.group(1);
                int dimId;
                try {
                    dimId = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }

                ResourceLocation dimLoc = ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, name);

                // Skip currently active exploration dims
                if (activeIds.contains(dimLoc)) continue;

                long lastModified = 0L;
                try {
                    lastModified = Files.getLastModifiedTime(dir).toMillis();
                } catch (IOException ignored) {}

                candidates.add(new RecoveryCandidate(name, dimType, dimId, dimLoc, lastModified));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan for inactive dimensions", e);
        }

        // Newest first so the list command shows the most recently-used dims at the top
        candidates.sort(Comparator.comparingLong(RecoveryCandidate::lastModifiedMs).reversed());
        return candidates;
    }

    /**
     * Find a specific inactive candidate by its exact folder name, or empty if not found
     * (including if the folder is currently active in DimensionRegistrar).
     */
    public static Optional<RecoveryCandidate> findCandidate(MinecraftServer server, String folderName) {
        return scanInactiveDimensions(server).stream()
                .filter(c -> c.folderName().equals(folderName))
                .findFirst();
    }

    /**
     * Check whether a given folder name exists on disk at all (active or inactive).
     */
    public static boolean folderExists(MinecraftServer server, String folderName) {
        Path dimensionsRoot = server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(ParallelWorlds.MOD_ID);
        return Files.isDirectory(dimensionsRoot.resolve(folderName));
    }
}
