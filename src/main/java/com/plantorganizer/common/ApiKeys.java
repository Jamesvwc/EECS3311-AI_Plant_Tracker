package com.plantorganizer.common;

/** Small helper shared by the adapters and the status endpoint. */
public final class ApiKeys {

    private ApiKeys() {
    }

    /** True when a key was supplied and is not the placeholder from .env.example. */
    public static boolean isConfigured(String key) {
        return key != null && !key.isBlank() && !key.startsWith("YOUR_");
    }
}
