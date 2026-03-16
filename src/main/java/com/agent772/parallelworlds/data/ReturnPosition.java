package com.agent772.parallelworlds.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.TimeUnit;

/**
 * Serializable record of a player's position before entering an exploration dimension.
 */
public record ReturnPosition(BlockPos pos, ResourceLocation dimension, float yRot, float xRot, long timestamp) {

    private static final long EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(7);

    public ReturnPosition(BlockPos pos, ResourceLocation dimension, float yRot, float xRot) {
        this(pos, dimension, yRot, xRot, System.currentTimeMillis());
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > EXPIRY_MILLIS;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("dim", dimension.toString());
        tag.putFloat("yRot", yRot);
        tag.putFloat("xRot", xRot);
        tag.putLong("ts", timestamp);
        return tag;
    }

    public static ReturnPosition fromNbt(CompoundTag tag) {
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        ResourceLocation dim = ResourceLocation.parse(tag.getString("dim"));
        float yRot = tag.getFloat("yRot");
        float xRot = tag.getFloat("xRot");
        long ts = tag.getLong("ts");
        return new ReturnPosition(pos, dim, yRot, xRot, ts);
    }
}
