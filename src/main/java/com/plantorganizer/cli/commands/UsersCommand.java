package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;

import com.plantorganizer.cli.ApiException;
import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Json;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/** There is no login (as in the GUI): the CLI acts as one user at a time, chosen by ID. */
@Command(name = "/users", separator = " ", description = "See the users on this server and switch between them.",
        subcommands = {UsersCommand.ListUsers.class, UsersCommand.Switch.class})
public class UsersCommand extends GroupCommand {

    @Command(name = "list", separator = " ", description = "List the users and their IDs.")
    static class ListUsers extends CliCommand {

        ListUsers(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            List<String[]> rows = new ArrayList<>();
            for (JsonNode u : Json.list(api().get("/api/users"))) {
                rows.add(new String[] {String.valueOf(Json.id(u)), Json.str(u, "displayName"),
                        Json.id(u) == session.userId() ? "(you)" : ""});
            }
            print("Users:");
            print(Text.table(rows, "  "));
            print("Act as another user with /users switch <id>.");
        }
    }

    @Command(name = "switch", separator = " ", description = "Act as another user from now on.")
    static class Switch extends CliCommand {

        @Parameters(index = "0", paramLabel = "<user id>", description = "The user's ID, a number (see /users list).")
        String userId;

        Switch(Session session) {
            super(session);
        }

        @Override
        protected void execute() {
            if (!userId.strip().matches("\\d+")) {
                throw new CliException("User IDs are numbers, e.g. /users switch 2. See /users list.");
            }
            long id = Long.parseLong(userId.strip());
            long previous = api().userId();
            api().setUserId(id);
            JsonNode user;
            try {
                user = api().get("/api/users/me");
            } catch (ApiException e) {
                api().setUserId(previous);
                if (e.status() == 404) {
                    throw new CliException("No such user found. See /users list.");
                }
                throw e;
            }
            session.switchUser(id, Json.str(user, "displayName"));
            print("You are now " + session.userName() + " (user " + id + ").");
        }
    }
}
