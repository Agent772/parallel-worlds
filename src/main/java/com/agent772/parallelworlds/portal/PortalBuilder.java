package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.registry.PWBlocks;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Builds physical portal structures (frame + portal blocks) in the world.
 * Used to create the shared entry portal in exploration dimensions.
 */
public final class PortalBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int INTERIOR_WIDTH = 2;
    public static final int INTERIOR_HEIGHT = 3;

    private PortalBuilder() {}

    /**
     * Build a portal structure. The bottom-left interior corner will be at {@code base}.
     * Clears space for the interior, places frame blocks and portal blocks.
     *
     * @param level the level to build in
     * @param base  the bottom-left corner of the portal interior (feet level)
     * @param axis  the portal axis (X = runs east-west, Z = runs north-south)
     * @return the bottom-left position of the portal interior
     */
    public static BlockPos buildPortal(ServerLevel level, BlockPos base, Direction.Axis axis) {
        Direction rightDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction leftDir = rightDir.getOpposite();

        Block frameBlock = resolveFrameBlock();
        BlockState frameState = frameBlock.defaultBlockState();
        BlockState portalState = PWBlocks.PW_PORTAL.get().defaultBlockState()
                .setValue(PWPortalBlock.AXIS, axis);

        // Clear interior space
        for (int y = 0; y < INTERIOR_HEIGHT; y++) {
            for (int w = 0; w < INTERIOR_WIDTH; w++) {
                level.setBlockAndUpdate(base.relative(rightDir, w).above(y), Blocks.AIR.defaultBlockState());
            }
        }

        // Ensure solid ground under the portal
        for (int w = -1; w <= INTERIOR_WIDTH; w++) {
            BlockPos groundPos = base.relative(rightDir, w).below();
            if (!level.getBlockState(groundPos).isSolid()) {
                level.setBlockAndUpdate(groundPos, frameState);
            }
        }

        // Bottom frame row (including corners)
        for (int w = -1; w <= INTERIOR_WIDTH; w++) {
            level.setBlockAndUpdate(base.relative(rightDir, w).below(), frameState);
        }

        // Top frame row (including corners)
        for (int w = -1; w <= INTERIOR_WIDTH; w++) {
            level.setBlockAndUpdate(base.relative(rightDir, w).above(INTERIOR_HEIGHT), frameState);
        }

        // Left column
        for (int y = 0; y < INTERIOR_HEIGHT; y++) {
            level.setBlockAndUpdate(base.relative(leftDir).above(y), frameState);
        }

        // Right column
        for (int y = 0; y < INTERIOR_HEIGHT; y++) {
            level.setBlockAndUpdate(base.relative(rightDir, INTERIOR_WIDTH).above(y), frameState);
        }

        // Fill interior with portal blocks
        for (int y = 0; y < INTERIOR_HEIGHT; y++) {
            for (int w = 0; w < INTERIOR_WIDTH; w++) {
                level.setBlockAndUpdate(base.relative(rightDir, w).above(y), portalState);
            }
        }

        // Clear 1 block of air in front and behind the portal for walkability
        Direction perpendicular = axis == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
        for (int side = 0; side < 2; side++) {
            Direction dir = side == 0 ? perpendicular : perpendicular.getOpposite();
            for (int y = 0; y < INTERIOR_HEIGHT; y++) {
                for (int w = 0; w < INTERIOR_WIDTH; w++) {
                    BlockPos clearPos = base.relative(rightDir, w).above(y).relative(dir);
                    BlockState existing = level.getBlockState(clearPos);
                    if (!existing.isAir() && existing.isSolid()) {
                        level.setBlockAndUpdate(clearPos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            // Ensure ground in front/behind
            for (int w = 0; w < INTERIOR_WIDTH; w++) {
                BlockPos groundPos = base.relative(rightDir, w).relative(dir).below();
                if (!level.getBlockState(groundPos).isSolid()) {
                    level.setBlockAndUpdate(groundPos, frameState);
                }
            }
        }

        LOGGER.info("Built portal at {} (axis={})", base, axis);
        return base;
    }

    /**
     * Calculate the center position inside a portal for player placement.
     */
    public static Vec3 getPortalCenter(BlockPos bottomLeft, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return new Vec3(
                    bottomLeft.getX() + INTERIOR_WIDTH / 2.0,
                    bottomLeft.getY(),
                    bottomLeft.getZ() + 0.5
            );
        } else {
            return new Vec3(
                    bottomLeft.getX() + 0.5,
                    bottomLeft.getY(),
                    bottomLeft.getZ() + INTERIOR_WIDTH / 2.0
            );
        }
    }

    /**
     * Check whether a portal still exists at the stored position.
     */
    public static boolean isPortalIntact(ServerLevel level, BlockPos bottomLeft, Direction.Axis axis) {
        BlockState state = level.getBlockState(bottomLeft);
        return state.is(PWBlocks.PW_PORTAL.get())
                && state.getValue(PWPortalBlock.AXIS) == axis;
    }

    private static Block resolveFrameBlock() {
        String configFrame = PWConfig.getPortalFrameBlock();
        if (configFrame != null && !configFrame.isEmpty()) {
            ResourceLocation frameLoc = ResourceLocation.tryParse(configFrame);
            if (frameLoc != null) {
                Block block = BuiltInRegistries.BLOCK.get(frameLoc);
                if (block != Blocks.AIR) {
                    return block;
                }
            }
        }
        return Blocks.GLASS;
    }
}
