package com.example.hello2;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Ollama Chat — ollama-chat (React) のチャット機能を移植した Android 版
 *
 * 機能:
 * - /api/chat によるチャット（Streaming ON/OFF 切替可能）
 * - /api/tags からモデル一覧を取得し選択（初期値: default）
 * - Streaming 有効時はセンテンス単位で TTS 読み上げ
 * - 設定は JSON ファイル (chat_settings.json) に保存
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final String TAG = "OllamaChat";
    private static final String SETTINGS_FILE = "chat_settings.json";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int REQ_RECORD_AUDIO = 1001;

    // --- UI ---
    private TextView tvConversation;
    private EditText etInput;
    private Button btnSend, btnSettings;
    private ScrollView scrollView;
    private View settingsPanel;
    private Spinner spinnerModel;
    private Switch switchStreaming, switchTts, switchVoiceInput;
    private EditText etOllamaUrl, etSpeechLang, etSpeechRate, etSpeechPitch, etSystemPrompt;

    // --- 設定（デフォルト値） ---
    private String ollamaBaseUrl = "http://localhost:11434";
    private String selectedModel = "default";
    private boolean streamingEnabled = true;
    private boolean ttsEnabled = false;
    private boolean voiceInputEnabled = true;
    private String speechLang = "ja-JP";
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String systemPromptText = "あなたはユーザの若い女性秘書です";

    // --- TTS ---
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private final AtomicInteger pendingUtterances = new AtomicInteger(0);

    // --- モデル一覧 ---
    private final List<String> modelList = new ArrayList<>();
    private ArrayAdapter<String> modelAdapter;

    // --- チャット ---
    private final List<JSONObject> conversationHistory = new ArrayList<>();
    private volatile boolean isProcessing = false;
    private volatile boolean isListening = false;
    private final Object historyLock = new Object();

    // --- 音声認識 ---
    private SpeechRecognizer speechRecognizer;
    private boolean pendingVoiceStart = false;
    private boolean triedOnlineFallback = false;
    private boolean currentPreferOffline = true;

    // --- ネットワーク ---
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3600, TimeUnit.SECONDS)
            .writeTimeout(3600, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .callTimeout(3600, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        initViews();
        loadSettings();
        applySettingsToUi();
        initTts();
        initConversationHistory();
        setupListeners();
        fetchModels();
    }

    // ========== UI初期化 ==========

    private void initViews() {
        tvConversation = findViewById(R.id.tvConversation);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        btnSettings = findViewById(R.id.btnSettings);
        scrollView = findViewById(R.id.scrollView);
        settingsPanel = findViewById(R.id.settingsPanel);
        spinnerModel = findViewById(R.id.spinnerModel);
        switchStreaming = findViewById(R.id.switchStreaming);
        switchTts = findViewById(R.id.switchTts);
        switchVoiceInput = findViewById(R.id.switchVoiceInput);
        etOllamaUrl = findViewById(R.id.etOllamaUrl);
        etSpeechLang = findViewById(R.id.etSpeechLang);
        etSpeechRate = findViewById(R.id.etSpeechRate);
        etSpeechPitch = findViewById(R.id.etSpeechPitch);
        etSystemPrompt = findViewById(R.id.etSystemPrompt);

        modelList.add("default");
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(modelAdapter);
    }

    private void setupListeners() {
        btnSettings.setOnClickListener(v -> {
            if (settingsPanel.getVisibility() == View.VISIBLE) {
                readSettingsFromUi();
                saveSettings();
                settingsPanel.setVisibility(View.GONE);
                reinitSystemPrompt();
            } else {
                settingsPanel.setVisibility(View.VISIBLE);
            }
        });

        btnSend.setOnClickListener(v -> {
            String userMsg = etInput.getText().toString().trim();
            if (userMsg.isEmpty()) {
                if (voiceInputEnabled) {
                    startVoiceRecognition(true);
                } else {
                    Toast.makeText(this, "入力してください", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            submitUserMessage(userMsg);
        });
    }

    // ========== 設定 I/O（JSONファイル） ==========

    private void loadSettings() {
        try {
            FileInputStream fis = openFileInput(SETTINGS_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject s = new JSONObject(sb.toString());
            ollamaBaseUrl = s.optString("ollamaBaseUrl", ollamaBaseUrl);
            selectedModel = s.optString("selectedModel", selectedModel);
            streamingEnabled = s.optBoolean("streamingEnabled", streamingEnabled);
            ttsEnabled = s.optBoolean("ttsEnabled", ttsEnabled);
            voiceInputEnabled = s.optBoolean("voiceInputEnabled", voiceInputEnabled);
            speechLang = s.optString("speechLang", speechLang);
            speechRate = (float) s.optDouble("speechRate", speechRate);
            speechPitch = (float) s.optDouble("speechPitch", speechPitch);
            systemPromptText = s.optString("systemPrompt", systemPromptText);
        } catch (FileNotFoundException e) {
            // 初回起動時: デフォルト値を使用
        } catch (Exception e) {
            Log.e(TAG, "loadSettings error", e);
        }
    }

    private void saveSettings() {
        try {
            JSONObject s = new JSONObject();
            s.put("ollamaBaseUrl", ollamaBaseUrl);
            s.put("selectedModel", selectedModel);
            s.put("streamingEnabled", streamingEnabled);
            s.put("ttsEnabled", ttsEnabled);
            s.put("voiceInputEnabled", voiceInputEnabled);
            s.put("speechLang", speechLang);
            s.put("speechRate", speechRate);
            s.put("speechPitch", speechPitch);
            s.put("systemPrompt", systemPromptText);

            FileOutputStream fos = openFileOutput(SETTINGS_FILE, MODE_PRIVATE);
            fos.write(s.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "saveSettings error", e);
        }
    }

    private void readSettingsFromUi() {
        ollamaBaseUrl = etOllamaUrl.getText().toString().trim();
        if (ollamaBaseUrl.isEmpty()) ollamaBaseUrl = "http://localhost:11434";
        streamingEnabled = switchStreaming.isChecked();
        ttsEnabled = switchTts.isChecked();
        voiceInputEnabled = switchVoiceInput.isChecked();
        speechLang = etSpeechLang.getText().toString().trim();
        if (speechLang.isEmpty()) speechLang = "ja-JP";
        try {
            speechRate = Float.parseFloat(etSpeechRate.getText().toString());
        } catch (Exception e) {
            speechRate = 1.0f;
        }
        try {
            speechPitch = Float.parseFloat(etSpeechPitch.getText().toString());
        } catch (Exception e) {
            speechPitch = 1.0f;
        }
        systemPromptText = etSystemPrompt.getText().toString().trim();
        if (spinnerModel.getSelectedItem() != null) {
            selectedModel = spinnerModel.getSelectedItem().toString();
        }
    }

    private void applySettingsToUi() {
        etOllamaUrl.setText(ollamaBaseUrl);
        switchStreaming.setChecked(streamingEnabled);
        switchTts.setChecked(ttsEnabled);
        switchVoiceInput.setChecked(voiceInputEnabled);
        etSpeechLang.setText(speechLang);
        etSpeechRate.setText(String.valueOf(speechRate));
        etSpeechPitch.setText(String.valueOf(speechPitch));
        etSystemPrompt.setText(systemPromptText);

        int idx = modelList.indexOf(selectedModel);
        if (idx >= 0) spinnerModel.setSelection(idx);
    }

    // ========== TTS ==========

    private void initTts() {
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true;
            applyTtsSettings();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    pendingUtterances.decrementAndGet();
                }
                @Override public void onError(String utteranceId) {
                    pendingUtterances.decrementAndGet();
                }
            });
        } else {
            Log.w(TAG, "TTS init failed");
        }
    }

    private void applyTtsSettings() {
        if (!ttsReady) return;
        try {
            String[] parts = speechLang.split("-");
            Locale locale = parts.length >= 2 ? new Locale(parts[0], parts[1]) : new Locale(parts[0]);
            tts.setLanguage(locale);
        } catch (Exception e) {
            tts.setLanguage(Locale.JAPAN);
        }
        tts.setSpeechRate(speechRate);
        tts.setPitch(speechPitch);
    }

    private void speakSentence(String text) {
        if (!ttsEnabled || !ttsReady) return;
        // テキスト正規化（ollama-chat の speak() と同等）
        String clean = text
                .replaceAll("[\\n\\r\\t]", "、")
                .replaceAll("[!@#$%^&*()_+={}\\[\\]|\\\\:;<>.?/]", "、")
                .replaceAll("[,\u201c\u201d]", " ")
                .replaceAll("、+", "、")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return;

        applyTtsSettings();
        pendingUtterances.incrementAndGet();
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "utt_" + System.currentTimeMillis());
    }

    /** ストリーミング中のチャンク処理: 文末で区切って逐次読み上げ */
    private void processChunkForTts(String chunk) {
        sentenceBuffer.append(chunk);
        String text = sentenceBuffer.toString();

        // 最後の文末文字を探す
        int lastBoundary = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("。！？.!?\n".indexOf(c) >= 0) {
                lastBoundary = i;
            }
        }

        if (lastBoundary >= 0) {
            String completePart = text.substring(0, lastBoundary + 1);
            sentenceBuffer.setLength(0);
            sentenceBuffer.append(text.substring(lastBoundary + 1));

            for (String sentence : completePart.split("(?<=[。！？.!?\\n])")) {
                String trimmed = sentence.trim();
                if (!trimmed.isEmpty()) {
                    speakSentence(trimmed);
                }
            }
        }
    }

    /** バッファに残ったテキストを読み上げ */
    private void flushSentenceBuffer() {
        String remaining = sentenceBuffer.toString().trim();
        sentenceBuffer.setLength(0);
        if (!remaining.isEmpty()) {
            speakSentence(remaining);
        }
    }

    private void stopTts() {
        if (tts != null) tts.stop();
        sentenceBuffer.setLength(0);
        pendingUtterances.set(0);
    }

    // ========== 会話履歴 ==========

    private void initConversationHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            try {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemPromptText);
                conversationHistory.add(sys);
            } catch (Exception e) {
                Log.e(TAG, "initHistory error", e);
            }
        }
    }

    private void reinitSystemPrompt() {
        synchronized (historyLock) {
            if (!conversationHistory.isEmpty()) {
                try {
                    JSONObject sys = conversationHistory.get(0);
                    if ("system".equals(sys.optString("role"))) {
                        sys.put("content", systemPromptText);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "reinitSystemPrompt error", e);
                }
            }
        }
    }

    private void addToHistory(String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            synchronized (historyLock) {
                conversationHistory.add(msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "addToHistory error", e);
        }
    }

    // ========== モデル取得 (/api/tags) ==========

    private void fetchModels() {
        String url = ollamaBaseUrl + "/api/tags";
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "fetchModels failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray models = json.getJSONArray("models");
                    List<String> names = new ArrayList<>();
                    names.add("default");
                    for (int i = 0; i < models.length(); i++) {
                        String name = models.getJSONObject(i).getString("name");
                        if (!names.contains(name)) names.add(name);
                    }
                    runOnUiThread(() -> {
                        modelList.clear();
                        modelList.addAll(names);
                        modelAdapter.notifyDataSetChanged();
                        int idx = modelList.indexOf(selectedModel);
                        if (idx >= 0) spinnerModel.setSelection(idx);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "fetchModels parse error", e);
                }
            }
        });
    }

    // ========== チャット送信 ==========

    private void updateSendButton() {
        runOnUiThread(() -> btnSend.setEnabled(!isProcessing && !isListening));
    }

    private void submitUserMessage(String userMsg) {
        if (userMsg == null || userMsg.trim().isEmpty()) return;
        etInput.setText("");
        appendConversation("You: " + userMsg + "\n");
        addToHistory("user", userMsg);
        sendChat();
    }

    private Intent buildRecognizerIntent(boolean preferOffline) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String lang = (speechLang != null && !speechLang.isEmpty())
                ? speechLang
                : Locale.getDefault().toLanguageTag();
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        return intent;
    }

    private void startVoiceRecognition(boolean preferOffline) {
        if (isProcessing || isListening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "音声認識が利用できません", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }

        isListening = true;
        updateSendButton();
        currentPreferOffline = preferOffline;
        triedOnlineFallback = !preferOffline;

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        } else {
            speechRecognizer.cancel();
        }

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                isListening = false;
                updateSendButton();
                boolean shouldFallback = currentPreferOffline && !triedOnlineFallback
                        && (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
                if (shouldFallback) {
                    triedOnlineFallback = true;
                    Toast.makeText(MainActivity.this,
                            "オフライン音声認識に失敗したためオンラインに切り替えます",
                            Toast.LENGTH_SHORT).show();
                    startVoiceRecognition(false);
                    return;
                }
                Toast.makeText(MainActivity.this, "音声認識に失敗しました", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                updateSendButton();
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : "";
                if (best.isEmpty()) {
                    Toast.makeText(MainActivity.this, "音声認識結果が空でした", Toast.LENGTH_SHORT).show();
                    return;
                }
                submitUserMessage(best);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizer.startListening(buildRecognizerIntent(preferOffline));
    }

    private void sendChat() {
        if (spinnerModel.getSelectedItem() != null) {
            selectedModel = spinnerModel.getSelectedItem().toString();
        }
        // 設定パネルが開いていたら最新値を反映
        readSettingsFromUi();

        isProcessing = true;
        updateSendButton();
        stopTts();

        try {
            JSONArray messages = new JSONArray();
            synchronized (historyLock) {
                for (JSONObject msg : conversationHistory) {
                    messages.put(msg);
                }
            }

            JSONObject body = new JSONObject();
            body.put("model", selectedModel);
            body.put("messages", messages);
            body.put("stream", streamingEnabled);

            RequestBody requestBody = RequestBody.create(body.toString(), JSON_MEDIA);
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(requestBody)
                    .build();

            if (streamingEnabled) {
                sendStreaming(request);
            } else {
                sendNonStreaming(request);
            }
        } catch (Exception e) {
            appendConversation("Error: " + e.getMessage() + "\n");
            isProcessing = false;
            updateSendButton();
        }
    }

    /** ストリーミングモード: チャンクごとに表示＋センテンス単位TTS */
    private void sendStreaming(Request request) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                appendConversation("Error: " + e.getMessage() + "\n");
                isProcessing = false;
                updateSendButton();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    appendConversation("HTTP error: " + response.code() + "\n");
                    isProcessing = false;
                    updateSendButton();
                    return;
                }

                StringBuilder fullResponse = new StringBuilder();
                boolean first = true;

                try (InputStream is = response.body().byteStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            JSONObject json = new JSONObject(line);
                            boolean done = json.optBoolean("done", false);

                            if (json.has("message")) {
                                String content = json.getJSONObject("message")
                                        .optString("content", "");
                                if (!content.isEmpty()) {
                                    if (first) {
                                        appendConversation("Assistant: ");
                                        first = false;
                                    }
                                    fullResponse.append(content);
                                    appendConversation(content);

                                    if (ttsEnabled) {
                                        processChunkForTts(content);
                                    }
                                }
                            }
                            if (done) break;
                        } catch (Exception e) {
                            Log.e(TAG, "Stream parse error", e);
                        }
                    }

                    appendConversation("\n");

                    if (ttsEnabled) {
                        flushSentenceBuffer();
                    }

                    String text = fullResponse.toString();
                    if (!text.isEmpty()) {
                        addToHistory("assistant", text);
                    }
                } catch (Exception e) {
                    appendConversation("Stream error: " + e.getMessage() + "\n");
                } finally {
                    isProcessing = false;
                    updateSendButton();
                }
            }
        });
    }

    /** 非ストリーミングモード: 完全なレスポンスを受信後に表示＋TTS */
    private void sendNonStreaming(Request request) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                appendConversation("Error: " + e.getMessage() + "\n");
                isProcessing = false;
                updateSendButton();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    appendConversation("HTTP error: " + response.code() + "\n");
                    isProcessing = false;
                    updateSendButton();
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String content = "";
                    if (json.has("message")) {
                        content = json.getJSONObject("message").optString("content", "");
                    }

                    if (!content.isEmpty()) {
                        appendConversation("Assistant: " + content + "\n");
                        addToHistory("assistant", content);

                        if (ttsEnabled) {
                            // センテンス単位で読み上げ
                            for (String sentence : content.split("(?<=[。！？.!?\\n])")) {
                                String trimmed = sentence.trim();
                                if (!trimmed.isEmpty()) {
                                    speakSentence(trimmed);
                                }
                            }
                        }
                    } else {
                        appendConversation("Assistant: （応答なし）\n");
                    }
                } catch (Exception e) {
                    appendConversation("Parse error: " + e.getMessage() + "\n");
                } finally {
                    isProcessing = false;
                    updateSendButton();
                }
            }
        });
    }

    // ========== UI ヘルパー ==========

    private void appendConversation(String text) {
        runOnUiThread(() -> {
            tvConversation.append(text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ========== ライフサイクル ==========

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingVoiceStart) {
                pendingVoiceStart = false;
                startVoiceRecognition(true);
            } else {
                pendingVoiceStart = false;
                Toast.makeText(this, "マイクの権限が必要です", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        isListening = false;
        updateSendButton();
        readSettingsFromUi();
        saveSettings();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        super.onDestroy();
    }
}
