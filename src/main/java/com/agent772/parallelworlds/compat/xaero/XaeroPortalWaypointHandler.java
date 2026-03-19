package com.agent772.parallelworlds.compat.xaero;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.network.payload.WaypointSyncPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side handler that creates/removes a Xaero's Minimap waypoint at the
 * PW portal entrance whenever a player travels through a parallel-world portal.
 * <p>
 * Uses reflection so Xaero's Minimap remains a fully optional runtime
 * dependency — no compile-time coupling required.
 * <p>
 * Behaviour:
 * <ul>
 *   <li><b>Enter portal</b> — adds a "PW Portal" waypoint at the portal
 *       position in the exploration dimension (if one doesn't already exist in
 *       the current waypoint set).</li>
 *   <li><b>Exit portal</b> — removes the waypoint from the exploration
 *       dimension's waypoint set using either the cached reference (same
 *       session) or a name-scan fallback (player reconnected).</li>
 *   <li><b>Dimension reset / cleanup</b> — the existing file-deletion cleanup
 *       already removes the waypoints file; this class just purges stale
 *       in-memory cache entries so stale references don't linger.</li>
 * </ul>
 */
public final class XaeroPortalWaypointHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The name written into every portal waypoint. */
    static final String WAYPOINT_NAME = "PW Portal";

    /**
     * Xaero colour index 13 = PURPLE — matches the portal's dimensional look.
     */
    private static final int WAYPOINT_COLOR = 13;

    /**
     * Per-dim cache: dimId → [WaypointSet obj, Waypoint obj].
     * Allows fast targeted removal without rescanning all waypoint worlds.
     * Cleared on session disconnect via {@link #clearCache()}.
     */
    private static final Map<ResourceLocation, Object[]> waypointCache = new ConcurrentHashMap<>();

    private XaeroPortalWaypointHandler() {}

    // ── Payload handler ──────────────────────────────────────────────────────

    public static void handleWaypointSync(WaypointSyncPayload payload, IPayloadContext context) {
        if (!PWConfig.isXaeroPortalWaypointEnabled()) return;
        if (!isModLoaded("xaerominimap")) return;

        context.enqueueWork(() -> {
            if (payload.add()) {
                scheduleAddWaypoint(payload.dimId(), payload.x(), payload.y(), payload.z(), 60);
            } else {
                try {
                    removeWaypoint(payload.dimId());
                } catch (Exception e) {
                    LOGGER.warn("[PW Xaero Compat] Waypoint remove failed for {}: {}", payload.dimId(), e.getMessage());
                }
            }
        });
    }

    // ── Add ──────────────────────────────────────────────────────────────────

    /**
     * Attempts to add the waypoint to Xaero's auto-managed {@code WaypointWorld}
     * for the exploration dim.  Retries each tick until Xaero has loaded the
     * dimension container on its own, then uses {@code getFirstWorld()} to reach
     * Xaero's existing auto world without creating duplicate world entries.
     * <p>
     * Key invariants:
     * <ul>
     *   <li>Never calls {@code addWorld()} or {@code getWorld()} — both are
     *       aliases in this Xaero version and always create new world entries.</li>
     *   <li>Uses {@code getWorldContainerNullable()} as the readiness signal.
     *       {@code getAutoContainerID()} is unreliable here because Xaero maps
     *       our dynamically-registered overworld-type dim back to {@code dim%0}
     *       in its auto-tracking, even when the player is in the exploration dim.</li>
     * </ul>
     */
    private static void scheduleAddWaypoint(ResourceLocation dimId, int x, int y, int z, int retriesLeft) {
        try {
            Object session = getSession();
            if (session == null) {
                retry("No Xaero session", dimId, x, y, z, retriesLeft);
                return;
            }

            Object manager = invoke(session, "getWaypointsManager");
            if (manager == null) {
                retry("WaypointsManager null", dimId, x, y, z, retriesLeft);
                return;
            }

            // Xaero 1.20+ world-ID format: "namespace$path"
            String worldID = dimId.getNamespace() + "$" + dimId.getPath();

            // Build the target container path directly from the dim key.
            // getAutoContainerID() always returns "WorldName/dim%0" for overworld-type
            // dims regardless of which dim the player is currently in — so we strip
            // the dim%... suffix and append the correct one ourselves.
            String autoContainerID = (String) invoke(manager, "getAutoContainerID");
            if (autoContainerID == null || autoContainerID.isEmpty()) {
                retry("No auto container ID", dimId, x, y, z, retriesLeft);
                return;
            }
            int slashIdx = autoContainerID.indexOf("/dim%");
            String baseName = (slashIdx >= 0) ? autoContainerID.substring(0, slashIdx) : autoContainerID;
            String targetContainerID = baseName + "/dim%" + worldID;

            // getWorldContainerNullable() is a pure lookup — null if Xaero hasn't
            // loaded this container yet, never creates one.  We use its non-null
            // return as the signal that Xaero is ready for us to add the waypoint.
            Object container = manager.getClass()
                    .getMethod("getWorldContainerNullable", String.class)
                    .invoke(manager, targetContainerID);
            if (container == null) {
                retry("Container not yet loaded (" + targetContainerID + ")", dimId, x, y, z, retriesLeft);
                return;
            }

            // getFirstWorld() returns Xaero's existing auto-managed world (the one
            // the player sees in the HUD) without ever creating a new numbered world.
            Object world = invoke(container, "getFirstWorld");
            if (world == null) {
                retry("No world in container yet", dimId, x, y, z, retriesLeft);
                return;
            }

            Object set = invoke(world, "getCurrentSet");
            if (set == null) {
                world.getClass().getMethod("addSet", String.class)
                        .invoke(world, "gui.xaero_default");
                set = invoke(world, "getCurrentSet");
            }
            if (set == null) {
                LOGGER.warn("[PW Xaero Compat] No waypoint set for {}; skipping", dimId);
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) invoke(set, "getList");
            if (list == null) return;

            // Guard: only add ONE portal waypoint per dim per session
            for (Object existing : list) {
                if (WAYPOINT_NAME.equals(invoke(existing, "getName"))) {
                    LOGGER.debug("[PW Xaero Compat] Waypoint '{}' already present in {}; skipping",
                            WAYPOINT_NAME, worldID);
                    return;
                }
            }

            // Waypoint(int x, int y, int z, String name, String initials, int colorIndex)
            Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> ctor = wpClass.getConstructor(
                    int.class, int.class, int.class, String.class, String.class, int.class);
            Object waypoint = ctor.newInstance(x, y, z, WAYPOINT_NAME, "P", WAYPOINT_COLOR);

            list.add(waypoint);
            waypointCache.put(dimId, new Object[]{set, waypoint});

            try {
                manager.getClass().getMethod("updateWaypoints").invoke(manager);
            } catch (Exception e) {
                LOGGER.debug("[PW Xaero Compat] updateWaypoints() unavailable: {}", e.getMessage());
            }

            LOGGER.info("[PW Xaero Compat] Added '{}' waypoint at ({},{},{}) in {} (container={})",
                    WAYPOINT_NAME, x, y, z, worldID, targetContainerID);

        } catch (Exception e) {
            LOGGER.warn("[PW Xaero Compat] Waypoint add error for {}: ", dimId, e);
        }
    }

    /** Schedules a retry on the next game tick, or logs and gives up if retries are exhausted. */
    private static void retry(String reason, ResourceLocation dimId, int x, int y, int z, int retriesLeft) {
        if (retriesLeft > 0) {
            LOGGER.debug("[PW Xaero Compat] ADD retry ({} left): {}", retriesLeft, reason);
            Minecraft.getInstance().execute(() -> scheduleAddWaypoint(dimId, x, y, z, retriesLeft - 1));
        } else {
            // Container never loaded — player entered a brand-new dim with no prior
            // waypoint data on disk.  Bootstrap the container ourselves so Xaero has
            // something to load next time, and add the waypoint now.
            LOGGER.info("[PW Xaero Compat] Container not loaded after retries — bootstrapping for {}: {}", dimId, reason);
            bootstrapAddWaypoint(dimId, x, y, z);
        }
    }

    /**
     * Last-resort path: Xaero never loaded a container for this dim (no prior
     * waypoint file on disk).  We create the container and its first world via
     * {@code addWorldContainer} + {@code container.addWorld(worldID)}.  Because
     * the container is empty at this point, {@code addWorld} creates the very
     * first world (index 1) which Xaero will treat as the auto world on next load.
     */
    private static void bootstrapAddWaypoint(ResourceLocation dimId, int x, int y, int z) {
        try {
            Object session = getSession();
            if (session == null) return;
            Object manager = invoke(session, "getWaypointsManager");
            if (manager == null) return;

            String worldID = dimId.getNamespace() + "$" + dimId.getPath();
            String autoContainerID = (String) invoke(manager, "getAutoContainerID");
            if (autoContainerID == null || autoContainerID.isEmpty()) return;
            int slashIdx = autoContainerID.indexOf("/dim%");
            String baseName = (slashIdx >= 0) ? autoContainerID.substring(0, slashIdx) : autoContainerID;
            String targetContainerID = baseName + "/dim%" + worldID;

            // addWorldContainer is idempotent — returns existing container if present.
            Object container = manager.getClass()
                    .getMethod("addWorldContainer", String.class)
                    .invoke(manager, targetContainerID);
            if (container == null) {
                LOGGER.warn("[PW Xaero Compat] addWorldContainer returned null for {}", targetContainerID);
                return;
            }

            // Try getFirstWorld before calling addWorld — Xaero may have loaded it
            // between our last retry and now.
            Object world = invoke(container, "getFirstWorld");
            if (world == null) {
                world = container.getClass()
                        .getMethod("addWorld", String.class)
                        .invoke(container, worldID);
            }
            if (world == null) {
                LOGGER.warn("[PW Xaero Compat] Could not get/create world in {} for {}", targetContainerID, dimId);
                return;
            }

            Object set = invoke(world, "getCurrentSet");
            if (set == null) {
                world.getClass().getMethod("addSet", String.class).invoke(world, "gui.xaero_default");
                set = invoke(world, "getCurrentSet");
            }
            if (set == null) return;

            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) invoke(set, "getList");
            if (list == null) return;

            for (Object existing : list) {
                if (WAYPOINT_NAME.equals(invoke(existing, "getName"))) return;
            }

            Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> ctor = wpClass.getConstructor(
                    int.class, int.class, int.class, String.class, String.class, int.class);
            Object waypoint = ctor.newInstance(x, y, z, WAYPOINT_NAME, "P", WAYPOINT_COLOR);
            list.add(waypoint);
            waypointCache.put(dimId, new Object[]{set, waypoint});

            try {
                manager.getClass().getMethod("updateWaypoints").invoke(manager);
            } catch (Exception e) {
                LOGGER.debug("[PW Xaero Compat] updateWaypoints() unavailable: {}", e.getMessage());
            }

            LOGGER.info("[PW Xaero Compat] Bootstrapped '{}' waypoint at ({},{},{}) in {} (container={})",
                    WAYPOINT_NAME, x, y, z, worldID, targetContainerID);
        } catch (Exception e) {
            LOGGER.warn("[PW Xaero Compat] Bootstrap add error for {}: ", dimId, e);
        }
    }

    // ── Remove ───────────────────────────────────────────────────────────────

    private static void removeWaypoint(ResourceLocation dimId) throws Exception {
        Object[] cached = waypointCache.remove(dimId);

        if (cached != null) {
            // Fast path: use the exact reference we added
            removeFromSet(cached[0], cached[1]);
            LOGGER.info("[PW Xaero Compat] Removed '{}' waypoint (cached) from {}", WAYPOINT_NAME, dimId);
            // Refresh display
            try {
                Object session = getSession();
                if (session != null) {
                    Object mgr = invoke(session, "getWaypointsManager");
                    if (mgr != null) mgr.getClass().getMethod("updateWaypoints").invoke(mgr);
                }
            } catch (Exception e) {
                LOGGER.debug("[PW Xaero Compat] updateWaypoints() unavailable during remove: {}", e.getMessage());
            }
        } else {
            // Fallback: player may have reconnected since the waypoint was added
            removeFallback(dimId);
        }
    }

    /**
     * Removes {@code waypoint} from the supplied {@code set}'s list.
     */
    private static void removeFromSet(Object set, Object waypoint) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) invoke(set, "getList");
        if (list != null) list.remove(waypoint);
    }

    /**
     * Fallback removal used when the in-memory cache is empty (e.g. the player
     * reconnected after the waypoint was placed in a previous session).
     * <p>
     * Resolves the WaypointWorld for {@code dimId} using the current server's
     * container ID and the Xaero dimension-directory name convention
     * ({@code namespace$path}), then removes any waypoint whose name matches
     * {@link #WAYPOINT_NAME}.
     */
    private static void removeFallback(ResourceLocation dimId) {
        try {
            Object session = getSession();
            if (session == null) return;

            Object manager = invoke(session, "getWaypointsManager");
            if (manager == null) return;

            // Xaero 1.20+ world ID format: "namespace$path"
            String worldID = dimId.getNamespace() + "$" + dimId.getPath();

            // The player is back in the overworld, so getAutoContainerID() reflects
            // the overworld.  Strip the dim%... suffix and rebuild the exploration dim's
            // container path so we can look it up by its on-disk path.
            String rawAutoContainerID = (String) invoke(manager, "getAutoContainerID");
            if (rawAutoContainerID == null || rawAutoContainerID.isEmpty()) return;
            int dimIdx = rawAutoContainerID.indexOf("/dim%");
            String baseContainerName = (dimIdx >= 0) ? rawAutoContainerID.substring(0, dimIdx) : rawAutoContainerID;
            String containerID = baseContainerName + "/dim%" + worldID;

            // getWorldContainerNullable is a pure lookup — never creates.
            // (getWorld/addWorld are aliases in this Xaero version and always create.)
            Object container = manager.getClass()
                    .getMethod("getWorldContainerNullable", String.class)
                    .invoke(manager, containerID);
            if (container == null) {
                LOGGER.debug("[PW Xaero Compat] No container for {}; nothing to remove", dimId);
                return;
            }

            Object world = invoke(container, "getFirstWorld");
            if (world == null) return;

            Object set = invoke(world, "getCurrentSet");
            if (set == null) return;

            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) invoke(set, "getList");
            if (list == null) return;

            list.removeIf(wp -> {
                try {
                    return WAYPOINT_NAME.equals(invoke(wp, "getName"));
                } catch (Exception ex) {
                    return false;
                }
            });

            try {
                manager.getClass().getMethod("updateWaypoints").invoke(manager);
            } catch (Exception e) {
                LOGGER.debug("[PW Xaero Compat] updateWaypoints() unavailable during remove: {}", e.getMessage());
            }

            LOGGER.info("[PW Xaero Compat] Removed '{}' waypoint (fallback scan) from {}/{}", WAYPOINT_NAME, containerID, worldID);
        } catch (Exception e) {
            LOGGER.debug("[PW Xaero Compat] Fallback removal failed for {}: {}", dimId, e.getMessage());
        }
    }

    // ── Cache lifecycle ──────────────────────────────────────────────────────

    /**
     * Clears all cached waypoint references.
     * Called on client disconnect so stale Xaero object references don't accumulate.
     */
    public static void clearCache() {
        waypointCache.clear();
    }

    /**
     * Clears cached references for specific deleted dimensions.
     * Called when a {@code DimensionResetPayload} or cleanup notification
     * arrives so we don't attempt to remove from a stale set later.
     */
    public static void clearCacheForDim(ResourceLocation dimId) {
        waypointCache.remove(dimId);
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    /**
     * Returns the current {@code XaeroMinimapSession} instance, or {@code null}
     * if Xaero is not loaded or the session is not yet initialised.
     */
    private static Object getSession() {
        try {
            Class<?> cls = Class.forName("xaero.common.XaeroMinimapSession");
            return cls.getMethod("getCurrentSession").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Invokes a no-arg method by name on an object using the object's actual class. */
    private static Object invoke(Object obj, String methodName) throws Exception {
        Method m = obj.getClass().getMethod(methodName);
        return m.invoke(obj);
    }

    private static boolean isModLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Exception e) {
            return false;
        }
    }
}
