package com.agent772.parallelworlds.util;

import net.minecraft.world.level.dimension.DimensionType;

/**
 * Classifies a dimension type into one of three environment categories
 * to determine safe-spawn strategy.
 */
public enum DimensionEnvironment {
    OVERWORLD_LIKE,
    NETHER_LIKE,
    END_LIKE;

    /**
     * Classify a dimension type based on its properties.
     * <ul>
     *   <li>ultraWarm → NETHER_LIKE (has ceiling, lava oceans)</li>
     *   <li>fixedTime + no ceiling → END_LIKE (open void, islands)</li>
     *   <li>everything else → OVERWORLD_LIKE (normal surface)</li>
     * </ul>
     */
    public static DimensionEnvironment classify(DimensionType type) {
        if (type.ultraWarm()) {
            return NETHER_LIKE;
        }
        if (type.fixedTime().isPresent() && !type.hasCeiling()) {
            return END_LIKE;
        }
        return OVERWORLD_LIKE;
    }
}
