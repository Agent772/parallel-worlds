package com.agent772.parallelworlds.network.payload;

import com.agent772.parallelworlds.ParallelWorlds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DimensionResetPayload(ResourceLocation dimensionId, long resetTime) implements CustomPacketPayload {

    public static final Type<DimensionResetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "dimension_reset"));

    public static final StreamCodec<ByteBuf, DimensionResetPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, DimensionResetPayload::dimensionId,
            ByteBufCodecs.VAR_LONG, DimensionResetPayload::resetTime,
            DimensionResetPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
