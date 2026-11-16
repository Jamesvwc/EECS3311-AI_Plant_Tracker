package com.plantorganizer.schedule.availability;

/**
 * Stage 1 "ParseError": the availability could not be understood. The message
 * is a question for the user (another photo, or a plain-text description),
 * after which the F03 workflow is simply run again.
 */
public class ScheduleParseException extends RuntimeException {

    public ScheduleParseException(String clarificationQuestion) {
        super(clarificationQuestion);
    }
}
