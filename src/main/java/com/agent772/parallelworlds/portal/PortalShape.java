package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.registry.PWBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects and validates a rectangular portal frame shape, similar to the Nether portal.
 * Supports X-axis and Z-axis orientations (vertical portals only).
 */
public final class PortalShape {
    private static final int MIN_WIDTH = 2;
    private static final int MIN_HEIGHT = 3;

    private static int getMaxSize() {
        int configured = PWConfig.getPortalMaxSize();
        return configured > 0 ? configured : 21;
    }

    private final LevelAccessor level;
    private final Direction.Axis axis;
    private final BlockPos bottomLeft;
    private final int width;
    private final int height;

    private PortalShape(LevelAccessor level, Direction.Axis axis, BlockPos bottomLeft, int width, int height) {
        this.level = level;
        this.axis = axis;
        this.bottomLeft = bottomLeft;
        this.width = width;
        this.height = height;
    }

    public Direction.Axis getAxis() {
        return axis;
    }

    public BlockPos getBottomLeft() {
        return bottomLeft;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Try to find a valid portal shape from a position inside the frame.
     * Attempts both X and Z axes.
     */
    public static Optional<PortalShape> findPortalShape(LevelAccessor level, BlockPos insidePos) {
        PortalShape shape = tryAxis(level, insidePos, Direction.Axis.X);
        if (shape != null && shape.isValid()) return Optional.of(shape);

        shape = tryAxis(level, insidePos, Direction.Axis.Z);
        if (shape != null && shape.isValid()) return Optional.of(shape);

        return Optional.empty();
    }

    @Nullable
    private static PortalShape tryAxis(LevelAccessor level, BlockPos insidePos, Direction.Axis axis) {
        // Determine the horizontal direction along the portal plane
        Direction rightDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction leftDir = rightDir.getOpposite();

        // Walk left to find the left edge (frame block)
        BlockPos leftEdge = insidePos;
        int maxSize = getMaxSize();
        for (int i = 1; i <= maxSize; i++) {
            BlockPos check = insidePos.relative(leftDir, i);
            if (isFrameBlock(level, check)) {
                break;
            }
            if (!isInsideBlock(level, check)) {
                return null; // Hit something that's neither frame nor air/portal
            }
            leftEdge = check;
        }

        // Walk down from leftEdge to find the bottom
        BlockPos bottomLeft = leftEdge;
        for (int i = 1; i <= maxSize; i++) {
            BlockPos check = leftEdge.below(i);
            if (isFrameBlock(level, check)) {
                break;
            }
            if (!isInsideBlock(level, check)) {
                return null;
            }
            bottomLeft = check;
        }

        // Verify there's a frame block below the bottom-left
        if (!isFrameBlock(level, bottomLeft.below())) {
            return null;
        }
        // Verify there's a frame block to the left of the bottom-left
        if (!isFrameBlock(level, bottomLeft.relative(leftDir))) {
            return null;
        }

        // Measure width by walking right from bottomLeft
        int width = 0;
        for (int i = 0; i < maxSize; i++) {
            BlockPos check = bottomLeft.relative(rightDir, i);
            if (isFrameBlock(level, check)) {
                break;
            }
            if (!isInsideBlock(level, check)) {
                return null;
            }
            width++;
        }

        if (width < MIN_WIDTH) return null;

        // Measure height by walking up from bottomLeft
        int height = 0;
        for (int i = 0; i < maxSize; i++) {
            BlockPos check = bottomLeft.above(i);
            if (isFrameBlock(level, check.relative(leftDir))) {
                // We're still along the left frame column — keep going
            } else {
                return null;
            }
            if (isFrameBlock(level, check)) {
                break;
            }
            if (!isInsideBlock(level, check)) {
                return null;
            }
            height++;
        }

        if (height < MIN_HEIGHT) return null;

        return new PortalShape(level, axis, bottomLeft, width, height);
    }

    /**
     * Validate the complete frame — all edges must be frame blocks,
     * and all interior positions must be empty (air or portal blocks).
     */
    public boolean isValid() {
        Direction rightDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction leftDir = rightDir.getOpposite();

        // Check bottom row (frame below the interior)
        for (int x = -1; x <= width; x++) {
            if (!isFrameBlock(level, bottomLeft.relative(rightDir, x).below())) {
                return false;
            }
        }

        // Check top row (frame above the interior)
        for (int x = -1; x <= width; x++) {
            if (!isFrameBlock(level, bottomLeft.relative(rightDir, x).above(height))) {
                return false;
            }
        }

        // Check left and right columns + interior
        for (int y = 0; y < height; y++) {
            // Left frame column
            if (!isFrameBlock(level, bottomLeft.above(y).relative(leftDir))) {
                return false;
            }
            // Right frame column
            if (!isFrameBlock(level, bottomLeft.above(y).relative(rightDir, width))) {
                return false;
            }
            // Interior must be empty
            for (int x = 0; x < width; x++) {
                BlockPos inner = bottomLeft.relative(rightDir, x).above(y);
                if (!isInsideBlock(level, inner)) {
                    return false;
                }
            }
        }

        // Check corners
        if (!isFrameBlock(level, bottomLeft.relative(leftDir).below())) return false;
        if (!isFrameBlock(level, bottomLeft.relative(rightDir, width).below())) return false;
        if (!isFrameBlock(level, bottomLeft.relative(leftDir).above(height))) return false;
        if (!isFrameBlock(level, bottomLeft.relative(rightDir, width).above(height))) return false;

        return true;
    }

    /**
     * Get all interior positions that should become portal blocks.
     */
    public List<BlockPos> getInnerPositions() {
        Direction rightDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        List<BlockPos> positions = new ArrayList<>(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                positions.add(bottomLeft.relative(rightDir, x).above(y));
            }
        }
        return positions;
    }


    /**
     * Returns true when the frame is valid AND every interior position contains a portal block.
     * Used by {@code updateShape} to decide whether to cascade-remove portal blocks:
     * if the shape is incomplete (some interior is air, e.g. a portal block was just broken)
     * every remaining portal block should also disappear, matching vanilla nether portal behaviour.
     */
    public boolean isCompletelyFilled() {
        Direction rightDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!level.getBlockState(bottomLeft.relative(rightDir, x).above(y)).is(PWBlocks.PW_PORTAL.get())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFrameBlock(LevelAccessor level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        String configFrame = PWConfig.getPortalFrameBlock();
        if (configFrame != null && !configFrame.isEmpty()) {
            ResourceLocation configLoc = ResourceLocation.tryParse(configFrame);
            if (configLoc != null) {
                ResourceLocation blockLoc = state.getBlock().builtInRegistryHolder().key().location();
                return blockLoc.equals(configLoc);
            }
        }
        // Fallback to glass
        return state.is(net.minecraft.world.level.block.Blocks.GLASS);
    }

    private static boolean isInsideBlock(LevelAccessor level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(PWBlocks.PW_PORTAL.get());
    }
}
