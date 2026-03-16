# Sub-Agent Instructions: Epic 6 — Data Persistence & Lifecycle

## Objective
Persist player state (return positions, stats), dimension metadata, and pre-gen progress across server restarts using Minecraft's SavedData system.

## Context
- **Depends on**: Epic 2 + 3 (dimensions and teleport produce data to persist)
- **Format**: SavedData (NBT stored in `data/parallelworlds.dat`)
- **Key requirement**: seed comparison for persistence mode — saved seed vs generated seed determines reuse/recreation

## Tasks

### 1. PWSavedData
`src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` (extends SavedData):

**Data Maps:**
- `Map<UUID, ReturnPosition> playerReturnPositions` — where players should return to
- `Map<ResourceLocation, DimensionMetadata> dimensionMetadata` — per-dim stats
- `Map<UUID, PlayerExplorationStats> playerStats` — per-player stats
- `Set<ResourceLocation> activeDimensions` — currently active exploration dims
- `Map<ResourceLocation, Long> dimensionSeeds` — persisted seeds for rotation comparison
- `Map<ResourceLocation, GenerationProgress> pregenTasks` — saved pre-gen state
- `Map<UUID, Set<ResourceLocation>> manualUnlocks` — admin-granted dim access
- `long nextResetTime` — when current seeds expire

**Methods:**
- `save(CompoundTag tag, HolderLookup.Provider provider) → CompoundTag`
- `static load(CompoundTag tag) → PWSavedData`
- `static Factory<PWSavedData> factory()` — register with DataStorage
- `static PWSavedData get(MinecraftServer server)` — accessor via overworld DataStorage
- `saveReturnPosition(UUID, BlockPos, ResourceLocation, float yRot, float xRot)`
- `getReturnPosition(UUID) → Optional<ReturnPosition>`
- `clearReturnPosition(UUID)`
- `recordDimensionAccess(ResourceLocation, UUID)`
- `getSavedSeed(ResourceLocation) → Optional<Long>`
- `saveSeed(ResourceLocation, long seed)`
- `hasManualUnlock(UUID, ResourceLocation) → boolean`
- `grantManualUnlock(UUID, ResourceLocation)` / `revokeManualUnlock(...)`

**Serialization rules:**
- UUIDs stored as string (UUID.toString / UUID.fromString)
- ResourceLocations stored as string
- ReturnPosition uses sub-compound
- Handle missing keys gracefully (backwards compat)
- Log warnings on parse errors, don't crash

### 2. DimensionMetadata
`src/main/java/com/agent772/parallelworlds/data/DimensionMetadata.java`:
- Record or class: `dimension`, `createdTime`, `lastAccessTime`, `accessedBy` (Set<UUID>), `totalVisits` (int)
- `recordAccess(UUID playerId)` — update lastAccessTime, add to set, increment visits
- `reset()` — clear for dimension reset
- `toNbt() / fromNbt()` serialization

### 3. PlayerExplorationStats
`src/main/java/com/agent772/parallelworlds/data/PlayerExplorationStats.java`:
- `UUID playerId`
- `Map<ResourceLocation, Integer> dimensionVisits`
- `long totalExplorationTimeMs`
- `long currentVisitStart` (transient — not serialized)
- `recordVisit(ResourceLocation dim)`, `startVisit()`, `endVisit()`
- `toNbt() / fromNbt()`

### 4. Seed Persistence Logic
In `DimensionRegistrar.createDimensionsOnServerStart()`:
- Load `PWSavedData.get(server)`
- For each enabled dimension:
  - Generate new seed via SeedManager
  - Load saved seed via `savedData.getSavedSeed(baseDim)`
  - If `PERSIST_UNTIL_ROTATION` mode AND saved seed matches new seed → skip recreation, log "Reusing existing dimension"
  - If seeds differ OR `REGENERATE_EACH_RESTART` mode → cleanup old dim, create new, save new seed
- After creation: `savedData.saveSeed(baseDim, newSeed)` + `savedData.setDirty()`

### 5. Pre-gen Task Persistence
- `GenerationProgress.toNbt() / fromNbt()` (for Epic 7)
- Save active tasks on server stop
- Restore active tasks on server start

## Verification
- Return positions survive restart: enter dim → stop server → start server → run return → arrives at saved position
- Dimension metadata tracks visitors correctly
- Seed comparison: same day (date mode) → no recreation, next day → recreation
- Corrupted NBT doesn't crash — defaults restored
- Manual unlocks persist
- `PWSavedData.get()` returns same instance on repeated calls (singleton per world)
- GameTest: `PersistenceTest` — save/load, seed comparison

## Do NOT
- Implement pre-gen logic (Epic 7) — just provide the data structures
- Create commands for data inspection (Epic 8)
- Handle events (Epic 9) — just provide the data API
