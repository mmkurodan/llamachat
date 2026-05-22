package com.micklab.llamachat.calendar;

import java.util.Locale;

public enum CalendarActionType {
    NONE,
    QUERY,
    CREATE,
    UPDATE,
    DELETE;

    public static CalendarActionType fromString(String value) {
        if (value == null) return NONE;
        try {
            return CalendarActionType.valueOf(value.trim().toUpperCase(Locale.US));
        } catch (Exception ignored) {
            return NONE;
        }
    }

    public boolean requiresCalendarOperation() {
        return this != NONE;
    }
}
