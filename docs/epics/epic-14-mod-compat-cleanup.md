# Sub-Agent Instructions: Epic 14 — Third-Party Mod Data Cleanup

## Objective
Add a cleanup system that purges stale cached data from popular third-party mods (Xaero's World Map, Xaero's Minimap, JourneyMap, Distant Horizons) when exploration dimensions are regenerated or deleted. This prevents orphaned map/LOD data from accumulating on disk and ensures players don't see stale map data for regenerated dimensions.

## Context
- **Depends on**: Epic 2 (dimension lifecycle), Epic 10 (networking)
- **Package**: `com.agent772.parallelworlds`
- **Problem**: When exploration dimensions are regenerated (seed rotation) or cleaned up (retention limit), the vanilla dimension folder under `world/dimensions/parallelworlds/` is deleted. However, third-party mods cache data indexed by dimension ID in their own locations. This stale data:
  - Wastes disk space (Distant Horizons LODs can be very large)
  - Shows incorrect/outdated map data when a dimension with the same ID is recreated
  - Confuses waypoint systems with waypoints floating in regenerated terrain
- **Scope**: Server-side cleanup for Distant Horizons; client notification packets for client-side mods (Xaero's, JourneyMap). The mod does NOT need to hard-depend on any of these — all interactions are optional and use soft-detection.

## Background: How Each Mod Stores Data

### Xaero's World Map (`xaeroworldmap`)
- **Location**: Client-side — `<minecraft>/XaeroWorldMap/<server_id>/`
- **Structure**: `<server_id>/DIM%<dimension_namespace>%<dimension_path>/` (e.g., `DIM%parallelworlds%pw_overworld_0/`)
  - Inside: `mw/` folder with region `.zip` files
- **Cleanup trigger**: Client needs to delete the `DIM%parallelworlds%pw_<type>_<counter>/` folder
- **API**: Xaero's has no public cleanup API. The mod must either:
  - Notify the client via custom packet → client deletes the folder
  - Or: Xaero's will simply show "unexplored" for regenerated dimensions (acceptable fallback)

### Xaero's Minimap (`xaerominimap`)
- **Location**: Client-side — `<minecraft>/XaeroWaypoints/<server_id>/`
- **Structure**: `<server_id>/dim%<dimension_namespace>%<dimension_path>/` (e.g., `dim%parallelworlds%pw_overworld_0/`)
  - Inside: `mw$default/` folder with waypoint `.txt` files
- **Cleanup trigger**: Same as World Map — client-side folder deletion
- **Note**: Shares naming convention with World Map (lowercase `dim%` vs `DIM%`)

### JourneyMap (`journeymap`)
- **Location**: Client-side — `<minecraft>/journeymap/data/<server_type>/`
  - `<server_type>` is `mp` (multiplayer), `sp` (singleplayer), or the server name
- **Structure**: `<server_type>/DIM<dimension_id>/` — JourneyMap uses integer dimension IDs or namespaced paths
  - For modded dimensions: folder named by the full ResourceLocation with `:` replaced
  - Inside: `day/`, `night/`, `topo/`, `cave/` subfolders with region PNGs, plus `waypoints/` folder
- **Cleanup trigger**: Client deletes the dimension folder
- **API**: JourneyMap has a plugin API (`journeymap-api`) but cleanup of cached data isn't exposed

### Distant Horizons (`distanthorizons`)
- **Location**: **Server-side** (when server-side DH is installed) — `world/dimensions/parallelworlds/pw_<type>_<counter>/data/DistantHorizons.sqlite`
  - Also: `world/data/DistantHorizons/` may contain shared configs
- **Client-side**: `<minecraft>/config/DistantHorizons/data/<server_id>/` with dimension-keyed SQLite databases
- **Structure**: SQLite database files per dimension, named by dimension ResourceLocation
- **Cleanup trigger**: 
  - Server-side: Delete the `.sqlite` file in the dimension folder (handled automatically when dimension folder is deleted by `DimensionCleanup`)
  - Client-side: Client needs to delete/recreate the dimension's LOD database
- **Note**: DH's server-side data lives INSIDE the dimension folder, so `DimensionCleanup.deleteRecursively()` already handles it. The client-side cache is the issue.

## Design

### Architecture Overview
```
                    Server                                    Client
┌─────────────────────────────────┐        ┌──────────────────────────────────┐
│  DimensionCleanup               │        │  ModDataCleanupHandler           │
│    ↓                            │        │    ↓                             │
│  ModCompatCleanup               │  ───►  │  XaeroCleanup (optional)        │
│    ├─ server-side DH cleanup    │ packet │  JourneyMapCleanup (optional)   │
│    └─ send cleanup packet ──────┼────────┤  DHClientCleanup (optional)     │
│                                 │        │                                  │
│  DimensionCleanupPayload        │        │  (detects installed mods,        │
│                                 │        │   deletes cached data)           │
└─────────────────────────────────┘        └──────────────────────────────────┘
```

### Key Design Decisions
1. **No hard dependencies** — All mod detection uses `ModList.get().isLoaded("modid")` checks. The cleanup system is a no-op if none of the target mods are installed.
2. **Client-side cleanup via packet** — Server sends a `DimensionCleanupPayload` listing deleted dimension IDs. The client handler checks which mapping mods are installed and deletes their cached data.
3. **Server-side DH cleanup is automatic** — Since DH stores data inside the dimension folder, `DimensionCleanup.deleteRecursively()` already handles it. No extra server-side work needed for DH.
4. **Graceful failure** — All file deletions are wrapped in try-catch. If a file is locked or missing, log a warning and continue. Never crash the game over cleanup.
5. **Config toggle** — `modCompatCleanupEnabled` (default: true) to let users disable this entirely.
6. **Cleanup on join** — When a player joins, if dimensions were cleaned up since their last session, send them the cleanup packet for any dimensions that were removed.

### When Cleanup Triggers
1. **Server startup** — `DimensionCleanup.cleanupOldDimensions()` runs, deletes old dimension folders. After cleanup, record which dimension IDs were deleted in `PWSavedData.pendingClientCleanups`.
2. **Player join** — Check `PWSavedData.pendingClientCleanups`. If non-empty, send `DimensionCleanupPayload` to the joining player. Mark as sent for that player.
3. **All players notified** — Once all online players have been notified, clear `pendingClientCleanups` (or keep until next restart for players who weren't online).
4. **Seed rotation** — Same as startup cleanup — old dimensions are deleted, new IDs recorded.

## Tasks

### 1. Config Addition
**File**: `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` + `PWConfig.java`

Add to the `[compatibility]` section:
```toml
[compatibility]
# Enable automatic cleanup of third-party mod cached data (Xaero's Map, JourneyMap, Distant Horizons)
# when exploration dimensions are regenerated or deleted.
modCompatCleanupEnabled = true
```

- `PWConfigSpec`: `BooleanValue MOD_COMPAT_CLEANUP_ENABLED`
- `PWConfig`: `static boolean modCompatCleanupEnabled`, getter `isModCompatCleanupEnabled()`

### 2. Dimension Cleanup Tracking
**File**: `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java`

Add tracking for which dimensions were cleaned up and which players have been notified:
- `Set<String> pendingCleanupDimensions` — dimension paths (e.g., `"pw_overworld_0"`) that were deleted since last full notification
- `Set<UUID> cleanupNotifiedPlayers` — players who received the cleanup packet this cycle
- NBT key: `"PendingCleanupDimensions"` (ListTag of StringTags)
- `addPendingCleanup(String dimensionPath)`
- `getPendingCleanups() → Set<String>`
- `markPlayerNotified(UUID playerUuid)`
- `isPlayerNotified(UUID playerUuid) → boolean`
- `clearPendingCleanups()` — called when all known players have been notified or on shutdown

### 3. Hook DimensionCleanup to Record Deleted Dimensions
**File**: `src/main/java/com/agent772/parallelworlds/dimension/DimensionCleanup.java`

Modify `cleanupOldDimensions()`:
- After successfully deleting a dimension folder, record the folder name in `PWSavedData.addPendingCleanup(folderName)`
- This requires passing `MinecraftServer` (already available) to access `PWSavedData`
- Add a return value or callback: `List<String> deletedDimensionPaths` so callers know what was cleaned

Updated signature concept:
```java
public static List<String> cleanupOldDimensions(MinecraftServer server) {
    List<String> deleted = new ArrayList<>();
    // ... existing logic ...
    // After deleteRecursively(folder):
    deleted.add(folder.getFileName().toString());
    // ... 
    // Record in saved data
    PWSavedData data = PWSavedData.get(server);
    deleted.forEach(data::addPendingCleanup);
    return deleted;
}
```

### 4. Cleanup Notification Packet
**File**: `src/main/java/com/agent772/parallelworlds/network/payload/DimensionCleanupPayload.java`

New packet payload:
```java
public record DimensionCleanupPayload(List<String> deletedDimensionPaths) implements CustomPacketPayload {
    public static final Type<DimensionCleanupPayload> TYPE = 
        new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "dimension_cleanup"));
    
    public static final StreamCodec<FriendlyByteBuf, DimensionCleanupPayload> STREAM_CODEC = 
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            DimensionCleanupPayload::deletedDimensionPaths,
            DimensionCleanupPayload::new
        );
    
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

- Register in `PWNetworking.java` alongside existing payloads
- Direction: server → client (play phase)

### 5. Client-Side Cleanup Handler
**File**: `src/main/java/com/agent772/parallelworlds/client/ModDataCleanupHandler.java`

Central client-side handler that dispatches cleanup to mod-specific cleaners:
```java
public final class ModDataCleanupHandler {
    
    public static void onDimensionCleanupReceived(DimensionCleanupPayload payload, 
                                                    IPayloadContext context) {
        if (!PWConfig.isModCompatCleanupEnabled()) return;
        
        context.enqueueWork(() -> {
            List<String> paths = payload.deletedDimensionPaths();
            LOGGER.info("Received cleanup notification for {} dimensions", paths.size());
            
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            
            for (String dimPath : paths) {
                cleanupXaeroWorldMap(gameDir, dimPath);
                cleanupXaeroMinimap(gameDir, dimPath);
                cleanupJourneyMap(gameDir, dimPath);
                cleanupDistantHorizons(gameDir, dimPath);
            }
        });
    }
}
```

**Important**: This class must be in a client-only source set or guarded with `@OnlyIn(Dist.CLIENT)` / `DistExecutor` patterns. Since NeoForge 1.21.1 uses the `client` source concept or dist checks, ensure no server-side classloading of Minecraft client classes.

### 6. Xaero's World Map Cleanup
**In**: `ModDataCleanupHandler.java` (private method)

```java
private static void cleanupXaeroWorldMap(Path gameDir, String dimPath) {
    if (!isModLoaded("xaeroworldmap")) return;
    
    // Xaero's World Map folder: XaeroWorldMap/<server_id>/DIM%<namespace>%<path>/
    Path xaeroRoot = gameDir.resolve("XaeroWorldMap");
    if (!Files.isDirectory(xaeroRoot)) return;
    
    String xaeroDimFolder = "DIM%" + ParallelWorlds.MOD_ID + "%" + dimPath;
    
    // Iterate all server folders (we don't know which server ID the client uses)
    try (Stream<Path> servers = Files.list(xaeroRoot)) {
        for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
            Path target = serverDir.resolve(xaeroDimFolder);
            if (Files.isDirectory(target)) {
                LOGGER.info("Cleaning up Xaero's World Map data: {}", target);
                deleteRecursively(target);
            }
        }
    } catch (IOException e) {
        LOGGER.warn("Failed to cleanup Xaero's World Map data for {}", dimPath, e);
    }
}
```

### 7. Xaero's Minimap Cleanup
**In**: `ModDataCleanupHandler.java` (private method)

```java
private static void cleanupXaeroMinimap(Path gameDir, String dimPath) {
    if (!isModLoaded("xaerominimap")) return;
    
    // Xaero's Minimap folder: XaeroWaypoints/<server_id>/dim%<namespace>%<path>/
    Path xaeroRoot = gameDir.resolve("XaeroWaypoints");
    if (!Files.isDirectory(xaeroRoot)) return;
    
    String xaeroDimFolder = "dim%" + ParallelWorlds.MOD_ID + "%" + dimPath;
    
    try (Stream<Path> servers = Files.list(xaeroRoot)) {
        for (Path serverDir : servers.filter(Files::isDirectory).toList()) {
            Path target = serverDir.resolve(xaeroDimFolder);
            if (Files.isDirectory(target)) {
                LOGGER.info("Cleaning up Xaero's Minimap data: {}", target);
                deleteRecursively(target);
            }
        }
    } catch (IOException e) {
        LOGGER.warn("Failed to cleanup Xaero's Minimap data for {}", dimPath, e);
    }
}
```

### 8. JourneyMap Cleanup
**In**: `ModDataCleanupHandler.java` (private method)

```java
private static void cleanupJourneyMap(Path gameDir, String dimPath) {
    if (!isModLoaded("journeymap")) return;
    
    // JourneyMap folder: journeymap/data/<server_type>/
    // Dimension folders use ResourceLocation format: <namespace>~<path> or similar
    Path jmRoot = gameDir.resolve("journeymap").resolve("data");
    if (!Files.isDirectory(jmRoot)) return;
    
    // JourneyMap dimension folder name: "parallelworlds~pw_overworld_0" 
    // (ResourceLocation with : replaced by ~)
    String jmDimFolder = ParallelWorlds.MOD_ID + "~" + dimPath;
    
    // Search all server type folders (mp, sp, server names)
    try (Stream<Path> serverTypes = Files.list(jmRoot)) {
        for (Path serverTypeDir : serverTypes.filter(Files::isDirectory).toList()) {
            Path target = serverTypeDir.resolve(jmDimFolder);
            if (Files.isDirectory(target)) {
                LOGGER.info("Cleaning up JourneyMap data: {}", target);
                deleteRecursively(target);
            }
        }
    } catch (IOException e) {
        LOGGER.warn("Failed to cleanup JourneyMap data for {}", dimPath, e);
    }
}
```

### 9. Distant Horizons Client Cleanup
**In**: `ModDataCleanupHandler.java` (private method)

```java
private static void cleanupDistantHorizons(Path gameDir, String dimPath) {
    if (!isModLoaded("distanthorizons")) return;
    
    // DH client data: config/DistantHorizons/data/<server_id>/
    // Dimension databases named by ResourceLocation
    Path dhRoot = gameDir.resolve("config").resolve("DistantHorizons").resolve("data");
    if (!Files.isDirectory(dhRoot)) return;
    
    // DH uses the full ResourceLocation as the SQLite DB name
    String dhDimName = ParallelWorlds.MOD_ID + "%" + dimPath;
    // Also check for colon-separated variant
    String dhDimNameAlt = ParallelWorlds.MOD_ID + "_" + dimPath;
    
    try (Stream<Path> serverDirs = Files.list(dhRoot)) {
        for (Path serverDir : serverDirs.filter(Files::isDirectory).toList()) {
            // DH may store as folder or .sqlite file — check both patterns
            deleteMatchingEntries(serverDir, dhDimName);
            deleteMatchingEntries(serverDir, dhDimNameAlt);
        }
    } catch (IOException e) {
        LOGGER.warn("Failed to cleanup Distant Horizons data for {}", dimPath, e);
    }
}

private static void deleteMatchingEntries(Path parent, String pattern) throws IOException {
    try (Stream<Path> entries = Files.list(parent)) {
        for (Path entry : entries.toList()) {
            if (entry.getFileName().toString().contains(pattern)) {
                LOGGER.info("Cleaning up Distant Horizons data: {}", entry);
                if (Files.isDirectory(entry)) {
                    deleteRecursively(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
```

### 10. Wire Into Event Handlers
**File**: `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java`

In `onPlayerLoggedIn()`, after existing sync logic:
```java
// Send pending mod data cleanup notifications
if (PWConfig.isModCompatCleanupEnabled()) {
    PWSavedData data = PWSavedData.get(server);
    Set<String> pending = data.getPendingCleanups();
    if (!pending.isEmpty() && !data.isPlayerNotified(player.getUUID())) {
        PacketDistributor.sendToPlayer(player, 
            new DimensionCleanupPayload(new ArrayList<>(pending)));
        data.markPlayerNotified(player.getUUID());
    }
}
```

### 11. Register Payload in PWNetworking
**File**: `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java`

Add registration for `DimensionCleanupPayload`:
```java
registrar.playToClient(
    DimensionCleanupPayload.TYPE,
    DimensionCleanupPayload.STREAM_CODEC,
    ModDataCleanupHandler::onDimensionCleanupReceived
);
```

### 12. Client Handler Registration
**File**: `src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java`

Ensure `ModDataCleanupHandler` is properly referenced from the client-side payload handler. If `PWClientHandler` already dispatches payloads, add the cleanup payload handling there and delegate to `ModDataCleanupHandler`.

### 13. Utility: Mod Detection Helper
**In**: `ModDataCleanupHandler.java` (private method)

```java
private static boolean isModLoaded(String modId) {
    try {
        return net.neoforged.fml.ModList.get().isLoaded(modId);
    } catch (Exception e) {
        return false;
    }
}
```

### 14. Utility: Recursive Delete
**In**: `ModDataCleanupHandler.java` (private method)

Reuse the same pattern as `DimensionCleanup.deleteRecursively()`:
```java
private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) return;
    try (Stream<Path> walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); }
            catch (IOException e) { LOGGER.warn("Could not delete {}", p, e); }
        });
    } catch (IOException e) {
        LOGGER.warn("Failed to walk directory for deletion: {}", root, e);
    }
}
```

## Security Considerations

### Path Traversal Prevention
The `dimPath` values come from the server and are used to construct file paths on the client. To prevent path traversal attacks:
- Validate that `dimPath` matches the expected pattern `pw_\w+_\d+` before using it in any file path operations
- Never allow `..`, `/`, `\`, or other path separators in `dimPath`
- All cleanup operations are scoped to known mod data directories — never allow arbitrary path deletion

```java
private static final Pattern VALID_DIM_PATH = Pattern.compile("^pw_\\w+_\\d+$");

private static boolean isValidDimPath(String dimPath) {
    return VALID_DIM_PATH.matcher(dimPath).matches();
}

// In onDimensionCleanupReceived, before processing:
List<String> safePaths = paths.stream()
    .filter(ModDataCleanupHandler::isValidDimPath)
    .toList();
```

### File Locking
Some mods (especially Distant Horizons with SQLite) may have files locked. The cleanup must:
- Use `Files.deleteIfExists()` which handles missing files gracefully
- Catch and log `IOException` without crashing
- Consider that cleanup may partially fail if files are locked — this is acceptable

## Files Summary

### New Files
- `src/main/java/com/agent772/parallelworlds/client/ModDataCleanupHandler.java` — client-side mod data cleanup dispatcher
- `src/main/java/com/agent772/parallelworlds/network/payload/DimensionCleanupPayload.java` — cleanup notification packet

### Modified Files
- `src/main/java/com/agent772/parallelworlds/dimension/DimensionCleanup.java` — record deleted dimensions, return deleted list
- `src/main/java/com/agent772/parallelworlds/data/PWSavedData.java` — pending cleanup tracking
- `src/main/java/com/agent772/parallelworlds/config/PWConfigSpec.java` — add `modCompatCleanupEnabled`
- `src/main/java/com/agent772/parallelworlds/config/PWConfig.java` — add config getter
- `src/main/java/com/agent772/parallelworlds/network/PWNetworking.java` — register cleanup payload
- `src/main/java/com/agent772/parallelworlds/event/PWEventHandlers.java` — send cleanup packets on player join
- `src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java` — wire cleanup handler

## Verification
- **No target mods installed**: Cleanup system is a no-op, no errors logged
- **Xaero's installed**: After dimension regeneration, player joins → Xaero's map/waypoint folders for old dimensions are deleted
- **JourneyMap installed**: After dimension regeneration, JourneyMap data folders are cleaned up
- **Distant Horizons installed**: 
  - Server-side: DH SQLite files are deleted along with dimension folder (already works via `DimensionCleanup`)
  - Client-side: DH LOD databases for old dimensions are cleaned up
- **Mixed install**: Only installed mods have their data cleaned; no errors for missing mods
- **Config disabled**: `modCompatCleanupEnabled = false` → no cleanup packets sent, no client-side deletion
- **Path traversal**: Malformed dimension paths in packets are rejected (validated against regex)
- **File locking**: Locked files log a warning but don't crash the game
- **Player offline during cleanup**: Cleanup notification is sent when they next join
- **Multiple cleanups between joins**: All deleted dimensions are batched into one packet
- `./gradlew build` succeeds

## Do NOT
- Add hard dependencies on Xaero's, JourneyMap, or Distant Horizons — all detection must be soft/optional
- Use reflection to call mod internals — only interact via file system cleanup
- Delete files outside of known mod data directories
- Crash the game if cleanup fails — always fail gracefully with logging
- Send cleanup packets if the feature is disabled in config
- Trust dimension path strings from packets without validation
