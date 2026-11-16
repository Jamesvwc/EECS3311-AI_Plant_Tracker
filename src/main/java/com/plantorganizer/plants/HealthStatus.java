package com.plantorganizer.plants;

/** Overall result of a health check (F05). */
public enum HealthStatus {
    HEALTHY, MINOR_ISSUES, NEEDS_ATTENTION, CRITICAL;

    public boolean needsAttention() {
        return this == NEEDS_ATTENTION || this == CRITICAL;
    }
}
