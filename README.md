<div align="center">

<!-- LOGO -->

# Parallel Worlds

**Configurable temporary mining dimensions for NeoForge 1.21.1**

</div>

---

Parallel Worlds adds a set of isolated **exploration dimensions** designed for mining, looting, and resource gathering — separate from your main world so it never gets torn up. Each dimension is regenerated on a configurable schedule (daily, weekly, or monthly), giving your world a fresh supply of resources without wiping your base.

## Features

**Multiple dimension types**
Step through the portal and choose your destination: an overworld-style surface dimension, a nether dimension, or an end dimension. Dimensions are cloned directly from your server's world generator — full modded biome and structure support included.

**Rotating seeds**
Dimensions regenerate on a server-defined schedule. When the seed rotates, old dimension data is wiped and a new world is generated on the next restart. Return positions are always preserved so players never get stranded.

**Custom portal with dimension cycling**
Build or find the portal frame, light it, and enter. Sneak-interact with the portal while holding the configured cycle item to scroll through dimension targets. The portal color shifts to reflect the current destination.

**Item & ender chest restrictions**
Server admins can configure which items and blocks are restricted inside exploration dimensions. Ender chests can be blocked, warned about, or fully allowed — keeping the resource-gathering space intentionally separate from your main inventory pipeline.

**Performance-first design**
- TPS-aware chunk pre-generation pauses automatically when the server is under load
- Aggressive chunk unloading frees memory when exploration dimensions are empty
- Optional async noise pre-computation keeps chunk gen from piling up on the main thread
- Old dimension folders are automatically cleaned up based on a configurable retention count

**Admin tools**
Full admin command suite: list active dimensions, view per-dimension stats, evacuate all players, manage per-player dimension locks, and control pre-generation tasks. Config can be reloaded live without restart.

**Third-party mod compatibility**
When dimensions are deleted, cached map data from **Xaero's World Map**, **Xaero's Minimap**, **JourneyMap**, and **Distant Horizons** is automatically cleaned up on each connecting client. No stale map tiles or LOD data left behind.

## Requirements

- **Minecraft**: 1.21.1
- **Loader**: NeoForge

## License

MIT
