package com.micklab.llamachat;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

final class MarkdownRenderer {
    private final Markwon markwon;

    private MarkdownRenderer(Context context) {
        Context appContext = context.getApplicationContext();
        this.markwon = Markwon.builder(appContext)
                .usePlugin(HtmlPlugin.create())
                .usePlugin(TablePlugin.create(appContext))
                .build();
    }

    static MarkdownRenderer create(Context context) {
        return new MarkdownRenderer(context);
    }

    void prepare(TextView textView) {
        if (textView == null) return;
        textView.setTextIsSelectable(true);
        textView.setLongClickable(true);
    }

    void render(TextView textView, String title, String body) {
        if (textView == null) return;
        markwon.setMarkdown(textView, toMarkdown(title, body));
    }

    void renderPlain(TextView textView, String title, String body) {
        if (textView == null) return;
        textView.setText(toPlainText(title, body));
    }

    private String toMarkdown(String title, String body) {
        String safeBody = body == null ? "" : body;
        if (TextUtils.isEmpty(title)) {
            return safeBody;
        }
        if (TextUtils.isEmpty(safeBody)) {
            return "**" + title + "**";
        }
        return "**" + title + "**\n\n" + safeBody;
    }

    private CharSequence toPlainText(String title, String body) {
        String safeBody = body == null ? "" : body;
        if (TextUtils.isEmpty(title)) {
            return safeBody;
        }
        if (TextUtils.isEmpty(safeBody)) {
            return title;
        }
        return title + "\n\n" + safeBody;
    }
}
