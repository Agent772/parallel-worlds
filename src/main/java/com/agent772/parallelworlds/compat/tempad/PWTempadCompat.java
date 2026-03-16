package com.agent772.parallelworlds.compat.tempad;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import earth.terrarium.tempad.api.event.TimedoorEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Hooks into Tempad's TimedoorEvent.Open to block portal opening into/inside exploration dimensions.
 * Registered conditionally — only when Tempad is loaded at runtime.
 */
public final class PWTempadCompat {

    @SubscribeEvent
    public static void onTimedoorOpen(TimedoorEvent.Open event) {
        var timedoor = event.getEntity();
        var targetDim = timedoor.getTargetDimension();
        var sourceDim = timedoor.level().dimension();

        // OP bypass — look up the opener as a live player
        ServerPlayer opener = ((ServerLevel) timedoor.level())
                .getServer()
                .getPlayerList()
                .getPlayer(event.getOpener().getId());
        if (opener != null && opener.hasPermissions(2)) return;

        // Block Tempad portal INTO an exploration dimension
        if (PWConfig.isBlockTempadPortalInto()
                && DimensionUtils.isExplorationDimension(targetDim)
                && !DimensionUtils.isExplorationDimension(sourceDim)) {
            event.fail(Component.translatable("parallelworlds.teleport.blocked_into")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // Block Tempad portal while INSIDE an exploration dimension
        if (PWConfig.isBlockTempadPortalInside()
                && DimensionUtils.isExplorationDimension(sourceDim)) {
            event.fail(Component.translatable("parallelworlds.teleport.blocked_inside")
                    .withStyle(ChatFormatting.RED));
        }
    }
}
