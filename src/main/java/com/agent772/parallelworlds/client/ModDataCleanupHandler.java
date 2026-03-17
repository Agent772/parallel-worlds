package com.agent772.parallelworlds.client;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.network.payload.DimensionCleanupPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Client-side handler that cleans up cached data from third-party mapping/LOD mods
 * when exploration dimensions are deleted or regenerated.
 */
public final class ModDataCleanupHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern VALID_DIM_PATH = Pattern.compile("^pw_\\w+_\\d+$");

    private ModDataCleanupHandler() {}

    public static void onDimensionCleanupReceived(DimensionCleanupPayload payload, IPayloadContext context) {
        if (!PWConfig.isModCompatCleanupEnabled()) return;

        context.enqueueWork(() -> {
            List<String> safePaths = payload.deletedDimensionPaths().stream()
                    .filter(ModDataCleanupHandler::isValidDimPath)
                    .toList();

            if (safePaths.isEmpty()) return;

            // Active paths let the client scope cleanup to the current server's folder only.
            List<String> safeActivePaths = payload.activeDimensionPaths().stream()
                    .filter(ModDataCleanupHandler::isValidDimPath)
                    .toList();

            LOGGER.info("Received cleanup notification for {} dimension(s) ({} currently active)",
                    safePaths.size(), safeActivePaths.size());

            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();

            cleanupXaeroWorldMap(gameDir, safePaths, safeActivePaths);
            cleanupXaeroMinimap(gameDir, safePaths, safeActivePaths);
            cleanupJourneyMap(gameDir, safePaths, safeActivePaths);
            cleanupDistantHorizons(gameDir, safePaths, safeActivePaths);
        });
    }

    private static boolean isValidDimPath(String dimPath) {
        return dimPath != null && VALID_DIM_PATH.matcher(dimPath).matches();
    }

    private static boolean isModLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Xaero's World Map ──

    private static void cleanupXaeroWorldMap(Path gameDir, List<String> deletedPaths, List<String> activePaths) {
        if (!isModLoaded("xaeroworldmap")) return;

        // New (1.20+): xaero/world-map/<server>/namespace$path
        cleanupScoped(gameDir.resolve("xaero").resolve("world-map"),
                toFolders(activePaths,  p -> ParallelWorlds.MOD_ID + "$" + p),
                toFolders(deletedPaths, p -> ParallelWorlds.MOD_ID + "$" + p),
                "Xaero's World Map");
        // Old (pre-1.20): XaeroWorldMap/<server>/DIM%namespace%path
        cleanupScoped(gameDir.resolve("XaeroWorldMap"),
                toFolders(activePaths,  p -> "DIM%" + ParallelWorlds.MOD_ID + "%" + p),
                toFolders(deletedPaths, p -> "DIM%" + ParallelWorlds.MOD_ID + "%" + p),
                "Xaero's World Map (legacy)");
    }

    // ── Xaero's Minimap ──

    private static void cleanupXaeroMinimap(Path gameDir, List<String> deletedPaths, List<String> activePaths) {
        if (!isModLoaded("xaerominimap")) return;

        // New (1.20+): xaero/minimap/<server>/namespace$path
        cleanupScoped(gameDir.resolve("xaero").resolve("minimap"),
                toFolders(activePaths,  p -> ParallelWorlds.MOD_ID + "$" + p),
                toFolders(deletedPaths, p -> ParallelWorlds.MOD_ID + "$" + p),
                "Xaero's Minimap");
        // Old (pre-1.20): XaeroWaypoints/<server>/dim%namespace%path
        cleanupScoped(gameDir.resolve("XaeroWaypoints"),
                toFolders(activePaths,  p -> "dim%" + ParallelWorlds.MOD_ID + "%" + p),
                toFolders(deletedPaths, p -> "dim%" + ParallelWorlds.MOD_ID + "%" + p),
                "Xaero's Minimap (legacy)");
    }

    /**
     * Find server directories under {@code root} that contain at least one of the given
     * dim folder names as a direct child directory — used to scope cleanup to the
     * current server and avoid touching other servers that may share the same dim IDs.
     */
    private static Set<Path> findServerDirsContaining(Path root, List<String> dimFolders) {
        if (!Files.isDirectory(root)) return Set.of();
        Set<Path> found = new LinkedHashSet<>();
        try (Stream<Path> servers = Files.list(root)) {
            for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
                for (String dim : dimFolders) {
                    if (Files.isDirectory(serverDir.resolve(dim))) {
                        found.add(serverDir);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan {} for dim folders", root, e);
        }
        return found;
    }

    /**
     * Delete each folder in {@code deletedFolders} from server directories scoped to
     * the current Minecraft connection.
     * <p>
     * Primary scope: server dirs that contain one of the {@code activeFolders} (the
     * currently registered exploration dims — only present for the correct server).
     * Fallback scope: server dirs containing a deleted dim folder — used when the
     * player hasn't visited the new active dim yet so no active folder exists in Xaero.
     * If neither yields results the cleanup is skipped silently.
     */
    private static void cleanupScoped(Path root, List<String> activeFolders,
                                       List<String> deletedFolders, String label) {
        Set<Path> scopedDirs = findServerDirsContaining(root, activeFolders);
        if (scopedDirs.isEmpty()) {
            scopedDirs = findServerDirsContaining(root, deletedFolders);
            if (!scopedDirs.isEmpty()) {
                LOGGER.debug("[{}] Active dim folder not yet mapped; scoping by deleted dim presence (fallback)", label);
            }
        }
        if (scopedDirs.isEmpty()) {
            LOGGER.debug("[{}] No matching server directories found, skipping cleanup", label);
            return;
        }
        for (Path serverDir : scopedDirs) {
            for (String delFolder : deletedFolders) {
                Path target = serverDir.resolve(delFolder);
                if (Files.isDirectory(target)) {
                    LOGGER.info("Cleaning up {} data: {}", label, target);
                    deleteRecursively(target);
                }
            }
        }
    }

    private static List<String> toFolders(List<String> dimPaths, Function<String, String> formatter) {
        return dimPaths.stream().map(formatter).toList();
    }

    // ── JourneyMap ──

    private static void cleanupJourneyMap(Path gameDir, List<String> deletedPaths, List<String> activePaths) {
        if (!isModLoaded("journeymap")) return;

        // JourneyMap layout: journeymap/data/{mp,sp}/<server-or-world-uuid>/<modid~dimpath>/
        // The UUID folder is the "server dir" level, so we must root cleanupScoped at the
        // mp/sp layer — not at journeymap/data — so findServerDirsContaining sees the UUID
        // dirs and checks for dim folders as their direct children.
        Path jmData = gameDir.resolve("journeymap").resolve("data");
        List<String> activeJmFolders  = toFolders(activePaths,  p -> ParallelWorlds.MOD_ID + "~" + p);
        List<String> deletedJmFolders = toFolders(deletedPaths, p -> ParallelWorlds.MOD_ID + "~" + p);
        for (String type : List.of("mp", "sp")) {
            Path typeDir = jmData.resolve(type);
            if (Files.isDirectory(typeDir)) {
                cleanupScoped(typeDir, activeJmFolders, deletedJmFolders, "JourneyMap (" + type + ")");
            }
        }
    }

    // ── Distant Horizons ──

    private static void cleanupDistantHorizons(Path gameDir, List<String> deletedPaths, List<String> activePaths) {
        if (!isModLoaded("distanthorizons")) return;

        Path dhRoot = gameDir.resolve("config").resolve("DistantHorizons").resolve("data");
        if (!Files.isDirectory(dhRoot)) return;

        // DH uses substring matching for dim entries. Build pattern lists for both
        // variant separators DH uses (% and _).
        List<String> activePatterns  = dhPatterns(activePaths);
        List<String> deletedPatterns = dhPatterns(deletedPaths);

        // Scope to the current server's directory.
        Set<Path> scopedDirs = findServerDirsContainingEntry(dhRoot, activePatterns);
        if (scopedDirs.isEmpty()) {
            scopedDirs = findServerDirsContainingEntry(dhRoot, deletedPatterns);
            if (!scopedDirs.isEmpty()) {
                LOGGER.debug("[Distant Horizons] Active dim not yet mapped; scoping by deleted dim presence (fallback)");
            }
        }
        if (scopedDirs.isEmpty()) {
            LOGGER.debug("[Distant Horizons] No matching server directories, skipping cleanup");
            return;
        }
        for (Path serverDir : scopedDirs) {
            for (String pattern : deletedPatterns) {
                try {
                    deleteMatchingEntries(serverDir, pattern);
                } catch (IOException e) {
                    LOGGER.warn("Failed to cleanup Distant Horizons data for pattern {}", pattern, e);
                }
            }
        }
    }

    /** Produce both separator variants DH can use for a list of dim paths. */
    private static List<String> dhPatterns(List<String> dimPaths) {
        List<String> result = new ArrayList<>(dimPaths.size() * 2);
        for (String p : dimPaths) {
            result.add(ParallelWorlds.MOD_ID + "%" + p);
            result.add(ParallelWorlds.MOD_ID + "_" + p);
        }
        return result;
    }

    /** Find server dirs where any direct child entry's filename contains one of the patterns. */
    private static Set<Path> findServerDirsContainingEntry(Path root, List<String> patterns) {
        if (!Files.isDirectory(root)) return Set.of();
        Set<Path> found = new LinkedHashSet<>();
        try (Stream<Path> servers = Files.list(root)) {
            for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
                try (Stream<Path> entries = Files.list(serverDir)) {
                    boolean matches = entries.anyMatch(e ->
                            patterns.stream().anyMatch(p -> e.getFileName().toString().contains(p)));
                    if (matches) found.add(serverDir);
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan {} for DH dim entries", root, e);
        }
        return found;
    }

    private static void deleteMatchingEntries(Path parent, String pattern) throws IOException {
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().contains(pattern)) {
                    LOGGER.info("Cleaning up Distant Horizons data: {}", entry);
                    if (Files.isDirectory(entry)) {
                        deleteRecursively(entry);
                    } else {
                        Files.deleteIfExists(entry);
                    }
                }
            }
        }
    }

    // ── Utility ──

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { LOGGER.warn("Could not delete {}", p, e); }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to walk directory for deletion: {}", root, e);
        }
    }
}
