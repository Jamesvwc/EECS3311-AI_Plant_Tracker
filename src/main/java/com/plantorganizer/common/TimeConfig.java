package com.plantorganizer.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} used for "now" everywhere (schedules, reminders,
 * share-link expiry). Tests replace it with a fixed clock so time-based
 * behaviour (due / overdue plants) can be checked deterministically.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
