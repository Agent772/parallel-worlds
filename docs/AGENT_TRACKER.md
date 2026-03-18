# Parallel Worlds — Agent Work Tracker

## Quick Reference
- **Mod ID**: `parallelworlds`
- **Package**: `com.agent772.parallelworlds`
- **Platform**: NeoForge 1.21.1, Java 21
- **Plan**: [docs/Plan.md](Plan.md)
- **Epics**: [docs/epics/*](Epics)
- **MemoryBackup**: [docs/memory_backup](Memorybackups)
- **Build**: `./gradlew build`
- **Run Dev Server**: `./gradlew runServer`

---

## Epic Status

| Epic | Name | Status | Dependencies | Notes |
|------|------|--------|--------------|-------|
| 1 | Project Skeleton & Config | ✅ DONE | — | Entry point, config spec, mod metadata |
| 2 | Dynamic Dimension Creation | ✅ DONE | Epic 1 | Mixins, registry manipulation, seed mgr |
| 3 | Teleportation & Safe Spawning | ✅ DONE | Epic 2 | TeleportHandler, safe position finder, DimensionEnvironment util |
| 4 | Custom Portal System | ✅ DONE | Epic 3 | Portal blocks, frame detection, activation |
| 5 | Item & Ender Chest Restrictions | ✅ DONE | Epic 2 | RestrictionHandler, tag-based config |
| 6 | Data Persistence & Lifecycle | ✅ DONE | Epic 2, 3 | PWSavedData, schedule-based seed rotation, configurable persistence |
| 7 | Performance & Chunk Management | ✅ DONE | Epic 2 | ChunkManager, MemoryMonitor, ChunkPreGenerator with TPS throttling |
| 8 | Commands | ✅ DONE | Epic 2, 3, 7 | Portal is the primary interface; player commands enabled, but return and tp into disabled by default but changeable by config |
| 9 | Event Handling & Player Lifecycle | ✅ DONE | Epic 2, 3, 5, 6 | Join/leave/death/respawn handlers |
| 10 | Networking & Client Sync | ✅ DONE | Epic 2 | Packet payloads, client handler, sync on join |
| 11 | Threading Research Spike | ✅ DONE | Epic 2 | Research doc complete — recommends Option A (async pre-gen) for v1 |
| 12 | Portal Dimension Cycling | ✅ DONE | Epic 4, 2 | Cycle portal target with configurable item & particle colors |
| 13 | Modded World Gen Compatibility | ✅ DONE | Epic 2 | Clone source ChunkGenerator + DimensionType instead of vanilla presets |
| 14 | Third-Party Mod Data Cleanup | 🔨 IN PROGRESS | Epic 2, 10 | Cleanup cached data from Xaero's Map/Minimap, JourneyMap, Distant Horizons on dimension regeneration |
| 15 | Async Chunk Generation | ✅ DONE | Epic 7, 2, 11 | Worker threads for noise computation; async pre-gen + player movement hints |
| 16 | Admin Dimension Recovery Command | 🔲 NOT STARTED | Epic 2, 3, 8 | Load inactive on-disk dims in recovery mode; admin-only access; auto-unload when empty |

---

## File Manifest

Files created/modified per epic. Agents should check this before starting work.

### Epic 1 — Project Skeleton & Config ✅
- [x] `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — mod entry point
- [x] `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — config holder (+ portalCycleItem, dimensionParticleColors)
- [x] `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — NeoForge config spec
- Note: `enabledDimensions` now uses `mod:dimension` ResourceLocation format (e.g. `minecraft:overworld`). Added `portalCycleItem` and `dimensionParticleColors` configs.
- [x] `src/main/templates/META-INF/neoforge.mods.toml` — cleaned up, no Create/TFMG deps
- [x] `build.gradle` — mixin plugin added, access transformer path
- [x] `src/main/resources/parallelworlds.mixins.json` — mixin config (empty refmap)
- [x] `src/main/resources/META-INF/accesstransformer.cfg` — placeholder

### Epic 2 — Dynamic Dimension Creation ✅
- [x] `src/main/java/com/agent772/parallelworlds/accessor/IServerDimensionAccessor.java`
- [x] `src/main/java/com/agent772/parallelworlds/accessor/IRegistryAccessor.java`
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinMinecraftServer.java`
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinMappedRegistry.java`
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinChunkMap.java`
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinServerLevel.java`
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinRandomState.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/SeedManager.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/ExplorationSeedManager.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionCounter.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionRegistrar.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionManager.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionCleanup.java`
- [x] `src/main/java/com/agent772/parallelworlds/dimension/DimensionUtils.java`
- [x] `src/main/resources/parallelworlds.mixins.json` — updated with mixin classes
- [x] `src/main/resources/META-INF/accesstransformer.cfg` — updated with needed ATs

### Epic 3 — Teleportation & Safe Spawning ✅
- [x] `src/main/java/com/agent772/parallelworlds/teleport/TeleportHandler.java`
- [x] `src/main/java/com/agent772/parallelworlds/data/ReturnPosition.java`
- [x] `src/main/java/com/agent772/parallelworlds/util/DimensionEnvironment.java`

### Epic 4 — Custom Portal System ✅
- [x] `src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java`
- [x] `src/main/java/com/agent772/parallelworlds/portal/PortalShape.java`
- [x] `src/main/java/com/agent772/parallelworlds/portal/PortalActivation.java`
- [x] `src/main/java/com/agent772/parallelworlds/registry/PWBlocks.java`
- [x] Assets: blue animated portal texture, models, blockstates, lang files
- Note: No custom frame block — uses configurable vanilla block (default: glass). PWPortalFrameBlock and PWItems removed.

### Epic 5 — Item & Ender Chest Restrictions ✅
- [x] `src/main/java/com/agent772/parallelworlds/restriction/RestrictionHandler.java`
- [x] `src/main/java/com/agent772/parallelworlds/restriction/RestrictionMode.java`
- Note: Tag JSONs removed — restrictions configured entirely via NeoForge config lists (supports *, #tag, prefix wildcards)

### Epic 6 — Data Persistence & Lifecycle ✅
- [x] `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java`
- [x] `src/main/java/com/agent772/parallelworlds/data/DimensionMetadata.java`
- [x] `src/main/java/com/agent772/parallelworlds/data/PlayerExplorationStats.java`
- Note: Seed rotation is now schedule-based (DAILY/WEEKLY/MONTHLY + day + time). Replaced old SeedStrategy enum with ResetSchedule. Config fields: seedRotationEnabled, resetSchedule, resetDayOfWeek, resetDayOfMonth, resetHour, resetMinute. Rotation only takes effect on server restart. TeleportHandler now persists return positions via PWSavedData.

### Epic 7 — Performance & Chunk Management ✅
- [x] `src/main/java/com/agent772/parallelworlds/performance/ChunkManager.java`
- [x] `src/main/java/com/agent772/parallelworlds/performance/MemoryMonitor.java`
- [x] `src/main/java/com/agent772/parallelworlds/generation/ChunkPreGenerator.java`
- [x] `src/main/java/com/agent772/parallelworlds/generation/GenerationTask.java`
- [x] `src/main/java/com/agent772/parallelworlds/generation/SpiralIterator.java`
- [x] `src/main/java/com/agent772/parallelworlds/generation/GenerationProgress.java`
- Modified: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — tick handler, pregen/chunk manager lifecycle
- Modified: `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` — pregen task persistence

### Epic 8 — Commands ✅
- [x] `src/main/java/com/agent772/parallelworlds/command/PWCommands.java`
- [x] `src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java`
- Modified: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — command registration via RegisterCommandsEvent
- Modified: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — added commandTpEnabled, commandReturnEnabled
- Modified: `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — added command config getters
- Modified: `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` — added getManualUnlocks()

### Epic 9 — Event Handling & Player Lifecycle ✅
- [x] `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`
- [x] `src/main/java/com/agent772/parallelworlds/util/InventoryKeeper.java`
- Modified: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — PWEventHandlers registration, InventoryKeeper cleanup on shutdown
- Note: preventExplorationSpawnSetting not in config spec — skipped. Respawn handler restores keepInventory snapshots. DimensionManager tracking wired via supplier.

### Epic 10 — Networking & Client Sync ✅
- [x] `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java`
- [x] `src/main/java/com/agent772/parallelworlds/network/payload/DimensionSyncPayload.java`
- [x] `src/main/java/com/agent772/parallelworlds/network/payload/DimensionResetPayload.java`
- [x] `src/main/java/com/agent772/parallelworlds/network/payload/ResetWarningPayload.java`
- [x] `src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java`
- Modified: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — payload registration on mod bus, client handler cleanup
- Modified: `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — sync all dims to player on join
- Modified: `src/main/resources/assets/parallelworlds/lang/en_us.json` — reset/warning translation keys

### Epic 11 — Threading Research Spike ✅
- [x] `docs/threading-research.md` — full research document with findings, risk matrix, and recommendation

### Epic 12 — Portal Dimension Cycling ✅
- [x] `src/main/java/com/agent772/parallelworlds/portal/PortalTargetManager.java`
- [x] `src/main/java/com/agent772/parallelworlds/portal/DimensionColors.java`
- [x] Modify: `src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java` — cycling interaction, colored particles, target-based teleport
- [x] Modify: `src/main/java/com/agent772/parallelworlds/portal/PortalActivation.java` — register default target on activation
- [x] Modify: `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` — portal target persistence
- [x] Modify: `src/main/java/com/agent772/parallelworlds/dimension/DimensionRegistrar.java` — multi-target lookups
- [x] Modify: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — PortalTargetManager + DimensionColors init/cleanup
- [x] Modify: `src/main/resources/assets/parallelworlds/lang/en_us.json` — portal cycling translation keys

### Epic 13 — Modded World Gen Compatibility ✅
- [x] Modify: `src/main/java/com/agent772/parallelworlds/dimension/DimensionFactory.java` — clone generator from source, resolve DimensionType from source
- [x] Modify: `src/main/java/com/agent772/parallelworlds/accessor/IServerDimensionAccessor.java` — DimensionType → Holder<DimensionType>
- [x] Modify: `src/main/java/com/agent772/parallelworlds/mixin/MixinMinecraftServer.java` — accept Holder<DimensionType>, remove vanilla type resolver

### Epic 14 — Third-Party Mod Data Cleanup 🔨
- [x] `src/main/java/com/agent772/parallelworlds/client/ModDataCleanupHandler.java` — client-side mod data cleanup dispatcher
- [x] `src/main/java/com/agent772/parallelworlds/network/payload/DimensionCleanupPayload.java` — cleanup notification packet
- [x] Modify: `src/main/java/com/agent772/parallelworlds/dimension/DimensionCleanup.java` — record deleted dimensions, return list
- [x] Modify: `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` — pending cleanup tracking
- [x] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — add `modCompatCleanupEnabled`
- [x] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — add config getter
- [x] Modify: `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java` — register cleanup payload
- [x] Modify: `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — send cleanup packets on player join
- [x] Modify: `src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java` — no changes needed (handler registered directly in PWNetworking)
- Note: Soft-detects Xaero's World Map, Xaero's Minimap, JourneyMap, Distant Horizons. No hard dependencies. Server-side DH data already cleaned by DimensionCleanup. Client-side cleanup via network packet.

### Epic 15 — Async Chunk Generation ✅
- [x] `src/main/java/com/agent772/parallelworlds/generation/async/AsyncChunkWorkerPool.java` — thread pool for noise computation
- [x] `src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeRequest.java` — immutable request record
- [x] `src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeResult.java` — computed terrain data
- [x] `src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeWorker.java` — noise/heightmap computation (runs off-thread)
- [x] `src/main/java/com/agent772/parallelworlds/generation/async/AsyncChunkHint.java` — predict chunks ahead of player movement
- [x] `src/main/java/com/agent772/parallelworlds/mixin/MixinNoiseBasedChunkGenerator.java` — intercept fillFromNoise to apply cached results
- [x] `src/main/java/com/agent772/parallelworlds/mixin/IStructureManagerAccessor.java` — accessor for StructureManager.level
- [x] Modify: `src/main/java/com/agent772/parallelworlds/generation/GenerationTask.java` — add async submit/apply path alongside sync fallback
- [x] Modify: `src/main/java/com/agent772/parallelworlds/generation/SpiralIterator.java` — added peek() for look-ahead
- [x] Modify: `src/main/java/com/agent772/parallelworlds/generation/ChunkPreGenerator.java` — pass worker pool to tasks
- [x] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — async config keys (5 new keys)
- [x] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — async config getters
- [x] Modify: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — worker pool lifecycle, chunk hint registration
- [x] Modify: `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — player tick for chunk hints, logout cleanup
- [x] Modify: `src/main/resources/parallelworlds.mixins.json` — added MixinNoiseBasedChunkGenerator, IStructureManagerAccessor
- Note: Uses mixin on NoiseBasedChunkGenerator.fillFromNoise to inject pre-computed noise. Off-thread workers use BeardifierMarker (no structures) for safe parallel computation. ProtoChunk sections copied block-by-block on main thread.

### Epic 16 — Admin Dimension Recovery Command 🔲
- [ ] `src/main/java/com/agent772/parallelworlds/dimension/RecoveryScanner.java` — scan disk for inactive dim folders
- [ ] `src/main/java/com/agent772/parallelworlds/dimension/RecoveryDimensionManager.java` — load/unload/auto-unload recovery dims
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java` — add `recovery` sub-command tree
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/command/PWCommands.java` — add recovery exclusion guard to `/pw tp` and `/pw list`
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/portal/PortalTargetManager.java` — skip recovery dims in cycling
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — skip recovery dims in network sync; add tick hook for auto-unload
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java` — exclude recovery dims from sync payloads
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — add `recoveryAutoUnloadDelaySec`
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — add getter
- [ ] Modify: `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — init RecoveryDimensionManager, register tick handler
- [ ] Modify: `src/main/resources/assets/parallelworlds/lang/en_us.json` — translation keys for recovery commands
- Note: All recovery state is transient — no persistence across restarts. Recovery dims must be re-loaded manually. DimensionCleanup must skip folders loaded as recovery dims.

---

## Agent Instructions

### Before Starting Work
1. Read this tracker to see what's done and what's in progress
2. Read `docs/Plan.md` for the full plan if needed
3. Check epic dependencies — don't start an epic whose deps are not ✅ DONE
4. Update this tracker when you start/finish work

### Marking Progress
- Change epic status: `🔲 NOT STARTED` → `🔨 IN PROGRESS` → `✅ DONE`
- Check file boxes: `- [ ]` → `- [x]`
- Add notes in the Notes column if useful for the next agent

### Conventions
- Package: `com.agent772.parallelworlds`
- NeoForge event bus: register in mod constructor or via `@EventBusSubscriber`
- Config: server-side TOML via `ModConfigEvent`
- Logging: `private static final Logger LOGGER = LogUtils.getLogger();`
- Mixin naming: `MixinTargetClass` → targets `TargetClass`
- Dimension naming: `pw_{basetype}_{counter}` (e.g., `pw_overworld_0`)

### Build Verification
After completing an epic, run `./gradlew build` and confirm no errors.

---

## Remaining Manual Tests

These require a live server + client and cannot be covered by unit or GameTest automation. Check them off as you verify.

### Epic 14 — Third-Party Mod Data Cleanup
- [x] **Xaero's World Map** — regenerate a dimension, reconnect client with Xaero's World Map installed; confirm old dimension tiles are gone from `XaeroWorldMap/<server>/DIM%parallelworlds%<dimpath>/`
- [x] **Xaero's Minimap** — same as above for minimap data under `XaeroMinimap/`
- [x] **JourneyMap** — confirm `journeymap/data/mp/<server>/<dimpath>/` is cleared on reconnect after rotation
- [x] **Distant Horizons (client)** — confirm matching SQLite DB files in `config/DistantHorizons/data/<server>/` are deleted after dimension rotation
- [x] **No mods installed** — cleanup packet received but no thread spawned and no errors logged (early-exit added before mod checks)
- [x] **Config toggle** — set `modCompatCleanupEnabled = false`; confirm no cleanup occurs and no packet is sent

### Shader Dimension Detection
- [ ] **Iris/Oculus dimension detection** — confirm shader `isExplorationDimension` uniform (or equivalent) is set correctly when inside a `pw_*` dimension (shaders that check for special dimensions should treat it like overworld/nether/end as appropriate)
- [ ] **Sky rendering** — confirm nether-type exploration dimension does not show void sky in overworld shaders
- [ ] **Ambient light** — confirm end-type exploration dimension ambient brightness matches expectations under shaders

### General Regression
- [x] Seed rotation triggers correctly on scheduled day/time after restart
- [x] Players in exploration dimension at shutdown are evacuated to their saved return position on next login
- [ ] Portal cycling color changes visible under shaders and without shaders

### Epic 16 — Admin Dimension Recovery Command
- [ ] `/pwadmin recovery list old` shows old dims
- [ ] `/pwadmin recovery list active` shows active dims
- [ ] `/pwadmin recovery load pw_overworld_0` loads an old dimension
- [ ] `/pwadmin recovery tp pw_overworld_0` teleports admin into it
- [ ] `/pw tp` and `/pw list` do NOT show the recovery dimension
- [ ] Portal cycling skips the recovery dimension
- [ ] Leaving the recovery dimension triggers auto-unload after delay
- [ ] Re-entering during delay cancels the unload
- [ ] `/pwadmin recovery unload` force-evacuates and unloads
- [ ] Server restart clears all recovery state (no auto-load)
- [ ] `DimensionCleanup` skips folders currently loaded as recovery dims
- [ ] integrate short instructions into Readme.
