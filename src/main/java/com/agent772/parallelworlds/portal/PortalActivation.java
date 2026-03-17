package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.registry.PWBlocks;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Handles portal activation when a player right-clicks a frame block
 * with the configured igniter item.
 */
public final class PortalActivation {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalActivation() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!PWConfig.isPortalEnabled()) return;

        Level level = event.getLevel();

        // Block portal building in exploration dimensions when config disables it
        if (level instanceof ServerLevel serverLevel
                && DimensionUtils.isExplorationDimension(serverLevel.dimension())
                && !PWConfig.isPortalBuildingInExplorationEnabled()) {
            return;
        }

        BlockPos clickedPos = event.getPos();
        BlockState clickedState = level.getBlockState(clickedPos);

        // Must click on a frame block
        if (!isFrameBlock(clickedState)) return;

        // Must be holding the igniter item
        ItemStack heldItem = event.getItemStack();
        if (!isIgniterItem(heldItem)) return;

        // Block portal ignition in non-exploration dimensions for non-ops when config requires it
        // This check is intentionally after the frame+igniter guards so normal block placement is unaffected.
        if (level instanceof ServerLevel serverLevel
                && !DimensionUtils.isExplorationDimension(serverLevel.dimension())
                && PWConfig.isPortalIgniteOpOnly()
                && event.getEntity() instanceof ServerPlayer sp
                && !serverLevel.getServer().getPlayerList().isOp(sp.getGameProfile())) {
            sp.sendSystemMessage(Component.translatable("parallelworlds.portal.ignite.no_permission"));
            event.setCanceled(true);
            return;
        }

        // Try to find a portal shape from the face the player clicked
        Direction face = event.getFace();
        if (face == null) return;

        // The portal interior is on the clicked face side of the frame block
        BlockPos insidePos = clickedPos.relative(face);

        // Also try adjacent to clicked block in case they clicked the inner side
        Optional<PortalShape> shape = PortalShape.findPortalShape(level, insidePos);

        if (shape.isEmpty()) {
            // Try from the clicked pos itself in case it borders the interior
            for (Direction dir : Direction.values()) {
                if (dir.getAxis() == Direction.Axis.Y) continue;
                shape = PortalShape.findPortalShape(level, clickedPos.relative(dir));
                if (shape.isPresent()) break;
            }
        }

        if (shape.isEmpty()) return;

        // Fill the portal
        PortalShape validShape = shape.get();
        List<BlockPos> innerPositions = validShape.getInnerPositions();
        Direction.Axis axis = validShape.getAxis();

        BlockState portalState = PWBlocks.PW_PORTAL.get().defaultBlockState()
                .setValue(PWPortalBlock.AXIS, axis);

        for (BlockPos inner : innerPositions) {
            level.setBlock(inner, portalState, 3);
        }

        // Play activation sound
        level.playSound(null, clickedPos, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 1.0f, 1.0f);

        // Register default portal target (first enabled dimension)
        // Only register for portals in non-exploration dimensions
        if (level instanceof ServerLevel serverLevel && !DimensionUtils.isExplorationDimension(serverLevel.dimension())) {
            var dims = PortalTargetManager.getOrderedDimensions();
            if (!dims.isEmpty()) {
                PortalTargetManager.setTarget(serverLevel, validShape.getBottomLeft(), dims.get(0));
            }
        }

        // Consume durability on the igniter (like flint_and_steel)
        if (!event.getEntity().getAbilities().instabuild) {
            heldItem.hurtAndBreak(1, event.getEntity(), event.getEntity().getEquipmentSlotForItem(heldItem));
        }

        event.setCanceled(true);
        LOGGER.debug("Portal activated at {} ({}x{}, axis={})",
                validShape.getBottomLeft(), validShape.getWidth(), validShape.getHeight(), axis);
    }

    private static boolean isFrameBlock(BlockState state) {
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

    private static boolean isIgniterItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String configIgniter = PWConfig.getPortalIgniterItem();
        if (configIgniter != null && !configIgniter.isEmpty()) {
            ResourceLocation configLoc = ResourceLocation.tryParse(configIgniter);
            if (configLoc != null) {
                ResourceLocation itemLoc = stack.getItem().builtInRegistryHolder().key().location();
                return itemLoc.equals(configLoc);
            }
        }
        // Default: flint_and_steel
        return stack.is(net.minecraft.world.item.Items.FLINT_AND_STEEL);
    }
}
