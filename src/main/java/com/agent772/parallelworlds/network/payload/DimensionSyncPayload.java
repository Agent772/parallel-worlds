package com.agent772.parallelworlds.network.payload;

import com.agent772.parallelworlds.ParallelWorlds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DimensionSyncPayload(ResourceLocation dimensionId, boolean exists) implements CustomPacketPayload {

    public static final Type<DimensionSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "dimension_sync"));

    public static final StreamCodec<ByteBuf, DimensionSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, DimensionSyncPayload::dimensionId,
            ByteBufCodecs.BOOL, DimensionSyncPayload::exists,
            DimensionSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
