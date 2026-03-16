package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Random;

/**
 * Generates seeds and computes reset schedules for exploration dimensions.
 * <p>
 * Seed rotation is schedule-based (daily/weekly/monthly) with a configurable
 * day and time. The actual rotation only takes effect on server restart:
 * on startup, the system checks whether the scheduled reset time has passed
 * since the last reset and rotates if needed.
 */
public final class SeedManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();

    private SeedManager() {}

    /**
     * Generate a fresh random seed. If debugSeed is set, returns that instead.
     */
    public static long generateSeed() {
        long debugSeed = PWConfig.getDebugSeed();
        if (debugSeed != 0) {
            return debugSeed;
        }
        return RANDOM.nextLong();
    }

    /**
     * Check whether a seed rotation is due based on the saved reset time.
     *
     * @param lastResetEpochSecond the epoch-second of the last reset (0 if never reset)
     * @return true if the current time is past the next scheduled reset
     */
    public static boolean isRotationDue(long lastResetEpochSecond) {
        if (!PWConfig.isSeedRotationEnabled()) {
            return false;
        }
        if (lastResetEpochSecond <= 0) {
            // Never rotated yet — rotation is due (first run)
            return true;
        }
        LocalDateTime lastReset = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(lastResetEpochSecond), ZoneId.systemDefault());
        LocalDateTime nextReset = computeNextResetAfter(lastReset);
        return LocalDateTime.now().isAfter(nextReset);
    }

    /**
     * Compute the next reset time after the given base time, based on config schedule.
     */
    public static LocalDateTime computeNextResetAfter(LocalDateTime after) {
        int hour = PWConfig.getResetHour();
        int minute = PWConfig.getResetMinute();

        return switch (PWConfig.getResetSchedule()) {
            case DAILY -> {
                LocalDateTime candidate = after.toLocalDate().plusDays(1).atTime(hour, minute);
                yield candidate;
            }
            case WEEKLY -> {
                DayOfWeek resetDay = toDayOfWeek(PWConfig.getResetDayOfWeek());
                LocalDate nextDay = after.toLocalDate().with(TemporalAdjusters.next(resetDay));
                yield nextDay.atTime(hour, minute);
            }
            case MONTHLY -> {
                int dayOfMonth = PWConfig.getResetDayOfMonth();
                LocalDate base = after.toLocalDate();
                LocalDate candidate = base.withDayOfMonth(Math.min(dayOfMonth, base.lengthOfMonth()));
                // If candidate is same day or before, go to next month
                if (!candidate.isAfter(base)) {
                    base = base.plusMonths(1);
                    candidate = base.withDayOfMonth(Math.min(dayOfMonth, base.lengthOfMonth()));
                }
                yield candidate.atTime(hour, minute);
            }
        };
    }

    /**
     * Get the duration until the next seed rotation from now, or null if rotation is disabled.
     */
    public static Duration getTimeUntilNextReset() {
        if (!PWConfig.isSeedRotationEnabled()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = computeNextResetAfter(now);
        return Duration.between(now, nextReset);
    }

    /**
     * Get the current time as epoch seconds (for storing as lastResetTime).
     */
    public static long currentEpochSecond() {
        return Instant.now().getEpochSecond();
    }

    static DayOfWeek toDayOfWeek(PWConfigSpec.WeekDay wd) {
        return switch (wd) {
            case MONDAY -> DayOfWeek.MONDAY;
            case TUESDAY -> DayOfWeek.TUESDAY;
            case WEDNESDAY -> DayOfWeek.WEDNESDAY;
            case THURSDAY -> DayOfWeek.THURSDAY;
            case FRIDAY -> DayOfWeek.FRIDAY;
            case SATURDAY -> DayOfWeek.SATURDAY;
            case SUNDAY -> DayOfWeek.SUNDAY;
        };
    }
}
