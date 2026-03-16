package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.mixin.IStructureManagerAccessor;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.generation.async.AsyncChunkWorkerPool;
import com.agent772.parallelworlds.generation.async.ChunkComputeResult;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Intercepts noise-based chunk generation to use pre-computed results
 * from the async worker pool when available.
 * This skips the expensive noise computation for chunks that were
 * pre-calculated off-thread.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator {
    @Unique
    private static final Logger pw$LOGGER = LogUtils.getLogger();

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void pw$usePrecomputedNoise(Blender blender, RandomState randomState,
                                        StructureManager structureManager, ChunkAccess chunk,
                                        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PWConfig.isAsyncChunkGenEnabled()) return;

        // Determine the dimension from the StructureManager's level
        ResourceKey<Level> dimension = pw$getDimensionKey(structureManager);
        if (dimension == null) return;

        // Check for pre-computed result
        ChunkPos chunkPos = chunk.getPos();
        ChunkComputeResult result = AsyncChunkWorkerPool.pollCachedResult(dimension, chunkPos);
        if (result == null || !result.success()) return;

        // Apply pre-computed noise data to the chunk and skip vanilla noise
        pw$applyPrecomputedNoise(chunk, result);

        pw$LOGGER.debug("Used pre-computed noise for chunk {} in {} (saved {}ms)",
                chunkPos, dimension.location(),
                String.format("%.2f", result.computeTimeNanos() / 1_000_000.0));

        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static void pw$applyPrecomputedNoise(ChunkAccess chunk, ChunkComputeResult result) {
        PalettedContainer<BlockState>[] precomputedSections = result.sections();
        if (precomputedSections == null) return;

        LevelChunkSection[] chunkSections = chunk.getSections();
        int count = Math.min(precomputedSections.length, chunkSections.length);

        for (int i = 0; i < count; i++) {
            if (precomputedSections[i] != null) {
                // Copy block states from pre-computed section
                chunkSections[i].acquire();
                try {
                    // The section's states paletted container is replaced by copying block by block
                    // using the pre-computed container's data
                    PalettedContainer<BlockState> source = precomputedSections[i];
                    LevelChunkSection target = chunkSections[i];
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = source.get(x, y, z);
                                if (!state.isAir()) {
                                    target.setBlockState(x, y, z, state, false);
                                }
                            }
                        }
                    }
                } finally {
                    chunkSections[i].release();
                }
            }
        }

        // Apply heightmaps
        if (result.heightmapTypes() != null && result.heightmapData() != null) {
            for (int i = 0; i < result.heightmapTypes().length; i++) {
                Heightmap.Types type = result.heightmapTypes()[i];
                long[] data = result.heightmapData()[i];
                if (type != null && data != null) {
                    chunk.setHeightmap(type, data);
                }
            }
        }
    }

    @Unique
    private static ResourceKey<Level> pw$getDimensionKey(StructureManager structureManager) {
        try {
            LevelAccessor levelAccessor = ((IStructureManagerAccessor) (Object) structureManager).pw$getLevel();
            if (levelAccessor instanceof ServerLevel serverLevel) {
                return serverLevel.dimension();
            } else if (levelAccessor instanceof WorldGenRegion worldGenRegion) {
                return worldGenRegion.getLevel().dimension();
            }
        } catch (Exception e) {
            pw$LOGGER.debug("Could not determine dimension from StructureManager: {}", e.getMessage());
        }
        return null;
    }
}
