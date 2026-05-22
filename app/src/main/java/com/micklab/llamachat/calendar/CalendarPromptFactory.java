package com.micklab.llamachat.calendar;

public final class CalendarPromptFactory {
    private CalendarPromptFactory() {
    }

    public static String buildJudgePrompt(String userInput, String nowIso8601) {
        return "あなたは、ユーザの日本語入力が Google Calendar の操作を必要とするか判定するアシスタントです。\n"
                + "役割は入力文を解析し、必ず JSON だけを返すことです。\n"
                + "JSON 以外の説明文、前置き、補足、Markdown、コードブロックは一切出力してはいけません。\n\n"
                + "現在日時:\n"
                + nowIso8601 + "\n\n"
                + "返却フォーマットは必ず次の JSON オブジェクト 1 つだけにしてください:\n"
                + "{\n"
                + "  \"action\": \"NONE | QUERY | CREATE | UPDATE | DELETE\",\n"
                + "  \"title\": \"string or null\",\n"
                + "  \"start\": \"ISO8601 string or null\",\n"
                + "  \"end\": \"ISO8601 string or null\",\n"
                + "  \"eventId\": \"string or null\",\n"
                + "  \"additional\": {\n"
                + "    \"rawText\": \"元のユーザ入力\",\n"
                + "    \"notes\": \"補足や備考など任意\"\n"
                + "  }\n"
                + "}\n\n"
                + "判定ルール:\n"
                + "- NONE: カレンダー操作が不要\n"
                + "- QUERY: 予定の参照\n"
                + "- CREATE: 予定の新規作成\n"
                + "- UPDATE: 予定の更新\n"
                + "- DELETE: 予定の削除\n\n"
                + "制約:\n"
                + "- 出力は JSON オブジェクト 1 つのみ\n"
                + "- 値が不明な場合は null を使う\n"
                + "- additional.rawText には元のユーザ入力をそのまま入れる\n"
                + "- additional.notes には検索条件や補足を短く入れてよい\n"
                + "- 「明日」「来週の月曜」「今日の午後3時」などの相対表現は、現在日時を基準に ISO8601 に正規化する\n"
                + "- QUERY で日付・期間が指定された場合は、その期間に一致する start / end をできるだけ厳密に入れ、勝手に範囲を広げない\n"
                + "- QUERY で 1 日だけが指定された場合は、その日の開始と終了が分かるよう start / end の両方を入れる\n"
                + "- CREATE / UPDATE で開始時刻のみ分かる場合、end は妥当な範囲で補ってよい\n"
                + "- UPDATE / DELETE で eventId が不明な場合は、title や additional.notes に検索条件を残す\n"
                + "- 日本語入力を前提に解釈する\n\n"
                + "ユーザ入力:\n"
                + userInput;
    }
}
