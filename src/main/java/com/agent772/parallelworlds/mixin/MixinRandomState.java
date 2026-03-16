package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Intercepts RandomState.create() to replace the seed with our custom exploration seed
 * when a dimension is being constructed.
 */
@Mixin(RandomState.class)
public class MixinRandomState {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();

    /**
     * Modify seed for the create(NoiseGeneratorSettings, HolderGetter, long) overload.
     */
    @ModifyVariable(
            method = "create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2
    )
    private static long pw$modifySeed(long seed) {
        return pw$interceptSeed(seed);
    }

    /**
     * Modify seed for the create(HolderGetter.Provider, ResourceKey, long) overload.
     */
    @ModifyVariable(
            method = "create(Lnet/minecraft/core/HolderGetter$Provider;Lnet/minecraft/resources/ResourceKey;J)Lnet/minecraft/world/level/levelgen/RandomState;",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2
    )
    private static long pw$modifySeedProvider(long seed) {
        return pw$interceptSeed(seed);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static long pw$interceptSeed(long seed) {
        ResourceKey<Level> current = ExplorationSeedManager.getCurrentDimension();
        if (current == null) return seed;

        if (!ExplorationSeedManager.isExplorationDimension(current)) {
            pw$LOGGER.warn("RandomState context has non-exploration dimension: {}", current.location());
            return seed;
        }

        Optional<Long> custom = ExplorationSeedManager.getSeedForDimension(current);
        if (custom.isPresent()) {
            pw$LOGGER.info("Using registered seed {} for {} (original {})",
                    custom.get(), current.location(), seed);
            return custom.get();
        }

        long modified = ExplorationSeedManager.modifySeed(current, seed);
        pw$LOGGER.info("Modified seed for {}: {} -> {}", current.location(), seed, modified);
        return modified;
    }
}
