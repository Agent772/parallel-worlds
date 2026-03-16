# Sub-Agent Instructions: Epic 3 — Teleportation & Safe Spawning

## Objective
Implement safe teleportation to/from mining dimensions with multi-phase position finding and player safety features.

## Context
- **Depends on**: Epic 2 (dimensions exist and are tracked)
- **Key classes to interact with**: `DimensionManager`, `DimensionRegistrar`, `ExplorationSeedManager`

## Tasks

### 1. TeleportHandler
`src/main/java/com/agent772/parallelworlds/teleport/TeleportHandler.java`:

**Entry points:**
- `teleportToExploration(ServerPlayer player, ServerLevel targetLevel)`:
  1. Check cooldown (ConcurrentHashMap<UUID, Long>), ops bypass
  2. Dismount from vehicles/entities
  3. Save return position to PWSavedData (only if not already in exploration dim)
  4. Find safe position via multi-phase search
  5. Teleport via `player.changeDimension(new DimensionTransition(...))`
  6. Apply post-teleport safety effects
  7. Record cooldown timestamp

- `returnFromExploration(ServerPlayer player)`:
  1. Load return position from PWSavedData
  2. If expired (7 days) or missing, use forceReturnToSpawn
  3. Optional inventory clear (PWConfig.isClearInventoryOnReturn())
  4. Teleport back to saved position
  5. Clear return position from saved data

- `forceReturnToSpawn(ServerPlayer player)`:
  1. Get overworld spawn point
  2. Teleport with safety effects

**Safe position finding (findSafePosition):**
- Phase 1: spiral search 8-block radius
- Phase 2: spiral search 16-block radius
- Phase 3: spiral search 32-block radius (nether/extended only)
- Phase 4: create emergency 3×3 obsidian platform
- Time budget: 5ms max (System.nanoTime() check per iteration)
- Column search: ±32 blocks vertical from center
- Surface preference: find highest solid block, exclude leaves/dangerous blocks
- Nether-specific: search for enclosed spaces, avoid lava lake Y-levels
- End-specific: find solid ground near 0,0 or create platform

**Safety features:**
- 100-tick invulnerability window post-teleport (via scheduled task on server)
- Apply DAMAGE_RESISTANCE II (100 ticks) + SLOW_FALLING (100 ticks)
- Clear dangerous effects: LEVITATION, BLINDNESS
- Dismount before teleport (remove from vehicles, boats, minecarts)

**Cooldown:**
- `ConcurrentHashMap<UUID, Long> lastTeleportTime`
- Check: `System.currentTimeMillis() - lastTime < PWConfig.getTeleportCooldown() * 1000`
- Ops (permission level 2+) bypass cooldown
- Background cleanup: every 5 min, remove entries older than 30 min

### 2. ReturnPosition
`src/main/java/com/agent772/parallelworlds/data/ReturnPosition.java`:
- Record: `(BlockPos pos, ResourceLocation dimension, float yRot, float xRot, long timestamp)`
- `isExpired()` → timestamp older than 7 days
- `toNbt(CompoundTag)` / `fromNbt(CompoundTag)` serialization

### 3. DimensionEnvironment Utility
`src/main/java/com/agent772/parallelworlds/util/DimensionEnvironment.java`:
- Enum: `OVERWORLD_LIKE`, `NETHER_LIKE`, `END_LIKE`
- `classify(DimensionType type)`: ultraWarm→NETHER_LIKE, fixedTime+!hasCeiling→END_LIKE, else OVERWORLD_LIKE
- Used by TeleportHandler to pick spawn strategy

## Verification
- Teleport places player on solid ground (all 3 dimension types)
- Return teleports to exact saved position + rotation
- Cooldown blocks rapid teleporting (test with non-op)
- No fall damage after arriving (damage resistance active)
- Emergency obsidian platform created when no safe spot found
- Dismount from boat → teleport → still alive
- Expired return positions (simulate 7 days) → fallback to world spawn
- GameTest: `TeleportSafetyTest`

## Do NOT
- Create portal blocks/mechanics (Epic 4)
- Implement commands (Epic 8)
- Handle death/inventory (Epic 9)
