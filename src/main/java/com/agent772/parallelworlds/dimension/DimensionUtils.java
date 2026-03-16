package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Naming-convention-based helpers for identifying exploration dimensions.
 * No runtime state required — purely pattern-based.
 */
public final class DimensionUtils {

    private DimensionUtils() {}

    /**
     * Exploration dimensions are in our namespace and start with {@code pw_}.
     */
    public static boolean isExplorationDimension(ResourceKey<?> key) {
        ResourceLocation loc = key.location();
        return loc.getNamespace().equals(ParallelWorlds.MOD_ID)
                && loc.getPath().startsWith("pw_");
    }

    public static boolean isExplorationDimension(ResourceLocation loc) {
        return loc.getNamespace().equals(ParallelWorlds.MOD_ID)
                && loc.getPath().startsWith("pw_");
    }

    /**
     * Check if a world-save folder name matches our exploration pattern.
     */
    public static boolean isExplorationDimensionFolder(String folderName) {
        return folderName.startsWith("pw_");
    }
}
