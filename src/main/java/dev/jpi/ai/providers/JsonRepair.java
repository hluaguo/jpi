package dev.jpi.ai.providers;

import java.util.Map;

/**
 * Salvage for streamed tool-call arguments. Model-emitted JSON string literals can
 * carry raw control characters (a newline echoed inside a command's output) or
 * invalid escapes; strict parsing would fail the whole assistant message over a
 * single bad byte.
 *
 * <p>The repair pass mirrors pi's {@code repairJson}: inside string literals, raw
 * control characters are escaped and backslashes before invalid escapes are
 * doubled; {@code uXXXX} escapes are kept only when all four digits are hex.
 *
 * <p>Arguments that remain unparseable become an empty map — the tool call then
 * fails downstream (schema validation reports the missing arguments) instead of
 * the message failing, so the model can re-issue just that one call.
 */
public final class JsonRepair {

    private static final String VALID_ESCAPES = "\"\\/bfnrt";

    private JsonRepair() {
    }

    /** Parses tool arguments, salvaging repairable string literals; never throws. */
    static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Json.MAPPER.readValue(json, Map.class);
        } catch (Exception ignored) {
            // fall through to salvage
        }
        String repaired = repair(json);
        if (repaired.equals(json)) {
            return Map.of();
        }
        try {
            return Json.MAPPER.readValue(repaired, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Escapes raw control characters and doubles backslashes before invalid escapes, inside string literals only. */
    public static String repair(String json) {
        StringBuilder repaired = new StringBuilder(json.length() + 16);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                repaired.append(c);
                if (c == '"') {
                    inString = true;
                }
                continue;
            }
            if (c == '"') {
                repaired.append(c);
                inString = false;
                continue;
            }
            if (c == '\\') {
                i = appendEscape(json, i, repaired);
                continue;
            }
            repaired.append(c < 0x20 ? escapeControl(c) : c);
        }
        return repaired.toString();
    }

    /** Handles the character after a backslash at {@code i}; returns the next index to consume. */
    private static int appendEscape(String json, int i, StringBuilder repaired) {
        if (i + 1 >= json.length()) {
            repaired.append("\\\\");
            return i;
        }
        char next = json.charAt(i + 1);
        if (next == 'u' && i + 5 < json.length() && isHexQuad(json, i + 2)) {
            repaired.append(json, i, i + 6);
            return i + 5;
        }
        if (VALID_ESCAPES.indexOf(next) >= 0) {
            repaired.append('\\').append(next);
            return i + 1;
        }
        repaired.append("\\\\");
        return i;
    }

    private static boolean isHexQuad(String json, int start) {
        for (int i = start; i < start + 4; i++) {
            char c = json.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static String escapeControl(char c) {
        return switch (c) {
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> String.format("\\u%04x", (int) c);
        };
    }
}
