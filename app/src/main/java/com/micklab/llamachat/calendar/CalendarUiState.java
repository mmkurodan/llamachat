package com.micklab.llamachat.calendar;

import com.google.api.services.calendar.model.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalendarUiState {
    private final boolean loading;
    private final List<Event> events;
    private final Event createdEvent;
    private final Event updatedEvent;
    private final boolean deleted;
    private final String errorType;

    public CalendarUiState(
            boolean loading,
            List<Event> events,
            Event createdEvent,
            Event updatedEvent,
            boolean deleted,
            String errorType
    ) {
        this.loading = loading;
        this.events = events == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(events));
        this.createdEvent = createdEvent;
        this.updatedEvent = updatedEvent;
        this.deleted = deleted;
        this.errorType = errorType;
    }

    public boolean isLoading() {
        return loading;
    }

    public List<Event> getEvents() {
        return events;
    }

    public Event getCreatedEvent() {
        return createdEvent;
    }

    public Event getUpdatedEvent() {
        return updatedEvent;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public String getErrorType() {
        return errorType;
    }
}
