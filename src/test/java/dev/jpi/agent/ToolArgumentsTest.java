package dev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit seam for the JSON-Schema-shaped validator the loop runs before executing a
 * tool call: required presence, types, enum — no coercion, no combinators.
 */
class ToolArgumentsTest {

    @Test
    void validArgsYieldNoProblems() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string"),
                                "count", Map.of("type", "integer")),
                        "required", List.of("path")),
                Map.of("path", "a.txt", "count", 3));

        assertTrue(problems.isEmpty(), () -> "unexpected problems: " + problems);
    }

    @Test
    void missingRequiredPropertyIsReported() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", List.of("path")),
                Map.of());

        assertEquals(List.of("  - path: is required"), problems);
    }

    @Test
    void wrongTypeIsReportedNamingTheArgument() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string"))),
                Map.of("path", 42));

        assertEquals(List.of("  - path: expected string, got integer"), problems);
    }

    @Test
    void integerRejectsFractionalNumbersButAcceptsWholeOnes() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("count", Map.of("type", "integer")));

        assertTrue(ToolArguments.validate(schema, Map.of("count", 2)).isEmpty());
        assertEquals(
                List.of("  - count: expected integer, got number"),
                ToolArguments.validate(schema, Map.of("count", 1.5)));
    }

    @Test
    void enumViolationIsReported() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("size",
                                Map.of("type", "string", "enum", List.of("small", "large")))),
                Map.of("size", "medium"));

        assertEquals(List.of("  - size: must be one of [small, large]"), problems);
    }

    @Test
    void nestedPropertiesAreValidatedWithDottedPaths() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("address",
                                Map.of("type", "object",
                                        "properties", Map.of("city", Map.of("type", "string"))))),
                Map.of("address", Map.of("city", 7)));

        assertEquals(List.of("  - address.city: expected string, got integer"), problems);
    }

    @Test
    void arrayItemsAreValidatedWithIndexPaths() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("tags",
                                Map.of("type", "array", "items", Map.of("type", "string")))),
                Map.of("tags", List.of("a", 1)));

        assertEquals(List.of("  - tags.1: expected string, got integer"), problems);
    }

    @Test
    void typeUnionAcceptsAnyListedType() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("limit", Map.of("type", List.of("integer", "null"))));

        Map<String, Object> nullLimit = new HashMap<String, Object>();
        nullLimit.put("limit", null);
        assertTrue(ToolArguments.validate(schema, nullLimit).isEmpty());
        assertTrue(ToolArguments.validate(schema, Map.of("limit", 5)).isEmpty());
        assertEquals(
                List.of("  - limit: expected integer|null, got string"),
                ToolArguments.validate(schema, Map.of("limit", "many")));
    }

    @Test
    void nullIsNotAString() {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", null);

        List<String> problems = ToolArguments.validate(
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string"))),
                args);

        assertEquals(List.of("  - path: expected string, got null"), problems);
    }

    @Test
    void emptySchemaAcceptsAnythingIncludingExtras() {
        assertTrue(ToolArguments.validate(Map.of(), Map.of("any", "thing", "n", 1)).isEmpty());
        assertTrue(ToolArguments.validate(Map.of("type", "object"), Map.of("extra", true)).isEmpty());
    }

    @Test
    void nonObjectArgsAreReportedAtTheRoot() {
        List<String> problems = ToolArguments.validate(
                Map.of("type", "object"), List.of("not", "a", "map"));

        assertEquals(List.of("  - root: expected object, got array"), problems);
    }
}
