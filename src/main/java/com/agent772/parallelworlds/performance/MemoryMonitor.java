package com.agent772.parallelworlds.performance;

import com.agent772.parallelworlds.config.PWConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Monitors JVM memory usage to throttle expensive operations
 * (e.g. chunk pre-generation) when memory pressure is high.
 */
public final class MemoryMonitor {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MemoryMonitor() {}

    /**
     * @return true if used memory exceeds the configured threshold of max memory
     */
    public static boolean isMemoryPressureHigh() {
        return getMemoryUsagePercent() > PWConfig.getPregenMemoryThreshold();
    }

    /**
     * @return fraction of max memory currently in use (0.0 – 1.0)
     */
    public static double getMemoryUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        return (double) used / max;
    }

    /**
     * Log current memory state at debug level.
     */
    public static void logMemoryUsage(String context) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long total = rt.totalMemory() / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        LOGGER.debug("[{}] Memory: {} MB used / {} MB allocated / {} MB max ({}%)",
                context, used, total, max, String.format("%.1f", getMemoryUsagePercent() * 100));
    }
}
