package com.micklab.llamachat.calendar;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalendarRepository {
    private static final String TAG = "CalendarRepository";
    private static final String PRIMARY_CALENDAR_ID = "primary";

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
            Calendar service = CalendarServiceFactory.createService(appContext);
            if (service == null) {
                return Collections.emptyList();
            }
            com.google.api.services.calendar.Calendar.Events.List request =
                    service.events().list(PRIMARY_CALENDAR_ID)
                            .setSingleEvents(true)
                            .setOrderBy("startTime")
                            .setMaxResults(Math.max(1, maxResults));
            if (startIso != null && !startIso.isEmpty()) {
                request.setTimeMin(new DateTime(startIso));
            }
            if (endIso != null && !endIso.isEmpty()) {
                request.setTimeMax(new DateTime(endIso));
            }
            if (title != null && !title.trim().isEmpty()) {
                request.setQ(title.trim());
            }
            Events events = request.execute();
            List<Event> items = events.getItems();
            return items == null ? Collections.emptyList() : new ArrayList<>(items);
        } catch (Exception e) {
            recordError("queryEvents", e);
            return Collections.emptyList();
        }
    }

    public Event createEvent(String title, String startIso, String endIso, String notes) {
        clearLastError();
        try {
            Calendar service = CalendarServiceFactory.createService(appContext);
            if (service == null) {
                return null;
            }
            Event event = new Event();
            event.setSummary(title);
            if (notes != null && !notes.trim().isEmpty()) {
                event.setDescription(notes.trim());
            }
            event.setStart(new EventDateTime().setDateTime(new DateTime(startIso)));
            event.setEnd(new EventDateTime().setDateTime(new DateTime(endIso)));
            return service.events().insert(PRIMARY_CALENDAR_ID, event).execute();
        } catch (Exception e) {
            recordError("createEvent", e);
            return null;
        }
    }

    public Event updateEvent(String eventId, String title, String startIso, String endIso, String notes) {
        clearLastError();
        try {
            Calendar service = CalendarServiceFactory.createService(appContext);
            if (service == null || eventId == null || eventId.trim().isEmpty()) {
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
                event.setStart(new EventDateTime().setDateTime(new DateTime(startIso.trim())));
            }
            if (endIso != null && !endIso.trim().isEmpty()) {
                event.setEnd(new EventDateTime().setDateTime(new DateTime(endIso.trim())));
            }
            return service.events().update(PRIMARY_CALENDAR_ID, eventId, event).execute();
        } catch (Exception e) {
            recordError("updateEvent", e);
            return null;
        }
    }

    public boolean deleteEvent(String eventId) {
        clearLastError();
        try {
            Calendar service = CalendarServiceFactory.createService(appContext);
            if (service == null || eventId == null || eventId.trim().isEmpty()) {
                return false;
            }
            service.events().delete(PRIMARY_CALENDAR_ID, eventId).execute();
            return true;
        } catch (Exception e) {
            recordError("deleteEvent", e);
            return false;
        }
    }

    public Event resolveEvent(String eventId, String title, String startIso, String endIso) {
        clearLastError();
        try {
            Calendar service = CalendarServiceFactory.createService(appContext);
            if (service == null) {
                return null;
            }
            if (eventId != null && !eventId.trim().isEmpty()) {
                return service.events().get(PRIMARY_CALENDAR_ID, eventId.trim()).execute();
            }
            List<Event> candidates = queryEvents(title, startIso, endIso, 10);
            if (candidates.isEmpty()) {
                setLastError("NOT_FOUND", "No matching calendar event");
                return null;
            }
            if (title != null && !title.trim().isEmpty()) {
                for (Event candidate : candidates) {
                    String summary = candidate.getSummary();
                    if (summary != null && summary.trim().equalsIgnoreCase(title.trim())) {
                        return candidate;
                    }
                }
            }
            return candidates.get(0);
        } catch (Exception e) {
            recordError("resolveEvent", e);
            return null;
        }
    }

    private void recordError(String operation, Exception e) {
        Log.e(TAG, operation + " failed", e);
        if (e instanceof GoogleJsonResponseException) {
            GoogleJsonResponseException responseException = (GoogleJsonResponseException) e;
            int code = responseException.getStatusCode();
            if (code == 403) {
                setLastError("PERMISSION_DENIED", responseException.getDetails() != null
                        ? responseException.getDetails().getMessage()
                        : responseException.getMessage());
            } else if (code == 401) {
                setLastError("AUTH_ERROR", responseException.getMessage());
            } else if (code == 404) {
                setLastError("NOT_FOUND", responseException.getMessage());
            } else {
                setLastError("CALENDAR_HTTP_" + code, responseException.getMessage());
            }
        } else if (e instanceof IOException) {
            setLastError("NETWORK_ERROR", e.getMessage());
        } else {
            setLastError("CALENDAR_ERROR", e.getMessage());
        }
    }

    private synchronized void setLastError(String errorType, String detail) {
        lastErrorType = errorType;
        lastErrorDetail = detail;
    }
}
