package com.plantorganizer.agent.tools;

import java.util.List;
import java.util.Map;

import com.plantorganizer.identification.IdentificationResult;
import com.plantorganizer.identification.ImageProcessor;
import com.plantorganizer.identification.PlantIdentifier;
import com.plantorganizer.identification.PlantImage;

/**
 * Concrete tool: Stage 1 workflow steps 1-3.
 * Resizes each photo (ImageProcessor), sends them to the identification
 * service (PlantIdentifier, i.e. Pl@ntNet) and returns the top 3 candidates.
 *
 * Arguments: {@code "images"} -> {@code List<PlantImage>}
 * Result:    {@link IdentificationResult}
 */
public class IdentifyPlantTool implements Tool {

    public static final String ARG_IMAGES = "images";
    static final int TOP_CANDIDATES = 3;

    private final ImageProcessor imageProcessor;
    private final PlantIdentifier plantIdentifier;

    IdentifyPlantTool(ImageProcessor imageProcessor, PlantIdentifier plantIdentifier) {
        this.imageProcessor = imageProcessor;
        this.plantIdentifier = plantIdentifier;
    }

    @Override
    public ToolType type() {
        return ToolType.IDENTIFY_PLANT;
    }

    @Override
    public IdentificationResult execute(Map<String, Object> args) {
        List<?> raw = (List<?>) args.get(ARG_IMAGES);
        List<PlantImage> prepared = raw.stream()
                .map(PlantImage.class::cast)
                .map(imageProcessor::resize)
                .toList();
        return plantIdentifier.identify(prepared, TOP_CANDIDATES);
    }
}
