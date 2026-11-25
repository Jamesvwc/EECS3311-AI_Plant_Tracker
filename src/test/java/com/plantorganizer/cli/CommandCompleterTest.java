package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.junit.jupiter.api.Test;

/**
 * Tab completion that needs no server: commands, verbs, options, fixed
 * choices, and how the line is split. Values looked up on the server (plants,
 * rooms...) are covered in AgentCliIntegrationTest.
 */
class CommandCompleterTest {

    private final CommandCompleter completer;

    CommandCompleterTest() {
        PrintWriter out = new PrintWriter(new StringWriter());
        CliConsole console = new CliConsole(LineInput.plain(new BufferedReader(new StringReader("")), out), out);
        Session session = new Session(new AgentClient("http://localhost:1", 1), console, "Me");
        new CommandDispatcher(session);
        completer = new CommandCompleter(session);
    }

    private List<String> offered(String... words) {
        List<String> list = List.of(words);
        return completer.candidates(list, list.size() - 1, list.get(list.size() - 1)).stream().map(Candidate::value).toList();
    }

    @Test
    void theFirstWordIsACommandThenAVerb() {
        assertThat(offered("/pl")).contains("/plants", "/wishlist", "/help", "/quit");
        assertThat(offered("/plants", "")).contains("list", "add", "water", "remove").doesNotContain("--help");
        assertThat(offered("/plantz", "")).isEmpty();
        assertThat(offered("plants", "")).isEmpty();
    }

    @Test
    void optionsAreOfferedOnceTheRequiredValuesAreGiven() {
        assertThat(offered("/plants", "add", "")).isEmpty(); // the species: free text, typed first
        assertThat(offered("/plants", "add", "Ficus lyrata", "")).contains("--nickname").doesNotContain("--help");
        assertThat(offered("/plants", "add", "Ficus lyrata", "--nickname", "Fig", "")).doesNotContain("--nickname");
        assertThat(offered("/plants", "add", "-")).contains("--nickname", "--help");
        assertThat(offered("/plants", "add", "--nickname", "")).isEmpty(); // a new nickname: free text
    }

    @Test
    void fixedChoicesAreOffered() {
        assertThat(offered("/plants", "care", "P1", "--indoor", "")).containsExactly("yes", "no");
        assertThat(offered("/plants", "care", "P1", "--watering", "")).contains("Frequent", "Average", "Minimum", "None");
        assertThat(offered("/spaces", "add-room", "Kitchen", "")).containsExactly("low", "medium", "bright-indirect", "direct-sun");
        assertThat(offered("/recommend", "list", "--sort", "")).containsExactly("care", "name", "space");
    }

    @Test
    void namesForSomethingNewAreNotCompleted() {
        assertThat(offered("/spaces", "add-room", "")).isEmpty();
        assertThat(offered("/spaces", "add-spot", "Kitchen", "")).isEmpty();
    }

    @Test
    void helpCompletesCommandNamesThenVerbs() {
        assertThat(offered("/help", "")).contains("plants", "wishlist").doesNotContain("/plants");
        assertThat(offered("/help", "plants", "")).contains("add", "water");
    }

    @Test
    void theLineIsSplitAsTheDispatcherSplitsIt() {
        CommandCompleter.Parser parser = new CommandCompleter.Parser();

        CompletingParsedLine open = (CompletingParsedLine) parser.parse("/plants water \"Monstera del", 27, null);
        assertThat(open.words()).containsExactly("/plants", "water", "Monstera del");
        assertThat(open.wordIndex()).isEqualTo(2);
        assertThat(open.wordCursor()).isEqualTo(12);
        assertThat(open.rawWordCursor()).isEqualTo(13);
        assertThat(open.escape("Monstera deliciosa", true)).hasToString("\"Monstera deliciosa\"");

        CompletingParsedLine next = (CompletingParsedLine) parser.parse("/plants ", 8, null);
        assertThat(next.words()).containsExactly("/plants", "");
        assertThat(next.wordIndex()).isEqualTo(1);
        assertThat(next.escape("water", true)).hasToString("water");

        CompletingParsedLine middle = (CompletingParsedLine) parser.parse("/plants  Monty", 8, null);
        assertThat(middle.words()).containsExactly("/plants", "", "Monty");
        assertThat(middle.wordIndex()).isEqualTo(1);

        CompletingParsedLine plain = (CompletingParsedLine) parser.parse("/plants water Mon", 17, null);
        assertThat(plain.escape("Monstera deliciosa", true)).hasToString("\"Monstera deliciosa\"");
        assertThat(plain.escape("Monstera d", false)).hasToString("\"Monstera d");
        assertThat(plain.escape("Monty", true)).hasToString("Monty");

        CompletingParsedLine single = (CompletingParsedLine) parser.parse("/plants water 'Mon", 18, null);
        assertThat(single.escape("Monty's fern", true)).hasToString("'Monty\\'s fern'");
    }
}
