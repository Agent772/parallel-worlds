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

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("compatibility");

        MOD_COMPAT_CLEANUP_ENABLED = builder
                .comment("Enable automatic cleanup of third-party mod cached data",
                         "(Xaero's Map, JourneyMap, Distant Horizons) when exploration",
                         "dimensions are regenerated or deleted.")
                .define("modCompatCleanupEnabled", true);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    private PWClientConfigSpec() {}
}
