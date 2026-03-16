package com.agent772.parallelworlds.item;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active Death Recall countdowns. Players must stand still for the full
 * charge duration; any movement cancels the recall. Checked every server tick.
 */
public final class DeathRecallTracker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Maximum distance (squared) a player can drift without cancelling the recall. */
    private static final double MOVE_THRESHOLD_SQ = 0.09; // ~0.3 blocks in any direction

    private record PendingRecall(
            Vec3 startPos,
            ResourceLocation dim,
            BlockPos deathPos,
            float deathYRot,
            float deathXRot,
            int ticksRemaining
    ) {
        PendingRecall decremented() {
            return new PendingRecall(startPos, dim, deathPos, deathYRot, deathXRot, ticksRemaining - 1);
        }
    }

    private static final Map<UUID, PendingRecall> pending = new ConcurrentHashMap<>();

    private DeathRecallTracker() {}

    /** Register (or restart) the recall countdown for a player. */
    public static void startRecall(ServerPlayer player, ResourceLocation dim, BlockPos deathPos,
                                   float yRot, float xRot, int chargeTicks) {
        pending.put(player.getUUID(), new PendingRecall(
                player.position(), dim, deathPos, yRot, xRot, chargeTicks));
        int secs = (int) Math.ceil(chargeTicks / 20.0);
        player.displayClientMessage(
                Component.translatable("parallelworlds.recall.charging", secs)
                        .withStyle(ChatFormatting.YELLOW), true);
    }

    /** Called every server tick per player (from PWEventHandlers.onPlayerTick). */
    public static void tick(ServerPlayer player) {
        PendingRecall recall = pending.get(player.getUUID());
        if (recall == null) return;

        // Abort if the player moved
        if (player.position().distanceToSqr(recall.startPos()) > MOVE_THRESHOLD_SQ) {
            pending.remove(player.getUUID());
            player.displayClientMessage(
                    Component.translatable("parallelworlds.recall.cancelled_moved")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        // Fire when countdown reaches zero
        if (recall.ticksRemaining() <= 0) {
            pending.remove(player.getUUID());
            executeRecall(player, recall);
            return;
        }

        // Decrement and show live action-bar countdown
        PendingRecall next = recall.decremented();
        pending.put(player.getUUID(), next);
        int secsLeft = (int) Math.ceil(next.ticksRemaining() / 20.0);
        player.displayClientMessage(
                Component.translatable("parallelworlds.recall.charging", secsLeft)
                        .withStyle(ChatFormatting.YELLOW), true);
    }

    /** Cancel any pending recall, e.g. on player logout. */
    public static void cancel(UUID playerId) {
        pending.remove(playerId);
    }

    private static void executeRecall(ServerPlayer player, PendingRecall recall) {
        // Find and consume one Death Recall Token from the player's inventory
        boolean consumed = false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof DeathRecallItem) {
                int cooldownTicks = PWConfig.getDeathRecallCooldownSeconds() * 20;
                if (cooldownTicks > 0) {
                    player.getCooldowns().addCooldown(s.getItem(), cooldownTicks);
                }
                s.shrink(1);
                consumed = true;
                break;
            }
        }

        // If the token was dropped / moved out of inventory mid-countdown, abort silently
        if (!consumed) return;

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, recall.dim());
        ServerLevel targetLevel = player.server.getLevel(dimKey);
        if (targetLevel == null) {
            player.displayClientMessage(
                    Component.translatable("parallelworlds.recall.dim_gone")
                            .withStyle(ChatFormatting.RED), false);
            return;
        }

        TeleportHandler.teleportToDeathLocation(
                player, targetLevel, recall.deathPos(), recall.deathYRot(), recall.deathXRot());
    }
}
