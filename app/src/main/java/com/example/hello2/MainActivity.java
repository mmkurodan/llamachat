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
import java.io.InputStream;
import java.io.InputStreamReader;
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
        // Keep system message at index 0, remove oldest user/assistant pairs
        // Count only user messages to determine number of pairs
        int userCount = 0;
        for (int i = 1; i < conversationHistory.size(); i++) {
            try {
                String role = conversationHistory.get(i).getString("role");
                if ("user".equals(role)) {
                    userCount++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // Remove oldest messages (user and possibly assistant) until we have <= 10 user messages
        while (userCount > 10 && conversationHistory.size() > 1) {
            // Find and remove the first user message after system prompt
            for (int i = 1; i < conversationHistory.size(); i++) {
                try {
                    String role = conversationHistory.get(i).getString("role");
                    if ("user".equals(role)) {
                        conversationHistory.remove(i);
                        userCount--;
                        // Also remove the following assistant message if it exists
                        if (i < conversationHistory.size()) {
                            try {
                                String nextRole = conversationHistory.get(i).getString("role");
                                if ("assistant".equals(nextRole)) {
                                    conversationHistory.remove(i);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
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
                    boolean streamComplete = false;
                    
                    try {
                        // Use byteStream for true streaming instead of reading entire response
                        InputStream inputStream = response.body().byteStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
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
                                
                                // If done, mark as complete
                                if (done) {
                                    streamComplete = true;
                                    break;
                                }
                            } catch (Exception e) {
                                appendConversation("Parse error on line: " + e.getMessage() + "\n");
                            }
                        }
                        
                        // Always add newline at the end
                        appendConversation("\n");
                        
                        // Save assistant response to history even if stream was interrupted
                        if (fullAssistantResponse.length() > 0) {
                            try {
                                JSONObject assistantMsg = new JSONObject();
                                assistantMsg.put("role", "assistant");
                                assistantMsg.put("content", fullAssistantResponse.toString());
                                conversationHistory.add(assistantMsg);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (!streamComplete) {
                            // If no content received and stream didn't complete, add error message
                            appendConversation("(Stream incomplete - no response received)\n");
                        }
                    } catch (Exception e) {
                        appendConversation("Streaming error: " + e.getMessage() + "\n");
                        // Even on error, try to save partial response if any
                        if (fullAssistantResponse.length() > 0) {
                            try {
                                JSONObject assistantMsg = new JSONObject();
                                assistantMsg.put("role", "assistant");
                                assistantMsg.put("content", fullAssistantResponse.toString());
                                conversationHistory.add(assistantMsg);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                }
            });

        } catch (Exception e) {
            appendConversation("Request build error: " + e.getMessage() + "\n");
        }
    }
}
