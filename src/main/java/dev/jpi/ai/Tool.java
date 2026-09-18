package dev.jpi.ai;

import java.util.Map;

/**
 * A tool declaration sent to the model: name, natural-language description, and the
 * argument schema as a JSON-Schema-shaped map.
 */
public record Tool(String name, String description, Map<String, Object> parameters) {

    public Tool {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
