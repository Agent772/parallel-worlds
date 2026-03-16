package com.agent772.parallelworlds.registry;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.portal.PWPortalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PWBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ParallelWorlds.MOD_ID);

    public static final DeferredBlock<PWPortalBlock> PW_PORTAL = BLOCKS.register("pw_portal",
            () -> new PWPortalBlock(BlockBehaviour.Properties.of()
                    .noCollission()
                    .strength(-1.0f)
                    .sound(SoundType.GLASS)
                    .lightLevel(state -> 11)
                    .noLootTable()));

    private PWBlocks() {}
}
