package com.micklab.llamachat.calendar;

import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CalendarJsonValidator {
    private static final String[] REQUIRED_KEYS = new String[]{
            "action", "title", "start", "end", "eventId", "additional"
    };
    private static final String[] FORBIDDEN_TITLE_WORDS = new String[]{
            "予定", "イベント", "日程", "スケジュール"
    };
    private static final Pattern ABSOLUTE_HOUR_DURATION_PATTERN = Pattern.compile(
            "(?:(本日|今日)(?:の)?\\s*)?(午前|午後)?\\s*(\\d{1,2})時(?:\\s*(\\d{1,2})分)?\\s*(?:から|より)\\s*(\\d{1,2})時間"
    );
    // 「N時から」「N時に」「午後N時」など duration なしの時刻表現（「N時間」は除外）
    private static final Pattern TIME_OF_DAY_PATTERN = Pattern.compile(
            "(?:午前|午後)?\\s*\\d{1,2}時(?!\\s*間)(?:\\s*\\d{1,2}分)?(?:\\s*(?:から|より|に|ごろ|頃|〜))?"
    );
    private static final Pattern ISO_IN_TEXT_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?((?:Z)|(?:[+-]\\d{2}:\\d{2}))"
    );

    private CalendarJsonValidator() {
    }

    public static ValidationResult validateGenerateApiResponse(String rawApiResponse) {
        try {
            String judgeJson = CalendarActionJson.extractGenerateResponseJson(rawApiResponse);
            JSONObject json = CalendarActionJson.parseStrictJsonObject(judgeJson);
            return validateJsonObject(json);
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "JSON を解析できません。"
                    : e.getMessage().trim();
            return ValidationResult.invalid(Collections.singletonList(message));
        }
    }

    public static ValidationResult validateJsonObject(JSONObject json) {
        List<String> reasons = new ArrayList<>();
        validateRequiredKeys(json, reasons);

        CalendarActionType actionType = validateAction(json, reasons);
        JSONObject additionalJson = json == null ? null : json.optJSONObject("additional");
        if (json != null && json.has("additional") && additionalJson == null) {
            reasons.add("additional は JSON オブジェクトである必要があります");
        }

        String title = CalendarActionJson.optNullableString(json, "title", null);
        validateTitle(title, reasons);

        String start = CalendarActionJson.optNullableString(json, "start", null);
        String end = CalendarActionJson.optNullableString(json, "end", null);
        String rawText = additionalJson == null
                ? ""
                : CalendarActionJson.optNullableString(additionalJson, "rawText", "");

        if (actionType == CalendarActionType.DELETE && (start != null || end != null)) {
            reasons.add("DELETE なのに start/end が null ではありません");
        }

        // CREATE で rawText に時刻表現があるのに start が null なら再判定。
        if (actionType == CalendarActionType.CREATE
                && start == null
                && rawText != null
                && (ABSOLUTE_HOUR_DURATION_PATTERN.matcher(rawText).find()
                    || TIME_OF_DAY_PATTERN.matcher(rawText).find())) {
            reasons.add("時刻が指定されているのに start が null です。start を ISO8601 で出力してください（end が不明なら null でよい）。");
        }

        validateIso8601Format(start, "start", reasons);
        validateIso8601Format(end, "end", reasons);
        validateTimeRange(start, end, reasons);
        validateAbsoluteTimeRule(rawText, start, end, reasons);
        validateIsoTimezoneRule(rawText, start, reasons);

        if (!reasons.isEmpty()) {
            return ValidationResult.invalid(reasons);
        }
        try {
            return ValidationResult.valid(CalendarActionJson.fromJsonObject(json).withRawTextFallback(rawText));
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "JSON を CalendarActionJson に変換できません"
                    : e.getMessage().trim();
            return ValidationResult.invalid(Collections.singletonList(message));
        }
    }

    private static void validateRequiredKeys(JSONObject json, List<String> reasons) {
        if (json == null) {
            reasons.add("JSON オブジェクトがありません");
            return;
        }
        for (String key : REQUIRED_KEYS) {
            if (!json.has(key)) {
                reasons.add("必須キー " + key + " がありません");
            }
        }
    }

    private static CalendarActionType validateAction(JSONObject json, List<String> reasons) {
        String actionValue = CalendarActionJson.optNullableString(json, "action", null);
        if (actionValue == null) {
            reasons.add("action がありません");
            return CalendarActionType.NONE;
        }
        CalendarActionType actionType = CalendarActionType.fromString(actionValue);
        if (actionType == CalendarActionType.NONE && !"NONE".equalsIgnoreCase(actionValue)) {
            reasons.add("action が不正です: " + actionValue);
        }
        return actionType;
    }

    private static void validateTitle(String title, List<String> reasons) {
        if (title == null) {
            return;
        }
        for (String forbidden : FORBIDDEN_TITLE_WORDS) {
            if (title.contains(forbidden)) {
                reasons.add("title に禁止語「" + forbidden + "」が含まれています");
            }
        }
    }

    private static void validateIso8601Format(String value, String fieldName, List<String> reasons) {
        if (value == null) return;
        try {
            CalendarRepository.parseOffsetDateTimeValue(value);
        } catch (DateTimeParseException e) {
            reasons.add(fieldName + " が ISO8601 形式ではありません（\"" + value + "\"）。現在日時を起点に正しい ISO8601 で出力してください。");
        }
    }

    private static void validateTimeRange(String start, String end, List<String> reasons) {
        if (start == null || end == null) {
            return;
        }
        try {
            OffsetDateTime parsedStart = CalendarRepository.parseOffsetDateTimeValue(start);
            OffsetDateTime parsedEnd = CalendarRepository.parseOffsetDateTimeValue(end);
            if (!parsedEnd.isAfter(parsedStart)) {
                reasons.add("end が start より前か同時刻です");
            }
        } catch (DateTimeParseException e) {
            reasons.add("start/end の ISO8601 解析に失敗しました");
        }
    }

    private static void validateAbsoluteTimeRule(String rawText, String start, String end, List<String> reasons) {
        if (rawText == null || start == null || end == null) {
            return;
        }
        Matcher matcher = ABSOLUTE_HOUR_DURATION_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return;
        }
        try {
            OffsetDateTime parsedStart = CalendarRepository.parseOffsetDateTimeValue(start);
            OffsetDateTime parsedEnd = CalendarRepository.parseOffsetDateTimeValue(end);
            int expectedHour = resolveHour(matcher.group(2), matcher.group(3));
            int expectedMinute = parseMinute(matcher.group(4));
            int expectedDurationHours = Integer.parseInt(matcher.group(5));
            if (parsedStart.getHour() != expectedHour || parsedStart.getMinute() != expectedMinute) {
                reasons.add("絶対時間「" + matcher.group(0) + "」と start が一致していません");
            }
            if (!parsedEnd.equals(parsedStart.plusHours(expectedDurationHours))) {
                reasons.add("絶対時間「" + matcher.group(0) + "」と end が一致していません");
            }
        } catch (Exception e) {
            reasons.add("絶対時間の検証に失敗しました");
        }
    }

    private static void validateIsoTimezoneRule(String rawText, String start, List<String> reasons) {
        if (rawText == null || start == null) {
            return;
        }
        Matcher matcher = ISO_IN_TEXT_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return;
        }
        try {
            OffsetDateTime rawDateTime = OffsetDateTime.parse(matcher.group(0));
            OffsetDateTime parsedStart = CalendarRepository.parseOffsetDateTimeValue(start);
            if (!rawDateTime.toLocalDateTime().equals(parsedStart.toLocalDateTime())
                    || !rawDateTime.getOffset().equals(parsedStart.getOffset())) {
                reasons.add("タイムゾーンが加算された可能性があります");
            }
        } catch (DateTimeParseException e) {
            reasons.add("タイムゾーン付き時刻の検証に失敗しました");
        }
    }

    private static int resolveHour(String meridiem, String hourText) {
        int hour = Integer.parseInt(hourText);
        if ("午後".equals(meridiem) && hour < 12) {
            return hour + 12;
        }
        if ("午前".equals(meridiem) && hour == 12) {
            return 0;
        }
        return hour;
    }

    private static int parseMinute(String minuteText) {
        return minuteText == null || minuteText.trim().isEmpty()
                ? 0
                : Integer.parseInt(minuteText);
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final CalendarActionJson action;
        private final List<String> reasons;

        private ValidationResult(boolean valid, CalendarActionJson action, List<String> reasons) {
            this.valid = valid;
            this.action = action;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }

        public static ValidationResult valid(CalendarActionJson action) {
            return new ValidationResult(true, action, Collections.emptyList());
        }

        public static ValidationResult invalid(List<String> reasons) {
            return new ValidationResult(false, null, reasons == null ? Collections.emptyList() : reasons);
        }

        public boolean isValid() {
            return valid;
        }

        public CalendarActionJson getAction() {
            return action;
        }

        public List<String> getReasons() {
            return reasons;
        }
    }
}
