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
import java.util.Comparator;
import java.util.List;
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

            LOGGER.info("Received cleanup notification for {} dimensions", safePaths.size());

            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();

            for (String dimPath : safePaths) {
                cleanupXaeroWorldMap(gameDir, dimPath);
                cleanupXaeroMinimap(gameDir, dimPath);
                cleanupJourneyMap(gameDir, dimPath);
                cleanupDistantHorizons(gameDir, dimPath);
            }
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

    private static void cleanupXaeroWorldMap(Path gameDir, String dimPath) {
        if (!isModLoaded("xaeroworldmap")) return;

        Path xaeroRoot = gameDir.resolve("XaeroWorldMap");
        if (!Files.isDirectory(xaeroRoot)) return;

        String xaeroDimFolder = "DIM%" + ParallelWorlds.MOD_ID + "%" + dimPath;

        try (Stream<Path> servers = Files.list(xaeroRoot)) {
            for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
                Path target = serverDir.resolve(xaeroDimFolder);
                if (Files.isDirectory(target)) {
                    LOGGER.info("Cleaning up Xaero's World Map data: {}", target);
                    deleteRecursively(target);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup Xaero's World Map data for {}", dimPath, e);
        }
    }

    // ── Xaero's Minimap ──

    private static void cleanupXaeroMinimap(Path gameDir, String dimPath) {
        if (!isModLoaded("xaerominimap")) return;

        Path xaeroRoot = gameDir.resolve("XaeroWaypoints");
        if (!Files.isDirectory(xaeroRoot)) return;

        String xaeroDimFolder = "dim%" + ParallelWorlds.MOD_ID + "%" + dimPath;

        try (Stream<Path> servers = Files.list(xaeroRoot)) {
            for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
                Path target = serverDir.resolve(xaeroDimFolder);
                if (Files.isDirectory(target)) {
                    LOGGER.info("Cleaning up Xaero's Minimap data: {}", target);
                    deleteRecursively(target);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup Xaero's Minimap data for {}", dimPath, e);
        }
    }

    // ── JourneyMap ──

    private static void cleanupJourneyMap(Path gameDir, String dimPath) {
        if (!isModLoaded("journeymap")) return;

        Path jmRoot = gameDir.resolve("journeymap").resolve("data");
        if (!Files.isDirectory(jmRoot)) return;

        String jmDimFolder = ParallelWorlds.MOD_ID + "~" + dimPath;

        try (Stream<Path> serverTypes = Files.list(jmRoot)) {
            for (Path serverTypeDir : serverTypes.filter(Files::isDirectory).toList()) {
                Path target = serverTypeDir.resolve(jmDimFolder);
                if (Files.isDirectory(target)) {
                    LOGGER.info("Cleaning up JourneyMap data: {}", target);
                    deleteRecursively(target);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup JourneyMap data for {}", dimPath, e);
        }
    }

    // ── Distant Horizons ──

    private static void cleanupDistantHorizons(Path gameDir, String dimPath) {
        if (!isModLoaded("distanthorizons")) return;

        Path dhRoot = gameDir.resolve("config").resolve("DistantHorizons").resolve("data");
        if (!Files.isDirectory(dhRoot)) return;

        String dhDimName = ParallelWorlds.MOD_ID + "%" + dimPath;
        String dhDimNameAlt = ParallelWorlds.MOD_ID + "_" + dimPath;

        try (Stream<Path> serverDirs = Files.list(dhRoot)) {
            for (Path serverDir : serverDirs.filter(Files::isDirectory).toList()) {
                deleteMatchingEntries(serverDir, dhDimName);
                deleteMatchingEntries(serverDir, dhDimNameAlt);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup Distant Horizons data for {}", dimPath, e);
        }
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
