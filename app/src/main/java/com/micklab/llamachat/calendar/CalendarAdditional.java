package com.micklab.llamachat.calendar;

import org.json.JSONObject;

public class CalendarAdditional {
    private final String rawText;
    private final String targetQuery;
    private final String notes;

    public CalendarAdditional(String rawText, String notes) {
        this(rawText, null, notes);
    }

    public CalendarAdditional(String rawText, String targetQuery, String notes) {
        this.rawText = rawText == null ? "" : rawText;
        this.targetQuery = normalize(targetQuery);
        this.notes = notes;
    }

    public String getRawText() {
        return rawText;
    }

    public String getNotes() {
        return notes;
    }

    public String getTargetQuery() {
        return targetQuery;
    }

    public CalendarAdditional withRawTextFallback(String fallback) {
        if (rawText != null && !rawText.trim().isEmpty()) {
            return this;
        }
        return new CalendarAdditional(fallback, targetQuery, notes);
    }

    public static CalendarAdditional fromJson(JSONObject json) {
        if (json == null) {
            return new CalendarAdditional("", null, null);
        }
        return new CalendarAdditional(
                CalendarActionJson.optNullableString(json, "rawText", ""),
                CalendarActionJson.optNullableString(json, "targetQuery", null),
                CalendarActionJson.optNullableString(json, "notes", null)
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
