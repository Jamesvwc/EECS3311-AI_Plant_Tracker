package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.cli.AgentClient;
import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F05: the agent checks the health of a plant you own from a photo and/or a description. */
@Command(name = "/health", separator = " ", description = "Check a plant's health, dig deeper, or see past checks.",
        subcommands = {HealthCommand.Check.class, HealthCommand.Research.class, HealthCommand.History.class})
public class HealthCommand extends GroupCommand {

    @Command(name = "check", separator = " ",
            description = "The agent checks one of your plants from a photo and/or a description of the problem, "
                    + "then asks whether to save the result to the plant's notes.")
    static class Check extends CliCommand {

        @Parameters(index = "0", paramLabel = PlantsCommand.PLANT, description = PlantsCommand.PLANT_HELP)
        String plant;

        @Option(names = "--photo", paramLabel = "<file>", description = "A photo of the plant or the problem.")
        String photo;

        @Option(names = "--describe", paramLabel = "<problem>", description = "What you've noticed, in quotes, e.g. \"yellow spots on the leaves\".")
        String describe;

        @Option(names = "--save", description = "Save the result to the plant's notes without asking.")
        boolean save;

        Check(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (photo == null && (describe == null || describe.isBlank())) {
                throw new CliException("Add a photo (--photo <file>) and/or a description of the problem "
                        + "(--describe \"...\"), e.g. /health check " + plant + " --describe \"yellow leaves\".");
            }
            JsonNode p = find().ownedPlant(plant);
            List<AgentClient.Part> parts = new ArrayList<>();
            if (photo != null) {
                parts.add(AgentClient.Part.file("photo", AgentClient.checkUpload(photo)));
            }
            if (describe != null && !describe.isBlank()) {
                parts.add(AgentClient.Part.text("description", describe));
            }
            status("The agent is checking " + Resolver.plantName(p) + "'s health");
            JsonNode report = api().postMultipart("/api/plants/" + Json.id(p) + "/health", parts);
            JsonNode record = report.path("record");
            print(healthText(Resolver.plantName(p), record));
            String warning = Json.str(report, "speciesWarning");
            if (warning != null) {
                print("Warning: " + Text.plain(warning));
            }
            if (save || session.confirm("Save these notes to " + Resolver.plantName(p) + "'s notes?")) {
                api().post("/api/plants/health/" + Json.id(record) + "/save-note", null);
                print("Saved to " + Resolver.plantName(p) + "'s notes.");
            } else {
                print("Not saved.");
            }
            print("Not satisfied? Dig deeper with /health research " + Resolver.plantId(p) + ".");
        }
    }

    @Command(name = "research", separator = " ",
            description = "Not satisfied with the last check? The agent searches the web for other causes and fixes.")
    static class Research extends CliCommand {

        @Parameters(index = "0", paramLabel = PlantsCommand.PLANT, description = PlantsCommand.PLANT_HELP)
        String plant;

        @Option(names = "--save", description = "Save the answer to the plant's notes without asking.")
        boolean save;

        Research(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            List<JsonNode> records = Json.list(api().get("/api/plants/" + Json.id(p) + "/health"));
            if (records.isEmpty()) {
                throw new CliException(Resolver.plantName(p) + " hasn't had a health check yet. Start with /health check "
                        + Resolver.plantId(p) + " --describe \"<problem>\".");
            }
            status("Searching the web for more about " + Resolver.plantName(p) + "'s problem");
            JsonNode research = api().post("/api/plants/health/" + Json.id(records.get(0)) + "/research", null);
            print(Views.research(research));
            if (save || session.confirm("Save this to " + Resolver.plantName(p) + "'s notes?")) {
                api().post("/api/plants/" + Json.id(p) + "/agent-notes", Json.body("text", Json.str(research, "answer")));
                print("Saved to " + Resolver.plantName(p) + "'s notes.");
            } else {
                print("Not saved.");
            }
        }
    }

    @Command(name = "history", separator = " ", description = "List a plant's past health checks, newest first.")
    static class History extends CliCommand {

        @Parameters(index = "0", paramLabel = PlantsCommand.PLANT, description = PlantsCommand.PLANT_HELP)
        String plant;

        History(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode p = find().ownedPlant(plant);
            List<JsonNode> records = Json.list(api().get("/api/plants/" + Json.id(p) + "/health"));
            if (records.isEmpty()) {
                print(Resolver.plantName(p) + " hasn't had a health check yet.");
                return;
            }
            print("Health checks of " + Resolver.plantLabel(p) + ":");
            for (JsonNode r : records) {
                print("  " + Text.when(Json.str(r, "createdAt")) + "  " + Text.words(Json.str(r, "status")) + " ("
                        + r.path("healthScore").asInt() + "/10): " + Text.orUnknown(Json.str(r, "summary")));
            }
        }
    }

    /** A HealthRecord as text: verdict, summary, issues, what to do, comparison with the last check. */
    static String healthText(String plantName, JsonNode record) {
        StringBuilder sb = new StringBuilder("Health check for ").append(plantName).append(": ")
                .append(Text.words(Json.str(record, "status"))).append(" (score ")
                .append(record.path("healthScore").asInt()).append("/10, ")
                .append(Text.words(Json.str(record, "trend"))).append(")\n")
                .append(Text.plain(Json.str(record, "summary")));
        String issues = Text.bullets(Json.str(record, "issues"));
        if (!issues.isEmpty()) {
            sb.append("\nIssues:\n").append(issues);
        }
        String steps = Text.bullets(Json.str(record, "careSteps"));
        if (!steps.isEmpty()) {
            sb.append("\nWhat to do:\n").append(steps);
        }
        String comparison = Json.str(record, "comparison");
        if (comparison != null) {
            sb.append("\nCompared with the last check: ").append(Text.plain(comparison));
        }
        return sb.toString();
    }
}
