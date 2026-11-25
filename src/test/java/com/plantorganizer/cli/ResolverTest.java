package com.plantorganizer.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** How names and IDs typed in the CLI are matched to the user's plants. */
class ResolverTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<JsonNode> PLANTS = List.of(
            plant(3, "Monty", "Swiss cheese plant", "Monstera deliciosa"),
            plant(7, null, "Swiss cheese plant", "Monstera deliciosa"),
            plant(9, null, "Snake plant", "Dracaena trifasciata"));
    private static final Function<JsonNode, List<String>> NAMES = p -> java.util.stream.Stream.of(
            Json.str(p, "nickname"), Json.str(p, "commonName"), Json.str(p, "scientificName"))
            .filter(n -> n != null).map(String::toLowerCase).toList();

    private static JsonNode plant(long id, String nickname, String common, String scientific) {
        return JSON.valueToTree(java.util.Map.of("id", id, "nickname", nickname == null ? "" : nickname,
                "commonName", common, "scientificName", scientific));
    }

    private static JsonNode match(String ref) {
        return Resolver.match(PLANTS, ref, "P", NAMES, Resolver::plantLabel, "You do not own a \"" + ref + "\" plant.", "");
    }

    @Test
    void idsNicknamesAndUniqueNamesMatch() {
        assertThat(Json.id(match("P7"))).isEqualTo(7);
        assertThat(Json.id(match("p9"))).isEqualTo(9);
        assertThat(Json.id(match("monty"))).isEqualTo(3);
        assertThat(Json.id(match("snake plant"))).isEqualTo(9);
        assertThat(Json.id(match("dracaena"))).isEqualTo(9); // genus
    }

    @Test
    void ambiguousNamesListTheCandidates() {
        assertThatThrownBy(() -> match("Swiss cheese plant")).hasMessageContaining("matches more than one")
                .hasMessageContaining("P3 \"Monty\"").hasMessageContaining("P7");
    }

    @Test
    void unknownNamesAndBareNumbersAreExplained() {
        assertThatThrownBy(() -> match("Fern")).hasMessageStartingWith("You do not own a \"Fern\" plant.");
        assertThatThrownBy(() -> match("Snake plnt")).hasMessageContaining("Did you mean P9");
        assertThatThrownBy(() -> match("3")).hasMessageContaining("IDs start with P, e.g. P3");
        assertThatThrownBy(() -> match("P42")).hasMessageContaining("Nothing has the ID P42");
    }
}
