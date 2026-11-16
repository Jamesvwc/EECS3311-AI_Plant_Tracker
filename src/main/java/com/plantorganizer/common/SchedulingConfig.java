package com.plantorganizer.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's @Scheduled timers (reminder checks, recommendation
 * refresh). Tests switch them off with plantorganizer.scheduling.enabled=false
 * and call the timer methods directly instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "plantorganizer.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
