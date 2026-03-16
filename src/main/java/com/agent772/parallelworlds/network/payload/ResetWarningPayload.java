package com.agent772.parallelworlds.network.payload;

import com.agent772.parallelworlds.ParallelWorlds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ResetWarningPayload(int minutesRemaining, String message) implements CustomPacketPayload {

    public static final Type<ResetWarningPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "reset_warning"));

    public static final StreamCodec<ByteBuf, ResetWarningPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ResetWarningPayload::minutesRemaining,
            ByteBufCodecs.STRING_UTF8, ResetWarningPayload::message,
            ResetWarningPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
