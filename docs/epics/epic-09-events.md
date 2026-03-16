# Sub-Agent Instructions: Epic 9 — Event Handling & Player Lifecycle

## Objective
Handle all player lifecycle events (join, leave, death, respawn, dimension change) in mining dimensions.

## Context
- **Depends on**: Epic 2, 3, 5, 6
- **Registration**: via NeoForge event bus (`NeoForge.EVENT_BUS.register()`)

## Tasks

### 1. PWEventHandlers
`src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`:

**Server Tick:**
- `@SubscribeEvent ServerTickEvent.Post` → `ChunkPreGenerator.tick(server)`

**Player Join (`PlayerEvent.PlayerLoggedInEvent`):**
- Sync dimension info to client (if mining dims exist)
- If player was in a mining dimension that no longer exists → evacuate to spawn

**Player Leave (`PlayerEvent.PlayerLoggedOutEvent`):**
- Clear invulnerability flags (CRITICAL — prevents god-mode persistence across sessions)
- Record last known dimension in PWSavedData
- End exploration stats timer

**Dimension Change (`PlayerEvent.PlayerChangedDimensionEvent`):**
- Leaving mining dim → optional: clear inventory, show return message
- Entering mining dim → show seed info with time until rotation, start stats timer
- Update DimensionManager tracking

**Player Death (`LivingDeathEvent` for ServerPlayer):**
- Only in mining dimensions:
  - If `keepInventoryInExploration` enabled:
    - Save inventory via InventoryKeeper
    - Clear inventory (prevent double-drop)
    - Clear XP

**Player Respawn (`PlayerEvent.PlayerRespawnEvent`):**
- If has saved inventory → restore via InventoryKeeper
- If `preventExplorationSpawnSetting` → force respawn in overworld (don't set spawn in mining dim)

**Block Interact (`PlayerInteractEvent.RightClickBlock`):**
- Delegate to RestrictionHandler for ender chest / restricted block checks
- Cancel event if restriction mode is BLOCK

**Block Place (`BlockEvent.EntityPlaceEvent`):**
- Delegate to RestrictionHandler for restricted block placement checks

### 2. InventoryKeeper
`src/main/java/com/agent772/parallelworlds/util/InventoryKeeper.java`:
- `saveInventory(ServerPlayer) → saves full inventory + armor + offhand to transient map`
- `restoreInventory(ServerPlayer) → restores saved inventory, clears saved`
- `hasSavedInventory(UUID) → boolean`
- Storage: `ConcurrentHashMap<UUID, List<ItemStack>>` — transient, cleaned up after restore
- Serialize armor slots, main inventory, offhand separately

### 3. Event Registration
In `ParallelWorlds` constructor:
```java
NeoForge.EVENT_BUS.register(PWEventHandlers.class);
```
Or use `@EventBusSubscriber` annotation on PWEventHandlers.

## Verification
- Join after dimension deleted → player evacuated safely (not in void)
- Leave → invulnerability cleared (re-join without god mode)
- Death in mining dim with keepInventory → items preserved after respawn
- Death in mining dim without keepInventory → items dropped normally
- Inventory clear on return works when configured
- Block interactions delegated to RestrictionHandler correctly
- No event handler throws unhandled exception (wrap in try-catch, log errors)
- GameTest: `EventLifecycleTest` — simulate join/leave/death/respawn sequences
