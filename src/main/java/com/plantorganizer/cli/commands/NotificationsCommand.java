package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F04: watering reminders. They are switched per plant; without a plant, for all plants and new ones. */
@Command(name = "/notifications", separator = " ", description = "Watering reminders: see them, turn them on or off.",
        subcommands = {NotificationsCommand.ListReminders.class, NotificationsCommand.On.class, NotificationsCommand.Off.class})
public class NotificationsCommand extends GroupCommand {

    @Command(name = "list", separator = " ", description = "Show upcoming and past watering reminders.")
    static class ListReminders extends CliCommand {

        ListReminders(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> plants = find().ownedPlants();
            if (plants.isEmpty()) {
                print("No notifications have been set. Add a plant first with /plants add \"<scientific name>\".");
                return;
            }
            List<JsonNode> past = Json.list(api().get("/api/notifications"));
            boolean anyOn = plants.stream().anyMatch(p -> Json.bool(p, "notificationsEnabled"));
            if (!anyOn) {
                print("Notifications are disabled, try /notifications on to enable notifications.");
                if (!past.isEmpty()) {
                    print("");
                    printPast(past);
                }
                return;
            }
            JsonNode overview = api().get("/api/calendar/overview");
            List<String[]> upcoming = new ArrayList<>();
            addItems(upcoming, overview.path("overdue"), "Missed:");
            addItems(upcoming, overview.path("dueNow"), "Due now:");
            addItems(upcoming, overview.path("upcoming"), "Coming up:");
            if (upcoming.isEmpty() && past.isEmpty()) {
                print("No notifications have been set.");
                return;
            }
            if (!upcoming.isEmpty()) {
                print("Upcoming reminders:");
                print(Text.table(upcoming, "  "));
            }
            if (!past.isEmpty()) {
                print("");
                printPast(past);
            }
            long off = plants.stream().filter(p -> !Json.bool(p, "notificationsEnabled")).count();
            if (off > 0) {
                print("(Reminders are off for " + Text.count((int) off, "plant") + "; /notifications on <plant> turns them on.)");
            }
        }

        private static void addItems(List<String[]> rows, JsonNode items, String label) {
            for (JsonNode i : Json.list(items)) {
                if (Json.bool(i, "notificationsEnabled")) {
                    rows.add(new String[] {label, Json.str(i, "plantName") + " (P" + i.path("plantId").asLong() + ")",
                            Text.when(Json.str(i, "dueAt"))});
                }
            }
        }

        private void printPast(List<JsonNode> past) {
            print("Past reminders:");
            boolean unread = false;
            for (JsonNode n : past) {
                boolean isNew = !Json.bool(n, "read");
                unread |= isNew;
                print("  " + Text.when(Json.str(n, "createdAt")) + (isNew ? "  [new] " : "  ") + Text.plain(Json.str(n, "message")));
            }
            if (unread) {
                api().post("/api/notifications/read", null);
            }
        }
    }

    @Command(name = "on", separator = " ",
            description = "Turn watering reminders on for one plant, or (without a plant) for all plants and new ones.")
    static class On extends CliCommand {

        @Parameters(index = "0", arity = "0..1", paramLabel = "<plant>", description = "Optional: " + PlantsCommand.PLANT_HELP)
        String plant;

        On(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            print(toggle(this, plant, true));
        }
    }

    @Command(name = "off", separator = " ",
            description = "Turn watering reminders off for one plant, or (without a plant) for all plants and new ones.")
    static class Off extends CliCommand {

        @Parameters(index = "0", arity = "0..1", paramLabel = "<plant>", description = "Optional: " + PlantsCommand.PLANT_HELP)
        String plant;

        Off(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            print(toggle(this, plant, false));
        }
    }

    static String toggle(CliCommand command, String plant, boolean on) {
        String state = on ? "on" : "off";
        if (plant != null) {
            JsonNode p = command.find().ownedPlant(plant);
            command.api().put("/api/plants/" + Json.id(p) + "/notifications", Json.body("enabled", on));
            return "Reminders are now " + state + " for " + Resolver.plantLabel(p) + ".";
        }
        command.api().post("/api/notifications/default", Json.body("enabled", on, "plantId", null));
        List<JsonNode> plants = command.find().ownedPlants();
        for (JsonNode p : plants) {
            command.api().put("/api/plants/" + Json.id(p) + "/notifications", Json.body("enabled", on));
        }
        return "Reminders are now " + state + (plants.isEmpty() ? "" : " for all " + Text.count(plants.size(), "plant") + ",")
                + " and will be " + state + " for plants you add later.";
    }
}
