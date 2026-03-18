package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks runtime state for exploration dimensions: active status,
 * player locations, and player evacuation.
 */
public class DimensionManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;
    private final Map<ResourceKey<Level>, ResourceKey<Level>> dimensionMappings;
    private final Set<ResourceLocation> activeDimensions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ResourceLocation> playerLastDimension = new ConcurrentHashMap<>();

    public DimensionManager(MinecraftServer server,
                            Map<ResourceKey<Level>, ResourceKey<Level>> registeredDimensions) {
        this.server = server;
        this.dimensionMappings = new ConcurrentHashMap<>(registeredDimensions);
        LOGGER.info("Initialized DimensionManager with {} mappings", dimensionMappings.size());
    }

    public Optional<ServerLevel> getExplorationDimension(ResourceLocation baseDimension) {
        ResourceKey<Level> baseKey = ResourceKey.create(Registries.DIMENSION, baseDimension);
        ResourceKey<Level> explorationKey = dimensionMappings.get(baseKey);
        if (explorationKey == null) return Optional.empty();

        ServerLevel level = server.getLevel(explorationKey);
        if (level != null) {
            activeDimensions.add(explorationKey.location());
        }
        return Optional.ofNullable(level);
    }

    public boolean isExplorationDimension(ResourceLocation dimensionId) {
        return dimensionMappings.values().stream()
                .anyMatch(k -> k.location().equals(dimensionId));
    }

    public void onPlayerEnter(ServerPlayer player, ResourceLocation dimension) {
        playerLastDimension.put(player.getUUID(), dimension);
    }

    public void onPlayerLeave(ServerPlayer player) {
        playerLastDimension.remove(player.getUUID());
    }

    public List<ServerPlayer> getPlayersInExplorationDimensions() {
        List<ServerPlayer> players = new ArrayList<>();
        for (ResourceKey<Level> key : dimensionMappings.values()) {
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                players.addAll(level.players());
            }
        }
        return players;
    }

    /**
     * Move all players out of exploration dimensions — called on shutdown.
     * Uses the saved return position (where the player entered from) so they
     * land at their exact pre-exploration location rather than world spawn.
     */
    public void evacuateAllPlayers() {
        LOGGER.info("Evacuating all players from exploration dimensions");
        List<ServerPlayer> toEvac = getPlayersInExplorationDimensions();

        for (ServerPlayer player : toEvac) {
            try {
                // Prefer the player's set respawn point (bed / respawn anchor) — it is the most
                // "home-like" location. forceReturnToSpawn checks getRespawnPosition() first and
                // falls through to world spawn only if none is set.
                // If the player has no respawn point, use returnFromExploration which goes back to
                // wherever they entered the exploration dimension from.
                if (player.getRespawnPosition() != null) {
                    TeleportHandler.forceReturnToSpawn(player);
                } else {
                    TeleportHandler.returnFromExploration(player);
                }
                LOGGER.info("Evacuated {}", player.getName().getString());
            } catch (Exception e) {
                LOGGER.error("Failed to evacuate {}", player.getName().getString(), e);
            }
        }
        LOGGER.info("Evacuated {} players", toEvac.size());
    }

    public void clearCaches() {
        playerLastDimension.clear();
        activeDimensions.clear();
    }
}
