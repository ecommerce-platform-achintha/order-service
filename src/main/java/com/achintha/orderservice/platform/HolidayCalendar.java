package com.achintha.orderservice.platform;

import com.achintha.orderservice.client.StoreServiceClient.Holiday;
import com.achintha.orderservice.client.StoreServiceGateway;
import com.achintha.orderservice.exception.ServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The holiday calendar (public and Poya days, Colombo dates) from store-service ({@code GET /internal/holidays}),
 * loaded one calendar year at a time and cached for {@code app.platform.holidays-ttl}; {@code HolidaysChanged} drops
 * the cache. If store-service cannot be reached, the last known year is used; with nothing known the caller gets a
 * 503 (a deadline is never computed from a guessed calendar).
 */
@Slf4j
@Component
public class HolidayCalendar {

    private record Year(Set<LocalDate> dates, Instant loadedAt) {
    }

    private final StoreServiceGateway storeService;
    private final Clock clock;
    private final Duration ttl;
    private final Map<Integer, Year> years = new ConcurrentHashMap<>();

    public HolidayCalendar(StoreServiceGateway storeService, Clock clock,
                           @Value("${app.platform.holidays-ttl:PT1H}") Duration ttl) {
        this.storeService = storeService;
        this.clock = clock;
        this.ttl = ttl;
    }

    public boolean isHoliday(LocalDate date) {
        return year(date.getYear()).dates().contains(date);
    }

    /** Drops the cache ({@code HolidaysChanged}): known years are re-read on next use. */
    public void invalidate() {
        years.replaceAll((y, cached) -> new Year(cached.dates(), Instant.MIN));
    }

    private Year year(int year) {
        Year cached = years.get(year);
        if (cached != null && clock.instant().isBefore(cached.loadedAt().plus(ttl))) {
            return cached;
        }
        try {
            Set<LocalDate> dates = storeService.holidays(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                    .stream().map(Holiday::date).collect(Collectors.toUnmodifiableSet());
            Year loaded = new Year(dates, clock.instant());
            years.put(year, loaded);
            return loaded;
        } catch (ServiceUnavailableException e) {
            if (cached != null) {
                log.warn("Holidays of {} could not be refreshed; using the calendar loaded at {}", year,
                        cached.loadedAt());
                return cached;
            }
            throw e;
        }
    }
}
