package com.plantorganizer.schedule.availability;

/**
 * Product interface of the Factory Method pattern, and target interface of
 * the Adapter pattern (Stage 1 AvailabilityImporter): every input format is
 * turned into the same {@link Availability}.
 */
public interface AvailabilityImporter {

    /** @throws ScheduleParseException when the input cannot be understood; its message asks the user to clarify */
    Availability parse(AvailabilityInput input);
}
