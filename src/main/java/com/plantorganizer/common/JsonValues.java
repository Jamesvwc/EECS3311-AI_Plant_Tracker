package com.plantorganizer.common;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Lenient readers for JSON returned by third-party APIs. External responses
 * are not always consistent (a field may be a string in one record, an array
 * or a number in another, or missing), so these helpers return null / empty
 * instead of throwing.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** The node as trimmed text, or null when missing, null or blank. */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainer()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    /** A string array (or a single string) as a list of non-blank strings. */
    public static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = text(item);
                if (value != null) {
                    values.add(value);
                }
            }
        } else {
            String value = text(node);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** Accepts true/false, 1/0 and "true"/"yes"/"1"; null when unknown. */
    public static Boolean bool(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "true", "yes", "1" -> Boolean.TRUE;
            case "false", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
