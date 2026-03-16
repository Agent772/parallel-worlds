package com.agent772.parallelworlds.generation.async;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

import java.util.concurrent.Callable;

/**
 * Runs noise computation on a worker thread.
 * Creates a ProtoChunk, fills it with noise-generated terrain (doFill equivalent),
 * and extracts the section data + heightmaps into a ChunkComputeResult.
 *
 * Thread safety: Only uses immutable/frozen data from the request.
 * Never accesses ServerLevel, entities, or mutable world state.
 */
public class ChunkComputeWorker implements Callable<ChunkComputeResult> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ChunkComputeRequest request;

    public ChunkComputeWorker(ChunkComputeRequest request) {
        this.request = request;
    }

    @Override
    public ChunkComputeResult call() {
        long startNanos = System.nanoTime();
        try {
            return computeNoise(startNanos);
        } catch (Exception e) {
            long elapsed = System.nanoTime() - startNanos;
            LOGGER.warn("Async noise computation failed for chunk {} in {}: {}",
                    request.chunkPos(), request.dimension().location(), e.getMessage());
            return ChunkComputeResult.failure(
                    request.dimension(), request.chunkPos(), e.getMessage(), elapsed);
        }
    }

    @SuppressWarnings("unchecked")
    private ChunkComputeResult computeNoise(long startNanos) {
        NoiseGeneratorSettings settings = request.noiseSettings();
        int minBuildHeight = request.minBuildHeight();
        int buildHeight = request.buildHeight();

        // Create a height accessor matching the dimension
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(minBuildHeight, buildHeight);

        // Create a ProtoChunk off-thread (safe — no Level references needed)
        var biomeRegistry = request.registryAccess().registryOrThrow(Registries.BIOME);
        ProtoChunk protoChunk = new ProtoChunk(
                request.chunkPos(), UpgradeData.EMPTY, heightAccessor, biomeRegistry, null);

        // Clamp noise settings to chunk height
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(protoChunk.getHeightAccessorForGeneration());
        int minCellY = Math.floorDiv(noiseSettings.minY(), noiseSettings.getCellHeight());
        int cellCountY = Math.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());

        if (cellCountY <= 0) {
            // Nothing to generate (e.g., flat world)
            long elapsed = System.nanoTime() - startNanos;
            return ChunkComputeResult.success(
                    request.dimension(), request.chunkPos(),
                    extractSections(protoChunk),
                    new Heightmap.Types[0], new long[0][],
                    elapsed);
        }

        // Create fluid picker from settings (replicates NoiseBasedChunkGenerator.createFluidPicker)
        Aquifer.FluidPicker fluidPicker = createFluidPicker(settings);

        // Create a no-op beardifier (returns 0.0 density — no structure terrain modification)
        // BeardifierMarker.INSTANCE is protected, so we implement the interface directly
        DensityFunctions.BeardifierOrMarker noOpBeardifier = new DensityFunctions.BeardifierOrMarker() {
            @Override public double compute(DensityFunction.FunctionContext ctx) { return 0.0; }
            @Override public double minValue() { return 0.0; }
            @Override public double maxValue() { return 0.0; }
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
                return DensityFunctions.BeardifierOrMarker.CODEC;
            }
        };

        // Create NoiseChunk with no structure beardifier (safe: no StructureManager needed)
        NoiseChunk noiseChunk = NoiseChunk.forChunk(
                protoChunk,
                request.randomState(),
                noOpBeardifier,
                settings,
                fluidPicker,
                Blender.empty()
        );

        // Store the noise chunk on the proto chunk so getOrCreateNoiseChunk returns it
        protoChunk.getOrCreateNoiseChunk(ignored -> noiseChunk);

        // Run noise fill — equivalent to NoiseBasedChunkGenerator.doFill()
        doFill(protoChunk, noiseChunk, settings, noiseSettings, minCellY, cellCountY);

        // Extract sections and heightmaps
        PalettedContainer<BlockState>[] sections = extractSections(protoChunk);
        Heightmap.Types[] hmTypes = {Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG};
        long[][] hmData = new long[hmTypes.length][];
        for (int i = 0; i < hmTypes.length; i++) {
            Heightmap hm = protoChunk.getOrCreateHeightmapUnprimed(hmTypes[i]);
            hmData[i] = hm.getRawData();
        }

        long elapsed = System.nanoTime() - startNanos;
        return ChunkComputeResult.success(
                request.dimension(), request.chunkPos(), sections, hmTypes, hmData, elapsed);
    }

    /**
     * Replicates {@code NoiseBasedChunkGenerator.doFill()} — pure noise computation.
     * Fills block states into the ProtoChunk's sections and updates heightmaps.
     */
    private void doFill(ProtoChunk chunk, NoiseChunk noiseChunk, NoiseGeneratorSettings settings,
                        NoiseSettings noiseSettings, int minCellY, int cellCountY) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunk.getPos();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();

        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int cellWidth = noiseChunk.cellWidth();
        int cellHeight = noiseChunk.cellHeight();
        int cellsX = 16 / cellWidth;
        int cellsZ = 16 / cellWidth;

        for (int cx = 0; cx < cellsX; cx++) {
            noiseChunk.advanceCellX(cx);

            for (int cz = 0; cz < cellsZ; cz++) {
                int sectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(sectionIndex);

                for (int cy = cellCountY - 1; cy >= 0; cy--) {
                    noiseChunk.selectCellYZ(cy, cz);

                    for (int dy = cellHeight - 1; dy >= 0; dy--) {
                        int blockY = (minCellY + cy) * cellHeight + dy;
                        int localY = blockY & 15;
                        int newSectionIndex = chunk.getSectionIndex(blockY);
                        if (sectionIndex != newSectionIndex) {
                            sectionIndex = newSectionIndex;
                            section = chunk.getSection(newSectionIndex);
                        }

                        double yFraction = (double) dy / (double) cellHeight;
                        noiseChunk.updateForY(blockY, yFraction);

                        for (int dx = 0; dx < cellWidth; dx++) {
                            int blockX = chunkMinX + cx * cellWidth + dx;
                            int localX = blockX & 15;
                            double xFraction = (double) dx / (double) cellWidth;
                            noiseChunk.updateForX(blockX, xFraction);

                            for (int dz = 0; dz < cellWidth; dz++) {
                                int blockZ = chunkMinZ + cz * cellWidth + dz;
                                int localZ = blockZ & 15;
                                double zFraction = (double) dz / (double) cellWidth;
                                noiseChunk.updateForZ(blockZ, zFraction);

                                BlockState blockState = noiseChunk.getInterpolatedState();
                                if (blockState == null) {
                                    blockState = settings.defaultBlock();
                                }

                                if (blockState != AIR && !SharedConstants.debugVoidTerrain(chunk.getPos())) {
                                    section.setBlockState(localX, localY, localZ, blockState, false);
                                    oceanFloor.update(localX, blockY, localZ, blockState);
                                    worldSurface.update(localX, blockY, localZ, blockState);
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                                        mutablePos.set(blockX, blockY, blockZ);
                                        chunk.markPosForPostprocessing(mutablePos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
    }

    @SuppressWarnings("unchecked")
    private PalettedContainer<BlockState>[] extractSections(ProtoChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        PalettedContainer<BlockState>[] result = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            result[i] = sections[i].getStates();
        }
        return result;
    }

    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lavaStatus = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus waterStatus = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaStatus : waterStatus;
    }
}
