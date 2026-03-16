package com.agent772.parallelworlds.compat.jade;

import com.agent772.parallelworlds.portal.PWPortalBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Registers Parallel Worlds compat providers with Jade.
 * Discovered via META-INF/services/snownee.jade.api.IWailaPlugin.
 */
@WailaPlugin
public final class PWJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PWPortalJadeProvider.INSTANCE, PWPortalBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PWPortalJadeProvider.INSTANCE, PWPortalBlock.class);
    }
}
