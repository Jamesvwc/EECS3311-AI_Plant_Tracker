package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.jline.reader.Candidate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.plantorganizer.support.ExternalApiStub;

import tools.jackson.databind.JsonNode;

/**
 * Agent.jar end to end: the real server runs on a random port (external APIs
 * replaced by {@link ExternalApiStub}) and the CLI is driven with typed lines,
 * exactly as a user would at the terminal. Each test acts as a new user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCliIntegrationTest {

    private static final ExternalApiStub STUB = new ExternalApiStub();

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        for (String api : new String[] {"plantnet", "perenual", "cohere", "websearch"}) {
            registry.add("plantorganizer." + api + ".base-url", STUB::baseUrl);
        }
        for (String api : new String[] {"plantnet", "perenual", "cohere"}) {
            registry.add("plantorganizer." + api + ".api-key", () -> "test-key");
        }
        registry.add("plantorganizer.scheduling.enabled", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:cli;MODE=PostgreSQL");
    }

    @Value("${local.server.port}")
    private int port;

    private String server;
    private String user;

    @BeforeEach
    void newUser() {
        server = "http://localhost:" + port;
        user = newUser("Tester");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    private String newUser(String name) {
        return String.valueOf(new AgentClient(server, 1).post("/api/users", Json.body("displayName", name)).path("id").asLong());
    }

    /** Runs Agent.jar as {@code user} with these typed lines (answers to confirmations included); returns what it printed. */
    private String cli(String... lines) {
        return cliAs(user, lines);
    }

    private String cliAs(String userId, String... lines) {
        StringWriter out = new StringWriter();
        int code = AgentCli.run(new String[] {"--server", server, "--user", userId},
                new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), new PrintWriter(out, true));
        assertThat(code).as(out.toString()).isZero();
        return out.toString();
    }

    // ------------------------------------------------------------------ start-up and command rules

    @Test
    void startUpNeedsARunningServerAndAnExistingUser() {
        StringWriter out = new StringWriter();
        assertThat(AgentCli.run(new String[] {"--server", "http://localhost:1"}, new BufferedReader(new StringReader("")),
                new PrintWriter(out, true))).isEqualTo(1);
        assertThat(out.toString()).contains("No Plant Organizer server found at http://localhost:1");

        out = new StringWriter();
        assertThat(AgentCli.run(new String[] {"--server", server, "--user", "999999"}, new BufferedReader(new StringReader("")),
                new PrintWriter(out, true))).isEqualTo(1);
        assertThat(out.toString()).contains("No such user found: 999999");

        assertThat(cli("/quit")).contains("connected to " + server + " as Tester", "Goodbye!");
    }

    @Test
    void onlySlashCommandsAreAcceptedAndHelpListsThemAll() {
        String out = cli("hello there", "/fly", "/plants", "/help", "/help plants add", "/help nope", "/quit");
        assertThat(out.split("Unknown command\\. Type /help for more info\\.", -1)).hasSize(3);
        assertThat(out).contains("/plants needs a verb. Choose one of: list, show, add");
        assertThat(out).contains("/plants add <scientific name> [options]", "/identify photo <file>...",
                "/recommend keep <recommendation>", "/wishlist add <scientific name> [options]", "/chat [<message>]",
                "Rules:", "They are separate lists.");
        assertThat(out).contains("Usage: /plants add", "--nickname <nickname>");
        assertThat(out).contains("There is no /nope command.");
    }

    // ------------------------------------------------------------------ plants, notes, health

    @Test
    void plantsAreAddedListedWateredAndNoted() {
        String out = cli(
                "/plants add \"Monstera deliciosa\" --nickname Monty", "y",
                "/plants add \"Monstera deliciosa\"", "y",
                "/plants add \"Poopus maximus\"", "y",
                "/plants add \"Dracaena trifasciata\"",
                "/plants list", // typed instead of answering y/N: counts as no, and runs next
                "/plants water Monty",
                "/plants water monstera",
                "/plants water Fern",
                "/plants note Monty repotted today",
                "/plants note Monty \"repotted today\"", "y",
                "/plants notes Monty",
                "/health check Monty --describe \"yellow leaves\"", "y",
                "/plants remove Monty", "n",
                "/quit");
        assertThat(out).containsPattern("Added Monty to your plants as P\\d+\\.");
        assertThat(out).contains("No plant called \"Poopus maximus\" could be found");
        assertThat(out).contains("Cancelled.\nagent[Tester]> /plants list\nYour plants (2 plants):");
        assertThat(out).doesNotContain("Snake plant");
        assertThat(out).containsPattern("Swiss cheese plant \\(Monstera deliciosa\\)\\s+x2\\s+P\\d+ \"Monty\", P\\d+");
        assertThat(out).contains("Plant was watered.");
        assertThat(out).contains("\"monstera\" matches more than one");
        assertThat(out).contains("You do not own a \"Fern\" plant.");
        assertThat(out).contains("Unmatched argument at index 3: 'today'",
                "Values with spaces must be in quotes", "Usage: /plants note <plant> <note>");
        assertThat(out).contains("Note added to Monty.", "(you):", "repotted today");
        assertThat(out).contains("Health check for Monty: minor issues (score 4/10, first check)", "Yellow leaves",
                "Let it dry out", "Saved to Monty's notes.");
        assertThat(out).doesNotContain("Removed P");
    }

    @Test
    void whenTheAiWritesTheCareNotesAddingSaysWhy() {
        // The stub's Perenual has the spider plant only on its paid plan, so the AI writes its care notes.
        String out = cli("/plants add \"Chlorophytum comosum\"", "y", "/plants add \"Monstera deliciosa\"", "y", "/quit");
        assertThat(out).contains("Note: These care notes were written by the AI, so they are less reliable: "
                + "Perenual: the free plan does not include care details for this species.");
        assertThat(out.split("Note: These care notes were written by the AI", -1)).hasSize(2); // not for the monstera
    }

    // ------------------------------------------------------------------ tab completion

    @Test
    void tabCompletionLooksUpYourPlantsSpacesAndUsers() {
        cli("/plants add \"Monstera deliciosa\" --nickname Monty", "y",
                "/spaces add-room \"Living room\" bright-indirect", "y",
                "/spaces add-spot \"Living room\" \"Window sill\"", "y",
                "/quit");
        PrintWriter out = new PrintWriter(new StringWriter());
        CliConsole console = new CliConsole(LineInput.plain(new BufferedReader(new StringReader("")), out), out);
        Session session = new Session(new AgentClient(server, Long.parseLong(user)), console, "Tester");
        new CommandDispatcher(session);
        CommandCompleter completer = new CommandCompleter(session);

        assertThat(completer.candidates(List.of("/plants", "water", ""), 2, "")).singleElement().satisfies(c -> {
            assertThat(c.value()).matches("P\\d+");
            assertThat(c.descr()).isEqualTo("\"Monty\" (Swiss cheese plant)");
        });
        assertThat(values(completer.candidates(List.of("/plants", "water", "Mo"), 2, "Mo")))
                .contains("Monty", "Monstera deliciosa", "Swiss cheese plant");
        assertThat(values(completer.candidates(List.of("/spaces", "place", ""), 2, ""))).containsExactly("Living room");
        assertThat(values(completer.candidates(List.of("/spaces", "place", "Living room", ""), 3, "")))
                .containsExactly("Window sill");
        assertThat(values(completer.candidates(List.of("/users", "switch", ""), 2, ""))).contains(user);
    }

    private static List<String> values(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).toList();
    }

    // ------------------------------------------------------------------ identification

    @Test
    void identifyThenSelectOneMatchOnce(@TempDir Path dir) throws Exception {
        Path photo = dir.resolve("monstera.png");
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        String out = cli(
                "/identify select 1",
                "/identify photo nope.jpg",
                "/identify photo a b c d e f",
                "/identify photo " + photo,
                "/identify text \"big glossy leaves with holes\"",
                "/identify select 4",
                "/identify select 1",
                "/identify select 1",
                "/quit");
        assertThat(out).contains("No plants have been identified. Use /identify photo");
        assertThat(out).contains("No file found at nope.jpg.", "You can send at most 5 photos at a time.");
        assertThat(out).contains("Best matches from 1 photo:", "1.  Swiss cheese plant (Monstera deliciosa)  90% confidence");
        assertThat(out).contains("Best matches for your description:", "80% confidence");
        assertThat(out).contains("Choose a number from 1 to");
        assertThat(out).contains("What next?", "/plants add \"Monstera deliciosa\" --common-name \"Swiss cheese plant\"");
        assertThat(out.split("No plants have been identified", -1)).hasSize(3); // before identifying, and after selecting
    }

    @Test
    void aDescriptionFindsSpeciesPerenualLacksWithTheirPhotos() {
        String moonCactus = "A small, colorful cactus species from South America, often grafted onto a Hylocereus cactus "
                + "due to its lack of chlorophyll.";
        String out = cli("/identify text \"" + moonCactus + "\"", "/quit");
        assertThat(out).contains("Best matches for your description:",
                "1.  Moon cactus (Gymnocalycium mihanovichii)  90% confidence",
                "Photo of 1: https://upload.wikimedia.org/test/Gymnocalycium_mihanovichii.jpg",
                "Note: The AI suggested Gymnocalycium fakeum, but neither Wikipedia nor Perenual knows a species by "
                        + "that name, so it was left out.");

        JsonNode result = new AgentClient(server, Long.parseLong(user)).post("/api/identify/text",
                Json.body("description", moonCactus));
        assertThat(result.path("candidates")).hasSize(1);
        JsonNode match = result.path("candidates").get(0);
        assertThat(Json.str(match, "family")).isEqualTo("Cactaceae");
        assertThat(Json.str(match, "genus")).isEqualTo("Gymnocalycium");
        assertThat(Json.str(match, "referenceImageUrl"))
                .isEqualTo("https://upload.wikimedia.org/test/Gymnocalycium_mihanovichii.jpg");
    }

    @Test
    void whenNoGuessIsARealSpeciesTheReasonIsShown() {
        assertThat(cli("/identify text \"an imaginary plant\"", "/quit")).contains(
                "The agent couldn't match that description to a plant.",
                "Note: The AI suggested Imaginaria fakeii, but neither Wikipedia nor Perenual knows a species by "
                        + "that name, so it was left out.");
    }

    // ------------------------------------------------------------------ read-only chat

    @Test
    void chatAnswersQuestionsAndOnlySuggestsCommandsForChanges() {
        String out = cli(
                "/plants add \"Monstera deliciosa\"", "y",
                "/chat \"how are you?\"",
                "/chat \"I watered the monstera\"", "n",
                "/chat \"I watered the monstera\"", "y",
                "/chat",
                "/chat history",
                "/quit");
        assertThat(out).contains("Agent: ", "(understood as general");
        assertThat(out).containsPattern("Suggested command:\\s+/plants water P\\d+");
        assertThat(out).contains("Not run.");
        assertThat(out.split("Plant was watered\\.", -1)).hasSize(2); // only after the "y"
        assertThat(out).contains("Write your message in quotes");
        assertThat(out).contains("You:   I watered the monstera");
    }

    // ------------------------------------------------------------------ spaces, wishlist, recommendations

    @Test
    void spacesWishlistAndRecommendations() {
        String out = cli(
                "/recommend auto",
                "/recommend ask",
                "/spaces add-room \"Living room\" sunny",
                "/spaces add-room \"Living room\" bright-indirect", "y",
                "/spaces add-spot \"Living room\" \"Window sill\"", "y",
                "/spaces add-spot Kitchen Shelf",
                "/plants add \"Ficus lyrata\"", "y",
                "/spaces place \"Living room\" \"Window sill\" ficus",
                "/spaces list",
                "/wishlist list",
                "/wishlist add \"Epipremnum aureum\"", "y",
                "/wishlist fit pothos --room \"Living room\" --spot \"Window sill\"",
                "/wishlist list",
                "/wishlist bought pothos", "y",
                "/recommend ask \"easy and safe for my cat\"",
                "/recommend keep \"Spider plant\"",
                "/recommend list",
                "/quit");
        assertThat(out).contains("doesn't know anything about you yet");
        assertThat(out).contains("Incorrect usage. Try /recommend ask \"...\", where \"...\" is the kind of plants you would like.");
        assertThat(out).contains("\"sunny\" isn't a light level. Choose one of: low (Low light)", "bright-indirect (Bright indirect light)");
        assertThat(out).contains("Added the room Living room.", "Added Living room > Window sill.");
        assertThat(out).contains("No room called \"Kitchen\".");
        assertThat(out).contains("is now in Living room > Window sill.");
        assertThat(out).containsPattern("- Window sill  \\[Bright indirect light, from the room\\]  .*\\(P\\d+\\)");
        assertThat(out).contains("No plants are wishlisted.");
        assertThat(out).containsPattern("Added Golden pothos \\(Epipremnum aureum\\) to your wishlist as W\\d+\\.");
        assertThat(out).contains("Fit for Living room > Window sill:");
        assertThat(out).containsPattern("W\\d+\\s+Golden pothos \\(Epipremnum aureum\\)\\s+for Living room > Window sill\\s+fit: ");
        assertThat(out).containsPattern("Added Golden pothos to your plants as P\\d+\\.");
        assertThat(out).containsPattern("R\\d+ Spider plant \\(Chlorophytum comosum\\)");
        assertThat(out).contains("Kept Spider plant (Chlorophytum comosum) on your recommendations.");
    }

    // ------------------------------------------------------------------ reminders and calendar

    @Test
    void remindersAndCalendar() {
        String out = cli(
                "/notifications list",
                "/plants add \"Monstera deliciosa\"", "y",
                "/notifications list",
                "/notifications on",
                "/notifications off monstera",
                "/calendar view --days 14",
                "/calendar update \"I'm free on weekday evenings\"",
                "/calendar update \"banana\"",
                "/calendar undo",
                "/quit");
        assertThat(out).contains("No notifications have been set. Add a plant first");
        assertThat(out).contains("Notifications are disabled, try /notifications on to enable notifications.");
        assertThat(out).contains("Reminders are now on for all 1 plant, and will be on for plants you add later.");
        assertThat(out).containsPattern("Reminders are now off for P\\d+ Swiss cheese plant\\.");
        assertThat(out).contains("Watering calendar, next 14 days:");
        assertThat(out).contains("Changes:", "Every plant's watering needs fit your availability.", "Undo with /calendar undo");
        assertThat(out).contains("The agent couldn't work out when you're free: When are you usually home?");
        assertThat(out).contains("Undone:");
    }

    // ------------------------------------------------------------------ friends, sharing, users

    @Test
    void friendsSharingAndUsers() {
        String friend = newUser("Sam");
        String out = cli(
                "/friends list",
                "/friends add abc",
                "/friends add " + friend, "y",
                "/friends allow Sam",
                "/friends list",
                "/share list",
                "/plants add \"Monstera deliciosa\"", "y",
                "/share create monstera --label \"for my sitter\"", "y",
                "/share list",
                "/users list",
                "/users switch 999999",
                "/users switch " + friend,
                "/friends plants Tester",
                "/quit");
        assertThat(out).contains("You haven't added any friends yet.", "User IDs are numbers");
        assertThat(out).contains("Sam is now your friend.", "Sam can now see your plants (names only).");
        assertThat(out).containsPattern("user " + friend + "\\s+Sam\\s+can see your plants: yes");
        assertThat(out).contains("No watering schedules have been shared.");
        assertThat(out).contains("Share link created.", server + "/share.html?token=", "\"for my sitter\": Swiss cheese plant");
        assertThat(out).containsPattern(user + "\\s+Tester\\s+\\(you\\)");
        assertThat(out).contains("No such user found.", "You are now Sam (user " + friend + ").");
        assertThat(out).contains("agent[Sam]> ");
    }
}
