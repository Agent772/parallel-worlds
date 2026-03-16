package com.agent772.parallelworlds;

import com.agent772.parallelworlds.command.PWAdminCommands;
import com.agent772.parallelworlds.command.PWCommands;
import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.agent772.parallelworlds.dimension.*;
import com.agent772.parallelworlds.event.PWEventHandlers;
import com.agent772.parallelworlds.generation.ChunkPreGenerator;
import com.agent772.parallelworlds.generation.async.AsyncChunkHint;
import com.agent772.parallelworlds.generation.async.AsyncChunkWorkerPool;
import com.agent772.parallelworlds.network.PWNetworking;
import com.agent772.parallelworlds.performance.ChunkManager;
import com.agent772.parallelworlds.portal.DimensionColors;
import com.agent772.parallelworlds.portal.PWPortalBlock;
import com.agent772.parallelworlds.portal.PortalActivation;
import com.agent772.parallelworlds.portal.PortalTargetManager;
import com.agent772.parallelworlds.registry.PWBlocks;
import com.agent772.parallelworlds.registry.PWItems;
import com.agent772.parallelworlds.restriction.RestrictionHandler;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.agent772.parallelworlds.compat.tempad.PWTempadCompat;
import com.agent772.parallelworlds.util.InventoryKeeper;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(ParallelWorlds.MOD_ID)
public class ParallelWorlds {
    public static final String MOD_ID = "parallelworlds";
    private static final Logger LOGGER = LogUtils.getLogger();

    private DimensionManager dimensionManager;
    private ChunkPreGenerator chunkPreGenerator;
    private ChunkManager chunkManager;
    private AsyncChunkWorkerPool asyncWorkerPool;

    public ParallelWorlds(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Parallel Worlds initializing...");

        // Register blocks
        PWBlocks.BLOCKS.register(modEventBus);

        // Register items
        PWItems.ITEMS.register(modEventBus);

        // Register server config
        modContainer.registerConfig(ModConfig.Type.SERVER, PWConfigSpec.SPEC);

        // Listen for config loading/reloading on the mod bus
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        // Register network payloads on the mod bus
        modEventBus.addListener(PWNetworking::registerPayloads);

        // Register server lifecycle events on the game bus
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Register portal activation handler
        NeoForge.EVENT_BUS.register(PortalActivation.class);

        // Register restriction handler
        NeoForge.EVENT_BUS.register(RestrictionHandler.class);

        // Register player lifecycle event handlers
        NeoForge.EVENT_BUS.register(PWEventHandlers.class);

        // Register Tempad portal blocking compat (only when Tempad is loaded)
        if (ModList.get().isLoaded("tempad")) {
            NeoForge.EVENT_BUS.register(PWTempadCompat.class);
            LOGGER.info("Parallel Worlds: Tempad compat enabled");
        }
    }

    private void onConfigLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER) {
            PWConfig.refresh();
            DimensionColors.refresh();
            LOGGER.info("Parallel Worlds config loaded");
        }
    }

    private void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER) {
            PWConfig.refresh();
            DimensionColors.refresh();
            LOGGER.info("Parallel Worlds config reloaded");
        }
    }

    private void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("Parallel Worlds: server starting");
        var server = event.getServer();

        // Initialize counters from saved data
        DimensionCounter.initialize(server);

        // Clean up old dimension folders
        DimensionCleanup.cleanupOldDimensions(server);

        // Create exploration dimensions (uses PWSavedData for seed persistence)
        DimensionRegistrar.initialize();
        DimensionRegistrar.getInstance().createDimensionsOnServerStart(server);

        // Initialize teleport handler with persistence
        TeleportHandler.initialize(server);

        // Initialize portal target manager (loads saved targets)
        PortalTargetManager.initialize(server);

        // Initialize runtime manager
        dimensionManager = new DimensionManager(server,
                DimensionRegistrar.getInstance().getRegisteredDimensions());

        // Initialize chunk management
        chunkManager = new ChunkManager();

        // Initialize pre-generator and resume any saved tasks
        chunkPreGenerator = new ChunkPreGenerator();

        // Initialize async worker pool if enabled
        if (PWConfig.isAsyncChunkGenEnabled()) {
            asyncWorkerPool = new AsyncChunkWorkerPool(
                    PWConfig.getAsyncMaxInFlight(),
                    PWConfig.getAsyncWorkerThreads());
            chunkPreGenerator.setWorkerPool(asyncWorkerPool);
            AsyncChunkHint.setWorkerPool(asyncWorkerPool);
        }

        if (PWConfig.isPregenEnabled()) {
            chunkPreGenerator.resumeSavedTasks(server);
        }

        // Supply runtime instances to admin commands and event handlers
        PWAdminCommands.setRuntimeSuppliers(() -> dimensionManager, () -> chunkPreGenerator);
        PWEventHandlers.setDimensionManagerSupplier(() -> dimensionManager);
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        PWCommands.register(event.getDispatcher());
        PWAdminCommands.register(event.getDispatcher());
        LOGGER.info("Parallel Worlds commands registered");
    }

    private void onServerTick(final ServerTickEvent.Post event) {
        var server = event.getServer();

        // Tick pre-generator
        if (chunkPreGenerator != null && PWConfig.isPregenEnabled()) {
            chunkPreGenerator.tick(server);
        }

        // Periodic chunk cleanup
        if (chunkManager != null) {
            chunkManager.performCleanup(server);
        }
    }

    private void onServerStopping(final ServerStoppingEvent event) {
        LOGGER.info("Parallel Worlds: server stopping");

        // Shut down async worker pool
        if (asyncWorkerPool != null) {
            asyncWorkerPool.shutdown(5000);
            asyncWorkerPool = null;
        }

        // Save pre-gen progress
        if (chunkPreGenerator != null) {
            chunkPreGenerator.shutdown(event.getServer());
            chunkPreGenerator = null;
        }

        chunkManager = null;

        // Evacuate players from exploration dimensions
        if (dimensionManager != null) {
            dimensionManager.evacuateAllPlayers();
            dimensionManager.clearCaches();
            dimensionManager = null;
        }

        // Save counters
        DimensionCounter.saveIfDirty();
    }

    private void onServerStopped(final ServerStoppedEvent event) {
        LOGGER.info("Parallel Worlds: server stopped");

        // Clear all runtime state
        DimensionRegistrar.cleanupOnShutdown();
        ExplorationSeedManager.clearAll();
        TeleportHandler.clearAll();
        PWPortalBlock.clearAll();
        PortalTargetManager.clearAll();
        InventoryKeeper.clearAll();
        AsyncChunkHint.clearAll();
        AsyncChunkWorkerPool.clearAllCaches();
        com.agent772.parallelworlds.client.PWClientHandler.clearAll();
    }

    public DimensionManager getDimensionManager() {
        return dimensionManager;
    }

    public ChunkPreGenerator getChunkPreGenerator() {
        return chunkPreGenerator;
    }
}
