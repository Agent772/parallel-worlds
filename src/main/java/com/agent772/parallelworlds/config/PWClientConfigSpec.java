package com.agent772.parallelworlds.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge CLIENT config spec — settings that only affect the local client.
 * Stored in {@code config/parallelworlds-client.toml} and are never synced
 * to or from the server.
 */
public final class PWClientConfigSpec {
    public static final ModConfigSpec CLIENT_SPEC;

    // Compatibility
    public static final ModConfigSpec.BooleanValue MOD_COMPAT_CLEANUP_ENABLED;
    public static final ModConfigSpec.BooleanValue XAERO_PORTAL_WAYPOINT_ENABLED;
    public static final ModConfigSpec.BooleanValue JOURNEYMAP_PORTAL_WAYPOINT_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("compatibility");

        MOD_COMPAT_CLEANUP_ENABLED = builder
                .comment("Enable automatic cleanup of third-party mod cached data",
                         "(Xaero's Map, JourneyMap, Distant Horizons) when exploration",
                         "dimensions are regenerated or deleted.")
                .define("modCompatCleanupEnabled", true);

        XAERO_PORTAL_WAYPOINT_ENABLED = builder
                .comment("[Xaero's Minimap] Automatically create a waypoint at the portal entrance",
                         "in a parallel dimension when you enter via portal, so you can always",
                         "find your way back. The waypoint is removed when you leave through",
                         "the portal or the dimension is regenerated/rotated.",
                         "Has no effect if Xaero's Minimap is not installed.")
                .define("xaeroPortalWaypointEnabled", true);

        JOURNEYMAP_PORTAL_WAYPOINT_ENABLED = builder
                .comment("[JourneyMap] Automatically create a waypoint at the portal entrance",
                         "in a parallel dimension when you enter via portal, so you can always",
                         "find your way back. The waypoint is removed when you leave through",
                         "the portal or the dimension is regenerated/rotated.",
                         "Has no effect if JourneyMap is not installed.")
                .define("journeyMapPortalWaypointEnabled", true);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    private PWClientConfigSpec() {}
}
