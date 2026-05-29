package org.example.coral.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/** Small JSON helper that tolerates markdown-fenced LLM output. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static <T> Optional<T> parse(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String cleaned = stripFences(raw);
        try {
            return Optional.ofNullable(MAPPER.readValue(cleaned, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Strip ```json ... ``` fences and any prose before the first JSON brace. */
    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
        }
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        int start = (brace < 0) ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (start > 0) s = s.substring(start);
        return s.trim();
    }
}
