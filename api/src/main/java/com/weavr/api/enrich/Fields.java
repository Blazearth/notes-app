package com.weavr.api.enrich;

import java.util.Map;

/**
 * Reading and writing {@code structured_data} values, with the registry's
 * conventions applied in one place.
 */
final class Fields {

    /**
     * The registry's marker for "the content genuinely did not say".
     *
     * <p>It is why enrichment needs a reader rather than a {@code Map.get}: a
     * field holding {@code [unclear]} is <em>present</em> but empty, and
     * treating it as a real value would both skip filling it and send the
     * literal string to TMDB as a search query.
     */
    static final String UNCLEAR = "[unclear]";

    private Fields() {
    }

    /** The field's value, or null when absent, blank, or {@code [unclear]}. */
    static String text(Map<String, Object> data, String key) {
        Object raw = data == null ? null : data.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || UNCLEAR.equalsIgnoreCase(value) ? null : value;
    }

    /** True when the field is worth filling in — missing, blank, or {@code [unclear]}. */
    static boolean isGap(Map<String, Object> data, String key) {
        return text(data, key) == null;
    }

    static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank() && !UNCLEAR.equalsIgnoreCase(value.trim())) {
            target.put(key, value.trim());
        }
    }
}
