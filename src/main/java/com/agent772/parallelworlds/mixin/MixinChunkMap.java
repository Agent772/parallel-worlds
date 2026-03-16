package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents chunk saving for exploration dimensions when preventDiskSaves is enabled.
 */
@Mixin(ChunkMap.class)
public class MixinChunkMap {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();
    @Shadow @Final ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"), cancellable = true)
    private void pw$preventChunkSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (PWConfig.isPreventDiskSaves()
                && ExplorationSeedManager.isExplorationDimension(level.dimension())) {
            cir.setReturnValue(false);
        }
    }
}
