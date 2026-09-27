package com.hohoo.ailab.structured;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit opt-in adapter. Only ONE complete json code fence is removable. */
public final class ContentFormat {
    private static final Pattern FENCE = Pattern.compile("\\A```json\\r?\\n([\\s\\S]*?)\\r?\\n```\\z");
    public static String unwrapOneJsonFence(String content) throws IOException {
        Classification.require(content != null && content.length() <= 8000, "content_missing_or_too_large");
        String trimmed = content.trim();
        Matcher match = FENCE.matcher(trimmed);
        if (!match.matches()) return content; // strict parser will reject prose and partial fences
        String inner = match.group(1);
        Classification.require(!inner.contains("```"), "multiple_or_nested_fences");
        return inner; // no key repair, no substring search, no category guessing
    }
}
