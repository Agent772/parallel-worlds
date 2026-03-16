package com.agent772.parallelworlds.compat.jade;

import com.agent772.parallelworlds.data.PWSavedData;
import com.agent772.parallelworlds.dimension.DimensionUtils;
import com.agent772.parallelworlds.portal.DimensionColors;
import com.agent772.parallelworlds.portal.PortalShape;
import com.agent772.parallelworlds.portal.PortalTargetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade tooltip provider that shows the destination of a Parallel World portal.
 * <p>
 * Entry portal (base dimension): shows the target exploration dimension.<br>
 * Return portal (exploration dimension): shows the home dimension the player came from.
 */
public final class PWPortalJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    public static final PWPortalJadeProvider INSTANCE = new PWPortalJadeProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("parallelworlds", "portal_target");

    private static final String KEY_MODE = "PWMode";
    private static final String KEY_TARGET = "PWTarget";
    /** mode = 0: entry (show exploration target), mode = 1: return (show home dim) */
    private static final byte MODE_ENTRY = 0;
    private static final byte MODE_RETURN = 1;

    private PWPortalJadeProvider() {}

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    // ── Server side ───────────────────────────────────────────────────────

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        Level level = accessor.getLevel();
        BlockPos pos = accessor.getPosition();

        if (DimensionUtils.isExplorationDimension(level.dimension())) {
            // Return portal — show which dimension the player came from
            if (accessor.getPlayer() instanceof ServerPlayer player) {
                PWSavedData savedData = PWSavedData.get(((ServerLevel) level).getServer());
                savedData.getPlayerEntryPortalGlobalPos(player.getUUID())
                        .map(gp -> gp.dimension().location())
                        .ifPresent(homeDim -> {
                            data.putByte(KEY_MODE, MODE_RETURN);
                            data.putString(KEY_TARGET, homeDim.toString());
                        });
            }
        } else {
            // Entry portal — show which exploration dimension this portal targets
            BlockPos canonical = PortalShape.findPortalShape(level, pos)
                    .map(PortalShape::getBottomLeft)
                    .orElse(pos);
            ResourceLocation target = PortalTargetManager.getTarget(level, canonical);
            if (target != null) {
                data.putByte(KEY_MODE, MODE_ENTRY);
                data.putString(KEY_TARGET, target.toString());
            }
        }
    }

    // ── Client side ───────────────────────────────────────────────────────

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(KEY_TARGET)) return;

        ResourceLocation target = ResourceLocation.tryParse(serverData.getString(KEY_TARGET));
        if (target == null) return;

        byte mode = serverData.getByte(KEY_MODE);
        Component destName = (mode == MODE_RETURN)
                ? DimensionColors.getHomeDimensionName(target)
                : DimensionColors.getDisplayName(target);

        tooltip.add(Component.translatable("jade.parallelworlds.portal_dest", destName));
    }
}
