package com.plantorganizer.llm;

import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.plantorganizer.common.ApiKeys;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonExtractor;
import com.plantorganizer.identification.PlantImage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter (Adapter pattern) from Cohere's Chat API v2 to {@link LLMClient}.
 *
 * Request:  POST https://api.cohere.com/v2/chat with "Authorization: Bearer KEY"
 *           and {"model", "messages": [{role, content}], "response_format"?}.
 * Response: {"message": {"content": [{"type": "text", "text": "..."}]}}.
 * For structured output, response_format {"type": "json_object",
 * "json_schema": {...}} makes Cohere return JSON matching the schema.
 * Photos go to the vision model (command-a-vision-07-2025) as content parts
 * {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}};
 * JSON mode is not documented for images, so that reply is parsed leniently.
 */
@Component
public class CohereAdapter implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(CohereAdapter.class);
    static final String SERVICE = "Cohere";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String model;
    private final String visionModel;

    public CohereAdapter(ClientHttpRequestFactory requestFactory,
                         JsonMapper jsonMapper,
                         @Value("${plantorganizer.cohere.base-url:https://api.cohere.com}") String baseUrl,
                         @Value("${plantorganizer.cohere.api-key}") String apiKey,
                         @Value("${plantorganizer.cohere.model:command-a-03-2025}") String model,
                         @Value("${plantorganizer.cohere.vision-model:command-a-vision-07-2025}") String visionModel) {
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.visionModel = visionModel;
    }

    @Override
    public boolean isConfigured() {
        return ApiKeys.isConfigured(apiKey);
    }

    @Override
    public String generate(String instructions, String prompt) {
        ObjectNode request = baseRequest(instructions, prompt, 0.4);
        return chat(request);
    }

    @Override
    public JsonNode generateStructured(String instructions, String prompt, JsonNode jsonSchema) {
        ObjectNode request = baseRequest(instructions, prompt, 0.2);
        ObjectNode format = request.putObject("response_format");
        format.put("type", "json_object");
        format.set("json_schema", jsonSchema);

        String text = chat(request);
        try {
            return jsonMapper.readTree(text);
        } catch (JacksonException e) {
            throw new ExternalServiceException(SERVICE, "returned text that is not valid JSON.", true, e);
        }
    }

    @Override
    public boolean supportsVision() {
        return isConfigured() && visionModel != null && !visionModel.isBlank();
    }

    @Override
    public JsonNode generateStructuredWithImages(String instructions, String prompt, List<PlantImage> images,
                                                 JsonNode jsonSchema) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", visionModel);
        request.put("temperature", 0.2);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", instructions);
        ObjectNode user = messages.addObject().put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "text").put("text", prompt
                + "\n\nReply with ONLY a JSON object (no other text) that follows this JSON Schema:\n"
                + jsonSchema.toString());
        for (PlantImage image : images) {
            String type = image.contentType() != null ? image.contentType() : "image/jpeg";
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:" + type + ";base64," + Base64.getEncoder().encodeToString(image.data()));
        }
        return JsonExtractor.firstObject(jsonMapper, chat(request), SERVICE);
    }

    private ObjectNode baseRequest(String instructions, String prompt, double temperature) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", model);
        request.put("temperature", temperature);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", instructions);
        messages.addObject().put("role", "user").put("content", prompt);
        return request;
    }

    /** Sends the request and returns the concatenated text parts of the reply. */
    private String chat(ObjectNode request) {
        if (!isConfigured()) {
            throw new ExternalServiceException(SERVICE,
                    "API key is not configured. Add COHERE_API_KEY to your .env file.", false);
        }
        try {
            String body = restClient.post()
                    .uri("/v2/chat")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            return extractText(jsonMapper.readTree(body));

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Cohere returned HTTP {}: {}", status, e.getResponseBodyAsString());
            String reason = switch (status) {
                case 401, 403 -> "rejected the API key. Check COHERE_API_KEY in .env.";
                case 429 -> "rate limit reached (trial keys are limited). Try again shortly.";
                default -> "returned HTTP " + status + ".";
            };
            throw new ExternalServiceException(SERVICE, reason, status == 429 || status >= 500, e);
        } catch (ResourceAccessException e) {
            throw new ExternalServiceException(SERVICE, "could not be reached (" + e.getMessage() + ")", true, e);
        }
    }

    /** Package-private for unit tests. */
    static String extractText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("message").path("content")) {
            if ("text".equals(part.path("type").asString())) {
                text.append(part.path("text").asString());
            }
        }
        if (text.isEmpty()) {
            throw new ExternalServiceException(SERVICE, "returned an empty answer.", true);
        }
        return text.toString().trim();
    }
}
