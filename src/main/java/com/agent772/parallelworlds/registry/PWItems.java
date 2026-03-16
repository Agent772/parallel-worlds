package com.agent772.parallelworlds.registry;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.item.DeathRecallItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PWItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ParallelWorlds.MOD_ID);

    public static final DeferredItem<DeathRecallItem> DEATH_RECALL_TOKEN = ITEMS.register(
            "death_recall_token",
            () -> new DeathRecallItem(new Item.Properties().stacksTo(1))
    );

    private PWItems() {}
}
