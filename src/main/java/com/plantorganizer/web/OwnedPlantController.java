package com.plantorganizer.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plantorganizer.agent.AddPlantResult;
import com.plantorganizer.agent.AgentController;
import com.plantorganizer.agent.OwnedPlantView;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.health.HealthReport;
import com.plantorganizer.plantinfo.PlantCareProfile;
import com.plantorganizer.plants.HealthRecord;
import com.plantorganizer.plants.PlantNote;
import com.plantorganizer.plants.PlantPhoto;
import com.plantorganizer.schedule.ScheduleResult;
import com.plantorganizer.websearch.ResearchResult;

/** HTTP boundary for F08 (owned plants), plus the per-plant parts of F02, F04 and F05. */
@RestController
@RequestMapping("/api/plants")
public class OwnedPlantController {

    private final AgentController agent;

    public OwnedPlantController(AgentController agent) {
        this.agent = agent;
    }

    public record AddPlantRequest(String scientificName, String commonName, String nickname, Long spaceId) {
    }

    public record UpdatePlantRequest(String nickname, PlantCareProfile care) {
    }

    public record TextRequest(String text) {
    }

    public record ToggleRequest(Boolean enabled) {
    }

    @GetMapping
    public List<OwnedPlantView> list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listOwnedPlants(userId);
    }

    @GetMapping("/{id}")
    public OwnedPlantView get(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                              @PathVariable long id) {
        return agent.getOwnedPlant(userId, id);
    }

    /** UC02: "I own this plant" or manual "Add plant". */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public AddPlantResult add(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                              @RequestBody AddPlantRequest r) {
        return agent.addOwnedPlant(userId, r.scientificName(), r.commonName(), r.nickname(), r.spaceId());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public OwnedPlantView update(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                 @PathVariable long id, @RequestBody UpdatePlantRequest r) {
        return agent.updateCareNotes(userId, id, r.nickname(), r.care());
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable long id) {
        agent.deleteOwnedPlant(userId, id);
    }

    @GetMapping("/{id}/notes")
    public List<PlantNote> notes(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                 @PathVariable long id) {
        return agent.listNotes(userId, id);
    }

    @PostMapping(path = "/{id}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlantNote addNote(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                             @PathVariable long id, @RequestBody TextRequest r) {
        return agent.saveNote(userId, id, r.text());
    }

    @PostMapping(path = "/{id}/agent-notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlantNote addAgentNote(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                  @PathVariable long id, @RequestBody TextRequest r) {
        return agent.saveAgentNote(userId, id, r.text());
    }

    @DeleteMapping("/{id}/notes/{noteId}")
    public void deleteNote(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                           @PathVariable long id, @PathVariable long noteId) {
        agent.deleteNote(userId, id, noteId);
    }

    /** F04 UC05: mark as watered. */
    @PostMapping("/{id}/water")
    public Map<String, String> water(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                     @PathVariable long id) {
        return Map.of("message", agent.markWatered(userId, id));
    }

    /** F04: per-plant reminder toggle. */
    @PutMapping(path = "/{id}/notifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> notifications(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                              @PathVariable long id, @RequestBody ToggleRequest r) {
        boolean enabled = Boolean.TRUE.equals(r.enabled());
        agent.setNotifications(userId, id, enabled);
        return Map.of("enabled", enabled);
    }

    /** F02: regenerate this plant's schedule. */
    @PostMapping("/{id}/schedule")
    public ScheduleResult schedule(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                   @PathVariable long id) {
        return agent.generateSchedule(userId, id);
    }

    /** F02: "search for longer" when the watering data was thin. */
    @PostMapping("/{id}/schedule/research")
    public ScheduleResult researchWatering(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                           @PathVariable long id) {
        return agent.researchWatering(userId, id);
    }

    /** F05: health check from the plant's page (multipart: optional "photo", optional "description"). */
    @PostMapping(path = "/{id}/health", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HealthReport health(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                               @PathVariable long id,
                               @RequestParam(name = "photo", required = false) MultipartFile photo,
                               @RequestParam(name = "description", required = false) String description) {
        return agent.analyzeHealth(userId, id, Uploads.toImage(photo), description, true);
    }

    @GetMapping("/{id}/health")
    public List<HealthRecord> healthHistory(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                            @PathVariable long id) {
        return agent.healthHistory(userId, id);
    }

    @PostMapping("/health/{recordId}/research")
    public ResearchResult researchHealth(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                         @PathVariable long recordId) {
        return agent.researchHealth(userId, recordId);
    }

    @PostMapping("/health/{recordId}/save-note")
    public PlantNote saveHealthNote(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                    @PathVariable long recordId) {
        return agent.saveHealthNote(userId, recordId);
    }

    /** Photos are fetched by the browser with ?user= because an <img> tag cannot send headers. */
    @GetMapping("/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> photo(@RequestParam(name = "user", defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                        @PathVariable long id, @PathVariable long photoId) {
        PlantPhoto photo = agent.getPhoto(userId, id, photoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, photo.getContentType() != null ? photo.getContentType() : "image/jpeg")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(photo.getData());
    }
}
