package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.memory.UserPreference;
import com.plantorganizer.recommendation.RecommendationEngine;
import com.plantorganizer.recommendation.RecommendedPlant;

/** HTTP boundary for F06 (automatic list) and F07 (recommendations for stated constraints). */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final AgentController agent;

    public RecommendationController(AgentController agent) {
        this.agent = agent;
    }

    public record ConstraintRequest(String constraints) {
    }

    @GetMapping
    public AgentController.RecommendationList list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                                   @RequestParam(name = "sort", defaultValue = "CARE") RecommendationEngine.SortBy sort) {
        return agent.getRecommendations(userId, sort);
    }

    @PostMapping("/refresh")
    public List<RecommendedPlant> refresh(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.refreshRecommendations(userId);
    }

    @PostMapping(path = "/request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<RecommendedPlant> request(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                          @RequestBody ConstraintRequest r) {
        return agent.getRecommendationsFor(userId, r.constraints());
    }

    @PostMapping("/{id}/accept")
    public RecommendedPlant accept(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                   @PathVariable long id) {
        return agent.acceptRecommendation(userId, id);
    }

    /** "Unrecommend": removes it and remembers the dislike. */
    @DeleteMapping("/{id}")
    public void unrecommend(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @PathVariable long id) {
        agent.unrecommend(userId, id);
    }

    @GetMapping("/preferences")
    public List<UserPreference> preferences(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listPreferences(userId);
    }

    @DeleteMapping("/preferences/{id}")
    public void forget(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable long id) {
        agent.forgetPreference(userId, id);
    }
}
