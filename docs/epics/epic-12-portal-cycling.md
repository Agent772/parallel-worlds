# Sub-Agent Instructions: Epic 12 — Portal Dimension Cycling

## Objective
Allow a single portal to target any of the enabled exploration dimensions. Players right-click an active portal frame with a configurable item (default: ender eye) to cycle through available dimensions. A clear visual indicator shows which dimension the portal currently targets.

## Context
- **Depends on**: Epic 4 (portal system), Epic 2 (dimension registry)
- **Config**: `portalCycleItem` (default `minecraft:ender_eye`), `enabledDimensions` list
- **Current behavior**: Portal auto-targets the exploration copy of the dimension it's built in (1:1 mapping). This epic decouples that — any portal can target any enabled exploration dimension.

## Design

### Core Concept
Each portal instance (identified by its frame position) stores a **target index** into the ordered list of enabled exploration dimensions. Right-clicking cycles this index. The portal's appearance changes to indicate the current target.

### State Storage
Portals need persistent state (target dimension). Two approaches:

**Chosen: Position-keyed SavedData**
- Store target mappings in `PWSavedData` as `Map<BlockPos, ResourceLocation>` (portal bottom-left position → target dimension)
- No BlockEntity needed — keeps portal blocks simple
- On portal destruction, clean up the entry
- On server start, validate stored targets against current `enabledDimensions`

### Visual Indicator System
The portal must clearly communicate which dimension it currently targets. Implement **both** indicators:

1. **Tinted Portal Particles** — Configurable per dimension via hex color in config:
   - Config: `dimensionParticleColors` list of `"mod:dimension=#RRGGBB"` entries
   - Defaults: overworld `#33CC4D` (green), nether `#E64D19` (orange), end `#B233E6` (purple)
   - Dimensions not in the list get fallback cyan `#33AAEE`
   - Use `DustParticleOptions` with custom color parsed from hex instead of `ParticleTypes.PORTAL`
   - `DimensionColors.java` parses config on refresh and caches `Map<ResourceLocation, Vector3f>`

2. **Floating Dimension Name** — Display the target dimension name as floating text:
   - Render a translatable text component above the portal center (top block row)
   - Use `ServerPlayer.sendSystemMessage()` with action bar when player looks at or enters portal
   - Show format: `"§b[Parallel Overworld]§r"` / `"§c[Parallel Nether]§r"` / `"§5[Parallel End]§r"`

### Cycling Mechanic
- Player right-clicks **any portal block** (not frame) with the configured cycle item
- Target advances to next enabled dimension in the list (wraps around)
- All portal blocks in the frame update simultaneously (same target)
- Play a distinct cycling sound (`SoundEvents.UI_BUTTON_CLICK` or `SoundEvents.EXPERIENCE_ORB_PICKUP`)
- Show action bar message: `"Portal target: [Parallel Overworld]"` (with dimension-appropriate color)
- Cycle item is NOT consumed

### Dimension Naming Display
- Vanilla dimensions get friendly names: `"Parallel Overworld"`, `"Parallel Nether"`, `"Parallel End"`
- Modded dimensions: use the ResourceLocation path with capitalization: `"Parallel My Dimension"`
- All prefixed with "Parallel " to indicate these are exploration copies

## Tasks

### 1. Portal Target Manager
`src/main/java/com/agent772/parallelworlds/portal/PortalTargetManager.java`:
- Singleton with `Map<GlobalPos, ResourceLocation>` — maps portal position → target exploration dimension
  - `GlobalPos` includes the Level dimension key + BlockPos to support portals in different dimensions
- `getTarget(Level, BlockPos) → ResourceLocation` — returns current target (default: first enabled dimension)
- `cycleTarget(Level, BlockPos) → ResourceLocation` — advance to next, return new target
- `setTarget(Level, BlockPos, ResourceLocation)` — explicit set
- `removeTarget(Level, BlockPos)` — cleanup on portal destruction
- `getOrderedDimensions() → List<ResourceLocation>` — get all exploration dimension IDs in config order
- Canonical position: always use bottom-left of portal shape (from PortalShape detection)
- Persist via `PWSavedData` on changes

### 2. Update PWSavedData
`src/main/java/com/agent772/parallelworlds/data/PWSavedData.java`:
- Add `portalTargets: Map<String, ResourceLocation>` (serialized GlobalPos string → dimension RL)
- NBT serialization: `"PortalTargets"` compound tag
- `savePortalTarget(GlobalPos, ResourceLocation)`
- `getPortalTarget(GlobalPos) → Optional<ResourceLocation>`
- `removePortalTarget(GlobalPos)`
- `getAllPortalTargets() → Map<GlobalPos, ResourceLocation>`
- On load, validate targets still exist in current enabled dimensions; remove stale entries

### 3. Update PWPortalBlock
`src/main/java/com/agent772/parallelworlds/portal/PWPortalBlock.java`:
- Add `useWithoutItem()` or `useItemOn()` override for right-click cycle interaction:
  - Check: player holding configured cycle item
  - Find canonical portal position (scan connected portal blocks to find bottom-left)
  - Call `PortalTargetManager.cycleTarget()`
  - Show action bar message with new target name
  - Play cycling sound
  - Return `InteractionResult.sidedSuccess()`
- Update `entityInside()` teleport logic:
  - Instead of `registrar.getExplorationLevel(serverLevel.dimension())`, look up target from `PortalTargetManager`
  - Get target dimension ResourceLocation → resolve to ServerLevel via `DimensionRegistrar`
  - If target not found (dimension disabled/removed), fall back to first available
- Update `animateTick()`:
  - Look up current target dimension for this portal position
  - Use dimension-specific colored `DustParticleOptions` instead of `ParticleTypes.PORTAL`
  - Show action bar text when player is within 3 blocks

### 4. Update PortalActivation
`src/main/java/com/agent772/parallelworlds/portal/PortalActivation.java`:
- After portal is successfully activated (frame filled):
  - Register default target in `PortalTargetManager` (first enabled dimension)
  - Store canonical position (bottom-left from PortalShape)

### 5. Portal Cleanup Integration
- When portal blocks are destroyed (`onRemove`), call `PortalTargetManager.removeTarget()`
- When server shuts down, save all targets via `PWSavedData`

### 6. Update DimensionRegistrar for Multi-Target Lookup
`src/main/java/com/agent772/parallelworlds/dimension/DimensionRegistrar.java`:
- Add `getExplorationLevelByExplorationId(ResourceLocation) → Optional<ServerLevel>`
  - Looks up a runtime dimension directly by its exploration key (e.g., `parallelworlds:pw_overworld_0`)
- Add `getAllExplorationKeys() → List<ResourceKey<Level>>`
  - Returns all active exploration dimension keys in config order

### 7. Dimension Color Registry
`src/main/java/com/agent772/parallelworlds/portal/DimensionColors.java`:
- Static utility mapping dimension types to display properties:
  - `getParticleColor(ResourceLocation) → Vector3f` (RGB)
  - `getDisplayName(ResourceLocation) → Component` (formatted text)
- `refresh()` — called from `PWConfig.refresh()`, parses `dimensionParticleColors` config list
  - Splits each entry on `=`, parses ResourceLocation + hex color
  - Caches as `Map<ResourceLocation, Vector3f>`
- Fallback cyan `#33AAEE` for dimensions not in the config
- Display names: friendly names for vanilla dims, capitalized path for modded

## Config (already added to PWConfigSpec)
```toml
[portal]
# Item used to cycle the portal's target dimension (right-click portal).
# Set to empty string to disable cycling (first enabled dimension only).
portalCycleItem = "minecraft:ender_eye"

# Portal particle color per dimension as "mod:dimension=#RRGGBB".
# Dimensions not listed here use the default color (cyan: #33AAEE).
dimensionParticleColors = ["minecraft:overworld=#33CC4D", "minecraft:the_nether=#E64D19", "minecraft:the_end=#B233E6"]
```

## Verification
- Portal activates → defaults to first enabled exploration dimension
- Right-click portal with ender eye → cycles to next dimension, action bar shows name
- Particle color changes to match target dimension
- Stepping into portal → teleports to the selected target dimension (not the origin-based one)
- Portal in overworld targeting Parallel Nether → player arrives in pw_nether_0
- Return portal (in exploration dim) → always returns player to their saved return position
- Cycle wraps around: last dimension → first dimension
- Portal target persists across server restarts
- Destroying portal → cleans up stored target
- `enabledDimensions` changed in config → stale targets cleaned up on next startup
- Empty `portalCycleItem` → cycling disabled, portal always targets first dimension
- Single enabled dimension → cycling does nothing (stays on same target)

## Do NOT
- Use BlockEntities for portal state — use position-keyed SavedData
- Add a GUI/screen — cycling is purely right-click based
- Add sound pitch variation per dimension — keep portal sounds uniform
- Change the portal frame block or activation mechanic — those stay as-is from Epic 4
