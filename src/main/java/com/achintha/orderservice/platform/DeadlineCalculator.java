package com.achintha.orderservice.platform;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deadlines in real (wall-clock) hours where hours falling on a holiday do not count (D1, section 6.4): the timer
 * pauses for the whole of every holiday day, judged by the {@code Asia/Colombo} date. A timer that starts on a
 * holiday starts running at the next non-holiday midnight. Only the auto-complete timer is in plain days
 * ({@link #plusDays}).
 */
@Component
public class DeadlineCalculator {

    /** Upper bound of the walk: 10 years of consecutive holidays would be a broken calendar. */
    private static final int MAX_DAYS = 3660;

    private final ZoneId zone;
    private final HolidayCalendar calendar;

    public DeadlineCalculator(@Value("${app.timezone:Asia/Colombo}") String timezone, HolidayCalendar calendar) {
        this.zone = ZoneId.of(timezone);
        this.calendar = calendar;
    }

    /** {@code start} plus {@code hours} counted hours, skipping holidays from the calendar. */
    public Instant plusHours(Instant start, int hours) {
        return plusHours(start, Duration.ofHours(hours), zone, calendar::isHoliday);
    }

    /** Plain calendar days (auto-complete, D10). */
    public Instant plusDays(Instant start, int days) {
        return start.plus(Duration.ofDays(days));
    }

    /**
     * The pure calculation (unit-tested on its own).
     *
     * @param isHoliday whether a local date (in {@code zone}) is a holiday
     */
    public static Instant plusHours(Instant start, Duration duration, ZoneId zone, Predicate<LocalDate> isHoliday) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Duration remaining = duration;
        ZonedDateTime cursor = start.atZone(zone);
        for (int day = 0; day <= MAX_DAYS; day++) {
            LocalDate date = cursor.toLocalDate();
            ZonedDateTime nextMidnight = date.plusDays(1).atStartOfDay(zone);
            if (isHoliday.test(date)) {
                cursor = nextMidnight;
                continue;
            }
            Duration available = Duration.between(cursor, nextMidnight);
            if (remaining.compareTo(available) <= 0) {
                return cursor.plus(remaining).toInstant();
            }
            remaining = remaining.minus(available);
            cursor = nextMidnight;
        }
        throw new IllegalStateException("Deadline is more than " + MAX_DAYS + " days away: check the holiday calendar");
    }
}
