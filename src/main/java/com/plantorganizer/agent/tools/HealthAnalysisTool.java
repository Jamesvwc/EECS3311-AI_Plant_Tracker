package com.plantorganizer.agent.tools;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.health.HealthReport;
import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.ImageProcessor;
import com.plantorganizer.identification.PlantCandidate;
import com.plantorganizer.identification.PlantIdentifier;
import com.plantorganizer.identification.PlantImage;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plantinfo.PlantInfoService;
import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.plants.HealthRecord;
import com.plantorganizer.plants.HealthStatus;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.plants.PlantPhoto;
import com.plantorganizer.plants.PlantRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concrete tool (Stage 1 HealthAnalysisTool, F05): orchestrates a health check.
 *
 *   1. loads the plant, its earlier health records and its last photo;
 *   2. if a photo was given (and the check was not started from the plant's
 *      own page), asks Pl@ntNet whether the photo really shows that species;
 *   3. gets the care profile (PlantInfoService, cached);
 *   4. asks the LLM to assess the plant, looking at the new and previous
 *      photos when the vision model is available;
 *   5. compares with the previous record (HealthRecord.compareTo) and stores
 *      the photo and the new record.
 */
public class HealthAnalysisTool implements Tool {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static final String INSTRUCTIONS = """
            You are a plant-health assistant. Assess the owner's plant from the photo(s) and/or their
            description, using its care profile and earlier health checks. The first image (if any) is
            today's photo; a second image is the previous check's photo, so compare them.
            healthScore: 1 (dying) to 10 (thriving). status: HEALTHY, MINOR_ISSUES, NEEDS_ATTENTION or
            CRITICAL. issues: what you observe. likelyCauses: the most likely causes, most likely first,
            tied to the care profile (e.g. watering more often than the profile suggests).
            careSteps: 3-6 concrete things to do, in order. comparison: whether it looks better or worse
            than the previous check and why ("" if there is no previous check). summary: two sentences
            that also teach the owner what sign to watch for next time. Do not invent details you
            cannot see; say when a better photo would help.
            """;
    private static final String SCHEMA = """
            {"type": "object", "properties": {
               "healthScore": {"type": "integer"}, "status": {"type": "string"}, "summary": {"type": "string"},
               "issues": {"type": "array", "items": {"type": "string"}},
               "likelyCauses": {"type": "array", "items": {"type": "string"}},
               "careSteps": {"type": "array", "items": {"type": "string"}},
               "comparison": {"type": "string"}},
             "required": ["healthScore", "status", "summary", "issues", "likelyCauses", "careSteps", "comparison"]}
            """;

    private final PlantRepository plantRepository;
    private final ImageProcessor imageProcessor;
    private final PlantIdentifier plantIdentifier;
    private final PlantInfoService plantInfoService;
    private final LLMClient llm;
    private final JsonNode schema;
    private final Clock clock;

    HealthAnalysisTool(PlantRepository plantRepository, ImageProcessor imageProcessor, PlantIdentifier plantIdentifier,
                       PlantInfoService plantInfoService, LLMClient llm, JsonMapper jsonMapper, Clock clock) {
        this.plantRepository = plantRepository;
        this.imageProcessor = imageProcessor;
        this.plantIdentifier = plantIdentifier;
        this.plantInfoService = plantInfoService;
        this.llm = llm;
        this.schema = jsonMapper.readTree(SCHEMA);
        this.clock = clock;
    }

    @Override
    public ToolType type() {
        return ToolType.ANALYZE_HEALTH;
    }

    @Override
    public HealthReport execute(Map<String, Object> args) {
        long userId = ToolArgs.userId(args);
        OwnedPlant plant = plantRepository.findOwned(userId, ToolArgs.required(args, "plantId", Long.class));
        PlantImage photo = ToolArgs.optional(args, "photo", PlantImage.class);
        String description = ToolArgs.optional(args, "description", String.class);
        boolean confirmSpecies = Boolean.TRUE.equals(ToolArgs.optional(args, "confirmSpecies", Boolean.class));
        if (photo == null && (description == null || description.isBlank())) {
            throw new InvalidInputException("Describe what you're seeing (e.g. \"yellow lower leaves\") or add a photo.");
        }
        if (!llm.isConfigured()) {
            throw new ExternalServiceException("Cohere",
                    "API key is not configured, and the health check needs the AI. Add COHERE_API_KEY to .env.", false);
        }
        List<String> notes = new ArrayList<>();
        PlantImage prepared = photo != null ? imageProcessor.resize(photo) : null;
        String speciesWarning = prepared != null && confirmSpecies ? checkSpecies(plant, prepared, notes) : null;
        PlantCareProfile profile = careProfile(plant, notes);
        List<HealthRecord> history = plantRepository.findHealthRecords(plant.getId());
        HealthRecord previous = history.isEmpty() ? null : history.get(0);

        String prompt = describe(plant, profile, description, history);
        JsonNode assessment;
        if (prepared != null && llm.supportsVision()) {
            List<PlantImage> images = new ArrayList<>(List.of(prepared));
            plantRepository.latestPhoto(plant.getId()).ifPresent(p ->
                    images.add(new PlantImage(p.getData(), "previous.jpg", p.getContentType())));
            assessment = llm.generateStructuredWithImages(INSTRUCTIONS, prompt, images, schema);
        } else {
            if (prepared != null) {
                notes.add("The photo was saved to the plant's history, but the AI could only use your description.");
            }
            assessment = llm.generateStructured(INSTRUCTIONS, prompt, schema);
        }

        Long photoId = prepared != null ? plantRepository.savePhoto(plant.getId(), prepared).getId() : null;
        List<String> issues = new ArrayList<>(JsonValues.textList(assessment.path("issues")));
        JsonValues.textList(assessment.path("likelyCauses")).forEach(c -> issues.add("Likely cause: " + c));
        HealthRecord record = new HealthRecord(plant.getId(), photoId, LocalDateTime.now(clock), description,
                assessment.path("healthScore").isNumber() ? assessment.path("healthScore").intValue() : 5,
                status(JsonValues.text(assessment.path("status"))), JsonValues.text(assessment.path("summary")),
                String.join("\n", issues), String.join("\n", JsonValues.textList(assessment.path("careSteps"))),
                JsonValues.text(assessment.path("comparison")), speciesWarning);
        record.setTrend(record.compareTo(previous));
        record = plantRepository.saveHealthRecord(record);
        return new HealthReport(plant.getId(), plant.getDisplayName(), record, photoId, speciesWarning, notes);
    }

    /** Stage 1: "agent confirms the species (unless called within a plant's notes)". */
    private String checkSpecies(OwnedPlant plant, PlantImage photo, List<String> notes) {
        try {
            IdentificationResult result = plantIdentifier.identify(List.of(photo), 3);
            String wanted = PlantQuery.binomial(plant.getScientificName());
            boolean matches = result.candidates().stream()
                    .anyMatch(c -> PlantQuery.binomial(c.scientificName()).equals(wanted));
            if (matches || result.noMatch()) {
                notes.add(matches ? "Pl@ntNet confirmed the photo shows " + plant.getScientificName() + "."
                        : "Pl@ntNet could not recognise the species in this photo.");
                return null;
            }
            PlantCandidate top = result.candidates().get(0);
            return "This photo looks more like " + top.scientificName() + " (" + Math.round(top.score() * 100)
                    + "% confidence) than " + plant.getScientificName() + ". If it's a different plant, choose that "
                    + "plant and try again.";
        } catch (ExternalServiceException e) {
            notes.add("Species check skipped: " + e.getMessage());
            return null;
        }
    }

    private PlantCareProfile careProfile(OwnedPlant plant, List<String> notes) {
        try {
            return plantInfoService.lookup(PlantQuery.of(plant.getScientificName(), plant.getCommonName())).profile();
        } catch (ExternalServiceException e) {
            notes.add("Used the plant's saved care notes (" + e.getMessage() + ").");
            return plant.getCare().toProfile(plant.getScientificName(), plant.getCommonName());
        }
    }

    private String describe(OwnedPlant plant, PlantCareProfile p, String description, List<HealthRecord> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plant: ").append(plant.getDisplayName()).append(" (").append(plant.getScientificName()).append(")\n");
        sb.append("Care profile: watering ").append(orUnknown(p.watering())).append(" ")
          .append(orUnknown(p.wateringFrequency())).append("; light ")
          .append(p.sunlight().isEmpty() ? "unknown" : String.join(", ", p.sunlight()))
          .append("; care level ").append(orUnknown(p.careLevel())).append('\n');
        if (plant.getLastWateredAt() != null) {
            sb.append("Last watered: ").append(plant.getLastWateredAt().format(DATE)).append('\n');
        }
        if (plant.getSpot() != null) {
            sb.append("Location: ").append(plant.getSpot().path()).append(" (")
              .append(plant.getSpot().effectiveLight() != null ? plant.getSpot().effectiveLight().getLabel() : "light unknown")
              .append(")\n");
        }
        sb.append("Owner's description: ").append(description == null || description.isBlank() ? "(none, photo only)" : description).append('\n');
        if (history.isEmpty()) {
            sb.append("Previous checks: none.\n");
        } else {
            sb.append("Previous checks (newest first):\n");
            history.stream().limit(3).forEach(h -> sb.append("- ").append(h.getCreatedAt().format(DATE)).append(": score ")
                    .append(h.getHealthScore()).append(", ").append(h.getStatus()).append(". ")
                    .append(orUnknown(h.getSummary())).append('\n'));
        }
        return sb.toString();
    }

    private static HealthStatus status(String value) {
        try {
            return HealthStatus.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return HealthStatus.MINOR_ISSUES;
        }
    }

    private static String orUnknown(String s) {
        return s == null ? "unknown" : s;
    }
}
