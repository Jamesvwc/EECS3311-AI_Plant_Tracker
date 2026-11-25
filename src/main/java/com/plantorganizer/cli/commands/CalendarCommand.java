package com.plantorganizer.cli.commands;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.plantorganizer.cli.AgentClient;
import com.plantorganizer.cli.ApiException;
import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F02/F03: the watering calendar, and fitting it to when the user is free. */
@Command(name = "/calendar", separator = " ",
        description = "The watering calendar: see it, fit it to when you're free, undo the last change.",
        subcommands = {CalendarCommand.View.class, CalendarCommand.Update.class, CalendarCommand.Import.class,
                CalendarCommand.Undo.class})
public class CalendarCommand extends GroupCommand {

    @Command(name = "view", separator = " ", description = "Show the waterings planned for the coming days.")
    static class View extends CliCommand {

        @Option(names = "--days", paramLabel = "<n>", defaultValue = "7", description = "How many days ahead (1-60, default 7).")
        int days;

        View(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (days < 1 || days > 60) {
                throw new CliException("--days must be between 1 and 60.");
            }
            LocalDate today = LocalDate.now();
            List<JsonNode> items = Json.list(api().get("/api/calendar", Map.of(
                    "from", today.toString(), "to", today.plusDays(days).toString())));
            if (items.isEmpty()) {
                print("Nothing to water in the next " + Text.count(days, "day") + ".");
                return;
            }
            Map<LocalDate, List<String[]>> byDay = new LinkedHashMap<>();
            for (JsonNode i : items) {
                LocalDate day = Text.day(Json.str(i, "dueAt"));
                String time = Json.str(i, "dueAt") != null && Json.str(i, "dueAt").length() >= 16
                        ? Json.str(i, "dueAt").substring(11, 16) : "";
                String extra = "DONE".equals(Json.str(i, "status")) ? "watered " + Text.when(Json.str(i, "completedAt"))
                        : Json.bool(i, "grouped") ? "grouped" : "";
                byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(new String[] {time,
                        Json.str(i, "plantName") + " (P" + i.path("plantId").asLong() + ")", extra});
            }
            print("Watering calendar, next " + Text.count(days, "day") + ":");
            byDay.forEach((day, rows) -> {
                print("  " + (day != null ? day.format(Text.DAY) : "?"));
                print(Text.table(rows, "    "));
            });
        }
    }

    @Command(name = "update", separator = " ",
            description = "Tell the agent when you're free or away; your waterings move to fit, and it tells you "
                    + "if a plant's needs can't be met.")
    static class Update extends CliCommand {

        @Parameters(index = "0", paramLabel = "<availability>",
                description = "In quotes, e.g. \"I work 9-5 on weekdays, 30 minutes to get home, away Dec 20-27\".")
        String availability;

        Update(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (availability.isBlank()) {
                throw new CliException("Say when you're free, e.g. /calendar update \"I work 9-5 on weekdays\".");
            }
            status("The agent is fitting your waterings to your availability");
            print(reschedule(this, List.of(AgentClient.Part.text("text", availability))));
        }
    }

    @Command(name = "import", separator = " ",
            description = "Fit your waterings to a calendar export (.ics) or a photo of your timetable.")
    static class Import extends CliCommand {

        @Parameters(index = "0", paramLabel = "<file>", description = "An .ics calendar file or a timetable photo.")
        String file;

        Import(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            AgentClient.Part part = AgentClient.Part.file("file", AgentClient.checkUpload(file));
            status("The agent is reading your file and fitting your waterings to it");
            print(reschedule(this, List.of(part)));
        }
    }

    @Command(name = "undo", separator = " ", description = "Undo the last change to your watering calendar.")
    static class Undo extends CliCommand {

        Undo(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            print(Json.str(api().post("/api/calendar/undo", null), "message"));
        }
    }

    /** Sends availability (text or file) and describes the result, including needs that can't be met. */
    static String reschedule(CliCommand command, List<AgentClient.Part> parts) {
        JsonNode r;
        try {
            r = command.api().postMultipart("/api/calendar/availability", parts);
        } catch (ApiException e) {
            if (e.status() == 422) {
                throw new CliException("The agent couldn't work out when you're free: " + e.getMessage());
            }
            throw e;
        }
        StringBuilder sb = new StringBuilder(Text.plain(Json.str(r, "explanation")));
        List<String> examples = Json.strings(r.path("examples"));
        if (!examples.isEmpty()) {
            sb.append("\nChanges:");
            examples.forEach(e -> sb.append("\n  - ").append(e));
        }
        List<String> warnings = Json.strings(r.path("warnings"));
        if (warnings.isEmpty()) {
            if (r.path("plantsRescheduled").asInt() > 0) {
                sb.append("\nEvery plant's watering needs fit your availability.");
            }
        } else {
            sb.append("\nYour availability can't fully meet these plants' needs:");
            warnings.forEach(w -> sb.append("\n  - ").append(w));
        }
        sb.append("\nUndo with /calendar undo if anything looks wrong.");
        return sb.toString();
    }
}
