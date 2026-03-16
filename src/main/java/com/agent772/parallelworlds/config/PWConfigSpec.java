package com.agent772.parallelworlds.config;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * NeoForge config spec — defines all config keys, defaults, comments, and validation.
 * Values are read into PWConfig on load/reload.
 */
public final class PWConfigSpec {
    public static final ModConfigSpec SPEC;

    // General
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_DIMENSIONS;
    public static final ModConfigSpec.IntValue EXPLORATION_BORDER;

    // Seed
    public static final ModConfigSpec.BooleanValue SEED_ROTATION_ENABLED;
    public static final ModConfigSpec.EnumValue<ResetSchedule> RESET_SCHEDULE;
    public static final ModConfigSpec.EnumValue<WeekDay> RESET_DAY_OF_WEEK;
    public static final ModConfigSpec.IntValue RESET_DAY_OF_MONTH;
    public static final ModConfigSpec.IntValue RESET_HOUR;
    public static final ModConfigSpec.IntValue RESET_MINUTE;
    public static final ModConfigSpec.LongValue DEBUG_SEED;

    // Portal
    public static final ModConfigSpec.ConfigValue<String> PORTAL_FRAME_BLOCK;
    public static final ModConfigSpec.ConfigValue<String> PORTAL_IGNITER_ITEM;
    public static final ModConfigSpec.ConfigValue<String> PORTAL_CYCLE_ITEM;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSION_PARTICLE_COLORS;
    public static final ModConfigSpec.BooleanValue PORTAL_ENABLED;
    public static final ModConfigSpec.IntValue PORTAL_DELAY_TICKS;
    public static final ModConfigSpec.IntValue PORTAL_MAX_SIZE;
    public static final ModConfigSpec.BooleanValue PORTAL_BUILDING_IN_EXPLORATION;

    // Restrictions
    public static final ModConfigSpec.EnumValue<RestrictionMode> ENDER_CHEST_MODE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RESTRICTED_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RESTRICTED_BLOCKS;
    public static final ModConfigSpec.EnumValue<RestrictionMode> RESTRICTED_ITEM_MODE;

    // Persistence
    public static final ModConfigSpec.EnumValue<PersistenceMode> PERSISTENCE_MODE;
    public static final ModConfigSpec.IntValue OLD_DIMENSION_RETENTION_COUNT;

    // Performance
    public static final ModConfigSpec.IntValue CHUNK_UNLOAD_DELAY;
    public static final ModConfigSpec.IntValue MAX_CHUNKS_PER_PLAYER;
    public static final ModConfigSpec.BooleanValue AGGRESSIVE_CHUNK_UNLOADING;
    public static final ModConfigSpec.BooleanValue PREVENT_DISK_SAVES;
    public static final ModConfigSpec.BooleanValue PREGEN_ENABLED;
    public static final ModConfigSpec.IntValue PREGEN_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue PREGEN_MAX_TICK_MS;
    public static final ModConfigSpec.DoubleValue PREGEN_MIN_TPS;
    public static final ModConfigSpec.DoubleValue PREGEN_MEMORY_THRESHOLD;

    // Async chunk generation
    public static final ModConfigSpec.BooleanValue ASYNC_CHUNK_GEN_ENABLED;
    public static final ModConfigSpec.IntValue ASYNC_MAX_IN_FLIGHT;
    public static final ModConfigSpec.IntValue ASYNC_WORKER_THREADS;
    public static final ModConfigSpec.BooleanValue ASYNC_CHUNK_HINTS_ENABLED;
    public static final ModConfigSpec.IntValue ASYNC_HINT_DISTANCE;

    // Safety
    public static final ModConfigSpec.IntValue TELEPORT_COOLDOWN;
    public static final ModConfigSpec.IntValue TELEPORT_SAFETY_RADIUS;
    public static final ModConfigSpec.BooleanValue KEEP_INVENTORY_IN_EXPLORATION;
    public static final ModConfigSpec.BooleanValue CLEAR_INVENTORY_ON_RETURN;
    public static final ModConfigSpec.BooleanValue PREVENT_EXPLORATION_SPAWN;

    // Dimension locks
    public static final ModConfigSpec.BooleanValue DIMENSION_LOCKS_ENABLED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSION_LOCKS;

    // Compatibility
    public static final ModConfigSpec.BooleanValue MOD_COMPAT_CLEANUP_ENABLED;

    // Commands
    public static final ModConfigSpec.BooleanValue COMMAND_TP_ENABLED;
    public static final ModConfigSpec.BooleanValue COMMAND_RETURN_ENABLED;

    // Teleport blocking
    public static final ModConfigSpec.BooleanValue BLOCK_VANILLA_TP_INTO;
    public static final ModConfigSpec.BooleanValue BLOCK_VANILLA_TP_INSIDE;
    public static final ModConfigSpec.BooleanValue BLOCK_TEMPAD_PORTAL_INTO;
    public static final ModConfigSpec.BooleanValue BLOCK_TEMPAD_PORTAL_INSIDE;

    public enum ResetSchedule { DAILY, WEEKLY, MONTHLY }
    public enum WeekDay { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }
    public enum RestrictionMode { BLOCK, WARN, ALLOW }
    public enum PersistenceMode { PERSIST_UNTIL_ROTATION, REGENERATE_EACH_RESTART }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // ── General ──
        builder.push("general");
        ENABLED_DIMENSIONS = builder
                .comment("List of dimension base types to create as mod:dimension ResourceLocations.",
                         "Vanilla: minecraft:overworld, minecraft:the_nether, minecraft:the_end",
                         "Modded dimensions are also supported (e.g. modid:my_dimension).")
                .defineListAllowEmpty("enabledDimensions",
                        List.of("minecraft:overworld"),
                        () -> "minecraft:overworld",
                        o -> o instanceof String s && s.contains(":") && ResourceLocation.tryParse(s) != null);
        EXPLORATION_BORDER = builder
                .comment("", "World border radius for exploration dimensions (-1 = inherit from parent world)")
                .defineInRange("explorationBorder", -1, -1, 1_000_000);
        builder.pop();

        // ── Seed ──
        builder.push("seed");
        SEED_ROTATION_ENABLED = builder
                .comment("Whether seeds rotate on a schedule. If false, seeds are generated once and kept forever.")
                .define("seedRotationEnabled", true);
        RESET_SCHEDULE = builder
                .comment("",
                         "How often to rotate seeds: DAILY, WEEKLY, MONTHLY. Only applies if seedRotationEnabled=true.",
                         "Rotation takes effect on the next server restart after the scheduled time.")
                .defineEnum("resetSchedule", ResetSchedule.WEEKLY);
        RESET_DAY_OF_WEEK = builder
                .comment("", "Day of week for WEEKLY rotation (ignored for DAILY/MONTHLY)")
                .defineEnum("resetDayOfWeek", WeekDay.MONDAY);
        RESET_DAY_OF_MONTH = builder
                .comment("", "Day of month for MONTHLY rotation, 1-28 (ignored for DAILY/WEEKLY)")
                .defineInRange("resetDayOfMonth", 1, 1, 28);
        RESET_HOUR = builder
                .comment("", "Hour of day (0-23) when the reset becomes eligible. Takes effect on next server restart.")
                .defineInRange("resetHour", 0, 0, 23);
        RESET_MINUTE = builder
                .comment("", "Minute of hour (0-59) when the reset becomes eligible. Takes effect on next server restart.")
                .defineInRange("resetMinute", 0, 0, 59);
        DEBUG_SEED = builder
                .comment("", "Fixed seed override for testing. 0 = disabled (use rotation system)")
                .defineInRange("debugSeed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        builder.pop();

        // ── Portal ──
        builder.push("portal");
        PORTAL_FRAME_BLOCK = builder
                .comment("Block used for portal frame (resource location, e.g. minecraft:glass)")
                .define("portalFrameBlock", "minecraft:glass");
        PORTAL_IGNITER_ITEM = builder
                .comment("", "Item used to ignite the portal (resource location)")
                .define("portalIgniterItem", "minecraft:flint_and_steel");
        PORTAL_CYCLE_ITEM = builder
                .comment("",
                         "Item used to cycle the portal's target dimension (right-click portal frame).",
                         "Set to empty string to disable cycling (first enabled dimension only).")
                .define("portalCycleItem", "minecraft:ender_eye");
        DIMENSION_PARTICLE_COLORS = builder
                .comment("",
                         "Portal particle color per dimension as 'mod:dimension=#RRGGBB'.",
                         "Dimensions not listed here use the default color (cyan: #33AAEE).",
                         "Example: minecraft:overworld=#33CC4D, minecraft:the_nether=#E64D19")
                .defineListAllowEmpty("dimensionParticleColors",
                        List.of("minecraft:overworld=#33CC4D", "minecraft:the_nether=#E64D19", "minecraft:the_end=#B233E6"),
                        () -> "minecraft:overworld=#33CC4D",
                        o -> o instanceof String s && s.contains("=") && s.contains(":"));
        PORTAL_ENABLED = builder
                .comment("", "Whether the custom portal system is enabled")
                .define("portalEnabled", true);
        PORTAL_DELAY_TICKS = builder
                .comment("", "Ticks a player must stand in the portal before teleporting (default 80 = 4 seconds)")
                .defineInRange("portalDelayTicks", 80, 10, 600);
        PORTAL_MAX_SIZE = builder
                .comment("", "Maximum inner size of a portal frame (width or height)")
                .defineInRange("portalMaxSize", 21, 2, 64);
        PORTAL_BUILDING_IN_EXPLORATION = builder
                .comment("",
                         "Allow players to build new portals inside exploration dimensions.",
                         "If false, players must walk back to the auto-generated entry portal to leave.",
                         "If true (default), players can build additional portals that also lead home.")
                .define("portalBuildingInExploration", true);
        builder.pop();

        // ── Restrictions ──
        builder.push("restrictions");
        ENDER_CHEST_MODE = builder
                .comment("Ender chest behavior in mining dimensions: BLOCK, WARN, ALLOW")
                .defineEnum("enderChestMode", RestrictionMode.BLOCK);
        RESTRICTED_ITEMS = builder
                .comment("",
                         "Restricted items in mining dimensions.",
                         "Each entry can be: '*' (all items), '#namespace:tag' (tag), 'namespace:id' (exact),",
                         "or 'namespace:id*' (prefix wildcard, e.g. 'minecraft:bamboo*' matches all bamboo items).",
                         "Example: ['minecraft:ender_chest', 'minecraft:bamboo*', '#minecraft:logs']")
                .defineListAllowEmpty("restrictedItems",
                        List.of("minecraft:ender_chest"),
                        () -> "",
                        o -> o instanceof String);
        RESTRICTED_BLOCKS = builder
                .comment("",
                         "Restricted blocks in mining dimensions (placement & interaction).",
                         "Each entry can be: '*' (all blocks), '#namespace:tag' (tag), 'namespace:id' (exact),",
                         "or 'namespace:id*' (prefix wildcard, e.g. 'minecraft:bamboo*' matches all bamboo blocks).",
                         "Example: ['minecraft:ender_chest', 'minecraft:shulker*', '#minecraft:logs']")
                .defineListAllowEmpty("restrictedBlocks",
                        List.of("minecraft:ender_chest"),
                        () -> "",
                        o -> o instanceof String);
        RESTRICTED_ITEM_MODE = builder
                .comment("", "Restricted item/block behavior in mining dimensions: BLOCK, WARN, ALLOW")
                .defineEnum("restrictedItemMode", RestrictionMode.BLOCK);
        builder.pop();

        // ── Persistence ──
        builder.push("persistence");
        PERSISTENCE_MODE = builder
                .comment("PERSIST_UNTIL_ROTATION: keep dimension data until seed changes. REGENERATE_EACH_RESTART: fresh each server start")
                .defineEnum("persistenceMode", PersistenceMode.PERSIST_UNTIL_ROTATION);
        OLD_DIMENSION_RETENTION_COUNT = builder
                .comment("", "Number of old dimension folders to keep before deleting (-1 = keep all)")
                .defineInRange("oldDimensionRetentionCount", 2, -1, 100);
        builder.pop();

        // ── Performance ──
        builder.push("performance");
        CHUNK_UNLOAD_DELAY = builder
                .comment("Ticks before unloading chunks with no players (default 300 = 15s)")
                .defineInRange("chunkUnloadDelay", 300, 20, 6000);
        MAX_CHUNKS_PER_PLAYER = builder
                .comment("", "Maximum forced chunks per player in mining dimensions")
                .defineInRange("maxChunksPerPlayer", 25, 1, 256);
        AGGRESSIVE_CHUNK_UNLOADING = builder
                .comment("", "Aggressively unload chunks when no players are in the dimension")
                .define("aggressiveChunkUnloading", true);
        PREVENT_DISK_SAVES = builder
                .comment("", "Prevent mining dimension chunks from saving to disk (saves I/O)")
                .define("preventDiskSaves", false);
        PREGEN_ENABLED = builder
                .comment("", "Enable chunk pre-generation for mining dimensions")
                .define("pregenEnabled", false);
        PREGEN_CHUNKS_PER_TICK = builder
                .comment("", "Maximum chunks to process per server tick during pre-generation")
                .defineInRange("pregenChunksPerTick", 2, 1, 20);
        PREGEN_MAX_TICK_MS = builder
                .comment("", "Maximum milliseconds to spend on pre-generation per tick")
                .defineInRange("pregenMaxTickMs", 5, 1, 50);
        PREGEN_MIN_TPS = builder
                .comment("", "Minimum TPS before pausing pre-generation")
                .defineInRange("pregenMinTps", 18.0, 5.0, 20.0);
        PREGEN_MEMORY_THRESHOLD = builder
                .comment("", "Pause pre-generation when heap usage exceeds this fraction (0.0-1.0)")
                .defineInRange("pregenMemoryThreshold", 0.85, 0.5, 0.99);
        ASYNC_CHUNK_GEN_ENABLED = builder
                .comment("",
                         "Enable async chunk noise computation for pre-generation and exploration.",
                         "Workers compute terrain data off-thread, improving pre-gen throughput.",
                         "Falls back to synchronous generation when disabled.")
                .define("asyncChunkGenEnabled", true);
        ASYNC_MAX_IN_FLIGHT = builder
                .comment("",
                         "Maximum chunks in the async compute pipeline.",
                         "Higher values use more memory but increase throughput.")
                .defineInRange("asyncMaxInFlight", 32, 4, 256);
        ASYNC_WORKER_THREADS = builder
                .comment("",
                         "Worker thread count for async chunk computation.",
                         "-1 = auto (availableProcessors - 2, minimum 1)")
                .defineInRange("asyncWorkerThreads", -1, -1, 32);
        ASYNC_CHUNK_HINTS_ENABLED = builder
                .comment("",
                         "Enable predictive chunk hints when players move in exploration dimensions.",
                         "Pre-computes terrain for chunks in the player's movement direction.")
                .define("asyncChunkHintsEnabled", true);
        ASYNC_HINT_DISTANCE = builder
                .comment("", "How many chunks to predict ahead of player movement direction.")
                .defineInRange("asyncHintDistance", 3, 1, 8);
        builder.pop();

        // ── Safety ──
        builder.push("safety");
        TELEPORT_COOLDOWN = builder
                .comment("Cooldown in seconds between teleports (0 = no cooldown)")
                .defineInRange("teleportCooldown", 5, 0, 300);
        TELEPORT_SAFETY_RADIUS = builder
                .comment("", "Radius to search for safe spawn position")
                .defineInRange("teleportSafetyRadius", 32, 4, 128);
        KEEP_INVENTORY_IN_EXPLORATION = builder
                .comment("", "Players keep inventory on death in mining dimensions")
                .define("keepInventoryInExploration", false);
        CLEAR_INVENTORY_ON_RETURN = builder
                .comment("", "Clear player inventory when returning from mining dimension")
                .define("clearInventoryOnReturn", false);
        PREVENT_EXPLORATION_SPAWN = builder
                .comment("",
                         "Prevent players from respawning inside exploration dimensions.",
                         "If a player sets their bed/respawn anchor in a mining dimension and dies,",
                         "they will respawn in the overworld instead. Recommended to keep enabled",
                         "since exploration dimensions are temporary and may be deleted on seed rotation.")
                .define("preventExplorationSpawn", true);
        BLOCK_VANILLA_TP_INTO = builder
                .comment("",
                         "Block non-operator players from teleporting INTO an exploration dimension from outside.",
                         "Covers vanilla /tp AND FTB Essentials /home, /back, /warp, /tpa.",
                         "Portal teleports and /pw tp are not affected by this setting.")
                .define("blockVanillaTeleportInto", false);
        BLOCK_VANILLA_TP_INSIDE = builder
                .comment("",
                         "Block non-operator players from teleporting WHILE INSIDE an exploration dimension.",
                         "Covers vanilla /tp AND FTB Essentials /home, /back, /warp, /tpa.",
                         "This prevents position-hopping and cross-dim escapes inside the mining world.")
                .define("blockVanillaTeleportInside", false);
        BLOCK_TEMPAD_PORTAL_INTO = builder
                .comment("",
                         "Block non-operator players from opening a Tempad portal INTO an exploration dimension.",
                         "Requires Tempad mod to be installed. Has no effect without it.")
                .define("blockTempadPortalInto", false);
        BLOCK_TEMPAD_PORTAL_INSIDE = builder
                .comment("",
                         "Block non-operator players from opening a Tempad portal WHILE INSIDE an exploration dimension.",
                         "Requires Tempad mod to be installed. Has no effect without it.")
                .define("blockTempadPortalInside", false);
        builder.pop();

        // ── Dimension Locks ──
        builder.push("locks");
        DIMENSION_LOCKS_ENABLED = builder
                .comment("Enable dimension lock system (require advancements to access dimensions)")
                .define("dimensionLocksEnabled", false);
        DIMENSION_LOCKS = builder
                .comment("",
                         "List of dimension lock entries as 'mod:dimension=mod:advancement_id'.",
                         "Players must complete the advancement to access the dimension.",
                         "Example: minecraft:the_nether=minecraft:story/enter_the_nether")
                .defineListAllowEmpty("dimensionLocks",
                        List.of(),
                        () -> "",
                        o -> o instanceof String s && s.contains("=") && s.contains(":"));
        builder.pop();

        // ── Compatibility ──
        builder.push("compatibility");
        MOD_COMPAT_CLEANUP_ENABLED = builder
                .comment("Enable automatic cleanup of third-party mod cached data",
                         "(Xaero's Map, JourneyMap, Distant Horizons) when exploration",
                         "dimensions are regenerated or deleted.")
                .define("modCompatCleanupEnabled", true);
        builder.pop();

        // ── Commands ──
        builder.push("commands");
        COMMAND_TP_ENABLED = builder
                .comment("Allow players to use /pw tp <dimension> to teleport into exploration dimensions")
                .define("commandTpEnabled", false);
        COMMAND_RETURN_ENABLED = builder
                .comment("", "Allow players to use /pw return to return from exploration dimensions")
                .define("commandReturnEnabled", false);
        builder.pop();

        SPEC = builder.build();
    }

    private PWConfigSpec() {}
}
