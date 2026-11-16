package com.plantorganizer.agent;

import java.util.List;

import com.plantorganizer.schedule.ScheduleResult;

/** F08 + F02 result: the plant that was added and the watering schedule the agent made for it. */
public record AddPlantResult(long plantId, String plantName, String location, ScheduleResult schedule,
                             List<String> notes) {

    public AddPlantResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
