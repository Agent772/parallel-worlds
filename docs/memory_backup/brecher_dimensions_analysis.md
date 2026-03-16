# BrecherDimensions Mod - Comprehensive Analysis

## Architecture Overview
A Minecraft mod that creates temporary, resettable exploration dimensions. Uses mixins to hook into Minecraft internals and runtime registry manipulation to create dimensions without datapacks.

## Core Components

### 1. ACCESSORS (Mixin Interfaces)
- **IRegistryAccessor** - Provides safe registry manipulation methods:
  - `setFrozen()`, `isFrozen()` - Control registry frozen state
  - `addMapping()`, `registerRuntime()` - Register entries at runtime
  - `removeRuntimeEntry()`, `cleanupAllRuntimeEntries()` - Cleanup
  - `getRuntimeEntries()`, `validateRegistryState()`, `dumpRegistryDiagnostics()`

- **IServerDimensionAccessor** - Server-level dimension operations:
  - `createRuntimeDimension()` - Create at runtime
  - `removeRuntimeDimension()` - Remove
  - `getRuntimeLevels()` - Map of all runtime dimensions
  - `cleanupAllRuntimeDimensions()` - Shutdown cleanup

### 2. TELEPORTATION (TeleportHandler)
**Major responsibilities:**
- `teleportToExploration()` - Main entry point for exploration dimension teleport
  - Saves return position before teleporting
  - Dismounts players from vehicles
  - Finds safe spawn positions (with dimension-specific logic for Nether/End)
  - Grants temporary invulnerability (100 ticks) + resistance + slow falling
  - Displays seed lock/reset info
  - Tracks in dimension manager
  
- `returnFromExploration()` - Exit exploration dimension
  - Clears optional inventory
  - Finds safe return position (tries to find surface if configured)
  - Optionally clears inventory based on config
  - Clears dimension tracking in saved data

- `teleportToWorldSpawn()` - Emergency fallback

**Position Finding:**
- `findSafePosition()` - Main search with phase-based radii (8, 16, 32, 48 blocks)
- `findSurfacePosition()` - Finds surface spawns for overworld-like dimensions
- `findNetherSafePosition()` - Specialized for Nether-like (ultra warm) dimensions
- `findEndSafePosition()` - Specialized for End-like (fixed time) dimensions
- `searchInRadius()` - Spiral search pattern with angle stepping
- `findSafeInColumn()` - Vertical search in single X,Z column
- `findHighestSolidBlock()` - Finds terrain surface for spawn above

**Safety Features:**
- Time budget: max 5ms per teleport search (prevents lag)
- Emergency platform creation if all searches fail
- Prevents teleporting while riding/mounted
- Cooldown enforcement (configurable, ops bypass)
- Teleport state tracking prevents double-firing events

### 3. MIXINS (Platform-Specific Hooks)

#### NeoForge Mixins:
- **MixinMinecraftServer** - Implements IServerDimensionAccessor
  - Handles runtime dimension creation with registry manipulation
  - Uses vanilla dimension types instead of registering custom ones
  - Manages LevelStem creation and serverLevel registration
  
- **MixinServerLevel** - Runtime dimension lifecycle
  - Marks runtime dimensions during construction
  - Prevents disk saves if configured
  - Prevents overall save operations
  - Logs runtime dimension cleanup
  
- **MixinRegistryFixed** - Advanced registry manipulation with reflection
  - Discovers registry field locations using 3 strategies
  - Temporarily unfreezes registry for runtime entries
  - Tracks runtime entries separately
  - Handles registry state validation
  
- **MixinServerChunkCache** - Chunk generation context
  - Logs creation for exploration dimensions
  - Seeds chunk generation appropriately
  
- **MixinChunkMap** - Chunk persistence
  - Prevents chunk saves for exploration dimensions
  
- **MixinPlayerList** - Player connection handling
  - Syncs runtime dimensions to new players
  - Evacuates players from exploration dimensions on join (fallback safety)
  - Handles dimension transitions
  
- **MixinEndGatewayBlock** - End gateway handling
  - Probably prevents End gateway teleportation
  
- **MixinLevelStem**, **MixinRandomState**, **MixinMinecraftPlayer** - Supporting mixins

#### Fabric Equivalents:
- Similar hierarchy but Fabric-specific implementations
- ServerPlayerMixin for Fabric-exclusive player handling
- Same mixin classes with fabric.mixin package

### 4. DATA PERSISTENCE (BrecherSavedData)
Extends SavedData - persists to level.dat

**Tracked Data:**
- `activeDimensions` - Set of ResourceLocations of active exploration dimensions
- `dimensionResetTimes` - When each dimension resets (long timestamp)
- `playerLastPositions` - Where players were last seen (UUID -> ResourceLocation)
- `dimensionAccessHistory` - Which players accessed which dimensions (UUID sets)
- `playerReturnPositions` - Where players teleported from (persistent 7-day expiry)
- `dimensionMetadata` - Creation time, access time, visit count, accessed by list
- `playerStats` - Per-player exploration statistics
- `playerLastKnownDimensions` - Last dimension each player was in
- `pregenTasks` - Chunk pre-generation progress
- `nextResetTime` - Upcoming reset timestamp

**Key Classes:**
- **ReturnPosition** - Record with pos, dimension, yRot, xRot, timestamp (expires after 7 days)
- **DimensionMetadata** - Tracks dimension creation, access, visited players
- **PlayerExplorationStats** - Tracks visits per dimension, loaded chunks, exploration time

### 5. DIMENSION MANAGEMENT

#### BrecherDimensionManager
**Runtime tracking:**
- `dimensionMappings` - Maps base dimension keys to exploration dimension keys
- `activeDimensions` - Set of active exploration dimension ResourceLocations
- `playerLastDimension` - Tracks which exploration dimension each player was in

**Methods:**
- `getExplorationDimension()` - Get ServerLevel by base dimension
- `isExplorationDimension()` - Check if ResourceLocation is exploration dim
- `getExplorationDimensions()` - Get all active exploration dimensions
- `onPlayerEnterExploration()`, `onPlayerLeaveExploration()` - Track player movement
- `getPlayersInExplorationDimensions()` - Get all current players
- `evacuateAllPlayers()` - Force return all players (server shutdown)
- `sendMessageToExplorationPlayers()` - Broadcast to exploration dimension players

#### DimensionRegistrar
**Creation/Registration:**
- `createDimensionsOnServerStart()` - Main entry point
- `getRuntimeDimensions()` - Get all created dimensions
- `getDimensionSeed()` - Get seed used for specific dimension
- `isRegistered()` - Check if base dimension registered

Uses SimpleSeedManager for seed generation (daily seeds or random).

#### DynamicDimensionFactory
**Core factory logic:**
- `createExplorationDimension()` - Creates ServerLevel at runtime
  - Uses ExplorationSeedManager to register seeds before generation
  - Creates appropriate ChunkGenerator (Nether, End, or Overworld)
  - Uses vanilla dimension types (maps base name to vanilla type)
  - Sets up LevelStem with chunk generator
  - Calls Services.DIMENSIONS.createDimension() (platform-specific)

#### ExplorationSeedManager
- Manages dimension -> seed mappings
- Tracks current dimension context during chunk generation
- Important for structure generation to use correct seed

### 6. CHUNK MANAGEMENT & PERFORMANCE

#### ChunkManager
**Tracking:**
- `loadedChunks` - Map of dimension -> set of ChunkPos loaded
- `chunkLoadCounts`, `chunkUnloadCounts` - Statistics

**Methods:**
- `configureForExploration()` - Initialize chunk tracking for dimension
- `forceUnloadAllChunks()` - Conditional aggressive unloading
- `performCleanup()` - Periodic chunk cleanup (respects time/memory limits)

#### MemoryMonitor
**Utilities:**
- `logMemoryUsage()` - Log current memory in MB
- `isMemoryPressureHigh()` - Check if >80% of max memory
- `getMemoryUsagePercent()` - Returns 0-100 percentage based on maxMemory (not totalMemory)
  - Uses -Xmx limit as denominator (not current heap size) for consistent readings

#### GenerationTask
**Per-dimension chunk pre-generation:**
- Time-based loop limiting (5ms/tick budget)
- Loads chunks to EMPTY status first to check if already generated
- Spiral iteration pattern with retry mechanism (3 retries max)
- Ticket leak prevention with removal scheduling
- TPS-aware throttling (adjusts batch size based on server TPS)
- Persists progress across server restarts via GenerationProgress

### 7. NETWORK/PACKETS

#### BrecherNetworking
**Payload Channel Constants:**
- DIMENSION_SYNC, DIMENSION_RESET, RESET_WARNING
- REGISTRY_SYNC, ENHANCED_REGISTRY_SYNC, CHUNKED_REGISTRY_SYNC

**Payloads:**
- **DimensionSyncPayload** - Notifies clients dimension exists (dimensionId, exists bool)
- **DimensionResetPayload** - Tells client when dimension resets (dimensionId, resetTime long)
- **ResetWarningPayload** - Warning message (minutesRemaining int, message str)
- **RegistrySyncPayload** - Dimension registry sync (dimensionId, nbtData bytes)
- **EnhancedRegistrySyncPayload** - Better registry sync (nbtData bytes)
- **ChunkedRegistrySyncPayload** - Split large syncs (chunkIndex, totalChunks, nbtData)

**Delegation:**
Uses Services.PACKETS for platform-specific networking (Fabric vs NeoForge).

### 8. UTILITIES

#### DimensionEnvironment
**Enum classification:**
- OVERWORLD_LIKE - Normal day/night cycle
- NETHER_LIKE - ultraWarm=true (no water, lava flows faster)
- END_LIKE - hasFixedTime=true, !hasCeiling

Uses dimension type properties (not names) for modded dimension compatibility.

#### DimensionCounterUtil
**Per dimension type counters:**
- Stored in `brecher_dimension_counters.dat` in world data folder
- Ensures unique exploration_overworld_0, exploration_nether_0, etc.
- Persists across restarts to avoid naming conflicts
- Dirty-flag optimization (saves on shutdown/admin commands)

#### DimensionCleanupUtil
**Old dimension cleanup:**
- Scans dimensions folder for exploration dimensions
- Groups by dimension type
- Deletes old ones, keeping N most recent per type (configurable)
- Cleans level.dat references to deleted dimensions
- Pattern-based folder identification

#### DimensionHelper
**Helper methods:**
- `getParentDimension()` - Extract parent dim from exploration_name_id pattern
- `getDimensionSeed()` - Get appropriate seed for structure location vs generation

#### InventoryKeeper
- Saves player inventory before dimension reset
- Restores inventory when returning

#### PlayerSnapshot
- Records player state for persistence

#### CorpseModCompat
**Corpse mod compatibility:**
- Detects corpse/gravestone mods (corpse, gravestones, yigd, tombstone, etc.)
- If present and configured, defers to corpse mod for inventory handling
- Checks if inventory already empty before handling

#### EventPriorityHelper
- Manages event priority (HIGH/NORMAL/LOW)
- When corpse mod present + defer enabled: uses LOW priority
- Otherwise uses HIGH to save inventory before item drops

### 9. EVENT HANDLERS (BrecherEventHandlers)
- `onPlayerJoin()` - Sync exploration dimension info
- `onPlayerLeave()` - Clear invulnerability (prevent god-mode persistence bug), cleanup stats
- `onPlayerChangeDimension()` - Track dimension changes
- `onPlayerDeath()` - Handle death in exploration dimensions (inventory preservation, etc.)

### 10. CONFIGURATION (BrecherConfig)
Managed settings for:
- Enabled dimensions, teleport cooldown, return message
- Chunk safety radius, safe position preferences
- Aggressive chunk unloading, tree cleanup
- Corpse mod deference
- Disk save prevention
- Chunk pre-generation settings
- Memory threshold (default 85%)

## Critical Implementation Details

### Registry Manipulation Strategy
1. **MixinRegistryFixed** uses 3-stage reflection discovery:
   - Stage 1: Direct class fields
   - Stage 2: Superclass hierarchy
   - Stage 3: Field name heuristics
2. **Temporary unfreezing** of registry allows runtime entries
3. **Separate tracking** of runtime entries to distinguish from vanilla
4. Throws `RegistryDiscoveryException` if critical fields not found

### Dimension Creation Flow
1. Server startup calls `DimensionRegistrar.createDimensionsOnServerStart()`
2. For each enabled base dimension:
   - Generate seed via SimpleSeedManager
   - Call `DynamicDimensionFactory.createExplorationDimension()`
   - Register seed in ExplorationSeedManager
   - Create appropriate ChunkGenerator (maps base name to vanilla type)
   - Call platform-specific `Services.DIMENSIONS.createDimension()`
   - Add to BrecherDimensionManager tracking

### Teleportation Flow
1. Player teleports via command/UI → `TeleportHandler.teleportToExploration()`
2. Save return position (unless already in exploration dim)
3. Dismount from vehicles
4. Find safe spawn position (time-budgeted search with fallbacks)
5. Clear dangerous effects (levitation, blindness)
6. Call `Services.TELEPORT.preTeleport()` → actual teleport → `postTeleport()`
7. Grant temporary safety effects
8. Send welcome/seed lock info
9. Track in dimension manager

### Return Flow
1. Player returns → `TeleportHandler.returnFromExploration()`
2. Optionally clear inventory
3. Find safe return position (with surface preference if configured)
4. Teleport back with safety effects
5. Clear saved data references
6. Track departure in dimension manager

### Chunk Generation Strategy
- Per-dimension `GenerationTask` manages pre-generation
- Runs on main thread, time-limited (5ms/tick)
- Spiral iteration with angular step adjustment
- Loads to EMPTY status first to check if already generated
- Retries failed chunks up to 3 times
- Progress persisted via GenerationProgress resource
- Automatically pauses if memory >85% or TPS <18

## Testing & Validation Complexity
- Cross-platform (NeoForge & Fabric) with similar but separate mixin implementations
- Heavy reflection usage in MixinRegistryFixed for field discovery
- Complex position-finding logic with multiple fallback strategies
- Memory monitoring and throttling to prevent lag
- Synchronization between server and client via packets
- Safe evacuation logic to prevent players being stuck in dimensions

## Known Safety Features
- Invulnerability when landing (prevents immediate damage)
- Automatic dismounting to prevent vehicle glitches
- Ticket leak prevention in chunk generation
- Emergency platform as final fallback spawn location
- Player evacuation on server shutdown
- Cleanup of invulnerability on player disconnect (prevents god-mode persistence)
- Time budget enforcement to prevent lag spikes
- Memory pressure monitoring

---

Total Class Count: 40+ files across common, neoforge, and fabric modules.
Main focus: Runtime registry manipulation + dimensional teleportation + chunk management.
