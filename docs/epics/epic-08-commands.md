# Sub-Agent Instructions: Epic 8 — Commands

## Objective
Implement player and admin commands for dimension management, pre-gen control, and player locks.

## Context
- **Depends on**: Epic 2 (dimensions), Epic 3 (teleport), Epic 7 (pre-gen)
- **Registration**: via `RegisterCommandsEvent` on mod event bus

## Tasks

### 1. Player Commands — `/pw`
`src/main/java/com/agent772/parallelworlds/command/PWCommands.java`:

- `/pw list` — show all available mining dimensions with status (player count, seed type, time remaining)
- `/pw tp <dimension>` — teleport to mining dimension
  - Check dimension locks (advancement requirements from config)
  - Check manual unlocks from PWSavedData
  - Call TeleportHandler.teleportToExploration()
  - Show seed info + time until rotation
- `/pw return` — return from mining dimension via TeleportHandler.returnFromExploration()
- `/pw info` — show current dimension info (if in mining dim: seed, creation time, players present)
- `/pw help` — formatted help message

### 2. Admin Commands — `/pwadmin` (permission level 2)
`src/main/java/com/agent772/parallelworlds/command/PWAdminCommands.java`:

**Management:**
- `/pwadmin returnall` — evacuate all players from all mining dims via DimensionManager.evacuateAllPlayers()
- `/pwadmin info <dimension>` — show DimensionMetadata (creation time, visitors, visit count)
- `/pwadmin stats` — global stats (total dimensions, total visits, active pre-gen tasks)
- `/pwadmin seed <dimension>` — show seed for specific dimension
- `/pwadmin reload` — reload config (PWConfig.reload())

**Pre-generation:**
- `/pwadmin pregen start <dimension> [radius]` — ChunkPreGenerator.startGeneration()
- `/pwadmin pregen stop <dimension>` — ChunkPreGenerator.stopGeneration()
- `/pwadmin pregen pause <dimension>` — ChunkPreGenerator.pauseGeneration()
- `/pwadmin pregen resume <dimension>` — ChunkPreGenerator.resumeGeneration()
- `/pwadmin pregen status [dimension]` — show progress (%, rate, ETA, chunks generated)
- `/pwadmin pregen stopall` — stop all tasks

**Progression:**
- `/pwadmin unlock <player> <dimension>` — grant manual unlock
- `/pwadmin lock <player> <dimension>` — revoke manual unlock
- `/pwadmin unlocks [player]` — list manual unlocks

### 3. Argument Providers
- `DimensionSuggestionProvider` — suggests enabled mining dimensions for tab completion
- `PlayerSuggestionProvider` — suggests online players

### 4. Output Formatting
- Color coding: GREEN (success), RED (error), YELLOW (warning), AQUA (info)
- Thousands separators for large numbers (NumberFormat)
- Clickable dimension names (suggest /pw tp <dim>)
- Hoverable text for additional info

## Verification
- All commands register and appear in tab completion
- `/pw list` shows correct dimension count and status
- `/pw tp` with locked dimension → denied with message showing required advancement
- `/pwadmin` commands require op (permission level 2)
- Pre-gen status shows accurate numbers
- Invalid dimension names → clear error message
- GameTest: `CommandTest` — execute each command programmatically, verify success/failure
