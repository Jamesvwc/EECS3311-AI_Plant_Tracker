package com.plantorganizer.schedule;

import java.util.Optional;

/**
 * Strategy pattern (F02/F03). ScheduleManager (the context) asks the current
 * strategy for a plan and swaps strategies with setStrategy() at run time:
 * GroupedWateringStrategy first, FallbackWateringStrategy when grouping is
 * impossible.
 */
public interface SchedulingStrategy {

    String name();

    /** @return the plan, or empty when this strategy cannot produce one for this context */
    Optional<SchedulePlan> plan(SchedulingContext context);
}
