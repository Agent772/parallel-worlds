# Sub-Agent Instructions: Epic 7 — Performance & Chunk Management

## Objective
Implement optimized chunk lifecycle, memory monitoring, and background chunk pre-generation with TPS-aware throttling.

## Context
- **Depends on**: Epic 2 (dimensions exist)
- **Critical constraints**: NEVER dip TPS below 18, respect memory limits, clean ticket management
- **Key pattern**: TPS throttling bands — full speed ≥19.5, reduced ≥19.0, minimum ≥18.0, pause <18.0

## Tasks

### 1. ChunkManager
`src/main/java/com/agent772/parallelworlds/performance/ChunkManager.java`:
- `configureForExploration(ServerLevel level)`:
  - Set custom chunk unload delay from config
  - Configure max loaded chunks per player
- `forceUnloadAllChunks(ServerLevel level)`:
  - Remove all tickets
  - Force save if not preventDiskSaves
  - Called during dimension teardown
- `performCleanup(MinecraftServer server)`:
  - Run periodically (configurable interval)
  - Unload chunks in empty exploration dims (no players present)
  - If `aggressiveChunkUnloading`: unload ALL non-entity-loaded chunks

### 2. MemoryMonitor
`src/main/java/com/agent772/parallelworlds/performance/MemoryMonitor.java`:
- `isMemoryPressureHigh() → boolean`: `Runtime.getRuntime().totalMemory() - freeMemory() > maxMemory() * threshold`
  - Use `maxMemory()` as denominator (not totalMemory) for consistent readings
  - Threshold from config (default 0.85)
- `getMemoryUsagePercent() → double`
- `logMemoryUsage(String context)` — debug log with MB values

### 3. SpiralIterator
`src/main/java/com/agent772/parallelworlds/generation/SpiralIterator.java`:
- Generates chunk positions spiraling outward from center
- **State**: `currentX`, `currentZ`, `dx`, `dz`, `stepsInCurrentDirection`, `stepsInCurrentSide`, `sidesCompleted`
- Start at center, first direction: North (dz=-1), clockwise rotation: N→E→S→W
- `hasNext() → boolean`, `next() → ChunkPos`
- `getTotalChunks() → long` = `(2*radius+1)^2`
- **Resumable**: all state is serializable to NBT
- `toNbt() / fromNbt()` for persistence

### 4. GenerationProgress
`src/main/java/com/agent772/parallelworlds/generation/GenerationProgress.java`:
- Thread-safe: `AtomicLong chunksGenerated`, `AtomicLong chunksActuallyGenerated`
- `ConcurrentHashMap<ChunkPos, Integer> failedChunkRetries`
- `volatile Status status` (RUNNING, PAUSED, COMPLETED, ERROR)
- `SpiralIterator spiralIterator` — resumable
- `getProgressPercent() → double`, `getGenerationRate() → double` (chunks/min), `getEstimatedMinutesRemaining() → double`
- `toNbt() / fromNbt()` — store iterator state, failed chunks as "x,z"→retryCount

### 5. GenerationTask
`src/main/java/com/agent772/parallelworlds/generation/GenerationTask.java`:
- Per-dimension task running on main server thread
- Constructor: `(ResourceKey<Level> dimensionKey, int radius, ChunkPos center)`

`processNextBatch(ServerLevel level)`:
1. **Process ticket removals first** (from scheduled queue) — even when throttled
2. Check completion status
3. **TPS throttling**:
   - Get current TPS from `server.getAverageTickTimeNanos()`
   - ≥19.5 TPS → `chunksPerTick` from config
   - ≥19.0 TPS → `chunksPerTick - 1`
   - ≥18.0 TPS → 1 chunk 
   - <18.0 TPS → skip entirely this tick
4. **Memory check**: if `MemoryMonitor.isMemoryPressureHigh()` → skip
5. **Time budget loop** (max `pregenMaxTickMs`):
   - Get next chunk from SpiralIterator
   - Add `PREGEN_TICKET` (TicketType.create("pw_pregen", ...)) BEFORE loading
   - Load chunk to `ChunkStatus.EMPTY` to check existing status
   - If `chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL)` → skip (already generated)
   - Otherwise count as newly generated
   - Schedule ticket removal (configurable ticks later) in finally block
   - On failure: track in failedChunkRetries, retry up to 3 times
6. **Logging**: every N chunks (config), log progress %

`isComplete() → boolean`, `getProgress() → GenerationProgress`

**Ticket management:**
- `static TicketType<ChunkPos> PREGEN_TICKET = TicketType.create("pw_pregen", Comparator.comparingLong(ChunkPos::toLong), 300)`
- Add ticket BEFORE chunk load
- Schedule removal via `ConcurrentLinkedQueue<ScheduledRemoval>` (pos + tick timestamp)
- Process removals at START of each batch (before new work)

### 6. ChunkPreGenerator
`src/main/java/com/agent772/parallelworlds/generation/ChunkPreGenerator.java`:
- `Map<ResourceKey<Level>, GenerationTask> activeTasks`
- `startGeneration(MinecraftServer server, ResourceKey<Level> dim, int radius) → String feedback`
- `stopGeneration(ResourceKey<Level> dim)`, `pauseGeneration(dim)`, `resumeGeneration(dim)`
- `stopAll()`
- `tick(MinecraftServer server)` — called every server tick, iterates active tasks
- `resumeSavedTasks(MinecraftServer server)` — load from PWSavedData, recreate GenerationTasks
- `getStatus(ResourceKey<Level>) → String` — formatted progress with ETA
- `hasActiveTasks() → boolean`
- `shutdown(MinecraftServer server)` — save all progress, cancel all tasks

## Verification
- Pre-gen runs: chunks actually generated (verify via chunk file existence)
- TPS stays ≥18 during pre-gen (monitor with `/tick` or similar)
- Pre-gen pauses when memory exceeds threshold
- Pre-gen resumes from saved progress after restart
- Spiral iterator visits every chunk in radius (total count = (2r+1)^2)
- No ticket leaks: loaded chunks don't stay loaded indefinitely
- `status` shows accurate progress %, rate, ETA
- Failed chunks retried (max 3), then skipped
- GameTest: `PreGenTest` — verify spiral coverage, throttle behavior, ticket cleanup

## Do NOT
- Implement commands (Epic 8)
- Run pre-gen on a separate thread (main thread with time budget only)
- Modify vanilla chunk loading behavior beyond ticket management
