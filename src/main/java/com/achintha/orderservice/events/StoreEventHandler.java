package com.achintha.orderservice.events;

import com.achintha.orderservice.platform.HolidayCalendar;
import com.achintha.orderservice.platform.PlatformSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies one {@code store-events} message: {@code SettingsChanged} drops the settings cache,
 * {@code HolidaysChanged} the holiday cache. Both are naturally idempotent (no de-duplication needed).
 */
@Component
@RequiredArgsConstructor
public class StoreEventHandler {

    private final PlatformSettings settings;
    private final HolidayCalendar holidays;

    public void handle(StoreEvent event) {
        if (event.eventType() == null) {
            return;
        }
        switch (event.eventType()) {
            case "SettingsChanged" -> settings.invalidate();
            case "HolidaysChanged" -> holidays.invalidate();
            default -> {
                // StoreVisibilityChanged: checkout asks store-service live
            }
        }
    }
}
