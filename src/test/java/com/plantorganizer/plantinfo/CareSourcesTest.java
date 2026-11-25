package com.plantorganizer.plantinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.plantorganizer.websearch.WebResult;

/** How the care sources explain failures, and which web pages the AI is given. */
class CareSourcesTest {

    @Test
    void perenualRejectingTheKeyIsSaidPlainly() {
        // Perenual answers a missing or bad key with HTTP 404 and this message
        assertThat(PerenualAdapter.reason(404, "Missing/Issue with API Key. Contact info@perenual.com"))
                .startsWith("rejected the API key (Perenual said: \"Missing/Issue with API Key.")
                .contains("Check PERENUAL_API_KEY in .env");
        assertThat(PerenualAdapter.reason(401, null)).startsWith("rejected the API key.");
        assertThat(PerenualAdapter.reason(429, null)).startsWith("daily request limit reached.");
        assertThat(PerenualAdapter.reason(404, "Not found")).isEqualTo("returned HTTP 404 (Perenual said: \"Not found\").");
    }

    @Test
    void theAiOnlySeesPagesAboutTheSpeciesItself() {
        WebResult species = new WebResult("Gymnocalycium mihanovichii",
                "Gymnocalycium mihanovichii is a species of cactus ... the combined plant is called a \"moon cactus\".", "u1");
        WebResult genus = new WebResult("Gymnocalycium",
                "Gymnocalycium, commonly called chin cactus, is a genus of about 70 South American species of cactus.", "u2");
        WebResult byCommonName = new WebResult("Snake plant", "Dracaena trifasciata is a species of flowering plant.", "u3");

        assertThat(LlmCareInfoAdapter.aboutSpecies(List.of(species, genus), "Gymnocalycium mihanovichii"))
                .containsExactly(species);
        assertThat(LlmCareInfoAdapter.aboutSpecies(List.of(genus), "Gymnocalycium mihanovichii")).isEmpty();
        assertThat(LlmCareInfoAdapter.aboutSpecies(List.of(byCommonName, genus), "Dracaena trifasciata"))
                .containsExactly(byCommonName);
    }
}
