# Sub-Agent Instructions: Epic 1 — Project Skeleton & Configuration

## Objective
Create a bootable NeoForge 1.21.1 mod with comprehensive server-side configuration. No gameplay logic yet — just the skeleton that all other epics build on.

## Context
- **Workspace**: `d:\Git\parallel-world`
- **Mod ID**: `parallelworlds`
- **Package**: `com.agent772.parallelworlds`
- **Java**: 21, NeoForge 21.1.209
- **Existing**: `build.gradle`, `gradle.properties`, `settings.gradle` already configured. `src/main/java/com/agent772/` is empty.

## Tasks

### 1. Create Main Mod Class
File: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java`
- `@Mod("parallelworlds")` annotation
- Constructor: register mod event bus listeners
- Static `LOGGER` (LoggerFactory)
- `onServerStarting(ServerStartingEvent)` — placeholder for dimension init
- `onServerStopping(ServerStoppingEvent)` — placeholder for cleanup
- Register commands via `RegisterCommandsEvent`

### 2. Create Config System
File: `src/main/java/com/agent772/parallelworlds/config/PWConfig.java`
- Static holder class with getters for all settings
- Populated from NeoForge config values
- Config groups:
  - General: `enabledDimensions` (List<String>), `explorationBorder` (int)
  - Seed: `seedStrategy` (String: "random"/"date"/"weekly"), `weeklyResetDay` (String), `debugSeed` (long)
  - Portal: `portalFrameBlock` (String ResourceLocation), `portalEnabled` (boolean)
  - Restrictions: `enderChestMode` (String: "BLOCK"/"WARN"/"ALLOW"), `restrictedItemTag` (String), `restrictedItemMode` (String)
  - Persistence: `persistenceMode` (String: "PERSIST_UNTIL_ROTATION"/"REGENERATE_EACH_RESTART"), `oldDimensionRetentionCount` (int)
  - Performance: `chunkUnloadDelay` (int), `maxChunksPerPlayer` (int), `aggressiveChunkUnloading` (bool), `preventDiskSaves` (bool), pregen settings (enabled, chunksPerTick, maxTickMs, minTPS, memoryThreshold, defaultRadius, autoStart, autoResume, ticketDuration, logInterval)
  - Safety: `teleportCooldown` (int seconds), `teleportSafetyRadius` (int), `keepInventoryInExploration` (bool), `clearInventoryOnReturn` (bool)
  - Locks: `dimensionLocksEnabled` (bool), `dimensionLocks` (list of "dim=advancement" strings)

File: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java`
- NeoForge `ModConfigSpec.Builder` based config definition
- All values with comments, ranges, defaults
- Register as SERVER config type

### 3. Update neoforge.mods.toml
File: `src/main/templates/META-INF/neoforge.mods.toml`
- Remove Create/TFMG dependencies
- Update description, authors, license to match gradle.properties
- Set `modId = "parallelworlds"`
- Required dep: neoforge [21.1,)

### 4. Update build.gradle
- Ensure mixin support is configured (NeoForge MDK includes it)
- Add accessTransformer reference if needed
- Uncomment/add any needed dependencies

## Verification
- `./gradlew build` succeeds
- Mod loads in `./gradlew runServer` without crash
- `config/parallelworlds-server.toml` generated with all settings
- All PWConfig getters return expected defaults
- Server log shows "Parallel Worlds initializing" or similar

## Do NOT
- Implement any gameplay logic (dimensions, teleportation, etc.)
- Add client-side code
- Create block/item registrations
- Add mixins (that's Epic 2)
