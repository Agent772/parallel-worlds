# Parallel Worlds — Verification Report

**Date**: 2025-03-15  
**Scope**: All 13 epics verified against `docs/plan.md` and epic specs  
**Build**: ✅ `./gradlew build` passes (one deprecation warning, non-blocking)

---

## How to Use This Report

Each issue below is self-contained with the file(s) to change, what's wrong, and what the fix should be. Issues are grouped by priority. An agent can pick up one or more issues and fix them independently — there are no cross-dependencies between fixes unless noted.

**Conventions reminder:**
- Package: `com.agent772.parallelworlds`
- Config: `PWConfig.java` (holder) + `PWConfigSpec.java` (spec definitions)
- After fixing, run `./gradlew build` to verify no compilation errors

---

## HIGH Priority

### H1 — Portal Model JSON Format Error
**Files:**
- `src/main/resources/assets/parallelworlds/models/block/pw_portal_ns.json`
- `src/main/resources/assets/parallelworlds/models/block/pw_portal_ew.json`

**Problem:** Texture declarations use a JSON array (`"textures": ["parallelworlds:block/pw_portal"]`) instead of the standard Minecraft model format which requires an object map. The faces reference `#0` which expects a named texture key in an object.

**Fix:** Change `"textures"` from an array to an object in both files:
```json
// WRONG:
"textures": ["parallelworlds:block/pw_portal"]

// CORRECT:
"textures": {
  "0": "parallelworlds:block/pw_portal",
  "particle": "parallelworlds:block/pw_portal"
}
```
The `"particle"` key is needed for break particles. Both model files need the same fix.

**Impact:** Without this fix, the portal block will render with missing/pink-black textures in-game.

---

## MEDIUM Priority

### M1 — Missing `dimensionLocks` Config Field
**Files:**
- `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java`
- `src/main/java/com/agent772/parallelworlds/config/PWConfig.java`

**Problem:** The `dimensionLocksEnabled` boolean toggle exists, but there's no actual `dimensionLocks` list/map config where server operators define which advancement gates which dimension. The lock system is therefore unconfigurable.

**Fix:**
1. In `PWConfigSpec.java`, add a config list in the Locks section:
   ```java
   // Format: "mod:dimension=mod:advancement_id"
   // Example: "minecraft:the_nether=minecraft:story/enter_the_nether"
   public final ForgeConfigSpec.ConfigValue<List<? extends String>> dimensionLocks;
   ```
   Default should be an empty list.
2. In `PWConfig.java`, add a getter that parses the list into a `Map<ResourceLocation, ResourceLocation>` (dimension → advancement).
3. In `PWCommands.java` `/pw tp`, use the parsed map to check if the player has the required advancement before allowing teleport (see also M4).

---

### M2 — No Default Particle Colors for Vanilla Dimensions
**File:** `src/main/java/com/agent772/parallelworlds/portal/DimensionColors.java`

**Problem:** The `colorCache` starts empty and is only populated from config. If a user doesn't configure `dimensionParticleColors`, all portals show the same cyan fallback color. The plan specifies hardcoded defaults: overworld = green `#33CC4D`, nether = orange `#E64D19`, end = purple `#B233E6`.

**Fix:** In the `refresh()` method (or in a static initializer), pre-populate the `colorCache` with defaults for the three vanilla dimension base types before applying config overrides:
```java
// Pre-populate defaults (config entries override these)
colorCache.put(ResourceLocation.parse("minecraft:overworld"), new Vector3f(0.2f, 0.8f, 0.3f));   // green
colorCache.put(ResourceLocation.parse("minecraft:the_nether"), new Vector3f(0.9f, 0.3f, 0.1f));  // orange  
colorCache.put(ResourceLocation.parse("minecraft:the_end"), new Vector3f(0.7f, 0.2f, 0.9f));     // purple
// Then apply config overrides on top...
```

---

### M3 — No Inventory Clear on Return from Exploration
**File:** `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`

**Problem:** When a player leaves an exploration dimension (detected in `onDimensionChange`), the handler ends the stats timer and shows a message, but does NOT implement the optional inventory clearing feature. The plan specifies: "Leaving mining dim → optional: clear inventory" gated by the `clearInventoryOnReturn` config.

**Fix:** In the `onDimensionChange` handler, after detecting the player left an exploration dimension, add:
```java
if (PWConfig.isClearInventoryOnReturn()) {
    player.getInventory().clearContent();
    // Optionally send a message: "Your exploration inventory has been cleared."
}
```
Check that `PWConfig.isClearInventoryOnReturn()` (or equivalent getter) exists — it should, since `clearInventoryOnReturn` is in the config spec.

---

### M4 — `/pw tp` Doesn't Check Dimension Locks
**File:** `src/main/java/com/agent772/parallelworlds/command/PWCommands.java`

**Problem:** The `/pw tp <dimension>` command checks `commandTpEnabled` config and validates the dimension exists, but does NOT check dimension locks (advancement requirements) or manual unlocks from `PWSavedData`.

**Fix:** Before teleporting in the tp command handler, add a lock check:
1. If `PWConfig.isDimensionLocksEnabled()`:
   - Look up the dimension in the `dimensionLocks` map (from M1 fix)
   - If an advancement is required, check `player.getAdvancements().getOrStartProgress(advancement).isDone()`
   - Also check `PWSavedData.get(server).getManualUnlocks()` for the player's UUID
   - If locked and not manually unlocked, deny with a message
2. This depends on M1 being implemented first for the config map.

---

### M5 — `ChunkManager.configureForExploration()` Is a No-Op  
**File:** `src/main/java/com/agent772/parallelworlds/performance/ChunkManager.java`

**Problem:** The `configureForExploration(ServerLevel)` method only logs a debug message. It doesn't configure chunk unload delays or max loaded chunks. The plan specifies it should "set custom chunk unload delay from config" and "configure max loaded chunks per player."

**Fix:** This may be a platform limitation (NeoForge doesn't expose direct unload delay APIs). Options:
1. If achievable via chunk ticket manipulation or reflection, implement it
2. If not feasible on NeoForge 1.21.1, add a code comment explaining the limitation and mark this as a known limitation in the plan
3. At minimum, consider setting a custom `TicketType` with a shorter timeout for exploration dimensions

---

### M6 — `ChunkManager.performCleanup()` Doesn't Unload Chunks in Empty Dims
**File:** `src/main/java/com/agent772/parallelworlds/performance/ChunkManager.java`

**Problem:** When `aggressiveChunkUnloading` is true and a dimension has no players, the method only logs a debug message. It does NOT force-unload any chunks. The plan specifies: "Unload chunks in empty exploration dims" and "If aggressiveChunkUnloading: unload ALL non-entity-loaded chunks."

**Fix:** When no players are in an exploration dimension and aggressive unloading is on:
1. Get the `ServerChunkCache` for the level
2. Iterate loaded chunks and remove tickets / force unload where possible
3. Call `chunkSource.tick()` or trigger chunk unloading via vanilla APIs
4. Be careful not to remove entity-associated tickets

---

## LOW Priority

### L1 — `ReturnPosition` Is a Class, Not a Record
**File:** `src/main/java/com/agent772/parallelworlds/data/ReturnPosition.java`

**Problem:** The plan specifies a record, but it's implemented as a plain `final class` with manual accessors. Missing auto-generated `equals()`, `hashCode()`, `toString()`.

**Fix:** Convert to a Java record:
```java
public record ReturnPosition(Vec3 pos, ResourceKey<Level> dimension, float yRot, float xRot, long timestamp) {
    // Keep NBT serialization methods as static
    // Add isExpired() method
}
```
Update any code that constructs `ReturnPosition` via `new` if the constructor order changes.

---

### L2 — `PWPortalBlock.resetPortalCounter()` Never Called
**File:** `src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java`

**Problem:** The method exists to reset a player's portal contact timer when they leave the portal area, but nothing calls it. Players who step in, step out, and step back will have their counter keep accumulating.

**Fix:** Either:
1. Hook into a player movement event to detect when the player is no longer standing in a portal block
2. Or in the `entityInside` method, reset the counter if the player's position has changed significantly since last tick
3. Or just clear the counter on successful teleport (simplest)

---

### L3 — `PlayerExplorationStats` Missing `chunksLoaded` Field
**File:** `src/main/java/com/agent772/parallelworlds/data/PlayerExplorationStats.java`

**Problem:** Plan specifies "visits per dimension, total time, chunks loaded." Only `dimensionVisits` and `totalExplorationTimeMs` exist.

**Fix:** Add a `chunksLoaded` counter field with NBT serialization, or document as descoped if not needed.

---

### L4 — `pregenTasks` Stored as Raw `ListTag`
**File:** `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java`

**Problem:** Plan specifies `Map<ResourceLocation, GenerationProgress>`, but implementation stores it as a raw `ListTag`. The `ChunkPreGenerator` handles its own serialization.

**Fix:** Either extract to typed map for cleaner API, or document the delegation pattern. Functionally works as-is.

---

### L5 — Standalone `RestrictionMode.java` Is Dead Code
**File:** `src/main/java/com/agent772/parallelworlds/restriction/RestrictionMode.java`

**Problem:** The enum exists but `RestrictionHandler` uses `PWConfigSpec.RestrictionMode` (a duplicate enum inside the config spec class) instead.

**Fix:** Either:
1. Make `RestrictionHandler` use the standalone `RestrictionMode` enum and remove the duplicate from `PWConfigSpec`
2. Or delete the standalone file if the config-embedded enum is preferred

---

### L6 — Command/Event Messages Not Translatable
**Files:**
- `src/main/java/com/agent772/parallelworlds/command/PWCommands.java`
- `src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java`
- `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`

**Problem:** All user-facing messages use `Component.literal("...")` instead of `Component.translatable("key", args)`. This prevents localization.

**Fix:**
1. Define translation keys in `src/main/resources/assets/parallelworlds/lang/en_us.json` for each message
2. Replace `Component.literal("text")` with `Component.translatable("parallelworlds.command.xyz")` throughout
3. This is a large but mechanical change across many files

---

### L7 — `/pw list` and `/pw tp` Don't Show Rotation Time
**File:** `src/main/java/com/agent772/parallelworlds/command/PWCommands.java`

**Problem:** Plan specifies showing "time remaining until rotation" in list and post-teleport output. Currently only shows player count and seed.

**Fix:** After listing a dimension or after teleporting, compute time until next reset using `SeedManager` and display it. Format as relative time (e.g., "Resets in 2d 5h").

---

### L8 — `TeleportHandler.CLEANUP_EXECUTOR` Never Shut Down
**File:** `src/main/java/com/agent772/parallelworlds/teleport/TeleportHandler.java`

**Problem:** A `ScheduledExecutorService` runs a daemon thread every 5 minutes to purge stale cooldown entries, but `clearAll()` only clears maps without calling `shutdown()` on the executor.

**Fix:** In `clearAll()`, add `CLEANUP_EXECUTOR.shutdown()`. Or make the executor lazily created and shut down with the server lifecycle.

---

### L9 — Unused Translation Key
**File:** `src/main/resources/assets/parallelworlds/lang/en_us.json`

**Problem:** `"parallelworlds.portal.cycling_disabled"` is defined but never referenced in code.

**Fix:** Either use it in the portal cycling code path (when cycling is attempted but disabled), or remove it.

---

## Summary Table

| ID | Severity | Epic | Description |
|----|----------|------|-------------|
| H1 | HIGH | 4 | Portal model JSON uses array instead of object for textures |
| M1 | MEDIUM | 1 | Missing `dimensionLocks` config list |
| M2 | MEDIUM | 12 | No default particle colors for vanilla dimensions |
| M3 | MEDIUM | 9 | No inventory clear on return from exploration |
| M4 | MEDIUM | 8 | `/pw tp` doesn't check dimension locks |
| M5 | MEDIUM | 7 | `ChunkManager.configureForExploration()` is a no-op |
| M6 | MEDIUM | 7 | `ChunkManager.performCleanup()` doesn't unload chunks |
| M1+M4 | MEDIUM | 1,8 | M4 depends on M1 — do M1 first |
| L1 | LOW | 3 | `ReturnPosition` is a class instead of a record |
| L2 | LOW | 4 | `resetPortalCounter()` never called |
| L3 | LOW | 6 | `PlayerExplorationStats` missing `chunksLoaded` |
| L4 | LOW | 6 | `pregenTasks` stored as raw ListTag |
| L5 | LOW | 5 | Standalone `RestrictionMode.java` is dead code |
| L6 | LOW | 8,9 | Messages use `Component.literal` instead of translatable |
| L7 | LOW | 8 | Commands don't show rotation time remaining |
| L8 | LOW | 3 | `CLEANUP_EXECUTOR` never shut down |
| L9 | LOW | — | Unused translation key in lang file |
