package com.plantorganizer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.plantorganizer.notification.NotificationService;
import com.plantorganizer.support.ExternalApiStub;
import com.plantorganizer.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end checks of F02-F12 and the chat-box CLI through the REST API, the
 * AgentController facade, the agent, its tools and the real adapters, with
 * the external APIs replaced by {@link ExternalApiStub}. Each test creates its
 * own user so tests do not see each other's plants.
 */
@SpringBootTest
class FeaturesIntegrationTest {

    private static final ExternalApiStub STUB = new ExternalApiStub();
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Instant START = LocalDateTime.of(2026, 11, 12, 12, 0).atZone(ZONE).toInstant();
    private static final MutableClock CLOCK = new MutableClock(START, ZONE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        for (String api : new String[] {"plantnet", "perenual", "cohere", "websearch"}) {
            registry.add("plantorganizer." + api + ".base-url", STUB::baseUrl);
        }
        for (String api : new String[] {"plantnet", "perenual", "cohere"}) {
            registry.add("plantorganizer." + api + ".api-key", () -> "test-key");
        }
        registry.add("plantorganizer.scheduling.enabled", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:features;MODE=PostgreSQL");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private NotificationService notificationService;

    private MockMvc mvc;
    private String user;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        CLOCK.set(START);
        user = newUser("Tester");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    // ------------------------------------------------------------------ helpers

    private String newUser(String name) throws Exception {
        return String.valueOf(json(mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + name + "\"}"))).path("id").longValue());
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(B request, String userId) {
        return request.header("X-User-Id", userId);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json(mvc.perform(as(request, user)).andExpect(status().is2xxSuccessful()));
    }

    private static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    private long addPlant(String scientific, String common) throws Exception {
        return call(post("/api/plants"), "{\"scientificName\":\"" + scientific + "\",\"commonName\":\"" + common + "\"}")
                .path("plantId").longValue();
    }

    // ------------------------------------------------------------------ F08 + F02

    @Test
    void ownedPlantsGetCareNotesAndASchedule_secondPlantIsGrouped() throws Exception {
        JsonNode first = call(post("/api/plants"), "{\"scientificName\":\"Monstera deliciosa\",\"commonName\":\"Swiss cheese plant\"}");
        assertThat(first.at("/schedule/intervalDays").intValue()).isEqualTo(8);
        assertThat(first.at("/schedule/grouped").booleanValue()).isFalse();
        assertThat(first.at("/schedule/askAboutNotifications").booleanValue()).isTrue();
        assertThat(first.at("/schedule/explanation").asString()).isEqualTo("Agent explanation.");

        JsonNode second = call(post("/api/plants"), "{\"scientificName\":\"Epipremnum aureum\",\"commonName\":\"Golden pothos\"}");
        assertThat(second.at("/schedule/grouped").booleanValue()).isTrue();
        assertThat(second.at("/schedule/groupedWith/0").asString()).isEqualTo("Swiss cheese plant");
        assertThat(second.at("/schedule/nextWaterings/0").asString()).isEqualTo(first.at("/schedule/nextWaterings/0").asString());

        JsonNode list = call(get("/api/plants"), null);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(1).at("/care/wateringFrequency").asString()).isEqualTo("every 7-10 days");
        assertThat(list.get(1).path("nextWatering").isNull()).isFalse();
    }

    @Test
    void missingCareDataLeavesNotesEmptyAndOffersResearch() throws Exception {
        JsonNode added = call(post("/api/plants"), "{\"scientificName\":\"Plantus nowhereus\",\"commonName\":\"Ghost\"}");
        // Perenual has no entry, the AI fallback answers for a similar plant; the plant is still added
        assertThat(added.path("plantId").longValue()).isPositive();

        JsonNode researched = call(post("/api/plants/" + added.path("plantId").longValue() + "/schedule/research"), null);
        assertThat(researched.path("explanation").asString()).contains("Water every 7-10 days [1]").contains("Sources:");
        assertThat(researched.path("intervalQuality").asString()).isEqualTo("GOOD");
    }

    // ------------------------------------------------------------------ F03

    @Test
    void availabilityReschedulesEveryPlantAndUndoRestoresTheOldTimes() throws Exception {
        addPlant("Monstera deliciosa", "Swiss cheese plant");
        String from = LocalDate.of(2026, 11, 12).toString();
        String to = LocalDate.of(2026, 11, 30).toString();
        String before = call(get("/api/calendar").param("from", from).param("to", to), null).get(0).path("dueAt").asString();
        assertThat(before).endsWith("09:00:00");

        JsonNode result = json(mvc.perform(as(multipart("/api/calendar/availability").param("text", "free evenings after work"), user))
                .andExpect(status().isOk()));
        assertThat(result.path("plantsRescheduled").intValue()).isEqualTo(1);
        assertThat(call(get("/api/calendar").param("from", from).param("to", to), null).get(0).path("dueAt").asString())
                .endsWith("17:30:00"); // first free window + 30 minutes of travel

        assertThat(call(post("/api/calendar/undo"), null).path("undone").booleanValue()).isTrue();
        assertThat(call(get("/api/calendar").param("from", from).param("to", to), null).get(0).path("dueAt").asString())
                .isEqualTo(before);
    }

    @Test
    void unreadableAvailabilityAsksAClarifyingQuestion() throws Exception {
        mvc.perform(as(multipart("/api/calendar/availability").param("text", "banana"), user))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("When are you usually home?"));
    }

    // ------------------------------------------------------------------ F04

    @Test
    void reminderTimerMovesThePlantThroughItsStatesAndNotifiesWhenEnabled() throws Exception {
        long plantId = addPlant("Monstera deliciosa", "Swiss cheese plant");
        call(put("/api/plants/" + plantId + "/notifications"), "{\"enabled\":true}");
        LocalDateTime due = LocalDateTime.parse(call(get("/api/plants/" + plantId), null).path("nextWatering").asString());

        CLOCK.set(due.plusMinutes(5).atZone(ZONE).toInstant());
        notificationService.checkDueEntries();
        assertThat(call(get("/api/plants/" + plantId), null).path("wateringStatus").asString()).isEqualTo("DUE");

        CLOCK.advance(Duration.ofDays(2));
        notificationService.checkDueEntries();
        JsonNode overview = call(get("/api/calendar/overview"), null);
        assertThat(overview.at("/overdue/0/plantId").longValue()).isEqualTo(plantId);
        JsonNode notifications = call(get("/api/notifications"), null);
        assertThat(notifications.findValuesAsString("type")).containsExactly("MISSED", "DUE");

        JsonNode watered = call(post("/api/plants/" + plantId + "/water"), null);
        assertThat(watered.path("message").asString()).contains("watered late");
        assertThat(call(get("/api/plants/" + plantId), null).path("wateringStatus").asString()).isEqualTo("OK");
        assertThat(call(get("/api/notifications"), null).findValues("read")).allMatch(JsonNode::booleanValue);
    }

    @Test
    void noRemindersAreSentWhenTheyAreOff() throws Exception {
        long plantId = addPlant("Monstera deliciosa", "Swiss cheese plant");
        LocalDateTime due = LocalDateTime.parse(call(get("/api/plants/" + plantId), null).path("nextWatering").asString());
        CLOCK.set(due.plusMinutes(5).atZone(ZONE).toInstant());
        notificationService.checkDueEntries();
        assertThat(call(get("/api/plants/" + plantId), null).path("wateringStatus").asString()).isEqualTo("DUE");
        assertThat(call(get("/api/notifications"), null).size()).isZero();
    }

    // ------------------------------------------------------------------ F05

    @Test
    void healthChecksAreStoredComparedAndCanBeResearched() throws Exception {
        long plantId = addPlant("Monstera deliciosa", "Swiss cheese plant");
        JsonNode first = json(mvc.perform(as(multipart("/api/plants/" + plantId + "/health")
                .param("description", "yellow leaves"), user)).andExpect(status().isOk()));
        assertThat(first.at("/record/healthScore").intValue()).isEqualTo(4);
        assertThat(first.at("/record/trend").asString()).isEqualTo("FIRST_CHECK");

        JsonNode second = json(mvc.perform(as(multipart("/api/plants/" + plantId + "/health")
                .param("description", "new leaves look green"), user)).andExpect(status().isOk()));
        assertThat(second.at("/record/trend").asString()).isEqualTo("IMPROVING");

        long recordId = second.at("/record/id").longValue();
        JsonNode research = call(post("/api/plants/health/" + recordId + "/research"), null);
        assertThat(research.path("answer").asString()).contains("root rot");
        assertThat(research.at("/sources/0/title").asString()).isEqualTo("Monstera deliciosa");

        call(post("/api/plants/health/" + recordId + "/save-note"), null);
        assertThat(call(get("/api/plants/" + plantId + "/notes"), null).get(0).path("kind").asString()).isEqualTo("HEALTH");
        assertThat(call(get("/api/plants/" + plantId + "/health"), null).size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ F06 / F07

    @Test
    void recommendationsRefreshFromOwnedPlantsAndRememberDislikes() throws Exception {
        addPlant("Monstera deliciosa", "Swiss cheese plant");
        JsonNode list = call(get("/api/recommendations").param("sort", "NAME"), null);
        assertThat(list.path("refreshed").booleanValue()).isTrue();
        assertThat(list.path("items").findValuesAsString("commonName")).containsExactly("Spider plant", "ZZ plant");
        assertThat(list.at("/items/0/verified").booleanValue()).isTrue();

        long zz = list.at("/items/1/id").longValue();
        call(delete("/api/recommendations/" + zz), null);
        assertThat(call(get("/api/recommendations/preferences"), null).findValuesAsString("subject"))
                .anyMatch(s -> s.startsWith("ZZ plant"));
        assertThat(call(get("/api/recommendations"), null).path("items").size()).isEqualTo(1);
    }

    @Test
    void constraintRecommendationsAreFilteredAndJoinTheListWhenKept() throws Exception {
        JsonNode suggestions = call(post("/api/recommendations/request"), "{\"constraints\":\"easy and safe for my cat\"}");
        // the ZZ plant is not pet safe, so the deterministic filter drops it
        assertThat(suggestions.findValuesAsString("commonName")).containsExactly("Spider plant");
        call(post("/api/recommendations/" + suggestions.get(0).path("id").longValue() + "/accept"), null);
        assertThat(call(get("/api/recommendations"), null).path("items").findValuesAsString("commonName"))
                .contains("Spider plant");
    }

    // ------------------------------------------------------------------ F09 / F10

    @Test
    void roomBuilderListsPlantsAtAnyLevelAndWishlistGetsAFitVerdict() throws Exception {
        long home = call(post("/api/spaces"), "{\"name\":\"Home\",\"light\":\"LOW\"}").path("id").longValue();
        long sill = call(post("/api/spaces"), "{\"name\":\"Sill\",\"parentId\":" + home + ",\"spot\":true}").path("id").longValue();
        long plantId = addPlant("Monstera deliciosa", "Swiss cheese plant");
        call(post("/api/spaces/" + sill + "/plants/" + plantId), null);

        assertThat(call(get("/api/spaces/" + home + "/plants"), null).findValuesAsString("location")).containsExactly("Home > Sill");
        mvc.perform(as(post("/api/spaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Pot\",\"parentId\":" + sill + "}"), user)).andExpect(status().isBadRequest());

        JsonNode wish = call(post("/api/desired"), "{\"scientificName\":\"Ficus lyrata\",\"commonName\":\"Fiddle-leaf fig\",\"spaceId\":" + home + "}");
        assertThat(wish.path("fitVerdict").asString()).isEqualTo("POOR");
        JsonNode bought = call(post("/api/desired/" + wish.path("id").longValue() + "/buy"), null);
        assertThat(bought.path("location").asString()).isEqualTo("Home > General area");
        assertThat(call(get("/api/desired"), null).size()).isZero();
    }

    // ------------------------------------------------------------------ F11 / F12

    @Test
    void friendsSeeNamesOnlyWhenAllowedAndShareLinksWorkWithoutAnAccount() throws Exception {
        long plantId = addPlant("Monstera deliciosa", "Swiss cheese plant");
        String friend = newUser("Friend");
        call(post("/api/friends"), "{\"friendId\":" + friend + "}");

        mvc.perform(as(get("/api/friends/" + user + "/plants"), friend)).andExpect(status().isForbidden());
        call(put("/api/friends/" + friend + "/can-view"), "{\"allowed\":true}");
        mvc.perform(as(get("/api/friends/" + user + "/plants"), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Swiss cheese plant"))
                .andExpect(jsonPath("$[0].care").doesNotExist());
        mvc.perform(as(get("/api/plants/" + plantId), friend)).andExpect(status().isForbidden());

        String token = call(post("/api/share"), "{\"plantIds\":[" + plantId + "]}").path("token").asString();
        mvc.perform(get("/api/share/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.plants[0].wateringFrequency").value("every 7-10 days"))
                .andExpect(jsonPath("$.plants[0].nextWaterings", Matchers.not(Matchers.empty())));
        mvc.perform(get("/api/share/not-a-real-token")).andExpect(status().isNotFound());
        CLOCK.advance(Duration.ofDays(31));
        mvc.perform(get("/api/share/" + token)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ CLI (chat box)

    @Test
    void chatAgentInterpretsTheMessageAndRunsTheMatchingTools() throws Exception {
        JsonNode added = chat("I bought a snake plant");
        assertThat(added.path("intent").asString()).isEqualTo("ADD_OWNED");
        assertThat(added.path("toolsUsed").valueStream().map(JsonNode::asString).toList())
                .contains("LOOKUP_PLANT_INFO", "PLANT_COLLECTION", "CALENDAR");
        assertThat(added.path("text").asString()).contains("Added **Snake plant**");
        assertThat(added.path("actions").findValuesAsString("type")).contains("NOTIFY");

        JsonNode notify = call(post("/api/chat/action"), "{\"type\":\"NOTIFY\",\"payload\":" + added.at("/actions/0/payload") + "}");
        assertThat(notify.path("text").asString()).contains("Reminders are on");

        JsonNode watered = chat("I watered the snake plant");
        assertThat(watered.path("intent").asString()).isEqualTo("MARK_WATERED");
        assertThat(watered.path("toolsUsed").get(0).asString()).isEqualTo("CALENDAR");

        JsonNode preference = chat("tell me something, I have a cat");
        assertThat(preference.path("intent").asString()).isEqualTo("GENERAL");
        assertThat(call(get("/api/recommendations/preferences"), null).findValuesAsString("subject")).contains("has a cat");

        assertThat(call(get("/api/chat/history"), null).size()).isEqualTo(7);
    }

    @Test
    void chatAgentUpdatesCareNotesAndSavesFreeTextNotes() throws Exception {
        chat("I bought a snake plant");
        long plantId = call(get("/api/plants"), null).get(0).path("id").asLong();
        String before = call(get("/api/plants"), null).get(0).at("/care/careLevel").asString();

        JsonNode updated = chat("update the care notes for my snake plant: water every 10 days, bright indirect light or part shade, toxic to pets");
        assertThat(updated.path("intent").asString()).isEqualTo("UPDATE_CARE_NOTES");
        assertThat(updated.path("text").asString()).contains("Watering frequency: every 10 days", "Poisonous to pets: true");
        JsonNode plant = call(get("/api/plants"), null).get(0);
        assertThat(plant.at("/care/wateringFrequency").asString()).isEqualTo("every 10 days");
        assertThat(plant.at("/care/sunlight").valueStream().map(JsonNode::asString).toList())
                .containsExactly("bright indirect", "part shade");
        assertThat(plant.at("/care/poisonousToPets").asBoolean()).isTrue();
        assertThat(plant.at("/care/careLevel").asString()).isEqualTo(before); // untouched fields are kept
        assertThat(plant.path("wateringIntervalDays").asInt()).isEqualTo(10); // the calendar follows the new frequency

        JsonNode noted = chat("add a note to my snake plant that I repotted it today");
        assertThat(noted.path("intent").asString()).isEqualTo("ADD_NOTE");
        assertThat(call(get("/api/plants/" + plantId + "/notes"), null).findValuesAsString("text"))
                .anyMatch(t -> t.contains("repotted it today"));
    }

    private JsonNode chat(String message) throws Exception {
        return json(mvc.perform(as(multipart("/api/chat").param("message", message), user)).andExpect(status().isOk()));
    }
}
