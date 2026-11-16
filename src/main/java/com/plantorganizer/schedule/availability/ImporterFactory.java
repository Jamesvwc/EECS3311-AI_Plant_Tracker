package com.plantorganizer.schedule.availability;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.plantorganizer.identification.ImageProcessor;
import com.plantorganizer.llm.LLMClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * Creator of the Factory Method pattern (F03): {@link #create} is the one place
 * that decides which parser handles which input type. ScheduleManager only
 * sees {@link AvailabilityImporter}, so supporting a new format (say, a
 * Google Calendar link) means one new class and one new case here.
 */
@Component
public class ImporterFactory {

    private final LLMClient llm;
    private final JsonMapper jsonMapper;
    private final ImageProcessor imageProcessor;
    private final Clock clock;

    public ImporterFactory(LLMClient llm, JsonMapper jsonMapper, ImageProcessor imageProcessor, Clock clock) {
        this.llm = llm;
        this.jsonMapper = jsonMapper;
        this.imageProcessor = imageProcessor;
        this.clock = clock;
    }

    public AvailabilityImporter create(AvailabilityInput.InputType type) {
        return switch (type) {
            case TEXT -> new TextScheduleParser(llm, jsonMapper, clock);
            case IMAGE -> new ImageScheduleParser(llm, jsonMapper, imageProcessor, clock);
            case ICAL -> new ICalAdapter(clock);
        };
    }
}
