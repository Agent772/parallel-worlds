package com.agent772.parallelworlds.mixin;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for StructureManager's private 'level' field.
 * Used by MixinNoiseBasedChunkGenerator to determine the dimension
 * when checking for pre-computed noise results.
 */
@Mixin(StructureManager.class)
public interface IStructureManagerAccessor {
    @Accessor("level")
    LevelAccessor pw$getLevel();
}
