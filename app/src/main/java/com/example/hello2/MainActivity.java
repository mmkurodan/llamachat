package com.example.hello2;

import android.app.Activity;
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
import java.io.StringReader;
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
 * モデル: gemma3:270m
 */
public class MainActivity extends Activity {
    private TextView tvConversation;
    private EditText etInput;
    private Button btnSend;
    private ScrollView scrollView;
    
    // Conversation history: list of messages (system, user, assistant)
    private List<JSONObject> conversationHistory;

    // 実機で Ollama が同じ端末��で動作している場合（adb reverse でフォワード済みなど）は localhost を使える
    // 例: "http://localhost:11434/api/chat"
    // もし Ollama が PC 上で動いて実機が Wi-Fi 接続の場合は PC のローカルIP に置き換えてください:
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

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String userMsg = etInput.getText().toString().trim();
                if (userMsg.isEmpty()) {
                    Toast.makeText(MainActivity.this, "入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                appendConversation("You: " + userMsg + "\n");
                etInput.setText("");
                sendChatToOllama(userMsg);
            }
        });
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
     * Maintain conversation history with max 10 user/assistant pairs plus system prompt
     */
    private void trimConversationHistory() {
        // Keep system message at index 0
        // Count user/assistant pairs (starting from index 1)
        int pairCount = 0;
        for (int i = 1; i < conversationHistory.size(); i++) {
            try {
                String role = conversationHistory.get(i).getString("role");
                if ("user".equals(role)) {
                    pairCount++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // If we have more than 10 pairs, remove oldest messages
        while (pairCount > 10) {
            // Remove the oldest user message (index 1, right after system)
            // and the following assistant message
            if (conversationHistory.size() > 1) {
                conversationHistory.remove(1);
            }
            if (conversationHistory.size() > 1) {
                conversationHistory.remove(1);
            }
            pairCount--;
        }
    }

    private void sendChatToOllama(String userMessage) {
        try {
            // Add user message to history
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            conversationHistory.add(userMsg);
            
            // Trim history to maintain max 10 pairs
            trimConversationHistory();
            
            JSONObject bodyJson = new JSONObject();
            // 使用するモデルを gemma3:1b に設定
            bodyJson.put("model", "gemma3:1b");
            
            // Build messages array from conversation history
            JSONArray messages = new JSONArray();
            for (JSONObject msg : conversationHistory) {
                messages.put(msg);
            }
            bodyJson.put("messages", messages);
            bodyJson.put("stream", true);  // Enable streaming

            RequestBody requestBody = RequestBody.create(bodyJson.toString(), JSON);
            Request request = new Request.Builder()
                    .url(OLLAMA_CHAT_URL)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    appendConversation("Error: " + e.getMessage() + "\n");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        appendConversation("HTTP error: " + response.code() + "\n");
                        return;
                    }
                    
                    // Handle streaming response (NDJSON format)
                    final StringBuilder fullAssistantResponse = new StringBuilder();
                    boolean isFirstChunk = true;
                    
                    try {
                        String responseBody = response.body().string();
                        BufferedReader reader = new BufferedReader(new StringReader(responseBody));
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
                                
                                // If done, add newline and save to history
                                if (done) {
                                    appendConversation("\n");
                                    
                                    // Add assistant response to conversation history
                                    try {
                                        JSONObject assistantMsg = new JSONObject();
                                        assistantMsg.put("role", "assistant");
                                        assistantMsg.put("content", fullAssistantResponse.toString());
                                        conversationHistory.add(assistantMsg);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                appendConversation("Parse error on line: " + e.getMessage() + "\n");
                            }
                        }
                    } catch (Exception e) {
                        appendConversation("Streaming error: " + e.getMessage() + "\n");
                    }
                }
            });

        } catch (Exception e) {
            appendConversation("Request build error: " + e.getMessage() + "\n");
        }
    }
}
