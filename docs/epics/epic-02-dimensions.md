# Sub-Agent Instructions: Epic 2 — Dynamic Dimension Creation

## Objective
Implement the core engine that creates, manages, and destroys mining dimensions at runtime using custom seeds.

## Context
- **Depends on**: Epic 1 (mod loads, config system works)
- **Package**: `com.agent772.parallelworlds`
- **Key reference patterns** (study, do NOT copy code from BrecherDimensions):
  - Registry manipulation: unfreeze MappedRegistry → register entries → refreeze  
  - ThreadLocal seed context set BEFORE chunk generator creation
  - Dimension naming: `pw_{basetype}_{counter}` format
  - Counter persistence in world data folder

## Tasks

### 1. Mixin Accessor Interfaces
`src/main/java/com/agent772/parallelworlds/accessor/IServerDimensionAccessor.java`:
- Interface with methods: `pw$createRuntimeDimension(ResourceKey<Level> key, DimensionType type, ChunkGenerator generator, long seed) → ServerLevel`, `pw$removeRuntimeDimension(ResourceKey<Level> key)`, `pw$getRuntimeLevels() → Map`

`src/main/java/com/agent772/parallelworlds/accessor/IRegistryAccessor.java`:
- Generic interface `<T>` with: `pw$setFrozen(boolean)`, `pw$registerRuntime(ResourceKey<T> key, T value)`, `pw$removeRuntime(ResourceKey<T> key)`, `pw$refreeze()`, `pw$validateState() → boolean`

### 2. Mixins
`MixinMinecraftServer.java` — `@Mixin(MinecraftServer.class) implements IServerDimensionAccessor`:
- Access `levels` field (Map<ResourceKey<Level>, ServerLevel>)
- `pw$createRuntimeDimension()`: validate inputs, check duplicates, create ServerLevel with custom LevelStem, register in DIMENSION_TYPE and LEVEL_STEM registries, add to levels map, fire ticking
- `pw$removeRuntimeDimension()`: remove from levels, unregister, save and close level

`MixinMappedRegistry.java` — `@Mixin(MappedRegistry.class) implements IRegistryAccessor`:
- Access `frozen`, `byKey`, `byLocation`, `byValue` fields
- Implement unfreeze/register/refreeze cycle
- Handle obfuscated field names with fallback field discovery
- Throw RuntimeException on critical failure

`MixinServerLevel.java`:
- `@Inject` into save methods: skip if `PWConfig.isPreventDiskSaves()` and is exploration dim
- Inject into constructor: register seed context via ExplorationSeedManager

`MixinChunkMap.java`:
- `@Inject` into save: return false for exploration dims when preventDiskSaves enabled

### 3. Access Transformer
`src/main/resources/META-INF/accesstransformer.cfg`:
- Make accessible: `MappedRegistry.frozen`, `MappedRegistry.byKey`, `MappedRegistry.byLocation`, `MappedRegistry.byValue`
- Make accessible: `MinecraftServer.levels`, `MinecraftServer.storageSource`
- Make accessible: `ServerLevel.serverLevelData`

### 4. Mixin Config
`src/main/resources/parallelworlds.mixins.json`:
- Package: `com.agent772.parallelworlds.mixin`
- Mixins: MixinMinecraftServer, MixinMappedRegistry, MixinServerLevel, MixinChunkMap
- Set compatibilityLevel to JAVA_21

### 5. Seed Management
`dimension/SeedManager.java`:
- Static methods: `generateRandomSeed()`, `generateDateBasedSeed(LocalDate, String dimName)`, `generateWeeklySeed(LocalDate, String dimName, DayOfWeek resetDay)`
- Seed mixing: murmurhash-style 64-bit mixing (XOR shift + multiply, 3 rounds)
- Read strategy from PWConfig.getSeedStrategy()

`dimension/ExplorationSeedManager.java`:
- `Map<ResourceKey<Level>, Long> DIMENSION_SEEDS` — registered seeds
- `ThreadLocal<ResourceKey<Level>> CURRENT_DIMENSION` — context for mixin
- `registerDimensionSeed(key, seed)`, `modifySeed(key, originalSeed) → long`, `setCurrentDimension(key)`, `clearCurrentDimension()`

### 6. Dimension Factory
`dimension/DimensionFactory.java`:
- `createExplorationDimension(MinecraftServer server, ResourceLocation baseDim, long seed) → ServerLevel`
- Set ExplorationSeedManager.setCurrentDimension() BEFORE creating chunk generator (for structure gen mixin interception)
- Create NoiseBasedChunkGenerator: overworld (MultiNoiseBiomeSource + OVERWORLD settings), nether (Nether preset), end (TheEndBiomeSource)
- Generate unique key: `pw_{basename}_{counter}` via DimensionCounter
- Call `((IServerDimensionAccessor)server).pw$createRuntimeDimension(...)` 
- Clear dimension context in finally block

### 7. Dimension Counter
`dimension/DimensionCounter.java`:
- Persists counters in `data/pw_dimension_counters.dat` (simple properties format: type=count)
- `getNextDimensionId(String baseDim) → long` (increments and saves)
- `getCurrentCounter(String baseDim) → long`
- `resetCounters()`, `resetCounter(String baseDim)`
- `getAllCounters() → Map<String, Long>`

### 8. Dimension Registrar (Singleton)
`dimension/DimensionRegistrar.java`:
- `createDimensionsOnServerStart(MinecraftServer)`: iterate PWConfig.getEnabledDimensions(), generate seed via SeedManager, call DimensionFactory, track in maps
- Persistence mode logic: if PERSIST_UNTIL_ROTATION, load saved seed from PWSavedData, compare with new seed, skip recreation if same
- Track: `runtimeDimensions`, `dimensionMappings`, `dimensionSeeds`
- Singleton pattern with `initialize()` / `getInstance()`

### 9. Dimension Manager
`dimension/DimensionManager.java`:
- Runtime state: `dimensionMappings` (base→exploration key), `activeDimensions`, `playerLastDimension`
- `getExplorationDimension(ResourceLocation baseDim) → Optional<ServerLevel>`
- `isExplorationDimension(ResourceLocation)`, `onPlayerEnter/Leave`
- `evacuateAllPlayers()`: try return → try spawn → final fallback with effects

### 10. Dimension Cleanup
`dimension/DimensionCleanup.java`:
- `cleanupOldDimensions(MinecraftServer)`: scan `dimensions/parallelworlds/` folder, group by type, sort by modification time, delete old ones keeping N per config
- Pattern match folder names: `pw_{type}_{id}`

### 11. Wire Into Lifecycle
In `ParallelWorlds.onServerStarting()`:
- Initialize DimensionCounter from saved data
- Run DimensionCleanup
- Create dimensions via DimensionRegistrar
- Initialize DimensionManager

In `ParallelWorlds.onServerStopping()`:
- Evacuate all players
- Clear runtime tracking
- Save counters
- Shut down pre-generator (if started)

## Verification
- Server starts with exploration dimensions visible in F3 debug or `/pw list`
- Each enabled base dimension has one exploration copy created
- Seeds follow configured strategy (random/date/weekly)
- `debugSeed` config overrides
- Old folders cleaned up per retention count
- Repeated start/stop does not crash or leak resources
- GameTest `DimensionCreationTest`: assert dimensions registered, seed correct, cleanup happens

## Do NOT
- Implement teleportation (that's Epic 3)
- Create portal blocks (that's Epic 4)
- Implement pre-generation (that's Epic 7)
- Add commands beyond basic `/pw list` for testing
