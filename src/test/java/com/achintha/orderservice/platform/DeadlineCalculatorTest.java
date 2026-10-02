package com.achintha.orderservice.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link DeadlineCalculator#plusHours(Instant, Duration, ZoneId, Predicate)}: real hours, but every hour that falls
 * on a holiday (Colombo date) does not count. Colombo is UTC+05:30 all year (no DST).
 */
class DeadlineCalculatorTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final Predicate<LocalDate> NO_HOLIDAYS = d -> false;

    /** A Colombo wall-clock time as an Instant. */
    private static Instant colombo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(COLOMBO).toInstant();
    }

    private static Instant plus(String start, int hours, Set<LocalDate> holidays) {
        return DeadlineCalculator.plusHours(colombo(start), Duration.ofHours(hours), COLOMBO, holidays::contains);
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void withoutHolidaysItIsPlainWallClockHours() {
        assertThat(plus("2026-10-05T10:00", 24, Set.of())).isEqualTo(colombo("2026-10-06T10:00"));
        assertThat(plus("2026-10-05T10:00", 72, Set.of())).isEqualTo(colombo("2026-10-08T10:00"));
    }

    @Test
    void zeroHoursOnAWorkingDayIsTheStart() {
        assertThat(plus("2026-10-05T10:00", 0, Set.of())).isEqualTo(colombo("2026-10-05T10:00"));
    }

    @Test
    void holidaysOutsideTheWindowDoNotMatter() {
        Set<LocalDate> holidays = Set.of(date("2026-10-01"), date("2026-10-20"));
        assertThat(plus("2026-10-05T10:00", 24, holidays)).isEqualTo(colombo("2026-10-06T10:00"));
    }

    @Nested
    class StartingJustBeforeAHoliday {

        @Test
        void theTimerPausesForTheWholeHolidayDay() {
            // 2 hours counted on the 5th (22:00-24:00), the 6th is skipped, 22 hours on the 7th
            Set<LocalDate> holidays = Set.of(date("2026-10-06"));
            assertThat(plus("2026-10-05T22:00", 24, holidays)).isEqualTo(colombo("2026-10-07T22:00"));
        }

        @Test
        void aMinuteBeforeMidnightCountsOneMinute() {
            Set<LocalDate> holidays = Set.of(date("2026-10-06"));
            Instant start = colombo("2026-10-05T23:59");
            Instant deadline = DeadlineCalculator.plusHours(start, Duration.ofHours(1), COLOMBO, holidays::contains);
            assertThat(deadline).isEqualTo(colombo("2026-10-07T00:59"));
        }

        @Test
        void aDeadlineEndingExactlyAtTheHolidayIsNotPushed() {
            Set<LocalDate> holidays = Set.of(date("2026-10-06"));
            assertThat(plus("2026-10-05T20:00", 4, holidays)).isEqualTo(colombo("2026-10-06T00:00"));
        }
    }

    @Nested
    class StartingInsideAHoliday {

        @Test
        void theTimerStartsAtTheNextNonHolidayMidnight() {
            Set<LocalDate> holidays = Set.of(date("2026-10-06"));
            assertThat(plus("2026-10-06T09:30", 24, holidays)).isEqualTo(colombo("2026-10-08T00:00"));
            assertThat(plus("2026-10-06T09:30", 10, holidays)).isEqualTo(colombo("2026-10-07T10:00"));
        }

        @Test
        void startingInsideAMultiDayRunWaitsForItsEnd() {
            Set<LocalDate> holidays = Set.of(date("2026-10-06"), date("2026-10-07"), date("2026-10-08"));
            assertThat(plus("2026-10-07T15:00", 24, holidays)).isEqualTo(colombo("2026-10-10T00:00"));
        }
    }

    @Nested
    class AcrossMultiDayHolidayRuns {

        @Test
        void aThreeDayRunAddsThreeDays() {
            // Poya + public holiday + weekend-style run of 3 days in the middle of a 24 h timer
            Set<LocalDate> holidays = Set.of(date("2026-10-06"), date("2026-10-07"), date("2026-10-08"));
            assertThat(plus("2026-10-05T12:00", 24, holidays)).isEqualTo(colombo("2026-10-09T12:00"));
        }

        @Test
        void twoSeparateRunsAreBothSkipped() {
            Set<LocalDate> holidays = Set.of(date("2026-10-06"), date("2026-10-08"), date("2026-10-09"));
            // 12 h on the 5th, skip 6th, 24 h on the 7th, skip 8th and 9th, 36 h left: all of the 10th + 12 h
            assertThat(plus("2026-10-05T12:00", 72, holidays)).isEqualTo(colombo("2026-10-11T12:00"));
        }

        @Test
        void aLongTimerCrossingAMonthBoundary() {
            Set<LocalDate> holidays = Set.of(date("2026-10-31"), date("2026-11-01"));
            assertThat(plus("2026-10-30T18:00", 72, holidays)).isEqualTo(colombo("2026-11-04T18:00"));
        }
    }

    @Test
    void holidayDaysAreColomboDatesNotUtcDates() {
        // 2026-10-05T20:00Z is already 2026-10-06T01:30 in Colombo, i.e. inside the holiday
        Set<LocalDate> holidays = Set.of(date("2026-10-06"));
        Instant start = Instant.parse("2026-10-05T20:00:00Z");
        Instant deadline = DeadlineCalculator.plusHours(start, Duration.ofHours(1), COLOMBO, holidays::contains);
        assertThat(deadline).isEqualTo(colombo("2026-10-07T01:00"));
    }

    @Test
    void aBrokenCalendarWhereEveryDayIsAHolidayFailsInsteadOfLooping() {
        assertThatThrownBy(() -> DeadlineCalculator.plusHours(colombo("2026-10-05T10:00"), Duration.ofHours(1),
                COLOMBO, d -> true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void negativeDurationsAreRejected() {
        assertThatThrownBy(() -> DeadlineCalculator.plusHours(colombo("2026-10-05T10:00"), Duration.ofHours(-1),
                COLOMBO, NO_HOLIDAYS)).isInstanceOf(IllegalArgumentException.class);
    }
}
