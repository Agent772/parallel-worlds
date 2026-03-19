package com.agent772.parallelworlds.compat.journeymap;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.network.payload.WaypointSyncPayload;
import com.mojang.logging.LogUtils;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side handler that creates/removes a JourneyMap waypoint at the
 * PW portal entrance whenever a player travels through a parallel-world portal.
 * <p>
 * JourneyMap is an optional runtime dependency  this class is only reached
 * when JourneyMap is loaded. The {@link PWJourneyMapPlugin} plugin stub
 * satisfies JM's requirement that every API caller be a registered plugin.
 * <p>
 * Behaviour:
 * <ul>
 *   <li><b>Enter portal</b>  adds a "PW Portal" waypoint at the portal
 *       position bound to the exploration dimension.</li>
 *   <li><b>Exit portal</b>  removes the waypoint using either the cached
 *       reference (same session) or a name-scan fallback.</li>
 *   <li><b>Dimension reset / cleanup</b>  purges stale in-memory cache
 *       entries so stale references don't linger.</li>
 * </ul>
 *
 * <p>The waypoint is non-persistent ({@code persistent = false}) so it lives
 * only for the current client session and is not written to JourneyMap's
 * waypoint files on disk.
 */
public final class JourneyMapPortalWaypointHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String WAYPOINT_NAME = "PW Portal";
    private static final String WAYPOINT_GROUP_NAME = "Temp";

    /** Purple — matches the portal's dimensional look. 0xRRGGBB. */
    private static final int WAYPOINT_COLOR = 0x9900CC;

    /**
     * Supplied by {@link PWJourneyMapPlugin#initialize} when JM is ready.
     * Null until JM calls our plugin's initialize().
     */
    private static volatile IClientAPI jmApi;

    /** Non-persistent waypoint group ("Temp") that holds all PW portal waypoints. */
    private static volatile WaypointGroup waypointGroup;

    /** Per-dim cache: dimId -> Waypoint. Cleared on disconnect. */
    private static final Map<ResourceLocation, Waypoint> waypointCache = new ConcurrentHashMap<>();

    private JourneyMapPortalWaypointHandler() {}

    // Called by PWJourneyMapPlugin 

    static void onApiReady(IClientAPI api) {
        jmApi = api;
        LOGGER.info("[PW JM Compat] JourneyMap API ready (modId={})", ParallelWorlds.MOD_ID);
    }

    // Payload handler 

    public static void handleWaypointSync(WaypointSyncPayload payload, IPayloadContext context) {
        if (!PWConfig.isJourneyMapPortalWaypointEnabled()) return;
        if (!ModList.get().isLoaded("journeymap")) return;

        context.enqueueWork(() -> {
            if (payload.add()) {
                addWaypoint(payload.dimId(), payload.x(), payload.y(), payload.z());
            } else {
                removeWaypoint(payload.dimId());
            }
        });
    }

    // Add 

    private static void addWaypoint(ResourceLocation dimId, int x, int y, int z) {
        IClientAPI api = jmApi;
        if (api == null) {
            LOGGER.warn("[PW JM Compat] API not ready yet when adding waypoint for {}", dimId);
            return;
        }
        if (waypointCache.containsKey(dimId)) {
            LOGGER.debug("[PW JM Compat] Waypoint already cached for {}; skipping", dimId);
            return;
        }
        try {
            WaypointGroup group = ensureGroup(api);
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
            Waypoint waypoint = group != null
                    ? WaypointFactory.createClientWaypoint(WAYPOINT_NAME, new BlockPos(x, y, z), group.getGuid(), dimKey, false)
                    : WaypointFactory.createClientWaypoint(WAYPOINT_NAME, new BlockPos(x, y, z), dimKey, false);
            waypoint.setName(WAYPOINT_NAME);
            waypoint.setColor(WAYPOINT_COLOR);

            api.addWaypoint(ParallelWorlds.MOD_ID, waypoint);
            waypointCache.put(dimId, waypoint);
            LOGGER.info("[PW JM Compat] Added '{}' waypoint at ({},{},{}) in {}", WAYPOINT_NAME, x, y, z, dimId);
        } catch (Exception e) {
            LOGGER.warn("[PW JM Compat] Waypoint add error for {}: ", dimId, e);
        }
    }

    // Remove 

    private static void removeWaypoint(ResourceLocation dimId) {
        Waypoint cached = waypointCache.remove(dimId);
        if (cached != null) {
            IClientAPI api = jmApi;
            if (api == null) return;
            try {
                api.removeWaypoint(ParallelWorlds.MOD_ID, cached);
                LOGGER.info("[PW JM Compat] Removed '{}' waypoint (cached) from {}", WAYPOINT_NAME, dimId);
            } catch (Exception e) {
                LOGGER.warn("[PW JM Compat] Waypoint remove error for {}: {}", dimId, e.getMessage());
            }
        } else {
            removeFallback(dimId);
        }
    }

    /**
     * Fallback when cache is empty (e.g. player reconnected after waypoint was placed).
     * Scans all waypoints registered under our modId and removes any named "PW Portal".
     */
    private static void removeFallback(ResourceLocation dimId) {
        IClientAPI api = jmApi;
        if (api == null) return;
        try {
            List<? extends Waypoint> waypoints = api.getWaypoints(ParallelWorlds.MOD_ID);
            if (waypoints == null || waypoints.isEmpty()) return;
            for (Waypoint wp : List.copyOf(waypoints)) {
                if (WAYPOINT_NAME.equals(wp.getName())) {
                    api.removeWaypoint(ParallelWorlds.MOD_ID, wp);
                    LOGGER.info("[PW JM Compat] Removed '{}' waypoint (fallback) for {}", WAYPOINT_NAME, dimId);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[PW JM Compat] Fallback removal failed for {}: {}", dimId, e.getMessage());
        }
    }

    // Group management ──────────────────────────────────────────────────────────

    private static WaypointGroup ensureGroup(IClientAPI api) {
        if (waypointGroup != null) return waypointGroup;
        try {
            WaypointGroup existing = api.getWaypointGroupByName(ParallelWorlds.MOD_ID, WAYPOINT_GROUP_NAME);
            if (existing != null) {
                waypointGroup = existing;
            } else {
                WaypointGroup group = WaypointFactory.createWaypointGroup(ParallelWorlds.MOD_ID, WAYPOINT_GROUP_NAME);
                group.setPersistent(false);
                api.addWaypointGroup(group);
                waypointGroup = group;
            }
        } catch (Exception e) {
            LOGGER.warn("[PW JM Compat] Failed to create waypoint group: {}", e.getMessage());
        }
        return waypointGroup;
    }

    // Cache lifecycle ─────────────────────────────────────────────────────────

    public static void clearCache() {
        waypointCache.clear();
        IClientAPI api = jmApi;
        WaypointGroup group = waypointGroup;
        if (api != null && group != null) {
            try { api.removeWaypointGroup(group, false); } catch (Exception ignored) {}
        }
        waypointGroup = null;
    }

    public static void clearCacheForDim(ResourceLocation dimId) {
        waypointCache.remove(dimId);
    }
}
