package com.agent772.parallelworlds.accessor;

import net.minecraft.resources.ResourceKey;

import java.util.Set;

/**
 * Accessor interface for MappedRegistry mixin —
 * provides safe runtime registry manipulation for frozen registries.
 */
public interface IRegistryAccessor<T> {

    void pw$setFrozen(boolean frozen);

    boolean pw$isFrozen();

    void pw$registerRuntime(ResourceKey<T> key, T value);

    void pw$removeRuntimeEntry(ResourceKey<T> key);

    void pw$cleanupAllRuntimeEntries();

    Set<ResourceKey<T>> pw$getRuntimeEntries();

    boolean pw$validateRegistryState();
}
