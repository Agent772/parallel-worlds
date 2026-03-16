package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.registry.PWBlocks;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The portal block that fills the inside of a PW portal frame.
 * Non-solid, no collision. Handles teleportation when an entity steps inside.
 * Supports right-click cycling to change the target exploration dimension.
 */
public class PWPortalBlock extends Block {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    private static final VoxelShape X_SHAPE = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
    private static final VoxelShape Z_SHAPE = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);

    // Track portal contact time per player (UUID -> tick count)
    private static final Map<UUID, Integer> portalTickCounters = new ConcurrentHashMap<>();

    public PWPortalBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.Z ? Z_SHAPE : X_SHAPE;
    }

    // ── Right-click cycling ──

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        String cycleItemId = PWConfig.getPortalCycleItem();
        if (cycleItemId == null || cycleItemId.isEmpty()) {
            // Cycling is disabled — inform the player if they're trying with a likely cycle item
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Check if the player is holding the cycle item
        ResourceLocation cycleItemLoc = ResourceLocation.tryParse(cycleItemId);
        if (cycleItemLoc == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ResourceLocation heldItemLoc = stack.getItem().builtInRegistryHolder().key().location();
        if (!heldItemLoc.equals(cycleItemLoc)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Don't allow cycling in exploration dimensions (return portals just return)
        ServerLevel serverLevel = (ServerLevel) level;
        if (DimensionUtils.isExplorationDimension(serverLevel.dimension())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Find canonical position for this portal
        BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
        if (canonical == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Cycle to next dimension
        ResourceLocation newTarget = PortalTargetManager.cycleTarget(serverLevel, canonical);
        if (newTarget == null) {
            ((ServerPlayer) player).sendSystemMessage(
                    Component.translatable("parallelworlds.portal.cycling_disabled"), true);
            return ItemInteractionResult.FAIL;
        }

        // Show action bar message with dimension name
        Component targetName = DimensionColors.getDisplayName(newTarget);
        Component message = Component.translatable("parallelworlds.portal.target", targetName);
        ((ServerPlayer) player).sendSystemMessage(message, true);

        // Play cycling sound
        level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.5f, 1.2f);

        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    // ── Teleportation ──

    @Override
    @SuppressWarnings("deprecation")
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }

        UUID uuid = player.getUUID();
        int ticks = portalTickCounters.getOrDefault(uuid, 0) + 1;
        portalTickCounters.put(uuid, ticks);

        if (ticks < PWConfig.getPortalDelayTicks()) {
            return;
        }

        // Reset counter on successful teleport
        resetPortalCounter(uuid);

        ServerLevel serverLevel = (ServerLevel) level;
        boolean inExploration = DimensionUtils.isExplorationDimension(serverLevel.dimension());

        if (inExploration) {
            TeleportHandler.returnFromExploration(player);
        } else {
            // Look up the portal's target dimension via PortalTargetManager
            BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
            Optional<ServerLevel> targetLevel;

            if (canonical != null) {
                targetLevel = PortalTargetManager.resolveTargetLevel(level, canonical);
            } else {
                // Fallback: use the old 1:1 mapping
                var registrar = DimensionRegistrar.getInstance();
                targetLevel = registrar.getExplorationLevel(serverLevel.dimension());
            }

            targetLevel.ifPresent(target -> TeleportHandler.teleportToExploration(player, target));
        }
    }

    // ── Shape validation ──

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction.getAxis() != Direction.Axis.Y || direction.getAxis() == state.getValue(AXIS)) {
            Optional<PortalShape> shape = PortalShape.findPortalShape(level, pos);
            if (shape.isEmpty()) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // Clean up portal target when portal is destroyed
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
                if (canonical != null) {
                    PortalTargetManager.removeTarget(serverLevel, canonical);
                }
            }

            // Cascade check neighbors
            Direction.Axis axis = state.getValue(AXIS);
            for (Direction dir : Direction.values()) {
                if (dir.getAxis() == Direction.Axis.Y || dir.getAxis() == axis) {
                    BlockPos neighbor = pos.relative(dir);
                    BlockState neighborState = level.getBlockState(neighbor);
                    if (neighborState.is(this)) {
                        Optional<PortalShape> shape = PortalShape.findPortalShape(level, neighbor);
                        if (shape.isEmpty()) {
                            level.setBlock(neighbor, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ── Particles ──

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // Determine the target dimension color
        BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
        ResourceLocation target = null;
        if (canonical != null) {
            target = PortalTargetManager.getTarget(level, canonical);
        }

        Vector3f color;
        if (target != null) {
            color = DimensionColors.getParticleColor(target);
        } else {
            // Fallback: cyan
            color = DimensionColors.getParticleColor(ResourceLocation.withDefaultNamespace("overworld"));
        }

        DustParticleOptions dustOptions = new DustParticleOptions(color, 1.0f);

        for (int i = 0; i < 4; i++) {
            double x = pos.getX() + random.nextDouble();
            double y = pos.getY() + random.nextDouble();
            double z = pos.getZ() + random.nextDouble();
            double dx = (random.nextDouble() - 0.5) * 0.5;
            double dy = (random.nextDouble() - 0.5) * 0.5;
            double dz = (random.nextDouble() - 0.5) * 0.5;
            level.addParticle(dustOptions, x, y, z, dx, dy, dz);
        }
    }

    // ── Canonical position detection ──

    /**
     * Find the canonical (bottom-left) position of the portal this block belongs to.
     * Scans connected portal blocks to find the PortalShape and returns its bottom-left.
     */
    private static BlockPos findCanonicalPos(Level level, BlockPos portalBlockPos, Direction.Axis axis) {
        Optional<PortalShape> shape = PortalShape.findPortalShape(level, portalBlockPos);
        return shape.map(PortalShape::getBottomLeft).orElse(null);
    }

    /**
     * Called when a player leaves the portal area to reset their counter.
     */
    public static void resetPortalCounter(UUID playerId) {
        portalTickCounters.remove(playerId);
    }

    /**
     * Clear all portal counters — called on server shutdown.
     */
    public static void clearAll() {
        portalTickCounters.clear();
    }
}
