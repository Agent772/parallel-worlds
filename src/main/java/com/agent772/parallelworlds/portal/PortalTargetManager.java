package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-portal target dimension mappings. Each portal (identified by its
 * canonical bottom-left position + dimension) stores which exploration dimension
 * it targets. Players cycle through available dimensions by right-clicking.
 */
public final class PortalTargetManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    // GlobalPos (dimension + block pos) → target base dimension ResourceLocation
    private static final Map<GlobalPos, ResourceLocation> portalTargets = new ConcurrentHashMap<>();

    private PortalTargetManager() {}

    /**
     * Initialize from saved data on server start.
     */
    public static void initialize(MinecraftServer server) {
        portalTargets.clear();
        PWSavedData data = PWSavedData.get(server);
        Map<GlobalPos, ResourceLocation> saved = data.getAllPortalTargets();

        // Validate saved targets against current enabled dimensions
        List<ResourceLocation> enabled = getOrderedDimensions();
        for (Map.Entry<GlobalPos, ResourceLocation> entry : saved.entrySet()) {
            if (enabled.contains(entry.getValue())) {
                portalTargets.put(entry.getKey(), entry.getValue());
            } else {
                LOGGER.info("Removing stale portal target {} -> {} (dimension no longer enabled)",
                        entry.getKey(), entry.getValue());
                data.removePortalTarget(entry.getKey());
            }
        }
        LOGGER.info("Loaded {} portal targets", portalTargets.size());
    }

    /**
     * Get the current target base dimension for a portal at the given position.
     * Returns the first enabled dimension if no target is stored.
     */
    public static ResourceLocation getTarget(Level level, BlockPos canonicalPos) {
        GlobalPos gp = GlobalPos.of(level.dimension(), canonicalPos);
        ResourceLocation target = portalTargets.get(gp);
        if (target != null) {
            return target;
        }
        // Default: first enabled dimension
        List<ResourceLocation> dims = getOrderedDimensions();
        return dims.isEmpty() ? null : dims.get(0);
    }

    /**
     * Cycle to the next enabled dimension for the portal and return the new target.
     */
    public static ResourceLocation cycleTarget(ServerLevel level, BlockPos canonicalPos) {
        List<ResourceLocation> dims = getOrderedDimensions();
        if (dims.size() <= 1) {
            // Only one or zero dims — nothing to cycle
            return dims.isEmpty() ? null : dims.get(0);
        }

        GlobalPos gp = GlobalPos.of(level.dimension(), canonicalPos);
        ResourceLocation current = portalTargets.getOrDefault(gp, dims.get(0));
        int idx = dims.indexOf(current);
        int nextIdx = (idx + 1) % dims.size();
        ResourceLocation next = dims.get(nextIdx);

        portalTargets.put(gp, next);

        // Persist
        PWSavedData data = PWSavedData.get(level.getServer());
        data.savePortalTarget(gp, next);

        LOGGER.debug("Portal at {} cycled target: {} -> {}", canonicalPos, current, next);
        return next;
    }

    /**
     * Explicitly set a portal's target dimension.
     */
    public static void setTarget(ServerLevel level, BlockPos canonicalPos, ResourceLocation target) {
        GlobalPos gp = GlobalPos.of(level.dimension(), canonicalPos);
        portalTargets.put(gp, target);

        PWSavedData data = PWSavedData.get(level.getServer());
        data.savePortalTarget(gp, target);
    }

    /**
     * Remove a portal's target mapping (e.g., when portal is destroyed).
     */
    public static void removeTarget(ServerLevel level, BlockPos canonicalPos) {
        GlobalPos gp = GlobalPos.of(level.dimension(), canonicalPos);
        if (portalTargets.remove(gp) != null) {
            PWSavedData data = PWSavedData.get(level.getServer());
            data.removePortalTarget(gp);
            LOGGER.debug("Removed portal target at {}", canonicalPos);
        }
    }

    /**
     * Get ordered list of base dimension ResourceLocations from config.
     */
    public static List<ResourceLocation> getOrderedDimensions() {
        List<String> enabled = PWConfig.getEnabledDimensions();
        if (enabled == null) return List.of();
        List<ResourceLocation> result = new ArrayList<>(enabled.size());
        for (String s : enabled) {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl != null) {
                result.add(rl);
            }
        }
        return result;
    }

    /**
     * Resolve the target to a ServerLevel via DimensionRegistrar.
     */
    public static Optional<ServerLevel> resolveTargetLevel(Level level, BlockPos canonicalPos) {
        ResourceLocation target = getTarget(level, canonicalPos);
        if (target == null) return Optional.empty();

        var registrar = DimensionRegistrar.getInstance();
        return registrar.getExplorationLevel(ResourceKey.create(Registries.DIMENSION, target));
    }

    /**
     * Clear all runtime state (called on server shutdown).
     */
    public static void clearAll() {
        portalTargets.clear();
    }
}
