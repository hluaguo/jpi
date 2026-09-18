package dev.jpi.ai;

import java.util.List;

/** A user message; content is text/image blocks. */
public record UserMessage(List<Content> content, long timestamp) implements Message {

    public UserMessage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static UserMessage of(String text) {
        return new UserMessage(List.of(new Content.Text(text)), System.currentTimeMillis());
    }
}
