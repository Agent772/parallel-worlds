package com.agent772.parallelworlds.client;

import com.agent772.parallelworlds.compat.journeymap.JourneyMapPortalWaypointHandler;
import com.agent772.parallelworlds.compat.xaero.XaeroPortalWaypointHandler;
import com.agent772.parallelworlds.network.payload.DimensionResetPayload;
import com.agent772.parallelworlds.network.payload.DimensionSyncPayload;
import com.agent772.parallelworlds.network.payload.ResetWarningPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side handler for dimension sync and reset warning packets.
 */
public final class PWClientHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<ResourceLocation> knownDimensions = ConcurrentHashMap.newKeySet();

    private PWClientHandler() {}

    public static void handleDimensionSync(DimensionSyncPayload data, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (data.exists()) {
                knownDimensions.add(data.dimensionId());
                LOGGER.debug("Dimension synced (added): {}", data.dimensionId());
            } else {
                knownDimensions.remove(data.dimensionId());
                LOGGER.debug("Dimension synced (removed): {}", data.dimensionId());
            }
        });
    }

    public static void handleDimensionReset(DimensionResetPayload data, IPayloadContext context) {
        context.enqueueWork(() -> {
            knownDimensions.remove(data.dimensionId());
            // Clear any cached waypoint reference for this dimension so we don’t try
            // to remove it from a stale object after the dim has been recreated
            XaeroPortalWaypointHandler.clearCacheForDim(data.dimensionId());
            JourneyMapPortalWaypointHandler.clearCacheForDim(data.dimensionId());
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.message.dimension_reset",
                                data.dimensionId().toString())
                                .withStyle(ChatFormatting.YELLOW),
                        false
                );
            }
            LOGGER.debug("Dimension reset received: {} at {}", data.dimensionId(), data.resetTime());
        });
    }

    public static void handleResetWarning(ResetWarningPayload data, IPayloadContext context) {
        context.enqueueWork(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                Component msg;
                if (!data.message().isEmpty()) {
                    msg = Component.literal(data.message()).withStyle(ChatFormatting.GOLD);
                } else {
                    msg = Component.translatable("parallelworlds.message.reset_warning",
                            data.minutesRemaining())
                            .withStyle(ChatFormatting.GOLD);
                }
                // Display on action bar for non-intrusive notification
                player.displayClientMessage(msg, true);
            }
        });
    }

    /**
     * Returns the set of exploration dimensions the client knows about.
     */
    public static Set<ResourceLocation> getKnownDimensions() {
        return Set.copyOf(knownDimensions);
    }

    /**
     * Clear client state (e.g. on disconnect).
     */
    public static void clearAll() {
        knownDimensions.clear();
        XaeroPortalWaypointHandler.clearCache();
        JourneyMapPortalWaypointHandler.clearCache();
    }
}
