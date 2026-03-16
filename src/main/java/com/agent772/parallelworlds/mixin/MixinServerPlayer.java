package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Intercepts cross-dimension teleports via the vanilla /tp (teleport) command and
 * blocks non-operator players from entering exploration dimensions when
 * {@code blockVanillaTeleportInto} is enabled.
 * <p>
 * {@code ServerPlayer#teleportTo(ServerLevel, ...)} is the code path used exclusively
 * by TeleportCommand for cross-dimension travel. Our own portal uses
 * {@code Entity#changeDimension(DimensionTransition)}, so portals are unaffected.
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer {

    /**
     * Fires at the head of the cross-dimension teleport method. Cancels the teleport
     * and notifies the player when the target level is an exploration dimension and
     * the player does not have operator permission (level 2).
     */
    @Inject(
            method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pw$blockTeleportIntoExploration(
            ServerLevel targetLevel, double x, double y, double z,
            Set<?> relativeMovements, float yRot, float xRot,
            CallbackInfo ci) {
        if (!PWConfig.isBlockVanillaTeleportInto()) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.hasPermissions(2)) return;
        if (!DimensionUtils.isExplorationDimension(targetLevel.dimension())) return;
        ci.cancel();
        self.displayClientMessage(
                Component.translatable("parallelworlds.teleport.blocked_into")
                        .withStyle(ChatFormatting.RED),
                true);
    }
}
