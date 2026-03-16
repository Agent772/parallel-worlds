package com.agent772.parallelworlds.command;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.data.DimensionMetadata;
import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.dimension.DimensionRegistrar;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.dimension.SeedManager;
import com.agent772.parallelworlds.teleport.TeleportHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * Player-facing /pw commands. list and info are always available.
 * tp and return are gated by config (disabled by default).
 */
public final class PWCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final NumberFormat NUM_FMT = NumberFormat.getInstance();

    private static final SuggestionProvider<CommandSourceStack> DIMENSION_SUGGESTIONS = (ctx, builder) -> {
        DimensionRegistrar registrar = DimensionRegistrar.getInstance();
        Set<ResourceLocation> ids = DimensionRegistrar.getExplorationDimensionIds();
        return SharedSuggestionProvider.suggestResource(ids, builder);
    };

    private PWCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pw")
                .then(Commands.literal("list").executes(PWCommands::listDimensions))
                .then(Commands.literal("info").executes(PWCommands::info))
                .then(Commands.literal("tp")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .suggests(DIMENSION_SUGGESTIONS)
                                .executes(PWCommands::teleport)))
                .then(Commands.literal("return").executes(PWCommands::returnFromExploration))
                .then(Commands.literal("help").executes(PWCommands::help))
                .executes(PWCommands::help)
        );
    }

    // ── /pw list ──

    private static int listDimensions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionRegistrar registrar = DimensionRegistrar.getInstance();
        var dims = registrar.getRuntimeDimensions();

        if (dims.isEmpty()) {
            source.sendFailure(Component.translatable("parallelworlds.command.no_dims_active"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("parallelworlds.command.list.header")
                .withStyle(ChatFormatting.GOLD), false);

        for (var entry : dims.entrySet()) {
            ResourceKey<Level> key = entry.getKey();
            ServerLevel level = entry.getValue();
            int players = level.players().size();

            MutableComponent line = Component.literal(" ")
                    .append(Component.literal(key.location().toString())
                            .withStyle(s -> s
                                    .withColor(ChatFormatting.AQUA)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                            "/pw tp " + key.location()))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.translatable("parallelworlds.command.list.click_to_tp")))));

            line.append(Component.literal(" [" + players + (players == 1 ? " player" : " players") + "]")
                    .withStyle(players > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));

            registrar.getDimensionSeed(key).ifPresent(seed ->
                    line.append(Component.literal(" seed:" + seed)
                            .withStyle(ChatFormatting.DARK_GRAY)));

            source.sendSuccess(() -> line, false);
        }

        source.sendSuccess(() -> Component.translatable("parallelworlds.command.list.total", dims.size())
                .withStyle(ChatFormatting.YELLOW), false);

        // Show rotation time if enabled
        Duration timeUntilReset = SeedManager.getTimeUntilNextReset();
        if (timeUntilReset != null) {
            String timeStr = formatDuration(timeUntilReset);
            source.sendSuccess(() -> Component.translatable("parallelworlds.command.seed_resets_in", timeStr)
                    .withStyle(ChatFormatting.AQUA), false);
        }

        return dims.size();
    }

    // ── /pw info ──

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();

        if (player == null) {
            source.sendFailure(Component.translatable("parallelworlds.command.player_only"));
            return 0;
        }

        ResourceKey<Level> currentDim = player.level().dimension();
        if (!DimensionUtils.isExplorationDimension(currentDim)) {
            source.sendFailure(Component.translatable("parallelworlds.command.not_in_exploration"));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        PWSavedData savedData = PWSavedData.get(player.server);
        DimensionRegistrar registrar = DimensionRegistrar.getInstance();

        source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.header")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.dimension")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(currentDim.location().toString())
                        .withStyle(ChatFormatting.AQUA)), false);

        registrar.getDimensionSeed(currentDim).ifPresent(seed ->
                source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.seed")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(seed))
                                .withStyle(ChatFormatting.WHITE)), false));

        source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.players")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(level.players().size()))
                        .withStyle(ChatFormatting.GREEN)), false);

        savedData.getDimensionMetadata(currentDim.location()).ifPresent(meta -> {
            source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.created")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(TIME_FMT.format(Instant.ofEpochMilli(meta.getCreatedTime())))
                            .withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.translatable("parallelworlds.command.info.visits")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(NUM_FMT.format(meta.getTotalVisits()))
                            .withStyle(ChatFormatting.WHITE)), false);
        });

        return 1;
    }

    // ── /pw tp <dimension> ──

    private static int teleport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!PWConfig.isCommandTpEnabled() && !source.hasPermission(2)) {
            source.sendFailure(Component.translatable("parallelworlds.command.tp.disabled"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("parallelworlds.command.player_only"));
            return 0;
        }

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
        if (dimLoc == null) {
            source.sendFailure(Component.translatable("parallelworlds.command.tp.invalid_dim", dimStr));
            return 0;
        }

        DimensionRegistrar registrar = DimensionRegistrar.getInstance();

        // Look up by exploration key directly
        ServerLevel targetLevel = null;
        for (var entry : registrar.getRuntimeDimensions().entrySet()) {
            if (entry.getKey().location().equals(dimLoc)) {
                targetLevel = entry.getValue();
                break;
            }
        }

        if (targetLevel == null) {
            source.sendFailure(Component.translatable("parallelworlds.command.tp.unknown_dim", dimStr)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Check dimension locks
        if (PWConfig.isDimensionLocksEnabled()) {
            Map<ResourceLocation, ResourceLocation> locks = PWConfig.getParsedDimensionLocks();
            ResourceLocation requiredAdvancement = locks.get(dimLoc);
            if (requiredAdvancement != null) {
                PWSavedData savedData = PWSavedData.get(player.server);
                boolean manuallyUnlocked = savedData.hasManualUnlock(player.getUUID(), dimLoc);
                if (!manuallyUnlocked) {
                    var advancement = player.server.getAdvancements().get(requiredAdvancement);
                    boolean hasAdvancement = advancement != null
                            && player.getAdvancements().getOrStartProgress(advancement).isDone();
                    if (!hasAdvancement) {
                        source.sendFailure(Component.translatable("parallelworlds.command.tp.locked", requiredAdvancement.toString())
                                .withStyle(ChatFormatting.RED));
                        return 0;
                    }
                }
            }
        }

        TeleportHandler.teleportToExploration(player, targetLevel);
        return 1;
    }

    // ── /pw return ──

    private static int returnFromExploration(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!PWConfig.isCommandReturnEnabled() && !source.hasPermission(2)) {
            source.sendFailure(Component.translatable("parallelworlds.command.return.disabled"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("parallelworlds.command.player_only"));
            return 0;
        }

        if (!DimensionUtils.isExplorationDimension(player.level().dimension())) {
            source.sendFailure(Component.translatable("parallelworlds.command.not_in_exploration"));
            return 0;
        }

        TeleportHandler.returnFromExploration(player);
        return 1;
    }

    // ── /pw help ──

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("parallelworlds.command.help.header")
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("/pw list")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.translatable("parallelworlds.command.help.list")
                        .withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("/pw info")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.translatable("parallelworlds.command.help.info")
                        .withStyle(ChatFormatting.GRAY)), false);

        source.sendSuccess(() -> Component.literal("/pw tp <dimension>")
                .withStyle(PWConfig.isCommandTpEnabled() ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY)
                .append(Component.translatable(PWConfig.isCommandTpEnabled()
                                ? "parallelworlds.command.help.tp" : "parallelworlds.command.help.tp_disabled")
                        .withStyle(ChatFormatting.GRAY)), false);

        source.sendSuccess(() -> Component.literal("/pw return")
                .withStyle(PWConfig.isCommandReturnEnabled() ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY)
                .append(Component.translatable(PWConfig.isCommandReturnEnabled()
                                ? "parallelworlds.command.help.return" : "parallelworlds.command.help.return_disabled")
                        .withStyle(ChatFormatting.GRAY)), false);

        source.sendSuccess(() -> Component.literal("/pw help")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.translatable("parallelworlds.command.help.help")
                        .withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    // ── Helpers ──

    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(minutes, 1) + "m";
    }
}
