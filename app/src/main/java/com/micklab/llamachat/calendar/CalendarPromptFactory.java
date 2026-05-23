package com.micklab.llamachat.calendar;

import java.util.List;

public final class CalendarPromptFactory {
    private CalendarPromptFactory() {
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
