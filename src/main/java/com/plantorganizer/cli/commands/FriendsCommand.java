package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** F11: friends, and whether they may see your plant list (names only). */
@Command(name = "/friends", separator = " ", description = "Friends and whether they can see your plants.",
        subcommands = {FriendsCommand.ListFriends.class, FriendsCommand.Add.class, FriendsCommand.Remove.class,
                FriendsCommand.Allow.class, FriendsCommand.Deny.class, FriendsCommand.Plants.class})
public class FriendsCommand extends GroupCommand {

    public static final String FRIEND = "<friend>";
    static final String FRIEND_HELP = "Your friend's user ID or name (in quotes if it has spaces).";

    @Command(name = "list", separator = " ", description = "List your friends and what each of you can see.")
    static class ListFriends extends CliCommand {

        ListFriends(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<JsonNode> friends = Json.list(api().get("/api/friends"));
            if (friends.isEmpty()) {
                print("You haven't added any friends yet. Add one with /friends add <user id> (see /users list).");
                return;
            }
            List<String[]> rows = new ArrayList<>();
            for (JsonNode f : friends) {
                rows.add(new String[] {"user " + Json.id(f), Json.str(f, "name"),
                        "can see your plants: " + (Json.bool(f, "canSeeMyPlants") ? "yes" : "no"),
                        "shares theirs with you: " + (Json.bool(f, "sharesWithMe") ? "yes" : "no")});
            }
            print("Your friends (" + friends.size() + "):");
            print(Text.table(rows, "  "));
        }
    }

    @Command(name = "add", separator = " ", description = "Add another user as a friend, by user ID (see /users list).")
    static class Add extends CliCommand {

        @Parameters(index = "0", paramLabel = "<user id>", description = "Their user ID, a number.")
        String userId;

        Add(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (!userId.strip().matches("\\d+")) {
                throw new CliException("User IDs are numbers, e.g. /friends add 2. See /users list.");
            }
            long id = Long.parseLong(userId.strip());
            JsonNode user = Json.list(api().get("/api/users")).stream().filter(u -> Json.id(u) == id).findFirst()
                    .orElseThrow(() -> new CliException("No such user found: " + id + ". See /users list."));
            if (!confirm("Add " + Json.str(user, "displayName") + " (user " + id + ") as a friend?")) {
                return;
            }
            JsonNode friend = api().post("/api/friends", Json.body("friendId", id));
            print(Json.str(friend, "name") + " is now your friend. Let them see your plants with /friends allow " + id + ".");
        }
    }

    @Command(name = "remove", separator = " ", description = "Remove a friend.")
    static class Remove extends CliCommand {

        @Parameters(index = "0", paramLabel = FRIEND, description = FRIEND_HELP)
        String friend;

        Remove(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode f = find().friend(friend);
            if (!confirm("Remove " + Json.str(f, "name") + " (user " + Json.id(f) + ") from your friends?")) {
                return;
            }
            api().delete("/api/friends/" + Json.id(f));
            print(Json.str(f, "name") + " is no longer your friend.");
        }
    }

    @Command(name = "allow", separator = " ", description = "Let a friend see your plant list (names only).")
    static class Allow extends CliCommand {

        @Parameters(index = "0", paramLabel = FRIEND, description = FRIEND_HELP)
        String friend;

        Allow(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            print(setCanView(this, friend, true));
        }
    }

    @Command(name = "deny", separator = " ", description = "Stop a friend from seeing your plant list.")
    static class Deny extends CliCommand {

        @Parameters(index = "0", paramLabel = FRIEND, description = FRIEND_HELP)
        String friend;

        Deny(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            print(setCanView(this, friend, false));
        }
    }

    @Command(name = "plants", separator = " ", description = "See a friend's plants (if they let you).")
    static class Plants extends CliCommand {

        @Parameters(index = "0", paramLabel = FRIEND, description = FRIEND_HELP)
        String friend;

        Plants(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            JsonNode f = find().friend(friend);
            List<JsonNode> plants = Json.list(api().get("/api/friends/" + Json.id(f) + "/plants"));
            if (plants.isEmpty()) {
                print(Json.str(f, "name") + " has no plants yet.");
                return;
            }
            print(Json.str(f, "name") + "'s plants:");
            for (JsonNode p : plants) {
                String name = Json.str(p, "name");
                String scientific = Json.str(p, "scientificName");
                print("  - " + name + (scientific != null && !scientific.equalsIgnoreCase(name) ? " (" + scientific + ")" : ""));
            }
        }
    }

    static String setCanView(CliCommand command, String friend, boolean allowed) {
        JsonNode f = command.find().friend(friend);
        command.api().put("/api/friends/" + Json.id(f) + "/can-view", Json.body("allowed", allowed));
        return Json.str(f, "name") + (allowed ? " can now see your plants (names only)." : " can no longer see your plants.");
    }
}
