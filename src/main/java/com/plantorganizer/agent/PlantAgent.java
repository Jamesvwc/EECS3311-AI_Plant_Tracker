package com.plantorganizer.agent;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.plantorganizer.agent.tools.ToolManager;
import com.plantorganizer.agent.tools.ToolType;
import com.plantorganizer.common.ExternalServiceException;
import com.plantorganizer.common.JsonValues;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.plantinfo.CareLookup;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plantinfo.PlantQuery;
import com.plantorganizer.plants.DesiredPlant.FitVerdict;
import com.plantorganizer.plants.OwnedPlant;
import com.plantorganizer.schedule.RescheduleResult;
import com.plantorganizer.schedule.ScheduleResult;
import com.plantorganizer.spaces.LightLevel;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.user.UserRepository;
import com.plantorganizer.websearch.ResearchResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI agent. For each request it:
 *   1. asks AgentPlanner for a plan (tool steps whose arguments can depend on
 *      earlier results),
 *   2. runs the steps through ToolManager, tolerating failures of optional steps,
 *   3. interprets the results for the user with the LLM: care and placement
 *      explanations (F01), schedule explanations with data-quality warnings
 *      (F02/F03), wishlist fit verdicts (F09) and answers to questions (CLI).
 * Every LLM-written text has a deterministic fallback, so features degrade
 * instead of failing when the LLM is unavailable.
 */
@Service
public class PlantAgent {

    private static final Logger log = LoggerFactory.getLogger(PlantAgent.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d 'at' HH:mm", Locale.ENGLISH);

    private static final String EXPLAIN_INSTRUCTIONS = """
            You are PlantOrganizer's plant-care assistant. Using ONLY the care profile provided,
            explain to the user, in a friendly and concise way (about 150-250 words):
              - how to water and care for the plant day to day,
              - what light, temperature and physical environment it needs to thrive,
              - where in their home it should be placed: if the user's spaces are listed, recommend the
                best one BY NAME and say why (its light level); otherwise give general placement advice
                and mention they can describe their rooms in the Room Builder,
              - any safety warning (e.g. toxic to pets) if the profile says so.
            Encourage the user to observe the plant and learn its signals rather than follow
            the advice blindly. If a field is missing, say that information was not available
            instead of inventing it. If the profile is marked as AI-generated, remind the user
            to double-check it. If the profile describes a SIMILAR plant rather than the exact
            species, say so clearly at the start and suggest retrying with more or clearer photos.
            Format: short paragraphs; you may use "- " bullet lines and **bold** for key terms.
            Do not use headings or tables.
            """;
    private static final String SCHEDULE_INSTRUCTIONS = """
            You are PlantOrganizer's plant-care assistant. Explain the watering schedule you just made, in
            3-5 friendly sentences: how often and when the next waterings are, and if it was grouped with
            other plants, that this keeps the calendar simple. If a data warning is given, say plainly that
            the information was thin or missing, that the schedule is a best guess, and that the user can
            press "Search for more info" to let you research it, or keep the schedule as is. Remind them to
            check the soil before watering. Use **bold** for the interval. No headings.
            """;
    private static final String RESCHEDULE_INSTRUCTIONS = """
            You are PlantOrganizer's plant-care assistant. Summarise, in 2-4 friendly sentences, how you
            understood the user's availability and how the watering calendar changed. Mention travel or
            trips if any. Tell them they can press "Undo" if anything looks wrong. No headings.
            """;
    private static final String FIT_INSTRUCTIONS = """
            You judge whether a plant suits a specific space in the user's home, from the plant's care
            profile and the space's light level. verdict: GOOD, FAIR or POOR. explanation: 2-4 sentences
            for the user naming the light mismatch or match, and if POOR or FAIR suggest what would help
            (another of their spaces if listed, a grow light, moving closer to a window).
            """;
    private static final String FIT_SCHEMA = """
            {"type": "object", "properties": {"verdict": {"type": "string"}, "explanation": {"type": "string"}},
             "required": ["verdict", "explanation"]}
            """;
    private static final String ANSWER_INSTRUCTIONS = """
            You are PlantOrganizer's plant-care assistant, chatting with the user. Answer their latest
            message helpfully and concisely (under 200 words) using the context: their plants, their spaces
            and any care profile provided. Ground plant facts in the care profile when given; if you are not
            sure, say so. Encourage them to observe their plants and learn from them rather than trust you
            blindly. You can also do things in the app; if relevant, mention they can ask you to identify a
            plant, add it to their plants, check a plant's health, plan watering around their schedule or
            recommend plants. Format: short paragraphs, "- " bullets and **bold** allowed, no headings.
            """;

    private final AgentPlanner planner;
    private final ToolManager toolManager;
    private final LLMClient llm;
    private final UserRepository userRepository;
    private final JsonNode fitSchema;

    public PlantAgent(AgentPlanner planner, ToolManager toolManager, LLMClient llm, UserRepository userRepository,
                      JsonMapper jsonMapper) {
        this.planner = planner;
        this.toolManager = toolManager;
        this.llm = llm;
        this.userRepository = userRepository;
        this.fitSchema = jsonMapper.readTree(FIT_SCHEMA);
    }

    public AgentResponse run(AgentRequest request) {
        StepResults results = execute(planner.createPlan(request));
        List<String> notes = new ArrayList<>(results.notes());
        Object result = switch (request.type()) {
            case EXPLAIN_CONFIRMED_PLANT -> explain(results.get(ToolType.LOOKUP_PLANT_INFO, CareLookup.class),
                    spaces(results));
            case ADD_OWNED_PLANT -> {
                OwnedPlant plant = results.get(ToolType.PLANT_COLLECTION, OwnedPlant.class);
                ScheduleResult schedule = results.get(ToolType.CALENDAR, ScheduleResult.class);
                if (!results.has(ToolType.LOOKUP_PLANT_INFO)) {
                    notes.add("Care information couldn't be found, so this plant's care notes were left empty. "
                            + "You can fill them in on its page or ask the agent for help.");
                }
                // F04: the agent asks about reminders only the first time it makes a schedule (UC03).
                boolean ask = !userRepository.findById(request.userId()).isNotificationsAsked();
                yield new AddPlantResult(plant.getId(), plant.getDisplayName(), SpaceManager.pathOf(plant),
                        schedule.withExplanation(explainSchedule(schedule)).withAskAboutNotifications(ask), notes);
            }
            case GENERATE_SCHEDULE -> {
                ScheduleResult schedule = results.get(ToolType.CALENDAR, ScheduleResult.class);
                yield schedule.withExplanation(explainSchedule(schedule));
            }
            case RESEARCH_WATERING -> {
                ScheduleResult schedule = results.get(ToolType.CALENDAR, ScheduleResult.class);
                ResearchResult research = results.get(ToolType.WEB_SEARCH, ResearchResult.class);
                yield schedule.withExplanation(research.answer() + sources(research) + "\n\n" + explainSchedule(schedule));
            }
            case UPDATE_SCHEDULE -> {
                RescheduleResult reschedule = results.get(ToolType.CALENDAR, RescheduleResult.class);
                yield reschedule.withExplanation(explainReschedule(reschedule));
            }
            case CHECK_DESIRED_FIT -> judgeFit(request, results.get(ToolType.LOOKUP_PLANT_INFO, CareLookup.class));
            case ANSWER_QUESTION -> answer(request, results.get(ToolType.LOOKUP_PLANT_INFO, CareLookup.class),
                    spaces(results));
            default -> results.last();
        };
        return new AgentResponse(result, notes, results.toolsUsed());
    }

    /** Runs the plan step by step; a failing optional step becomes a note instead of an error. */
    StepResults execute(List<PlanStep> plan) {
        StepResults results = new StepResults();
        for (PlanStep step : plan) {
            var args = step.args().apply(results);
            if (args == null) {
                continue;
            }
            try {
                results.put(step.tool(), toolManager.executeTool(step.tool(), args));
            } catch (ExternalServiceException e) {
                if (!step.optional()) {
                    throw e;
                }
                log.info("Optional step {} failed: {}", step.tool(), e.getMessage());
                results.note(e.getMessage());
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static List<String> spaces(StepResults results) {
        List<String> spaces = results.get(ToolType.SPACE, List.class);
        return spaces == null ? List.of() : spaces;
    }

    // ------------------------------------------------------------------ F01 explanation

    /** Combines the care profile with the user's spaces into an explanation. */
    PlantReport explain(CareLookup lookup, List<String> userSpaces) {
        PlantCareProfile profile = lookup.profile();
        List<String> notes = new ArrayList<>(lookup.notes());
        if (llm.isConfigured()) {
            try {
                String explanation = llm.generate(EXPLAIN_INSTRUCTIONS, describeForLlm(profile, userSpaces));
                return new PlantReport(profile, explanation, true, notes);
            } catch (ExternalServiceException e) {
                log.warn("LLM explanation failed, using summary instead: {}", e.getMessage());
                notes.add("AI explanation unavailable (" + e.getMessage() + "); showing a summary instead.");
            }
        } else {
            notes.add("AI explanation unavailable (COHERE_API_KEY not configured); showing a summary instead.");
        }
        return new PlantReport(profile, summarize(profile), false, notes);
    }

    /** The context block sent to the LLM. Package-private for unit tests. */
    static String describeForLlm(PlantCareProfile p, List<String> userSpaces) {
        StringBuilder sb = new StringBuilder("Care profile:\n");
        line(sb, "Scientific name", p.scientificName());
        line(sb, "Common name", p.commonName());
        if (p.similarPlantName() != null) {
            line(sb, "NOTE", "exact species not found; this profile describes the similar plant "
                    + p.similarPlantName());
        }
        line(sb, "Family", p.family());
        line(sb, "Description", p.description());
        line(sb, "Watering", p.watering());
        line(sb, "Watering frequency", p.wateringFrequency());
        line(sb, "Sunlight", p.sunlight().isEmpty() ? null : String.join(", ", p.sunlight()));
        line(sb, "Care level", p.careLevel());
        line(sb, "Growth rate", p.growthRate());
        line(sb, "Indoor plant", yesNo(p.indoor()));
        line(sb, "Poisonous to pets", yesNo(p.poisonousToPets()));
        line(sb, "Poisonous to humans", yesNo(p.poisonousToHumans()));
        line(sb, "Hardiness", p.hardinessZones());
        line(sb, "Source", p.source() != null ? p.source().getLabel() : null);
        sb.append("\nUser's spaces: ")
          .append(userSpaces.isEmpty() ? "none recorded yet" : String.join("; ", userSpaces))
          .append("\n\nWrite the explanation now.");
        return sb.toString();
    }

    /** Deterministic fallback when the LLM cannot be used. */
    static String summarize(PlantCareProfile p) {
        String name = p.commonName() != null ? p.commonName() + " (" + p.scientificName() + ")" : p.scientificName();
        StringBuilder sb = new StringBuilder();
        if (p.similarPlantName() != null) {
            sb.append("Exact care data was not found; this describes a similar plant, ")
              .append(p.similarPlantName()).append(". Try again with more photos for a better match.\n\n");
        }
        sb.append("Here is what we know about ").append(name).append(":\n");
        bullet(sb, "Watering", join(p.watering(), p.wateringFrequency()));
        bullet(sb, "Light", p.sunlight().isEmpty() ? null : String.join(", ", p.sunlight()));
        bullet(sb, "Care level", p.careLevel());
        bullet(sb, "Grows indoors", yesNo(p.indoor()));
        if (Boolean.TRUE.equals(p.poisonousToPets())) {
            sb.append("- **Warning:** poisonous to pets, so keep it out of their reach.\n");
        }
        sb.append("\nWatch how your plant responds (drooping, yellowing or crispy leaves) and adjust.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ F02/F03 explanations

    String explainSchedule(ScheduleResult s) {
        StringBuilder facts = new StringBuilder();
        facts.append("Plant: ").append(s.plantName()).append('\n')
             .append("Interval: every ").append(s.intervalDays()).append(" days (").append(s.intervalBasis()).append(")\n")
             .append("Method: ").append(s.strategy()).append(" - ").append(s.reason()).append('\n')
             .append("Next waterings: ").append(String.join("; ", s.nextWaterings().stream().map(t -> t.format(WHEN)).toList()))
             .append('\n');
        if (s.dataWarning() != null) {
            facts.append("Data warning: ").append(s.dataWarning()).append('\n');
        }
        return generateOr(SCHEDULE_INSTRUCTIONS, facts.toString(), () -> {
            StringBuilder sb = new StringBuilder("I've planned **every " + s.intervalDays() + " days** for "
                    + s.plantName() + " (" + s.reason() + ").");
            if (!s.nextWaterings().isEmpty()) {
                sb.append(" Next watering: ").append(s.nextWaterings().get(0).format(WHEN)).append('.');
            }
            if (s.dataWarning() != null) {
                sb.append("\n\n").append(s.dataWarning())
                  .append(" Press \"Search for more info\" and I'll research it, or keep this best guess.");
            }
            sb.append("\n\nAlways check the soil first: if the top few centimetres are still damp, wait a day or two.");
            return sb.toString();
        });
    }

    String explainReschedule(RescheduleResult r) {
        String facts = "Understood availability: " + r.availability().summary() + "\nTravel buffer minutes: "
                + r.availability().travelBufferMinutes() + "\nTrips away: " + r.availability().awayPeriods().size()
                + "\nPlants rescheduled: " + r.plantsRescheduled() + "\nChanges:\n- " + String.join("\n- ", r.examples());
        return generateOr(RESCHEDULE_INSTRUCTIONS, facts, () -> {
            if (r.plantsRescheduled() == 0) {
                return "Got it: " + r.availability().summary() + " I'll use this when you add plants.";
            }
            return "Got it: " + r.availability().summary() + " I moved " + r.plantsRescheduled()
                    + " plant(s) onto times that suit you:\n- " + String.join("\n- ", r.examples())
                    + "\n\nPress \"Undo\" if anything looks wrong.";
        });
    }

    // ------------------------------------------------------------------ F09 fit

    DesiredFit judgeFit(AgentRequest request, CareLookup lookup) {
        String spacePath = request.param("spacePath", String.class);
        String lightName = request.param("light", String.class);
        LightLevel light = lightName == null ? null : LightLevel.valueOf(lightName);
        PlantQuery query = request.param("query", PlantQuery.class);
        if (lookup == null) {
            return new DesiredFit(FitVerdict.UNKNOWN, "I couldn't find care information for " + query.scientificName()
                    + ", so I can't judge whether " + spacePath + " suits it. Press \"Search the web\" and I'll look it up.",
                    false, true);
        }
        PlantCareProfile p = lookup.profile();
        FitVerdict rule = ruleBasedFit(p.sunlight(), light);
        if (llm.isConfigured()) {
            try {
                String prompt = describeForLlm(p, List.of()) + "\n\nSpace: " + spacePath + " - light: "
                        + (light != null ? light.getLabel() : "not set") + "\nPlants already there: "
                        + String.join(", ", stringList(request.params().get("neighbours")))
                        + "\nSimple light-matching rule says: " + rule;
                JsonNode json = llm.generateStructured(FIT_INSTRUCTIONS, prompt, fitSchema);
                FitVerdict verdict = parseVerdict(JsonValues.text(json.path("verdict")), rule);
                return new DesiredFit(verdict, JsonValues.text(json.path("explanation")), true, false);
            } catch (ExternalServiceException e) {
                log.info("Fit check by LLM failed, using the light rule: {}", e.getMessage());
            }
        }
        String needs = p.sunlight().isEmpty() ? "unknown light needs" : "needs " + String.join(", ", p.sunlight());
        String text = switch (rule) {
            case GOOD -> "Good match: " + spacePath + " has " + light.getLabel().toLowerCase(Locale.ROOT) + " and this plant " + needs + ".";
            case FAIR -> "Could work: this plant " + needs + ", and " + spacePath + " is a step away from that. Watch for stretching or scorched leaves.";
            case POOR -> "Probably not a good spot: this plant " + needs + ", but " + spacePath + " has "
                    + (light != null ? light.getLabel().toLowerCase(Locale.ROOT) : "no light level set") + ".";
            case UNKNOWN -> light == null ? "Set a light level for " + spacePath + " in the Room Builder so I can judge the fit."
                    : "The care data doesn't say how much light this plant needs, so I can't judge the fit.";
        };
        return new DesiredFit(rule, text, false, rule == FitVerdict.UNKNOWN && light != null);
    }

    /** Light-level matching used as a baseline and fallback. Package-private for tests. */
    static FitVerdict ruleBasedFit(List<String> sunlight, LightLevel light) {
        List<LightLevel> needs = LightLevel.fromSunlight(sunlight);
        if (light == null || needs.isEmpty()) {
            return FitVerdict.UNKNOWN;
        }
        if (needs.contains(light)) {
            return FitVerdict.GOOD;
        }
        int distance = needs.stream().mapToInt(n -> Math.abs(n.ordinal() - light.ordinal())).min().orElse(9);
        return distance == 1 ? FitVerdict.FAIR : FitVerdict.POOR;
    }

    private static FitVerdict parseVerdict(String value, FitVerdict fallback) {
        try {
            return FitVerdict.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ CLI answers

    String answer(AgentRequest request, CareLookup lookup, List<String> spaces) {
        String question = request.param("question", String.class);
        StringBuilder prompt = new StringBuilder();
        String context = request.param("context", String.class);
        if (context != null) {
            prompt.append(context).append("\n\n");
        }
        if (lookup != null) {
            prompt.append(describeForLlm(lookup.profile(), spaces)).append("\n\n");
        } else if (!spaces.isEmpty()) {
            prompt.append("User's spaces: ").append(String.join("; ", spaces)).append("\n\n");
        }
        prompt.append("User's latest message: ").append(question);
        if (!llm.isConfigured()) {
            return lookup != null ? summarize(lookup.profile())
                    : "I need the AI (COHERE_API_KEY in .env) to answer open questions. I can still identify plants "
                      + "from photos, show what needs watering (\"what needs water?\"), record waterings "
                      + "(\"I watered the monstera\") and undo schedule changes.";
        }
        return llm.generate(ANSWER_INSTRUCTIONS, prompt.toString());
    }

    // ------------------------------------------------------------------ helpers

    private String generateOr(String instructions, String prompt, java.util.function.Supplier<String> fallback) {
        if (llm.isConfigured()) {
            try {
                return llm.generate(instructions, prompt);
            } catch (ExternalServiceException e) {
                log.info("LLM text failed, using the plain version: {}", e.getMessage());
            }
        }
        return fallback.get();
    }

    private static String sources(ResearchResult research) {
        if (research.sources().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nSources:");
        for (int i = 0; i < research.sources().size(); i++) {
            sb.append("\n- [").append(i + 1).append("] ").append(research.sources().get(i).title())
              .append(" (").append(research.sources().get(i).url()).append(')');
        }
        return sb.toString();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of("none");
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static void bullet(StringBuilder sb, String label, String value) {
        sb.append("- **").append(label).append(":** ").append(value != null ? value : "not available").append('\n');
    }

    private static String yesNo(Boolean value) {
        return value == null ? null : (value ? "yes" : "no");
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " (" + b + ")";
    }
}
