package com.plantorganizer.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.agent.PlantReport;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.PlantImage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP boundary for F01, used by plantidentifier.html. It only converts
 * between HTTP and Java objects and delegates everything to the
 * AgentController facade.
 *
 *   POST /api/identify          multipart "photos" (1-5 files) -> IdentificationResult JSON
 *   POST /api/identify/text     {"description"}                 -> IdentificationResult JSON
 *   POST /api/identify/confirm  {"scientificName", "commonName"} -> PlantReport JSON
 */
@RestController
@RequestMapping("/api/identify")
public class PlantIdentifierController {

    private final AgentController agentController;

    public PlantIdentifierController(AgentController agentController) {
        this.agentController = agentController;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IdentificationResult identify(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                         @RequestParam("photos") List<MultipartFile> photos) {
        List<PlantImage> images = new ArrayList<>();
        for (MultipartFile photo : photos) {
            PlantImage image = Uploads.toImage(photo);
            if (image != null) {
                images.add(image);
            }
        }
        return agentController.identifyPlant(userId, images);
    }

    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IdentificationResult identifyByText(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                               @RequestBody DescribeRequest request) {
        return agentController.identifyByText(userId, request.description());
    }

    @PostMapping(path = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlantReport confirm(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                               @Valid @RequestBody ConfirmRequest request) {
        return agentController.confirmCandidate(userId, request.scientificName(), request.commonName());
    }

    /** JSON body sent when the user picks one of the candidates. */
    public record ConfirmRequest(@NotBlank String scientificName, String commonName) {
    }

    public record DescribeRequest(String description) {
    }
}
