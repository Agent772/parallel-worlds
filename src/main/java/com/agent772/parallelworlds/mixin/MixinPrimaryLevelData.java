package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.accessor.IWorldDataAccessor;
import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PrimaryLevelData.class, priority = 900)
public class MixinPrimaryLevelData implements IWorldDataAccessor {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();

    @Shadow @Mutable
    private WorldOptions worldOptions;

    @Override
    public WorldOptions pw$getWorldOptions() {
        return this.worldOptions;
    }

    @Override
    public void pw$setWorldOptions(WorldOptions options) {
        if (ExplorationSeedManager.isWorldOptionsGuardActive()) {
            WorldOptions original = ExplorationSeedManager.getOriginalWorldOptions();
            if (original != null && options.seed() != original.seed()) {
                pw$LOGGER.warn("Blocked worldOptions write during guarded operation " +
                        "(attempted seed={}, keeping seed={})", options.seed(), original.seed());
                return;
            }
        }
        this.worldOptions = options;
    }

    /**
     * Intercept worldGenOptions() reads to detect and fix contamination while the
     * write guard is active. If the field has been overwritten with a different seed
     * (e.g. by vanilla code during ServerLevel construction), restore the original
     * and return it instead.
     */
    @Inject(method = "worldGenOptions", at = @At("HEAD"), cancellable = true)
    private void pw$protectWorldGenOptions(CallbackInfoReturnable<WorldOptions> cir) {
        if (ExplorationSeedManager.isWorldOptionsGuardActive()) {
            WorldOptions original = ExplorationSeedManager.getOriginalWorldOptions();
            if (original != null && this.worldOptions.seed() != original.seed()) {
                pw$LOGGER.warn("WorldOptions contamination detected — field seed={}, expected seed={} — restoring",
                        this.worldOptions.seed(), original.seed());
                this.worldOptions = original;
                cir.setReturnValue(original);
            }
        }
    }
}
