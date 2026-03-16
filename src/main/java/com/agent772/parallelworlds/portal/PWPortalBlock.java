package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.data.ReturnPosition;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
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
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The portal block that fills the inside of a PW portal frame.
 * Non-solid, no collision. Implements the vanilla {@link Portal} interface
 * to get nether-portal-style overlay animation and delayed teleportation.
 */
public class PWPortalBlock extends Block implements Portal {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    // Rate-limit portal destination preview: show at most once per 60 ticks (3 s) per player
    private static final Map<UUID, Long> lastPreviewTick = new ConcurrentHashMap<>();

    private static final VoxelShape X_SHAPE = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
    private static final VoxelShape Z_SHAPE = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);

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

    // ── Portal interface: nether-style behavior ──

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity.canUsePortal(false)) {
            entity.setAsInsidePortal(this, pos);
        }
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            long now = level.getGameTime();
            Long last = lastPreviewTick.get(player.getUUID());
            if (last == null || now - last >= 60L) {
                lastPreviewTick.put(player.getUUID(), now);
                showPortalDestination(player, (ServerLevel) level, pos, state);
            }
        }
    }

    private static void showPortalDestination(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        Direction.Axis axis = state.hasProperty(AXIS) ? state.getValue(AXIS) : Direction.Axis.X;

        if (DimensionUtils.isExplorationDimension(level.dimension())) {
            // Returning – show the base dimension the player came from
            PWSavedData data = PWSavedData.get(level.getServer());
            ResourceLocation homeDim = data.getPlayerEntryPortalGlobalPos(player.getUUID())
                    .map(gp -> gp.dimension().location())
                    .orElseGet(() -> Level.OVERWORLD.location());
            Component dimName = DimensionColors.getHomeDimensionName(homeDim);
            player.sendSystemMessage(
                    Component.translatable("parallelworlds.portal.target", dimName), true);
        } else {
            // Entering – show the target exploration dimension
            BlockPos canonical = findCanonicalPos(level, pos, axis);
            if (canonical == null) return;
            ResourceLocation targetDim = PortalTargetManager.getTarget(level, canonical);
            if (targetDim == null) return;
            Component dimName = DimensionColors.getDisplayName(targetDim);
            player.sendSystemMessage(
                    Component.translatable("parallelworlds.portal.target", dimName), true);
        }
    }

    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        if (entity instanceof Player) {
            return PWConfig.getPortalDelayTicks();
        }
        return 0;
    }

    @Override
    public Portal.Transition getLocalTransition() {
        return Portal.Transition.CONFUSION;
    }

    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) return null;

        boolean inExploration = DimensionUtils.isExplorationDimension(level.dimension());

        if (inExploration) {
            return getReturnDestination(level, player);
        } else {
            return getExplorationDestination(level, player, pos);
        }
    }

    // ── Destination resolution ──

    @Nullable
    private DimensionTransition getExplorationDestination(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Direction.Axis currentAxis = state.hasProperty(AXIS) ? state.getValue(AXIS) : Direction.Axis.X;

        // Find canonical position for this portal
        BlockPos canonical = findCanonicalPos(level, pos, currentAxis);
        if (canonical == null) canonical = pos;

        // Resolve target exploration level
        Optional<ServerLevel> targetOpt = PortalTargetManager.resolveTargetLevel(level, canonical);
        if (targetOpt.isEmpty()) {
            // Fallback: try 1:1 mapping
            var registrar = DimensionRegistrar.getInstance();
            targetOpt = registrar.getExplorationLevel(level.dimension());
        }
        if (targetOpt.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("parallelworlds.teleport.failed")
                            .withStyle(ChatFormatting.RED), false);
            return null;
        }

        ServerLevel target = targetOpt.get();
        PWSavedData data = PWSavedData.get(level.getServer());

        // Get or build the shared portal in the exploration dimension
        ResourceLocation explorationDimLoc = target.dimension().location();
        BlockPos explorationPortalPos;
        Direction.Axis explorationAxis;

        Optional<BlockPos> existingPos = data.getExplorationPortalPos(explorationDimLoc);
        Optional<Direction.Axis> existingAxis = data.getExplorationPortalAxis(explorationDimLoc);

        if (existingPos.isPresent() && existingAxis.isPresent()) {
            explorationPortalPos = existingPos.get();
            explorationAxis = existingAxis.get();

            // Verify portal is still intact, rebuild if not
            if (!PortalBuilder.isPortalIntact(target, explorationPortalPos, explorationAxis)) {
                LOGGER.info("Exploration portal at {} was destroyed, rebuilding", explorationPortalPos);
                PortalBuilder.buildPortal(target, explorationPortalPos, explorationAxis);
            }
        } else {
            // First entry: find safe position and build portal
            BlockPos safePos = TeleportHandler.resolveSpawnPosition(target);
            explorationAxis = currentAxis;
            explorationPortalPos = PortalBuilder.buildPortal(target, safePos, explorationAxis);
            data.saveExplorationPortal(explorationDimLoc, explorationPortalPos, explorationAxis);
            LOGGER.info("Created shared exploration portal in {} at {} (axis={})",
                    explorationDimLoc, explorationPortalPos, explorationAxis);
        }

        // Save player's entry portal info (so they return to this specific overworld portal)
        PortalShape portalShape = PortalShape.findPortalShape(level, pos).orElse(null);
        BlockPos entryCanonical = portalShape != null ? portalShape.getBottomLeft() : canonical;
        Direction.Axis entryAxis = portalShape != null ? portalShape.getAxis() : currentAxis;
        data.savePlayerEntryPortal(player.getUUID(), level.dimension().location(), entryCanonical, entryAxis);

        // Save return position for command-based return (/pw return)
        ReturnPosition returnPos = new ReturnPosition(
                player.blockPosition(),
                level.dimension().location(),
                player.getYRot(),
                player.getXRot()
        );
        TeleportHandler.saveReturnPosition(player, returnPos);

        TeleportHandler.dismountBeforeTeleport(player);
        TeleportHandler.clearDangerousEffects(player);

        // Calculate destination position (center of exploration portal)
        Vec3 destPos = PortalBuilder.getPortalCenter(explorationPortalPos, explorationAxis);

        return new DimensionTransition(
                target, destPos, Vec3.ZERO,
                player.getYRot(), player.getXRot(),
                DimensionTransition.PLAY_PORTAL_SOUND.then(e -> {
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(
                                Component.translatable("parallelworlds.teleport.success")
                                        .withStyle(ChatFormatting.GREEN), false);
                    }
                }).then(DimensionTransition.PLACE_PORTAL_TICKET)
        );
    }

    @Nullable
    private DimensionTransition getReturnDestination(ServerLevel level, ServerPlayer player) {
        PWSavedData data = PWSavedData.get(level.getServer());

        // Look up the player's entry portal
        Optional<GlobalPos> entryGp = data.getPlayerEntryPortalGlobalPos(player.getUUID());
        Optional<Direction.Axis> entryAxis = data.getPlayerEntryPortalAxis(player.getUUID());

        ServerLevel returnLevel;
        Vec3 destPos;

        if (entryGp.isPresent() && entryAxis.isPresent()) {
            returnLevel = level.getServer().getLevel(entryGp.get().dimension());
            if (returnLevel == null) {
                returnLevel = level.getServer().overworld();
                destPos = Vec3.atBottomCenterOf(returnLevel.getSharedSpawnPos());
            } else {
                destPos = PortalBuilder.getPortalCenter(entryGp.get().pos(), entryAxis.get());
            }
        } else {
            // Fallback: use return position from TeleportHandler
            Optional<ReturnPosition> retOpt = TeleportHandler.getReturnPosition(player.getUUID());
            if (retOpt.isPresent()) {
                ReturnPosition ret = retOpt.get();
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ret.dimension());
                returnLevel = level.getServer().getLevel(dimKey);
                if (returnLevel == null) returnLevel = level.getServer().overworld();
                destPos = Vec3.atBottomCenterOf(ret.pos());
            } else {
                // Emergency: world spawn
                returnLevel = level.getServer().overworld();
                destPos = Vec3.atBottomCenterOf(returnLevel.getSharedSpawnPos());
            }
        }

        if (PWConfig.isClearInventoryOnReturn()) {
            player.getInventory().clearContent();
        }

        TeleportHandler.dismountBeforeTeleport(player);
        TeleportHandler.clearDangerousEffects(player);

        return new DimensionTransition(
                returnLevel, destPos, Vec3.ZERO,
                player.getYRot(), player.getXRot(),
                DimensionTransition.PLAY_PORTAL_SOUND.then(e -> {
                    if (e instanceof ServerPlayer sp) {
                        TeleportHandler.removeReturnPosition(sp);
                        PWSavedData.get(sp.server).clearPlayerEntryPortal(sp.getUUID());
                        sp.displayClientMessage(
                                Component.translatable("parallelworlds.teleport.returned")
                                        .withStyle(ChatFormatting.GREEN), false);
                    }
                }).then(DimensionTransition.PLACE_PORTAL_TICKET)
        );
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
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ResourceLocation cycleItemLoc = ResourceLocation.tryParse(cycleItemId);
        if (cycleItemLoc == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ResourceLocation heldItemLoc = stack.getItem().builtInRegistryHolder().key().location();
        if (!heldItemLoc.equals(cycleItemLoc)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Don't allow cycling in exploration dimensions
        ServerLevel serverLevel = (ServerLevel) level;
        if (DimensionUtils.isExplorationDimension(serverLevel.dimension())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
        if (canonical == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        ResourceLocation newTarget = PortalTargetManager.cycleTarget(serverLevel, canonical);
        if (newTarget == null) {
            ((ServerPlayer) player).sendSystemMessage(
                    Component.translatable("parallelworlds.portal.cycling_disabled"), true);
            return ItemInteractionResult.FAIL;
        }

        Component targetName = DimensionColors.getDisplayName(newTarget);
        Component message = Component.translatable("parallelworlds.portal.target", targetName);
        ((ServerPlayer) player).sendSystemMessage(message, true);

        level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.5f, 1.2f);

        return ItemInteractionResult.sidedSuccess(level.isClientSide);
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
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
                if (canonical != null) {
                    PortalTargetManager.removeTarget(serverLevel, canonical);
                }
            }

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
        BlockPos canonical = findCanonicalPos(level, pos, state.getValue(AXIS));
        ResourceLocation target = null;
        if (canonical != null) {
            target = PortalTargetManager.getTarget(level, canonical);
        }

        Vector3f color;
        if (target != null) {
            color = DimensionColors.getParticleColor(target);
        } else {
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

    private static BlockPos findCanonicalPos(Level level, BlockPos portalBlockPos, Direction.Axis axis) {
        Optional<PortalShape> shape = PortalShape.findPortalShape(level, portalBlockPos);
        return shape.map(PortalShape::getBottomLeft).orElse(null);
    }

    /**
     * No-op — portal tick counting is now handled by vanilla PortalProcessor.
     */
    public static void clearAll() {
        // Nothing to clear — PortalProcessor is per-entity and managed by vanilla
    }
}
