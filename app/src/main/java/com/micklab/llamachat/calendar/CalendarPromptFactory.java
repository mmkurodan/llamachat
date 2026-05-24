package com.micklab.llamachat.calendar;

import android.content.Context;

import com.micklab.llamachat.ExpertPromptStore;

public final class CalendarPromptFactory {
    private CalendarPromptFactory() {
    }

    public static String buildExplainSystemPrompt(Context context) {
        return ExpertPromptStore.buildCalendarExplainSystemPrompt(context);
    }

    public static String buildExplainUserPrompt(Context context,
                                                String originalUserInput,
                                                CalendarResultForChat resultForChat,
                                                String searchResultsBlock) {
        return ExpertPromptStore.buildCalendarExplainUserPrompt(context, originalUserInput, resultForChat, searchResultsBlock);
    }
}
