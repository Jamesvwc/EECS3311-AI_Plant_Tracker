package com.plantorganizer.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.agent.OwnedPlantView;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.spaces.LightLevel;
import com.plantorganizer.spaces.SpaceView;

/** HTTP boundary for F10 (Room Builder). */
@RestController
@RequestMapping("/api/spaces")
public class SpaceController {

    private final AgentController agent;

    public SpaceController(AgentController agent) {
        this.agent = agent;
    }

    public record CreateRequest(String name, LightLevel light, Long parentId, Boolean spot) {
    }

    public record UpdateRequest(String name, LightLevel light, Boolean inheritLight) {
    }

    public record PositionRequest(double x, double y) {
    }

    @GetMapping
    public List<SpaceView> list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listSpaces(userId);
    }

    @GetMapping("/light-levels")
    public List<Map<String, String>> lightLevels() {
        return Arrays.stream(LightLevel.values()).map(l -> Map.of("value", l.name(), "label", l.getLabel())).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SpaceView create(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @RequestBody CreateRequest r) {
        return agent.createSpace(userId, r.name(), r.light(), r.parentId(), Boolean.TRUE.equals(r.spot()));
    }

    @GetMapping("/{id}")
    public SpaceView get(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                         @PathVariable long id) {
        return agent.getSpace(userId, id);
    }

    /** Selecting a space lists every plant inside it, at any depth (Composite). */
    @GetMapping("/{id}/plants")
    public List<OwnedPlantView> plants(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                       @PathVariable long id) {
        return agent.getPlantsInSpace(userId, id);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SpaceView update(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @PathVariable long id, @RequestBody UpdateRequest r) {
        return agent.updateSpace(userId, id, r.name(), r.light(), Boolean.TRUE.equals(r.inheritLight()));
    }

    @PutMapping(path = "/{id}/position", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void move(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                     @PathVariable long id, @RequestBody PositionRequest r) {
        agent.moveSpace(userId, id, r.x(), r.y());
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable long id) {
        agent.deleteSpace(userId, id);
    }

    @PostMapping("/{id}/plants/{plantId}")
    public SpaceView assign(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @PathVariable long id, @PathVariable long plantId) {
        return agent.assignPlant(userId, plantId, id);
    }

    @DeleteMapping("/plants/{plantId}")
    public void unassign(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                         @PathVariable long plantId) {
        agent.unassignPlant(userId, plantId);
    }
}
