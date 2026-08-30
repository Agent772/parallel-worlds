## Version 1.1.0

### Added
- Added Parallel Worlds portals: buildable obsidian-frame portals that send players into a personal exploration dimension with a unique world generation seed
- Added exploration dimensions for the overworld, Nether, and End, each independently seeded and isolated from the vanilla world
- Added configurable seed rotation that automatically resets exploration dimensions on a schedule, safely evacuating players to the overworld before each reset
- Added `/pwadmin forcerotate` command to schedule a forced seed rotation of all exploration dimensions on the next server restart
- Added advancement-based dimension locks: server admins can require players to earn a specific advancement before they can enter a dimension
- Added configurable noise generator preset for exploration dimensions (`minecraft:large_biomes`, `minecraft:amplified`, etc.), allowing the terrain type to differ from the source dimension
- Added portal ignition and portal cycling permission options: server admins can restrict either action to operators only
- Added configurable teleport blocking for non-operators inside exploration dimensions
- Added Death Recall Token item: a usable item that returns the player to their most recent death location inside an exploration dimension
- Added admin recovery commands to return players stranded in inactive exploration dimensions (`/pwadmin` recovery subcommands)
- Added chunk pre-generation for exploration dimensions with asynchronous background generation to minimize server lag
- Added Jade integration: displays the destination dimension's name and color in the HUD overlay when looking at a PW portal
- Added Xaero's Minimap and Xaero's World Map integration: automatically places a waypoint at the destination when traveling through a PW portal
- Added JourneyMap integration: automatically places a JourneyMap waypoint at the destination when traveling through a PW portal
- Added Tempad integration: Tempad teleportation is blocked inside exploration dimensions (configurable)
- Added client-side map data cleanup: automatically removes stale Xaero's Minimap, Xaero's World Map, JourneyMap, and Distant Horizons map data for dimensions that have been rotated
