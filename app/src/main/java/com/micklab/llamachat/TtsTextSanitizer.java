package com.micklab.llamachat;

/**
 * TTS 読み上げ用にテキストから Markdown / HTML の装飾を取り除くユーティリティ。
 *
 * 表示用バブルは {@link MarkdownRenderer} でリッチ表示するが、読み上げでは
 * タグ名（例 {@code <strong>} → 「strong」）や記号がそのまま読まれないよう、
 * 構造マークアップを語ごと取り除いて本文だけを残す。
 *
 * 句読点を「、」へ変換する既存の正規化の前段で呼ぶこと。
 */
final class TtsTextSanitizer {

    private TtsTextSanitizer() {
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw;
        // フェンスドコードブロックは丸ごと除去（言語名やコード本体を読み上げない）。
        s = s.replaceAll("(?s)```.*?```", " ");
        // 画像 ![alt](url) は alt を、リンク [text](url) は表示テキストを残す。
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // HTML タグはタグ名ごと除去（<strong> 等が「strong」と読まれるのを防ぐ）。
        s = s.replaceAll("(?s)<[^>]+>", " ");
        // 主要な HTML エンティティを実体へ。
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", " ")
                .replace("&gt;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        // 強調・打消し・インラインコードの記号を除去（本文は残す）。
        // 併せて *** / ___ 形式の水平線もここで消える。
        s = s.replaceAll("\\*\\*\\*|\\*\\*|\\*|___|__|_|~~|`", "");
        // ハイフン形式の水平線（---, - - - など）はリスト記号処理より先に除去。
        s = s.replaceAll("(?m)^[ \\t]{0,3}-([ \\t]*-){2,}[ \\t]*$", " ");
        // 行頭の見出し・引用・リスト記号を除去。
        s = s.replaceAll("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*", "");
        s = s.replaceAll("(?m)^[ \\t]{0,3}>[ \\t]?", "");
        s = s.replaceAll("(?m)^[ \\t]{0,3}([-+]|\\d+[.)])[ \\t]+", "");
        // 表のセル区切り。
        s = s.replace("|", " ");
        return s;
    }
}
