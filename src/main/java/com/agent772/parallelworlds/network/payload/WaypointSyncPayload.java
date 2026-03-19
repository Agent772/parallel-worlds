package com.agent772.parallelworlds.network.payload;

import com.agent772.parallelworlds.ParallelWorlds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from server to a specific client to create or remove a Xaero's Minimap
 * waypoint at the PW portal entrance in an exploration dimension.
 *
 * <ul>
 *   <li>{@code add = true}  — create waypoint at (x, y, z) in the given dim</li>
 *   <li>{@code add = false} — remove the portal waypoint for the given dim
 *       (x/y/z are ignored)</li>
 * </ul>
 */
public record WaypointSyncPayload(ResourceLocation dimId, int x, int y, int z, boolean add)
        implements CustomPacketPayload {

    public static final Type<WaypointSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "waypoint_sync"));

    public static final StreamCodec<ByteBuf, WaypointSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, WaypointSyncPayload::dimId,
            ByteBufCodecs.VAR_INT,          WaypointSyncPayload::x,
            ByteBufCodecs.VAR_INT,          WaypointSyncPayload::y,
            ByteBufCodecs.VAR_INT,          WaypointSyncPayload::z,
            ByteBufCodecs.BOOL,             WaypointSyncPayload::add,
            WaypointSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
