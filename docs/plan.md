# Plan: Parallel Worlds – Mining Dimension Mod

## TL;DR
Build a NeoForge 1.21.1 mod (`parallelworlds`) that creates configurable, temporary mining dimensions with seed rotation, custom portal entrance, item/ender-chest restrictions by tag, and performance-focused chunk management. Architecture is inspired by BrecherDimensions' patterns but 100% original implementation under MIT license.

---

## Architecture Overview

**Package**: `com.agent772.parallelworlds`  
**Mod ID**: `parallelworlds`  
**Platform**: NeoForge 1.21.1 (primary), Fabric/multiloader (future)  
**Java**: 21  

### Core Packages
```
com.agent772.parallelworlds/
├── ParallelWorlds.java            # Mod entry point
├── config/                        # Configuration system
├── dimension/                     # Dynamic dimension creation & lifecycle
├── generation/                    # Chunk pre-generation, spiral iterator
├── portal/                        # Custom portal frame detection & teleportation
├── restriction/                   # Ender chest / item tag restrictions
├── data/                          # SavedData persistence (return positions, stats)
├── command/                       # Player & admin commands
├── event/                         # Event handlers (join/leave/death/interact)
├── network/                       # Packet payloads for client sync
├── mixin/                         # Registry manipulation, seed injection
├── accessor/                      # Mixin accessor interfaces
├── performance/                   # Chunk manager, memory monitor
└── util/                          # Dimension helpers, environment detection
```

---

## Epics & Steps

### Epic 1: Project Skeleton & Configuration System
**Goal**: Bootable mod with comprehensive config, no gameplay yet.

**Steps:**
1. Create `ParallelWorlds.java` entry point with `@Mod` annotation, `onServerStarting`/`onServerStopping` lifecycle hooks
2. Create `PWConfig.java` — platform-agnostic config holder with all settings (seed strategy, enabled dimensions, portal settings, restriction mode, performance tuning, persistence mode)
3. Create `PWConfigSpec.java` — config spec with defaults and comments
4. Implement NeoForge config loading via `ModConfigEvent` → populates `PWConfig`
5. Register server lifecycle events (starting, started, stopping)
6. Create `neoforge.mods.toml` from template with correct metadata

**Config Groups (PWConfig):**
- **General**: `enabledDimensions` (list), `explorationBorder` (int, -1=parent)
- **Seed**: `seedStrategy` (random/date/weekly), `weeklyResetDay`, `debugSeed`
- **Portal**: `portalFrameBlock` (ResourceLocation), `portalIgniterItem` (ResourceLocation), `portalEnabled` (bool)
- **Restrictions**: `enderChestMode` (BLOCK/WARN/ALLOW), `restrictedItemTag` (ResourceLocation), `restrictedItemMode` (BLOCK/WARN/ALLOW)
- **Persistence**: `persistenceMode` (PERSIST_UNTIL_ROTATION/REGENERATE_EACH_RESTART), `oldDimensionRetentionCount`
- **Performance**: `chunkUnloadDelay`, `maxChunksPerPlayer`, `aggressiveChunkUnloading`, `preventDiskSaves`, `pregenEnabled`, `pregenChunksPerTick`, `pregenMaxTickMs`, `pregenMinTPS`, `pregenMemoryThreshold`
- **Safety**: `teleportCooldown`, `teleportSafetyRadius`, `keepInventoryInExploration`, `clearInventoryOnReturn`
- **Locks**: `dimensionLocksEnabled`, `dimensionLocks` (map of dimension→advancement)

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java`
- `src/main/java/com/agent772/parallelworlds/config/PWConfig.java`
- `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java`
- `src/main/resources/META-INF/neoforge.mods.toml`

**Verification:**
- Mod loads in dev environment without crash
- Config file generated at `config/parallelworlds-server.toml`
- All config values readable via `PWConfig.get*()`
- `./gradlew build` succeeds

---

### Epic 2: Dynamic Dimension Creation (Core Engine)
**Goal**: Create/destroy exploration dimensions at runtime with custom seeds.

**Steps:**
1. Create `IServerDimensionAccessor` mixin accessor interface — methods: `pw$createRuntimeDimension(key, type, generator, seed)`, `pw$removeRuntimeDimension(key)`
2. Create `MixinMinecraftServer` — implements `IServerDimensionAccessor`, manipulates `levels` map
3. Create `IRegistryAccessor<T>` mixin accessor — methods: `pw$unfreeze()`, `pw$registerRuntime(key, value)`, `pw$removeRuntime(key)`, `pw$refreeze()`
4. Create `MixinMappedRegistry` — implements `IRegistryAccessor`, handles frozen/unfrozen state transitions
5. Create access widener/transformer for `MappedRegistry.frozen`, `MinecraftServer.levels`, `MinecraftServer.storageSource`
6. Create `SeedManager.java` — seed generation strategies: random, date-based (daily), weekly with configurable reset day. Murmurhash-style mixing function.
7. Create `ExplorationSeedManager.java` — ThreadLocal current dimension + Map<ResourceKey, Long> seed registry. `modifySeed()` returns custom seed for exploration dims, original for vanilla.
8. Create `DimensionFactory.java` — creates `ServerLevel` instances: NoiseBasedChunkGenerator for each dimension type (overworld/nether/end), unique naming with counter (`pw_overworld_0`)
9. Create `DimensionCounter.java` — persists per-type counters in world data folder
10. Create `DimensionRegistrar.java` (singleton) — `createDimensionsOnServerStart()`: iterates enabled dims, generates seed, calls factory, tracks mappings
11. Create `DimensionManager.java` — runtime tracking: dimension mappings, active dimensions, player locations. `evacuateAllPlayers()` with fallback chain.
12. Create `DimensionCleanup.java` — scans old dimension folders, deletes beyond retention count, cleans `level.dat` refs
13. Implement persistence mode logic in `DimensionRegistrar`: if PERSIST_UNTIL_ROTATION, check saved seed vs new seed — skip recreation if same; if REGENERATE_EACH_RESTART, always create fresh
14. Create `MixinChunkMap` — prevent chunk file persistence when `preventDiskSaves` enabled
15. Create `MixinServerLevel` — seed injection during init, save prevention hooks

**Key patterns to follow (from Brecher analysis):**
- Registry manipulation: unfreeze → register → refreeze (3-stage with field discovery fallback)
- Seed context: set ThreadLocal BEFORE creating chunk generator so mixins can intercept
- Dimension naming: `pw_{basetype}_{counter}` format
- Safety: wrap registry ops in try/finally, throw custom exception on failure

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/accessor/IServerDimensionAccessor.java`
- `src/main/java/com/agent772/parallelworlds/accessor/IRegistryAccessor.java`
- `src/main/java/com/agent772/parallelworlds/mixin/MixinMinecraftServer.java`
- `src/main/java/com/agent772/parallelworlds/mixin/MixinMappedRegistry.java`
- `src/main/java/com/agent772/parallelworlds/mixin/MixinChunkMap.java`
- `src/main/java/com/agent772/parallelworlds/mixin/MixinServerLevel.java`
- `src/main/java/com/agent772/parallelworlds/dimension/SeedManager.java`
- `src/main/java/com/agent772/parallelworlds/dimension/ExplorationSeedManager.java`
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionCounter.java`
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionRegistrar.java`
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionManager.java`
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionCleanup.java`
- `src/main/resources/parallelworlds.mixins.json`
- `src/main/resources/META-INF/accesstransformer.cfg`

**Depends on:** Epic 1

**Verification:**
- Server starts, exploration dimensions appear in `/pw list`
- Seed changes between restarts (random mode)
- Seed stays same within day (date mode) / week (weekly mode)
- `debugSeed` config overrides all strategies
- Old dimension folders cleaned up per retention count
- No crash on repeated start/stop cycles
- GameTest: `DimensionCreationTest` — verifies dimensions registered, seed correct, cleanup works

---

### Epic 3: Teleportation & Safe Spawning
**Goal**: Players can teleport to/from mining dimensions safely.

**Steps:**
1. Create `TeleportHandler.java` — main teleport logic:
   - `teleportToExploration(ServerPlayer, ServerLevel)` — save return position, find safe spawn, teleport, apply safety effects
   - `returnFromExploration(ServerPlayer)` — load return position, teleport back, optional inventory clear
   - `forceReturnToSpawn(ServerPlayer)` — emergency fallback to world spawn
2. Implement safe position finder with multi-phase search:
   - Phase 1: 8-block spiral search
   - Phase 2: 16-block radius
   - Phase 3: 32-block radius (Nether/extended)
   - Phase 4: emergency platform creation (3×3 obsidian)
   - Time budget: 5ms max per search to prevent lag
3. Dimension-specific spawn logic:
   - Overworld: prefer surface, avoid water/lava
   - Nether: find enclosed spaces with floor, avoid lava
   - End: find solid ground or create platform
4. Implement cooldown system (ConcurrentHashMap<UUID, Long>, configurable seconds, ops bypass)
5. Post-teleport safety: 100-tick invulnerability, DAMAGE_RESISTANCE II, SLOW_FALLING, clear LEVITATION/BLINDNESS
6. Dismount players from vehicles/entities before teleport
7. Create `ReturnPosition` record — pos, dimension, rotation, timestamp, 7-day expiry

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/teleport/TeleportHandler.java`
- `src/main/java/com/agent772/parallelworlds/data/ReturnPosition.java`

**Depends on:** Epic 2

**Verification:**
- Teleport to mining dimension places player on safe ground
- Return teleports to exact saved position
- Cooldown prevents spam (non-ops)
- No fall damage after teleport
- Emergency platform created when no safe spot found
- Teleport during vehicle ride works without glitches
- GameTest: `TeleportSafetyTest` — verifies safe position finding, cooldown, return position

---

### Epic 4: Custom Portal System
**Goal**: Custom portal frame that transports players to mining dimension.

**Steps:**
1. Register custom portal frame block (`pw_portal_frame`) — simple block with BlockEntity optional
2. Register portal igniter item or use flint_and_steel interaction
3. Create `PortalShape.java` — portal frame detection:
   - Rectangular frame validation (min 3×3 inner, max configurable)
   - Frame block validation (must be `pw_portal_frame` or configurable block)
   - Orientation detection (X-axis or Z-axis)
4. Create `PortalBlock.java` — the portal fill block (like nether portal):
   - Non-solid, render with custom particles
   - Collision handler: start teleport timer (like nether portal standing)
   - Or instant teleport on entry (configurable)
5. Create portal activation logic:
   - Right-click frame with igniter → detect shape → fill with portal blocks
   - Breaking frame block → destroy portal
6. Create portal teleport handler:
   - Player stands in portal → after configurable delay → `TeleportHandler.teleportToExploration()`
   - In mining dimension, portal returns player via `TeleportHandler.returnFromExploration()`
7. Register blocks/items via DeferredRegister
8. Add block models, textures, blockstates, loot tables (data gen)
9. Add crafting recipe for portal frame block (data gen)

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/portal/PWPortalFrameBlock.java`
- `src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java`
- `src/main/java/com/agent772/parallelworlds/portal/PortalShape.java`
- `src/main/java/com/agent772/parallelworlds/portal/PortalActivation.java`
- `src/main/java/com/agent772/parallelworlds/registry/PWBlocks.java`
- `src/main/java/com/agent772/parallelworlds/registry/PWItems.java`
- `src/main/java/com/agent772/parallelworlds/datagen/PWBlockStateProvider.java`
- `src/main/java/com/agent772/parallelworlds/datagen/PWRecipeProvider.java`
- `src/main/java/com/agent772/parallelworlds/datagen/PWLootTableProvider.java`
- Assets: textures, models, blockstates, lang files

**Depends on:** Epic 3

**Verification:**
- Frame builds correctly, validates shape
- Igniter activates portal, fills inner area
- Breaking frame destroys portal
- Standing in portal teleports after delay
- Portal works bidirectionally (overworld ↔ mining)
- Invalid frame shapes rejected
- Portal disabled when config `portalEnabled=false`
- GameTest: `PortalShapeTest` — validates frame detection, activation, destruction

---

### Epic 5: Item & Ender Chest Restrictions
**Goal**: Configurable restrictions on ender chests and tagged items in mining dimensions.

**Steps:**
1. Create item tag `parallelworlds:restricted_items` — default includes `minecraft:ender_chest`
2. Create `RestrictionHandler.java`:
   - `onBlockInteract(player, block, pos)` — checks ender chest interaction
   - `onItemUse(player, itemStack)` — checks item tag
   - Modes: BLOCK (cancel + message), WARN (allow + log + message), ALLOW (no action)
3. Hook into `PlayerInteractEvent.RightClickBlock` — intercept ender chest open
4. Hook into `PlayerInteractEvent.RightClickItem` — intercept restricted item use
5. Hook into block placement events — prevent placing restricted blocks
6. Create translatable messages for each restriction mode
7. Log warnings to server console when WARN mode triggers

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/restriction/RestrictionHandler.java`
- `src/main/java/com/agent772/parallelworlds/restriction/RestrictionMode.java` (enum: BLOCK, WARN, ALLOW)
- `src/main/resources/data/parallelworlds/tags/items/restricted_items.json`
- `src/main/resources/data/parallelworlds/tags/blocks/restricted_blocks.json`

**Depends on:** Epic 2 (needs dimension detection)

**Verification:**
- BLOCK mode: ender chest cannot be opened, message shown
- WARN mode: ender chest opens, warning logged + shown
- ALLOW mode: no interference
- Custom tag items also restricted
- Restrictions only apply inside mining dimensions
- GameTest: `RestrictionTest` — verifies each mode for ender chest and tagged items

---

### Epic 6: Data Persistence & Lifecycle
**Goal**: Persist player state, dimension metadata, and pre-gen progress across restarts.

**Steps:**
1. Create `PWSavedData.java` (extends SavedData):
   - `playerReturnPositions: Map<UUID, ReturnPosition>`
   - `dimensionMetadata: Map<ResourceLocation, DimensionMetadata>`
   - `playerStats: Map<UUID, PlayerExplorationStats>`
   - `activeDimensions: Set<ResourceLocation>`
   - `dimensionSeeds: Map<ResourceLocation, Long>` (for persistence mode comparison)
   - `pregenTasks: Map<ResourceLocation, GenerationProgress>`
   - `manualUnlocks: Map<UUID, Set<ResourceLocation>>`
   - Full NBT serialization with backwards-compat loading
2. Create `DimensionMetadata.java` record — creation time, last access, visitors, visit count
3. Create `PlayerExplorationStats.java` — visits per dimension, total time, chunks loaded
4. Implement seed comparison on startup: load saved seed → compare with generated seed → if same, reuse dimension data; if different, cleanup old and create new
5. Wire `PWSavedData` into server starting/stopping lifecycle

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java`
- `src/main/java/com/agent772/parallelworlds/data/DimensionMetadata.java`
- `src/main/java/com/agent772/parallelworlds/data/PlayerExplorationStats.java`

**Depends on:** Epic 2, Epic 3

**Verification:**
- Return positions survive server restart
- Dimension metadata updates on player access
- Pre-gen tasks resume after restart
- Seed comparison correctly triggers recreation vs reuse
- Corrupted NBT handled gracefully (no crash, reset to defaults)
- GameTest: `PersistenceTest` — save/load cycle, seed comparison logic

---

### Epic 7: Performance & Chunk Management
**Goal**: Optimized chunk lifecycle and pre-generation with TPS protection.

**Steps:**
1. Create `ChunkManager.java`:
   - `configureForExploration(ServerLevel)` — set unload delays, ticket management
   - `forceUnloadAllChunks(ServerLevel)` — for dimension teardown
   - `performCleanup(MinecraftServer)` — periodic cleanup of empty dimensions
2. Create `MemoryMonitor.java`:
   - `isMemoryPressureHigh()` — true if >configurable% of max heap
   - `logMemoryUsage(context)` — debug logging
3. Create `ChunkPreGenerator.java`:
   - `startGeneration(server, dimensionKey, radius)` — create GenerationTask
   - `tick(server)` — process active tasks per server tick
   - `resumeSavedTasks(server)` — restore from PWSavedData
   - `pauseGeneration()`, `resumeGeneration()`, `stopGeneration()`
4. Create `GenerationTask.java`:
   - SpiralIterator-based chunk processing
   - Time budget: configurable ms per tick (default 5ms)
   - TPS-based throttling: full speed ≥19.5, reduced ≥19.0, minimum ≥18.0, pause <18.0
   - Memory-based throttling: pause if >threshold%
   - Ticket management: add before load, schedule removal to prevent leaks
   - Retry failed chunks (max 3 attempts)
   - Track: chunks generated, chunks skipped, estimated time remaining
5. Create `SpiralIterator.java` — resumable outward spiral from center, serializable state
6. Create `GenerationProgress.java` — thread-safe progress tracking with AtomicLong counters

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/performance/ChunkManager.java`
- `src/main/java/com/agent772/parallelworlds/performance/MemoryMonitor.java`
- `src/main/java/com/agent772/parallelworlds/generation/ChunkPreGenerator.java`
- `src/main/java/com/agent772/parallelworlds/generation/GenerationTask.java`
- `src/main/java/com/agent772/parallelworlds/generation/SpiralIterator.java`
- `src/main/java/com/agent772/parallelworlds/generation/GenerationProgress.java`

**Depends on:** Epic 2

**Verification:**
- Pre-gen runs without dropping TPS below 18
- Pre-gen pauses when memory >threshold
- Pre-gen resumes after restart from saved progress
- Spiral iterator visits all chunks in radius
- Chunk tickets cleaned up (no ticket leak)
- `status` command shows accurate progress, rate, ETA
- GameTest: `PreGenTest` — spiral coverage, throttling behavior, persistence

---

### Epic 8: Commands
**Goal**: Player and admin commands for dimension management.

**Steps:**
1. Create `PWCommands.java` — top-level command registration:
   - `/pw list` — show available mining dimensions
   - `/pw tp <dimension>` — teleport to mining dimension (checks locks)
   - `/pw return` — return to previous dimension
   - `/pw info` — show player's current dimension info
   - `/pw help` — show help
2. Admin commands (permission level 2):
   - `/pwadmin returnall` — evacuate all players
   - `/pwadmin info <dimension>` — dimension metadata
   - `/pwadmin stats` — global statistics
   - `/pwadmin pregen start <dim> [radius]` — start pre-generation
   - `/pwadmin pregen stop/pause/resume/status <dim>`
   - `/pwadmin pregen stopall`
   - `/pwadmin unlock/lock <player> <dimension>` — manual dimension locks
   - `/pwadmin seed <dimension>` — show current seed
   - `/pwadmin reload` — reload config
3. Tab completion via suggestion providers for dimension names
4. Formatted output with color coding and thousands separators

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/command/PWCommands.java`
- `src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java`

**Depends on:** Epic 2, Epic 3, Epic 7

**Verification:**
- All commands work and show correct output
- Permission checks enforced (admin commands require op)
- Tab completion works
- Invalid dimension names show error
- GameTest: `CommandTest` — execute each command, verify output

---

### Epic 9: Event Handling & Player Lifecycle
**Goal**: Handle all player lifecycle events in mining dimensions.

**Steps:**
1. Create `PWEventHandlers.java`:
   - `onPlayerJoin` — sync dimension info, evacuate if dimension no longer exists
   - `onPlayerLeave` — clear invulnerability (prevent god-mode persist), save location
   - `onDimensionChange` — track entry/exit, show messages, optional inventory clear on return
   - `onPlayerDeath` — optional keepInventory in mining dims, inventory save
   - `onPlayerRespawn` — restore inventory, optionally force respawn in overworld
   - `onBlockInteract` — delegate to RestrictionHandler
   - `onServerTick` — delegate to ChunkPreGenerator.tick()
2. Register all events in mod constructor via NeoForge event bus
3. Create `InventoryKeeper.java` — save/restore player inventory (for keepInventory + death handling)

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`
- `src/main/java/com/agent772/parallelworlds/util/InventoryKeeper.java`

**Depends on:** Epic 2, 3, 5, 6

**Verification:**
- Join after dimension deleted → evacuated safely
- Leave → invulnerability cleared
- Death in mining dim with keepInventory → items preserved
- Inventory clear on return works when configured
- GameTest: `EventLifecycleTest` — simulate join/leave/death/respawn

---

### Epic 10: Networking & Client Sync
**Goal**: Sync dimension state to clients for proper rendering and UI.

**Steps:**
1. Create packet payloads:
   - `DimensionSyncPayload` — tells client dimension exists/removed
   - `DimensionResetPayload` — tells client about upcoming reset time
   - `ResetWarningPayload` — countdown warning
2. Create `PWNetworking.java` — register packets via `RegisterPayloadHandlersEvent`
3. Send dimension sync on: dimension creation, player join, dimension removal
4. Send reset warnings periodically (configurable interval)
5. Client handler: track known dimensions, show HUD warnings

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java`
- `src/main/java/com/agent772/parallelworlds/network/payload/DimensionSyncPayload.java`
- `src/main/java/com/agent772/parallelworlds/network/payload/DimensionResetPayload.java`
- `src/main/java/com/agent772/parallelworlds/network/payload/ResetWarningPayload.java`
- `src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java`

**Depends on:** Epic 2

**Verification:**
- Client receives dimension sync on join
- New dimensions synced when created
- Reset warnings appear on action bar
- No desync when player teleports between dimensions

---

### Epic 11: Threading Research Spike
**Goal**: Investigate feasibility of running mining dimension on separate thread.

**Steps:**
1. Research Minecraft's thread safety model:
   - What operations are thread-safe in ServerLevel?
   - What locks/synchronization exist in chunk loading?
   - How does the tick loop interact with chunk generation?
2. Investigate existing multi-threaded dimension approaches:
   - C2ME (Concurrent Chunk Management Engine) — Fabric mod that parallelizes chunk gen
   - Dimensional Threading (1.16 mod) — ran dims on separate threads
   - Identify what broke and what worked
3. Prototype options:
   - **Option A**: Async chunk generation only (safest — chunk gen already partially async in vanilla)
   - **Option B**: Separate tick thread for mining dim (complex — entity ticking, block updates, event dispatch all need synchronization)
   - **Option C**: Worker thread pool for heavy operations (pre-gen, cleanup) while ticking stays on main thread
4. Document findings with risk assessment and recommendation
5. If feasible, create `ThreadedDimensionTicker.java` prototype

**Files to create (documentation):**
- `docs/threading-research.md`

**Depends on:** Epic 2 (needs working dimensions to test against)
**Blocks:** Nothing — this is a spike, results inform future work

**Verification:**
- Document produced with clear recommendation
- If prototype created: no data corruption, no deadlocks, TPS improvement measurable

---

### Epic 14: Third-Party Mod Data Cleanup
**Goal**: Clean up cached data from Xaero's World Map, Xaero's Minimap, JourneyMap, and Distant Horizons when exploration dimensions are regenerated or deleted.

**Steps:**
1. Add `modCompatCleanupEnabled` config toggle (default: true)
2. Modify `DimensionCleanup` to record deleted dimension folder names in `PWSavedData.pendingCleanupDimensions`
3. Create `DimensionCleanupPayload` — server-to-client packet listing deleted dimension paths
4. Create `ModDataCleanupHandler` — client-side handler that detects installed mods and deletes cached data:
   - Xaero's World Map: delete `XaeroWorldMap/<server>/DIM%parallelworlds%<dimpath>/`
   - Xaero's Minimap: delete `XaeroWaypoints/<server>/dim%parallelworlds%<dimpath>/`
   - JourneyMap: delete `journeymap/data/<server>/parallelworlds~<dimpath>/`
   - Distant Horizons: delete matching SQLite databases from `config/DistantHorizons/data/<server>/`
5. Wire into `PWEventHandlers.onPlayerLoggedIn()` — send cleanup packet to joining players with pending cleanups
6. Register payload in `PWNetworking`
7. Validate dimension path strings against `pw_\w+_\d+` regex to prevent path traversal
8. All mod detection via `ModList.get().isLoaded()` — no hard dependencies

**Files to create:**
- `src/main/java/com/agent772/parallelworlds/client/ModDataCleanupHandler.java`
- `src/main/java/com/agent772/parallelworlds/network/payload/DimensionCleanupPayload.java`

**Depends on:** Epic 2, Epic 10

**Verification:**
- No errors when target mods are not installed
- Cached map/LOD data deleted after dimension regeneration
- Config toggle disables all cleanup behavior
- Path traversal attempts in packets are rejected
- Locked files log warnings without crashing
- `./gradlew build` succeeds

---

## Relevant Files (Existing)

- [build.gradle](build.gradle) — add mixin plugin, access transformer config
- [gradle.properties](gradle.properties) — mod metadata (already configured)
- [settings.gradle](settings.gradle) — may need mixin plugin
- [src/main/templates/META-INF/neoforge.mods.toml](src/main/templates/META-INF/neoforge.mods.toml) — update mod description, remove Create/TFMG deps

### Reference (BrecherDimensions patterns — DO NOT COPY CODE):
- `other_src/BrecherDimensions-main/common/src/main/java/net/tinkstav/brecher_dim/dimension/DynamicDimensionFactory.java` — dimension creation pattern
- `other_src/BrecherDimensions-main/common/src/main/java/net/tinkstav/brecher_dim/dimension/ExplorationSeedManager.java` — ThreadLocal seed context pattern
- `other_src/BrecherDimensions-main/common/src/main/java/net/tinkstav/brecher_dim/generation/GenerationTask.java` — TPS-aware chunk gen pattern
- `other_src/BrecherDimensions-main/common/src/main/java/net/tinkstav/brecher_dim/teleport/TeleportHandler.java` — safe position search pattern

---

## Epic Dependency Graph

```
Epic 1 (Skeleton + Config)
  └─► Epic 2 (Dimension Engine) ◄── CRITICAL PATH
        ├─► Epic 3 (Teleportation)
        │     └─► Epic 4 (Portal System)
        ├─► Epic 5 (Restrictions) [parallel with 3]
        ├─► Epic 6 (Persistence) [depends on 2+3]
        ├─► Epic 7 (Performance) [parallel with 3]
        ├─► Epic 8 (Commands) [depends on 2+3+7]
        ├─► Epic 9 (Events) [depends on 2+3+5+6]
        ├─► Epic 10 (Networking) [parallel with 3]
        │     └─► Epic 14 (Mod Data Cleanup) [depends on 2+10]
        └─► Epic 11 (Threading Spike) [independent research]
```

**Parallelizable after Epic 2:** Epics 3, 5, 7, 10, 11 can all start simultaneously.

---

## Verification Strategy

### Automated Tests (GameTest Framework)
Each epic has associated GameTests registered via `@GameTest`:

1. `DimensionCreationTest` — verify runtime dimension creation, seed assignment, cleanup
2. `TeleportSafetyTest` — safe spawn in all 3 dim types, cooldown, return
3. `PortalShapeTest` — frame validation, activation, destruction
4. `RestrictionTest` — each mode × ender chest + tagged items
5. `PersistenceTest` — save/load cycle, seed rotation detection
6. `PreGenTest` — spiral coverage, throttling, resume
7. `CommandTest` — execute commands, verify output
8. `EventLifecycleTest` — join/leave/death/respawn sequences

### Manual Testing Checklist
- [ ] Fresh server start creates dimensions
- [ ] Portal construction and activation
- [ ] Teleport to/from mining dimension
- [ ] Ender chest restriction modes
- [ ] Seed changes on date/week boundary
- [ ] Pre-gen status command shows progress
- [ ] Server restart preserves return positions
- [ ] Multiple players in mining dimension simultaneously
- [ ] Server stop evacuates all players cleanly
- [ ] Config reload applies changes

---

## Decisions

- **Original code only** — BrecherDimensions is All Rights Reserved; we study patterns but write 100% original implementation
- **NeoForge 1.21.1 primary** — Fabric/multiloader is future work, not in initial scope
- **Portal: custom frame** — like Nether portal with configurable frame block
- **Restrictions: configurable** — BLOCK/WARN/ALLOW modes for ender chest and tagged items
- **Persistence: configurable** — server admin chooses persist-until-rotation or regenerate-each-restart
- **Threading: research first** — spike in Epic 11 before committing to approach
- **Mod ID: `parallelworlds`** — matches gradle.properties

## Scope Exclusions
- Fabric/multiloader support (future epic)
- 1.20.1 Forge backport (future epic)
- Client-side rendering (portal effects, HUD overlays — minimal in v1)
- Modded dimension support (only vanilla overworld/nether/end initially)
- Advancement locks (configurable but not critical for v1)
- Corpse mod compatibility (future consideration)
