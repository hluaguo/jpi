package dev.jpi.ai.providers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit seam for the JSON salvage applied to streamed tool arguments: raw control
 * characters and invalid escapes inside string literals are repaired before
 * parsing; still-unparseable arguments become an empty map so the loop reports
 * an error tool call instead of failing the whole message.
 */
class JsonRepairTest {

    @Test
    void rawControlCharactersInsideStringsAreEscaped() {
        Map<String, Object> parsed = JsonRepair.parseArguments("{\"cmd\": \"ls\n--all\"}");

        assertEquals(Map.of("cmd", "ls\n--all"), parsed);
    }

    @Test
    void invalidEscapesAreDoubledSoTheBackslashSurvives() {
        // \q is not a valid JSON escape; repair turns it into a literal backslash + q
        assertEquals(Map.of("path", "a\\qb"), JsonRepair.parseArguments("{\"path\": \"a\\qb\"}"));
    }

    @Test
    void validEscapesAreLeftUntouched() {
        assertEquals(
                Map.of("a", "\n", "b", "\t", "c", "\\", "d", "\"", "e", "/", "f", "A"),
                JsonRepair.parseArguments(
                        "{\"a\": \"\\n\", \"b\": \"\\t\", \"c\": \"\\\\\", \"d\": \"\\\"\", \"e\": \"\\/\", \"f\": \"\\u0041\"}"));
    }

    @Test
    void escapedQuoteDoesNotEndTheString() {
        assertEquals(Map.of("t", "a\"b"), JsonRepair.parseArguments("{\"t\": \"a\\\"b\"}"));
    }

    @Test
    void trailingBackslashAtEndOfInputIsDoubled() {
        // a lone backslash at end of input cannot be a valid escape; repair doubles
        // it. The string stays unterminated, so parsing yields no arguments — the
        // loop's schema validation reports that.
        assertEquals("{\"t\": \"b\\\\", JsonRepair.repair("{\"t\": \"b\\"));
        assertEquals(Map.of(), JsonRepair.parseArguments("{\"t\": \"b\\"));
    }

    @Test
    void invalidUnicodeEscapeIsDoubled() {
        assertEquals(Map.of("t", "\\uZZZZ"), JsonRepair.parseArguments("{\"t\": \"\\uZZZZ\"}"));
    }

    @Test
    void controlCharactersOutsideStringsAreLeftAlone() {
        // whitespace control chars are legal JSON outside strings
        assertEquals(Map.of("a", 1), JsonRepair.parseArguments("{\n \"a\": 1\n}"));
    }

    @Test
    void unrepairableGarbageBecomesAnEmptyArgumentsMap() {
        assertEquals(Map.of(), JsonRepair.parseArguments("<<<not json at all>>>"));
        assertEquals(Map.of(), JsonRepair.parseArguments(""));
        assertEquals(Map.of(), JsonRepair.parseArguments(null));
    }
}
