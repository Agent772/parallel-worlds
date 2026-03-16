package com.agent772.parallelworlds.restriction;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Enforces configurable restrictions on ender chests, tagged items/blocks, specific
 * items/blocks, or all items/blocks (wildcard *) inside exploration dimensions.
 * <p>
 * Config entries support three formats:
 * <ul>
 *   <li>{@code *} — match everything</li>
 *   <li>{@code #namespace:tag} — match by tag</li>
 *   <li>{@code namespace:id} — match a specific item/block</li>
 * </ul>
 */
public final class RestrictionHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RestrictionHandler() {}

    // ─── Block interaction (ender chest right-click + restricted blocks) ───

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) return;

        BlockState state = event.getLevel().getBlockState(event.getPos());

        // Ender chest check (dedicated config key)
        if (state.is(Blocks.ENDER_CHEST)) {
            PWConfigSpec.RestrictionMode mode = PWConfig.getEnderChestMode();
            if (mode == PWConfigSpec.RestrictionMode.BLOCK) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.ender_chest.blocked"), true);
                event.setCanceled(true);
                return;
            }
            if (mode == PWConfigSpec.RestrictionMode.WARN) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.ender_chest.warning"), true);
                LOGGER.info("Player {} used ender chest in exploration dimension {}",
                        player.getName().getString(),
                        player.level().dimension().location());
                return;
            }
            return;
        }

        // Restricted block interaction check
        if (isBlockRestricted(state)) {
            PWConfigSpec.RestrictionMode mode = PWConfig.getRestrictedItemMode();
            if (mode == PWConfigSpec.RestrictionMode.BLOCK) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.blocked"), true);
                event.setCanceled(true);
                return;
            }
            if (mode == PWConfigSpec.RestrictionMode.WARN) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.warning"), true);
                LOGGER.info("Player {} interacted with restricted block {} in exploration dimension {}",
                        player.getName().getString(),
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                        player.level().dimension().location());
            }
        }
    }

    // ─── Item right-click ───

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) return;

        ItemStack stack = event.getItemStack();
        if (isItemRestricted(stack)) {
            PWConfigSpec.RestrictionMode mode = PWConfig.getRestrictedItemMode();
            if (mode == PWConfigSpec.RestrictionMode.BLOCK) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.blocked"), true);
                event.setCanceled(true);
                return;
            }
            if (mode == PWConfigSpec.RestrictionMode.WARN) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.warning"), true);
                LOGGER.info("Player {} used restricted item {} in exploration dimension {}",
                        player.getName().getString(),
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        player.level().dimension().location());
            }
        }
    }

    // ─── Block placement ───

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) return;
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) return;

        BlockState placedState = event.getPlacedBlock();

        // Ender chest placement (dedicated config key)
        if (placedState.is(Blocks.ENDER_CHEST)) {
            PWConfigSpec.RestrictionMode mode = PWConfig.getEnderChestMode();
            if (mode == PWConfigSpec.RestrictionMode.BLOCK) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.ender_chest.blocked"), true);
                event.setCanceled(true);
                return;
            }
            if (mode == PWConfigSpec.RestrictionMode.WARN) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.ender_chest.warning"), true);
                LOGGER.info("Player {} placed ender chest in exploration dimension {}",
                        player.getName().getString(),
                        player.level().dimension().location());
                return;
            }
        }

        // Restricted block placement
        if (isBlockRestricted(placedState)) {
            PWConfigSpec.RestrictionMode mode = PWConfig.getRestrictedItemMode();
            if (mode == PWConfigSpec.RestrictionMode.BLOCK) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.blocked"), true);
                event.setCanceled(true);
                return;
            }
            if (mode == PWConfigSpec.RestrictionMode.WARN) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.restriction.item.warning"), true);
                LOGGER.info("Player {} placed restricted block {} in exploration dimension {}",
                        player.getName().getString(),
                        BuiltInRegistries.BLOCK.getKey(placedState.getBlock()),
                        player.level().dimension().location());
            }
        }
    }

    // ─── Matching logic ───

    /**
     * Check whether an item matches any entry in the restrictedItems config list.
     * Supports: {@code *} (all), {@code #namespace:tag}, {@code namespace:id*} (prefix wildcard),
     * {@code namespace:id} (exact match).
     */
    private static boolean isItemRestricted(ItemStack stack) {
        if (stack.isEmpty()) return false;
        List<String> entries = PWConfig.getRestrictedItems();
        if (entries == null || entries.isEmpty()) return false;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemIdStr = itemId.toString();

        for (String entry : entries) {
            if (entry.equals("*")) return true;

            if (entry.startsWith("#")) {
                ResourceLocation tagLoc = ResourceLocation.tryParse(entry.substring(1));
                if (tagLoc != null && stack.is(TagKey.create(Registries.ITEM, tagLoc))) {
                    return true;
                }
            } else if (entry.endsWith("*")) {
                String prefix = entry.substring(0, entry.length() - 1);
                if (itemIdStr.startsWith(prefix)) {
                    return true;
                }
            } else {
                if (itemIdStr.equals(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check whether a block state matches any entry in the restrictedBlocks config list.
     * Supports: {@code *} (all), {@code #namespace:tag}, {@code namespace:id*} (prefix wildcard),
     * {@code namespace:id} (exact match).
     */
    private static boolean isBlockRestricted(BlockState state) {
        List<String> entries = PWConfig.getRestrictedBlocks();
        if (entries == null || entries.isEmpty()) return false;

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockIdStr = blockId.toString();

        for (String entry : entries) {
            if (entry.equals("*")) return true;

            if (entry.startsWith("#")) {
                ResourceLocation tagLoc = ResourceLocation.tryParse(entry.substring(1));
                if (tagLoc != null && state.is(TagKey.create(Registries.BLOCK, tagLoc))) {
                    return true;
                }
            } else if (entry.endsWith("*")) {
                String prefix = entry.substring(0, entry.length() - 1);
                if (blockIdStr.startsWith(prefix)) {
                    return true;
                }
            } else {
                if (blockIdStr.equals(entry)) {
                    return true;
                }
            }
        }
        return false;
    }
}
