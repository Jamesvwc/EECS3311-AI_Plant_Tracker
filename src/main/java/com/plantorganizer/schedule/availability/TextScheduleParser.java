package com.plantorganizer.schedule.availability;

import java.time.Clock;
import java.time.LocalDate;

import com.plantorganizer.llm.LLMClient;

import tools.jackson.databind.json.JsonMapper;

/** Concrete importer: the LLM reads a typed description ("I'm free weekday evenings, away Dec 20-27"). */
public class TextScheduleParser implements AvailabilityImporter {

    static final String QUESTION = "I couldn't tell when you're free from that. Could you list the days and times "
            + "you're usually home (for example \"weekdays after 6pm, weekends any time\") and any trips?";

    private final LLMClient llm;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    TextScheduleParser(LLMClient llm, JsonMapper jsonMapper, Clock clock) {
        this.llm = llm;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public Availability parse(AvailabilityInput input) {
        if (input.text() == null || input.text().isBlank()) {
            throw new ScheduleParseException(QUESTION);
        }
        LocalDate today = LocalDate.now(clock);
        var json = llm.generateStructured(AvailabilityTemplate.instructions(today),
                "Availability description from the user:\n\"\"\"\n" + input.text().trim() + "\n\"\"\"",
                AvailabilityTemplate.schema(jsonMapper));
        return AvailabilityTemplate.read(json, "TEXT", QUESTION);
    }
}
