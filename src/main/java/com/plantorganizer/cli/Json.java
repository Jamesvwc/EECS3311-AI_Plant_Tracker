package com.plantorganizer.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** Small helpers for reading the server's JSON answers and writing request bodies. */
public final class Json {

    private Json() {
    }

    /** The field as text, or null when it is missing, null or blank. */
    public static String str(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    public static long id(JsonNode node) {
        return node.path("id").asLong();
    }

    public static boolean bool(JsonNode node, String field) {
        return node.path(field).asBoolean();
    }

    /** The elements of a JSON array (an empty list for anything else). */
    public static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }

    /** The texts of a JSON array of strings. */
    public static List<String> strings(JsonNode array) {
        return list(array).stream().filter(n -> !n.isNull()).map(JsonNode::asString).filter(s -> !s.isBlank()).toList();
    }

    /** A request body from key/value pairs; null values are kept (the server reads them as "not given"). */
    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
