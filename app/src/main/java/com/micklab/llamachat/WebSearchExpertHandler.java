package com.micklab.llamachat;

public final class WebSearchExpertHandler {
    public String buildSearchQuery(String userInput, ExpertType expertType) {
        if (expertType != ExpertType.WEB) {
            return null;
        }
        String original = userInput == null ? "" : userInput.trim();
        if (original.isEmpty()) {
            return null;
        }

        String cleaned = original
                .replaceAll("(?i)\\bweb\\b", " ")
                .replace("ウェブ", " ")
                .replace("インターネット", " ")
                .replaceAll("(検索して|検索する|検索|ググって|ググる|調べて|調べる)", " ")
                .replaceAll("(ください|下さい|お願い|お願いします|してほしい|して欲しい)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        cleaned = cleaned.replaceAll("^[\\p{Punct}、。・]+", "").replaceAll("[\\p{Punct}、。・]+$", "").trim();
        return cleaned.isEmpty() ? original : cleaned;
    }
}
