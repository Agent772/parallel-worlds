package com.agent772.parallelworlds.event;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.data.PlayerExplorationStats;
import com.agent772.parallelworlds.data.ReturnPosition;
import com.agent772.parallelworlds.dimension.DimensionManager;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.dimension.SeedManager;
import com.agent772.parallelworlds.generation.async.AsyncChunkHint;
import com.agent772.parallelworlds.item.DeathRecallItem;
import com.agent772.parallelworlds.item.DeathRecallTracker;
import com.agent772.parallelworlds.network.PWNetworking;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.agent772.parallelworlds.util.InventoryKeeper;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Handles player lifecycle events in exploration dimensions:
 * join, leave, dimension change, death, and respawn.
 */
public final class PWEventHandlers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static Supplier<DimensionManager> dimensionManagerSupplier;

    /** Pending death locations for players who died in an exploration dimension this session. */
    private static final Map<UUID, ReturnPosition> pendingDeathRecalls = new ConcurrentHashMap<>();

    private PWEventHandlers() {}

    public static void setDimensionManagerSupplier(Supplier<DimensionManager> supplier) {
        dimensionManagerSupplier = supplier;
    }

    // ── Player Join ──

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            // If the player is in a dimension that no longer exists, evacuate to spawn
            if (DimensionUtils.isExplorationDimension(player.level().dimension())) {
                var server = player.server;
                var level = server.getLevel(player.level().dimension());
                if (level == null) {
                    LOGGER.warn("Player {} was in deleted exploration dimension — evacuating",
                            player.getName().getString());
                    TeleportHandler.forceReturnToSpawn(player);
                }
            }

            // Sync all active exploration dimensions to the joining player
            PWNetworking.syncAllDimensionsToPlayer(player);

            // Send pending mod data cleanup notifications.
            // No server-side gate on modCompatCleanupEnabled — that is a client setting
            // and unavailable on a dedicated server. The client handler checks it instead.
            {
                PWSavedData data = PWSavedData.get(player.server);
                java.util.Set<String> pending = data.getPendingCleanups();
                if (!pending.isEmpty() && !data.isPlayerNotified(player.getUUID())) {
                    var activePaths = DimensionRegistrar.getExplorationDimensionIds()
                            .stream().map(ResourceLocation::getPath).toList();
                    PWNetworking.sendDimensionCleanup(player, new java.util.ArrayList<>(pending), activePaths);
                    data.markPlayerNotified(player.getUUID());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error handling player login for {}", event.getEntity().getName().getString(), e);
        }
    }

    // ── Player Leave ──

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            // Clear invulnerability to prevent god-mode persistence across sessions
            player.setInvulnerable(false);

            // Cancel any pending death recall countdown
            DeathRecallTracker.cancel(player.getUUID());

            // Clean up async chunk hint tracker
            AsyncChunkHint.onPlayerLogout(player.getUUID());

            ResourceLocation dimLoc = player.level().dimension().location();

            // Record last known dimension
            if (DimensionUtils.isExplorationDimension(dimLoc)) {
                // End exploration stats timer
                PWSavedData savedData = PWSavedData.get(player.server);
                PlayerExplorationStats stats = savedData.getOrCreatePlayerStats(player.getUUID());
                stats.endVisit();
                savedData.setDirty();
            }

            // Notify DimensionManager
            DimensionManager mgr = getDimensionManager();
            if (mgr != null) {
                mgr.onPlayerLeave(player);
            }
        } catch (Exception e) {
            LOGGER.error("Error handling player logout for {}", event.getEntity().getName().getString(), e);
        }
    }

    // ── Dimension Change ──

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            ResourceLocation from = event.getFrom().location();
            ResourceLocation to = event.getTo().location();
            boolean leftExploration = DimensionUtils.isExplorationDimension(from);
            boolean enteredExploration = DimensionUtils.isExplorationDimension(to);

            PWSavedData savedData = PWSavedData.get(player.server);
            DimensionManager mgr = getDimensionManager();

            // Leaving exploration dimension
            if (leftExploration) {
                PlayerExplorationStats stats = savedData.getOrCreatePlayerStats(player.getUUID());
                stats.endVisit();
                savedData.setDirty();

                if (mgr != null) {
                    mgr.onPlayerLeave(player);
                }

                if (!enteredExploration) {
                    player.displayClientMessage(
                            Component.translatable("parallelworlds.event.returned_from_exploration")
                                    .withStyle(ChatFormatting.GREEN), false);
                }
            }

            // Entering exploration dimension
            if (enteredExploration) {
                PlayerExplorationStats stats = savedData.getOrCreatePlayerStats(player.getUUID());
                stats.recordVisit(to);
                stats.startVisit();
                savedData.setDirty();

                if (mgr != null) {
                    mgr.onPlayerEnter(player, to);
                }

                // Show seed rotation info if enabled
                Duration timeUntilReset = SeedManager.getTimeUntilNextReset();
                if (timeUntilReset != null) {
                    String timeStr = formatDuration(timeUntilReset);
                    player.displayClientMessage(
                            Component.translatable("parallelworlds.command.seed_resets_in", timeStr)
                                    .withStyle(ChatFormatting.AQUA), false);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error handling dimension change for {}", event.getEntity().getName().getString(), e);
        }
    }

    // ── Player Death ──

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) return;

        try {
            if (PWConfig.isKeepInventoryInExploration()) {
                // Save inventory so it can be restored on respawn
                InventoryKeeper.saveInventory(player);

                // Clear inventory and XP to prevent double-drops
                player.getInventory().clearContent();
                player.setExperiencePoints(0);
                player.setExperienceLevels(0);
            }

            // Record death location for the Death Recall Token
            if (PWConfig.isDeathRecallEnabled()) {
                pendingDeathRecalls.put(player.getUUID(), new ReturnPosition(
                        player.blockPosition(),
                        player.level().dimension().location(),
                        player.getYRot(),
                        player.getXRot()
                ));
            }
        } catch (Exception e) {
            LOGGER.error("Error handling death for {}", player.getName().getString(), e);
        }
    }

    // ── Player Respawn ──

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            // Restore saved inventory from keepInventoryInExploration
            if (InventoryKeeper.hasSavedInventory(player.getUUID())) {
                InventoryKeeper.restoreInventory(player);
            }

            // Give Death Recall Token AFTER inventory restoration so it isn't overwritten
            ReturnPosition deathInfo = pendingDeathRecalls.remove(player.getUUID());
            if (deathInfo != null && PWConfig.isDeathRecallEnabled()) {
                ItemStack token = DeathRecallItem.createFor(
                        deathInfo.dimension(), deathInfo.pos(), deathInfo.yRot(), deathInfo.xRot());
                // Prefer an empty hotbar slot; fall back to any slot, then drop at feet
                boolean placed = false;
                for (int slot = 8; slot >= 0; slot--) {
                    if (player.getInventory().getItem(slot).isEmpty()) {
                        player.getInventory().setItem(slot, token);
                        placed = true;
                        break;
                    }
                }
                if (!placed && !player.getInventory().add(token)) {
                    player.drop(token, false);
                }
                player.displayClientMessage(
                        Component.translatable("parallelworlds.recall.received")
                                .withStyle(ChatFormatting.AQUA), false);
            }

            // Prevent respawning inside exploration dimensions
            if (PWConfig.isPreventExplorationSpawn()
                    && DimensionUtils.isExplorationDimension(player.level().dimension())) {
                ServerLevel overworld = player.server.overworld();
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld,
                        spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                player.displayClientMessage(
                        Component.translatable("parallelworlds.event.respawned_overworld")
                                .withStyle(ChatFormatting.YELLOW), false);
            }
        } catch (Exception e) {
            LOGGER.error("Error handling respawn for {}", player.getName().getString(), e);
        }
    }

    // ── Player Tick (Async Chunk Hints + Death Recall) ──

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AsyncChunkHint.onPlayerTick(player);
        DeathRecallTracker.tick(player);
    }

    // ── Block vanilla /tp while inside an exploration dimension ──

    @SubscribeEvent
    public static void onTeleportCommand(EntityTeleportEvent.TeleportCommand event) {
        if (!PWConfig.isBlockVanillaTeleportInside()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) return;
        event.setCanceled(true);
        player.displayClientMessage(
                Component.translatable("parallelworlds.teleport.blocked_inside")
                        .withStyle(ChatFormatting.RED),
                true);
    }

    // ── Block cross-dim teleport INTO an exploration dimension ──

    @SubscribeEvent
    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!PWConfig.isBlockVanillaTeleportInto()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;
        // Allow Death Recall Token teleports to pass through regardless of config
        if (TeleportHandler.isRecallInProgress(player.getUUID())) return;
        // Allow PW portal teleports to pass through regardless of config
        if (TeleportHandler.isPortalInProgress(player.getUUID())) return;
        if (!DimensionUtils.isExplorationDimension(event.getDimension())) return;
        event.setCanceled(true);
        player.displayClientMessage(
                Component.translatable("parallelworlds.teleport.blocked_into")
                        .withStyle(ChatFormatting.RED),
                true);
    }

    // ── Helpers ──

    private static DimensionManager getDimensionManager() {
        return dimensionManagerSupplier != null ? dimensionManagerSupplier.get() : null;
    }

    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        } else if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return Math.max(minutes, 1) + "m";
        }
    }
}
