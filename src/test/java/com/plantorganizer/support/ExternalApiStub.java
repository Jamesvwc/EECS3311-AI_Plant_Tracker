package com.plantorganizer.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local HTTP server that imitates Pl@ntNet, Perenual, Cohere and Wikipedia
 * with fixed answers, so integration tests exercise the real adapters without
 * keys, network or quota. Cohere answers are chosen from the JSON schema (or
 * the instructions) in the request, which is how each agent step is told apart.
 */
public final class ExternalApiStub {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** id -> scientific name, common name, details JSON (null = premium only) */
    private static final Map<Integer, String[]> SPECIES = Map.of(
            1, new String[] {"Monstera deliciosa", "Swiss cheese plant",
                    "{\"watering\":\"Average\",\"watering_general_benchmark\":{\"value\":\"7-10\",\"unit\":\"days\"},\"sunlight\":[\"part shade\"],\"poisonous_to_pets\":1}"},
            2, new String[] {"Dracaena trifasciata", "Snake plant",
                    "{\"watering\":\"Minimum\",\"watering_general_benchmark\":{\"value\":\"14-21\",\"unit\":\"days\"},\"sunlight\":[\"full shade\"]}"},
            3, new String[] {"Epipremnum aureum", "Golden pothos",
                    "{\"watering\":\"Average\",\"watering_general_benchmark\":{\"value\":\"7-10\",\"unit\":\"days\"},\"sunlight\":[\"part shade\"]}"},
            4, new String[] {"Ficus lyrata", "Fiddle-leaf fig",
                    "{\"watering\":\"Average\",\"sunlight\":[\"full sun\"],\"care_level\":\"High\"}"},
            5, new String[] {"Chlorophytum comosum", "Spider plant", null},
            6, new String[] {"Zamioculcas zamiifolia", "ZZ plant", null});

    private final HttpServer server;

    public ExternalApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if (path.equals("/v2/identify/all")) {
            response = """
                    {"remainingIdentificationRequests": 400, "results": [{"score": 0.9, "species": {
                      "scientificNameWithoutAuthor": "Monstera deliciosa", "commonNames": ["Swiss cheese plant"]}}]}
                    """;
        } else if (path.equals("/api/v2/species-list")) {
            String q = query.replaceAll(".*q=", "").toLowerCase();
            StringBuilder hits = new StringBuilder();
            SPECIES.forEach((id, s) -> {
                if (s[0].toLowerCase().contains(q) || s[1].toLowerCase().contains(q)) {
                    hits.append(hits.isEmpty() ? "" : ",").append("{\"id\":").append(id)
                        .append(",\"scientific_name\":[\"").append(s[0]).append("\"],\"common_name\":\"").append(s[1]).append("\"}");
                }
            });
            response = "{\"data\":[" + hits + "]}";
        } else if (path.startsWith("/api/v2/species/details/")) {
            String[] s = SPECIES.get(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)));
            response = s[2] == null ? "{\"watering\":\"Upgrade Plans To Premium\"}"
                    : s[2].replaceFirst("\\{", "{\"scientific_name\":[\"" + s[0] + "\"],\"common_name\":\"" + s[1] + "\",");
        } else if (path.equals("/w/rest.php/v1/search/page")) {
            response = "{\"pages\":[{\"key\":\"Monstera_deliciosa\",\"title\":\"Monstera deliciosa\",\"excerpt\":\"x\"}]}";
        } else if (path.startsWith("/api/rest_v1/page/summary/")) {
            response = "{\"extract\":\"A tropical plant that likes bright indirect light.\"}";
        } else if (path.equals("/v2/chat")) {
            JsonNode request = JSON.readTree(body);
            String problem = schemaProblem(request.path("response_format").path("json_schema"), "");
            if (problem != null) {
                // Same rejection the real Cohere API gives (seen with a real key on the recommendations page).
                send(ex, 400, JSON.writeValueAsString(Map.of("message",
                        "invalid request: response_format validation: invalid 'json_schema' provided: " + problem)));
                return;
            }
            response = chat(request);
        } else {
            send(ex, 404, "{}");
            return;
        }
        send(ex, 200, response);
    }

    private static String chat(JsonNode request) {
        String schema = request.path("response_format").path("json_schema").toString();
        String system = request.path("messages").get(0).path("content").asString();
        JsonNode user = request.path("messages").get(1).path("content");
        String prompt = user.isArray() ? user.get(0).path("text").asString() : user.asString();
        String text;
        if (schema.contains("knownSpecies")) {
            text = "{\"knownSpecies\":true,\"scientificName\":\"Chlorophytum comosum\",\"watering\":\"Average\","
                    + "\"wateringFrequency\":\"every 7 days\",\"sunlight\":[\"bright indirect\"],\"careLevel\":\"Easy\",\"description\":\"Easy.\"}";
        } else if (schema.contains("\"intent\"")) {
            text = intent(prompt.substring(prompt.lastIndexOf("Latest message:")).toLowerCase());
        } else if (schema.contains("\"hardinessZones\"")) {
            text = "{\"watering\":\"\",\"wateringFrequency\":\"every 10 days\",\"sunlight\":\"bright indirect, part shade\","
                    + "\"careLevel\":\"\",\"growthRate\":\"\",\"hardinessZones\":\"\",\"family\":\"\",\"description\":\"\","
                    + "\"indoor\":\"\",\"poisonousToPets\":\"true\",\"poisonousToHumans\":\"\"}";
        } else if (schema.contains("\"candidates\"")) {
            text = prompt.toLowerCase().contains("snake")
                    ? "{\"candidates\":[{\"scientificName\":\"Dracaena trifasciata\",\"commonName\":\"Snake plant\",\"confidence\":0.9}]}"
                    : "{\"candidates\":[{\"scientificName\":\"Monstera deliciosa\",\"commonName\":\"Swiss cheese plant\",\"confidence\":0.8},"
                      + "{\"scientificName\":\"Monstera fakeii\",\"confidence\":0.2}]}";
        } else if (schema.contains("\"recommendations\"")) {
            text = """
                    {"parsedConstraints": {"maxCareLevel": "Easy", "petSafeOnly": true},
                     "recommendations": [
                      {"scientificName": "Chlorophytum comosum", "commonName": "Spider plant", "reason": "Easy and pet safe.",
                       "careLevel": "Easy", "sunlight": "bright", "watering": "Average", "petSafe": true, "recommendedSpace": "Bedroom"},
                      {"scientificName": "Zamioculcas zamiifolia", "commonName": "ZZ plant", "reason": "Very tolerant.",
                       "careLevel": "Easy", "sunlight": "low", "watering": "Minimum", "petSafe": false, "recommendedSpace": "Office"}]}
                    """;
        } else if (schema.contains("healthScore") || prompt.contains("healthScore")) {
            int score = prompt.contains("Previous checks: none") ? 4 : 7;
            text = "{\"healthScore\":" + score + ",\"status\":\"MINOR_ISSUES\",\"summary\":\"Some yellow leaves.\","
                    + "\"issues\":[\"Yellow leaves\"],\"likelyCauses\":[\"Overwatering\"],\"careSteps\":[\"Let it dry out\"],\"comparison\":\"\"}";
        } else if (schema.contains("weeklyWindows")) {
            text = prompt.contains("banana")
                    ? "{\"understood\":false,\"clarificationQuestion\":\"When are you usually home?\",\"summary\":\"\",\"weeklyWindows\":[],\"awayPeriods\":[],\"travelBufferMinutes\":0}"
                    : "{\"understood\":true,\"summary\":\"Evenings after work.\",\"travelBufferMinutes\":30,\"awayPeriods\":[],\"weeklyWindows\":["
                      + String.join(",", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY").stream()
                              .map(d -> "{\"day\":\"" + d + "\",\"start\":\"17:00\",\"end\":\"21:00\"}").toList()) + "]}";
        } else if (schema.contains("\"verdict\"")) {
            text = "{\"verdict\":\"POOR\",\"explanation\":\"Too dark for a plant that wants full sun.\"}";
        } else if (schema.contains("\"found\"")) {
            text = "{\"answer\":\"Water every 7-10 days [1].\",\"found\":true,\"watering\":\"Average\",\"wateringFrequency\":\"every 7-10 days\"}";
        } else if (system.contains("research assistant")) {
            text = "Also check for root rot [1].";
        } else {
            text = "Agent explanation.";
        }
        if (user.isArray()) {
            text = "```json\n" + text + "\n```"; // vision answers are free text around the JSON
        }
        return JSON.writeValueAsString(Map.of("message", Map.of("content", List.of(Map.of("type", "text", "text", text)))));
    }

    /**
     * Cohere's structured-output rules (docs.cohere.com/docs/structured-outputs): the top level must be
     * an object, every object (nested ones too) needs at least one "required" field, and some keywords
     * (minimum, maximum, minLength, maxLength, minItems, maxItems, uniqueItems, allOf, oneOf, not) are
     * not supported. Returns the first violation, or null when the schema is acceptable.
     */
    static String schemaProblem(JsonNode schema, String path) {
        if (schema.isMissingNode()) {
            return null;
        }
        if (path.isEmpty() && !"object".equals(schema.path("type").asString())) {
            return "the top-level type must be `object`";
        }
        for (String unsupported : List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
                "uniqueItems", "allOf", "oneOf", "not")) {
            if (schema.has(unsupported)) {
                return "error at '" + path + "': `" + unsupported + "` is not supported";
            }
        }
        if ("object".equals(schema.path("type").asString())) {
            if (!schema.path("required").isArray() || schema.path("required").isEmpty()) {
                return "error at '" + path + "': `object` type must have at least one required field";
            }
            for (var field : schema.path("properties").properties()) {
                String problem = schemaProblem(field.getValue(), (path.isEmpty() ? "" : path + ".") + "properties." + field.getKey());
                if (problem != null) {
                    return problem;
                }
            }
        }
        if (schema.has("items")) {
            return schemaProblem(schema.path("items"), path + ".items");
        }
        return null;
    }

    private static String intent(String m) {
        String type = m.contains("care notes") ? "UPDATE_CARE_NOTES" : m.contains("a note") ? "ADD_NOTE"
                : m.contains("watered") ? "MARK_WATERED" : m.contains("needs water") ? "WATERING_STATUS"
                : m.contains("bought") ? "ADD_OWNED" : m.contains("yellow") ? "HEALTH_CHECK"
                : m.contains("recommend") ? "RECOMMEND" : m.contains("free") ? "UPDATE_AVAILABILITY" : "GENERAL";
        String plant = m.contains("monstera") ? "monstera" : m.contains("snake") ? "snake plant" : "";
        return "{\"intent\":\"" + type + "\",\"plantName\":\"" + plant + "\",\"spaceName\":\"\",\"details\":\"\",\"preference\":\""
                + (m.contains("cat") ? "has a cat" : "") + "\"}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
