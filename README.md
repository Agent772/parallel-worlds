<div align="center">

<!-- LOGO -->

# Parallel Worlds

**Temporary mining dimensions, fresh on a schedule — NeoForge 1.21.1**

</div>

---

Parallel Worlds adds isolated **exploration dimensions** for mining and looting so your main world never gets strip-mined. Pick a destination through the portal, dig everything up, and come back — the dimension resets on schedule and a fresh one waits next restart.

Dimensions are cloned directly from your server's world generator, so modded biomes and structures work out of the box.

---

## Getting in

**Portal (default)** — Build a frame out of the configured block (default: glass), light it with the configured item (default: flint and steel), and step through. Sneak + right-click with the cycle item to scroll through dimension targets; the portal color changes to show where you'll land.

**Commands (optional)** — `/pw tp` and `/pw return` are disabled by default but can be turned on in config. Useful for admins or servers that don't want the portal requirement.

---

## Key config features

**Seed rotation** — Dimensions reset on a DAILY / WEEKLY / MONTHLY schedule. Set the day, hour, and minute; the wipe happens on the next server restart after that time. Return positions are always saved, so no one gets stranded.

**Persistence** — Saved data (return positions, portal targets, pregen progress, pending cleanups) survives restarts via NeoForge's `SavedData`. If you want a dimension to stick around longer, bump up the retention count — old dimension folders are only deleted once the limit is exceeded.

**Map mod cleanup** — When a dimension is deleted, cached data from **Xaero's World Map**, **Xaero's Minimap**, **JourneyMap**, and **Distant Horizons** is automatically wiped on each client that reconnects. No stale tiles or LOD files. Can be disabled with `modCompatCleanupEnabled = false`.

**Chunk pre-generation** — Optionally pre-generate a radius of chunks around spawn when a new dimension is created. The generator is TPS-aware: full speed above 19.5 TPS, throttled below 19.0, paused below 18.0. This avoids lag spikes during normal play but will consume some extra CPU in the background — tune the radius and chunk budget per tick to match your hardware.

**Async noise computation** — Noise and heightmap calculations can be offloaded to worker threads so chunk generation doesn't block the main thread. Disabled by default; enable with `asyncChunkGenerationEnabled = true`. The worker count defaults to half your available CPU cores and is configurable.

---

## Requirements

- **Minecraft**: 1.21.1
- **Loader**: NeoForge

## License

MIT
