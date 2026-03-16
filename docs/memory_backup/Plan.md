---

## Plan: Parallel Worlds — Mining Dimension Mod

**TL;DR**: Build a NeoForge 1.21.1 mod (`parallelworlds`) that creates configurable, temporary mining dimensions with seed rotation, custom portal entrance, item/ender-chest restrictions by tag, and performance-focused chunk management. 100% original implementation (BrecherDimensions studied for patterns only — it's All Rights Reserved).

---

### Epic Dependency Graph

```
Epic 1 (Skeleton + Config)
  └─► Epic 2 (Dimension Engine) ◄── CRITICAL PATH
        ├─► Epic 3 (Teleportation)
        │     └─► Epic 4 (Portal System)
        ├─► Epic 5 (Restrictions)        [parallel with 3]
        ├─► Epic 6 (Persistence)         [depends on 2+3]
        ├─► Epic 7 (Performance/PreGen)  [parallel with 3]
        ├─► Epic 8 (Commands)            [depends on 2+3+7]
        ├─► Epic 9 (Events/Lifecycle)    [depends on 2+3+5+6]
        ├─► Epic 10 (Networking)         [parallel with 3]
        └─► Epic 11 (Threading Spike)    [independent research]
```

---

### **Epic 1: Project Skeleton & Configuration**
Create bootable mod with comprehensive server-side TOML config (~60 settings across 8 groups: general, seed, portal, restrictions, persistence, performance, safety, locks). NeoForge `ModConfigSpec.Builder`, `@Mod` entry point, lifecycle hooks.

**Files**: `ParallelWorlds.java`, `PWConfig.java`, `PWConfigSpec.java`, updated `neoforge.mods.toml`
**Verify**: `./gradlew build` passes, config file generated with all defaults

---

### **Epic 2: Dynamic Dimension Creation (Core Engine)**
Runtime dimension creation via mixin accessors on `MinecraftServer` and `MappedRegistry`. Unfreeze registry → register → refreeze pattern. Seed strategies (random/date/weekly) with ThreadLocal context for chunk generator creation. Dimension counter persistence, cleanup of old dimension folders.

**Key classes**: `DimensionFactory`, `DimensionRegistrar`, `DimensionManager`, `SeedManager`, `ExplorationSeedManager`, `DimensionCounter`, `DimensionCleanup`
**Mixins**: `MixinMinecraftServer`, `MixinMappedRegistry`, `MixinServerLevel`, `MixinChunkMap`
**Verify**: Server starts with exploration dimensions, seeds follow configured strategy, old folders cleaned up

---

### **Epic 3: Teleportation & Safe Spawning**
Multi-phase safe position finding (8→16→32 block radius search, 5ms time budget). Dimension-specific spawn logic (overworld surface, nether enclosed, end platform). Cooldown system, 100-tick post-teleport invulnerability, vehicle dismount, return position with 7-day expiry.

**Key classes**: `TeleportHandler`, `ReturnPosition`, `DimensionEnvironment`
**Verify**: Safe landing in all 3 dim types, cooldown works, emergency platform created when needed

---

### **Epic 4: Custom Portal System**
Rectangular frame detection (like Nether portal) with configurable frame block (`pw_portal_frame`). Portal fill block, activation via flint_and_steel, bidirectional teleport, frame destruction → portal collapse. Block/item registration, data generation, textures.

**Key classes**: `PWPortalFrameBlock`, `PWPortalBlock`, `PortalShape`, `PortalActivation`, `PWBlocks`, `PWItems`
**Verify**: Frame builds, activates, teleports, collapses on break, disabled via config

---

### **Epic 5: Item & Ender Chest Restrictions**
Tag-based item/block restrictions with three modes: **BLOCK** (cancel + message), **WARN** (allow + log), **ALLOW** (no action). Default tag includes ender chest. Hooks into right-click and placement events. Only active inside mining dimensions.

**Key classes**: `RestrictionHandler`, `RestrictionMode` enum
**Tags**: `parallelworlds:restricted_items`, `parallelworlds:restricted_blocks`
**Verify**: Each mode works correctly, restrictions only in mining dims

---

### **Epic 6: Data Persistence & Lifecycle**
`PWSavedData` (extends SavedData) persists: return positions, dimension metadata, player stats, seeds (for rotation comparison), pre-gen progress, manual unlocks. Seed comparison logic: same seed → reuse dimension; different seed → cleanup + recreate.

**Key classes**: `PWSavedData`, `DimensionMetadata`, `PlayerExplorationStats`
**Verify**: Return positions survive restart, seed rotation triggers recreation, corrupted NBT handled gracefully

---

### **Epic 7: Performance & Chunk Management**
TPS-aware chunk pre-generation with spiral iterator pattern. Throttling bands: full ≥19.5 TPS, reduced ≥19.0, minimum ≥18.0, pause <18.0. Memory monitoring (pause >85% heap). Ticket management (add before load, schedule removal). Resumable across restarts.

**Key classes**: `ChunkPreGenerator`, `GenerationTask`, `SpiralIterator`, `GenerationProgress`, `ChunkManager`, `MemoryMonitor`
**Verify**: Pre-gen runs without TPS drops, resumes after restart, no ticket leaks

---

### **Epic 8: Commands**
Player: `/pw list|tp|return|info|help`. Admin: `/pwadmin returnall|info|stats|seed|reload|pregen start/stop/pause/resume/status|unlock/lock`. Tab completion, color-coded output, thousands separators.

**Verify**: All commands work, permissions enforced, tab completion functional

---

### **Epic 9: Event Handling & Player Lifecycle**
Join→sync+evacuate-if-needed, Leave→clear-invulnerability (prevents god-mode bug), Death→optional keepInventory, Respawn→restore inventory, Dimension change→track+message, Block interact→delegate to restrictions.

**Key classes**: `PWEventHandlers`, `InventoryKeeper`
**Verify**: No god-mode persistence, inventory preserved on death when configured

---

### **Epic 10: Networking & Client Sync**
Payloads: `DimensionSyncPayload`, `DimensionResetPayload`, `ResetWarningPayload`. Sync on join, creation, removal. Periodic reset warnings on action bar.

**Verify**: Client stays in sync, warnings display correctly

---

### **Epic 11: Threading Research Spike**
Research Minecraft's thread safety model, study C2ME and Dimensional Threading approaches my, analyze three options (async chunk gen only / separate tick thread / worker pool). Produce `docs/threading-research.md` with risk/benefit matrix and recommendation.

**Output**: Research document, not code

---

### Verification Strategy

**GameTests** (NeoForge GameTest framework):
1. `DimensionCreationTest` — runtime creation, seed assignment, cleanup
2. `TeleportSafetyTest` — safe spawn, cooldown, return
3. `PortalShapeTest` — frame detection, activation, destruction
4. `RestrictionTest` — each mode × ender chest + tagged items
5. `PersistenceTest` — save/load, seed rotation detection
6. `PreGenTest` — spiral coverage, throttling, resume
7. `CommandTest` — execute commands, verify output
8. `EventLifecycleTest` — join/leave/death/respawn sequences

---

### Decisions
- **Original code only** — BrecherDimensions is All Rights Reserved; patterns studied, zero code copied
- **NeoForge 1.21.1 primary** — Fabric/multiloader/1.20.1 backport are future scope
- **Portal**: custom frame (like Nether portal with configurable blocks)
- **Restrictions**: BLOCK/WARN/ALLOW modes for ender chest and tagged items
- **Persistence**: configurable per-server (persist-until-rotation OR regenerate-each-restart)
- **Threading**: research first (Epic 11), decide later
- **Mod ID**: `parallelworlds`

### Scope Exclusions
- Fabric/multiloader support
- 1.20.1 Forge backport
- Custom portal particle effects
- Modded dimension support (only overworld/nether/end initially)
- Corpse mod compatibility
- Client-side HUD overlays beyond action bar messages

---

All 11 epic sub-agent instruction files are saved in session memory. **Note**: Since I'm in Plan mode without file editing tools, the documentation files need to be created in the actual repo (`docs/`) once you switch to implementation mode. The first implementation step should create `docs/PLAN.md` and the epic instruction files in `docs/epics/`.

Ready for your review — any changes, questions, or ready to proceed?