package com.micklab.llamachat.calendar;

import java.util.List;

public final class CalendarActionParser {
    private CalendarActionParser() {
    }

    public static String buildJudgePrompt(String userInput, String nowIso8601) {
        return "Google Calendar 操作判定です。\n"
                + "【出力】\n"
                + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                + "【title 抽出】\n"
                + "- 「◯◯の予定」「◯◯という予定」→ title = 「◯◯」\n"
                + "- 「予定」「イベント」「日程」「スケジュール」は title に含めない\n"
                + "- 曖昧でも null に逃げず、最も自然な名詞を title にする\n"
                + "【DELETE】\n"
                + "- DELETE の start/end は必ず null\n"
                + "- 期間指定があっても start/end を生成しない\n"
                + "【時間】\n"
                + "- 「11時から2時間」→ 絶対時間 11:00〜13:00\n"
                + "- 「今から2時間」だけ相対時間\n"
                + "- タイムゾーン +09:00 は加算しない。付与するだけ\n"
                + "【JSON 仕様】\n"
                + "{\"action\":\"NONE|QUERY|CREATE|UPDATE|DELETE\",\"title\":\"string or null\",\"start\":\"ISO8601 string or null\",\"end\":\"ISO8601 string or null\",\"eventId\":\"string or null\",\"additional\":{\"rawText\":\"元のユーザ入力\",\"notes\":\"補足\"}}\n"
                + "現在日時:\n"
                + nowIso8601 + "\n"
                + "ユーザ入力:\n"
                + userInput;
    }

    public static String buildRetryPrompt(String userInput, String nowIso8601, List<String> reasons) {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM 出力が不正でした。理由:\n");
        if (reasons == null || reasons.isEmpty()) {
            sb.append("- JSON が不正です\n");
        } else {
            for (String reason : reasons) {
                sb.append("- ").append(reason == null ? "不明な理由" : reason).append("\n");
            }
        }
        sb.append("上記を修正し、正しい JSON オブジェクト 1 つだけを返してください。\n\n");
        sb.append(buildJudgePrompt(userInput, nowIso8601));
        return sb.toString();
    }
}
