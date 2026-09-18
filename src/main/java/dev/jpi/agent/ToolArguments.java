package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates tool-call arguments against a tool's JSON-Schema-shaped
 * {@code parameters} map before execution: required presence, JSON types
 * (including integer and type unions), enum membership — recursing through
 * {@code properties} and {@code items}. Deliberately no coercion and no
 * combinators ({@code $ref}/{@code allOf}/…): silent coercion hides model
 * defects, and the demo tools declare plain schemas.
 *
 * <p>Problems are returned as data (formatted, one per line) rather than thrown,
 * so the loop can turn them into an error tool result naming the bad argument.
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * All problems with {@code args}, outermost first; each line is formatted as
     * {@code "  - <path>: <what>"}. Empty means valid.
     */
    public static List<String> validate(Map<String, Object> schema, Object args) {
        List<String> problems = new ArrayList<>();
        check(schema, args, "root", problems);
        return problems;
    }

    private static void check(Map<String, Object> schema, Object value, String path, List<String> problems) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        List<String> types = types(schema);
        if (!types.isEmpty() && !matchesAnyType(value, types)) {
            problems.add("  - " + path + ": expected " + String.join("|", types) + ", got " + jsonType(value));
            return;
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> allowed && !allowed.contains(value)) {
            problems.add("  - " + path + ": must be one of " + allowed);
        }
        if (value instanceof Map<?, ?> map) {
            checkObject(schema, map, path, problems);
        } else if (value instanceof List<?> items) {
            checkArray(schema, items, path, problems);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkObject(Map<String, Object> schema, Map<?, ?> value, String path, List<String> problems) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (value.containsKey(entry.getKey()) && entry.getValue() instanceof Map<?, ?> sub) {
                    check((Map<String, Object>) sub, value.get(entry.getKey()),
                            child(path, entry.getKey()), problems);
                }
            }
        }
        Object required = schema.get("required");
        if (required instanceof List<?> names) {
            for (Object name : names) {
                if (!value.containsKey(name)) {
                    problems.add("  - " + child(path, String.valueOf(name)) + ": is required");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkArray(Map<String, Object> schema, List<?> value, String path, List<String> problems) {
        if (schema.get("items") instanceof Map<?, ?> itemSchema) {
            for (int i = 0; i < value.size(); i++) {
                check((Map<String, Object>) itemSchema, value.get(i), path + "." + i, problems);
            }
        }
    }

    private static List<String> types(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String s) {
            return List.of(s);
        }
        if (type instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static boolean matchesAnyType(Object value, List<String> types) {
        return types.stream().anyMatch(t -> matchesType(value, t));
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n
                    && Double.isFinite(n.doubleValue())
                    && n.doubleValue() == Math.floor(n.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true; // unknown type keyword: don't invent a failure
        };
    }

    private static String jsonType(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Number n) {
            return matchesType(n, "integer") ? "integer" : "number";
        }
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return value.getClass().getSimpleName();
    }

    /** The root path has no prefix; nested paths are dot-joined. */
    private static String child(String path, String key) {
        return "root".equals(path) ? key : path + "." + key;
    }
}
