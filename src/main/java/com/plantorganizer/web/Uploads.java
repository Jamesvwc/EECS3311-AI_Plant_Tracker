package com.plantorganizer.web;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.plantorganizer.common.InvalidInputException;
import com.plantorganizer.identification.PlantImage;

/** Converts an uploaded file to the app's PlantImage (bytes + name + type). */
final class Uploads {

    private Uploads() {
    }

    static PlantImage toImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new PlantImage(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new InvalidInputException("Could not read the uploaded file " + file.getOriginalFilename());
        }
    }
}
