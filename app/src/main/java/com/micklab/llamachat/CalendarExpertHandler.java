package com.micklab.llamachat;

import com.micklab.llamachat.calendar.CalendarActionJson;
import com.micklab.llamachat.calendar.CalendarActionType;
import com.micklab.llamachat.calendar.CalendarAdditional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CalendarExpertHandler {
    private static final DateTimeFormatter RFC3339_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern DATE_DURATION_PATTERN = Pattern.compile(
            "(?:(今日|本日|明日|あした|明後日|あさって)(?:の)?\\s*)?(午前|午後)?\\s*(\\d{1,2})時(?:\\s*(\\d{1,2})分)?\\s*(?:から|より)\\s*(\\d{1,2})時間(半)?"
    );
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(?:(今日|本日|明日|あした|明後日|あさって)(?:の)?\\s*)?(午前|午後)?\\s*(\\d{1,2})時(?:\\s*(\\d{1,2})分)?\\s*(?:から|より|〜|~|-|ー)\\s*(午前|午後)?\\s*(\\d{1,2})時(?:\\s*(\\d{1,2})分)?"
    );
    private static final Pattern RELATIVE_DURATION_PATTERN = Pattern.compile(
            "今から\\s*(\\d{1,2})時間(半)?"
    );
    // 「N時から」「N時に」など終了時刻・時間指定なしの時刻表現（「N時間」は除外）
    private static final Pattern TIME_ONLY_PATTERN = Pattern.compile(
            "(?:(今日|本日|明日|あした|明後日|あさって)(?:の)?\\s*)?(午前|午後)?\\s*(\\d{1,2})時(?!\\s*間)(?:\\s*(\\d{1,2})分)?\\s*(?:から|に|ごろ|頃)?"
    );
    private static final Pattern TITLE_BEFORE_SCHEDULE_PATTERN = Pattern.compile(
            "(.+?)(?:の)?(?:予定|イベント|日程|スケジュール)"
    );

    public interface CalendarModelResolver {
        CalendarActionJson resolve(String userInput, ExpertType expertType) throws Exception;
    }

    public CalendarActionJson resolveAction(String userInput,
                                            ExpertType expertType,
                                            CalendarModelResolver modelResolver) throws Exception {
        if (expertType == ExpertType.CALENDAR_CREATE) {
            return buildDirectCreateAction(userInput);
        }
        if ((expertType == ExpertType.CALENDAR_QUERY
                || expertType == ExpertType.CALENDAR_UPDATE
                || expertType == ExpertType.CALENDAR_DELETE)
                && modelResolver != null) {
            return modelResolver.resolve(userInput, expertType);
        }
        return CalendarActionJson.none(userInput, "calendar expert not selected");
    }

    private CalendarActionJson buildDirectCreateAction(String userInput) {
        TimeRange timeRange = parseTimeRange(userInput);
        String title = extractTitle(userInput, timeRange);
        return new CalendarActionJson(
                CalendarActionType.CREATE,
                title,
                timeRange == null ? null : timeRange.start.format(RFC3339_SECONDS_FORMATTER),
                timeRange == null ? null : timeRange.end.format(RFC3339_SECONDS_FORMATTER),
                null,
                new CalendarAdditional(userInput, "keyword-based calendar create")
        );
    }

    private TimeRange parseTimeRange(String userInput) {
        String source = userInput == null ? "" : userInput;
        OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0);

        Matcher durationMatcher = DATE_DURATION_PATTERN.matcher(source);
        if (durationMatcher.find()) {
            OffsetDateTime start = createDateTime(
                    now,
                    durationMatcher.group(1),
                    durationMatcher.group(2),
                    durationMatcher.group(3),
                    durationMatcher.group(4)
            );
            int durationMinutes = Integer.parseInt(durationMatcher.group(5)) * 60
                    + (durationMatcher.group(6) == null ? 0 : 30);
            return new TimeRange(durationMatcher.group(0), start, start.plusMinutes(durationMinutes));
        }

        Matcher rangeMatcher = DATE_RANGE_PATTERN.matcher(source);
        if (rangeMatcher.find()) {
            OffsetDateTime start = createDateTime(
                    now,
                    rangeMatcher.group(1),
                    rangeMatcher.group(2),
                    rangeMatcher.group(3),
                    rangeMatcher.group(4)
            );
            String endMeridiem = rangeMatcher.group(5) == null ? rangeMatcher.group(2) : rangeMatcher.group(5);
            OffsetDateTime end = createDateTime(
                    now,
                    rangeMatcher.group(1),
                    endMeridiem,
                    rangeMatcher.group(6),
                    rangeMatcher.group(7)
            );
            if (!end.isAfter(start)) {
                end = end.plusDays(1);
            }
            return new TimeRange(rangeMatcher.group(0), start, end);
        }

        Matcher relativeMatcher = RELATIVE_DURATION_PATTERN.matcher(source);
        if (relativeMatcher.find()) {
            int durationMinutes = Integer.parseInt(relativeMatcher.group(1)) * 60
                    + (relativeMatcher.group(2) == null ? 0 : 30);
            return new TimeRange(relativeMatcher.group(0), now, now.plusMinutes(durationMinutes));
        }

        Matcher timeOnlyMatcher = TIME_ONLY_PATTERN.matcher(source);
        if (timeOnlyMatcher.find()) {
            OffsetDateTime start = createDateTime(
                    now,
                    timeOnlyMatcher.group(1),
                    timeOnlyMatcher.group(2),
                    timeOnlyMatcher.group(3),
                    timeOnlyMatcher.group(4)
            );
            return new TimeRange(timeOnlyMatcher.group(0), start, start.plusHours(1));
        }

        return null;
    }

    private OffsetDateTime createDateTime(OffsetDateTime reference,
                                          String dateWord,
                                          String meridiem,
                                          String hourText,
                                          String minuteText) {
        LocalDate date = resolveDate(reference.toLocalDate(), dateWord);
        int hour = resolveHour(meridiem, hourText);
        int minute = parseMinute(minuteText);
        return OffsetDateTime.of(date, LocalTime.of(hour, minute), reference.getOffset());
    }

    private LocalDate resolveDate(LocalDate baseDate, String dateWord) {
        if ("明日".equals(dateWord) || "あした".equals(dateWord)) {
            return baseDate.plusDays(1);
        }
        if ("明後日".equals(dateWord) || "あさって".equals(dateWord)) {
            return baseDate.plusDays(2);
        }
        return baseDate;
    }

    private int resolveHour(String meridiem, String hourText) {
        int hour = Integer.parseInt(hourText);
        if ("午後".equals(meridiem) && hour < 12) {
            return hour + 12;
        }
        if ("午前".equals(meridiem) && hour == 12) {
            return 0;
        }
        return hour;
    }

    private int parseMinute(String minuteText) {
        return minuteText == null || minuteText.trim().isEmpty()
                ? 0
                : Integer.parseInt(minuteText);
    }

    private String extractTitle(String userInput, TimeRange timeRange) {
        String working = userInput == null ? "" : userInput;
        if (timeRange != null && timeRange.matchedText != null) {
            working = working.replace(timeRange.matchedText, " ");
        }
        Matcher scheduleMatcher = TITLE_BEFORE_SCHEDULE_PATTERN.matcher(working);
        if (scheduleMatcher.find()) {
            String candidate = cleanTitle(scheduleMatcher.group(1));
            if (candidate != null) {
                return candidate;
            }
        }

        working = working
                .replaceAll("(今日|本日|明日|あした|明後日|あさって)", " ")
                .replaceAll("今から\\s*\\d{1,2}時間半?", " ")
                .replaceAll("(午前|午後)?\\s*\\d{1,2}時(?:\\s*\\d{1,2}分)?", " ")
                .replaceAll("(から|より|〜|~|-|ー)\\s*", " ")
                .replaceAll("(予定を入れたい|予定したい|予定する|予定して欲しい|予定してほしい|登録して|追加して|入れて)", " ")
                .replaceAll("(登録|追加|入れ)(?:たい|る|てほしい|て欲しい)?", " ")
                .replaceAll("(してください|して下さい|お願いします|お願い|してほしい|して欲しい)", " ")
                .replaceAll("(予定|イベント|日程|スケジュール)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return cleanTitle(working);
    }

    private String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw
                .replaceAll("^[、。\\s]+", "")
                .replaceAll("[、。\\s]+$", "")
                .replaceAll("^[をにのはがと]+", "")
                .replaceAll("[をにのはがと]+$", "")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if ("予定".equals(cleaned) || "イベント".equals(cleaned)
                || "日程".equals(cleaned) || "スケジュール".equals(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private static final class TimeRange {
        private final String matchedText;
        private final OffsetDateTime start;
        private final OffsetDateTime end;

        private TimeRange(String matchedText, OffsetDateTime start, OffsetDateTime end) {
            this.matchedText = matchedText;
            this.start = start;
            this.end = end;
        }
    }
}
