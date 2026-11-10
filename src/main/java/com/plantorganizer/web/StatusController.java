package com.plantorganizer.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test endpoint: lets the frontend (and you) check that the backend is
 * running and which API keys were picked up from .env. It reports only
 * whether each key is present, never the key itself.
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    @Value("${plantorganizer.cohere.api-key}")
    private String cohereKey;

    @Value("${plantorganizer.perenual.api-key}")
    private String perenualKey;

    @Value("${plantorganizer.plantnet.api-key}")
    private String plantnetKey;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "status", "ok",
                "keysConfigured", Map.of(
                        "cohere", isSet(cohereKey),
                        "perenual", isSet(perenualKey),
                        "plantnet", isSet(plantnetKey)));
    }

    private static boolean isSet(String key) {
        return key != null && !key.isBlank() && !key.startsWith("YOUR_");
    }
}
