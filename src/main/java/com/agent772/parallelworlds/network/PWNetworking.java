package com.agent772.parallelworlds.network;

import com.agent772.parallelworlds.client.ModDataCleanupHandler;
import com.agent772.parallelworlds.client.PWClientHandler;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.network.payload.DimensionCleanupPayload;
import com.agent772.parallelworlds.network.payload.DimensionResetPayload;
import com.agent772.parallelworlds.network.payload.DimensionSyncPayload;
import com.agent772.parallelworlds.network.payload.ResetWarningPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Registers network payloads and provides convenience methods for sending
 * dimension sync and reset warning packets to clients.
 */
public final class PWNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PWNetworking() {}

    /**
     * Called from the mod event bus to register all payload types.
     */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                DimensionSyncPayload.TYPE,
                DimensionSyncPayload.STREAM_CODEC,
                PWClientHandler::handleDimensionSync
        );
        registrar.playToClient(
                DimensionResetPayload.TYPE,
                DimensionResetPayload.STREAM_CODEC,
                PWClientHandler::handleDimensionReset
        );
        registrar.playToClient(
                ResetWarningPayload.TYPE,
                ResetWarningPayload.STREAM_CODEC,
                PWClientHandler::handleResetWarning
        );
        registrar.playToClient(
                DimensionCleanupPayload.TYPE,
                DimensionCleanupPayload.STREAM_CODEC,
                ModDataCleanupHandler::onDimensionCleanupReceived
        );

        LOGGER.info("Parallel Worlds network payloads registered");
    }

    // ── Send helpers ──

    public static void sendDimensionSync(ServerPlayer player, ResourceLocation dim, boolean exists) {
        PacketDistributor.sendToPlayer(player, new DimensionSyncPayload(dim, exists));
    }

    public static void sendDimensionSyncToAll(ResourceLocation dim, boolean exists) {
        PacketDistributor.sendToAllPlayers(new DimensionSyncPayload(dim, exists));
    }

    /**
     * Sync all currently active exploration dimensions to a single player (e.g. on join).
     */
    public static void syncAllDimensionsToPlayer(ServerPlayer player) {
        Set<ResourceLocation> dims = DimensionRegistrar.getExplorationDimensionIds();
        for (ResourceLocation dim : dims) {
            sendDimensionSync(player, dim, true);
        }
        LOGGER.debug("Synced {} exploration dimensions to {}", dims.size(), player.getName().getString());
    }

    public static void sendDimensionReset(ServerPlayer player, ResourceLocation dim, long resetTime) {
        PacketDistributor.sendToPlayer(player, new DimensionResetPayload(dim, resetTime));
    }

    public static void sendDimensionResetToAll(ResourceLocation dim, long resetTime) {
        PacketDistributor.sendToAllPlayers(new DimensionResetPayload(dim, resetTime));
    }

    public static void sendResetWarning(ServerPlayer player, int minutesRemaining, String message) {
        PacketDistributor.sendToPlayer(player, new ResetWarningPayload(minutesRemaining, message));
    }

    public static void sendResetWarningToAll(int minutesRemaining, String message) {
        PacketDistributor.sendToAllPlayers(new ResetWarningPayload(minutesRemaining, message));
    }

    public static void sendDimensionCleanup(ServerPlayer player, List<String> deletedPaths) {
        PacketDistributor.sendToPlayer(player, new DimensionCleanupPayload(deletedPaths));
    }
}
