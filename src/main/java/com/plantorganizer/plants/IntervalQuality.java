package com.plantorganizer.plants;

/** How the watering interval was worked out (F02 "little/mixed information" handling). */
public enum IntervalQuality {
    /** A number of days from the care data, e.g. "every 7-10 days". */
    GOOD,
    /** Estimated from a category such as "Average" or "Frequent". */
    ESTIMATED,
    /** No watering data at all; a 7-day default was used. */
    MISSING
}
