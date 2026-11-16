package com.plantorganizer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end check of F01 through the real Spring wiring and the real
 * adapters: REST endpoint -> AgentController -> PlantAgent -> tools ->
 * PlantNetAdapter / PlantInfoService / PerenualAdapter / CohereAdapter.
 * The three external APIs are replaced by a local stub HTTP server that
 * mimics their request/response formats, so no real keys or quota are used.
 */
@SpringBootTest
class PlantIdentifierFlowIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpServer STUB = startStub();
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    record RecordedRequest(String method, String path, String query, String contentType, String body) {
    }

    @DynamicPropertySource
    static void pointAdaptersAtStub(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("plantorganizer.plantnet.base-url", () -> base);
        registry.add("plantorganizer.perenual.base-url", () -> base);
        registry.add("plantorganizer.cohere.base-url", () -> base);
        registry.add("plantorganizer.plantnet.api-key", () -> "test-plantnet-key");
        registry.add("plantorganizer.perenual.api-key", () -> "test-perenual-key");
        registry.add("plantorganizer.cohere.api-key", () -> "test-cohere-key");
        registry.add("plantorganizer.websearch.base-url", () -> base);
        registry.add("plantorganizer.scheduling.enabled", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:f01test;MODE=PostgreSQL");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        REQUESTS.clear();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Test
    void uploadedPhotoIsResizedSentToPlantNetAndTop3Returned() throws Exception {
        MockMultipartFile photo = new MockMultipartFile("photos", "monstera.png", "image/png", pngBytes(3000, 2000));

        mvc.perform(multipart("/api/identify").file(photo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].scientificName").value("Monstera deliciosa"))
                .andExpect(jsonPath("$.candidates[0].commonNames[0]").value("Swiss cheese plant"))
                .andExpect(jsonPath("$.candidates[0].score").value(0.91))
                .andExpect(jsonPath("$.candidates[0].referenceImageUrl").value("https://img.example/monstera-m.jpg"))
                .andExpect(jsonPath("$.photosUsed").value(1))
                .andExpect(jsonPath("$.remainingRequests").value(498));

        RecordedRequest sent = REQUESTS.get(0);
        assertThat(sent.path()).isEqualTo("/v2/identify/all");
        assertThat(sent.query()).contains("api-key=test-plantnet-key").contains("nb-results=3");
        assertThat(sent.contentType()).startsWith("multipart/form-data");
        assertThat(sent.body()).contains("name=\"images\"; filename=\"monstera.jpg\"")
                .contains("Content-Type: image/jpeg")
                .contains("name=\"organs\"");
    }

    @Test
    void noRecognisedPlantReturnsEmptyCandidateList() throws Exception {
        MockMultipartFile photo = new MockMultipartFile("photos", "wall.png", "image/png", pngBytes(10, 10));

        mvc.perform(multipart("/api/identify").file(photo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void confirmedPlantUsesPerenualAndAiExplanation() throws Exception {
        mvc.perform(post("/api/identify/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scientificName\":\"Monstera deliciosa\",\"commonName\":\"Swiss cheese plant\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.source").value("PERENUAL"))
                .andExpect(jsonPath("$.profile.wateringFrequency").value("every 7-10 days"))
                .andExpect(jsonPath("$.profile.sunlight[0]").value("part shade"))
                .andExpect(jsonPath("$.profile.poisonousToPets").value(true))
                .andExpect(jsonPath("$.aiExplanation").value(true))
                .andExpect(jsonPath("$.explanation").value("Water your Monstera when the top soil is dry."));

        assertThat(REQUESTS).anyMatch(r -> r.path().equals("/v2/chat")
                && r.body().contains("every 7-10 days") && r.body().contains("command-a-03-2025"));
    }

    @Test
    void perenualPlanLimitFallsBackToCohereCareTemplate() throws Exception {
        mvc.perform(post("/api/identify/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scientificName\":\"Rhaphidophora tetrasperma\",\"commonName\":\"Mini monstera\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.source").value("AI_GENERATED"))
                .andExpect(jsonPath("$.profile.watering").value("Average"))
                .andExpect(jsonPath("$.notes[0]").value(org.hamcrest.Matchers.containsString("free plan")));

        long detailCalls = REQUESTS.stream().filter(r -> r.path().startsWith("/api/v2/species/details")).count();
        assertThat(detailCalls).as("non-retryable plan limit is not retried").isEqualTo(1);
        assertThat(REQUESTS).anyMatch(r -> r.path().equals("/v2/chat") && r.body().contains("json_object"));
    }

    @Test
    void nonImageUploadIsRejectedWithFriendlyMessage() throws Exception {
        MockMultipartFile text = new MockMultipartFile("photos", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/identify").file(text))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("JPEG or PNG")));
    }

    @Test
    void confirmWithoutScientificNameIsRejected() throws Exception {
        mvc.perform(post("/api/identify/confirm").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- stub server

    private static byte[] pngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", PlantIdentifierFlowIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        REQUESTS.add(new RecordedRequest(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders().getFirst("Content-Type"), body));

        Map.Entry<Integer, String> response = route(path, query == null ? "" : query, body);
        byte[] bytes = response.getValue().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.getKey(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final Map<String, String> PERENUAL_SEARCH = new ConcurrentHashMap<>(Map.of(
            "Monstera", "{\"data\":[{\"id\":1,\"common_name\":\"Swiss cheese plant\",\"scientific_name\":[\"Monstera deliciosa\"]}]}",
            "Rhaphidophora", "{\"data\":[{\"id\":9999,\"common_name\":\"Mini monstera\",\"scientific_name\":[\"Rhaphidophora tetrasperma\"]}]}"));

    private static Map.Entry<Integer, String> route(String path, String query, String body) {
        if (path.equals("/v2/identify/all")) {
            // A tiny 10x10 photo stands in for "no plant in this picture" -> Pl@ntNet's 404.
            if (body.length() < 2000) {
                return Map.entry(404, "{\"statusCode\":404,\"error\":\"Not Found\",\"message\":\"Species not found\"}");
            }
            return Map.entry(200, """
                    {"bestMatch":"Monstera deliciosa Liebm.","remainingIdentificationRequests":498,
                     "results":[
                      {"score":0.91,"species":{"scientificNameWithoutAuthor":"Monstera deliciosa","scientificNameAuthorship":"Liebm.",
                        "genus":{"scientificNameWithoutAuthor":"Monstera"},"family":{"scientificNameWithoutAuthor":"Araceae"},
                        "commonNames":["Swiss cheese plant","Ceriman"]},
                       "images":[{"organ":"leaf","url":{"o":"https://img.example/monstera-o.jpg","m":"https://img.example/monstera-m.jpg","s":"https://img.example/monstera-s.jpg"}}]},
                      {"score":0.05,"species":{"scientificNameWithoutAuthor":"Rhaphidophora tetrasperma","commonNames":["Mini monstera"],
                        "family":{"scientificNameWithoutAuthor":"Araceae"}}},
                      {"score":0.02,"species":{"scientificNameWithoutAuthor":"Thaumatophyllum bipinnatifidum","commonNames":[]}}
                     ]}
                    """);
        }
        if (path.equals("/api/v2/species-list")) {
            return PERENUAL_SEARCH.entrySet().stream()
                    .filter(e -> query.contains(e.getKey()))
                    .map(e -> Map.entry(200, e.getValue()))
                    .findFirst()
                    .orElse(Map.entry(200, "{\"data\":[]}"));
        }
        if (path.equals("/api/v2/species/details/1")) {
            return Map.entry(200, """
                    {"id":1,"common_name":"Swiss cheese plant","scientific_name":["Monstera deliciosa"],"family":"Araceae",
                     "watering":"Average","watering_general_benchmark":{"value":"\\"7-10\\"","unit":"days"},
                     "sunlight":["part shade"],"care_level":"Medium","growth_rate":"High","indoor":true,
                     "poisonous_to_pets":1,"poisonous_to_humans":1,"hardiness":{"min":"10","max":"12"},
                     "description":"A tropical climber.","default_image":{"medium_url":"https://img.example/p.jpg"}}
                    """);
        }
        if (path.equals("/api/v2/species/details/9999")) {
            return Map.entry(200, "{\"id\":9999,\"watering\":\"Upgrade Plans To Premium/Supreme - https://perenual.com/subscription-api-pricing. I'm sorry\"}");
        }
        if (path.equals("/v2/chat")) {
            String text;
            if (body.contains("json_object")) {
                ObjectNode care = JSON.createObjectNode();
                care.put("knownSpecies", true);
                care.put("scientificName", "Rhaphidophora tetrasperma");
                care.put("watering", "Average");
                care.put("wateringFrequency", "every 7 days");
                care.putArray("sunlight").add("bright indirect light");
                care.put("careLevel", "Easy");
                care.put("description", "A fast-growing climbing aroid.");
                text = JSON.writeValueAsString(care);
            } else {
                text = "Water your Monstera when the top soil is dry.";
            }
            ObjectNode reply = JSON.createObjectNode();
            reply.putObject("message").putArray("content").addObject().put("type", "text").put("text", text);
            return Map.entry(200, JSON.writeValueAsString(reply));
        }
        return Map.entry(500, "{\"error\":\"unexpected path " + path + "\"}");
    }
}
