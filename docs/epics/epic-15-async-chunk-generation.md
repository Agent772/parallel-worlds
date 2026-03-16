# Sub-Agent Instructions: Epic 15 — Async Chunk Generation

## Objective
Move chunk noise/heightmap computation off the main thread into a worker pool. The main thread submits chunk coordinates, workers compute terrain data, and the main thread applies the results to the world. This speeds up both pre-generation and on-demand chunk loading in exploration dimensions without impacting server TPS.

## Context
- **Depends on**: Epic 7 (ChunkPreGenerator, GenerationTask exist), Epic 2 (dimensions exist)
- **Research**: Epic 11 Threading Research Spike — "Option A: Async Chunk Pre-Generation" recommended
- **Key principle**: Workers compute, main thread mutates. Never touch `ServerLevel`, `LevelChunk`, or any entity from a worker thread.
- **Platform**: NeoForge 1.21.1, Java 21

## Why This Works

Chunk generation has two phases:
1. **Computation** (noise sampling, heightmap, surface rules, biome blending) — pure math, reads config/registry data that doesn't change, produces a data structure describing what blocks go where. **Thread-safe.**
2. **Application** (placing blocks into `LevelChunk`, spawning entities, placing features like trees/ores, lighting) — mutates world state, reads neighbor chunks. **NOT thread-safe.**

We run phase 1 on worker threads and phase 2 on the main thread. This is the same pattern vanilla uses internally for its `ThreadedLevelLightEngine` (lighting runs on its own thread, results applied on main).

---

## Tasks

### 1. AsyncChunkWorkerPool
`src/main/java/com/agent772/parallelworlds/generation/async/AsyncChunkWorkerPool.java`

Thread pool that runs noise computation jobs.

```java
public class AsyncChunkWorkerPool {
    private final ExecutorService executor;
    private final int maxInFlight;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    
    // Creates pool with (availableProcessors - 2) threads, min 1
    public AsyncChunkWorkerPool(int maxInFlight);
    
    // Submit a chunk for async computation. Returns false if at capacity.
    public boolean submit(ChunkComputeRequest request);
    
    // Poll completed results (non-blocking). Returns empty list if none ready.
    public List<ChunkComputeResult> pollResults();
    
    // Drain all pending work, block until complete. Called on shutdown.
    public void shutdown(long timeoutMs);
    
    // How many chunks are currently being computed
    public int getInFlightCount();
    
    // Is the pool at capacity?
    public boolean isFull();
}
```

**Implementation details:**
- Use `ExecutorService` with `ThreadFactory` naming threads `"PW-ChunkGen-N"` as daemon threads
- Thread count: `Math.max(1, Runtime.getRuntime().availableProcessors() - 2)` — leave room for main thread + vanilla lighting thread
- `maxInFlight` (configurable, default 32) — prevents unbounded memory usage from queued results
- Track in-flight count with `AtomicInteger`, decrement when result is produced
- Results collected in a `ConcurrentLinkedQueue<ChunkComputeResult>` polled by main thread
- On shutdown: `executor.shutdown()` → `executor.awaitTermination(timeoutMs)` → `executor.shutdownNow()`

### 2. ChunkComputeRequest
`src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeRequest.java`

Immutable data class describing what to compute.

```java
public record ChunkComputeRequest(
    ResourceKey<Level> dimension,
    ChunkPos chunkPos,
    long seed,
    // Snapshot of everything the worker needs — no live references to ServerLevel
    RegistryAccess registryAccess,       // frozen, read-only — safe to share
    NoiseGeneratorSettings noiseSettings,
    BiomeSource biomeSource,
    RandomState randomState              // see note below
) {}
```

**Critical**: `RandomState` contains `PositionalRandomFactory` which is **stateless per-position** (it generates randoms from position, not internal state). This is safe to share read-only across threads. However, workers must NOT call any method that mutates it. Use `RandomState.create()` to make a per-worker copy if needed.

**Registry safety**: `RegistryAccess` is frozen after server startup. Reading frozen registries is thread-safe. We only read biome data, noise parameters, density functions — all immutable after startup.

### 3. ChunkComputeResult
`src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeResult.java`

Result data produced by a worker. Applied to the world on the main thread.

```java
public record ChunkComputeResult(
    ResourceKey<Level> dimension,
    ChunkPos chunkPos,
    boolean success,
    @Nullable String error,
    
    // Computed terrain data — flat arrays matching chunk section layout
    // These are the raw block states and biomes computed by noise gen
    @Nullable PalettedContainer<BlockState>[] sections,
    @Nullable Heightmap.Types[] heightmapTypes,
    @Nullable long[][] heightmapData,
    
    long computeTimeNanos  // for performance metrics
) {}
```

**Why flat arrays, not a ChunkAccess**: `LevelChunk` and `ProtoChunk` hold references to `ServerLevel` and aren't safe to create off-thread. We extract only the computed data and let the main thread build the actual chunk objects.

### 4. ChunkComputeWorker
`src/main/java/com/agent772/parallelworlds/generation/async/ChunkComputeWorker.java`

The actual computation logic run on worker threads.

```java
public class ChunkComputeWorker implements Callable<ChunkComputeResult> {
    private final ChunkComputeRequest request;
    
    public ChunkComputeResult call() {
        // 1. Create a NoiseChunk for this position
        //    NoiseChunk.forChunk() uses NoiseGeneratorSettings + BiomeSource + RandomState
        //    All are read-only — this is the expensive step (~2-4ms per chunk)
        
        // 2. Sample noise at every block position in the chunk
        //    Produces density values → block states via surfaceRules
        
        // 3. Compute heightmaps from the generated blocks
        
        // 4. Package into ChunkComputeResult
        //    On any exception: return result with success=false and error message
    }
}
```

**What this replaces**: Currently `level.getChunk(x, z, ChunkStatus.FULL, true)` does noise + surface + features + lighting all synchronously. We split out the noise + surface part.

**What this does NOT do**:
- No feature placement (trees, ores, flowers) — these read neighbor chunks
- No entity spawning — touches ServerLevel
- No lighting — vanilla already has its own light thread
- No structure generation — reads/writes cross-chunk data
- No block entity creation — requires Level reference

### 5. Modify GenerationTask — Async Mode
`src/main/java/com/agent772/parallelworlds/generation/GenerationTask.java`

Add an async path alongside the existing synchronous path.

```java
public void processNextBatch(ServerLevel level, long budgetNanos) {
    processTicketRemovals(level, currentTick);
    
    if (paused || completed) return;
    
    // === PHASE 1: Apply completed async results (main thread) ===
    applyCompletedResults(level, budgetNanos);
    
    // === PHASE 2: Submit new chunks to worker pool ===
    if (!workerPool.isFull() && iterator.hasNext()) {
        submitNextChunks(level);
    }
    
    // === PHASE 3: Fallback — if async disabled or pool unavailable, sync path ===
    if (!asyncEnabled) {
        processNextBatchSync(level, budgetNanos);  // current behavior
    }
}

private void applyCompletedResults(ServerLevel level, long budgetNanos) {
    long start = System.nanoTime();
    List<ChunkComputeResult> results = workerPool.pollResults();
    
    for (ChunkComputeResult result : results) {
        if (System.nanoTime() - start >= budgetNanos) {
            // Re-queue unapplied results for next tick
            deferredResults.addAll(results.subList(i, results.size()));
            break;
        }
        
        if (result.success()) {
            applyToWorld(level, result);  // Place blocks, mark FULL
        } else {
            handleFailure(result);
        }
    }
}
```

**Key behaviors:**
- Async results are time-budgeted when applying (same global budget from `ChunkPreGenerator`)
- If more results arrive than we can apply in one tick, they queue for the next tick
- The sync fallback remains for when async is disabled via config
- TPS throttling still applies — controls how many new submissions per tick
- Memory pressure still pauses submissions (but lets in-flight work complete)

### 6. Async On-Demand Chunk Acceleration
`src/main/java/com/agent772/parallelworlds/generation/async/AsyncChunkHint.java`

**Optional**: When a player moves in an exploration dimension, predict which chunks they'll need and pre-submit them to the worker pool.

```java
public class AsyncChunkHint {
    // Called from PWEventHandlers when player moves in exploration dim
    public static void onPlayerMove(ServerPlayer player, ServerLevel level) {
        if (!PWConfig.isAsyncChunkHintsEnabled()) return;
        if (!DimensionUtils.isExplorationDimension(level.dimension())) return;
        
        // Predict chunks in player's movement direction
        Vec3 motion = player.getDeltaMovement();
        ChunkPos current = player.chunkPosition();
        
        // Submit chunks in a cone ahead of the player (3-5 chunks)
        List<ChunkPos> predicted = predictChunks(current, motion, 3);
        
        for (ChunkPos pos : predicted) {
            if (!isChunkLoaded(level, pos)) {
                workerPool.submit(createRequest(level, pos));
            }
        }
    }
}
```

**This is speculative pre-computation only.** If the prediction is wrong, the result is simply never applied (or applied later when the chunk is actually needed). No wasted main-thread time — only worker time.

**Event hook**: `PlayerTickEvent.Post` — check every 10 ticks (~0.5s) if player has moved >8 blocks since last check. Low overhead.

### 7. Config Additions
Modify `PWConfigSpec.java` and `PWConfig.java`:

```toml
[performance]
# Enable async chunk computation for pre-generation and exploration
asyncChunkGenEnabled = true

# Maximum chunks in the async compute pipeline (memory limit)
asyncMaxInFlight = 32

# Worker thread count (-1 = auto: availableProcessors - 2, min 1)
asyncWorkerThreads = -1

# Enable predictive chunk hints when players move in exploration dims
asyncChunkHintsEnabled = true

# How many chunks to predict ahead of player movement
asyncHintDistance = 3
```

### 8. Lifecycle Integration
Modify `ParallelWorlds.java`:

```java
// In onServerStarting:
asyncWorkerPool = new AsyncChunkWorkerPool(PWConfig.getAsyncMaxInFlight());

// In onServerStopping:
asyncWorkerPool.shutdown(5000);  // 5s timeout

// Pass pool to ChunkPreGenerator and AsyncChunkHint
chunkPreGenerator.setWorkerPool(asyncWorkerPool);
AsyncChunkHint.setWorkerPool(asyncWorkerPool);
```

---

## Architecture Diagram

```
Player moves in       Admin runs
exploration dim       /pw pregen start
      │                     │
      ▼                     ▼
 AsyncChunkHint      ChunkPreGenerator
 (predict 3-5           (spiral
  chunks ahead)         iterator)
      │                     │
      └────────┬────────────┘
               ▼
     AsyncChunkWorkerPool
     ┌──────────────────┐
     │ Worker 1: noise  │
     │ Worker 2: noise  │  ← Pure math, no world access
     │ Worker 3: noise  │
     └────────┬─────────┘
              │ ConcurrentLinkedQueue<ChunkComputeResult>
              ▼
      Main Server Thread
      (tick budget: 5ms)
              │
              ├── Apply block data to LevelChunk
              ├── Trigger feature placement (trees, ores)
              ├── Schedule lighting
              └── Mark chunk FULL
```

---

## What is Thread-Safe (OK for workers)

| Operation | Safe? | Why |
|-----------|-------|-----|
| Read `NoiseGeneratorSettings` | ✅ | Immutable after startup |
| Read `BiomeSource` | ✅ | Immutable after startup |
| Call `DensityFunction.compute()` | ✅ | Pure function of position |
| Read frozen `Registry` | ✅ | Frozen = read-only |
| Create `NoiseChunk` | ✅ | Local object, no world refs |
| Sample noise at positions | ✅ | Deterministic from seed + pos |
| Compute heightmap from blocks | ✅ | Local array math |

## What is NOT Thread-Safe (main thread ONLY)

| Operation | Safe? | Why |
|-----------|-------|-----|
| `LevelChunk.setBlockState()` | ❌ | Mutates chunk section arrays |
| Feature placement | ❌ | Reads neighbor chunks |
| Structure placement | ❌ | Cross-chunk writes |
| Entity spawning | ❌ | Modifies entity lists |
| `ServerLevel.getChunk()` | ❌ | Triggers chunk loading pipeline |
| Ticket add/remove | ❌ | Modifies `DistanceManager` |
| `ProtoChunk` creation | ❌ | References `LevelHeightAccessor` from Level |

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Worker pool leaks on crash | Daemon threads auto-terminate. `shutdown()` called in `onServerStopping`. |
| OOM from too many queued results | `maxInFlight` cap (default 32). Pool rejects when full. |
| Stale results for unloaded dims | Check dimension still exists before applying. Discard stale results. |
| RandomState thread safety | Create per-worker copy via `RandomState.create()` if any mutation detected. |
| Wrong blocks placed (race) | Workers produce data arrays; main thread is sole writer to world. No race possible. |
| NeoForge version drift | `NoiseGeneratorSettings` API is stable since 1.18. Pin version, test on update. |
| Mod compat (custom generators) | Only applies to `NoiseBasedChunkGenerator`. Custom generators fall back to sync path. |
| Player prediction misses | No cost — unused results are discarded or applied lazily. |

---

## Performance Expectations

| Metric | Sync (Current) | Async (This Epic) |
|--------|---------------|-------------------|
| Pre-gen chunks/tick (20 TPS) | 1-2 (limited by 5ms budget) | 5-10 (workers prepare ahead) |
| Pre-gen TPS impact | 3-5 ms/tick | 1-2 ms/tick (apply only) |
| Player chunk load time | ~50-100ms per new chunk | ~20-40ms (pre-computed) |
| Memory overhead | Baseline | +10-30 MB (in-flight queue) |
| Thread count | 1 (main) | +2-4 workers |

**Net effect**: ~2x pre-gen throughput at ~50% less TPS cost. Player exploration in mining dimensions feels smoother because chunks ahead of them are already computed.

---

## Verification

- [ ] Async pre-gen produces identical terrain to sync pre-gen (same seed → same blocks)
- [ ] TPS stays ≥19.5 during async pre-gen (was ≥18 with sync)
- [ ] `maxInFlight` is respected — never more than N results queued
- [ ] Worker pool shuts down cleanly on server stop (no leaked threads)
- [ ] Dimension unload discards pending results for that dimension
- [ ] Config toggle `asyncChunkGenEnabled = false` falls back to sync cleanly
- [ ] Player chunk hints only fire in exploration dimensions
- [ ] Memory usage stays within configured threshold
- [ ] `./gradlew build` succeeds
- [ ] Works alongside Create mod (no interference with Create's chunk access)

## Do NOT
- Run feature placement (trees, ores) on worker threads
- Create `ProtoChunk` or `LevelChunk` objects on worker threads
- Access `ServerLevel` from worker threads
- Modify any vanilla chunk loading pipeline — this is an overlay, not a replacement
- Make this required — sync fallback must always work
- Use `ForkJoinPool.commonPool()` — Create and other mods may already saturate it
