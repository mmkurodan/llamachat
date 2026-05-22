package com.micklab.llamachat.calendar;

import org.json.JSONException;
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
        JSONObject json = new JSONObject(requireStrictJsonObject(raw));
        return new CalendarActionJson(
                CalendarActionType.fromString(json.optString("action", "NONE")),
                optNullableString(json, "title", null),
                optNullableString(json, "start", null),
                optNullableString(json, "end", null),
                optNullableString(json, "eventId", null),
                CalendarAdditional.fromJson(json.optJSONObject("additional"))
        );
    }

    public static CalendarActionJson fromGenerateApiResponse(String rawApiResponse, String userInput) throws Exception {
        return fromJsonString(extractGenerateResponseJson(rawApiResponse)).withRawTextFallback(userInput);
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

    private static String extractGenerateResponseJson(String rawApiResponse) throws Exception {
        String trimmed = rawApiResponse == null ? "" : rawApiResponse.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Judge API response is empty.");
        }
        try {
            JSONObject root = new JSONObject(trimmed);
            if (looksLikeJudgeObject(root)) {
                return requireStrictJsonObject(trimmed);
            }
            Object responseValue = root.opt("response");
            if (!(responseValue instanceof String)) {
                throw new IllegalArgumentException("Judge API response does not contain a valid response field.");
            }
            return requireStrictJsonObject((String) responseValue);
        } catch (JSONException ignored) {
            return requireStrictJsonObject(trimmed);
        }
    }

    private static boolean looksLikeJudgeObject(JSONObject json) {
        return json != null && json.has("action");
    }

    private static String requireStrictJsonObject(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Judge output is empty.");
        }
        if (trimmed.startsWith("```")) {
            throw new IllegalArgumentException("Judge output must not contain Markdown.");
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            throw new IllegalArgumentException("Judge output must be a JSON object, not a quoted string.");
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Judge output must be exactly one JSON object.");
        }
        return trimmed;
    }
}
