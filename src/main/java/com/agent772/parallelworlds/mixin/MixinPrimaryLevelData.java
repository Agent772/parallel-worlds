package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.accessor.IWorldDataAccessor;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PrimaryLevelData.class)
public class MixinPrimaryLevelData implements IWorldDataAccessor {

    @Shadow @Mutable
    private WorldOptions worldOptions;

    @Override
    public WorldOptions pw$getWorldOptions() {
        return this.worldOptions;
    }

    @Override
    public void pw$setWorldOptions(WorldOptions options) {
        this.worldOptions = options;
    }
}
