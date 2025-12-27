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

import java.io.IOException;
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

    private void sendChatToOllama(String userMessage) {
        try {
            JSONObject bodyJson = new JSONObject();
            // 使用するモデルを gemma3:1b に設定
            bodyJson.put("model", "gemma3:1b");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "You are a helpful assistant."));
            messages.put(new JSONObject().put("role", "user").put("content", userMessage));
            bodyJson.put("messages", messages);
            bodyJson.put("stream", false);

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
                    String respBody = response.body().string();
                    try {
                        JSONObject respJson = new JSONObject(respBody);

                        // 新しい形式: top-level "message": { "role":"assistant", "content":"..." }
                        if (respJson.has("message")) {
                            JSONObject msg = respJson.optJSONObject("message");
                            if (msg != null) {
                                String role = msg.optString("role", "");
                                String content = msg.optString("content", "");
                                final String finalText = (content != null && !content.isEmpty())
                                        ? content
                                        : "(no assistant response)";
                                if ("assistant".equals(role)) {
                                    appendConversation("Assistant: " + finalText + "\n");
                                } else if (!role.isEmpty()) {
                                    appendConversation(role + ": " + finalText + "\n");
                                } else {
                                    // role が無い／空の場合でも content を出力
                                    appendConversation("Assistant: " + finalText + "\n");
                                }
                                return;
                            } else {
                                appendConversation("Unexpected 'message' format: " + respBody + "\n");
                                return;
                            }
                        }

                        // 旧形式: "messages": [ ... ]
                        if (respJson.has("messages")) {
                            JSONArray msgs = respJson.getJSONArray("messages");
                            String assistantText = null;
                            for (int i = msgs.length() - 1; i >= 0; i--) {
                                JSONObject m = msgs.getJSONObject(i);
                                if ("assistant".equals(m.optString("role"))) {
                                    assistantText = m.optString("content");
                                    break;
                                }
                            }
                            if (assistantText == null) {
                                assistantText = respJson.optString("response", "");
                            }
                            final String finalText = (assistantText != null && !assistantText.isEmpty())
                                    ? assistantText
                                    : "(no assistant response)";
                            appendConversation("Assistant: " + finalText + "\n");
                        } else {
                            // その他のケース: デバッグのため全体を表示
                            appendConversation("Unexpected response: " + respBody + "\n");
                        }
                    } catch (Exception e) {
                        appendConversation("Parse error: " + e.getMessage() + "\nResponse body: " + respBody + "\n");
                    }
                }
            });

        } catch (Exception e) {
            appendConversation("Request build error: " + e.getMessage() + "\n");
        }
    }
}
