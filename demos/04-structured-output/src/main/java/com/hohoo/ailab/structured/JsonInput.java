package com.hohoo.ailab.structured;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Response decoding and syntax checks, separate from the classification contract. */
final class JsonInput {
    private static final int MAX_CONTAINERS = 64;
    private JsonInput() {}

    static JsonObject parseResponse(byte[] bytes) throws IOException {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("invalid_response_utf8", e);
        }
        try {
            checkLexical(text);
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                consumeValue(reader, 0);
                require(reader.peek() == JsonToken.END_DOCUMENT, "json_trailing_content");
            }
            // Gson 2.10.1's tree parser enables leniency. Only give it text
            // already checked for syntax, duplicate names and bounded depth.
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IOException("invalid_response_json", e);
        }
    }

    private static void consumeValue(JsonReader reader, int depth) throws IOException {
        switch (reader.peek()) {
        case BEGIN_OBJECT:
            require(depth < MAX_CONTAINERS, "json_too_deep");
            reader.beginObject();
            Set<String> names = new HashSet<String>();
            while (reader.hasNext()) {
                require(names.add(reader.nextName()), "json_duplicate_field");
                consumeValue(reader, depth + 1);
            }
            reader.endObject();
            return;
        case BEGIN_ARRAY:
            require(depth < MAX_CONTAINERS, "json_too_deep");
            reader.beginArray();
            while (reader.hasNext()) consumeValue(reader, depth + 1);
            reader.endArray();
            return;
        case STRING:
        case NUMBER:
            reader.nextString(); // The token was checked; do not coerce a business field.
            return;
        case BOOLEAN:
            reader.nextBoolean();
            return;
        case NULL:
            reader.nextNull();
            return;
        default:
            throw new IOException("json_value_required");
        }
    }

    // Gson 2.10.1 accepts some non-JSON string escapes even with lenient=false.
    // This checks spelling only; JsonReader and the contract below still check structure.
    static void checkLexical(String text) throws IOException {
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inString) {
                if (c == '"') { inString = true; continue; }
                // Legacy JsonReader accepts case-insensitive keywords in strict mode.
                // Only standard JSON spellings are allowed. It still checks number
                // grammar and structure; this screen is not a second JSON parser.
                if (c == 't' || c == 'f' || c == 'n') {
                    String keyword = c == 't' ? "true" : c == 'f' ? "false" : "null";
                    require(text.startsWith(keyword, i), "json_keyword");
                    i += keyword.length() - 1;
                } else {
                    // RFC 8259 permits a parser to ignore an initial UTF-8 BOM.
                    require((i == 0 && c == 0xfeff) || "{}[],:-+0123456789.eE \t\r\n".indexOf(c) >= 0,
                        "json_character");
                }
                continue;
            }
            if (c == '"') { inString = false; continue; }
            require(c >= 0x20, "json_control_character");
            if (c != '\\') continue;
            require(++i < text.length(), "json_escape");
            char escaped = text.charAt(i);
            if (escaped == 'u') {
                require(i + 4 < text.length(), "json_unicode_escape");
                for (int end = i + 4; i < end;) {
                    char digit = text.charAt(++i);
                    require((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f')
                        || (digit >= 'A' && digit <= 'F'), "json_unicode_escape");
                }
            } else {
                require("\"\\/bfnrt".indexOf(escaped) >= 0, "json_escape");
            }
        }
        require(!inString, "json_unterminated_string");
    }

    private static void require(boolean ok, String message) throws IOException {
        if (!ok) throw new IOException(message);
    }
}
