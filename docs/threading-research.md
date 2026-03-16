# Threading Research Spike — Parallel Worlds

**Epic 11 | Status: COMPLETE | Date: 2026-03-15**

## Executive Summary

This document investigates the feasibility of running exploration dimensions on separate threads or leveraging async patterns for better server performance in the Parallel Worlds mod (NeoForge 1.21.1, Java 21).

**Recommendation**: **Option A (Async Chunk Pre-Generation)** for v1, with **Option C (Worker Thread Pool)** as a future enhancement. Full dimensional threading (Option B) is not recommended due to extreme risk and marginal benefit for our use case.

---

## Table of Contents

1. [Current Threading Model](#1-current-threading-model)
2. [Minecraft's Thread Safety Model](#2-minecrafts-thread-safety-model)
3. [Existing Community Approaches](#3-existing-community-approaches)
4. [Option Analysis](#4-option-analysis)
5. [Risk/Benefit Matrix](#5-riskbenefit-matrix)
6. [Known Thread-Safety Hazards](#6-known-thread-safety-hazards)
7. [Recommendation & Implementation Plan](#7-recommendation--implementation-plan)
8. [Appendix: Code Audit Results](#appendix-code-audit-results)

---

## 1. Current Threading Model

### Architecture: Single-Threaded Core

Parallel Worlds runs **all game logic on the main server thread**. The only off-thread work is a single daemon thread for teleport cooldown cleanup.

| Component | Thread | Concurrency Constructs |
|-----------|--------|----------------------|
| Chunk Pre-Generation | Main thread | ConcurrentHashMap (task map), ConcurrentLinkedQueue (ticket removals) |
| Dimension Creation | Main thread (startup) | ReentrantReadWriteLock (registry mutation) |
| Teleportation | Main thread | ConcurrentHashMap (cooldown tracking) |
| Teleport Cleanup | Daemon thread | ScheduledExecutorService (5-min interval) |
| Seed Injection | Main thread | ThreadLocal (dimension context) |
| Memory Monitoring | Main thread | Stateless (JVM Runtime queries) |
| Registry Mutation | Main thread (startup) | ReentrantReadWriteLock, volatile flag |
| Inventory Keeping | Main thread | ConcurrentHashMap |

### Defensive Concurrency

The codebase uses `ConcurrentHashMap` in 6+ locations defensively, even though most access is main-thread-only. This is good practice — it future-proofs against accidental cross-thread access and costs negligible overhead.

### Tick Budget

The chunk pre-generator enforces a **5ms tick budget** (~10% of a 50ms tick at 20 TPS) with TPS-aware throttling:

| TPS Range | Behavior |
|-----------|----------|
| ≥ 19.5 | Full chunk budget (configured max) |
| ≥ 19.0 | Reduced (50% of max) |
| ≥ 18.0 | Minimum (1 chunk/tick) |
| < 18.0 | **Paused** — no pre-gen |

Memory pressure (>85% heap) also pauses pre-generation.

---

## 2. Minecraft's Thread Safety Model

### 2.1 The Server Tick Loop

Minecraft's server runs a single-threaded tick loop (`MinecraftServer.runServer()`) at 20 TPS (50ms per tick). Each tick:

1. **Process pending tasks** — queued `TickTask` objects (scheduled callbacks)
2. **Tick all dimensions** — sequentially calls `ServerLevel.tick()` for each loaded dimension
3. **Tick connections** — process incoming packets
4. **Auto-save** — periodic world saves

**Critical invariant**: All `ServerLevel`, `Entity`, `BlockEntity`, and `ChunkAccess` operations assume single-thread access. There are **no locks** protecting these data structures.

### 2.2 What IS Thread-Safe in Vanilla

| Component | Thread Safety | Notes |
|-----------|--------------|-------|
| `ChunkMap` internal futures | Yes (CompletableFuture) | Chunk loading uses async futures internally |
| `ThreadedLevelLightEngine` | Yes (own thread) | Lighting calculated on separate thread, results applied on main |
| `ServerChunkCache.mainThreadProcessor` | Yes (task queue) | Tasks queued from async threads, executed on main |
| `MinecraftServer.tell(TickTask)` | Yes (thread-safe queue) | Safe to schedule work from any thread |
| `ProcessorMailbox` | Yes (lock-free queue) | Internal messaging between chunk system components |

### 2.3 What is NOT Thread-Safe

| Component | Risk | Shared State |
|-----------|------|-------------|
| `ServerLevel.tick()` | **CRITICAL** | Block updates, entity ticking, redstone, weather |
| `Entity` state | **CRITICAL** | Position, health, inventory, passengers, effects |
| `BlockEntity` state | **HIGH** | Container contents, processing state, rendering data |
| `LevelChunk` sections | **HIGH** | Block states, biomes, heightmap |
| Player inventory | **HIGH** | Slot manipulation, crafting state |
| Scoreboard / Advancements | **MEDIUM** | Cross-dimension shared singleton via `MinecraftServer` |
| Recipe manager | **LOW** | Read-only after startup (mostly) |
| Registry access | **LOW** | Frozen after startup (should be read-only) |

### 2.4 Vanilla Async Chunk Loading (1.14+)

Since 1.14, Minecraft uses `CompletableFuture<ChunkAccess>` internally for chunk loading:

```
ChunkMap.getChunkFutureMainThread(x, z, status, create)
  └→ Returns CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
       └→ Chunk generation stages run via task queue
            └→ Final result delivered via mainThreadProcessor
```

**How it works**:
- `ChunkMap` manages a pool of `ChunkHolder` objects
- Each holder tracks a chunk's status progression (EMPTY → STRUCTURE_STARTS → ... → FULL)
- Generation tasks are queued and processed, with results chained via `CompletableFuture`
- **Lighting** runs on its own thread (`ThreadedLevelLightEngine`)
- **Final chunk application** always happens on the main thread via `mainThreadProcessor`

**Key insight**: Vanilla already has async infrastructure for chunk I/O and light calculation, but **structure generation, feature placement, and entity spawning** all run synchronously on the main thread.

### 2.5 Shared State Between Dimensions

These singletons are accessed by all dimensions and would require synchronization in a multi-threaded model:

- `MinecraftServer.getScoreboard()` — single instance
- `MinecraftServer.getAdvancements()` — shared progress tracking
- `MinecraftServer.getRecipeManager()` — shared recipes
- `MinecraftServer.getPlayerList()` — player connections
- `MinecraftServer.getStructureManager()` — structure template cache
- `MinecraftServer.overworld()` — spawn point, world border reference
- `MinecraftServer.getGameRules()` — shared game rules
- Cross-dimension entity references (passengers, leash holders)
- Cross-dimension command execution
- Cross-dimension teleportation (changes entity's level reference)

---

## 3. Existing Community Approaches

### 3.1 C2ME (Concurrent Chunk Management Engine) — Fabric

**What it does**: Parallelizes chunk generation stages across multiple threads.

**How it works**:
- Replaces `ChunkMap`'s sequential processing with a thread pool
- Noise generation, surface building, and feature placement run on worker threads
- Uses fine-grained locks per-chunk to prevent concurrent modification of adjacent chunks
- Heightmap and lighting parallelized with careful synchronization

**What broke**:
- Mods that store shared mutable state in chunk generators (common in worldgen mods)
- Mods that access neighboring chunks during generation without proper guards
- Structure generation that reads/writes cross-chunk data
- Occasional worldgen artifacts from race conditions in biome blending

**What works**:
- Significant speedup for vanilla worldgen (2-4x on multi-core systems)
- Reliable with vanilla and well-designed mods
- Active maintenance catches edge cases

**Relevance to Parallel Worlds**: C2ME's approach is Fabric-only and deeply coupled to Fabric's chunk loading internals. The pattern (parallelize generation stages) is sound but would require significant NeoForge-specific implementation. **Not directly portable.**

### 3.2 Dimensional Threading (1.16 mod)

**What it did**: Ran each dimension's tick on a separate Java thread.

**How it worked**:
- Replaced `MinecraftServer.tickChildren()` sequential loop with parallel `ForkJoinPool`
- Each `ServerLevel.tick()` ran on its own thread
- Synchronized on shared resources (scoreboard, player list) via locks

**Why it was discontinued**:
- **Cross-dimension entity interactions** caused hard-to-reproduce crashes
- **Redstone circuits** that span chunk boundaries behaved unpredictably
- **Mod compatibility**: virtually every mod assumed single-threaded access
- **Player teleportation** between dimensions caused race conditions (entity exists in two levels simultaneously during transfer)
- **Command execution** from console/command blocks was unsafe (could target entities in other dimensions mid-tick)
- **Maintenance burden**: every Minecraft version changed internal threading assumptions

**Key lesson**: Dimensional threading is theoretically appealing but practically unworkable with the Minecraft ecosystem. The mod worked for vanilla-only servers with no cross-dimension interaction, which is an unrealistic constraint.

### 3.3 Vanilla Improvements (1.18+)

Mojang has incrementally improved threading:
- **1.14**: Async chunk loading via `CompletableFuture`
- **1.17**: `ThreadedLevelLightEngine` for off-thread lighting
- **1.18+**: Improved chunk generation pipeline with better task scheduling
- **1.20+**: Chunk system rewrite with `ChunkGenerationTask` and priority scheduling

These improvements mean vanilla already handles the "easy wins" for async chunk work. The remaining synchronous work (feature placement, entity ticking, block updates) is synchronous **by design** because it mutates shared world state.

---

## 4. Option Analysis

### Option A: Async Chunk Pre-Generation

**Concept**: Move the computational parts of chunk pre-generation off the main thread while keeping world mutation on the main thread.

**Implementation**:
```
Main Thread                          Worker Thread(s)
─────────────────────────────────    ─────────────────────
1. Submit chunk coords to workers     
                                     2. Compute noise values
                                     3. Generate heightmap
                                     4. Compute surface rules
                                     5. Generate structures (read-only probe)
                                     6. Return computed data
7. Apply computed data to world
8. Place features & entities
9. Mark chunk FULL
```

**What can run off-thread**:
- Noise computation (`NoiseChunk`, density functions)
- Heightmap calculation
- Biome sampling
- Surface rule evaluation

**What MUST stay on main thread**:
- Block placement into `LevelChunk`
- Feature placement (trees, ores — reads neighbor chunks)
- Entity spawning
- Structure piece placement
- Lighting application
- Chunk status transitions

**Complexity**: MODERATE — requires intercepting `NoiseBasedChunkGenerator` to split computation from application.

**Risk**: LOW — well-understood producer/consumer pattern. Worst case: chunks generate slightly out of order (cosmetic, not functional).

**Benefit**: MODERATE — noise computation is ~40-60% of chunk gen time. 2x pre-gen throughput on multi-core servers without TPS impact.

**Compatibility**: HIGH — mod worldgen that only hooks generation stages works fine. Custom generators that use global mutable state during noise computation would need serialization.

### Option B: Separate Tick Thread for Mining Dimensions

**Concept**: Each exploration dimension ticks on its own Java thread, isolated from the overworld tick.

**Implementation**:
```
Main Thread (Overworld)              Dimension Thread (pw_overworld_0)
─────────────────────────            ────────────────────────────────
tick overworld                        tick exploration dim
  block updates                         block updates
  entity ticking                        entity ticking
  weather                               weather
  ─── synchronization barrier ───
  process cross-dim events            process cross-dim events
  player transfers                    player transfers
```

**Required synchronization points**:
1. Player teleportation (entity must be atomically moved between levels)
2. Scoreboard updates (shared singleton)
3. Advancement triggers (shared state)
4. Command execution (can target any dimension)
5. Game rule changes (shared)
6. World save (must not save mid-tick)
7. Server shutdown (orderly drain)
8. Player list operations
9. Chunk ticket management across dimensions

**Complexity**: EXTREME — requires wrapping nearly every shared `MinecraftServer` method with synchronization.

**Risk**: VERY HIGH
- Every mod that touches `ServerLevel` assumes single-threaded access
- Entity AI, pathfinding, and goal systems mutate shared state
- `RandomSource` instances in chunk generators are NOT thread-safe
- Block entity ticking (hoppers, furnaces) reads neighbor blocks
- NeoForge event bus fires from the ticking thread — every listener must be thread-safe
- Debugging concurrent issues in a modded environment is extremely difficult

**Benefit**: HIGH in theory — full TPS isolation. If the exploration dimension lags, overworld is unaffected. But our dimensions are temporary mining worlds with minimal entity/redstone load, so the actual benefit is small.

**Compatibility**: VERY LOW — breaks most mods. Even Parallel Worlds' own code (DimensionManager, TeleportHandler) would need significant thread-safety work.

### Option C: Worker Thread Pool for Heavy Operations

**Concept**: Main thread ticks normally. Heavy, non-gameplay operations run on worker threads and queue results back to the main thread.

**Implementation**:
```
Main Thread                          Worker Pool
─────────────                        ────────────
1. Enqueue work                       
                                     2. Dimension cleanup (file I/O)
                                     3. Pre-gen noise computation
                                     4. Memory analysis
                                     5. Saved data serialization
6. Dequeue & apply results
```

**Candidates for off-thread work**:
| Operation | Current | Offloadable? | Savings |
|-----------|---------|-------------|---------|
| Noise computation | Main thread | ✅ Yes | ~2-3ms per chunk |
| Dimension folder cleanup | Main thread | ✅ Yes | 10-100ms per cleanup |
| NBT serialization (SavedData) | Main thread | ✅ Yes | 1-5ms per save |
| Chunk pre-gen status checking | Main thread | ⚠️ Partial | <1ms per chunk |
| Memory monitoring | Main thread | ✅ Yes (already stateless) | <0.1ms |
| Chunk ticket management | Main thread | ❌ No (modifies ChunkMap) | — |
| Block placement | Main thread | ❌ No (modifies LevelChunk) | — |
| Entity operations | Main thread | ❌ No (shared state) | — |

**Complexity**: LOW-MODERATE — straightforward `ExecutorService` + `ConcurrentLinkedQueue` for result delivery. Pattern already exists in `TeleportHandler`'s cleanup executor.

**Risk**: LOW — each worker operates on independent data. Results applied on main thread via `server.tell(TickTask)`.

**Benefit**: MODERATE — eliminates the heaviest non-gameplay costs from the tick loop. Combined with time-budgeted pre-gen, this could reduce pre-gen's TPS impact to near zero while maintaining or improving throughput.

**Compatibility**: HIGH — no changes to how game state is accessed. Workers only compute; main thread mutates.

---

## 5. Risk/Benefit Matrix

| Option | Risk | Benefit | Complexity | Mod Compat | Recommendation |
|--------|------|---------|------------|------------|---------------|
| **A: Async Pre-Gen** | LOW | MODERATE | MODERATE | HIGH | ✅ **v1 target** |
| **B: Dim Threading** | VERY HIGH | HIGH (theoretical) | EXTREME | VERY LOW | ❌ Do not pursue |
| **C: Worker Pool** | LOW | MODERATE | LOW-MODERATE | HIGH | ✅ **v2 enhancement** |
| **Current (main-thread)** | NONE | BASELINE | NONE | FULL | ✅ **Already implemented** |

---

## 6. Known Thread-Safety Hazards

### 6.1 ThreadLocal Leak in ExplorationSeedManager

**Risk**: MEDIUM  
**Component**: `ExplorationSeedManager.CURRENT_DIMENSION`

The ThreadLocal is set before `ServerLevel` construction and cleared after. If an exception occurs during construction, the ThreadLocal retains a stale dimension key until:
- Next tick's scheduled cleanup fires, OR
- `clearAll()` on server shutdown

**Current mitigation**: Dual cleanup (immediate + scheduled clear).  
**Recommended fix**: Wrap in try-finally:
```java
ExplorationSeedManager.setCurrentDimension(dimKey);
try {
    // create ServerLevel
} finally {
    ExplorationSeedManager.clearCurrentDimension();
}
```

### 6.2 Registry Reflection Fragility

**Risk**: MEDIUM  
**Component**: `MixinMappedRegistry`

Registry field discovery uses reflection to find `byKey`, `byValue`, `byLocation` maps. This breaks silently if NeoForge changes internal field names or types.

**Current mitigation**: 3-strategy fallback (name → type → heuristic), CRITICAL-level logging on failure.  
**Recommended action**: Pin to NeoForge version. Add startup validation test. Consider Access Transformers as alternative to reflection where possible.

### 6.3 BorderChangeListener ArrayList

**Risk**: LOW  
**Component**: `MixinMinecraftServer.pw$borderListeners`

`ArrayList` used for append-only storage of `BorderChangeListener` instances. Not thread-safe, but currently only accessed from the main thread during dimension creation.

**Recommended action**: Replace with `CopyOnWriteArrayList` for defensive safety, or document the single-thread constraint.

### 6.4 GenerationProgress Fields

**Risk**: LOW  
**Component**: `GenerationTask` → `GenerationProgress`

Progress counters (`chunksGenerated`, `status`) are not volatile or atomic. Safe only because they're accessed from the main thread.

**Recommended action**: If Option C is implemented, these must become `AtomicLong`/`volatile` (the reference BrecherDimensions mod already uses `AtomicLong` and `volatile` for these fields — our implementation should match).

### 6.5 ScheduledExecutorService Shutdown

**Risk**: LOW  
**Component**: `TeleportHandler.CLEANUP_EXECUTOR`

The executor is a static `ScheduledExecutorService` with daemon threads. If the mod is loaded/unloaded multiple times (hot-reload in dev), multiple executors accumulate.

**Recommended action**: Shut down the executor on server stop event. Already partially handled by daemon thread flag (JVM shutdown cleans them), but explicit shutdown is cleaner.

---

## 7. Recommendation & Implementation Plan

### Phase 1: Harden Current Threading (Low Effort, High Value)

**Goal**: Fix the known hazards in the current single-threaded model.

**Tasks**:
1. Wrap `ExplorationSeedManager` ThreadLocal in try-finally in `DimensionFactory`
2. Add startup validation for registry reflection in `MixinMappedRegistry`
3. Replace `pw$borderListeners` ArrayList with `CopyOnWriteArrayList`
4. Make `GenerationProgress` fields volatile/atomic to match BrecherDimensions pattern
5. Add explicit `CLEANUP_EXECUTOR.shutdown()` on server stop

**Effort**: ~2-4 hours  
**Risk**: NONE — purely defensive improvements

### Phase 2: Async Chunk Pre-Generation (Option A)

**Goal**: Move noise computation off the main thread for faster pre-gen without TPS impact.

**Architecture**:
```
ChunkPreGenerator (main thread)
  │
  ├── Submits ChunkPos to ComputePool
  │     └── ComputePool (2-4 threads)
  │           └── Runs noise/heightmap computation
  │           └── Returns NoiseComputeResult
  │
  ├── Polls completed results (non-blocking)
  │     └── Applies block data to LevelChunk
  │     └── Triggers feature placement
  │     └── Marks chunk FULL
  │
  └── Manages ticket lifecycle
```

**Implementation steps**:
1. Create `AsyncNoiseComputer` — wraps `NoiseBasedChunkGenerator` computation stages
2. Create `NoiseComputeResult` — data class holding computed noise/heightmap/surface data
3. Modify `GenerationTask.processNextBatch()` — submit to pool instead of blocking
4. Add result polling to tick handler — apply completed results during tick budget
5. Implement backpressure — limit in-flight chunks to prevent memory exhaustion
6. Add graceful shutdown — drain queue on server stop

**Key constraints**:
- Worker count: `max(1, Runtime.getRuntime().availableProcessors() - 2)` (leave room for main + lighting threads)
- Max in-flight chunks: 32 (configurable, prevents OOM)
- Result application still time-budgeted (5ms per tick)
- Must handle worker failure gracefully (retry on main thread as fallback)

**Effort**: ~2-3 days  
**Risk**: LOW-MODERATE — noise computation is stateless; the challenge is coordinating with vanilla's chunk status pipeline

### Phase 3: Worker Thread Pool (Option C) — Future

**Goal**: Offload non-gameplay heavy operations to workers.

**Candidates**:
1. Dimension folder cleanup (file I/O) — already a natural fit
2. SavedData NBT serialization — compute NBT off-thread, write on main
3. Memory analysis — already stateless, trivial to offload
4. Old dimension data cleanup — file system scanning

**Effort**: ~1-2 days (after Phase 2 infrastructure exists)  
**Risk**: LOW — independent data, results applied on main thread

### What We Are NOT Doing

**Option B (Dimensional Threading)** is explicitly not recommended:
- The Minecraft engine is fundamentally single-threaded for world mutation
- Every mod in the ecosystem assumes single-threaded access
- The historical Dimensional Threading mod (1.16) was abandoned due to insurmountable compatibility issues
- Our mining dimensions have low entity/redstone load — the TPS isolation benefit is marginal
- The engineering cost (synchronization points, testing, debugging) vastly exceeds the benefit

---

## 8. Heavy Modpack Scenario: Create + Northstar + 3 Mining Dimensions

### Scenario

A Create: Northstar (or similar heavy modpack) server running Parallel Worlds with:
- Vanilla Overworld, Nether, End
- Create modpack dimensions (e.g. Northstar custom dims, if any)
- 3 Parallel Worlds mining dimensions (`pw_overworld_0`, `pw_nether_0`, `pw_end_0`)
- 10-20+ players, many with active Create contraptions

### Where the TPS Budget Goes

A 20 TPS server has **50ms per tick**. In a heavy Create modpack:

| Component | Typical Cost | Notes |
|-----------|-------------|-------|
| Overworld tick | 10-25 ms | Create contraptions, entity ticking, redstone |
| Nether tick | 3-8 ms | Typically lighter |
| End tick | 1-3 ms | Rarely populated |
| Modded dimensions | 2-10 ms | Depends on mod |
| Network / player I/O | 2-5 ms | Packet processing |
| **Remaining for mining dims** | **~5-25 ms** | **Tight** |

**Each mining dimension with 1+ player**: ~2-5 ms/tick for chunk loading, entity ticking, random ticks.  
**3 mining dimensions × 3 ms average**: ~9 ms/tick just for mining dimensions existing.

### Performance Hardening Recommendations

#### 1. Use `preventDiskSaves = true` (HIGH impact)
Mining dimensions are temporary — saving chunks to disk is wasted I/O. This eliminates the chunk serialization cost and disk write pressure that competes with Create's own heavy I/O.

```toml
[performance]
preventDiskSaves = true
```

#### 2. Use `aggressiveChunkUnloading = true` (HIGH impact)
When a player leaves a mining dimension, unload all chunks immediately. Create contraptions won't be running in mining dimensions, so there's no reason to keep chunks loaded.

```toml
[performance]
aggressiveChunkUnloading = true
chunkUnloadDelay = 60  # 3 seconds instead of default 15
```

#### 3. Lower `maxChunksPerPlayer` (MODERATE impact)
Default 25 chunks per player is generous. For mining dimensions where players are just digging, 12-16 is sufficient and reduces the per-player chunk load.

```toml
[performance]
maxChunksPerPlayer = 12
```

#### 4. Keep `pregenEnabled = false` unless off-hours (MODERATE impact)
Pre-gen competes directly with Create contraption ticking for main-thread time. On a busy Create server, pre-gen should only run during off-peak hours or be left disabled entirely — chunks generate on-demand when players enter.

```toml
[performance]
pregenEnabled = false
pregenChunksPerTick = 1    # if enabled, be very conservative
pregenMaxTickMs = 3        # tighter budget for heavy modpacks
```

#### 5. Use `REGENERATE_EACH_RESTART` persistence mode (LOW-MODERATE impact)
Avoids accumulating world data from old mining dimensions. Less disk usage, faster startups.

```toml
[persistence]
persistenceMode = REGENERATE_EACH_RESTART
oldDimensionRetentionCount = 0
```

#### 6. Set a world border (LOW impact)
Limits how far players can explore in mining dimensions, capping the maximum chunk count.

```toml
[general]
explorationBorder = 5000  # 5000 blocks = ~312 chunks diameter
```

### Per-Dimension Cost Summary (Heavy Modpack)

| Mining Dim State | Per-Tick Cost | Notes |
|---|---|---|
| Exists, 0 players, chunks unloaded | ~0.05 ms | Nearly free |
| 1 player, 12 chunks loaded | ~2-3 ms | Chunk ticking, random ticks |
| 1 player, 25 chunks loaded | ~3-5 ms | More random/block ticks |
| 3 players, 60 chunks loaded | ~6-10 ms | Significant |
| Pre-gen active (1 chunk/tick) | +2-4 ms | Single chunk is already heavy |

### Worst Case: 3 Mining Dims + Create Overworld

```
Overworld (Create contraptions): 20 ms
Nether:                            5 ms
End:                               2 ms
pw_overworld_0 (2 players):        5 ms
pw_nether_0 (1 player):           3 ms
pw_end_0 (1 player):              3 ms
Network + overhead:                3 ms
──────────────────────────────────────
Total:                           ~41 ms  (≈ 19.5 TPS — tight but OK)
```

Add pre-gen to that and it tips over. **Pre-gen should be off during peak hours on heavy modpacks.**

### Global Time Budget Fix (Implemented)

Previously, each `GenerationTask` independently consumed up to `pregenMaxTickMs` per tick. With 3 active pre-gen tasks at 5ms each, that was **15ms** eaten by pre-gen alone — enough to drop a Create server below 18 TPS.

**Fix**: `ChunkPreGenerator.tick()` now enforces a single global time budget. All active pre-gen tasks share the configured `pregenMaxTickMs` limit. If task A takes 3ms of a 5ms budget, task B gets only 2ms, and task C gets skipped until next tick.

```java
// ChunkPreGenerator.tick() — global budget enforcement
long globalStartNanos = System.nanoTime();
long globalBudgetNanos = PWConfig.getPregenMaxTickMs() * 1_000_000L;

for (GenerationTask task : activeTasks) {
    long remaining = globalBudgetNanos - (System.nanoTime() - globalStartNanos);
    if (remaining <= 0) break;  // Budget exhausted, remaining tasks wait
    task.processNextBatch(level, remaining);
}
```

This ensures that regardless of how many dimensions are pre-generating, the total time cost is bounded by one `pregenMaxTickMs` value (default 5ms).

---

## Appendix: Code Audit Results

### Concurrency Construct Inventory

| Construct | Count | Locations |
|-----------|-------|-----------|
| `ConcurrentHashMap` | 8+ | DimensionManager, DimensionRegistrar, TeleportHandler, ChunkPreGenerator, InventoryKeeper, MixinMinecraftServer |
| `ConcurrentLinkedQueue` | 1 | GenerationTask (ticket removal scheduling) |
| `ThreadLocal` | 1 | ExplorationSeedManager (dimension context) |
| `ScheduledExecutorService` | 1 | TeleportHandler (cooldown cleanup) |
| `ReentrantReadWriteLock` | 1 | MixinMappedRegistry (registry mutation) |
| `volatile` | 1 | MixinMappedRegistry (unfrozen flag) |
| `AtomicLong` / `AtomicInteger` | 0 | (Should add to GenerationProgress) |
| `CompletableFuture` | 0 | (Candidate for Phase 2) |
| `ForkJoinPool` | 0 | Not used |
| `synchronized` | 0 | Not used (concurrent collections preferred) |

### Thread Model Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    JVM Process                               │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Main Server Thread                       │   │
│  │                                                       │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐ │   │
│  │  │ Tick Loop    │  │ Chunk PreGen│  │ Dim Creation │ │   │
│  │  │ (all dims)   │  │ (5ms budget)│  │ (startup)    │ │   │
│  │  └─────────────┘  └─────────────┘  └──────────────┘ │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌────────────────────┐  ┌────────────────────────────┐     │
│  │ Lighting Thread     │  │ Teleport Cleanup (daemon)  │     │
│  │ (vanilla)           │  │ (5-min interval)           │     │
│  └────────────────────┘  └────────────────────────────┘     │
│                                                              │
│  ┌────────────────────────────────────────────────────┐     │
│  │ [PROPOSED] Async Pre-Gen Worker Pool (2-4 threads) │     │
│  │ - Noise computation only                           │     │
│  │ - Results queued back to main thread               │     │
│  └────────────────────────────────────────────────────┘     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### BrecherDimensions Comparison

| Aspect | BrecherDimensions | Parallel Worlds | Notes |
|--------|------------------|-----------------|-------|
| Core model | Single-threaded | Single-threaded | Identical |
| ConcurrentHashMap | ✅ (6+) | ✅ (8+) | PW slightly more defensive |
| ThreadLocal seed | ✅ (fragile) | ✅ (fragile) | Both need try-finally fix |
| Background cleanup | ✅ (1 daemon) | ✅ (1 daemon) | Identical pattern |
| Async chunk gen | ❌ | ❌ | Neither implemented |
| AtomicLong counters | ✅ | ❌ | PW should adopt |
| volatile status | ✅ | ❌ | PW should adopt |
| Registry lock | ✅ (RWLock) | ✅ (RWLock) | Identical pattern |
| CompletableFuture | ❌ (client I/O only) | ❌ | Candidate for Phase 2 |

---

## Conclusion

The current single-threaded architecture is **production-safe** and **correct**. The TPS-aware throttling and memory monitoring provide adequate protection against performance degradation.

The primary opportunity for improvement is in **chunk pre-generation throughput** — moving noise computation to worker threads (Option A) could roughly double pre-gen speed without impacting server TPS. This is the recommended next step when performance optimization becomes a priority.

Full dimensional threading (Option B) is a research dead-end for our use case — the engineering cost is not justified, the ecosystem compatibility is unacceptable, and our temporary mining dimensions don't generate enough per-dimension load to benefit from TPS isolation.

The existing defensive concurrency patterns (ConcurrentHashMap everywhere, ReadWriteLock for registry, daemon cleanup thread) provide a solid foundation for future async work. The immediate priorities should be hardening the ThreadLocal pattern and adopting atomic/volatile fields for cross-thread-visible state.
