package com.example.hello2;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * シンプルなチャット UI と Ollama ローカル /api/chat への連携例
 * モデル: default
 *
 * 変更点（要約連携）:
 * - 会話が 10 回を超過したら、超過分と既存の会話要約を Ollama に渡して要約を作成 -> 新しい会話要約として保持
 * - ユーザがメッセージを送る際には、会話履歴の古い方に要約を挿入しておく（system プロンプトの次）
 * - AI（アシスタント）からの応答を受信中、及び会話要約を受信するまで送信ボタンを無効化する
 *
 * 追加:
 * - UI に「要約表示」ボタンを追加。クリックするとこれまでに作成された会話要約をポップアップ表示します。
 * - 要約がまだない場合は、要約は会話が 10 回以上になったときに作成される旨をポップアップで通知します。
 */
public class MainActivity extends Activity {
    private TextView tvConversation;
    private EditText etInput;
    private Button btnSend;
    private Button btnShowSummary;
    private ScrollView scrollView;

    // Conversation history: list of messages (system, summary (optional), user, assistant, ...)
    private List<JSONObject> conversationHistory;

    // Conversation summary stored separately (inserted into history as a system message at index 1 when present)
    private JSONObject conversationSummary = null;

    // Maximum number of user/assistant message pairs to keep in history (plus system prompt and optional summary)
    private static final int MAX_USER_MESSAGE_PAIRS = 10;

    // Flags to control UI state
    private volatile boolean isStreamingInProgress = false;
    private volatile boolean isSummarizationInProgress = false;

    // Lock for conversation history modifications
    private final Object historyLock = new Object();

    // 実機で Ollama が同じ端末上で動作している場合（adb reverse でフォワード済みなど）は localhost を使える
    // 例: "http://localhost:11434/api/chat"
    // もし Ollama が PC 上で実機が Wi-Fi 接続の場合は PC のローカルIP に置き換えてください:
    // 例: "http://192.168.1.100:11434/api/chat"
    private static final String OLLAMA_CHAT_URL = "http://localhost:11434/api/chat";
    
    // タイムアウトを 3600 秒 (1 時間) に設定
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3600, TimeUnit.SECONDS)
            .writeTimeout(3600, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .callTimeout(3600, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvConversation = findViewById(R.id.tvConversation);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        btnShowSummary = findViewById(R.id.btnShowSummary);
        scrollView = findViewById(R.id.scrollView);

        // Initialize conversation history with system prompt
        conversationHistory = new ArrayList<>();
        try {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "あなたはユーザの若い女性秘書です");
            conversationHistory.add(systemMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }

        updateSendButtonState();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String userMsg = etInput.getText().toString().trim();
                if (userMsg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Disable send button while sending/streaming/summarizing
                etInput.setText("");
                appendConversation("You: " + userMsg + "\n");

                // Add user's message and ensure summary is present as the oldest message (after system)
                synchronized (historyLock) {
                    insertSummaryIfNeededIntoHistory();
                    try {
                        JSONObject userMsgObj = new JSONObject();
                        userMsgObj.put("role", "user");
                        userMsgObj.put("content", userMsg);
                        conversationHistory.add(userMsgObj);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // Start chat request (this will set streaming flag and disable the button)
                sendChatToOllama(userMsg);
            }
        });

        // 要約表示ボタンの動作を追加
        if (btnShowSummary != null) {
            btnShowSummary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    synchronized (historyLock) {
                        if (conversationSummary != null) {
                            final String summaryText = conversationSummary.optString("content", "");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("会話要約")
                                            .setMessage(summaryText)
                                            .setPositiveButton("閉じる", null)
                                            .show();
                                }
                            });
                        } else {
                            // 要約がまだない場合は、要約は会話が 10 回以上になったときに作成される旨を通知
                            final int currentUserCount = countUserMessages();
                            final String msg = "会話要約はまだ作成されていません。会話が " + MAX_USER_MESSAGE_PAIRS + " 回以上になると自動的に要約が作成されます。\n現在の会話数: " + currentUserCount + " 回";
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("要約はまだありません")
                                            .setMessage(msg)
                                            .setPositiveButton("OK", null)
                                            .show();
                                }
                            });
                        }
                    }
                }
            });
        }
    }

    private void appendConversation(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvConversation.append(text);
                scrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    /**
     * Save assistant response to conversation history
     */
    private void saveAssistantResponse(String content) {
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject assistantMsg = new JSONObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", content);
                synchronized (historyLock) {
                    conversationHistory.add(assistantMsg);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Insert conversationSummary into conversationHistory as the oldest message after system prompt,
     * if a summary exists and it's not already inserted.
     */
    private void insertSummaryIfNeededIntoHistory() {
        synchronized (historyLock) {
            if (conversationSummary == null) return;

            // Ensure system prompt is at index 0
            if (conversationHistory.isEmpty()) return;

            // If index 1 exists and matches summary content, do nothing
            if (conversationHistory.size() > 1) {
                try {
                    JSONObject possible = conversationHistory.get(1);
                    String role = possible.optString("role", "");
                    String content = possible.optString("content", "");
                    if ("system".equals(role) && content.equals(conversationSummary.optString("content", ""))) {
                        return; // already inserted
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Insert summary at index 1
            conversationHistory.add(1, conversationSummary);
        }
    }

    /**
     * Count the number of user messages in conversation history (excluding system/summary).
     */
    private int countUserMessages() {
        int userCount = 0;
        synchronized (historyLock) {
            for (int i = 0; i < conversationHistory.size(); i++) {
                try {
                    String role = conversationHistory.get(i).getString("role");
                    if ("user".equals(role)) userCount++;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return userCount;
    }

    /**
     * Build a list of oldest messages that should be summarized because user pairs exceed MAX_USER_MESSAGE_PAIRS.
     * We return a copy list of JSONObjects to avoid concurrent modification.
     */
    private List<JSONObject> collectOldMessagesToSummarize() {
        List<JSONObject> toSummarize = new ArrayList<>();
        synchronized (historyLock) {
            // Skip index 0 (system prompt). If summary is at index 1, keep it out (we send it separately).
            // Count user messages from the end backwards to find the boundary to keep last MAX_USER_MESSAGE_PAIRS pairs.
            int totalUser = 0;
            for (int i = conversationHistory.size() - 1; i >= 0; i--) {
                try {
                    String role = conversationHistory.get(i).getString("role");
                    if ("user".equals(role)) {
                        totalUser++;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // If totalUser <= MAX_USER_MESSAGE_PAIRS then nothing to summarize
            if (totalUser <= MAX_USER_MESSAGE_PAIRS) {
                return toSummarize;
            }

            // We will keep the last MAX_USER_MESSAGE_PAIRS user messages + their following assistant messages.
            // Find the index (from start) where the kept messages begin.
            int userSeen = 0;
            int keepStartIndex = conversationHistory.size(); // default to end
            for (int i = conversationHistory.size() - 1; i >= 0; i--) {
                try {
                    String role = conversationHistory.get(i).getString("role");
                    if ("user".equals(role)) {
                        userSeen++;
                        if (userSeen == MAX_USER_MESSAGE_PAIRS) {
                            // We want to keep from this user message (and everything after it)
                            keepStartIndex = i;
                            break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Collect everything from index 1 (or 2 if index 1 is summary) up to keepStartIndex (exclusive)
            int startCollect = 1;
            if (conversationSummary != null && conversationHistory.size() > 1) {
                // If the summary exists, it should be at index 1. We don't collect the summary here.
                startCollect = 2;
            }
            for (int i = startCollect; i < keepStartIndex; i++) {
                try {
                    // shallow copy: new JSONObject(original.toString()) to decouple
                    JSONObject copy = new JSONObject(conversationHistory.get(i).toString());
                    toSummarize.add(copy);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return toSummarize;
    }

    /**
     * After obtaining a new summary string, prune the conversationHistory:
     * - keep system prompt at index 0
     * - insert summary at index 1 (if not null)
     * - keep last MAX_USER_MESSAGE_PAIRS user messages (and their assistant messages)
     */
    private void pruneHistoryAfterSummary(String summaryText) {
        synchronized (historyLock) {
            List<JSONObject> newHistory = new ArrayList<>();
            try {
                // system prompt (original at index 0)
                if (!conversationHistory.isEmpty()) {
                    newHistory.add(conversationHistory.get(0));
                } else {
                    // fallback: default system
                    JSONObject systemMessage = new JSONObject();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", "あなたはユーザの若い女性秘書です");
                    newHistory.add(systemMessage);
                }

                // add new summary as system message if provided
                if (summaryText != null && !summaryText.isEmpty()) {
                    JSONObject summaryMsg = new JSONObject();
                    summaryMsg.put("role", "system");
                    summaryMsg.put("content", summaryText);
                    newHistory.add(summaryMsg);
                    // update local conversationSummary reference
                    conversationSummary = summaryMsg;
                } else {
                    // if no summaryText provided, keep existing conversationSummary if present
                    if (conversationSummary != null) {
                        newHistory.add(conversationSummary);
                    }
                }

                // Now collect the last MAX_USER_MESSAGE_PAIRS user messages and their assistant replies from existing history
                List<JSONObject> tail = new ArrayList<>();
                int userSeen = 0;
                // iterate from end to start and collect until we have MAX_USER_MESSAGE_PAIRS user messages
                for (int i = conversationHistory.size() - 1; i >= 0; i--) {
                    try {
                        JSONObject msg = conversationHistory.get(i);
                        String role = msg.optString("role", "");
                        // skip the summary that might have been in the old history to avoid duplication
                        if ("system".equals(role) && conversationSummary != null
                                && msg.optString("content", "").equals(conversationSummary.optString("content", ""))) {
                            continue;
                        }
                        tail.add(0, new JSONObject(msg.toString())); // prepend to maintain order
                        if ("user".equals(role)) {
                            userSeen++;
                            if (userSeen >= MAX_USER_MESSAGE_PAIRS) {
                                // we have enough user messages; but keep assistant after last user too (already included)
                                // stop collecting earlier messages
                                break;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // append tail to newHistory
                newHistory.addAll(tail);

                // replace conversationHistory
                conversationHistory = newHistory;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Update the enabled state of the send button based on current flags.
     */
    private void updateSendButtonState() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean enabled = !isStreamingInProgress && !isSummarizationInProgress;
                btnSend.setEnabled(enabled);
            }
        });
    }

    private void sendChatToOllama(String userMessage) {
        try {
            // Build request body from conversationHistory snapshot
            JSONArray messages = new JSONArray();
            synchronized (historyLock) {
                for (JSONObject msg : conversationHistory) {
                    messages.put(msg);
                }
            }

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("model", "default");
            bodyJson.put("messages", messages);
            bodyJson.put("stream", true);  // Enable streaming

            RequestBody requestBody = RequestBody.create(bodyJson.toString(), JSON);
            Request request = new Request.Builder()
                    .url(OLLAMA_CHAT_URL)
                    .post(requestBody)
                    .build();

            // Set streaming flag and update UI
            isStreamingInProgress = true;
            updateSendButtonState();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    appendConversation("Error: " + e.getMessage() + "\n");
                    isStreamingInProgress = false;
                    updateSendButtonState();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        appendConversation("HTTP error: " + response.code() + "\n");
                        isStreamingInProgress = false;
                        updateSendButtonState();
                        return;
                    }

                    // Handle streaming response (NDJSON format)
                    final StringBuilder fullAssistantResponse = new StringBuilder();
                    boolean isFirstChunk = true;

                    // Use try-with-resources to ensure proper cleanup
                    try (InputStream inputStream = response.body().byteStream();
                         InputStreamReader streamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                         BufferedReader reader = new BufferedReader(streamReader)) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) {
                                continue;
                            }

                            try {
                                JSONObject jsonLine = new JSONObject(line);

                                // Check if streaming is done
                                boolean done = jsonLine.optBoolean("done", false);

                                // Extract message content
                                if (jsonLine.has("message")) {
                                    JSONObject message = jsonLine.getJSONObject("message");
                                    String content = message.optString("content", "");

                                    if (!content.isEmpty()) {
                                        // Append "Assistant: " only for the first chunk
                                        if (isFirstChunk) {
                                            appendConversation("Assistant: ");
                                            isFirstChunk = false;
                                        }

                                        // Append the incremental content
                                        fullAssistantResponse.append(content);
                                        appendConversation(content);
                                    }
                                }

                                // If done, exit the loop
                                if (done) {
                                    break;
                                }
                            } catch (Exception e) {
                                appendConversation("Parse error on line: " + e.getMessage() + "\n");
                            }
                        }

                        // Always add newline at the end
                        appendConversation("\n");

                        // Save assistant response to history if any content was received
                        String assistantText = fullAssistantResponse.toString();
                        if (!assistantText.isEmpty()) {
                            saveAssistantResponse(assistantText);
                        }
                    } catch (Exception e) {
                        appendConversation("Streaming error: " + e.getMessage() + "\n");
                        // Even on error, try to save partial response if any
                        saveAssistantResponse(fullAssistantResponse.toString());
                    } finally {
                        // Streaming finished
                        isStreamingInProgress = false;
                        updateSendButtonState();

                        // After receiving assistant response, check whether we exceed MAX pairs and if so, summarize the old messages
                        int userCountAfter = countUserMessages();
                        if (userCountAfter > MAX_USER_MESSAGE_PAIRS) {
                            // collect old messages to summarize
                            List<JSONObject> oldMessages = collectOldMessagesToSummarize();
                            if (!oldMessages.isEmpty()) {
                                isSummarizationInProgress = true;
                                updateSendButtonState();
                                requestSummarizeOldConversations(oldMessages, conversationSummary != null ? conversationSummary.optString("content", "") : "");
                            }
                        }
                    }
                }
            });

        } catch (Exception e) {
            appendConversation("Request build error: " + e.getMessage() + "\n");
            isStreamingInProgress = false;
            updateSendButtonState();
        }
    }

    /**
     * Send the old messages + existing summary to Ollama to produce a new summary.
     * The summarization result will be used to replace the old messages in history with the summary.
     *
     * This runs asynchronously. While summarization is in progress, the send button stays disabled.
     */
    private void requestSummarizeOldConversations(List<JSONObject> oldMessages, String existingSummary) {
        try {
            // Build a conversation payload that asks the model to summarize the provided messages.
            JSONArray messages = new JSONArray();

            // System instruction guiding the summarizer
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "あなたは与えられた会話を日本語で簡潔に要約するアシスタントです。重要な事実とコンテキストを残し、冗長な部分は省いてください。");
            messages.put(sys);

            // If there is an existing summary, include it so the model can merge/update it
            if (existingSummary != null && !existingSummary.isEmpty()) {
                JSONObject prevSummaryMsg = new JSONObject();
                prevSummaryMsg.put("role", "user");
                prevSummaryMsg.put("content", "既存の会話要約:\n" + existingSummary);
                messages.put(prevSummaryMsg);
            }

            // Include the old messages to summarize
            // We'll present them in a "user" message block to be summarized
            StringBuilder convoBuilder = new StringBuilder();
            for (JSONObject msg : oldMessages) {
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                convoBuilder.append(role.toUpperCase()).append(": ").append(content).append("\n");
            }
            JSONObject convoMsg = new JSONObject();
            convoMsg.put("role", "user");
            convoMsg.put("content", "以下の会話を簡潔に要約してください。可能なら箇条書きで重要点をまとめてください:\n" + convoBuilder.toString());
            messages.put(convoMsg);

            // Ask explicitly for a single concise summary
            JSONObject finalInstruction = new JSONObject();
            finalInstruction.put("role", "user");
            finalInstruction.put("content", "上の会話を1つの要約にまとめ、会話の主要なコンテキストと決定事項を含めてください。");
            messages.put(finalInstruction);

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("model", "default");
            bodyJson.put("messages", messages);
            bodyJson.put("stream", true); // use streaming to receive incremental summary

            RequestBody requestBody = RequestBody.create(bodyJson.toString(), JSON);
            Request request = new Request.Builder()
                    .url(OLLAMA_CHAT_URL)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    appendConversation("Summary request failed: " + e.getMessage() + "\n");
                    // Fallback: if summarization fails, prune history without summary to avoid unbounded growth
                    pruneHistoryAfterSummary(null);
                    isSummarizationInProgress = false;
                    updateSendButtonState();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        appendConversation("Summary HTTP error: " + response.code() + "\n");
                        pruneHistoryAfterSummary(null);
                        isSummarizationInProgress = false;
                        updateSendButtonState();
                        return;
                    }

                    final StringBuilder fullSummary = new StringBuilder();
                    boolean isFirstChunk = true;

                    try (InputStream inputStream = response.body().byteStream();
                         InputStreamReader streamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                         BufferedReader reader = new BufferedReader(streamReader)) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;

                            try {
                                JSONObject jsonLine = new JSONObject(line);
                                boolean done = jsonLine.optBoolean("done", false);

                                if (jsonLine.has("message")) {
                                    JSONObject message = jsonLine.getJSONObject("message");
                                    String content = message.optString("content", "");
                                    if (!content.isEmpty()) {
                                        if (isFirstChunk) {
                                            appendConversation("System: (updating conversation summary)\n");
                                            isFirstChunk = false;
                                        }
                                        fullSummary.append(content);
                                        appendConversation(content);
                                    }
                                }

                                if (done) break;
                            } catch (Exception e) {
                                appendConversation("Summary parse error: " + e.getMessage() + "\n");
                            }
                        }

                        appendConversation("\n");

                        String summaryText = fullSummary.toString().trim();

                        // If we got a summary, prune history and set conversationSummary accordingly
                        if (!summaryText.isEmpty()) {
                            pruneHistoryAfterSummary(summaryText);
                        } else {
                            // no summary produced => prune without summary to keep history bounded
                            pruneHistoryAfterSummary(null);
                        }
                    } catch (Exception e) {
                        appendConversation("Summary streaming error: " + e.getMessage() + "\n");
                        pruneHistoryAfterSummary(null);
                    } finally {
                        isSummarizationInProgress = false;
                        updateSendButtonState();
                    }
                }
            });
        } catch (Exception e) {
            appendConversation("Summary request build error: " + e.getMessage() + "\n");
            pruneHistoryAfterSummary(null);
            isSummarizationInProgress = false;
            updateSendButtonState();
        }
    }
}