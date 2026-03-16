package com.agent772.parallelworlds.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transient in-memory store for player inventories saved on death in
 * exploration dimensions (when keepInventoryInExploration is enabled).
 * Inventories are restored on respawn and then discarded.
 */
public final class InventoryKeeper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, List<ItemStack>> savedInventories = new ConcurrentHashMap<>();

    private InventoryKeeper() {}

    /**
     * Snapshot the player's full inventory (main + armor + offhand).
     */
    public static void saveInventory(ServerPlayer player) {
        Inventory inv = player.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            snapshot.add(inv.getItem(i).copy());
        }
        savedInventories.put(player.getUUID(), snapshot);
        LOGGER.debug("Saved inventory for {} ({} slots)", player.getName().getString(), snapshot.size());
    }

    /**
     * Restore a previously saved inventory onto the player, replacing
     * whatever they currently hold. Clears the saved data afterwards.
     */
    public static void restoreInventory(ServerPlayer player) {
        List<ItemStack> snapshot = savedInventories.remove(player.getUUID());
        if (snapshot == null) return;

        Inventory inv = player.getInventory();
        for (int i = 0; i < Math.min(snapshot.size(), inv.getContainerSize()); i++) {
            inv.setItem(i, snapshot.get(i));
        }
        LOGGER.debug("Restored inventory for {}", player.getName().getString());
    }

    public static boolean hasSavedInventory(UUID playerId) {
        return savedInventories.containsKey(playerId);
    }

    /** Called on server shutdown to prevent stale data across restarts. */
    public static void clearAll() {
        savedInventories.clear();
    }
}
