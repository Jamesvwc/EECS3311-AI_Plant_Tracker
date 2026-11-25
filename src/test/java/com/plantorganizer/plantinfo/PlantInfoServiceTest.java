package com.plantorganizer.plantinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.identification.PlantCandidate;
import com.plantorganizer.websearch.WebResult;
import com.plantorganizer.websearch.WebSearch;

/** Checks the Stage 1 UC13 decision logic: Perenual, one retry, then the AI fallback. */
class PlantInfoServiceTest {

    private static final PlantQuery MONSTERA = PlantQuery.of("Monstera deliciosa", "Swiss cheese plant");

    @Test
    void usesPrimaryWhenItFindsThePlant() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.of(profile(InfoSource.PERENUAL)));
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES).lookup(MONSTERA);

        assertThat(result.profile().source()).isEqualTo(InfoSource.PERENUAL);
        assertThat(primary.searchTerms).containsExactly("Monstera deliciosa");
        assertThat(fallback.searchTerms).isEmpty();
    }

    @Test
    void retriesWithCommonNameWhenNotFound() {
        FakeProvider primary = FakeProvider.sequence(Optional.empty(), Optional.of(profile(InfoSource.PERENUAL)));
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES).lookup(MONSTERA);

        assertThat(result.profile().source()).isEqualTo(InfoSource.PERENUAL);
        assertThat(primary.searchTerms).containsExactly("Monstera deliciosa", "Swiss cheese plant");
        assertThat(fallback.searchTerms).isEmpty();
    }

    // ---------------------------------------------------------------- findSpecies (identifying from a description)

    private static final String MOON_CACTUS = "Gymnocalycium mihanovichii";

    @Test
    void aSpeciesWithAWikipediaPageIsRealAndGetsItsPhoto() {
        FakeProvider perenual = FakeProvider.searching(name -> List.of());
        PlantInfoService service = new PlantInfoService(perenual, null, Wikipedia.with(MOON_CACTUS));

        PlantCandidate found = service.findSpecies(MOON_CACTUS).orElseThrow();

        assertThat(found.scientificName()).isEqualTo(MOON_CACTUS);
        assertThat(found.genus()).isEqualTo("Gymnocalycium");
        assertThat(found.referenceImageUrl()).isEqualTo("https://photo.example/" + MOON_CACTUS + ".jpg");
        assertThat(perenual.searchedNames).isEmpty(); // Perenual's daily quota is left alone
    }

    @Test
    void perenualIsAskedWhenWikipediaHasNoPageAndAMadeUpNameIsNotFound() {
        FakeProvider perenual = FakeProvider.searching(name -> name.startsWith("Monstera deliciosa")
                ? List.of(new PlantCandidate("Monstera deliciosa", null, List.of("Swiss cheese plant"), "Monstera",
                        "Araceae", 0, "https://perenual.example/m.jpg"))
                : List.of());
        PlantInfoService service = new PlantInfoService(perenual, null, Wikipedia.WITHOUT_PAGES);

        assertThat(service.findSpecies("Monstera deliciosa")).get()
                .extracting(PlantCandidate::family, PlantCandidate::referenceImageUrl)
                .containsExactly("Araceae", "https://perenual.example/m.jpg");
        assertThat(service.findSpecies("Monstera fakeii")).isEmpty();
    }

    @Test
    void aPageAboutTheGenusDoesNotConfirmTheSpecies() {
        // Wikipedia redirects some rare species to their genus' page, which doesn't name the species
        Wikipedia genusOnly = new Wikipedia(Map.of("Gymnocalycium fakeum",
                new WebResult("Gymnocalycium", "Gymnocalycium, commonly called chin cactus, is a genus.", "u", "p")));
        PlantInfoService service = new PlantInfoService(FakeProvider.searching(name -> List.of()), null, genusOnly);

        assertThat(service.findSpecies("Gymnocalycium fakeum")).isEmpty();
    }

    @Test
    void aSpeciesMovedToAnotherGenusIsStillFound() {
        WebResult newName = new WebResult("Selenicereus undatus", "Selenicereus undatus, the white-fleshed pitahaya, "
                + "is a species of the genus Selenicereus (formerly Hylocereus).", "u", "https://photo.example/s.jpg");
        PlantInfoService service = new PlantInfoService(FakeProvider.searching(name -> List.of()), null,
                new Wikipedia(Map.of("Hylocereus undatus", newName)));

        assertThat(service.findSpecies("Hylocereus undatus")).get()
                .extracting(PlantCandidate::referenceImageUrl).isEqualTo("https://photo.example/s.jpg");
    }

    @Test
    void whenASourceFailsAndTheOtherDoesNotFindTheSpeciesItIsUnchecked() {
        FakeProvider perenualDown = FakeProvider.searching(name -> {
            throw new ExternalServiceException("Perenual", "daily request limit reached.", false);
        });
        PlantInfoService service = new PlantInfoService(perenualDown, null, Wikipedia.WITHOUT_PAGES);

        assertThatThrownBy(() -> service.findSpecies("Gymnocalycium fakeum"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("daily request limit");
        assertThat(new PlantInfoService(perenualDown, null, Wikipedia.with(MOON_CACTUS)).findSpecies(MOON_CACTUS))
                .isPresent(); // Wikipedia alone is enough
    }

    /** Wikipedia with only the given pages. */
    record Wikipedia(Map<String, WebResult> pages) implements WebSearch {

        static final Wikipedia WITHOUT_PAGES = new Wikipedia(Map.of());

        static Wikipedia with(String species) {
            return new Wikipedia(Map.of(species, new WebResult(species, species + " is a species of cactus.",
                    "https://wiki.example/" + species, "https://photo.example/" + species + ".jpg")));
        }

        @Override
        public List<WebResult> search(String query, int maxResults) {
            return List.of();
        }

        @Override
        public Optional<WebResult> page(String title) {
            return Optional.ofNullable(pages.get(title));
        }
    }

    @Test
    void doesNotRepeatTheSameSearchWhenThereIsNoOtherNameToTry() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.empty());
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES)
                .lookup(PlantQuery.of("Gymnocalycium mihanovichii", null));

        assertThat(primary.searchTerms).containsExactly("Gymnocalycium mihanovichii"); // one search, not two
        assertThat(result.profile().source()).isEqualTo(InfoSource.AI_GENERATED);
        assertThat(result.notes()).first().asString().contains("has no entry for \"Gymnocalycium mihanovichii\"");
    }

    @Test
    void fallsBackToAiAfterTwoTemporaryFailures() {
        FakeProvider primary = FakeProvider.answering(q -> {
            throw new ExternalServiceException("Perenual", "timeout", true);
        });
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES).lookup(MONSTERA);

        assertThat(primary.searchTerms).hasSize(2);
        assertThat(result.profile().source()).isEqualTo(InfoSource.AI_GENERATED);
        assertThat(result.notes()).anyMatch(n -> n.contains("Cohere AI"));
    }

    @Test
    void skipsRetryWhenFailureCannotChange() {
        FakeProvider primary = FakeProvider.answering(q -> {
            throw new ExternalServiceException("Perenual", "premium only", false);
        });
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES).lookup(MONSTERA);

        assertThat(primary.searchTerms).hasSize(1);
        assertThat(result.profile().source()).isEqualTo(InfoSource.AI_GENERATED);
    }

    @Test
    void cachesSuccessfulLookups() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.of(profile(InfoSource.PERENUAL)));
        PlantInfoService service = new PlantInfoService(primary, FakeProvider.answering(q -> Optional.empty()), Wikipedia.WITHOUT_PAGES);

        service.lookup(MONSTERA);
        service.lookup(PlantQuery.of("monstera deliciosa", null));

        assertThat(primary.searchTerms).hasSize(1);
    }

    @Test
    void reportsFailureWhenEverySourceFails() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.empty());
        FakeProvider fallback = FakeProvider.answering(q -> {
            throw new ExternalServiceException("Cohere", "down", true);
        });

        assertThatThrownBy(() -> new PlantInfoService(primary, fallback, Wikipedia.WITHOUT_PAGES).lookup(MONSTERA))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("Cohere");
    }

    private static PlantCareProfile profile(InfoSource source) {
        return new PlantCareProfile("Monstera deliciosa", "Swiss cheese plant", "Araceae", null, "Average",
                "every 7 days", List.of("part shade"), "Medium", null, true, true, null, null, null, source, null);
    }

    /** Minimal hand-written test double that records every search term. */
    private static final class FakeProvider implements PlantInfoProvider {
        final List<String> searchTerms = new ArrayList<>();
        final List<String> searchedNames = new ArrayList<>();
        private final Function<PlantQuery, Optional<PlantCareProfile>> behaviour;
        private Function<String, List<PlantCandidate>> species = name -> List.of();

        private FakeProvider(Function<PlantQuery, Optional<PlantCareProfile>> behaviour) {
            this.behaviour = behaviour;
        }

        static FakeProvider answering(Function<PlantQuery, Optional<PlantCareProfile>> behaviour) {
            return new FakeProvider(behaviour);
        }

        /** A species search (Perenual's species list) answered by {@code species}. */
        static FakeProvider searching(Function<String, List<PlantCandidate>> species) {
            FakeProvider provider = new FakeProvider(q -> Optional.empty());
            provider.species = species;
            return provider;
        }

        @SafeVarargs
        static FakeProvider sequence(Optional<PlantCareProfile>... answers) {
            Deque<Optional<PlantCareProfile>> queue = new ArrayDeque<>(List.of(answers));
            return new FakeProvider(q -> queue.poll());
        }

        @Override
        public String name() {
            return "Cohere AI";
        }

        @Override
        public Optional<PlantCareProfile> getCareProfile(PlantQuery query) {
            searchTerms.add(query.searchTerm());
            return behaviour.apply(query);
        }

        @Override
        public List<PlantCandidate> searchByName(String name) {
            searchedNames.add(name);
            return species.apply(name);
        }
    }
}
