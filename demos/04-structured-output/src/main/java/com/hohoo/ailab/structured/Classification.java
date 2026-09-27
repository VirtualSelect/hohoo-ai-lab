package com.hohoo.ailab.structured;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.util.*;

/** Small explicit contract, NOT a general JSON Schema implementation. */
public final class Classification {
    public final String category;
    public final List<String> tags;
    private static final Set<String> CATEGORIES = new HashSet<String>(
        Arrays.asList("ai-apps", "llm", "embodied-ai", "needs-review"));

    private Classification(String category, List<String> tags) {
        this.category = category;
        this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
    }

    public static Classification parse(String text) throws IOException {
        require(text != null && text.length() <= 8000, "content_missing_or_too_large");
        // Do not strip fences or extract a substring: reject ambiguous output.
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "object_required");
        reader.beginObject();
        Set<String> seen = new HashSet<String>();
        String category = null;
        List<String> tags = null;
        while (reader.hasNext()) {
            String key = reader.nextName();
            require(seen.add(key), "duplicate_field");
            if ("category".equals(key)) {
                require(reader.peek() == JsonToken.STRING, "category_string_required");
                category = reader.nextString();
                require(CATEGORIES.contains(category), "unknown_category");
            } else if ("tags".equals(key)) {
                require(reader.peek() == JsonToken.BEGIN_ARRAY, "tags_array_required");
                reader.beginArray();
                tags = new ArrayList<String>();
                while (reader.hasNext()) {
                    require(tags.size() < 3, "too_many_tags");
                    require(reader.peek() == JsonToken.STRING, "tag_string_required");
                    String tag = reader.nextString();
                    require(!tag.trim().isEmpty() && tag.equals(tag.trim()), "invalid_tag_whitespace");
                    require(tag.codePointCount(0, tag.length()) <= 20, "tag_too_long");
                    require(!tag.matches("(?s).*[\\p{Cntrl}].*"), "tag_control_character");
                    require(!tags.contains(tag), "duplicate_tag");
                    tags.add(tag);
                }
                reader.endArray();
                require(!tags.isEmpty(), "tags_empty");
            } else {
                throw new IOException("unknown_field");
            }
        }
        reader.endObject();
        require(seen.contains("category") && seen.contains("tags"), "missing_field");
        require(reader.peek() == JsonToken.END_DOCUMENT, "trailing_content");
        reader.close();
        return new Classification(category, tags);
    }

    static void require(boolean ok, String error) throws IOException {
        if (!ok) throw new IOException(error);
    }

    /** Validating the API envelope is a separate step from the inner business JSON. */
    public static JsonObject assistant(JsonObject envelope) throws IOException {
        try {
            JsonArray choices = envelope.getAsJsonArray("choices");
            require(choices != null && choices.size() > 0, "empty_choices");
            JsonObject choice = choices.get(0).getAsJsonObject();
            require("stop".equals(string(choice.get("finish_reason"))), "unfinished_response");
            JsonObject message = choice.getAsJsonObject("message");
            require(message != null && "assistant".equals(string(message.get("role"))), "assistant_required");
            require(message.get("refusal") == null || message.get("refusal").isJsonNull(), "model_refusal");
            String content = string(message.get("content"));
            require(!content.trim().isEmpty(), "empty_content");
            JsonObject selected = new JsonObject();
            selected.addProperty("content", content);
            selected.addProperty("finish_reason", "stop");
            if (envelope.has("model")) selected.add("model", envelope.get("model"));
            if (envelope.has("usage")) {
                JsonObject safeUsage = new JsonObject();
                JsonObject usage = envelope.getAsJsonObject("usage");
                if (usage != null) for (String key : Arrays.asList("prompt_tokens", "completion_tokens", "total_tokens")) {
                    JsonElement n = usage.get(key);
                    if (n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber())
                        safeUsage.add(key, n);
                }
                selected.add("usage", safeUsage);
            }
            return selected;
        } catch (IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("invalid_envelope");
        }
    }

    static String string(JsonElement value) throws IOException {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
            "string_required");
        return value.getAsString();
    }
}
