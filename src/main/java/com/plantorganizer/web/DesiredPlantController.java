package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AddPlantResult;
import com.plantorganizer.agent.AgentController;
import com.plantorganizer.agent.DesiredFit;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.plants.DesiredPlant;
import com.plantorganizer.websearch.ResearchResult;

/** HTTP boundary for F09 (wishlist / desired plants). */
@RestController
@RequestMapping("/api/desired")
public class DesiredPlantController {

    private final AgentController agent;

    public DesiredPlantController(AgentController agent) {
        this.agent = agent;
    }

    public record AddRequest(String scientificName, String commonName, Long spaceId, String note, String imageUrl) {
    }

    public record UpdateRequest(Long spaceId, String note) {
    }

    @GetMapping
    public List<DesiredPlant> list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listDesiredPlants(userId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DesiredPlant add(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @RequestBody AddRequest r) {
        return agent.addDesiredPlant(userId, r.scientificName(), r.commonName(), r.spaceId(), r.note(), r.imageUrl());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DesiredPlant update(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                               @PathVariable long id, @RequestBody UpdateRequest r) {
        return agent.updateDesiredPlant(userId, id, r.spaceId(), r.note());
    }

    @PostMapping("/{id}/fit")
    public DesiredFit fit(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                          @PathVariable long id) {
        return agent.checkDesiredPlantFit(userId, id);
    }

    @PostMapping("/{id}/research")
    public ResearchResult research(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                   @PathVariable long id) {
        return agent.researchDesiredPlant(userId, id);
    }

    @PostMapping("/{id}/buy")
    public AddPlantResult buy(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                              @PathVariable long id) {
        return agent.buyDesiredPlant(userId, id);
    }

    @DeleteMapping("/{id}")
    public void remove(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable long id) {
        agent.removeDesiredPlant(userId, id);
    }
}
