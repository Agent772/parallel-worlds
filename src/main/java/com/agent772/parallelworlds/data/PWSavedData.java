package com.agent772.parallelworlds.data;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.*;

/**
 * Central persistence for Parallel Worlds data, stored as
 * {@code data/parallelworlds.dat} in the overworld save folder.
 * <p>
 * Persists: return positions, dimension seeds, dimension metadata,
 * player exploration stats, manual unlocks, and the last reset timestamp.
 * <p>
 * All reads/writes happen on the server thread — no synchronization needed.
 */
public class PWSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "parallelworlds";

    // ── Data maps ──
    private final Map<UUID, ReturnPosition> playerReturnPositions = new HashMap<>();
    // baseDim -> exploration dim ResourceLocation (e.g. minecraft:overworld -> parallelworlds:pw_overworld_0)
    private final Map<ResourceLocation, ResourceLocation> dimensionKeys = new HashMap<>();
    // baseDim -> epoch-second when the exploration dimension was last created (0 = never)
    private final Map<ResourceLocation, Long> dimensionRegisteredAt = new HashMap<>();
    private final Map<ResourceLocation, DimensionMetadata> dimensionMetadata = new HashMap<>();
    private final Map<UUID, PlayerExplorationStats> playerStats = new HashMap<>();
    private final Set<ResourceLocation> activeDimensions = new HashSet<>();
    private final Map<UUID, Set<ResourceLocation>> manualUnlocks = new HashMap<>();
    private final Map<String, ResourceLocation> portalTargets = new HashMap<>();
    // Shared portal position per exploration dimension (dim location string -> {x,y,z,axis})
    private final Map<String, CompoundTag> explorationPortals = new HashMap<>();
    // Per-player entry portal: which overworld portal they used to enter (UUID -> {dim,x,y,z,axis})
    private final Map<UUID, CompoundTag> playerEntryPortals = new HashMap<>();
    private final Set<String> pendingCleanupDimensions = new HashSet<>();
    private final Set<UUID> cleanupNotifiedPlayers = new HashSet<>();
    // Pre-gen tasks — serialization is delegated to ChunkPreGenerator which manages
    // its own ListTag of task entries. This raw storage avoids duplicating the
    // serialization logic and is only read/written by ChunkPreGenerator.
    private net.minecraft.nbt.ListTag pregenTasks = new net.minecraft.nbt.ListTag();

    public PWSavedData() {
    }

    // ── Factory ──

    public static SavedData.Factory<PWSavedData> factory() {
        return new SavedData.Factory<>(PWSavedData::new, PWSavedData::load);
    }

    /**
     * Accessor: get or create PWSavedData from the overworld's DataStorage.
     * Returns the same instance on repeated calls within a server lifecycle.
     */
    public static PWSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    // ── Return positions ──

    public void saveReturnPosition(UUID playerId, ReturnPosition pos) {
        playerReturnPositions.put(playerId, pos);
        setDirty();
    }

    public Optional<ReturnPosition> getReturnPosition(UUID playerId) {
        ReturnPosition pos = playerReturnPositions.get(playerId);
        if (pos != null && pos.isExpired()) {
            playerReturnPositions.remove(playerId);
            setDirty();
            return Optional.empty();
        }
        return Optional.ofNullable(pos);
    }

    public void clearReturnPosition(UUID playerId) {
        if (playerReturnPositions.remove(playerId) != null) {
            setDirty();
        }
    }

    // ── Dimension key mapping (baseDim → exploration key) ──

    public void saveDimensionKey(ResourceLocation baseDim, ResourceLocation explorationKey) {
        dimensionKeys.put(baseDim, explorationKey);
        setDirty();
    }

    public Optional<ResourceLocation> getSavedDimensionKey(ResourceLocation baseDim) {
        return Optional.ofNullable(dimensionKeys.get(baseDim));
    }

    public void clearAllDimensionKeys() {
        dimensionKeys.clear();
        setDirty();
    }

    // ── Dimension registered-at timestamps (baseDim → epoch-second of last creation) ──

    public long getDimensionRegisteredAt(ResourceLocation baseDim) {
        return dimensionRegisteredAt.getOrDefault(baseDim, 0L);
    }

    public void saveDimensionRegisteredAt(ResourceLocation baseDim, long epochSecond) {
        dimensionRegisteredAt.put(baseDim, epochSecond);
        setDirty();
    }

    // ── Dimension metadata ──

    public void recordDimensionAccess(ResourceLocation dimension, UUID playerId) {
        DimensionMetadata meta = dimensionMetadata.computeIfAbsent(dimension,
                k -> new DimensionMetadata(System.currentTimeMillis()));
        if (playerId != null) {
            meta.recordAccess(playerId);
        }
        activeDimensions.add(dimension);
        setDirty();
    }

    public Optional<DimensionMetadata> getDimensionMetadata(ResourceLocation dimension) {
        return Optional.ofNullable(dimensionMetadata.get(dimension));
    }

    public void clearDimensionMetadata(ResourceLocation dimension) {
        dimensionMetadata.remove(dimension);
        activeDimensions.remove(dimension);
        setDirty();
    }

    public Set<ResourceLocation> getActiveDimensions() {
        return Set.copyOf(activeDimensions);
    }

    // ── Player stats ──

    public PlayerExplorationStats getOrCreatePlayerStats(UUID playerId) {
        return playerStats.computeIfAbsent(playerId, PlayerExplorationStats::new);
    }

    public Optional<PlayerExplorationStats> getPlayerStats(UUID playerId) {
        return Optional.ofNullable(playerStats.get(playerId));
    }

    // ── Manual unlocks ──

    public boolean hasManualUnlock(UUID playerId, ResourceLocation dimension) {
        Set<ResourceLocation> unlocks = manualUnlocks.get(playerId);
        return unlocks != null && unlocks.contains(dimension);
    }

    public void grantManualUnlock(UUID playerId, ResourceLocation dimension) {
        manualUnlocks.computeIfAbsent(playerId, k -> new HashSet<>()).add(dimension);
        setDirty();
    }

    public Set<ResourceLocation> getManualUnlocks(UUID playerId) {
        Set<ResourceLocation> unlocks = manualUnlocks.get(playerId);
        return unlocks != null ? Set.copyOf(unlocks) : Set.of();
    }

    public void revokeManualUnlock(UUID playerId, ResourceLocation dimension) {
        Set<ResourceLocation> unlocks = manualUnlocks.get(playerId);
        if (unlocks != null && unlocks.remove(dimension)) {
            if (unlocks.isEmpty()) {
                manualUnlocks.remove(playerId);
            }
            setDirty();
        }
    }

    // ── Portal targets ──

    public void savePortalTarget(GlobalPos pos, ResourceLocation target) {
        portalTargets.put(globalPosToString(pos), target);
        setDirty();
    }

    public Optional<ResourceLocation> getPortalTarget(GlobalPos pos) {
        return Optional.ofNullable(portalTargets.get(globalPosToString(pos)));
    }

    public void removePortalTarget(GlobalPos pos) {
        if (portalTargets.remove(globalPosToString(pos)) != null) {
            setDirty();
        }
    }

    public Map<GlobalPos, ResourceLocation> getAllPortalTargets() {
        Map<GlobalPos, ResourceLocation> result = new HashMap<>();
        portalTargets.forEach((key, value) -> {
            GlobalPos gp = stringToGlobalPos(key);
            if (gp != null) {
                result.put(gp, value);
            }
        });
        return result;
    }

    private static String globalPosToString(GlobalPos pos) {
        ResourceLocation dimLoc = pos.dimension().location();
        BlockPos bp = pos.pos();
        return dimLoc + "|" + bp.getX() + "," + bp.getY() + "," + bp.getZ();
    }

    private static GlobalPos stringToGlobalPos(String s) {
        try {
            int pipe = s.indexOf('|');
            if (pipe < 0) return null;
            ResourceLocation dimLoc = ResourceLocation.parse(s.substring(0, pipe));
            String[] coords = s.substring(pipe + 1).split(",");
            if (coords.length != 3) return null;
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            return GlobalPos.of(dimKey, new BlockPos(
                    Integer.parseInt(coords[0]),
                    Integer.parseInt(coords[1]),
                    Integer.parseInt(coords[2])));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Exploration portals (shared per exploration dimension) ──

    public void saveExplorationPortal(ResourceLocation dimLoc, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction.Axis axis) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("axis", axis.name());
        explorationPortals.put(dimLoc.toString(), tag);
        setDirty();
    }

    public Optional<net.minecraft.core.BlockPos> getExplorationPortalPos(ResourceLocation dimLoc) {
        CompoundTag tag = explorationPortals.get(dimLoc.toString());
        if (tag == null) return Optional.empty();
        return Optional.of(new net.minecraft.core.BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    public Optional<net.minecraft.core.Direction.Axis> getExplorationPortalAxis(ResourceLocation dimLoc) {
        CompoundTag tag = explorationPortals.get(dimLoc.toString());
        if (tag == null) return Optional.empty();
        try {
            return Optional.of(net.minecraft.core.Direction.Axis.valueOf(tag.getString("axis")));
        } catch (IllegalArgumentException e) {
            return Optional.of(net.minecraft.core.Direction.Axis.X);
        }
    }

    public void clearExplorationPortal(ResourceLocation dimLoc) {
        if (explorationPortals.remove(dimLoc.toString()) != null) {
            setDirty();
        }
    }

    // ── Player entry portals (which overworld portal a player entered from) ──

    public void savePlayerEntryPortal(UUID playerId, ResourceLocation dimension, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction.Axis axis) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("axis", axis.name());
        playerEntryPortals.put(playerId, tag);
        setDirty();
    }

    public Optional<GlobalPos> getPlayerEntryPortalGlobalPos(UUID playerId) {
        CompoundTag tag = playerEntryPortals.get(playerId);
        if (tag == null) return Optional.empty();
        try {
            ResourceLocation dimLoc = ResourceLocation.parse(tag.getString("dim"));
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            return Optional.of(GlobalPos.of(dimKey, pos));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<net.minecraft.core.Direction.Axis> getPlayerEntryPortalAxis(UUID playerId) {
        CompoundTag tag = playerEntryPortals.get(playerId);
        if (tag == null) return Optional.empty();
        try {
            return Optional.of(net.minecraft.core.Direction.Axis.valueOf(tag.getString("axis")));
        } catch (IllegalArgumentException e) {
            return Optional.of(net.minecraft.core.Direction.Axis.X);
        }
    }

    public void clearPlayerEntryPortal(UUID playerId) {
        if (playerEntryPortals.remove(playerId) != null) {
            setDirty();
        }
    }

    // ── Pre-gen tasks ──

    public void savePregenTasks(net.minecraft.nbt.ListTag tasks) {
        this.pregenTasks = tasks;
        setDirty();
    }

    public net.minecraft.nbt.ListTag getPregenTasks() {
        return pregenTasks;
    }

    // ── Pending cleanup ──

    public void addPendingCleanup(String dimensionPath) {
        pendingCleanupDimensions.add(dimensionPath);
        cleanupNotifiedPlayers.clear();
        setDirty();
    }

    public Set<String> getPendingCleanups() {
        return Set.copyOf(pendingCleanupDimensions);
    }

    public void markPlayerNotified(UUID playerUuid) {
        cleanupNotifiedPlayers.add(playerUuid);
        setDirty();
    }

    public boolean isPlayerNotified(UUID playerUuid) {
        return cleanupNotifiedPlayers.contains(playerUuid);
    }

    public void clearPendingCleanups() {
        pendingCleanupDimensions.clear();
        cleanupNotifiedPlayers.clear();
        setDirty();
    }

    // ── Serialization ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        // Return positions
        CompoundTag returnPosTag = new CompoundTag();
        playerReturnPositions.forEach((uuid, pos) ->
                returnPosTag.put(uuid.toString(), pos.toNbt()));
        tag.put("returnPositions", returnPosTag);

        // Dimension key mappings (baseDim -> exploration key)
        CompoundTag keysTag = new CompoundTag();
        dimensionKeys.forEach((baseDim, explorationKey) ->
                keysTag.putString(baseDim.toString(), explorationKey.toString()));
        tag.put("dimensionKeys", keysTag);

        // Dimension registered-at timestamps
        CompoundTag regAtTag = new CompoundTag();
        dimensionRegisteredAt.forEach((baseDim, ts) -> regAtTag.putLong(baseDim.toString(), ts));
        tag.put("dimensionRegisteredAt", regAtTag);

        // Dimension metadata
        CompoundTag metaTag = new CompoundTag();
        dimensionMetadata.forEach((dim, meta) ->
                metaTag.put(dim.toString(), meta.toNbt()));
        tag.put("dimensionMetadata", metaTag);

        // Active dimensions
        ListTag activeTag = new ListTag();
        activeDimensions.forEach(dim ->
                activeTag.add(net.minecraft.nbt.StringTag.valueOf(dim.toString())));
        tag.put("activeDimensions", activeTag);

        // Player stats
        ListTag statsTag = new ListTag();
        playerStats.values().forEach(stats -> statsTag.add(stats.toNbt()));
        tag.put("playerStats", statsTag);

        // Manual unlocks
        CompoundTag unlocksTag = new CompoundTag();
        manualUnlocks.forEach((uuid, dims) -> {
            ListTag dimList = new ListTag();
            dims.forEach(dim -> dimList.add(net.minecraft.nbt.StringTag.valueOf(dim.toString())));
            unlocksTag.put(uuid.toString(), dimList);
        });
        tag.put("manualUnlocks", unlocksTag);

        // Pre-gen tasks
        tag.put("pregenTasks", pregenTasks);

        // Portal targets
        CompoundTag portalTargetsTag = new CompoundTag();
        portalTargets.forEach((key, target) ->
                portalTargetsTag.putString(key, target.toString()));
        tag.put("portalTargets", portalTargetsTag);

        // Exploration portals
        CompoundTag explorationPortalsTag = new CompoundTag();
        explorationPortals.forEach(explorationPortalsTag::put);
        tag.put("explorationPortals", explorationPortalsTag);

        // Player entry portals
        CompoundTag entryPortalsTag = new CompoundTag();
        playerEntryPortals.forEach((uuid, entryTag) ->
                entryPortalsTag.put(uuid.toString(), entryTag));
        tag.put("playerEntryPortals", entryPortalsTag);

        // Pending cleanup dimensions
        ListTag cleanupTag = new ListTag();
        pendingCleanupDimensions.forEach(dim ->
                cleanupTag.add(net.minecraft.nbt.StringTag.valueOf(dim)));
        tag.put("pendingCleanupDimensions", cleanupTag);

        // Cleanup notified players
        ListTag notifiedTag = new ListTag();
        cleanupNotifiedPlayers.forEach(uuid ->
                notifiedTag.add(net.minecraft.nbt.StringTag.valueOf(uuid.toString())));
        tag.put("cleanupNotifiedPlayers", notifiedTag);

        return tag;
    }

    public static PWSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        PWSavedData data = new PWSavedData();

        // Return positions
        if (tag.contains("returnPositions", Tag.TAG_COMPOUND)) {
            CompoundTag returnPosTag = tag.getCompound("returnPositions");
            for (String key : returnPosTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ReturnPosition pos = ReturnPosition.fromNbt(returnPosTag.getCompound(key));
                    if (!pos.isExpired()) {
                        data.playerReturnPositions.put(uuid, pos);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed return position for key: {}", key);
                }
            }
        }

        // Dimension key mappings (baseDim -> exploration key)
        if (tag.contains("dimensionKeys", Tag.TAG_COMPOUND)) {
            CompoundTag keysTag = tag.getCompound("dimensionKeys");
            for (String key : keysTag.getAllKeys()) {
                try {
                    ResourceLocation baseDim = ResourceLocation.parse(key);
                    ResourceLocation explorationKey = ResourceLocation.parse(keysTag.getString(key));
                    data.dimensionKeys.put(baseDim, explorationKey);
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed dimension key entry for key: {}", key);
                }
            }
        }

        // Dimension registered-at timestamps
        if (tag.contains("dimensionRegisteredAt", Tag.TAG_COMPOUND)) {
            CompoundTag regAtTag = tag.getCompound("dimensionRegisteredAt");
            for (String key : regAtTag.getAllKeys()) {
                try {
                    data.dimensionRegisteredAt.put(ResourceLocation.parse(key), regAtTag.getLong(key));
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed dimensionRegisteredAt entry for key: {}", key);
                }
            }
        }

        // Dimension metadata
        if (tag.contains("dimensionMetadata", Tag.TAG_COMPOUND)) {
            CompoundTag metaTag = tag.getCompound("dimensionMetadata");
            for (String key : metaTag.getAllKeys()) {
                try {
                    ResourceLocation dim = ResourceLocation.parse(key);
                    data.dimensionMetadata.put(dim, DimensionMetadata.fromNbt(metaTag.getCompound(key)));
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed dimension metadata for key: {}", key);
                }
            }
        }

        // Active dimensions
        if (tag.contains("activeDimensions", Tag.TAG_LIST)) {
            ListTag activeTag = tag.getList("activeDimensions", Tag.TAG_STRING);
            for (int i = 0; i < activeTag.size(); i++) {
                try {
                    data.activeDimensions.add(ResourceLocation.parse(activeTag.getString(i)));
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed active dimension at index {}", i);
                }
            }
        }

        // Player stats
        if (tag.contains("playerStats", Tag.TAG_LIST)) {
            ListTag statsTag = tag.getList("playerStats", Tag.TAG_COMPOUND);
            for (int i = 0; i < statsTag.size(); i++) {
                PlayerExplorationStats stats = PlayerExplorationStats.fromNbt(statsTag.getCompound(i));
                if (stats != null) {
                    data.playerStats.put(stats.getPlayerId(), stats);
                }
            }
        }

        // Manual unlocks
        if (tag.contains("manualUnlocks", Tag.TAG_COMPOUND)) {
            CompoundTag unlocksTag = tag.getCompound("manualUnlocks");
            for (String key : unlocksTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ListTag dimList = unlocksTag.getList(key, Tag.TAG_STRING);
                    Set<ResourceLocation> dims = new HashSet<>();
                    for (int i = 0; i < dimList.size(); i++) {
                        try {
                            dims.add(ResourceLocation.parse(dimList.getString(i)));
                        } catch (Exception ignored) {}
                    }
                    if (!dims.isEmpty()) {
                        data.manualUnlocks.put(uuid, dims);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed manual unlock for key: {}", key);
                }
            }
        }

        // Pre-gen tasks
        if (tag.contains("pregenTasks", Tag.TAG_LIST)) {
            data.pregenTasks = tag.getList("pregenTasks", Tag.TAG_COMPOUND);
        }

        // Portal targets
        if (tag.contains("portalTargets", Tag.TAG_COMPOUND)) {
            CompoundTag portalTargetsTag = tag.getCompound("portalTargets");
            for (String key : portalTargetsTag.getAllKeys()) {
                try {
                    ResourceLocation target = ResourceLocation.parse(portalTargetsTag.getString(key));
                    data.portalTargets.put(key, target);
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed portal target entry for key: {}", key);
                }
            }
        }

        LOGGER.info("Loaded PWSavedData: {} return positions, {} metadata entries, {} player stats, {} portal targets",
                data.playerReturnPositions.size(),
                data.dimensionMetadata.size(), data.playerStats.size(), data.portalTargets.size());

        // Exploration portals
        if (tag.contains("explorationPortals", Tag.TAG_COMPOUND)) {
            CompoundTag explorationPortalsTag = tag.getCompound("explorationPortals");
            for (String key : explorationPortalsTag.getAllKeys()) {
                try {
                    data.explorationPortals.put(key, explorationPortalsTag.getCompound(key));
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed exploration portal entry for key: {}", key);
                }
            }
        }

        // Player entry portals
        if (tag.contains("playerEntryPortals", Tag.TAG_COMPOUND)) {
            CompoundTag entryPortalsTag = tag.getCompound("playerEntryPortals");
            for (String key : entryPortalsTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    data.playerEntryPortals.put(uuid, entryPortalsTag.getCompound(key));
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed player entry portal for key: {}", key);
                }
            }
        }

        // Pending cleanup dimensions
        if (tag.contains("pendingCleanupDimensions", Tag.TAG_LIST)) {
            ListTag cleanupTag = tag.getList("pendingCleanupDimensions", Tag.TAG_STRING);
            for (int i = 0; i < cleanupTag.size(); i++) {
                data.pendingCleanupDimensions.add(cleanupTag.getString(i));
            }
        }

        // Cleanup notified players
        if (tag.contains("cleanupNotifiedPlayers", Tag.TAG_LIST)) {
            ListTag notifiedTag = tag.getList("cleanupNotifiedPlayers", Tag.TAG_STRING);
            for (int i = 0; i < notifiedTag.size(); i++) {
                try {
                    data.cleanupNotifiedPlayers.add(UUID.fromString(notifiedTag.getString(i)));
                } catch (Exception ignored) {}
            }
        }

        return data;
    }
}
