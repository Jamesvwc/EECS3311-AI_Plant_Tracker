package com.plantorganizer.agent.tools;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.plantorganizer.identification.ImageProcessor;
import com.plantorganizer.identification.PlantIdentifier;
import com.plantorganizer.llm.LLMClient;
import com.plantorganizer.memory.AgentMemory;
import com.plantorganizer.notification.NotificationService;
import com.plantorganizer.plantinfo.PlantInfoService;
import com.plantorganizer.plants.PlantRepository;
import com.plantorganizer.recommendation.RecommendationEngine;
import com.plantorganizer.schedule.ScheduleManager;
import com.plantorganizer.spaces.SpaceManager;
import com.plantorganizer.user.UserRepository;
import com.plantorganizer.websearch.WebSearch;

import tools.jackson.databind.json.JsonMapper;

/**
 * Creator of the Factory Method pattern: {@link #createTool(ToolType)} is the
 * one place that knows which concrete Tool class belongs to each ToolType and
 * which dependencies it needs. ToolManager asks for tools by type and works
 * with the {@link Tool} interface only, so adding a tool means adding a
 * ToolType value and one case here, without touching the agent or the manager.
 */
@Component
public class ToolFactory {

    private final ImageProcessor imageProcessor;
    private final PlantIdentifier plantIdentifier;
    private final PlantInfoService plantInfoService;
    private final LLMClient llm;
    private final JsonMapper jsonMapper;
    private final PlantRepository plantRepository;
    private final SpaceManager spaceManager;
    private final AgentMemory memory;
    private final RecommendationEngine recommendationEngine;
    private final UserRepository userRepository;
    private final ScheduleManager scheduleManager;
    private final NotificationService notificationService;
    private final WebSearch webSearch;
    private final Clock clock;

    public ToolFactory(ImageProcessor imageProcessor, PlantIdentifier plantIdentifier, PlantInfoService plantInfoService,
                       LLMClient llm, JsonMapper jsonMapper, PlantRepository plantRepository, SpaceManager spaceManager,
                       AgentMemory memory, RecommendationEngine recommendationEngine, UserRepository userRepository,
                       ScheduleManager scheduleManager, NotificationService notificationService, WebSearch webSearch,
                       Clock clock) {
        this.imageProcessor = imageProcessor;
        this.plantIdentifier = plantIdentifier;
        this.plantInfoService = plantInfoService;
        this.llm = llm;
        this.jsonMapper = jsonMapper;
        this.plantRepository = plantRepository;
        this.spaceManager = spaceManager;
        this.memory = memory;
        this.recommendationEngine = recommendationEngine;
        this.userRepository = userRepository;
        this.scheduleManager = scheduleManager;
        this.notificationService = notificationService;
        this.webSearch = webSearch;
        this.clock = clock;
    }

    public Tool createTool(ToolType type) {
        return switch (type) {
            case IDENTIFY_PLANT -> new IdentifyPlantTool(imageProcessor, plantIdentifier);
            case LOOKUP_PLANT_INFO -> new PlantInfoTool(plantInfoService, llm, jsonMapper);
            case PLANT_COLLECTION -> new PlantCollectionTool(plantRepository, spaceManager, memory,
                    recommendationEngine, userRepository, clock);
            case CALENDAR -> new CalendarTool(scheduleManager, notificationService);
            case ANALYZE_HEALTH -> new HealthAnalysisTool(plantRepository, imageProcessor, plantIdentifier,
                    plantInfoService, llm, jsonMapper, clock);
            case WEB_SEARCH -> new WebSearchTool(webSearch, llm, jsonMapper);
            case RECOMMEND -> new RecommendTool(recommendationEngine);
            case SPACE -> new SpaceTool(spaceManager);
        };
    }
}
