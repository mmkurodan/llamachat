package com.micklab.llamachat;

import java.util.Locale;

public final class ExpertSelector {
    private static final String[] WEB_KEYWORDS = new String[]{
            "web", "ウェブ", "インターネット", "検索", "ググる", "調べて"
    };
    private static final String[] CALENDAR_CREATE_KEYWORDS = new String[]{
            "予定を入れたい",
            "予定したい",
            "予定する",
            "登録して",
            "追加して",
            "入れて",
            "予定して欲しい",
            "予定してほしい"
    };
    private static final String[] CALENDAR_UPDATE_KEYWORDS = new String[]{
            "予定を変える", "変更する", "ずらす", "移動する", "時間を変える"
    };
    private static final String[] CALENDAR_DELETE_KEYWORDS = new String[]{
            "予定を削除", "予定を消す", "予定を取り消す", "予定をやめる"
    };
    private static final String[] CALENDAR_CONTEXT_KEYWORDS = new String[]{
            "予定", "カレンダー", "スケジュール", "日程"
    };
    private static final String[] CALENDAR_UPDATE_VERBS = new String[]{
            "変える", "変更", "ずらす", "移動", "時間を変える"
    };
    private static final String[] CALENDAR_DELETE_VERBS = new String[]{
            "削除", "消す", "取り消す", "やめる"
    };

    public ExpertType select(String userInput, boolean webAvailable, boolean calendarAvailable) {
        String normalized = normalize(userInput);
        if (normalized.isEmpty()) {
            return ExpertType.NONE;
        }
        if (calendarAvailable && isCalendarCreateIntent(normalized)) {
            return ExpertType.CALENDAR_CREATE;
        }
        // Prefer calendar intents over generic search words like "調べて".
        if (calendarAvailable && isCalendarModelIntent(normalized)) {
            return ExpertType.CALENDAR_MODEL;
        }
        if (webAvailable && containsAny(normalized, WEB_KEYWORDS)) {
            return ExpertType.WEB;
        }
        return ExpertType.NONE;
    }

    private boolean isCalendarCreateIntent(String normalized) {
        return containsAny(normalized, CALENDAR_CREATE_KEYWORDS);
    }

    private boolean isCalendarModelIntent(String normalized) {
        if (containsAny(normalized, CALENDAR_CONTEXT_KEYWORDS)) {
            return true;
        }
        return containsAny(normalized, CALENDAR_UPDATE_KEYWORDS)
                || containsAny(normalized, CALENDAR_DELETE_KEYWORDS)
                || containsCalendarVerb(normalized, CALENDAR_UPDATE_VERBS)
                || containsCalendarVerb(normalized, CALENDAR_DELETE_VERBS);
    }

    private boolean containsCalendarVerb(String normalized, String[] verbs) {
        return containsAny(normalized, verbs) && containsAny(normalized, CALENDAR_CONTEXT_KEYWORDS);
    }

    private boolean containsAny(String normalized, String[] keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replace('\u3000', ' ')
                .replaceAll("\\s+", "");
    }
}
