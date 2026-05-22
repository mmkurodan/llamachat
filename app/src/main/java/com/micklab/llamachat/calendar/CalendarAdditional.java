package com.micklab.llamachat.calendar;

import org.json.JSONObject;

public class CalendarAdditional {
    private final String rawText;
    private final String notes;

    public CalendarAdditional(String rawText, String notes) {
        this.rawText = rawText == null ? "" : rawText;
        this.notes = notes;
    }

    public String getRawText() {
        return rawText;
    }

    public String getNotes() {
        return notes;
    }

    public CalendarAdditional withRawTextFallback(String fallback) {
        if (rawText != null && !rawText.trim().isEmpty()) {
            return this;
        }
        return new CalendarAdditional(fallback, notes);
    }

    public static CalendarAdditional fromJson(JSONObject json) {
        if (json == null) {
            return new CalendarAdditional("", null);
        }
        return new CalendarAdditional(
                CalendarActionJson.optNullableString(json, "rawText", ""),
                CalendarActionJson.optNullableString(json, "notes", null)
        );
    }
}
