# Sub-Agent Instructions: Epic 13 — Modded World Generation Compatibility

## Objective
Replace the hardcoded vanilla-preset-based chunk generator creation with a source-dimension-cloning approach so that parallel dimensions fully inherit **all** modded world generation: biomes, structures, features, ores, noise settings, dimension types, and any other world-gen content added by other mods.

## Context
- **Depends on**: Epic 2 (dimension creation pipeline exists)
- **Package**: `com.agent772.parallelworlds`
- **Problem**: `DimensionFactory.createChunkGenerator()` currently creates a brand-new `NoiseBasedChunkGenerator` using hardcoded vanilla presets (`NoiseGeneratorSettings.OVERWORLD`, `minecraft:overworld`, etc.). This strips ALL modded content:
  - Mod-added biomes (e.g. Biomes O' Plenty, Terralith) — **lost**
  - Mod-added structures (e.g. YUNG's Better Structures) — **lost**
  - Mod-added features/ores (e.g. Create, Mekanism ores) — **lost**
  - Custom noise settings (e.g. Terralith, Tectonic) — **lost**
  - Mod-added dimension types — **falls back to vanilla via name pattern matching**
  - Custom ChunkGenerator subclasses (e.g. mods replacing the entire generator) — **lost**
- **Solution**: Clone the `ChunkGenerator` and `DimensionType` directly from the source dimension's existing `ServerLevel`. The seed replacement is already handled by the `MixinRandomState` which intercepts `RandomState.create()` — that continues to work regardless of generator type.

## Design Principles
1. **Clone, don't recreate** — Copy the exact ChunkGenerator from the source ServerLevel, preserving all modded modifications
2. **Respect the source DimensionType** — Use the actual DimensionType from the source dimension's LevelStem, not a hardcoded placeholder or name-pattern-matched vanilla type
3. **Handle non-standard generators** — If a mod replaces `NoiseBasedChunkGenerator` with its own subclass, the clone approach preserves that automatically
4. **Maintain seed isolation** — The existing `MixinRandomState` + `ExplorationSeedManager` pattern handles seed replacement via ThreadLocal context, and works regardless of generator type
5. **Fallback gracefully** — If the source dimension doesn't exist as a loaded ServerLevel yet (edge case at early startup), fall back to looking up the source LevelStem from the registry

## Tasks

### 1. Rewrite `DimensionFactory.createChunkGenerator()` → `cloneChunkGenerator()`
**File**: `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`

Replace the `createChunkGenerator(MinecraftServer server, String dimensionName)` method entirely.

**New approach**:
```java
private static ChunkGenerator cloneChunkGenerator(MinecraftServer server, 
                                                    ResourceLocation baseDimension) {
    // 1. Try to get the source dimension's ServerLevel
    ResourceKey<Level> sourceKey = ResourceKey.create(Registries.DIMENSION, baseDimension);
    ServerLevel sourceLevel = server.getLevel(sourceKey);
    
    if (sourceLevel != null) {
        // Clone generator directly from the loaded source level
        return sourceLevel.getChunkSource().getGenerator();
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
    
    // 3. Last resort: log error and throw
    throw new IllegalStateException("Cannot find source dimension: " + baseDimension);
}
```

**Key points**:
- The method signature changes to accept `ResourceLocation baseDimension` instead of `String dimensionName`
- The returned ChunkGenerator is the EXACT same instance type as the source — if a mod uses a custom generator subclass, it's preserved
- The BiomeSource, NoiseGeneratorSettings, structure settings — everything is preserved because we're using the source's generator directly
- The seed override happens later via `MixinRandomState` when `RandomState.create()` is called during chunk generation — this is independent of the generator instance
- Remove all the `switch(dimensionName)` vanilla-preset logic entirely

**Important detail about seed isolation**: The ChunkGenerator instance is shared by reference, but this is safe because:
- The `NoiseBasedChunkGenerator` does NOT store the seed — it receives it via `RandomState.create()` during generation
- `MixinRandomState` intercepts `RandomState.create()` and replaces the seed based on the `ExplorationSeedManager` ThreadLocal context
- Each `ServerLevel` gets its own `ChunkSource` which calls `RandomState.create()` independently with the level's seed
- The `ServerLevel` constructor receives the seed parameter, which is passed through to `RandomState.create()`

### 2. Resolve DimensionType from source dimension (not hardcoded)
**File**: `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`

Add a new method to resolve the DimensionType from the source:

```java
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
```

### 3. Update `createExplorationDimension()` to use cloned generator and resolved DimensionType
**File**: `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`

Changes:
- Remove `buildPlaceholderDimensionType()` method entirely — no longer needed
- Pass `baseDimensionLocation` (ResourceLocation) to `cloneChunkGenerator()` instead of `baseName` (String)
- Pass `Holder<DimensionType>` from `resolveSourceDimensionType()` instead of the placeholder
- The `DimensionType dimensionType` parameter in `createExplorationDimension` should become a `Holder<DimensionType>` or be resolved internally

Updated flow:
```
createExplorationDimension(server, baseDimLocation, seed)
  → resolveSourceDimensionType(server, baseDimLocation)  // gets actual dim type from source
  → cloneChunkGenerator(server, baseDimLocation)          // clones generator from source
  → ExplorationSeedManager setup (unchanged)
  → pw$createRuntimeDimension(key, dimTypeHolder, generator, seed)
```

### 4. Update `MixinMinecraftServer.pw$createRuntimeDimension()` signature
**File**: `src/main/java/com/agent772/parallelworlds/mixin/MixinMinecraftServer.java`

Changes:
- Change the `dimensionType` parameter from `DimensionType` to `Holder<DimensionType>`
- Remove the `pw$resolveVanillaDimType()` helper method entirely — DimensionType is now resolved correctly in `DimensionFactory` and passed in as a `Holder<DimensionType>`
- Create the `LevelStem` directly from the passed-in holder: `new LevelStem(dimTypeHolder, chunkGenerator)`

### 5. Update `IServerDimensionAccessor` interface
**File**: `src/main/java/com/agent772/parallelworlds/accessor/IServerDimensionAccessor.java`

Change `pw$createRuntimeDimension` signature:
```java
// Before:
ServerLevel pw$createRuntimeDimension(ResourceKey<Level> key, DimensionType type, 
                                       ChunkGenerator generator, long seed);
// After:
ServerLevel pw$createRuntimeDimension(ResourceKey<Level> key, Holder<DimensionType> type, 
                                       ChunkGenerator generator, long seed);
```

### 6. Remove unused imports
**File**: `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`

Remove imports that are no longer needed after the rewrite:
- `net.minecraft.tags.BlockTags`
- `net.minecraft.util.valueproviders.UniformInt`
- `net.minecraft.world.level.biome.BiomeSource`
- `net.minecraft.world.level.biome.MultiNoiseBiomeSource`
- `net.minecraft.world.level.biome.TheEndBiomeSource`
- `net.minecraft.world.level.dimension.DimensionType` (still need `Holder<DimensionType>`, check)
- `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`
- `net.minecraft.world.level.levelgen.NoiseGeneratorSettings`
- `java.util.OptionalLong`

Add imports needed:
- `net.minecraft.core.Holder`
- `net.minecraft.core.Registry`
- `net.minecraft.world.level.dimension.LevelStem`

### 7. Verify `MixinRandomState` still works with cloned generators
**File**: `src/main/java/com/agent772/parallelworlds/mixin/MixinRandomState.java`

**No code changes needed** — just verify the mixin intercepts `RandomState.create()` and replaces the seed based on `ExplorationSeedManager.getCurrentDimension()`. This works regardless of generator type because:
- `RandomState.create()` is called during `ServerLevel` construction
- The ThreadLocal context is set before `new ServerLevel(...)` in `MixinMinecraftServer`
- The seed parameter is passed to `ServerLevel` constructor and flows through to `RandomState.create()`

### 8. Build verification
Run `./gradlew build` and confirm zero errors.

## Files Modified

| File | Change |
|------|--------|
| `DimensionFactory.java` | Rewrite `createChunkGenerator()` → `cloneChunkGenerator()`, add `resolveSourceDimensionType()`, remove `buildPlaceholderDimensionType()`, update `createExplorationDimension()` |
| `IServerDimensionAccessor.java` | Change `DimensionType` → `Holder<DimensionType>` in method signature |
| `MixinMinecraftServer.java` | Update `pw$createRuntimeDimension()` parameter, remove `pw$resolveVanillaDimType()` |

## Files NOT Modified (verified still work)
| File | Reason |
|------|--------|
| `MixinRandomState.java` | Seed interception works regardless of generator type |
| `ExplorationSeedManager.java` | ThreadLocal context is generator-agnostic |
| `DimensionRegistrar.java` | Calls `DimensionFactory.createExplorationDimension()` — interface unchanged |
| `DimensionManager.java` | Higher-level orchestration — unchanged |

## Compatibility Gains

| Feature | Before (Epic 2) | After (Epic 13) |
|---------|-----------------|-----------------|
| Mod-added biomes | ❌ Vanilla presets only | ✅ Cloned from source |
| Mod-added structures | ❌ Lost | ✅ Preserved via generator |
| Mod-added features/ores | ❌ Lost | ✅ Preserved via biome source |
| Custom noise settings | ❌ Hardcoded vanilla | ✅ From source generator |
| Custom ChunkGenerator subclasses | ❌ Always NoiseBasedChunkGenerator | ✅ Any generator type preserved |
| Mod-added DimensionTypes | ❌ Falls back via name matching | ✅ Resolved from source LevelStem |
| Seed isolation | ✅ Works | ✅ Still works (unchanged) |

## Testing Checklist
- [ ] Vanilla overworld parallel dimension generates correctly
- [ ] Vanilla nether parallel dimension generates correctly
- [ ] Vanilla end parallel dimension generates correctly
- [ ] Mod-added dimension (e.g. `modid:custom`) can be used as a source
- [ ] Biomes from world-gen mods appear in parallel dimensions
- [ ] Structures from mods appear in parallel dimensions
- [ ] Custom ores from mods generate in parallel dimensions
- [ ] Seeds are different between source and parallel dimension
- [ ] Multiple parallel dimensions have independent seeds
- [ ] Build passes with `./gradlew build`
