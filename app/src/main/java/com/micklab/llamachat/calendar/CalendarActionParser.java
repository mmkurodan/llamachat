package com.micklab.llamachat.calendar;

import android.content.Context;

import com.micklab.llamachat.ExpertPromptStore;
import com.micklab.llamachat.ExpertType;

import java.util.List;

public final class CalendarActionParser {
    private CalendarActionParser() {
    }

    public static String buildJudgePrompt(Context context,
                                          ExpertType expertType,
                                          String userInput,
                                          String nowIso8601) {
        return ExpertPromptStore.buildCalendarJudgePrompt(context, expertType, userInput, nowIso8601);
    }

    public static String buildRetryPrompt(Context context,
                                          ExpertType expertType,
                                          String userInput,
                                          String nowIso8601,
                                          List<String> reasons) {
        return ExpertPromptStore.buildCalendarRetryPrompt(context, expertType, userInput, nowIso8601, reasons);
    }
}
