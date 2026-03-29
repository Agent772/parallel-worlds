# Epic: dimconfig Terrain Pin Compatibility

## Problem

When dimconfig's **Dimension Firewall** pins the overworld to vanilla terrain
(to block Terralith/etc.), it rewrites the overworld's level stem so the
`NoiseBasedChunkGenerator` references scoped `dimconfig:overworld` noise
settings and `dimconfig:overworld` biome-source preset (containing vanilla
worldgen), while freeing the `minecraft:overworld` registry slots for Terralith
to own globally.

**PW's `cloneChunkGenerator()` returns the overworld's `ChunkGenerator` by
object reference** (`DimensionFactory.java:124`). This means every PW
exploration dimension inherits the rewritten `dimconfig:overworld` references
and gets vanilla terrain instead of Terralith terrain.

### Why this can't be fixed in dimconfig

dimconfig operates at the **data-pack level** (`AddPackFindersEvent`), which
runs before any `ServerLevel` exists. The only way to give the overworld
different noise settings than PW dims is via different `Holder` references in
the `ChunkGenerator`. Since PW shares the same Java object, any data-pack-level
rewrite propagates to all PW clones.

## Solution: Reconstruct the generator from the original registry entries

Instead of sharing the overworld's ChunkGenerator by reference, construct a
**fresh** `NoiseBasedChunkGenerator` that reads from the `minecraft:overworld`
registry slots (which dimconfig freed for Terralith to own).

### Detection logic

dimconfig only rewrites entries into the `dimconfig` namespace. If the
overworld's noise-settings Holder key is **not** in the base dimension's
namespace (i.e., `dimconfig:overworld` instead of `minecraft:overworld`), that's
the signal that reconstruction is needed. If no rewriting occurred (dimconfig
not installed, or no pin rule), the existing fast path (same object reference)
is used.

## Implementation

### File: `DimensionFactory.java`

**Replace** `cloneChunkGenerator()` (lines 117-140) with:

```java
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
                    server.registryAccess(), noiseGen, baseDimension);
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
```

**Add** new private helper method:

```java
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
private static ChunkGenerator maybeReconstructGenerator(RegistryAccess registryAccess,
                                                         NoiseBasedChunkGenerator sourceGen,
                                                         ResourceLocation baseDimension) {
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

    // --- Biome source: reconstruct from the original preset ---
    BiomeSource biomeSource = reconstructBiomeSource(registryAccess, baseDimension);
    if (biomeSource == null) {
        // Fallback: reuse the source biome source (may still have the rewritten preset,
        // but at least the noise settings will be correct)
        biomeSource = sourceGen.getBiomeSource();
    }

    LOGGER.info("[PW] Reconstructed generator for {} → noise_settings={}, bypassing scoped {}",
            baseDimension, originalSettingsId, settingsId);
    return new NoiseBasedChunkGenerator(biomeSource, originalSettings.get());
}

/**
 * Constructs a fresh {@link MultiNoiseBiomeSource} from the base dimension's
 * biome-source preset in the registry. Returns {@code null} if the preset is
 * not found (e.g. non-multi_noise dimensions like the_end).
 */
@Nullable
private static BiomeSource reconstructBiomeSource(RegistryAccess registryAccess,
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
```

### New imports needed in `DimensionFactory.java`

```java
import javax.annotation.Nullable;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
```

(`Holder` and `Registries` are already imported.)

## Behaviour Matrix

| dimconfig installed? | pin rule active? | PW behaviour |
|---|---|---|
| No | N/A | Fast path: same object reference (unchanged) |
| Yes | No pin | Fast path: namespace check passes (`minecraft` = `minecraft`) |
| Yes | Pin active | Reconstruct: `dimconfig:overworld` detected → fresh generator with `minecraft:overworld` noise settings + modded biome source from dimconfig reference file |

## Biome Source Reconstruction

**Problem (v1):** The original `reconstructBiomeSource()` created a
`MultiNoiseBiomeSource` from the `minecraft:overworld`
`MultiNoiseBiomeSourceParameterList` preset. But Terralith does NOT provide
a separate MNBSPL — it provides an **inline biome list** directly in
`data/minecraft/dimension/overworld.json`. The MNBSPL preset in the registry
is vanilla-only, so PW got vanilla biomes.

**Fix (v2):** dimconfig now stores the mod-provided level stem (e.g.
Terralith's `dimension/overworld.json` with inline biomes) in the virtual
data pack at `dimconfig:terrain_router/modded_level_stem/<path>.json`. PW's
`readModdedBiomeSource()` reads this reference file, deserializes the
`generator.biome_source` using `BiomeSource.CODEC` with `RegistryOps`, and
uses the resulting biome source (which includes Terralith's full biome list).

Fallback chain: modded level stem → MNBSPL preset → source generator's biome source.

## Testing

**Test scenario:** dimconfig Test 4B (see dimconfig's `TESTING_MANUAL.md`)

1. Take a vanilla snapshot (`/dimfirewall snapshot`) without Terralith.
2. Install Terralith. Apply pin rule for the overworld.
3. Start server. Verify:
   - **Overworld**: vanilla terrain (pinned). `/locate biome terralith:highlands` → not found.
   - **PW overworld dim**: Terralith terrain. `/locate biome terralith:highlands` → found.
   - **PW seed**: each PW dim still has a unique seed (terrain layout differs from overworld).
4. Log should show:
   ```
   [dimconfig terrain] Stored modded level stem reference for minecraft:overworld as dimconfig:terrain_router/modded_level_stem/overworld.json
   [PW] Parsed modded biome source from dimconfig:terrain_router/modded_level_stem/overworld.json (N possible biomes)
   [PW] Reconstructed generator for minecraft:overworld → noise_settings=minecraft:overworld, bypassing scoped dimconfig:overworld
   ```

## Notes

- **No dimconfig compile dependency required.** PW detects the scoped namespace
  generically (any non-matching namespace triggers reconstruction). The modded
  level stem reference is read from the data pack at runtime — PW only needs
  to know the resource path convention (`dimconfig:terrain_router/modded_level_stem/`).
- **Backwards compatible.** When dimconfig is not installed (or no pin is active),
  the namespace check passes and the existing fast-path is used. If the reference
  file is absent, PW falls back to the MNBSPL preset, then the source biome source.
- The `dimconfig:` namespace convention is stable — dimconfig documents it in
  `DfNamespaceRewriter.PIN_NS`.
