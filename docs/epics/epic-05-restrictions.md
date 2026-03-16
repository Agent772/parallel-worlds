# Sub-Agent Instructions: Epic 5 — Item & Ender Chest Restrictions

## Objective
Implement configurable restrictions on ender chests and tagged items inside mining dimensions. Three modes: BLOCK (prevent), WARN (allow + log), ALLOW (no action).

## Context
- **Depends on**: Epic 2 (dimension detection via DimensionManager.isExplorationDimension())
- **Config keys**: `enderChestMode`, `restrictedItemTag`, `restrictedItemMode`

## Tasks

### 1. RestrictionMode Enum
`src/main/java/com/agent772/parallelworlds/restriction/RestrictionMode.java`:
- `BLOCK` — cancel action, show player message
- `WARN` — allow action, show player warning, log to server console
- `ALLOW` — no interference
- Parse from config string (case-insensitive)

### 2. RestrictionHandler
`src/main/java/com/agent772/parallelworlds/restriction/RestrictionHandler.java`:

Static methods:
- `onBlockInteract(ServerPlayer player, BlockPos pos, BlockState state, InteractionHand hand) → InteractionResult`:
  - Check: is player in exploration dimension? (via DimensionManager)
  - Check: is block an ender chest? (`state.is(Blocks.ENDER_CHEST)`)
  - Apply PWConfig.getEnderChestMode():
    - BLOCK: return FAIL, send translatable message "You cannot use ender chests in the mining dimension"
    - WARN: send warning message, log to server console "Player X used ender chest in mining dim Y", return PASS
    - ALLOW: return PASS

- `onBlockPlace(ServerPlayer player, BlockPos pos, BlockState state) → boolean (cancel?)`:
  - Check: is player in exploration dimension?
  - Check: is block tagged `parallelworlds:restricted_blocks`?
  - Apply PWConfig.getRestrictedItemMode()

- `onItemUse(ServerPlayer player, ItemStack stack, InteractionHand hand) → InteractionResult`:
  - Check: is player in exploration dimension?
  - Check: is item tagged `parallelworlds:restricted_items`?
  - Apply PWConfig.getRestrictedItemMode()

### 3. Item/Block Tags
`src/main/resources/data/parallelworlds/tags/items/restricted_items.json`:
```json
{ "values": ["minecraft:ender_chest"] }
```

`src/main/resources/data/parallelworlds/tags/blocks/restricted_blocks.json`:
```json
{ "values": ["minecraft:ender_chest"] }
```

### 4. Event Registration
In `PWEventHandlers` (or directly in restriction handler):
- Subscribe to `PlayerInteractEvent.RightClickBlock` — delegate to `RestrictionHandler.onBlockInteract()`
- Subscribe to `PlayerInteractEvent.RightClickItem` — delegate to `RestrictionHandler.onItemUse()`
- Subscribe to `BlockEvent.EntityPlaceEvent` — delegate to `RestrictionHandler.onBlockPlace()`

### 5. Messages
Add to lang file (`en_us.json`):
- `parallelworlds.restriction.ender_chest.blocked` = "Ender chests are disabled in mining dimensions"
- `parallelworlds.restriction.ender_chest.warning` = "Warning: Using ender chests in mining dimensions may bypass intended restrictions"
- `parallelworlds.restriction.item.blocked` = "This item is restricted in mining dimensions"
- `parallelworlds.restriction.item.warning` = "Warning: This item is restricted in mining dimensions"

## Verification
- BLOCK mode: right-click ender chest → denied, message shown, chest does NOT open
- WARN mode: right-click ender chest → opens, warning shown to player, logged to console
- ALLOW mode: ender chest works normally
- Custom-tagged items also restricted correctly
- Restrictions ONLY apply inside mining dimensions (no effect in overworld)
- Restrictions DO NOT apply to ops (permission level 2+)? — decide based on config
- GameTest: `RestrictionTest` — each mode × ender chest + tagged items, verify event cancellation

## Do NOT
- Implement inventory scanning (items already in inventory are fine — only restrict usage/placement)
- Create custom item restrictions beyond tag-based
- Add per-player restriction overrides
