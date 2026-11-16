package com.plantorganizer.schedule.availability;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.plantorganizer.identification.ImageProcessor;
import com.plantorganizer.llm.LLMClient;

import tools.jackson.databind.json.JsonMapper;

/** Concrete importer: the vision model reads a photo or screenshot of the user's timetable. */
public class ImageScheduleParser implements AvailabilityImporter {

    static final String QUESTION = "I couldn't read a timetable in that image. Could you send a clearer photo "
            + "(straight on, good light), or type when you're usually home?";

    private final LLMClient llm;
    private final JsonMapper jsonMapper;
    private final ImageProcessor imageProcessor;
    private final Clock clock;

    ImageScheduleParser(LLMClient llm, JsonMapper jsonMapper, ImageProcessor imageProcessor, Clock clock) {
        this.llm = llm;
        this.jsonMapper = jsonMapper;
        this.imageProcessor = imageProcessor;
        this.clock = clock;
    }

    @Override
    public Availability parse(AvailabilityInput input) {
        if (!llm.supportsVision()) {
            throw new ScheduleParseException("Reading photos needs the Cohere vision model (check COHERE_API_KEY). "
                    + "You can type your availability instead.");
        }
        LocalDate today = LocalDate.now(clock);
        var json = llm.generateStructuredWithImages(AvailabilityTemplate.instructions(today),
                "This image is the user's timetable or calendar. Work out when they are free at home.",
                List.of(imageProcessor.resize(input.file())), AvailabilityTemplate.schema(jsonMapper));
        return AvailabilityTemplate.read(json, "IMAGE", QUESTION);
    }
}
