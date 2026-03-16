package com.agent772.parallelworlds.mixin;

import com.agent772.parallelworlds.ParallelWorlds;
import com.agent772.parallelworlds.accessor.IRegistryAccessor;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Mixin(MappedRegistry.class)
public class MixinMappedRegistry<T> implements IRegistryAccessor<T> {
    @Unique private static final Logger pw$LOGGER = LogUtils.getLogger();

    @Shadow @Final private Map<ResourceKey<T>, Holder.Reference<T>> byKey;
    @Shadow private boolean frozen;

    @Unique private Map<T, Holder.Reference<T>> pw$byValue;
    @Unique private Map<ResourceLocation, Holder.Reference<T>> pw$byLocation;
    @Unique private volatile boolean pw$temporarilyUnfrozen;
    @Unique private final Set<ResourceKey<T>> pw$runtimeEntries = ConcurrentHashMap.newKeySet();
    @Unique private final ReentrantReadWriteLock pw$registryLock = new ReentrantReadWriteLock();
    @Unique private boolean pw$reflectionInit;

    // ── Reflection-based field discovery ──

    @Unique
    private void pw$initReflection() {
        if (pw$reflectionInit) return;
        pw$LOGGER.debug("Starting registry field discovery");

        try {
            pw$discoverFields(this.getClass());
            if (pw$byValue == null || pw$byLocation == null) {
                Class<?> sup = this.getClass().getSuperclass();
                while (sup != null && sup != Object.class) {
                    pw$discoverFields(sup);
                    if (pw$byValue != null && pw$byLocation != null) break;
                    sup = sup.getSuperclass();
                }
            }
            if (pw$byValue == null || pw$byLocation == null) {
                pw$heuristicDiscovery();
            }
            if (pw$byValue == null || pw$byLocation == null) {
                pw$LOGGER.error("CRITICAL: Could not discover registry fields (byValue={}, byLocation={})",
                        pw$byValue != null, pw$byLocation != null);
            } else {
                pw$LOGGER.info("Successfully discovered all registry fields");
            }
        } catch (Exception e) {
            pw$LOGGER.error("Unexpected error in registry field discovery", e);
        }
        pw$reflectionInit = true;
    }

    @Unique
    private void pw$discoverFields(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<?, ?> map = (Map<?, ?>) f.get(this);
                if (map == null || map.isEmpty()) continue;

                Object firstKey = map.keySet().iterator().next();
                Object firstVal = map.values().iterator().next();
                if (!(firstVal instanceof Holder.Reference)) continue;

                if (pw$byValue == null && !(firstKey instanceof ResourceKey<?>)
                        && !(firstKey instanceof ResourceLocation)) {
                    @SuppressWarnings("unchecked")
                    Map<T, Holder.Reference<T>> cast = (Map<T, Holder.Reference<T>>) map;
                    pw$byValue = cast;
                    pw$LOGGER.debug("Found byValue: {}", f.getName());
                } else if (pw$byLocation == null && firstKey instanceof ResourceLocation) {
                    @SuppressWarnings("unchecked")
                    Map<ResourceLocation, Holder.Reference<T>> cast =
                            (Map<ResourceLocation, Holder.Reference<T>>) map;
                    pw$byLocation = cast;
                    pw$LOGGER.debug("Found byLocation: {}", f.getName());
                }
            } catch (Exception ignored) {}
        }
    }

    @Unique
    private void pw$heuristicDiscovery() {
        String[] valueCandidates = {"byValue", "valueToKey", "values"};
        String[] locCandidates = {"byLocation", "locationToKey", "byName"};

        for (String c : valueCandidates) {
            if (pw$byValue != null) break;
            pw$tryFieldByPattern(c, true);
        }
        for (String c : locCandidates) {
            if (pw$byLocation != null) break;
            pw$tryFieldByPattern(c, false);
        }
    }

    @Unique
    private void pw$tryFieldByPattern(String pattern, boolean isValueMap) {
        for (Field f : this.getClass().getDeclaredFields()) {
            if (!f.getName().contains(pattern) || !Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<?, ?> map = (Map<?, ?>) f.get(this);
                if (map == null) continue;
                if (isValueMap) {
                    @SuppressWarnings("unchecked")
                    Map<T, Holder.Reference<T>> cast = (Map<T, Holder.Reference<T>>) map;
                    pw$byValue = cast;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<ResourceLocation, Holder.Reference<T>> cast =
                            (Map<ResourceLocation, Holder.Reference<T>>) map;
                    pw$byLocation = cast;
                }
            } catch (Exception ignored) {}
        }
    }

    // ── IRegistryAccessor implementation ──

    @Override @Unique
    public void pw$setFrozen(boolean frozen) {
        this.frozen = frozen;
        this.pw$temporarilyUnfrozen = !frozen;
    }

    @Override @Unique
    public boolean pw$isFrozen() {
        return frozen && !pw$temporarilyUnfrozen;
    }

    @Override @Unique
    public void pw$registerRuntime(ResourceKey<T> key, T value) {
        if (!frozen || !pw$isOurDimension(key)) return;
        pw$initReflection();

        if (byKey.containsKey(key)) return;

        pw$registryLock.writeLock().lock();
        try {
            boolean wasFrozen = frozen;
            frozen = false;
            pw$temporarilyUnfrozen = true;
            try {
                if (pw$useInternalRegister(key, value)) {
                    pw$runtimeEntries.add(key);
                    pw$LOGGER.info("Registered runtime entry: {}", key.location());
                } else {
                    pw$LOGGER.error("Failed to register runtime entry: {}", key.location());
                }
            } finally {
                frozen = wasFrozen;
                pw$temporarilyUnfrozen = false;
            }
        } finally {
            pw$registryLock.writeLock().unlock();
        }
    }

    @Unique
    private boolean pw$useInternalRegister(ResourceKey<T> key, T value) {
        try {
            @SuppressWarnings("unchecked")
            MappedRegistry<T> self = (MappedRegistry<T>) (Object) this;
            for (Method m : self.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("register")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && ResourceKey.class.isAssignableFrom(pt[1])) {
                    m.setAccessible(true);
                    m.invoke(self, byKey.size() + 1000, key, value);
                    return true;
                }
                if (pt.length == 2 && ResourceKey.class.isAssignableFrom(pt[0])) {
                    m.setAccessible(true);
                    m.invoke(self, key, value);
                    return true;
                }
            }
        } catch (Exception e) {
            pw$LOGGER.debug("Internal register failed for {}: {}", key.location(), e.getMessage());
        }
        return pw$directFieldRegistration(key, value);
    }

    @Unique
    private boolean pw$directFieldRegistration(ResourceKey<T> key, T value) {
        try {
            Holder.Reference<T> reference = pw$createHolder(key, value);
            if (reference == null) return false;

            // Backup
            Map<ResourceKey<T>, Holder.Reference<T>> bkKey = new HashMap<>(byKey);
            Map<T, Holder.Reference<T>> bkVal = pw$byValue != null ? new HashMap<>(pw$byValue) : null;
            Map<ResourceLocation, Holder.Reference<T>> bkLoc = pw$byLocation != null ? new HashMap<>(pw$byLocation) : null;

            try {
                byKey.put(key, reference);
                if (pw$byValue != null) pw$byValue.put(value, reference);
                if (pw$byLocation != null) pw$byLocation.put(key.location(), reference);
                return true;
            } catch (Exception e) {
                // Restore
                byKey.clear(); byKey.putAll(bkKey);
                if (pw$byValue != null && bkVal != null) { pw$byValue.clear(); pw$byValue.putAll(bkVal); }
                if (pw$byLocation != null && bkLoc != null) { pw$byLocation.clear(); pw$byLocation.putAll(bkLoc); }
                pw$LOGGER.warn("Registry modification rolled back for {}", key.location(), e);
                return false;
            }
        } catch (Exception e) {
            pw$LOGGER.error("Direct field registration failed for {}", key.location(), e);
            return false;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private Holder.Reference<T> pw$createHolder(ResourceKey<T> key, T value) {
        // Try existing entry
        Holder.Reference<T> existing = byKey.get(key);
        if (existing != null) {
            pw$tryBind(existing, value);
            return existing;
        }

        // Try constructors via reflection
        for (Constructor<?> ctor : Holder.Reference.class.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            Class<?>[] pt = ctor.getParameterTypes();
            try {
                Holder.Reference<T> ref;
                if (pt.length == 4) {
                    ref = (Holder.Reference<T>) ctor.newInstance(pt[0].getEnumConstants()[0], this, key, value);
                } else if (pt.length == 3) {
                    ref = (Holder.Reference<T>) ctor.newInstance(this, key, value);
                } else if (pt.length == 2) {
                    ref = (Holder.Reference<T>) ctor.newInstance(this, key);
                    pw$tryBind(ref, value);
                } else {
                    continue;
                }
                return ref;
            } catch (Exception ignored) {}
        }

        // computeIfAbsent fallback
        return byKey.computeIfAbsent(key, k -> {
            for (Constructor<?> ctor : Holder.Reference.class.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                try {
                    Holder.Reference<T> ref = (Holder.Reference<T>) ctor.newInstance(
                            ctor.getParameterTypes()[0].getEnumConstants()[0], this, k, value);
                    return ref;
                } catch (Exception ignored2) {}
            }
            return null;
        });
    }

    @Unique
    private void pw$tryBind(Holder.Reference<T> ref, T value) {
        if (ref.isBound()) return;
        try {
            Method bind = ref.getClass().getDeclaredMethod("bindValue", Object.class);
            bind.setAccessible(true);
            bind.invoke(ref, value);
        } catch (Exception e) {
            pw$LOGGER.debug("Could not bind value: {}", e.getMessage());
        }
    }

    @Override @Unique
    public void pw$removeRuntimeEntry(ResourceKey<T> key) {
        if (!pw$runtimeEntries.contains(key)) return;
        pw$registryLock.writeLock().lock();
        try {
            boolean wasFrozen = frozen;
            frozen = false;
            pw$temporarilyUnfrozen = true;
            try {
                pw$initReflection();
                Holder.Reference<T> holder = byKey.remove(key);
                if (holder != null && holder.isBound()) {
                    if (pw$byValue != null) pw$byValue.remove(holder.value());
                    if (pw$byLocation != null) pw$byLocation.remove(key.location());
                }
                pw$runtimeEntries.remove(key);
            } finally {
                frozen = wasFrozen;
                pw$temporarilyUnfrozen = false;
            }
        } finally {
            pw$registryLock.writeLock().unlock();
        }
    }

    @Override @Unique
    public void pw$cleanupAllRuntimeEntries() {
        new HashSet<>(pw$runtimeEntries).forEach(this::pw$removeRuntimeEntry);
    }

    @Override @Unique
    public Set<ResourceKey<T>> pw$getRuntimeEntries() {
        return Collections.unmodifiableSet(pw$runtimeEntries);
    }

    @Override @Unique
    public boolean pw$validateRegistryState() {
        if (byKey == null) return false;
        for (ResourceKey<T> rk : pw$runtimeEntries) {
            Holder.Reference<T> h = byKey.get(rk);
            if (h == null || !h.isBound()) return false;
        }
        return true;
    }

    @Unique
    private boolean pw$isOurDimension(ResourceKey<?> key) {
        return key.location().getNamespace().equals(ParallelWorlds.MOD_ID)
                && key.location().getPath().startsWith("pw_");
    }
}
