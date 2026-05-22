package com.micklab.llamachat.calendar;

import org.json.JSONObject;

public class CalendarActionJson {
    private final CalendarActionType action;
    private final String title;
    private final String start;
    private final String end;
    private final String eventId;
    private final CalendarAdditional additional;

    public CalendarActionJson(
            CalendarActionType action,
            String title,
            String start,
            String end,
            String eventId,
            CalendarAdditional additional
    ) {
        this.action = action == null ? CalendarActionType.NONE : action;
        this.title = title;
        this.start = start;
        this.end = end;
        this.eventId = eventId;
        this.additional = additional == null ? new CalendarAdditional("", null) : additional;
    }

    public CalendarActionType getAction() {
        return action;
    }

    public String getTitle() {
        return title;
    }

    public String getStart() {
        return start;
    }

    public String getEnd() {
        return end;
    }

    public String getEventId() {
        return eventId;
    }

    public CalendarAdditional getAdditional() {
        return additional;
    }

    public CalendarActionJson withRawTextFallback(String userInput) {
        return new CalendarActionJson(
                action,
                title,
                start,
                end,
                eventId,
                additional.withRawTextFallback(userInput)
        );
    }

    public static CalendarActionJson fromJsonString(String raw) throws Exception {
        JSONObject json = new JSONObject(sanitizeJson(raw));
        return new CalendarActionJson(
                CalendarActionType.fromString(json.optString("action", "NONE")),
                optNullableString(json, "title", null),
                optNullableString(json, "start", null),
                optNullableString(json, "end", null),
                optNullableString(json, "eventId", null),
                CalendarAdditional.fromJson(json.optJSONObject("additional"))
        );
    }

    static String optNullableString(JSONObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        String value = json.optString(key, fallback);
        if (value == null) return fallback;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return fallback;
        }
        return trimmed;
    }

    private static String sanitizeJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            if (firstNewLine >= 0) {
                trimmed = trimmed.substring(firstNewLine + 1).trim();
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            trimmed = trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
