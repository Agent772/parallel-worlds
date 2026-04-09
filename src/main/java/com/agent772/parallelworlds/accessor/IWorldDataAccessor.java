package com.agent772.parallelworlds.accessor;

import net.minecraft.world.level.levelgen.WorldOptions;

/**
 * Accessor interface for PrimaryLevelData — allows reading and restoring
 * the world generation options (specifically the seed) after runtime
 * dimension construction to prevent seed leaks.
 */
public interface IWorldDataAccessor {

    WorldOptions pw$getWorldOptions();

    void pw$setWorldOptions(WorldOptions options);
}
