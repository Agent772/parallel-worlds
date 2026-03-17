package com.agent772.parallelworlds.dimension;

import com.agent772.parallelworlds.config.PWConfig;
import com.agent772.parallelworlds.config.PWConfigSpec;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.time.*;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
     * Check whether a seed rotation is due based on the seed's creation timestamp.
     *
     * @param seedCreatedAt the epoch-second when this seed was generated (0 = unknown/never)
     * @return true if the current time is past the next scheduled reset after that timestamp
     */
    public static boolean isRotationDue(long seedCreatedAt) {
        if (!PWConfig.isSeedRotationEnabled()) {
            return false;
        }
        if (seedCreatedAt <= 0) {
            // Unknown creation time — treat as needing rotation so a fresh timestamp is assigned.
            return true;
        }
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(seedCreatedAt), ZoneId.systemDefault());
        LocalDateTime nextReset = computeNextResetAfter(createdAt);
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
                // Next occurrence of resetHour:resetMinute strictly after 'after'.
                LocalDateTime sameDayCandidate = after.toLocalDate().atTime(hour, minute);
                // If that time is still in the future relative to 'after', use same day;
                // otherwise use the next day.
                yield sameDayCandidate.isAfter(after)
                        ? sameDayCandidate
                        : sameDayCandidate.plusDays(1);
            }
            case WEEKLY -> {
                DayOfWeek resetDay = toDayOfWeek(PWConfig.getResetDayOfWeek());
                // Use nextOrSame so that if today IS the reset day and the time hasn't
                // passed yet relative to 'after', we use today rather than skipping a week.
                LocalDate nextDay = after.toLocalDate().with(TemporalAdjusters.nextOrSame(resetDay));
                LocalDateTime candidate = nextDay.atTime(hour, minute);
                // If that lands at or before 'after' (same day but time already passed),
                // advance by one week.
                if (!candidate.isAfter(after)) {
                    candidate = candidate.plusWeeks(1);
                }
                yield candidate;
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

    /**
     * Formats an epoch-second as a local date-time string (e.g. "2026-03-17 14:19").
     * Returns "never" for values ≤ 0.
     */
    public static String formatEpoch(long epochSecond) {
        if (epochSecond <= 0) return "never";
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.systemDefault())
                .format(LOG_FMT);
    }

    /**
     * Returns a formatted string of when the next rotation is due after the given
     * seed creation timestamp, or a descriptive message if rotation is disabled.
     */
    public static String nextResetFormatted(long seedCreatedAt) {
        if (!PWConfig.isSeedRotationEnabled()) return "rotation disabled";
        if (seedCreatedAt <= 0) return "unknown (no timestamp)";
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(seedCreatedAt), ZoneId.systemDefault());
        return computeNextResetAfter(createdAt).format(LOG_FMT);
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
