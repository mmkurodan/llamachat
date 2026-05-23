package com.micklab.llamachat.calendar;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class CalendarRepository {
    private static final String TAG = "CalendarRepository";
    private static final String PRIMARY_CALENDAR_ID = "primary";
    private static final DateTimeFormatter RFC3339_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern ISO_OFFSET_PATTERN =
            Pattern.compile(".*(?:Z|[+-]\\d{2}:\\d{2})$");

    private final Context appContext;
    private String lastErrorType;
    private String lastErrorDetail;

    public CalendarRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized boolean isSignedIn() {
        return GoogleSignIn.getLastSignedInAccount(appContext) != null;
    }

    public synchronized boolean hasReadAccess() {
        return CalendarSignInHelper.hasReadAccess(GoogleSignIn.getLastSignedInAccount(appContext));
    }

    public synchronized boolean hasWriteAccess() {
        return CalendarSignInHelper.hasWriteAccess(GoogleSignIn.getLastSignedInAccount(appContext));
    }

    public synchronized String getLastErrorType() {
        return lastErrorType;
    }

    public synchronized String getLastErrorDetail() {
        return lastErrorDetail;
    }

    public synchronized void clearLastError() {
        lastErrorType = null;
        lastErrorDetail = null;
    }

    public List<Event> queryEvents(String title, String startIso, String endIso, int maxResults) {
        clearLastError();
        try {
            CalendarDebugLogger.log(appContext,
                    "queryEvents start title=" + safe(title)
                            + ", start=" + safe(startIso)
                            + ", end=" + safe(endIso)
                            + ", maxResults=" + maxResults);
            Calendar service = CalendarServiceFactory.createReadService(appContext);
            if (service == null) {
                setLastError("AUTH_ERROR", "Calendar read service could not be created.");
                return Collections.emptyList();
            }
            Events events = executeQuery(service, title, startIso, endIso, maxResults);
            List<Event> items = events.getItems();
            CalendarDebugLogger.log(appContext,
                    "queryEvents success count=" + (items == null ? 0 : items.size()));
            return items == null ? Collections.emptyList() : new ArrayList<>(items);
        } catch (Exception e) {
            recordError("queryEvents", e);
            return Collections.emptyList();
        }
    }

    public Event createEvent(String title, String startIso, String endIso, String notes) {
        clearLastError();
        try {
            CalendarDebugLogger.log(appContext,
                    "createEvent start title=" + safe(title)
                            + ", start=" + safe(startIso)
                            + ", end=" + safe(endIso)
                            + ", notes=" + safe(notes));
            Calendar service = CalendarServiceFactory.createWriteService(appContext);
            if (service == null) {
                setLastError("AUTH_ERROR", "Calendar write service could not be created.");
                return null;
            }
            if (!isValidTimeRange(startIso, endIso)) {
                setLastError("INVALID_TIME_RANGE",
                        "Calendar event end time must be after the start time.");
                CalendarDebugLogger.log(appContext,
                        "createEvent invalid time range start=" + safe(startIso)
                                + ", end=" + safe(endIso));
                return null;
            }
            Event event = new Event();
            event.setSummary(title == null ? null : title.trim());
            if (notes != null && !notes.trim().isEmpty()) {
                event.setDescription(notes.trim());
            }
            event.setStart(buildEventDateTime(startIso));
            event.setEnd(buildEventDateTime(endIso));
            Event created = service.events().insert(PRIMARY_CALENDAR_ID, event).execute();
            CalendarDebugLogger.log(appContext,
                    "createEvent success id=" + safe(created == null ? null : created.getId())
                            + ", summary=" + summarizeEvent(created));
            return created;
        } catch (Exception e) {
            recordError("createEvent", e);
            return null;
        }
    }

    public Event updateEvent(String eventId, String title, String startIso, String endIso, String notes) {
        clearLastError();
        try {
            CalendarDebugLogger.log(appContext,
                    "updateEvent start eventId=" + safe(eventId)
                            + ", title=" + safe(title)
                            + ", start=" + safe(startIso)
                            + ", end=" + safe(endIso)
                            + ", notes=" + safe(notes));
            Calendar service = CalendarServiceFactory.createWriteService(appContext);
            if (service == null || eventId == null || eventId.trim().isEmpty()) {
                if (service == null) {
                    setLastError("AUTH_ERROR", "Calendar write service could not be created.");
                }
                return null;
            }
            Event event = service.events().get(PRIMARY_CALENDAR_ID, eventId).execute();
            if (event == null) {
                setLastError("NOT_FOUND", "Calendar event not found");
                return null;
            }
            if (title != null && !title.trim().isEmpty()) {
                event.setSummary(title.trim());
            }
            if (notes != null && !notes.trim().isEmpty()) {
                event.setDescription(notes.trim());
            }
            if (startIso != null && !startIso.trim().isEmpty()) {
                event.setStart(buildEventDateTime(startIso.trim()));
            }
            if (endIso != null && !endIso.trim().isEmpty()) {
                event.setEnd(buildEventDateTime(endIso.trim()));
            }
            if (!isValidTimeRange(
                    event.getStart() != null && event.getStart().getDateTime() != null
                            ? event.getStart().getDateTime().toStringRfc3339() : null,
                    event.getEnd() != null && event.getEnd().getDateTime() != null
                            ? event.getEnd().getDateTime().toStringRfc3339() : null
            )) {
                setLastError("INVALID_TIME_RANGE",
                        "Calendar event end time must be after the start time.");
                CalendarDebugLogger.log(appContext,
                        "updateEvent invalid time range eventId=" + safe(eventId));
                return null;
            }
            Event updated = service.events().update(PRIMARY_CALENDAR_ID, eventId, event).execute();
            CalendarDebugLogger.log(appContext,
                    "updateEvent success id=" + safe(updated == null ? null : updated.getId())
                            + ", summary=" + summarizeEvent(updated));
            return updated;
        } catch (Exception e) {
            recordError("updateEvent", e);
            return null;
        }
    }

    public boolean deleteEvent(String eventId) {
        clearLastError();
        try {
            CalendarDebugLogger.log(appContext, "deleteEvent start eventId=" + safe(eventId));
            Calendar service = CalendarServiceFactory.createWriteService(appContext);
            if (service == null || eventId == null || eventId.trim().isEmpty()) {
                if (service == null) {
                    setLastError("AUTH_ERROR", "Calendar write service could not be created.");
                }
                return false;
            }
            service.events().delete(PRIMARY_CALENDAR_ID, eventId).execute();
            CalendarDebugLogger.log(appContext, "deleteEvent success eventId=" + safe(eventId));
            return true;
        } catch (Exception e) {
            recordError("deleteEvent", e);
            return false;
        }
    }

    public Event resolveEvent(String eventId, String title, String startIso, String endIso) {
        return resolveEvent(eventId, title, startIso, endIso, false);
    }

    public Event resolveEventForWrite(String eventId, String title, String startIso, String endIso) {
        return resolveEvent(eventId, title, startIso, endIso, true);
    }

    private Event resolveEvent(String eventId, String title, String startIso, String endIso, boolean requireWriteAccess) {
        clearLastError();
        try {
            CalendarDebugLogger.log(appContext,
                    "resolveEvent start eventId=" + safe(eventId)
                            + ", title=" + safe(title)
                            + ", start=" + safe(startIso)
                            + ", end=" + safe(endIso)
                            + ", requireWriteAccess=" + requireWriteAccess);
            Calendar service = requireWriteAccess
                    ? CalendarServiceFactory.createWriteService(appContext)
                    : CalendarServiceFactory.createReadService(appContext);
            if (service == null) {
                setLastError("AUTH_ERROR", "Calendar service could not be created.");
                return null;
            }
            if (eventId != null && !eventId.trim().isEmpty()) {
                Event resolved = service.events().get(PRIMARY_CALENDAR_ID, eventId.trim()).execute();
                CalendarDebugLogger.log(appContext,
                        "resolveEvent by id success=" + summarizeEvent(resolved));
                return resolved;
            }
            Events events = executeQuery(service, title, startIso, endIso, 10);
            List<Event> candidates = events.getItems() == null
                    ? Collections.emptyList()
                    : new ArrayList<>(events.getItems());
            if (candidates.isEmpty()) {
                setLastError("NOT_FOUND", "No matching calendar event");
                CalendarDebugLogger.log(appContext, "resolveEvent no candidates");
                return null;
            }
            if (title != null && !title.trim().isEmpty()) {
                for (Event candidate : candidates) {
                    String summary = candidate.getSummary();
                    if (summary != null && summary.trim().equalsIgnoreCase(title.trim())) {
                        CalendarDebugLogger.log(appContext,
                                "resolveEvent exact title match=" + summarizeEvent(candidate));
                        return candidate;
                    }
                }
            }
            CalendarDebugLogger.log(appContext,
                    "resolveEvent fallback first candidate=" + summarizeEvent(candidates.get(0)));
            return candidates.get(0);
        } catch (Exception e) {
            recordError("resolveEvent", e);
            return null;
        }
    }

    private void recordError(String operation, Exception e) {
        Log.e(TAG, operation + " failed", e);
        CalendarDebugLogger.logError(appContext, operation + " failed", e);
        if (e instanceof GoogleJsonResponseException) {
            GoogleJsonResponseException responseException = (GoogleJsonResponseException) e;
            int code = responseException.getStatusCode();
            String detail = responseException.getDetails() != null
                    ? responseException.getDetails().getMessage()
                    : responseException.getMessage();
            if (code == 403) {
                setLastError("PERMISSION_DENIED", detail);
            } else if (code == 401) {
                setLastError("AUTH_ERROR", detail);
            } else if (code == 404) {
                setLastError("NOT_FOUND", detail);
            } else {
                setLastError("CALENDAR_HTTP_" + code, detail);
            }
        } else if (e instanceof UserRecoverableAuthIOException) {
            setLastError("AUTH_RECOVERY_REQUIRED", e.getMessage());
        } else if (e instanceof IOException) {
            setLastError("NETWORK_ERROR", e.getMessage());
        } else {
            setLastError("CALENDAR_ERROR", e.getMessage());
        }
    }

    private synchronized void setLastError(String errorType, String detail) {
        lastErrorType = errorType;
        lastErrorDetail = detail;
        CalendarDebugLogger.log(appContext,
                "setLastError type=" + safe(errorType) + ", detail=" + safe(detail));
    }

    private Events executeQuery(Calendar service, String title, String startIso, String endIso, int maxResults)
            throws IOException {
        com.google.api.services.calendar.Calendar.Events.List request =
                service.events().list(PRIMARY_CALENDAR_ID)
                        .setSingleEvents(true)
                        .setOrderBy("startTime")
                        .setMaxResults(Math.max(1, maxResults));
        if (startIso != null && !startIso.trim().isEmpty()) {
            request.setTimeMin(new DateTime(normalizeIsoValue(startIso.trim())));
        }
        if (endIso != null && !endIso.trim().isEmpty()) {
            request.setTimeMax(new DateTime(normalizeIsoValue(endIso.trim())));
        }
        if (title != null && !title.trim().isEmpty()) {
            request.setQ(title.trim());
        }
        return request.execute();
    }

    private boolean isValidTimeRange(String startIso, String endIso) {
        if (startIso == null || endIso == null) {
            return true;
        }
        try {
            return parseEpochMillis(endIso) > parseEpochMillis(startIso);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            setLastError("INVALID_TIME_RANGE", e.getMessage());
            CalendarDebugLogger.logError(appContext, "isValidTimeRange parse failed", e);
            return false;
        }
    }

    private EventDateTime buildEventDateTime(String isoValue) {
        String normalizedIso = normalizeIsoValue(isoValue);
        boolean hasExplicitOffset = hasExplicitOffset(normalizedIso);
        EventDateTime eventDateTime = new EventDateTime()
                .setDateTime(new DateTime(normalizedIso));
        if (!hasExplicitOffset) {
            eventDateTime.setTimeZone(java.util.TimeZone.getDefault().getID());
        }
        CalendarDebugLogger.log(appContext,
                "buildEventDateTime normalized=" + safe(normalizedIso)
                        + ", explicitOffset=" + hasExplicitOffset
                        + ", appliedTimeZone=" + safe(eventDateTime.getTimeZone()));
        return eventDateTime;
    }

    private long parseEpochMillis(String isoValue) {
        try {
            return parseOffsetDateTimeValue(isoValue).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return new DateTime(normalizeIsoValue(isoValue)).getValue();
        }
    }

    private String normalizeIsoValue(String isoValue) {
        if (isoValue == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(isoValue.trim()).format(RFC3339_SECONDS_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return isoValue.trim();
        }
    }

    private boolean hasExplicitOffset(String isoValue) {
        return hasExplicitOffsetValue(isoValue);
    }

    static OffsetDateTime parseOffsetDateTimeValue(String isoValue) {
        return OffsetDateTime.parse(normalizeIsoValueValue(isoValue));
    }

    static boolean hasExplicitOffsetValue(String isoValue) {
        return isoValue != null && ISO_OFFSET_PATTERN.matcher(isoValue.trim()).matches();
    }

    static String normalizeIsoValueValue(String isoValue) {
        if (isoValue == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(isoValue.trim()).format(RFC3339_SECONDS_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return isoValue.trim();
        }
    }

    private String summarizeEvent(Event event) {
        if (event == null) {
            return "(null)";
        }
        String start = null;
        if (event.getStart() != null) {
            if (event.getStart().getDateTime() != null) {
                start = event.getStart().getDateTime().toStringRfc3339();
            } else if (event.getStart().getDate() != null) {
                start = event.getStart().getDate().toStringRfc3339();
            }
        }
        return "id=" + safe(event.getId())
                + ", summary=" + safe(event.getSummary())
                + ", start=" + safe(start);
    }

    private String safe(String value) {
        return value == null ? "(null)" : value;
    }
}
