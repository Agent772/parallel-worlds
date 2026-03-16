package com.agent772.parallelworlds.teleport;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.data.ReturnPosition;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.util.DimensionEnvironment;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * Handles teleportation to and from exploration dimensions with
 * safe-spawn finding, cooldowns, and post-teleport safety effects.
 * <p>
 * Return positions are cached in-memory and persisted to PWSavedData
 * so they survive server restarts.
 */
public final class TeleportHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Return position cache (backed by PWSavedData for persistence) ──
    private static final Map<UUID, ReturnPosition> returnPositions = new ConcurrentHashMap<>();

    // ── Cooldown tracking ──
    private static final Map<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();
    private static final int MAX_TELEPORT_RECORDS = 1000;

    // ── Time budget for safe-position search (5 ms ≈ 10% of a tick) ──
    private static final long MAX_SEARCH_TIME_NS = TimeUnit.MILLISECONDS.toNanos(5);

    // ── Death-recall bypass: players mid-recall are exempt from travel-to-dim blocking ──
    private static final java.util.Set<UUID> recallInProgress = ConcurrentHashMap.newKeySet();

    /** Returns true while a Death Recall Token teleport is in progress for this player. */
    public static boolean isRecallInProgress(UUID playerId) {
        return recallInProgress.contains(playerId);
    }

    // ── Background cleanup ──
    private static final ScheduledExecutorService CLEANUP_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PW-TeleportCleanup");
                t.setDaemon(true);
                return t;
            });

    static {
        CLEANUP_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
                lastTeleportTime.entrySet().removeIf(e -> e.getValue() < cutoff);
            } catch (Exception e) {
                LOGGER.error("Error during teleport cooldown cleanup", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    private TeleportHandler() {}

    // ═══════════════════════════════════════════════════════════════
    //  Public entry points
    // ═══════════════════════════════════════════════════════════════

    /**
     * Teleport a player into an exploration dimension.
     */
    public static void teleportToExploration(ServerPlayer player, ServerLevel targetLevel) {
        if (!checkCooldown(player)) return;

        dismountBeforeTeleport(player);

        // Save return position only when leaving a non-exploration dimension
        boolean fromExploration = DimensionUtils.isExplorationDimension(player.level().dimension());
        if (!fromExploration) {
            ReturnPosition pos = new ReturnPosition(
                    player.blockPosition(),
                    player.level().dimension().location(),
                    player.getYRot(),
                    player.getXRot()
            );
            saveReturnPosition(player, pos);
        } else if (!returnPositions.containsKey(player.getUUID())) {
            // Player is in exploration dim with no saved return — emergency fallback
            ServerLevel overworld = player.server.overworld();
            ReturnPosition pos = new ReturnPosition(
                    overworld.getSharedSpawnPos(),
                    overworld.dimension().location(),
                    0F, 0F
            );
            saveReturnPosition(player, pos);
        }

        try {
            BlockPos safePos = resolveSpawnPosition(targetLevel);

            clearDangerousEffects(player);

            Vec3 target = Vec3.atBottomCenterOf(safePos);
            DimensionTransition transition = new DimensionTransition(
                    targetLevel, target, Vec3.ZERO,
                    player.getYRot(), player.getXRot(),
                    DimensionTransition.DO_NOTHING
            );
            player.changeDimension(transition);

            applyPostTeleportSafety(player);
            recordCooldown(player);

            player.displayClientMessage(
                    Component.translatable("parallelworlds.teleport.success")
                            .withStyle(ChatFormatting.GREEN), false);
        } catch (Exception e) {
            LOGGER.error("Failed to teleport {} to {}",
                    player.getName().getString(), targetLevel.dimension().location(), e);
            player.displayClientMessage(
                    Component.translatable("parallelworlds.teleport.failed")
                            .withStyle(ChatFormatting.RED), false);
            // Roll back saved return if this was the initial entry
            if (!fromExploration) {
                removeReturnPosition(player);
            }
        }
    }

    /**
     * Return a player from an exploration dimension to their saved position.
     */
    public static void returnFromExploration(ServerPlayer player) {
        dismountBeforeTeleport(player);

        ReturnPosition ret = returnPositions.get(player.getUUID());
        if (ret == null || ret.isExpired()) {
            LOGGER.debug("No valid return position for {} — falling back to spawn",
                    player.getName().getString());
            forceReturnToSpawn(player);
            return;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ret.dimension());
        ServerLevel returnLevel = player.server.getLevel(dimKey);
        if (returnLevel == null) {
            LOGGER.error("Return level {} not found, using world spawn", ret.dimension());
            forceReturnToSpawn(player);
            return;
        }

        if (PWConfig.isClearInventoryOnReturn()) {
            player.getInventory().clearContent();
        }

        BlockPos safePos = ensureSafePosition(returnLevel, ret.pos());

        clearDangerousEffects(player);

        Vec3 target = Vec3.atBottomCenterOf(safePos);
        DimensionTransition transition = new DimensionTransition(
                returnLevel, target, Vec3.ZERO,
                ret.yRot(), ret.xRot(),
                DimensionTransition.DO_NOTHING
        );
        player.changeDimension(transition);

        applyPostTeleportSafety(player);
        removeReturnPosition(player);
        recordCooldown(player);

        player.displayClientMessage(
                Component.translatable("parallelworlds.teleport.returned")
                        .withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * Teleport a player to their death location in an exploration dimension via a Death Recall Token.
     * Bypasses cooldowns, teleport-block config settings, and dimension locks.
     * Saves the player's current position as a return position so they can come back normally.
     */
    public static void teleportToDeathLocation(ServerPlayer player, ServerLevel targetLevel,
                                               BlockPos deathPos, float yRot, float xRot) {
        dismountBeforeTeleport(player);

        // Save current overworld position as return so the player can come back via portal/command
        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) {
            ReturnPosition ret = new ReturnPosition(
                    player.blockPosition(),
                    player.level().dimension().location(),
                    player.getYRot(),
                    player.getXRot()
            );
            saveReturnPosition(player, ret);
        }

        // Force-load the death chunk so safe-position search has terrain data
        ChunkPos cp = new ChunkPos(deathPos);
        targetLevel.getChunkSource().addRegionTicket(TicketType.PORTAL, cp, 3, deathPos);
        targetLevel.getChunk(deathPos.getX() >> 4, deathPos.getZ() >> 4, ChunkStatus.FULL);

        BlockPos safePos = ensureSafePosition(targetLevel, deathPos);
        clearDangerousEffects(player);

        Vec3 target = Vec3.atBottomCenterOf(safePos);
        DimensionTransition transition = new DimensionTransition(
                targetLevel, target, Vec3.ZERO,
                yRot, xRot,
                DimensionTransition.DO_NOTHING
        );

        // Mark in-progress so onTravelToDimension bypass check lets this through
        recallInProgress.add(player.getUUID());
        try {
            player.changeDimension(transition);
        } finally {
            recallInProgress.remove(player.getUUID());
        }

        applyPostTeleportSafety(player);
        // Intentionally no cooldown recorded — the token itself is the usage gate

        player.displayClientMessage(
                Component.translatable("parallelworlds.recall.used")
                        .withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * Emergency return to overworld spawn. Used when no saved return exists.
     */
    public static void forceReturnToSpawn(ServerPlayer player) {
        dismountBeforeTeleport(player);

        ServerLevel overworld = player.server.overworld();
        BlockPos safePos;

        // Prefer the player's bed/respawn-anchor position if it's in the overworld
        BlockPos respawn = player.getRespawnPosition();
        ResourceKey<Level> respawnDim = player.getRespawnDimension();
        if (respawn != null && overworld.dimension().equals(respawnDim)) {
            // Force-load the respawn chunk before searching (same as resolveSpawnPosition)
            ChunkPos cp = new ChunkPos(respawn);
            overworld.getChunkSource().addRegionTicket(TicketType.PORTAL, cp, 3, respawn);
            overworld.getChunk(respawn.getX() >> 4, respawn.getZ() >> 4, ChunkStatus.FULL);
            safePos = ensureSafePosition(overworld, respawn);
        } else {
            // No valid overworld respawn — use resolveSpawnPosition which handles chunk loading
            safePos = resolveSpawnPosition(overworld);
        }

        clearDangerousEffects(player);

        Vec3 target = Vec3.atBottomCenterOf(safePos);
        DimensionTransition transition = new DimensionTransition(
                overworld, target, Vec3.ZERO,
                player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING
        );
        player.changeDimension(transition);

        applyPostTeleportSafety(player);
        removeReturnPosition(player);

        player.displayClientMessage(
                Component.translatable("parallelworlds.teleport.no_return_pos")
                        .withStyle(ChatFormatting.YELLOW), false);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Return-position queries (for future use by commands / events)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Load persisted return positions from PWSavedData into the in-memory cache.
     * Called on server start after PWSavedData is available.
     */
    public static void initialize(net.minecraft.server.MinecraftServer server) {
        PWSavedData savedData = PWSavedData.get(server);
        // PWSavedData already loaded return positions from NBT;
        // no explicit bulk-load needed — queries go through cache + savedData fallback.
        LOGGER.info("TeleportHandler initialized with PWSavedData persistence");
    }

    public static Optional<ReturnPosition> getReturnPosition(UUID playerId) {
        return Optional.ofNullable(returnPositions.get(playerId));
    }

    public static void clearReturnPosition(UUID playerId) {
        returnPositions.remove(playerId);
    }

    /** Called on server shutdown to clear in-memory cache. */
    public static void clearAll() {
        returnPositions.clear();
        lastTeleportTime.clear();
        CLEANUP_EXECUTOR.shutdown();
    }

    // ── Persistence helpers ──

    public static void saveReturnPosition(ServerPlayer player, ReturnPosition pos) {
        returnPositions.put(player.getUUID(), pos);
        PWSavedData.get(player.server).saveReturnPosition(player.getUUID(), pos);
    }

    public static void removeReturnPosition(ServerPlayer player) {
        returnPositions.remove(player.getUUID());
        PWSavedData.get(player.server).clearReturnPosition(player.getUUID());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Post-teleport safety
    // ═══════════════════════════════════════════════════════════════

    public static void applyPostTeleportSafety(ServerPlayer player) {
        player.setInvulnerable(true);
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 1)); // Resistance II
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0));

        UUID uuid = player.getUUID();
        MinecraftServer server = player.server;
        server.tell(new TickTask(server.getTickCount() + 100, () -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                p.setInvulnerable(false);
            }
        }));
    }

    public static void clearDangerousEffects(ServerPlayer player) {
        player.removeEffect(MobEffects.LEVITATION);
        player.removeEffect(MobEffects.BLINDNESS);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Dismount
    // ═══════════════════════════════════════════════════════════════

    public static void dismountBeforeTeleport(ServerPlayer player) {
        if (player.getVehicle() != null) {
            player.stopRiding();
            player.displayClientMessage(
                    Component.translatable("parallelworlds.teleport.dismounted")
                            .withStyle(ChatFormatting.YELLOW), false);
        }
        if (!player.getPassengers().isEmpty()) {
            player.ejectPassengers();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cooldown
    // ═══════════════════════════════════════════════════════════════

    private static boolean checkCooldown(ServerPlayer player) {
        if (player.hasPermissions(2)) return true; // ops bypass

        Long lastTime = lastTeleportTime.get(player.getUUID());
        if (lastTime == null) return true;

        long cooldownMs = PWConfig.getTeleportCooldown() * 1000L;
        long elapsed = System.currentTimeMillis() - lastTime;
        if (elapsed < cooldownMs) {
            long remaining = (cooldownMs - elapsed) / 1000;
            player.displayClientMessage(
                    Component.translatable("parallelworlds.teleport.cooldown", remaining)
                            .withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    private static void recordCooldown(ServerPlayer player) {
        if (lastTeleportTime.size() >= MAX_TELEPORT_RECORDS) {
            lastTeleportTime.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(e -> lastTeleportTime.remove(e.getKey()));
        }
        lastTeleportTime.put(player.getUUID(), System.currentTimeMillis());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Safe-position resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve a safe spawn in the given level near its shared spawn pos.
     * Forces a synchronous chunk load on the center chunk, then runs
     * the multi-phase search, falling back to an emergency platform.
     */
    public static BlockPos resolveSpawnPosition(ServerLevel level) {
        BlockPos center = level.getSharedSpawnPos();

        // Force-load the center chunk so terrain is available for the search
        ChunkPos cp = new ChunkPos(center);
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, cp, 3, center);
        level.getChunk(center.getX() >> 4, center.getZ() >> 4, ChunkStatus.FULL);

        DimensionEnvironment env = DimensionEnvironment.classify(level.dimensionType());
        BlockPos safe;
        if (env == DimensionEnvironment.NETHER_LIKE) {
            safe = findNetherSafePosition(level, center);
        } else if (env == DimensionEnvironment.END_LIKE) {
            safe = findEndSafePosition(level);
        } else {
            safe = findSafePosition(level, center);
        }

        if (safe != null) return safe;

        LOGGER.warn("No safe position found in {} — creating emergency platform", level.dimension().location());
        return createEmergencyPlatform(level, center);
    }

    /**
     * Ensure the given pos is safe; search nearby or platform if not.
     */
    private static BlockPos ensureSafePosition(ServerLevel level, BlockPos pos) {
        if (isSafePosition(level, pos)) return pos;

        BlockPos safe = findSafePosition(level, pos);
        if (safe != null) return safe;

        LOGGER.warn("Position {} unsafe — creating emergency platform", pos);
        return createEmergencyPlatform(level, pos);
    }

    // ── Multi-phase search (overworld-like) ──

    private static BlockPos findSafePosition(ServerLevel level, BlockPos center) {
        if (isSafePosition(level, center)) return center;

        long deadline = System.nanoTime() + MAX_SEARCH_TIME_NS;

        // Phase 1: 8-block radius
        BlockPos p = searchInRadius(level, center, 8, deadline);
        if (p != null) return p;

        // Phase 2: 16-block radius
        p = searchInRadius(level, center, 16, deadline);
        if (p != null) return p;

        // Phase 3: 32-block radius
        p = searchInRadius(level, center, 32, deadline);
        return p; // may be null → caller creates platform
    }

    // ── Nether-specific ──

    private static BlockPos findNetherSafePosition(ServerLevel level, BlockPos center) {
        final int minY = 5;
        final int maxY = 122;
        int startY = Math.min(Math.max(center.getY(), minY + 10), maxY - 10);

        long deadline = System.nanoTime() + MAX_SEARCH_TIME_NS;

        for (int radius = 0; radius <= 32; radius += 2) {
            if (System.nanoTime() > deadline) return null;

            int angleStep = radius <= 8 ? 45 : 30;
            for (int angle = 0; angle < 360; angle += angleStep) {
                double rad = Math.toRadians(angle);
                int x = center.getX() + (int) (radius * Math.cos(rad));
                int z = center.getZ() + (int) (radius * Math.sin(rad));

                BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(x, startY, z);
                for (int dy = 0; dy <= 20; dy++) {
                    if (startY + dy <= maxY) {
                        mpos.setY(startY + dy);
                        if (isSafePosition(level, mpos)) return mpos.immutable();
                    }
                    if (dy > 0 && startY - dy >= minY) {
                        mpos.setY(startY - dy);
                        if (isSafePosition(level, mpos)) return mpos.immutable();
                    }
                }
            }
        }
        return null;
    }

    // ── End-specific ──

    private static BlockPos findEndSafePosition(ServerLevel level) {
        // Try main island area first
        BlockPos mainIsland = new BlockPos(0, 65, 0);
        BlockPos safe = findSafePosition(level, mainIsland);
        if (safe != null) return safe;

        // Side platform fallback
        BlockPos platformCenter = new BlockPos(100, 50, 0);
        if (isSafePosition(level, platformCenter.above())) {
            return platformCenter.above();
        }

        // Create end platform
        createEndPlatform(level, platformCenter);
        return platformCenter.above();
    }

    private static void createEndPlatform(ServerLevel level, BlockPos center) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState());
                for (int y = 1; y <= 3; y++) {
                    BlockPos clearPos = center.offset(x, y, z);
                    if (!level.getBlockState(clearPos).isAir()) {
                        level.setBlockAndUpdate(clearPos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // ── Spiral-radius search ──

    private static BlockPos searchInRadius(ServerLevel level, BlockPos center, int maxRadius, long deadline) {
        for (int radius = 1; radius <= maxRadius; radius += 2) {
            if (System.nanoTime() > deadline) return null;

            int angleStep = radius <= 8 ? 45 : (radius <= 16 ? 30 : 20);
            for (int angle = 0; angle < 360; angle += angleStep) {
                double rad = Math.toRadians(angle);
                int x = center.getX() + (int) (radius * Math.cos(rad));
                int z = center.getZ() + (int) (radius * Math.sin(rad));

                BlockPos safe = findSafeInColumn(level, x, center.getY(), z);
                if (safe != null) return safe;
            }
        }
        return null;
    }

    private static BlockPos findSafeInColumn(ServerLevel level, int x, int centerY, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        // Search upward
        int maxUp = Math.min(centerY + 32, level.getMaxBuildHeight() - 2);
        for (int y = centerY; y <= maxUp; y++) {
            mpos.set(x, y, z);
            if (isSafePosition(level, mpos)) return mpos.immutable();
        }
        // Search downward
        int minDown = Math.max(centerY - 32, level.getMinBuildHeight() + 1);
        for (int y = centerY - 1; y >= minDown; y--) {
            mpos.set(x, y, z);
            if (isSafePosition(level, mpos)) return mpos.immutable();
        }
        return null;
    }

    // ── Safety checks ──

    private static boolean isSafePosition(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() + 2 || pos.getY() > level.getMaxBuildHeight() - 2) {
            return false;
        }
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;

        BlockState ground = level.getBlockState(pos.below());
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());

        // Ground must be solid and not air
        if (!ground.isSolid() || ground.isAir()) return false;

        // Avoid bedrock floor spawns
        if (pos.getY() <= level.getMinBuildHeight() + 5 && ground.is(Blocks.BEDROCK)) return false;

        // Feet and head must be passable
        if (!isPassable(feet) || !isPassable(head)) return false;

        // Dangerous ground blocks
        if (isDangerousBlock(ground)) return false;

        // Adjacent liquid / lava check
        boolean netherLike = DimensionEnvironment.classify(level.dimensionType()) == DimensionEnvironment.NETHER_LIKE;
        int checkRadius = netherLike ? 2 : 1;
        int checkHeight = netherLike ? 3 : 1;
        for (int dy = -1; dy <= checkHeight; dy++) {
            for (int dx = -checkRadius; dx <= checkRadius; dx++) {
                for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                    BlockState s = level.getBlockState(pos.offset(dx, dy, dz));
                    if (s.is(Blocks.LAVA) || s.is(Blocks.WATER) ||
                            s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA)) {
                        return false;
                    }
                }
            }
        }

        // Falling blocks above
        for (int y = 1; y <= 3; y++) {
            BlockState above = level.getBlockState(pos.above(y));
            if (above.is(Blocks.SAND) || above.is(Blocks.GRAVEL) || above.is(Blocks.ANVIL)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isPassable(BlockState state) {
        if (state.isAir()) return true;
        if (state.is(Blocks.SNOW)) {
            return state.getValue(BlockStateProperties.LAYERS) < 5;
        }
        return false;
    }

    private static boolean isDangerousBlock(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) ||
                state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) ||
                state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) ||
                state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE) ||
                state.is(Blocks.POWDER_SNOW) || state.getBlock() instanceof LeavesBlock;
    }

    // ── Emergency platform ──

    private static BlockPos createEmergencyPlatform(ServerLevel level, BlockPos center) {
        BlockPos platformPos = new BlockPos(
                center.getX(),
                Math.max(level.getMinBuildHeight() + 10, 64),
                center.getZ()
        );

        // 3×3 obsidian
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(platformPos.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        // Clear headroom
        for (int y = 0; y < 3; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = platformPos.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        LOGGER.warn("Created emergency obsidian platform at {}", platformPos);
        return platformPos;
    }
}
