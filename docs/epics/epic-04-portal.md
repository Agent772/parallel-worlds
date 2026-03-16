# Sub-Agent Instructions: Epic 4 — Custom Portal System

## Objective
Build a custom portal frame block and portal mechanic that transports players to the mining dimension. Uses a rectangular frame (like Nether portals) with configurable frame blocks.

## Context
- **Depends on**: Epic 3 (TeleportHandler exists)
- **Frame block**: registered custom block `pw_portal_frame`
- **Portal block**: registered custom block `pw_portal` (the filled-in center)
- **Activation**: right-click frame with flint_and_steel (or configurable igniter)

## Tasks

### 1. Block & Item Registration
`src/main/java/com/agent772/parallelworlds/registry/PWBlocks.java`:
- `DeferredRegister.Blocks`
- `PW_PORTAL_FRAME` — simple stone-like block, `BlockBehaviour.Properties.of().strength(5.0f, 6.0f).requiresCorrectToolForDrops()`
- `PW_PORTAL` — non-solid, no collision, translucent rendering, particle spawning, not obtainable

`src/main/java/com/agent772/parallelworlds/registry/PWItems.java`:
- `DeferredRegister.Items`
- `PW_PORTAL_FRAME` block item
- Register into creative tab (Functional Blocks or custom tab)

### 2. Portal Shape Detection
`src/main/java/com/agent772/parallelworlds/portal/PortalShape.java`:
- `findPortalShape(Level level, BlockPos clickedPos, Direction.Axis axis) → Optional<PortalShape>`
- Detect rectangular frame: walk outward from clicked position to find edges
- Validate: all frame positions must be `PW_PORTAL_FRAME` (or block from config `portalFrameBlock`)
- Minimum inner size: 2×3 (like Nether portal)
- Maximum inner size: 21×21 (configurable)
- Orientation: X-axis or Z-axis (not horizontal — vertical portals only)
- Store: `bottomLeft`, `width`, `height`, `axis`
- `getInnerPositions() → List<BlockPos>` — all positions that should become portal blocks
- `isValid()` — verify complete frame with no gaps

### 3. Portal Block
`src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java` (extends Block):
- Non-solid, no collision box, translucent
- `entityInside(BlockState, Level, BlockPos, Entity)`:
  - If player and server side: start portal timer
  - After configurable delay (default 80 ticks, like nether portal): call `TeleportHandler.teleportToExploration()`
  - In mining dimension: call `TeleportHandler.returnFromExploration()`
- `neighborChanged(...)`: if any neighbor is no longer a valid portal shape → destroy portal (replace self with air)
- `onRemove(...)`: cascade removal of connected portal blocks
- Spawn particles (`ParticleTypes.PORTAL` or custom) in `animateTick`
- No drops (not obtainable)

### 4. Portal Activation
`src/main/java/com/agent772/parallelworlds/portal/PortalActivation.java`:
- Hook into `PlayerInteractEvent.RightClickBlock`:
  - Check: player right-clicks a frame block with flint_and_steel (or configurable igniter)
  - Check: `PWConfig.isPortalEnabled()` — bail if disabled
  - Detect orientation (try both X and Z axes)
  - Call `PortalShape.findPortalShape()` for the clicked position
  - If valid shape: fill inner positions with `PW_PORTAL` blocks, play sound, consume durability
  - If invalid: do nothing (let vanilla handle)

### 5. Portal Destruction
- When any frame block is broken: scan adjacent portal blocks → if shape invalid → remove all portal blocks
- Portal block neighborhood check: on any block update, verify shape still valid

### 6. Data Generation
`src/main/java/com/agent772/parallelworlds/datagen/PWBlockStateProvider.java`:
- Block states for portal frame (simple cube model) and portal block (flat plane model like nether portal)

`src/main/java/com/agent772/parallelworlds/datagen/PWRecipeProvider.java`:
- Crafting recipe for portal frame: e.g., obsidian ring with eye of ender center, or configurable

`src/main/java/com/agent772/parallelworlds/datagen/PWLootTableProvider.java`:
- Portal frame drops itself
- Portal block drops nothing

### 7. Assets
- `src/main/resources/assets/parallelworlds/textures/block/pw_portal_frame.png` — 16×16 texture
- `src/main/resources/assets/parallelworlds/textures/block/pw_portal.png` — animated portal texture
- `src/main/resources/assets/parallelworlds/blockstates/pw_portal_frame.json`
- `src/main/resources/assets/parallelworlds/blockstates/pw_portal.json`
- `src/main/resources/assets/parallelworlds/models/block/pw_portal_frame.json`
- `src/main/resources/assets/parallelworlds/lang/en_us.json`

## Verification
- Frame blocks craftable and placeable
- Right-click frame with flint_and_steel → portal fills in
- Invalid shapes (incomplete frame, wrong blocks) → no activation
- Standing in portal → teleport after delay
- Breaking frame block → portal collapses
- Portal works bidirectionally
- `portalEnabled=false` → flint_and_steel doesn't activate
- GameTest: `PortalShapeTest` — build shapes, activate, verify fill, destroy, verify removal

## Do NOT
- Implement portal block entity (not needed for v1)
- Create custom particle effects (use vanilla portal particles)
- Add NBT/custom data to portal blocks
