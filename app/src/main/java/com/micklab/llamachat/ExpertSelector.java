package com.micklab.llamachat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
        List<ExpertType> ordered = selectAll(userInput, webAvailable, calendarAvailable);
        if (ordered.isEmpty()) {
            return ExpertType.NONE;
        }
        return ordered.get(0);
    }

    public List<ExpertType> selectAll(String userInput, boolean webAvailable, boolean calendarAvailable) {
        String normalized = normalize(userInput);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        List<MatchedExpert> matches = new ArrayList<>();
        if (calendarAvailable) {
            MatchedExpert calendarMatch = findCalendarMatch(normalized);
            if (calendarMatch != null) {
                matches.add(calendarMatch);
            }
        }
        if (webAvailable) {
            int webPosition = firstKeywordPosition(normalized, WEB_KEYWORDS);
            if (webPosition >= 0) {
                matches.add(new MatchedExpert(ExpertType.WEB, webPosition));
            }
        }
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        matches.sort(Comparator.comparingInt(match -> match.position));
        List<ExpertType> ordered = new ArrayList<>();
        for (MatchedExpert match : matches) {
            if (!ordered.contains(match.expertType)) {
                ordered.add(match.expertType);
            }
        }
        return ordered;
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

    private MatchedExpert findCalendarMatch(String normalized) {
        int createPosition = firstCalendarCreatePosition(normalized);
        int explicitModelPosition = firstExplicitCalendarModelPosition(normalized);
        int queryPosition = firstKeywordPosition(normalized, CALENDAR_CONTEXT_KEYWORDS);
        if (createPosition < 0 && explicitModelPosition < 0 && queryPosition < 0) {
            return null;
        }
        if (explicitModelPosition >= 0 && (createPosition < 0 || explicitModelPosition < createPosition)) {
            return new MatchedExpert(ExpertType.CALENDAR_MODEL, explicitModelPosition);
        }
        if (createPosition >= 0) {
            return new MatchedExpert(ExpertType.CALENDAR_CREATE, createPosition);
        }
        return new MatchedExpert(ExpertType.CALENDAR_MODEL, queryPosition);
    }

    private int firstCalendarCreatePosition(String normalized) {
        if (!isCalendarCreateIntent(normalized)) {
            return -1;
        }
        int createKeywordPosition = firstKeywordPosition(normalized, CALENDAR_CREATE_KEYWORDS);
        int contextPosition = firstKeywordPosition(normalized, CALENDAR_CONTEXT_KEYWORDS);
        return minPositive(createKeywordPosition, contextPosition);
    }

    private int firstExplicitCalendarModelPosition(String normalized) {
        int best = firstKeywordPosition(normalized, CALENDAR_UPDATE_KEYWORDS);
        best = minPositive(best, firstKeywordPosition(normalized, CALENDAR_DELETE_KEYWORDS));
        best = minPositive(best, firstCalendarVerbPosition(normalized, CALENDAR_UPDATE_VERBS));
        best = minPositive(best, firstCalendarVerbPosition(normalized, CALENDAR_DELETE_VERBS));
        return best;
    }

    private int firstCalendarVerbPosition(String normalized, String[] verbs) {
        int verbPosition = firstKeywordPosition(normalized, verbs);
        int contextPosition = firstKeywordPosition(normalized, CALENDAR_CONTEXT_KEYWORDS);
        if (verbPosition < 0 || contextPosition < 0) {
            return -1;
        }
        return Math.min(verbPosition, contextPosition);
    }

    private int firstKeywordPosition(String normalized, String[] keywords) {
        int best = -1;
        for (String keyword : keywords) {
            int index = normalized.indexOf(normalize(keyword));
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private int minPositive(int current, int candidate) {
        if (current < 0) {
            return candidate;
        }
        if (candidate < 0) {
            return current;
        }
        return Math.min(current, candidate);
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

    private static final class MatchedExpert {
        private final ExpertType expertType;
        private final int position;

        private MatchedExpert(ExpertType expertType, int position) {
            this.expertType = expertType;
            this.position = position;
        }
    }
}
