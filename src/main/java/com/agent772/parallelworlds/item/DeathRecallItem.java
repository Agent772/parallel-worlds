package com.agent772.parallelworlds.item;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.registry.PWItems;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.List;

/**
 * One-time use item given to players on death in an exploration dimension.
 * Using it (hold right-click for the configured charge duration) teleports the player
 * back to their exact death location, bypassing cooldowns and teleport-block settings.
 * The token expires after a configurable time and vanishes from inventory.
 */
public class DeathRecallItem extends Item {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final String TAG_DIM          = "recall_dim";
    static final String TAG_X            = "recall_x";
    static final String TAG_Y            = "recall_y";
    static final String TAG_Z            = "recall_z";
    static final String TAG_YROT         = "recall_yRot";
    static final String TAG_XROT         = "recall_xRot";
    // Baked at creation time so tooltip/behaviour work client-side without server config access
    static final String TAG_EXPIRES_AT   = "recall_expires_at";   // epoch ms, -1 = never
    static final String TAG_CHARGE_TICKS = "recall_charge_ticks"; // 0 = instant

    public DeathRecallItem(Properties properties) {
        super(properties);
    }

    // ── Factory ──

    /**
     * Build an ItemStack pre-loaded with the given death location and baked config values.
     * Must be called server-side (reads PWConfig).
     */
    public static ItemStack createFor(ResourceLocation dimension, BlockPos deathPos, float yRot, float xRot) {
        ItemStack stack = new ItemStack(PWItems.DEATH_RECALL_TOKEN.get());
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIM,  dimension.toString());
        tag.putInt(TAG_X,       deathPos.getX());
        tag.putInt(TAG_Y,       deathPos.getY());
        tag.putInt(TAG_Z,       deathPos.getZ());
        tag.putFloat(TAG_YROT,  yRot);
        tag.putFloat(TAG_XROT,  xRot);

        int expireSecs = PWConfig.getDeathRecallExpireSeconds();
        tag.putLong(TAG_EXPIRES_AT, expireSecs > 0
                ? System.currentTimeMillis() + expireSecs * 1000L
                : -1L);
        tag.putInt(TAG_CHARGE_TICKS, PWConfig.getDeathRecallChargeTicks());

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    // ── Use ──

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.pass(stack);

        if (sp.getCooldowns().isOnCooldown(this)) return InteractionResultHolder.fail(stack);

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.copyTag().contains(DeathRecallItem.TAG_DIM)) {
            sp.displayClientMessage(
                    Component.translatable("parallelworlds.recall.invalid")
                            .withStyle(ChatFormatting.RED), false);
            return InteractionResultHolder.fail(stack);
        }

        CompoundTag tag = customData.copyTag();
        ResourceLocation dimLoc = ResourceLocation.tryParse(tag.getString(DeathRecallItem.TAG_DIM));
        if (dimLoc == null) {
            sp.displayClientMessage(
                    Component.translatable("parallelworlds.recall.invalid")
                            .withStyle(ChatFormatting.RED), false);
            return InteractionResultHolder.fail(stack);
        }

        int chargeTicks = getChargeTicks(stack);
        BlockPos deathPos = new BlockPos(tag.getInt(TAG_X), tag.getInt(TAG_Y), tag.getInt(TAG_Z));
        float yRot = tag.getFloat(TAG_YROT);
        float xRot = tag.getFloat(TAG_XROT);

        if (chargeTicks <= 0) {
            // Instant activate
            doRecall(sp, stack);
        } else {
            // Start stand-still countdown via tracker
            DeathRecallTracker.startRecall(sp, dimLoc, deathPos, yRot, xRot, chargeTicks);
        }
        return InteractionResultHolder.success(stack);
    }

    // ── Expiry ──

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_EXPIRES_AT)) return;
        long expiresAt = tag.getLong(TAG_EXPIRES_AT);
        if (expiresAt < 0) return; // never expire
        if (System.currentTimeMillis() >= expiresAt) {
            stack.shrink(stack.getCount());
            if (entity instanceof ServerPlayer player) {
                player.displayClientMessage(
                        Component.translatable("parallelworlds.recall.expired")
                                .withStyle(ChatFormatting.GRAY), false);
            }
        }
    }

    // ── Tooltip ──

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> components, TooltipFlag flag) {
        components.add(Component.translatable("parallelworlds.recall.tooltip.desc")
                .withStyle(ChatFormatting.GRAY));
        components.add(Component.translatable("parallelworlds.recall.tooltip.single_use")
                .withStyle(ChatFormatting.RED));

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        CompoundTag tag = data.copyTag();

        // Target dimension
        if (tag.contains(TAG_DIM)) {
            components.add(Component.translatable("parallelworlds.recall.tooltip.target",
                            tag.getString(TAG_DIM))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // Charge indicator (no time shown — it's configurable server-side)
        if (tag.contains(TAG_CHARGE_TICKS) && tag.getInt(TAG_CHARGE_TICKS) > 0) {
            components.add(Component.translatable("parallelworlds.recall.tooltip.charge")
                    .withStyle(ChatFormatting.AQUA));
        }
        // Expiry countdown
        if (tag.contains(TAG_EXPIRES_AT)) {
            long expiresAt = tag.getLong(TAG_EXPIRES_AT);
            if (expiresAt >= 0) {
                long remainMs = expiresAt - System.currentTimeMillis();
                if (remainMs > 0) {
                    long secs = remainMs / 1000;
                    String timeStr = secs >= 60 ? (secs / 60) + "m " + (secs % 60) + "s" : secs + "s";
                    components.add(Component.translatable("parallelworlds.recall.tooltip.expires", timeStr)
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }

    // ── Internal ──

    private void doRecall(ServerPlayer player, ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.copyTag().contains(TAG_DIM)) {
            player.displayClientMessage(
                    Component.translatable("parallelworlds.recall.invalid")
                            .withStyle(ChatFormatting.RED), false);
            return;
        }

        CompoundTag tag = customData.copyTag();
        ResourceLocation dimLoc = ResourceLocation.tryParse(tag.getString(TAG_DIM));
        if (dimLoc == null) {
            player.displayClientMessage(
                    Component.translatable("parallelworlds.recall.invalid")
                            .withStyle(ChatFormatting.RED), false);
            return;
        }

        // Consume the item — spent regardless of outcome
        stack.shrink(1);

        // Apply per-type cooldown so other tokens in inventory are grayed out too
        int cooldownTicks = PWConfig.getDeathRecallCooldownSeconds() * 20;
        if (cooldownTicks > 0) {
            player.getCooldowns().addCooldown(this, cooldownTicks);
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
        ServerLevel targetLevel = player.server.getLevel(dimKey);
        if (targetLevel == null) {
            player.displayClientMessage(
                    Component.translatable("parallelworlds.recall.dim_gone")
                            .withStyle(ChatFormatting.RED), false);
            return;
        }

        BlockPos deathPos = new BlockPos(tag.getInt(TAG_X), tag.getInt(TAG_Y), tag.getInt(TAG_Z));
        float yRot = tag.getFloat(TAG_YROT);
        float xRot = tag.getFloat(TAG_XROT);
        TeleportHandler.teleportToDeathLocation(player, targetLevel, deathPos, yRot, xRot);
    }

    private static int getChargeTicks(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_CHARGE_TICKS) ? tag.getInt(TAG_CHARGE_TICKS) : 0;
    }
}
