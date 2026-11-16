package com.plantorganizer.plantinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.plantorganizer.common.ExternalServiceException;

/** Checks the Stage 1 UC13 decision logic: Perenual, one retry, then the AI fallback. */
class PlantInfoServiceTest {

    private static final PlantQuery MONSTERA = PlantQuery.of("Monstera deliciosa", "Swiss cheese plant");

    @Test
    void usesPrimaryWhenItFindsThePlant() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.of(profile(InfoSource.PERENUAL)));
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback).lookup(MONSTERA);

        assertThat(result.profile().source()).isEqualTo(InfoSource.PERENUAL);
        assertThat(primary.searchTerms).containsExactly("Monstera deliciosa");
        assertThat(fallback.searchTerms).isEmpty();
    }

    @Test
    void retriesWithCommonNameWhenNotFound() {
        FakeProvider primary = FakeProvider.sequence(Optional.empty(), Optional.of(profile(InfoSource.PERENUAL)));
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback).lookup(MONSTERA);

        assertThat(result.profile().source()).isEqualTo(InfoSource.PERENUAL);
        assertThat(primary.searchTerms).containsExactly("Monstera deliciosa", "Swiss cheese plant");
        assertThat(fallback.searchTerms).isEmpty();
    }

    @Test
    void fallsBackToAiAfterTwoTemporaryFailures() {
        FakeProvider primary = FakeProvider.answering(q -> {
            throw new ExternalServiceException("Perenual", "timeout", true);
        });
        FakeProvider fallback = FakeProvider.answering(q -> Optional.of(profile(InfoSource.AI_GENERATED)));

        CareLookup result = new PlantInfoService(primary, fallback).lookup(MONSTERA);

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

        CareLookup result = new PlantInfoService(primary, fallback).lookup(MONSTERA);

        assertThat(primary.searchTerms).hasSize(1);
        assertThat(result.profile().source()).isEqualTo(InfoSource.AI_GENERATED);
    }

    @Test
    void cachesSuccessfulLookups() {
        FakeProvider primary = FakeProvider.answering(q -> Optional.of(profile(InfoSource.PERENUAL)));
        PlantInfoService service = new PlantInfoService(primary, FakeProvider.answering(q -> Optional.empty()));

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

        assertThatThrownBy(() -> new PlantInfoService(primary, fallback).lookup(MONSTERA))
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
        private final Function<PlantQuery, Optional<PlantCareProfile>> behaviour;

        private FakeProvider(Function<PlantQuery, Optional<PlantCareProfile>> behaviour) {
            this.behaviour = behaviour;
        }

        static FakeProvider answering(Function<PlantQuery, Optional<PlantCareProfile>> behaviour) {
            return new FakeProvider(behaviour);
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
    }
}
