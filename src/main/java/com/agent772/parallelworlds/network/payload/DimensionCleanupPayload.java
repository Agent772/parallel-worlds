package com.agent772.parallelworlds.network.payload;

import com.agent772.parallelworlds.ParallelWorlds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record DimensionCleanupPayload(List<String> deletedDimensionPaths,
                                       List<String> activeDimensionPaths) implements CustomPacketPayload {

    public static final Type<DimensionCleanupPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParallelWorlds.MOD_ID, "dimension_cleanup"));

    public static final StreamCodec<FriendlyByteBuf, DimensionCleanupPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    DimensionCleanupPayload::deletedDimensionPaths,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    DimensionCleanupPayload::activeDimensionPaths,
                    DimensionCleanupPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
