package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.ExplorationSeedManager;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.Executor;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();
    @Unique private boolean pw$isRuntimeDimension;

    /**
     * After construction, mark this level as a runtime exploration dimension.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void pw$markRuntime(MinecraftServer server, Executor executor,
                                LevelStorageSource.LevelStorageAccess storage,
                                ServerLevelData levelData, ResourceKey<Level> dimension,
                                LevelStem stem, ChunkProgressListener listener,
                                boolean isDebug, long seed, List<CustomSpawner> spawners,
                                boolean tickTime, @Nullable RandomSequences randomSeqs,
                                CallbackInfo ci) {
        if (ExplorationSeedManager.isExplorationDimension(dimension)) {
            pw$isRuntimeDimension = true;
            pw$LOGGER.info("Exploration dimension constructed: {} seed={}", dimension.location(), seed);
        }
    }

    /**
     * Skip saveLevelData for exploration dims when preventDiskSaves is enabled.
     */
    @Inject(method = "saveLevelData", at = @At("HEAD"), cancellable = true)
    private void pw$preventLevelDataSave(CallbackInfo ci) {
        if (pw$isRuntimeDimension && PWConfig.isPreventDiskSaves()) {
            ci.cancel();
        }
    }

    /**
     * Skip full save for exploration dims when preventDiskSaves is enabled.
     */
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void pw$preventSave(net.minecraft.util.ProgressListener progress,
                                boolean flush, boolean skipSave, CallbackInfo ci) {
        if (pw$isRuntimeDimension && PWConfig.isPreventDiskSaves()) {
            ci.cancel();
        }
    }

    /**
     * Log when a runtime dimension is closing.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void pw$logClose(CallbackInfo ci) {
        if (pw$isRuntimeDimension) {
            ServerLevel self = (ServerLevel) (Object) this;
            pw$LOGGER.info("Exploration dimension closing: {}", self.dimension().location());
        }
    }
}
