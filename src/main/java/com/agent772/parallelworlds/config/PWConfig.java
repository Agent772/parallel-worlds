package com.agent772.parallelworlds.config;

import java.util.List;

/**
 * Runtime config cache. Values are populated from PWConfigSpec on config load/reload.
 * All fields are accessed from the server thread — no synchronization needed.
 */
public final class PWConfig {

    // General
    private static List<String> enabledDimensions;
    private static int explorationBorderDiameter;

    // Seed
    private static boolean seedRotationEnabled;
    private static PWConfigSpec.ResetSchedule resetSchedule;
    private static PWConfigSpec.WeekDay resetDayOfWeek;
    private static int resetDayOfMonth;
    private static int resetHour;
    private static int resetMinute;
    private static long debugSeed;

    // Portal
    private static String portalFrameBlock;
    private static String portalIgniterItem;
    private static String portalCycleItem;
    private static List<String> dimensionParticleColors;
    private static boolean portalEnabled;
    private static int portalDelayTicks;
    private static int portalMaxSize;
    private static boolean portalBuildingInExploration;
    private static boolean portalIgniteOpOnly;
    private static boolean portalCycleOpOnly;

    // Restrictions
    private static PWConfigSpec.RestrictionMode enderChestMode;
    private static List<String> restrictedItems;
    private static List<String> restrictedBlocks;
    private static PWConfigSpec.RestrictionMode restrictedItemMode;

    // Persistence
    private static PWConfigSpec.PersistenceMode persistenceMode;
    private static int oldDimensionRetentionCount;

    // Performance
    private static int chunkUnloadDelay;
    private static int maxChunksPerPlayer;
    private static boolean aggressiveChunkUnloading;
    private static boolean preventDiskSaves;
    private static boolean pregenEnabled;
    private static int pregenRadius;
    private static int pregenChunksPerTick;
    private static int pregenMaxTickMs;
    private static double pregenMinTps;
    private static double pregenMemoryThreshold;

    // Async chunk generation
    private static boolean asyncChunkGenEnabled;
    private static int asyncMaxInFlight;
    private static int asyncWorkerThreads;
    private static boolean asyncChunkHintsEnabled;
    private static int asyncHintDistance;

    // Safety
    private static int teleportCooldown;
    private static int teleportSafetyRadius;
    private static boolean keepInventoryInExploration;
    private static boolean clearInventoryOnReturn;
    private static boolean preventExplorationSpawn;

    // Dimension locks
    private static boolean dimensionLocksEnabled;
    private static List<String> dimensionLocks;

    // Compatibility
    private static boolean modCompatCleanupEnabled;

    // Commands
    private static boolean commandTpEnabled;
    private static boolean commandReturnEnabled;
    private static int recoveryAutoUnloadDelaySec;

    // Teleport blocking
    private static boolean blockVanillaTeleportInto;
    private static boolean blockVanillaTeleportInside;
    private static boolean blockTempadPortalInto;
    private static boolean blockTempadPortalInside;

    // Death recall
    private static boolean deathRecallEnabled;
    private static int deathRecallChargeTicks;
    private static int deathRecallCooldownSeconds;
    private static int deathRecallExpireSeconds;

    private PWConfig() {}

    /**
     * Refresh all cached values from the config spec. Called on load and reload.
     */
    @SuppressWarnings("unchecked")
    public static void refresh() {
        enabledDimensions = (List<String>) PWConfigSpec.ENABLED_DIMENSIONS.get();
        explorationBorderDiameter = PWConfigSpec.EXPLORATION_BORDER_DIAMETER.get();

        seedRotationEnabled = PWConfigSpec.SEED_ROTATION_ENABLED.get();
        resetSchedule = PWConfigSpec.RESET_SCHEDULE.get();
        resetDayOfWeek = PWConfigSpec.RESET_DAY_OF_WEEK.get();
        resetDayOfMonth = PWConfigSpec.RESET_DAY_OF_MONTH.get();
        resetHour = PWConfigSpec.RESET_HOUR.get();
        resetMinute = PWConfigSpec.RESET_MINUTE.get();
        debugSeed = PWConfigSpec.DEBUG_SEED.get();

        portalFrameBlock = PWConfigSpec.PORTAL_FRAME_BLOCK.get();
        portalIgniterItem = PWConfigSpec.PORTAL_IGNITER_ITEM.get();
        portalCycleItem = PWConfigSpec.PORTAL_CYCLE_ITEM.get();
        dimensionParticleColors = (List<String>) PWConfigSpec.DIMENSION_PARTICLE_COLORS.get();
        portalEnabled = PWConfigSpec.PORTAL_ENABLED.get();
        portalDelayTicks = PWConfigSpec.PORTAL_DELAY_TICKS.get();
        portalMaxSize = PWConfigSpec.PORTAL_MAX_SIZE.get();
        portalBuildingInExploration = PWConfigSpec.PORTAL_BUILDING_IN_EXPLORATION.get();
        portalIgniteOpOnly = PWConfigSpec.PORTAL_IGNITE_OP_ONLY.get();
        portalCycleOpOnly = PWConfigSpec.PORTAL_CYCLE_OP_ONLY.get();

        enderChestMode = PWConfigSpec.ENDER_CHEST_MODE.get();
        restrictedItems = (List<String>) PWConfigSpec.RESTRICTED_ITEMS.get();
        restrictedBlocks = (List<String>) PWConfigSpec.RESTRICTED_BLOCKS.get();
        restrictedItemMode = PWConfigSpec.RESTRICTED_ITEM_MODE.get();

        persistenceMode = PWConfigSpec.PERSISTENCE_MODE.get();
        oldDimensionRetentionCount = PWConfigSpec.OLD_DIMENSION_RETENTION_COUNT.get();

        chunkUnloadDelay = PWConfigSpec.CHUNK_UNLOAD_DELAY.get();
        maxChunksPerPlayer = PWConfigSpec.MAX_CHUNKS_PER_PLAYER.get();
        aggressiveChunkUnloading = PWConfigSpec.AGGRESSIVE_CHUNK_UNLOADING.get();
        preventDiskSaves = PWConfigSpec.PREVENT_DISK_SAVES.get();
        pregenEnabled = PWConfigSpec.PREGEN_ENABLED.get();
        pregenRadius = PWConfigSpec.PREGEN_RADIUS.get();
        pregenChunksPerTick = PWConfigSpec.PREGEN_CHUNKS_PER_TICK.get();
        pregenMaxTickMs = PWConfigSpec.PREGEN_MAX_TICK_MS.get();
        pregenMinTps = PWConfigSpec.PREGEN_MIN_TPS.get();
        pregenMemoryThreshold = PWConfigSpec.PREGEN_MEMORY_THRESHOLD.get();

        asyncChunkGenEnabled = PWConfigSpec.ASYNC_CHUNK_GEN_ENABLED.get();
        asyncMaxInFlight = PWConfigSpec.ASYNC_MAX_IN_FLIGHT.get();
        asyncWorkerThreads = PWConfigSpec.ASYNC_WORKER_THREADS.get();
        asyncChunkHintsEnabled = PWConfigSpec.ASYNC_CHUNK_HINTS_ENABLED.get();
        asyncHintDistance = PWConfigSpec.ASYNC_HINT_DISTANCE.get();

        teleportCooldown = PWConfigSpec.TELEPORT_COOLDOWN.get();
        teleportSafetyRadius = PWConfigSpec.TELEPORT_SAFETY_RADIUS.get();
        keepInventoryInExploration = PWConfigSpec.KEEP_INVENTORY_IN_EXPLORATION.get();
        clearInventoryOnReturn = PWConfigSpec.CLEAR_INVENTORY_ON_RETURN.get();
        preventExplorationSpawn = PWConfigSpec.PREVENT_EXPLORATION_SPAWN.get();

        dimensionLocksEnabled = PWConfigSpec.DIMENSION_LOCKS_ENABLED.get();
        dimensionLocks = (List<String>) PWConfigSpec.DIMENSION_LOCKS.get();

        commandTpEnabled = PWConfigSpec.COMMAND_TP_ENABLED.get();
        commandReturnEnabled = PWConfigSpec.COMMAND_RETURN_ENABLED.get();
        recoveryAutoUnloadDelaySec = PWConfigSpec.RECOVERY_AUTO_UNLOAD_DELAY_SEC.get();

        blockVanillaTeleportInto = PWConfigSpec.BLOCK_VANILLA_TP_INTO.get();
        blockVanillaTeleportInside = PWConfigSpec.BLOCK_VANILLA_TP_INSIDE.get();
        blockTempadPortalInto = PWConfigSpec.BLOCK_TEMPAD_PORTAL_INTO.get();
        blockTempadPortalInside = PWConfigSpec.BLOCK_TEMPAD_PORTAL_INSIDE.get();

        deathRecallEnabled = PWConfigSpec.DEATH_RECALL_ENABLED.get();
        deathRecallChargeTicks = PWConfigSpec.DEATH_RECALL_CHARGE_TICKS.get();
        deathRecallCooldownSeconds = PWConfigSpec.DEATH_RECALL_COOLDOWN_SECONDS.get();
        deathRecallExpireSeconds = PWConfigSpec.DEATH_RECALL_EXPIRE_SECONDS.get();
    }

    /** Refresh client-only cached values from the client config spec. Called on client load/reload. */
    public static void refreshClient() {
        modCompatCleanupEnabled = PWClientConfigSpec.MOD_COMPAT_CLEANUP_ENABLED.get();
    }

    // ── Getters ──

    public static List<String> getEnabledDimensions() { return enabledDimensions; }
    public static int getExplorationBorderDiameter() { return explorationBorderDiameter; }

    public static boolean isSeedRotationEnabled() { return seedRotationEnabled; }
    public static PWConfigSpec.ResetSchedule getResetSchedule() { return resetSchedule; }
    public static PWConfigSpec.WeekDay getResetDayOfWeek() { return resetDayOfWeek; }
    public static int getResetDayOfMonth() { return resetDayOfMonth; }
    public static int getResetHour() { return resetHour; }
    public static int getResetMinute() { return resetMinute; }
    public static long getDebugSeed() { return debugSeed; }

    public static String getPortalFrameBlock() { return portalFrameBlock; }
    public static String getPortalIgniterItem() { return portalIgniterItem; }
    public static String getPortalCycleItem() { return portalCycleItem; }
    public static List<String> getDimensionParticleColors() { return dimensionParticleColors; }
    public static boolean isPortalEnabled() { return portalEnabled; }
    public static int getPortalDelayTicks() { return portalDelayTicks; }
    public static int getPortalMaxSize() { return portalMaxSize; }
    public static boolean isPortalBuildingInExplorationEnabled() { return portalBuildingInExploration; }
    public static boolean isPortalIgniteOpOnly() { return portalIgniteOpOnly; }
    public static boolean isPortalCycleOpOnly() { return portalCycleOpOnly; }

    public static PWConfigSpec.RestrictionMode getEnderChestMode() { return enderChestMode; }
    public static List<String> getRestrictedItems() { return restrictedItems; }
    public static List<String> getRestrictedBlocks() { return restrictedBlocks; }
    public static PWConfigSpec.RestrictionMode getRestrictedItemMode() { return restrictedItemMode; }

    public static PWConfigSpec.PersistenceMode getPersistenceMode() { return persistenceMode; }
    public static int getOldDimensionRetentionCount() { return oldDimensionRetentionCount; }

    public static int getChunkUnloadDelay() { return chunkUnloadDelay; }
    public static int getMaxChunksPerPlayer() { return maxChunksPerPlayer; }
    public static boolean isAggressiveChunkUnloading() { return aggressiveChunkUnloading; }
    public static boolean isPreventDiskSaves() { return preventDiskSaves; }
    public static boolean isPregenEnabled() { return pregenEnabled; }
    public static int getPregenRadius() { return pregenRadius; }
    public static int getPregenChunksPerTick() { return pregenChunksPerTick; }
    public static int getPregenMaxTickMs() { return pregenMaxTickMs; }
    public static double getPregenMinTps() { return pregenMinTps; }
    public static double getPregenMemoryThreshold() { return pregenMemoryThreshold; }

    public static boolean isAsyncChunkGenEnabled() { return asyncChunkGenEnabled; }
    public static int getAsyncMaxInFlight() { return asyncMaxInFlight; }
    public static int getAsyncWorkerThreads() { return asyncWorkerThreads; }
    public static boolean isAsyncChunkHintsEnabled() { return asyncChunkHintsEnabled; }
    public static int getAsyncHintDistance() { return asyncHintDistance; }

    public static int getTeleportCooldown() { return teleportCooldown; }
    public static int getTeleportSafetyRadius() { return teleportSafetyRadius; }
    public static boolean isKeepInventoryInExploration() { return keepInventoryInExploration; }
    public static boolean isClearInventoryOnReturn() { return clearInventoryOnReturn; }
    public static boolean isPreventExplorationSpawn() { return preventExplorationSpawn; }

    public static boolean isDimensionLocksEnabled() { return dimensionLocksEnabled; }
    public static List<String> getDimensionLocks() { return dimensionLocks; }

    /**
     * Parse dimensionLocks config into a map of dimension -> required advancement.
     */
    public static java.util.Map<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> getParsedDimensionLocks() {
        java.util.Map<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> result = new java.util.HashMap<>();
        if (dimensionLocks == null) return result;
        for (String entry : dimensionLocks) {
            int eq = entry.indexOf('=');
            if (eq < 0) continue;
            net.minecraft.resources.ResourceLocation dim = net.minecraft.resources.ResourceLocation.tryParse(entry.substring(0, eq).trim());
            net.minecraft.resources.ResourceLocation adv = net.minecraft.resources.ResourceLocation.tryParse(entry.substring(eq + 1).trim());
            if (dim != null && adv != null) {
                result.put(dim, adv);
            }
        }
        return result;
    }

    public static boolean isModCompatCleanupEnabled() { return modCompatCleanupEnabled; }

    public static boolean isCommandTpEnabled() { return commandTpEnabled; }
    public static boolean isCommandReturnEnabled() { return commandReturnEnabled; }
    public static int getRecoveryAutoUnloadDelaySec() { return recoveryAutoUnloadDelaySec; }

    public static boolean isBlockVanillaTeleportInto() { return blockVanillaTeleportInto; }
    public static boolean isBlockVanillaTeleportInside() { return blockVanillaTeleportInside; }
    public static boolean isBlockTempadPortalInto() { return blockTempadPortalInto; }
    public static boolean isBlockTempadPortalInside() { return blockTempadPortalInside; }

    public static boolean isDeathRecallEnabled() { return deathRecallEnabled; }
    public static int getDeathRecallChargeTicks() { return deathRecallChargeTicks; }
    public static int getDeathRecallCooldownSeconds() { return deathRecallCooldownSeconds; }
    public static int getDeathRecallExpireSeconds() { return deathRecallExpireSeconds; }
}
