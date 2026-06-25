package com.micklab.llamachat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MainActivity と FloatOverlayService が同一プロセス内で共有する会話ログのシングルトン。
 *
 * 表示用の user/assistant ターンのみを保持し、backing file（overlay_sync_log.jsonl）へ永続化する。
 * どちらのコンポーネントから追記しても全リスナーへ通知され、双方向にライブ同期される。
 * system プロンプトは各コンポーネントが自前で付与するため本ストアでは保持しない。
 */
public final class ConversationStore {

    public static final class Entry {
        public final String role;     // "user" | "assistant"
        public final String content;

        public Entry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public interface Listener {
        /** エントリ追記時。newSize は追記後の総件数。各リスナーは差分のみ描画すること。 */
        void onEntryAppended(int newSize);

        /** 会話リセット時。 */
        void onCleared();
    }

    private static final String BACKING_FILE = "overlay_sync_log.jsonl";
    private static final int MAX_ENTRIES = 200;

    private static ConversationStore instance;

    public static synchronized ConversationStore get(Context context) {
        if (instance == null) {
            instance = new ConversationStore(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    // 通知は必ずメインスレッドへ post（非同期）する。これにより append 呼び出し元が
    // storeRenderedCount を更新し終えた後にリスナーが走り、発信元の二重描画を防ぐ。
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConversationStore(Context appContext) {
        this.appContext = appContext;
        loadFromFile();
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    public void append(String role, String content) {
        if (role == null || content == null) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int newSize;
        synchronized (this) {
            entries.add(new Entry(role, trimmed));
            boolean trimmed_ = trimMemory();
            newSize = entries.size();
            if (trimmed_) {
                rewriteFile();
            } else {
                persistAppend(role, trimmed);
            }
        }
        final int sz = newSize;
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onEntryAppended(sz);
            }
        });
    }

    public void clear() {
        synchronized (this) {
            entries.clear();
            File f = new File(appContext.getFilesDir(), BACKING_FILE);
            if (f.exists() && !f.delete()) {
                // 削除に失敗した場合は空で上書きする。
                try (FileOutputStream fos = appContext.openFileOutput(BACKING_FILE, Context.MODE_PRIVATE)) {
                    fos.write(new byte[0]);
                } catch (Exception ignored) {
                }
            }
        }
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onCleared();
            }
        });
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ----- internal -----

    /** メモリ上限を超えたら先頭から削る。削った場合 true。 */
    private boolean trimMemory() {
        boolean removed = false;
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
            removed = true;
        }
        return removed;
    }

    private void loadFromFile() {
        File f = new File(appContext.getFilesDir(), BACKING_FILE);
        if (!f.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            List<Entry> loaded = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(line);
                    String role = o.optString("role", "").trim();
                    String content = o.optString("content", "").trim();
                    if (content.isEmpty()) {
                        continue;
                    }
                    if ("user".equals(role) || "assistant".equals(role)) {
                        loaded.add(new Entry(role, content));
                    }
                } catch (Exception ignored) {
                }
            }
            int start = Math.max(0, loaded.size() - MAX_ENTRIES);
            for (int i = start; i < loaded.size(); i++) {
                entries.add(loaded.get(i));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistAppend(String role, String content) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("content", content);
            try (FileOutputStream fos = appContext.openFileOutput(BACKING_FILE, Context.MODE_APPEND)) {
                fos.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** メモリ内容で backing file を丸ごと書き直す（トリム発生時に呼ぶ）。 */
    private void rewriteFile() {
        try (FileOutputStream fos = appContext.openFileOutput(BACKING_FILE, Context.MODE_PRIVATE)) {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("role", e.role);
                o.put("content", e.content);
                sb.append(o.toString()).append("\n");
            }
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
