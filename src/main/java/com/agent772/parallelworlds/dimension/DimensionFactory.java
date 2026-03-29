package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.accessor.IServerDimensionAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Creates exploration dimensions at server startup using runtime registry manipulation.
 * Clones chunk generators and dimension types from source dimensions to preserve
 * all modded world generation (biomes, structures, features, ores, noise settings).
 */
public final class DimensionFactory {
    private static final Logger LOGGER = LogUtils.getLogger();

    private DimensionFactory() {}

    /**
     * Create an exploration copy of the given base dimension with a custom seed.
     *
     * @return the new ServerLevel, or null on failure
     */
    public static ServerLevel createExplorationDimension(MinecraftServer server,
                                                          ResourceLocation baseDimensionLocation,
                                                          long seed) {
        try {
            String baseName = baseDimensionLocation.getPath();

            // Resolve DimensionType from the source dimension (preserves modded types)
            Holder<DimensionType> dimTypeHolder = resolveSourceDimensionType(server, baseDimensionLocation);

            // Allocate a fresh ID: always higher than last-used, with a disk-existence check
            // as a crash-proof fallback in case the counter file was not saved on previous exit.
            long dimensionId = DimensionCounter.allocateFreshId(baseDimensionLocation, server);
            ResourceKey<Level> explorationKey = ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID,
                            "pw_" + baseName + "_" + dimensionId)
            );

            return registerDimensionWithKey(server, baseDimensionLocation, explorationKey, dimTypeHolder, seed);
        } catch (Exception e) {
            LOGGER.error("Failed to create exploration dimension for {}", baseDimensionLocation, e);
            return null;
        }
    }

    /**
     * Re-register an exploration dimension using an existing key (no counter increment).
     * Used on server restart in PERSIST mode to reload a dimension whose data is still on disk.
     *
     * @return the ServerLevel, or null on failure
     */
    public static ServerLevel createExplorationDimensionWithKey(MinecraftServer server,
                                                                 ResourceLocation baseDimensionLocation,
                                                                 ResourceLocation existingExplorationKey,
                                                                 long seed) {
        try {
            Holder<DimensionType> dimTypeHolder = resolveSourceDimensionType(server, baseDimensionLocation);
            ResourceKey<Level> explorationKey = ResourceKey.create(Registries.DIMENSION, existingExplorationKey);
            return registerDimensionWithKey(server, baseDimensionLocation, explorationKey, dimTypeHolder, seed);
        } catch (Exception e) {
            LOGGER.error("Failed to re-register existing exploration dimension {} for {}",
                    existingExplorationKey, baseDimensionLocation, e);
            return null;
        }
    }

    /** Shared dimension-registration logic used by both creation paths. */
    private static ServerLevel registerDimensionWithKey(MinecraftServer server,
                                                         ResourceLocation baseDimensionLocation,
                                                         ResourceKey<Level> explorationKey,
                                                         Holder<DimensionType> dimTypeHolder,
                                                         long seed) {
        // Register seed and set ThreadLocal context BEFORE chunk generator creation
        ExplorationSeedManager.registerDimensionSeed(explorationKey, seed);
        ExplorationSeedManager.setCurrentDimension(explorationKey);

        try {
            // Clone the generator from the source dimension (preserves all modded worldgen)
            ChunkGenerator chunkGenerator = cloneChunkGenerator(server, baseDimensionLocation);
            LOGGER.info("Cloned {} generator from {} for {} with seed {}",
                    chunkGenerator.getClass().getSimpleName(), baseDimensionLocation,
                    explorationKey.location(), seed);

            // Delegate to mixin on MinecraftServer
            ServerLevel level = ((IServerDimensionAccessor) server)
                    .pw$createRuntimeDimension(explorationKey, dimTypeHolder, chunkGenerator, seed);

            if (level != null) {
                LOGGER.info("Successfully created exploration dimension: {} seed: {}",
                        explorationKey.location(), seed);
            }
            return level;
        } catch (Exception e) {
            LOGGER.error("Failed to register dimension with key {} for {}",
                    explorationKey.location(), baseDimensionLocation, e);
            return null;
        } finally {
            ExplorationSeedManager.clearCurrentDimension();
        }
    }

    // ── Clone chunk generator from source dimension ──

    private static ChunkGenerator cloneChunkGenerator(MinecraftServer server,
                                                       ResourceLocation baseDimension) {
        // 1. Try to get the source dimension's ServerLevel
        ResourceKey<Level> sourceKey = ResourceKey.create(Registries.DIMENSION, baseDimension);
        ServerLevel sourceLevel = server.getLevel(sourceKey);

        if (sourceLevel != null) {
            ChunkGenerator sourceGen = sourceLevel.getChunkSource().getGenerator();

            // If the generator's noise settings were scoped into another namespace
            // (e.g. dimconfig rewrites minecraft:overworld → dimconfig:overworld to pin
            // vanilla terrain), reconstruct a fresh generator from the original
            // minecraft: registry entries so PW dims inherit the real mod terrain.
            if (sourceGen instanceof NoiseBasedChunkGenerator noiseGen) {
                ChunkGenerator reconstructed = maybeReconstructGenerator(
                        server, noiseGen, baseDimension);
                if (reconstructed != null) return reconstructed;
            }

            return sourceGen;
        }

        // 2. Fallback: look up the LevelStem from registry (for early-startup edge cases)
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, baseDimension);

        if (stemRegistry.containsKey(stemKey)) {
            LevelStem stem = stemRegistry.get(stemKey);
            if (stem != null) {
                return stem.generator();
            }
        }

        // 3. Last resort: throw
        throw new IllegalStateException("Cannot find source dimension to clone generator: " + baseDimension);
    }

    // ── Reconstruct chunk generator for dimconfig compatibility ──

    /**
     * If the source generator's noise settings were rewritten into a different
     * namespace (e.g. {@code dimconfig:overworld} instead of
     * {@code minecraft:overworld}), construct a fresh {@link NoiseBasedChunkGenerator}
     * using the original {@code minecraft:} registry entries.
     *
     * <p>Returns {@code null} if no reconstruction is needed (settings namespace
     * already matches the base dimension).
     */
    @Nullable
    private static ChunkGenerator maybeReconstructGenerator(MinecraftServer server,
                                                             NoiseBasedChunkGenerator sourceGen,
                                                             ResourceLocation baseDimension) {
        RegistryAccess registryAccess = server.registryAccess();

        // Check if noise settings were rewritten to a foreign namespace
        Optional<ResourceKey<NoiseGeneratorSettings>> settingsKey =
                sourceGen.generatorSettings().unwrapKey();
        if (settingsKey.isEmpty()) return null; // direct holder — no key to inspect

        ResourceLocation settingsId = settingsKey.get().location();
        if (settingsId.getNamespace().equals(baseDimension.getNamespace())) {
            return null; // namespace matches (e.g. minecraft:overworld) — no rewrite detected
        }

        // --- Noise settings: look up the original minecraft: entry ---
        ResourceLocation originalSettingsId = ResourceLocation.fromNamespaceAndPath(
                baseDimension.getNamespace(), settingsId.getPath());
        Registry<NoiseGeneratorSettings> settingsRegistry =
                registryAccess.registryOrThrow(Registries.NOISE_SETTINGS);
        Optional<Holder.Reference<NoiseGeneratorSettings>> originalSettings =
                settingsRegistry.getHolder(ResourceKey.create(Registries.NOISE_SETTINGS, originalSettingsId));
        if (originalSettings.isEmpty()) {
            LOGGER.warn("[PW] Cannot find original noise settings {} — using source generator as-is",
                    originalSettingsId);
            return null;
        }

        // --- Biome source: try modded level stem reference first, then preset, then source ---
        BiomeSource biomeSource = readModdedBiomeSource(server, baseDimension);
        if (biomeSource == null) {
            biomeSource = reconstructBiomeSourceFromPreset(registryAccess, baseDimension);
        }
        if (biomeSource == null) {
            // Last resort: reuse the source biome source (may still have the rewritten preset,
            // but at least the noise settings will be correct)
            biomeSource = sourceGen.getBiomeSource();
        }

        LOGGER.info("[PW] Reconstructed generator for {} → noise_settings={}, bypassing scoped {}",
                baseDimension, originalSettingsId, settingsId);
        return new NoiseBasedChunkGenerator(biomeSource, originalSettings.get());
    }

    /**
     * Reads the mod-provided ("modded") level stem from dimconfig's reference file in the
     * virtual data pack and extracts the biome source from it.
     *
     * <p>dimconfig stores the original mod-provided level stem (e.g. Terralith's overworld.json
     * with inline biome list) at {@code dimconfig:terrain_router/modded_level_stem/<path>.json}
     * so that PW can reconstruct generators with the correct biome source even when the pin
     * replaces the level stem.
     *
     * @return the biome source from the modded level stem, or {@code null} if not available
     */
    @Nullable
    private static BiomeSource readModdedBiomeSource(MinecraftServer server,
                                                      ResourceLocation baseDimension) {
        ResourceLocation refKey = ResourceLocation.fromNamespaceAndPath(
                "dimconfig", "terrain_router/modded_level_stem/" + baseDimension.getPath() + ".json");
        try {
            Optional<net.minecraft.server.packs.resources.Resource> resource =
                    server.getResourceManager().getResource(refKey);
            if (resource.isEmpty()) {
                LOGGER.debug("[PW] No modded level stem reference at {} — dimconfig may not be installed or no pin active",
                        refKey);
                return null;
            }

            // Parse the generator.biome_source from the modded level stem JSON
            com.google.gson.JsonObject root;
            try (java.io.InputStream is = resource.get().open()) {
                root = com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }

            if (!root.has("generator")) return null;
            com.google.gson.JsonObject gen = root.getAsJsonObject("generator");
            if (!gen.has("biome_source")) return null;
            com.google.gson.JsonElement biomeSourceJson = gen.get("biome_source");

            // Deserialize the biome source using MC's codec system with registry resolution
            com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                    net.minecraft.resources.RegistryOps.create(
                            com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            com.mojang.serialization.DataResult<? extends BiomeSource> result =
                    BiomeSource.CODEC.parse(ops, biomeSourceJson);

            Optional<? extends BiomeSource> parsed = result.result();
            if (parsed.isPresent()) {
                LOGGER.info("[PW] Parsed modded biome source from {} ({} possible biomes)",
                        refKey, parsed.get().possibleBiomes().size());
                return parsed.get();
            } else {
                LOGGER.warn("[PW] Failed to parse biome source from {}: {}",
                        refKey, result.error().map(Object::toString).orElse("unknown"));
            }
        } catch (Exception e) {
            LOGGER.debug("[PW] Failed to read modded level stem reference {}: {}", refKey, e.getMessage());
        }
        return null;
    }

    /**
     * Constructs a fresh {@link MultiNoiseBiomeSource} from the base dimension's
     * biome-source preset in the registry. Returns {@code null} if the preset is
     * not found (e.g. non-multi_noise dimensions like the_end).
     */
    @Nullable
    private static BiomeSource reconstructBiomeSourceFromPreset(RegistryAccess registryAccess,
                                                                 ResourceLocation baseDimension) {
        Registry<MultiNoiseBiomeSourceParameterList> presetRegistry =
                registryAccess.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        ResourceKey<MultiNoiseBiomeSourceParameterList> presetKey =
                ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, baseDimension);

        Optional<Holder.Reference<MultiNoiseBiomeSourceParameterList>> holder =
                presetRegistry.getHolder(presetKey);
        if (holder.isEmpty()) return null;

        return MultiNoiseBiomeSource.createFromPreset(holder.get());
    }

    // ── Resolve DimensionType from source dimension ──

    private static Holder<DimensionType> resolveSourceDimensionType(MinecraftServer server,
                                                                     ResourceLocation baseDimension) {
        // 1. Try LevelStem registry (contains DimensionType holders with full mod data)
        Registry<LevelStem> stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, baseDimension);

        if (stemRegistry.containsKey(stemKey)) {
            LevelStem stem = stemRegistry.get(stemKey);
            if (stem != null) {
                return stem.type();
            }
        }

        // 2. Fallback: try loading directly from DimensionType registry
        Registry<DimensionType> dimTypeRegistry = server.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<DimensionType> dimTypeKey = ResourceKey.create(Registries.DIMENSION_TYPE, baseDimension);

        if (dimTypeRegistry.containsKey(dimTypeKey)) {
            return dimTypeRegistry.getHolderOrThrow(dimTypeKey);
        }

        // 3. Last resort: overworld
        LOGGER.warn("Could not resolve DimensionType for {}, falling back to overworld", baseDimension);
        return dimTypeRegistry.getHolderOrThrow(
                ResourceKey.create(Registries.DIMENSION_TYPE,
                        ResourceLocation.withDefaultNamespace("overworld")));
    }
}
