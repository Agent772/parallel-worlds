# Epic 16 — Admin Dimension Recovery Command

## Goal

Allow server admins to temporarily spin up **inactive exploration dimensions** (still saved on disk but not currently registered/active) in a read-only recovery mode, teleport into them, and have them automatically shut down once empty.

---

## Background

When seed rotation or regeneration occurs, old exploration dimensions are unregistered but their world folders may still exist on disk (depending on retention config). Currently there is no way for an admin to access these old dimensions to recover builds, investigate issues, or retrieve items.

This epic adds a **recovery sub-command** to `/pwadmin` that:
1. Scans the `dimensions/parallelworlds/` folder for dimension folders **not** currently active.
2. Lets admins pick one and temporarily load it.
3. The loaded dimension is marked as **recovery-only** — completely invisible to player commands (`/pw tp`), portal cycling, and portal teleportation.
4. Once no players remain in the recovery dimension (after a configurable short delay, default 30 seconds), it is automatically unloaded (but **not** deleted from disk).

---

## Dependencies

| Depends On | Reason |
|------------|--------|
| Epic 2 — Dynamic Dimension Creation | Uses DimensionFactory + DimensionRegistrar for hot-loading dimensions |
| Epic 8 — Commands | Extends PWAdminCommands |
| Epic 3 — Teleportation & Safe Spawning | Uses TeleportHandler for admin TP |

---

## Acceptance Criteria

- [ ] `/pwadmin recovery list` — shows dimension folders on disk that are **not** currently active, with folder name and last-modified date.
- [ ] `/pwadmin recovery load <folder>` — loads the dimension from disk into the server in recovery mode. Shows success/failure message.
- [ ] `/pwadmin recovery tp <folder>` — teleports the executing admin into the recovery dimension (auto-loads if not yet loaded). Requires the admin to be a player (not console).
- [ ] `/pwadmin recovery unload <folder>` — force-unloads a recovery dimension (evacuates any players in it first).
- [ ] `/pwadmin recovery status` — shows currently loaded recovery dimensions and player counts.
- [ ] Recovery dimensions are **excluded** from:
  - `/pw tp` suggestions and execution
  - `/pw list` output
  - Portal destination cycling (`PortalTargetManager`)
  - `DimensionRegistrar.getExplorationDimensionIds()` (used for suggestions everywhere)
  - `DimensionManager` active tracking
  - Network sync packets (`DimensionSyncPayload`)
- [ ] Recovery dimensions auto-unload when empty after a configurable delay (default: 30 seconds).
  - A tick-based check monitors player count.
  - If the dimension becomes non-empty again during the delay, the unload is cancelled.
- [ ] Unloading a recovery dimension does **not** delete its world folder.
- [ ] Admins inside a recovery dimension can use `/pwadmin recovery unload` or `/pw return` to leave.
- [ ] Config option `recoveryAutoUnloadDelaySec` (int, default 30) controls the empty-dimension grace period.
- [ ] All recovery state is transient — no persistence across server restarts. Recovery dimensions must be re-loaded manually after restart.

---

## Technical Design

### 1. Recovery Dimension Scanner

**New class**: `com.agent772.parallelworlds.dimension.RecoveryScanner`

- Scans `dimensions/parallelworlds/` for folders matching `pw_(type)_(id)` pattern (reuse `DimensionCleanup.PW_FOLDER_PATTERN`).
- Filters out folders whose corresponding `ResourceKey<Level>` is present in `DimensionRegistrar.getRuntimeDimensions()`.
- Returns a list of `RecoveryCandidate` records:
  ```java
  public record RecoveryCandidate(String folderName, String dimType, int dimId, 
                                   ResourceLocation dimensionId, long lastModifiedMs) {}
  ```

### 2. Recovery Dimension Manager

**New class**: `com.agent772.parallelworlds.dimension.RecoveryDimensionManager`

Responsibilities:
- **Load**: Use `DimensionFactory` to create a `ServerLevel` from an existing on-disk folder. The dimension key must match the folder name so Minecraft loads existing region files.
  - Register via `MixinMinecraftServer` accessor (same as `DimensionRegistrar` does for normal dims).
  - Mark the key in a `Set<ResourceKey<Level>> recoveryDimensions` to distinguish from normal exploration dims.
- **Unload**: Evacuate players (teleport to return position or spawn), then remove the `ServerLevel` from the server's level map.
  - Flush/save chunks before removal.
  - Remove from `recoveryDimensions` set.
- **Auto-unload tick**: On server tick, check each recovery dimension's player count.
  - If empty, start a countdown (`emptyTickCounters` map).
  - If countdown reaches threshold (configurable seconds × 20 ticks), unload.
  - If players re-enter, reset counter.
- **Query**: `isRecoveryDimension(ResourceKey<Level>)` — used by other systems to exclude recovery dims.

### 3. Command Extensions

**Modify**: `PWAdminCommands.java`

Add `recovery` literal with sub-commands:

```
/pwadmin recovery list
/pwadmin recovery load <folder>
/pwadmin recovery tp <folder>
/pwadmin recovery unload <folder>
/pwadmin recovery status
```

- `<folder>` argument uses custom suggestion provider that lists `RecoveryScanner` results (for `load`/`tp`) or currently loaded recovery dims (for `unload`).
- `tp` auto-loads if not already loaded, then teleports the admin. Uses `TeleportHandler.teleportToExploration()` with recovery flag.
- `unload` calls `RecoveryDimensionManager.unloadRecovery()` which evacuates + removes.

### 4. Exclusion Guards

Add `RecoveryDimensionManager.isRecoveryDimension(key)` checks to:

| Location | Guard Purpose |
|----------|---------------|
| `PWCommands.teleport()` | Block `/pw tp` to recovery dims |
| `PWCommands.listDimensions()` | Hide from `/pw list` |
| `DimensionRegistrar.getExplorationDimensionIds()` | Exclude from all suggestion providers |
| `PortalTargetManager.cycleTarget()` | Skip recovery dims in portal cycling |
| `PWEventHandlers.onPlayerJoin()` | Don't sync recovery dims to non-admin clients |
| `PWNetworking` / sync payloads | Exclude recovery dims from dimension sync |

### 5. Config

**Modify**: `PWConfigSpec.java` / `PWConfig.java`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `recoveryAutoUnloadDelaySec` | int | 30 | Seconds to wait after recovery dim becomes empty before auto-unloading |

### 6. Lifecycle

- **Server start**: `RecoveryDimensionManager` initializes with empty state. No recovery dims are auto-loaded.
- **Server tick**: Auto-unload check runs every 20 ticks (1 second) for efficiency.
- **Server stop**: Any still-loaded recovery dims are saved and removed cleanly. No persistence of recovery state.

---

## File Plan

### New Files
- [ ] `src/main/java/com/agent772/parallelworlds/dimension/RecoveryScanner.java` — scan disk for inactive dim folders
- [ ] `src/main/java/com/agent772/parallelworlds/dimension/RecoveryDimensionManager.java` — load/unload/auto-unload recovery dims

### Modified Files
- [ ] `src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java` — add `recovery` sub-command tree
- [ ] `src/main/java/com/agent772/parallelworlds/command/PWCommands.java` — add recovery exclusion guard to `/pw tp` and `/pw list`
- [ ] `src/main/java/com/agent772/parallelworlds/portal/PortalTargetManager.java` — skip recovery dims in cycling
- [ ] `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — skip recovery dims in network sync; add tick hook for auto-unload
- [ ] `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java` — exclude recovery dims from sync payloads
- [ ] `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — add `recoveryAutoUnloadDelaySec`
- [ ] `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — add getter
- [ ] `src/main/java/com/agent772/parallelworlds/ParallelWorlds.java` — init RecoveryDimensionManager, register tick handler
- [ ] `src/main/resources/assets/parallelworlds/lang/en_us.json` — translation keys for recovery commands

---

## Translation Keys

```
parallelworlds.admin.recovery.list.header         = "Inactive dimensions on disk:"
parallelworlds.admin.recovery.list.entry           = "  %s — last modified %s"
parallelworlds.admin.recovery.list.empty           = "No inactive dimensions found on disk."
parallelworlds.admin.recovery.load.success         = "Recovery dimension %s loaded successfully."
parallelworlds.admin.recovery.load.already_loaded  = "Recovery dimension %s is already loaded."
parallelworlds.admin.recovery.load.already_active  = "Dimension %s is currently active (not a recovery candidate)."
parallelworlds.admin.recovery.load.failed          = "Failed to load recovery dimension %s."
parallelworlds.admin.recovery.load.not_found       = "No dimension folder found for %s."
parallelworlds.admin.recovery.tp.success           = "Teleported to recovery dimension %s."
parallelworlds.admin.recovery.tp.not_player        = "This command must be run by a player."
parallelworlds.admin.recovery.unload.success       = "Recovery dimension %s unloaded."
parallelworlds.admin.recovery.unload.not_loaded    = "Recovery dimension %s is not currently loaded."
parallelworlds.admin.recovery.unload.evacuated     = "Evacuated %d player(s) from recovery dimension %s."
parallelworlds.admin.recovery.status.header        = "Loaded recovery dimensions:"
parallelworlds.admin.recovery.status.entry         = "  %s — %d player(s)"
parallelworlds.admin.recovery.status.none          = "No recovery dimensions are currently loaded."
parallelworlds.admin.recovery.auto_unload          = "Recovery dimension %s auto-unloaded (empty for %d seconds)."
```

---

## Edge Cases

1. **Folder belongs to currently active dimension** — `load` should reject with `already_active` message.
2. **Admin runs `/pw return` from recovery dim** — should work normally via TeleportHandler (return position was saved on entry).
3. **Server stops with recovery dim loaded** — save chunks, unload cleanly, no crash.
4. **Recovery dim loaded but seed rotation deletes its folder** — DimensionCleanup should skip folders that are currently loaded as recovery dims.
5. **Multiple admins in same recovery dim** — auto-unload waits for ALL players to leave.
6. **Admin disconnects while in recovery dim** — on rejoin, player spawns at world spawn (recovery dim is gone). Emergency fallback via `PWEventHandlers`.

---

## Manual Test Checklist

- [ ] `/pwadmin recovery list` shows old dims not currently active
- [ ] `/pwadmin recovery load pw_overworld_0` loads an old dimension
- [ ] `/pwadmin recovery tp pw_overworld_0` teleports admin into it
- [ ] `/pw tp` and `/pw list` do NOT show the recovery dimension
- [ ] Portal cycling skips the recovery dimension
- [ ] Leaving the recovery dimension triggers auto-unload after delay
- [ ] Re-entering during delay cancels the unload
- [ ] `/pwadmin recovery unload` force-evacuates and unloads
- [ ] Server restart clears all recovery state (no auto-load)
- [ ] `DimensionCleanup` skips folders currently loaded as recovery dims
