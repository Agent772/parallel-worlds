# Sub-Agent Instructions: Epic 11 — Threading Research Spike

## Objective
Research the feasibility of running mining dimensions on a separate thread for better server performance. This is a research spike — produce a document with findings and recommendation, optionally a prototype.

## Context
- **Independent**: can run in parallel with other epics after Epic 2
- **Output**: `docs/threading-research.md` in the repo
- **User preference**: "research first, decide later"

## Research Tasks

### 1. Minecraft's Thread Safety Model
- Document which ServerLevel operations are thread-safe
- Identify locks/synchronization in chunk loading (ChunkMap, ChunkHolder, DistanceManager)
- Map the server tick loop: what happens in what order
- Identify shared state between dimensions (player data, scoreboards, advancements, recipes)

### 2. Existing Approaches
- **C2ME** (Concurrent Chunk Management Engine — Fabric):
  - How does it parallelize chunk generation?
  - What synchronization does it add?
  - What broke / what works?
- **Dimensional Threading** (1.16 mod):
  - How did it run dims on separate threads?
  - What compatibility issues arose?
  - Why was it discontinued?
- **Vanilla async chunk loading** (since 1.14+):
  - What's already async in vanilla?
  - How does CompletableFuture<ChunkAccess> work?

### 3. Option Analysis

**Option A: Async chunk generation only (RECOMMENDED for v1)**
- Chunk generation already has async components in vanilla
- Pre-gen can run computations off-thread, apply results on main thread
- Risk: LOW — well-understood pattern
- Benefit: MODERATE — faster pre-gen without TPS impact

**Option B: Separate tick thread for mining dimension**
- Each dimension ticks on own thread
- Requires synchronization for: player teleport, cross-dim interactions, entity tracking, world save
- Risk: HIGH — entity ticking, block updates, redstone all assume single-thread
- Benefit: HIGH — full TPS isolation

**Option C: Worker thread pool for heavy operations**
- Main thread ticks normally
- Heavy operations (cleanup, pre-gen computation, lighting) run on workers
- Results queued back to main thread
- Risk: LOW-MODERATE
- Benefit: MODERATE

### 4. Prototype (if time permits)
- Create `ThreadedDimensionTicker.java` as proof-of-concept
- Run a simple mining dim tick on separate thread
- Identify first 5 thread-safety violations encountered
- Document each with stack trace and proposed fix

### 5. Deliverable
Write `docs/threading-research.md` with:
- Findings per research area
- Risk/benefit matrix for each option
- Recommendation with rationale
- Implementation plan if proceeding
- List of known thread-safety hazards

## Do NOT
- Commit to a threading approach without user approval
- Modify existing game code for threading
- Create production-ready threading implementation (this is research only)
