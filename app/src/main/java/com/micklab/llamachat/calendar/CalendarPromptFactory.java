package com.micklab.llamachat.calendar;

import java.util.List;

public final class CalendarPromptFactory {
    private CalendarPromptFactory() {
    }

    public static String buildJudgePrompt(String userInput, String nowIso8601) {
        return "あなたは Google Calendar 操作判定専用のアシスタントです。\n"
                + "役割は入力文を解析し、必ず JSON オブジェクト 1 つだけを返すことです。\n\n"
                + "絶対ルール:\n"
                + "- 出力は JSON オブジェクト 1 つのみ\n"
                + "- JSON の前後に空白、改行、説明文、Markdown、コードブロックを一切付けない\n"
                + "- JSON を文字列化してはいけない\n"
                + "- \"response\":\"{...}\" のように JSON 本体を文字列として入れてはいけない\n"
                + "- JSON 全体をダブルクォートで囲ってはいけない\n"
                + "- JSON 以外を出力しそうになった場合は即座に停止し、有効な JSON オブジェクト 1 つだけを出力する\n"
                + "- ダブルクォートは JSON のキーと文字列値にだけ使い、余計なエスケープをしない\n"
                + "- action は NONE / QUERY / CREATE / UPDATE / DELETE のいずれか\n"
                + "- 値が不明な場合は null を使う\n"
                + "- additional.rawText には元のユーザ入力をそのまま入れる\n"
                + "- title は予定名そのものであり、絶対に変形してはいけない\n"
                + "- title に「〜の予定」「〜のイベント」「〜の日程変更」などの語を付け足してはいけない\n"
                + "- title にはユーザ入力に含まれる予定名をそのまま抜き出して入れる\n"
                + "- 予定名が曖昧な場合は title を null にする\n"
                + "- UPDATE / DELETE では title は既存イベント検索キーになるため、1文字でも変形してはいけない\n"
                + "- 相対日時（明日、来週の月曜、今日の午後3時など）は現在日時を基準に ISO8601 に正規化する\n"
                + "- 「正午」は 12:00 として解釈する\n"
                + "- 「午後1時」から「午後11時」は 13:00 から 23:00 として解釈する\n"
                + "- 「午後12時」は 12:00 として解釈する\n"
                + "- 「午前X時」は X:00 として解釈する\n"
                + "- 「朝」は 06:00〜11:59 の範囲で文脈に応じて解釈する\n"
                + "- 「夕方」は 17:00〜18:59 の範囲で解釈する\n"
                + "- 「夜」は 19:00〜23:59 の範囲で解釈する\n"
                + "- 「本日」「今日」は現在日時の日付として解釈する\n"
                + "- QUERY で日付や期間が指定された場合は、その範囲に一致する start / end を厳密に入れ、勝手に広げない\n"
                + "- QUERY で 1 日だけが指定された場合は、その日の開始と終了が分かるよう start / end の両方を入れる\n"
                + "- CREATE / UPDATE で開始時刻のみ分かる場合、end は妥当な範囲で補ってよい\n"
                + "- UPDATE / DELETE で eventId が不明な場合は、title や additional.notes に検索条件を残す\n"
                + "- 日本語入力を前提に解釈する\n\n"
                + "現在日時:\n"
                + nowIso8601 + "\n\n"
                + "返却する JSON 仕様:\n"
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
                + "ユーザ入力:\n"
                + userInput;
    }

    public static String buildExplainSystemPrompt() {
        return "あなたはユーザ向けの説明を生成するアシスタントです。"
                + "CALENDAR_OPERATION_RESULT の内容だけを使って、自然な日本語で簡潔に説明してください。"
                + "余計な推測、創作、補足、追加情報は禁止です。";
    }

    public static String buildExplainUserPrompt(String originalUserInput, CalendarResultForChat resultForChat) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたはユーザ向けの説明を生成するアシスタントです。\n");
        sb.append("以下はアプリが実行した Google Calendar API の結果です。\n\n");
        sb.append("CALENDAR_OPERATION_RESULT:\n");
        sb.append("action: ").append(resultForChat == null ? "NONE" : resultForChat.getAction()).append("\n");
        sb.append("success: ").append(resultForChat != null && resultForChat.isSuccess()).append("\n");
        sb.append("message: ").append(resultForChat == null ? "Calendar result is unavailable." : resultForChat.getMessageForSystem()).append("\n");
        sb.append("events:\n");
        List<String> summaries = resultForChat == null ? null : resultForChat.getEventSummaries();
        if (summaries == null || summaries.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (String summary : summaries) {
                sb.append("- ").append(summary == null ? "" : summary).append("\n");
            }
        }
        sb.append("\n元のユーザ入力:\n");
        sb.append(originalUserInput == null ? "" : originalUserInput).append("\n");
        if (resultForChat != null && (resultForChat.getErrorType() != null || resultForChat.getErrorDetail() != null)) {
            sb.append("\n内部エラー情報:\n");
            sb.append("errorType: ").append(resultForChat.getErrorType() == null ? "null" : resultForChat.getErrorType()).append("\n");
            sb.append("errorDetail: ").append(resultForChat.getErrorDetail() == null ? "null" : resultForChat.getErrorDetail()).append("\n");
        }
        sb.append("\n");
        sb.append("上記の内容を踏まえて、ユーザに自然な日本語で簡潔に説明してください。\n");
        sb.append("余計な推測や追加情報は含めないでください。");
        return sb.toString();
    }
}
