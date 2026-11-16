package com.plantorganizer.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.plantorganizer.agent.AgentPlannerAccess;
import com.plantorganizer.agent.ChatIntent;
import com.plantorganizer.plants.DesiredPlant.FitVerdict;
import com.plantorganizer.spaces.LightLevel;

import tools.jackson.databind.json.JsonMapper;

/** Deterministic rules around the AI: F07 constraint filter, F06 sorting, F09 light rule, CLI keyword fallback. */
class RecommendationRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static RecommendedPlant rec(String name, String care, String watering, Boolean petSafe, String space) {
        return new RecommendedPlant(1, name, name, "why", care, space, "bright", watering, petSafe);
    }

    @Test
    void constraintFilterDropsCandidatesThatBreakAHardRule() {
        var constraints = JSON.readTree("{\"maxCareLevel\":\"Easy\",\"petSafeOnly\":true,\"wateringPreference\":\"Minimum\"}");
        assertThat(UserConstraintStrategy.satisfies(rec("Spider plant", "Easy", "Average", true, null), constraints)).isTrue();
        assertThat(UserConstraintStrategy.satisfies(rec("ZZ plant", "Easy", "Minimum", false, null), constraints)).isFalse();
        assertThat(UserConstraintStrategy.satisfies(rec("Calathea", "Hard", "Average", true, null), constraints)).isFalse();
        assertThat(UserConstraintStrategy.satisfies(rec("Fern", "Easy", "Frequent", true, null), constraints)).isFalse();
    }

    @Test
    void sortsByEaseOfCareNameOrSpace() {
        List<RecommendedPlant> list = List.of(rec("Calathea", "Hard", "", null, "Bathroom"),
                rec("Pothos", "Easy", "", null, null), rec("Begonia", "Medium", "", null, "Attic"));
        assertThat(RecommendationEngine.sort(list, RecommendationEngine.SortBy.CARE))
                .extracting(RecommendedPlant::getScientificName).containsExactly("Pothos", "Begonia", "Calathea");
        assertThat(RecommendationEngine.sort(list, RecommendationEngine.SortBy.NAME))
                .extracting(RecommendedPlant::getScientificName).containsExactly("Begonia", "Calathea", "Pothos");
        assertThat(RecommendationEngine.sort(list, RecommendationEngine.SortBy.SPACE))
                .extracting(RecommendedPlant::getScientificName).containsExactly("Begonia", "Calathea", "Pothos");
    }

    @Test
    void lightRuleJudgesTheWishlistFit() {
        assertThat(AgentPlannerAccess.fit(List.of("full sun"), LightLevel.DIRECT_SUN)).isEqualTo(FitVerdict.GOOD);
        assertThat(AgentPlannerAccess.fit(List.of("bright indirect light"), LightLevel.MEDIUM)).isEqualTo(FitVerdict.FAIR);
        assertThat(AgentPlannerAccess.fit(List.of("full sun"), LightLevel.LOW)).isEqualTo(FitVerdict.POOR);
        assertThat(AgentPlannerAccess.fit(List.of(), LightLevel.LOW)).isEqualTo(FitVerdict.UNKNOWN);
    }

    @Test
    void keywordFallbackStillRecognisesBasicCommands() {
        assertThat(AgentPlannerAccess.keywords("I watered the fern", false)).isEqualTo(ChatIntent.Type.MARK_WATERED);
        assertThat(AgentPlannerAccess.keywords("what needs water today?", false)).isEqualTo(ChatIntent.Type.WATERING_STATUS);
        assertThat(AgentPlannerAccess.keywords("undo that please", false)).isEqualTo(ChatIntent.Type.UNDO_SCHEDULE);
        assertThat(AgentPlannerAccess.keywords("", true)).isEqualTo(ChatIntent.Type.IDENTIFY);
        assertThat(AgentPlannerAccess.keywords("update the care notes for my fern", false)).isEqualTo(ChatIntent.Type.UPDATE_CARE_NOTES);
        assertThat(AgentPlannerAccess.keywords("add a note to my fern: repotted", false)).isEqualTo(ChatIntent.Type.ADD_NOTE);
    }
}
