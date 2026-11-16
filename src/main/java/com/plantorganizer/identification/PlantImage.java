package com.plantorganizer.identification;

/**
 * A photo of a plant held in memory: the raw bytes plus the file name and
 * MIME type. Used both for the user's original upload and for the resized
 * copy that ImageProcessor produces for Pl@ntNet.
 */
public record PlantImage(byte[] data, String filename, String contentType) {

    public PlantImage {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Image data must not be empty");
        }
        filename = (filename == null || filename.isBlank()) ? "photo.jpg" : filename;
    }

    public int sizeInBytes() {
        return data.length;
    }
}
