package com.micklab.llamachat.calendar;

import com.google.api.services.calendar.model.Event;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CalendarViewModel {
    public interface Listener {
        void onCalendarResult(CalendarUiState uiState, CalendarResultForChat resultForChat);
    }

    private final CalendarRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile CalendarUiState latestUiState =
            new CalendarUiState(false, Collections.emptyList(), null, null, false, null, null);
    private volatile CalendarResultForChat latestResultForChat;

    public CalendarViewModel(CalendarRepository repository) {
        this.repository = repository;
    }

    public CalendarUiState getLatestUiState() {
        return latestUiState;
    }

    public CalendarResultForChat getLatestResultForChat() {
        return latestResultForChat;
    }

    public boolean hasReadAccess() {
        return repository.hasReadAccess();
    }

    public boolean hasWriteAccess() {
        return repository.hasWriteAccess();
    }

    public void handleCalendarAction(CalendarActionJson json, Listener listener) {
        executor.execute(() -> {
            CalendarActionJson action = json == null
                    ? new CalendarActionJson(
                    CalendarActionType.NONE,
                    null,
                    null,
                    null,
                    null,
                    new CalendarAdditional("", null)
            )
                    : json;

            CalendarUiState state;
            CalendarResultForChat result;
            if (action.getAction() == CalendarActionType.NONE) {
                state = new CalendarUiState(false, Collections.emptyList(), null, null, false, null, null);
                result = new CalendarResultForChat(
                        action.getAdditional().getRawText(),
                        CalendarActionType.NONE.name(),
                        true,
                        null,
                        null,
                        "カレンダー操作は不要と判定されました。",
                        Collections.emptyList()
                );
            } else if (!repository.isSignedIn()) {
                state = new CalendarUiState(false, Collections.emptyList(), null, null, false,
                        "NOT_SIGNED_IN", "No Google account is signed in.");
                result = new CalendarResultForChat(
                        action.getAdditional().getRawText(),
                        action.getAction().name(),
                        false,
                        "NOT_SIGNED_IN",
                        "No Google account is signed in.",
                        "Google Calendar にログインしていません。",
                        Collections.emptyList()
                );
            } else if (requiresWriteAccess(action.getAction()) && !repository.hasWriteAccess()) {
                state = new CalendarUiState(false, Collections.emptyList(), null, null, false,
                        "MISSING_WRITE_PERMISSION", "Missing https://www.googleapis.com/auth/calendar.events");
                result = new CalendarResultForChat(
                        action.getAdditional().getRawText(),
                        action.getAction().name(),
                        false,
                        "MISSING_WRITE_PERMISSION",
                        "Missing https://www.googleapis.com/auth/calendar.events",
                        "Google Calendar の編集権限がありません。再ログインして権限を許可してください。",
                        Collections.emptyList()
                );
            } else if (requiresReadAccess(action.getAction()) && !repository.hasReadAccess()) {
                state = new CalendarUiState(false, Collections.emptyList(), null, null, false,
                        "MISSING_READ_PERMISSION", "Missing https://www.googleapis.com/auth/calendar.readonly");
                result = new CalendarResultForChat(
                        action.getAdditional().getRawText(),
                        action.getAction().name(),
                        false,
                        "MISSING_READ_PERMISSION",
                        "Missing https://www.googleapis.com/auth/calendar.readonly",
                        "Google Calendar の参照権限がありません。再ログインして権限を許可してください。",
                        Collections.emptyList()
                );
            } else {
                switch (action.getAction()) {
                    case QUERY:
                        state = handleQuery(action);
                        result = buildQueryResult(action, state);
                        break;
                    case CREATE:
                        state = handleCreate(action);
                        result = buildMutationResult(action, state, "予定を作成しました。", "予定作成に失敗しました。");
                        break;
                    case UPDATE:
                        state = handleUpdate(action);
                        result = buildMutationResult(action, state, "予定を更新しました。", "予定更新に失敗しました。");
                        break;
                    case DELETE:
                        state = handleDelete(action);
                        result = buildDeleteResult(action, state);
                        break;
                    default:
                        state = new CalendarUiState(false, Collections.emptyList(), null, null, false, null, null);
                        result = new CalendarResultForChat(
                                action.getAdditional().getRawText(),
                                CalendarActionType.NONE.name(),
                                true,
                                null,
                                null,
                                "カレンダー操作は不要と判定されました。",
                                Collections.emptyList()
                        );
                        break;
                }
            }
            latestUiState = state;
            latestResultForChat = result;
            if (listener != null) {
                listener.onCalendarResult(state, result);
            }
        });
    }

    public void fetchUpcomingEventsForDebug(Listener listener) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault());
        handleCalendarAction(new CalendarActionJson(
                CalendarActionType.QUERY,
                null,
                now.toString(),
                now.plusDays(7).toString(),
                null,
                new CalendarAdditional("debug: fetch upcoming events", "next 7 days")
        ), listener);
    }

    public void createTestEventForDebug(Listener listener) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0);
        ZonedDateTime start = now.getMinute() < 30
                ? now.withMinute(30)
                : now.plusHours(1).withMinute(0);
        if (!start.isAfter(now)) {
            start = start.plusMinutes(30);
        }
        ZonedDateTime end = start.plusMinutes(30);
        handleCalendarAction(new CalendarActionJson(
                CalendarActionType.CREATE,
                "Dual AI Chat Test Event",
                start.toOffsetDateTime().toString(),
                end.toOffsetDateTime().toString(),
                null,
                new CalendarAdditional("debug: create test event", "created from Calendar Expert Mode")
        ), listener);
    }

    private CalendarUiState handleQuery(CalendarActionJson action) {
        String start = blankToNull(action.getStart());
        String end = blankToNull(action.getEnd());
        if (start == null && end == null) {
            OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault());
            start = now.toString();
            end = now.plusDays(7).toString();
        }
        List<Event> events = repository.queryEvents(action.getTitle(), start, end, 10);
        String errorType = repository.getLastErrorType();
        return new CalendarUiState(false, events, null, null, false, errorType, repository.getLastErrorDetail());
    }

    private CalendarUiState handleCreate(CalendarActionJson action) {
        if (isBlank(action.getTitle()) || isBlank(action.getStart()) || isBlank(action.getEnd())) {
            return new CalendarUiState(false, Collections.emptyList(), null, null, false,
                    "INVALID_INPUT", "title, start, and end are required.");
        }
        Event event = repository.createEvent(
                action.getTitle(),
                action.getStart(),
                action.getEnd(),
                action.getAdditional().getNotes()
        );
        String errorType = event == null ? defaultError(repository.getLastErrorType(), "CREATE_FAILED") : null;
        String errorDetail = event == null ? repository.getLastErrorDetail() : null;
        return new CalendarUiState(false, Collections.emptyList(), event, null, false, errorType, errorDetail);
    }

    private CalendarUiState handleUpdate(CalendarActionJson action) {
        Event target = repository.resolveEventForWrite(
                action.getEventId(), action.getTitle(), action.getStart(), action.getEnd());
        if (target == null) {
            return new CalendarUiState(false, Collections.emptyList(), null, null, false,
                    defaultError(repository.getLastErrorType(), "NOT_FOUND"),
                    repository.getLastErrorDetail());
        }
        Event updated = repository.updateEvent(
                target.getId(),
                action.getTitle(),
                action.getStart(),
                action.getEnd(),
                action.getAdditional().getNotes()
        );
        String errorType = updated == null ? defaultError(repository.getLastErrorType(), "UPDATE_FAILED") : null;
        String errorDetail = updated == null ? repository.getLastErrorDetail() : null;
        return new CalendarUiState(false, Collections.emptyList(), null, updated, false, errorType, errorDetail);
    }

    private CalendarUiState handleDelete(CalendarActionJson action) {
        Event target = repository.resolveEventForWrite(
                action.getEventId(), action.getTitle(), action.getStart(), action.getEnd());
        if (target == null) {
            return new CalendarUiState(false, Collections.emptyList(), null, null, false,
                    defaultError(repository.getLastErrorType(), "NOT_FOUND"),
                    repository.getLastErrorDetail());
        }
        boolean deleted = repository.deleteEvent(target.getId());
        String errorType = deleted ? null : defaultError(repository.getLastErrorType(), "DELETE_FAILED");
        String errorDetail = deleted ? null : repository.getLastErrorDetail();
        return new CalendarUiState(false, Collections.emptyList(), null, null, deleted, errorType, errorDetail);
    }

    private CalendarResultForChat buildQueryResult(CalendarActionJson action, CalendarUiState state) {
        boolean success = state.getErrorType() == null;
        String message;
        if (!success) {
            message = messageForError(state.getErrorType(), "予定取得に失敗しました。");
        } else if (state.getEvents().isEmpty()) {
            message = "一致する予定は見つかりませんでした。";
        } else {
            message = "予定を取得しました。";
        }
        return new CalendarResultForChat(
                action.getAdditional().getRawText(),
                action.getAction().name(),
                success,
                state.getErrorType(),
                state.getErrorDetail(),
                message,
                summarizeEvents(state.getEvents())
        );
    }

    private CalendarResultForChat buildMutationResult(
            CalendarActionJson action,
            CalendarUiState state,
            String successMessage,
            String failureMessage
    ) {
        Event event = state.getCreatedEvent() != null ? state.getCreatedEvent() : state.getUpdatedEvent();
        boolean success = event != null && state.getErrorType() == null;
        List<String> summaries = event == null
                ? Collections.emptyList()
                : Collections.singletonList(summarizeEvent(event));
        return new CalendarResultForChat(
                action.getAdditional().getRawText(),
                action.getAction().name(),
                success,
                state.getErrorType(),
                state.getErrorDetail(),
                success ? successMessage : messageForError(state.getErrorType(), failureMessage),
                summaries
        );
    }

    private CalendarResultForChat buildDeleteResult(CalendarActionJson action, CalendarUiState state) {
        boolean success = state.isDeleted() && state.getErrorType() == null;
        return new CalendarResultForChat(
                action.getAdditional().getRawText(),
                action.getAction().name(),
                success,
                state.getErrorType(),
                state.getErrorDetail(),
                success ? "予定を削除しました。" : messageForError(state.getErrorType(), "予定削除に失敗しました。"),
                Collections.emptyList()
        );
    }

    private List<String> summarizeEvents(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> summaries = new ArrayList<>();
        for (Event event : events) {
            summaries.add(summarizeEvent(event));
        }
        return summaries;
    }

    private String summarizeEvent(Event event) {
        String title = event.getSummary() == null ? "(no title)" : event.getSummary();
        String start = "";
        if (event.getStart() != null) {
            if (event.getStart().getDateTime() != null) {
                start = event.getStart().getDateTime().toStringRfc3339();
            } else if (event.getStart().getDate() != null) {
                start = event.getStart().getDate().toStringRfc3339();
            }
        }
        return title + (start.isEmpty() ? "" : " / " + start);
    }

    private String defaultError(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean requiresReadAccess(CalendarActionType actionType) {
        return actionType == CalendarActionType.QUERY
                || actionType == CalendarActionType.UPDATE
                || actionType == CalendarActionType.DELETE;
    }

    private boolean requiresWriteAccess(CalendarActionType actionType) {
        return actionType == CalendarActionType.CREATE
                || actionType == CalendarActionType.UPDATE
                || actionType == CalendarActionType.DELETE;
    }

    private String messageForError(String errorType, String fallback) {
        if (errorType == null || errorType.trim().isEmpty()) {
            return fallback;
        }
        switch (errorType) {
            case "MISSING_READ_PERMISSION":
                return "Google Calendar の参照権限がありません。再ログインして権限を許可してください。";
            case "MISSING_WRITE_PERMISSION":
                return "Google Calendar の編集権限がありません。再ログインして権限を許可してください。";
            case "PERMISSION_DENIED":
                return "Google Calendar へのアクセスが拒否されました。権限設定を確認してください。";
            case "AUTH_ERROR":
                return "Google Calendar の認証が切れています。再ログインしてください。";
            case "AUTH_RECOVERY_REQUIRED":
                return "Google Calendar の追加認証が必要です。再ログインして権限を許可してください。";
            case "NETWORK_ERROR":
                return "Google Calendar への通信に失敗しました。ネットワーク状態を確認してください。";
            case "INVALID_INPUT":
                return "Google Calendar の入力値が不足しています。";
            case "INVALID_TIME_RANGE":
                return "Google Calendar の開始時刻と終了時刻が不正です。";
            default:
                return fallback;
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
