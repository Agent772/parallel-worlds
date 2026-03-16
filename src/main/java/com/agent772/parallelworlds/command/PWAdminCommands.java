package com.agent772.parallelworlds.command;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.DimensionMetadata;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.dimension.DimensionManager;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.generation.ChunkPreGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Admin commands under /pwadmin. Always require permission level 2 (op).
 */
public final class PWAdminCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final NumberFormat NUM_FMT = NumberFormat.getInstance();

    private static Supplier<DimensionManager> dimensionManagerSupplier;
    private static Supplier<ChunkPreGenerator> chunkPreGeneratorSupplier;

    private static final SuggestionProvider<CommandSourceStack> DIMENSION_SUGGESTIONS = (ctx, builder) -> {
        Set<ResourceLocation> ids = DimensionRegistrar.getExplorationDimensionIds();
        return SharedSuggestionProvider.suggestResource(ids, builder);
    };

    private PWAdminCommands() {}

    /**
     * Supply runtime instances that are created during server start.
     * Must be called before commands are executed (during ServerStartingEvent).
     */
    public static void setRuntimeSuppliers(Supplier<DimensionManager> dimMgr,
                                           Supplier<ChunkPreGenerator> preGen) {
        dimensionManagerSupplier = dimMgr;
        chunkPreGeneratorSupplier = preGen;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pwadmin")
                .requires(s -> s.hasPermission(2))
                // Management
                .then(Commands.literal("returnall").executes(PWAdminCommands::returnAll))
                .then(Commands.literal("info")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .suggests(DIMENSION_SUGGESTIONS)
                                .executes(PWAdminCommands::dimInfo)))
                .then(Commands.literal("stats").executes(PWAdminCommands::stats))
                .then(Commands.literal("seed")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .suggests(DIMENSION_SUGGESTIONS)
                                .executes(PWAdminCommands::seed)))
                .then(Commands.literal("reload").executes(PWAdminCommands::reload))
                // Pre-generation
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start")
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(ctx -> pregenStart(ctx, 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                                .executes(ctx -> pregenStart(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::pregenStop)))
                        .then(Commands.literal("pause")
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::pregenPause)))
                        .then(Commands.literal("resume")
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::pregenResume)))
                        .then(Commands.literal("status")
                                .executes(PWAdminCommands::pregenStatusAll)
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::pregenStatus)))
                        .then(Commands.literal("stopall").executes(PWAdminCommands::pregenStopAll)))
                // Progression
                .then(Commands.literal("unlock")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::unlock))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("dimension", StringArgumentType.string())
                                        .suggests(DIMENSION_SUGGESTIONS)
                                        .executes(PWAdminCommands::lock))))
                .then(Commands.literal("unlocks")
                        .executes(PWAdminCommands::unlocksList)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(PWAdminCommands::unlocksPlayer)))
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  Management commands
    // ═══════════════════════════════════════════════════════════════

    private static int returnAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionManager mgr = getDimensionManager();
        if (mgr == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.dim_manager_unavailable"));
            return 0;
        }
        mgr.evacuateAllPlayers();
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.returnall.success")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int dimInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
        if (dimLoc == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        PWSavedData savedData = PWSavedData.get(source.getServer());
        var metaOpt = savedData.getDimensionMetadata(dimLoc);
        if (metaOpt.isEmpty()) {
            source.sendFailure(Component.translatable("parallelworlds.admin.no_metadata", dimStr));
            return 0;
        }

        DimensionMetadata meta = metaOpt.get();
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.header", dimStr)
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.created",
                        TIME_FMT.format(Instant.ofEpochMilli(meta.getCreatedTime())))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.last_access",
                        TIME_FMT.format(Instant.ofEpochMilli(meta.getLastAccessTime())))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.unique_visitors",
                        String.valueOf(meta.getAccessedBy().size()))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.total_visits",
                        NUM_FMT.format(meta.getTotalVisits()))
                .withStyle(ChatFormatting.GRAY), false);

        // Current players in dimension
        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
        ServerLevel level = source.getServer().getLevel(levelKey);
        if (level != null) {
            int count = level.players().size();
            source.sendSuccess(() -> Component.translatable("parallelworlds.admin.info.online_now",
                            String.valueOf(count))
                    .withStyle(count > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionRegistrar registrar = DimensionRegistrar.getInstance();
        var dims = registrar.getRuntimeDimensions();

        int totalPlayers = 0;
        for (ServerLevel level : dims.values()) {
            totalPlayers += level.players().size();
        }
        int finalTotalPlayers = totalPlayers;

        PWSavedData savedData = PWSavedData.get(source.getServer());
        int totalVisits = 0;
        for (ResourceLocation dimId : savedData.getActiveDimensions()) {
            var meta = savedData.getDimensionMetadata(dimId);
            if (meta.isPresent()) {
                totalVisits += meta.get().getTotalVisits();
            }
        }
        int finalTotalVisits = totalVisits;

        ChunkPreGenerator pregen = getChunkPreGenerator();
        boolean hasPregen = pregen != null && pregen.hasActiveTasks();

        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.stats.header")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.stats.active_dims",
                        String.valueOf(dims.size()))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.stats.players",
                        String.valueOf(finalTotalPlayers))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.stats.total_visits",
                        NUM_FMT.format(finalTotalVisits))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.stats.pregen_active",
                        hasPregen ? "Yes" : "No")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int seed(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
        if (dimLoc == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        DimensionRegistrar registrar = DimensionRegistrar.getInstance();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimLoc);
        var seedOpt = registrar.getDimensionSeed(key);
        if (seedOpt.isEmpty()) {
            source.sendFailure(Component.translatable("parallelworlds.admin.seed.not_found", dimStr));
            return 0;
        }

        long seedVal = seedOpt.get();
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.seed.result", dimStr, String.valueOf(seedVal))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PWConfig.refresh();
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.config_reloaded")
                .withStyle(ChatFormatting.GREEN), true);
        LOGGER.info("Config reloaded by {}", source.getTextName());
        return 1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pre-generation commands
    // ═══════════════════════════════════════════════════════════════

    private static int pregenStart(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceKey<Level> key = parseDimensionKey(dimStr);
        if (key == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        String result = pregen.startGeneration(source.getServer(), key, radius);
        source.sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int pregenStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceKey<Level> key = parseDimensionKey(dimStr);
        if (key == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        String result = pregen.stopGeneration(key, source.getServer());
        source.sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int pregenPause(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceKey<Level> key = parseDimensionKey(dimStr);
        if (key == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        String result = pregen.pauseGeneration(key);
        source.sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int pregenResume(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceKey<Level> key = parseDimensionKey(dimStr);
        if (key == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        String result = pregen.resumeGeneration(key);
        source.sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int pregenStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceKey<Level> key = parseDimensionKey(dimStr);
        if (key == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        String result = pregen.getStatus(key);
        source.sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int pregenStatusAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }

        if (!pregen.hasActiveTasks()) {
            source.sendSuccess(() -> Component.translatable("parallelworlds.admin.pregen.no_tasks")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.pregen.header")
                .withStyle(ChatFormatting.GOLD), false);
        DimensionRegistrar registrar = DimensionRegistrar.getInstance();
        for (ResourceKey<Level> key : registrar.getRuntimeDimensions().keySet()) {
            String status = pregen.getStatus(key);
            if (!status.startsWith("No active")) {
                source.sendSuccess(() -> Component.literal(status).withStyle(ChatFormatting.AQUA), false);
            }
        }
        return 1;
    }

    private static int pregenStopAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkPreGenerator pregen = getChunkPreGenerator();
        if (pregen == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.pregen.unavailable"));
            return 0;
        }
        pregen.stopAll(source.getServer());
        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.pregen.stopall")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Progression commands
    // ═══════════════════════════════════════════════════════════════

    private static int unlock(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
        if (dimLoc == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        PWSavedData savedData = PWSavedData.get(source.getServer());
        savedData.grantManualUnlock(target.getUUID(), dimLoc);

        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.unlock.success", dimStr, target.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
        target.displayClientMessage(Component.translatable("parallelworlds.admin.unlock.player_msg", dimStr)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int lock(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
        if (dimLoc == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.invalid_dim", dimStr));
            return 0;
        }

        PWSavedData savedData = PWSavedData.get(source.getServer());
        savedData.revokeManualUnlock(target.getUUID(), dimLoc);

        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.lock.success", dimStr, target.getName().getString())
                .withStyle(ChatFormatting.YELLOW), true);
        target.displayClientMessage(Component.translatable("parallelworlds.admin.lock.player_msg", dimStr)
                .withStyle(ChatFormatting.RED), false);
        return 1;
    }

    private static int unlocksPlayer(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        return showUnlocks(source, target.getUUID(), target.getName().getString());
    }

    private static int unlocksList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("parallelworlds.admin.unlocks.specify_player"));
            return 0;
        }
        return showUnlocks(source, player.getUUID(), player.getName().getString());
    }

    private static int showUnlocks(CommandSourceStack source, UUID playerId, String playerName) {
        PWSavedData savedData = PWSavedData.get(source.getServer());
        Set<ResourceLocation> unlocks = savedData.getManualUnlocks(playerId);

        if (unlocks.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("parallelworlds.admin.unlocks.none", playerName)
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("parallelworlds.admin.unlocks.header", playerName)
                .withStyle(ChatFormatting.GOLD), false);
        for (ResourceLocation dim : unlocks) {
            source.sendSuccess(() -> Component.literal(" - " + dim)
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return unlocks.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static ResourceKey<Level> parseDimensionKey(String dimStr) {
        ResourceLocation loc = ResourceLocation.tryParse(dimStr);
        if (loc == null) return null;
        return ResourceKey.create(Registries.DIMENSION, loc);
    }

    private static DimensionManager getDimensionManager() {
        return dimensionManagerSupplier != null ? dimensionManagerSupplier.get() : null;
    }

    private static ChunkPreGenerator getChunkPreGenerator() {
        return chunkPreGeneratorSupplier != null ? chunkPreGeneratorSupplier.get() : null;
    }
}
