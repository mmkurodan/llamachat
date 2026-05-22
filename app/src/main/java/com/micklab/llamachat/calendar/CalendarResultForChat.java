package com.micklab.llamachat.calendar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalendarResultForChat {
    private final String originalPrompt;
    private final String action;
    private final boolean success;
    private final String errorType;
    private final String messageForSystem;
    private final List<String> eventSummaries;

    public CalendarResultForChat(
            String originalPrompt,
            String action,
            boolean success,
            String errorType,
            String messageForSystem,
            List<String> eventSummaries
    ) {
        this.originalPrompt = originalPrompt == null ? "" : originalPrompt;
        this.action = action == null ? CalendarActionType.NONE.name() : action;
        this.success = success;
        this.errorType = errorType;
        this.messageForSystem = messageForSystem == null ? "" : messageForSystem;
        this.eventSummaries = eventSummaries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(eventSummaries));
    }

    public String getOriginalPrompt() {
        return originalPrompt;
    }

    public String getAction() {
        return action;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getMessageForSystem() {
        return messageForSystem;
    }

    public List<String> getEventSummaries() {
        return eventSummaries;
    }
}
