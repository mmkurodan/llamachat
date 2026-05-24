package com.micklab.llamachat.calendar;

import android.content.Context;

import com.micklab.llamachat.ExpertType;

import java.util.Collections;
import java.util.List;

public class CalendarRetryHandler {
    private static final int MAX_RETRY_COUNT = 2;

    public interface JudgeModelClient {
        String request(String prompt) throws Exception;
    }

    private final Context context;
    private final JudgeModelClient judgeModelClient;

    public CalendarRetryHandler(Context context, JudgeModelClient judgeModelClient) {
        this.context = context == null ? null : context.getApplicationContext();
        this.judgeModelClient = judgeModelClient;
    }

    public CalendarActionJson resolveAction(String userInput, ExpertType expertType, String nowIso8601) {
        List<String> reasons = Collections.emptyList();
        for (int attempt = 0; attempt <= MAX_RETRY_COUNT; attempt++) {
            String prompt = attempt == 0
                    ? CalendarActionParser.buildJudgePrompt(context, expertType, userInput, nowIso8601)
                    : CalendarActionParser.buildRetryPrompt(context, expertType, userInput, nowIso8601, reasons);
            try {
                CalendarDebugLogger.log(context,
                        "calendarJudge attempt=" + (attempt + 1)
                                + ", retry=" + (attempt > 0)
                                + ", expertType=" + (expertType == null ? "null" : expertType.name()));
                String rawResponse = judgeModelClient.request(prompt);
                CalendarJsonValidator.ValidationResult validation =
                        CalendarJsonValidator.validateGenerateApiResponse(rawResponse);
                if (validation.isValid()) {
                    CalendarActionJson action = validation.getAction().withRawTextFallback(userInput);
                    CalendarDebugLogger.log(context,
                            "calendarJudge validated action=" + action.getAction()
                                    + ", title=" + action.getTitle()
                                    + ", start=" + action.getStart()
                                    + ", end=" + action.getEnd()
                                    + ", eventId=" + action.getEventId());
                    return action;
                }
                reasons = validation.getReasons();
                CalendarDebugLogger.log(context,
                        "calendarJudge validation failed attempt=" + (attempt + 1)
                                + ", reasons=" + joinReasons(reasons));
            } catch (Exception e) {
                reasons = Collections.singletonList(
                        e.getMessage() == null || e.getMessage().trim().isEmpty()
                                ? "判定モデルへの再送に失敗しました"
                                : e.getMessage().trim()
                );
                CalendarDebugLogger.logError(context, "calendarJudge request failed", e);
            }
        }
        CalendarDebugLogger.log(context,
                "calendarJudge fallback NONE reasons=" + joinReasons(reasons));
        return CalendarActionJson.none(userInput, joinReasons(reasons));
    }

    private String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (String reason : reasons) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(reason == null ? "unknown" : reason);
        }
        return sb.toString();
    }
}
