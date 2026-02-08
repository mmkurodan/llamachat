package com.example.hello2;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
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
import java.util.Random;
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
    private static final int REQ_PICK_C0 = 2000;
    private static final int REQ_PICK_C1 = 2001;
    private static final int REQ_PICK_C2 = 2002;
    private static final int REQ_PICK_C3 = 2003;
    private static final String AVATAR_C0_FILE = "avatar_c0.jpg";
    private static final String AVATAR_C1_FILE = "avatar_c1.jpg";
    private static final String AVATAR_C2_FILE = "avatar_c2.jpg";
    private static final String AVATAR_C3_FILE = "avatar_c3.jpg";
    private static final int TTS_WARMUP_MS = 120;
    private static final int AVATAR_TALK_FRAME_MS = 120;
    private static final int AVATAR_BLINK_MIN_MS = 3000;
    private static final int AVATAR_BLINK_MAX_MS = 7000;
    private static final int AVATAR_BLINK_DURATION_MS = 120;

    // --- UI ---
    private TextView tvConversation;
    private EditText etInput;
    private Button btnSend, btnSettings, btnPickC0, btnPickC1, btnPickC2, btnPickC3;
    private ScrollView scrollView;
    private View settingsPanel;
    private Spinner spinnerModel;
    private Switch switchStreaming, switchTts, switchVoiceInput, switchAutoChatter, switchAutoVoiceInput, switchWebSearch;
    private EditText etOllamaUrl, etSpeechLang, etSpeechRate, etSpeechPitch, etSystemPrompt;
    private EditText etHistoryLimit, etAutoChatterSeconds;
    private EditText etWebSearchUrl, etWebSearchApiKey;
    private ImageView ivAvatarBackground;
    private ImageView ivAvatar;

    // --- 設定（デフォルト値） ---
    private String ollamaBaseUrl = "http://localhost:11434";
    private String selectedModel = "default";
    private boolean streamingEnabled = true;
    private boolean ttsEnabled = false;
    private boolean voiceInputEnabled = true;
    private boolean autoChatterEnabled = false;
    private boolean autoVoiceInputEnabled = false;
    private boolean webSearchEnabled = false;
    private String webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
    private String webSearchApiKey = "";
    private String speechLang = "ja-JP";
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String systemPromptText = "あなたはユーザの若い女性秘書です";
    private int historyLimit = 10;
    private int autoChatterSeconds = 30;

    // --- TTS ---
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsNeedsWarmup = true;
    private boolean isTtsSpeaking = false;
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private final AtomicInteger pendingUtterances = new AtomicInteger(0);
    private final List<String> pendingTtsQueue = new ArrayList<>();
    private boolean pendingAutoVoiceStart = false;

    private enum AvatarMode { IDLE, TALKING }

    // --- アバター ---
    private final Handler avatarHandler = new Handler(Looper.getMainLooper());
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Random avatarRandom = new Random();
    private final int[] talkFrames = new int[]{
            R.drawable.c1, R.drawable.c3
    };
    private File avatarC0File;
    private File avatarC1File;
    private File avatarC2File;
    private File avatarC3File;
    private Bitmap avatarC0Bitmap;
    private Bitmap avatarC1Bitmap;
    private Bitmap avatarC2Bitmap;
    private Bitmap avatarC3Bitmap;
    private int talkFrameIndex = 0;
    private boolean isStreamingResponse = false;
    private AvatarMode avatarMode = AvatarMode.IDLE;
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(R.drawable.c2);
            avatarHandler.postDelayed(blinkResetRunnable, AVATAR_BLINK_DURATION_MS);
        }
    };
    private final Runnable blinkResetRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(R.drawable.c1);
            scheduleNextBlink();
        }
    };
    private final Runnable talkRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(talkFrames[talkFrameIndex]);
            talkFrameIndex = (talkFrameIndex + 1) % talkFrames.length;
            avatarHandler.postDelayed(this, AVATAR_TALK_FRAME_MS);
        }
    };

    private final Runnable autoChatterRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoChatterEnabled) return;
            if (isProcessing || isListening) {
                autoHandler.postDelayed(this, 1000);
                return;
            }
            sendChat("さらに続けて");
        }
    };

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
        initAvatarAssets();
        initAvatarAnimation();
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
        btnPickC0 = findViewById(R.id.btnPickC0);
        btnPickC1 = findViewById(R.id.btnPickC1);
        btnPickC2 = findViewById(R.id.btnPickC2);
        btnPickC3 = findViewById(R.id.btnPickC3);
        ivAvatarBackground = findViewById(R.id.ivAvatarBackground);
        ivAvatar = findViewById(R.id.ivAvatar);
        scrollView = findViewById(R.id.scrollView);
        settingsPanel = findViewById(R.id.settingsPanel);
        spinnerModel = findViewById(R.id.spinnerModel);
        switchStreaming = findViewById(R.id.switchStreaming);
        switchTts = findViewById(R.id.switchTts);
        switchVoiceInput = findViewById(R.id.switchVoiceInput);
        switchAutoChatter = findViewById(R.id.switchAutoChatter);
        switchAutoVoiceInput = findViewById(R.id.switchAutoVoiceInput);
        etOllamaUrl = findViewById(R.id.etOllamaUrl);
        etSpeechLang = findViewById(R.id.etSpeechLang);
        etSpeechRate = findViewById(R.id.etSpeechRate);
        etSpeechPitch = findViewById(R.id.etSpeechPitch);
        etSystemPrompt = findViewById(R.id.etSystemPrompt);
        etHistoryLimit = findViewById(R.id.etHistoryLimit);
        etAutoChatterSeconds = findViewById(R.id.etAutoChatterSeconds);
        switchWebSearch = findViewById(R.id.switchWebSearch);
        etWebSearchUrl = findViewById(R.id.etWebSearchUrl);
        etWebSearchApiKey = findViewById(R.id.etWebSearchApiKey);

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

        btnPickC0.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C0));
        btnPickC1.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C1));
        btnPickC2.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C2));
        btnPickC3.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C3));
    }

    // ========== アバター ==========

    private void initAvatarAssets() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Log.w(TAG, "External files dir unavailable");
            return;
        }
        avatarC0File = new File(dir, AVATAR_C0_FILE);
        avatarC1File = new File(dir, AVATAR_C1_FILE);
        avatarC2File = new File(dir, AVATAR_C2_FILE);
        avatarC3File = new File(dir, AVATAR_C3_FILE);
        reloadAvatarBitmaps();
    }

    private void reloadAvatarBitmaps() {
        avatarC0Bitmap = loadAvatarBitmap(avatarC0File);
        avatarC1Bitmap = loadAvatarBitmap(avatarC1File);
        avatarC2Bitmap = loadAvatarBitmap(avatarC2File);
        avatarC3Bitmap = loadAvatarBitmap(avatarC3File);
    }

    private Bitmap loadAvatarBitmap(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            Log.w(TAG, "Failed to decode avatar image: " + file.getAbsolutePath());
        }
        return bitmap;
    }

    private void pickAvatarImage(int requestCode) {
        if (avatarC0File == null) {
            Toast.makeText(this, "外部ファイルフォルダが利用できません", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, requestCode);
    }

    private File getAvatarFileForRequest(int requestCode) {
        switch (requestCode) {
            case REQ_PICK_C0:
                return avatarC0File;
            case REQ_PICK_C1:
                return avatarC1File;
            case REQ_PICK_C2:
                return avatarC2File;
            case REQ_PICK_C3:
                return avatarC3File;
            default:
                return null;
        }
    }

    private int getAvatarResIdForRequest(int requestCode) {
        switch (requestCode) {
            case REQ_PICK_C0:
                return R.drawable.c0;
            case REQ_PICK_C1:
                return R.drawable.c1;
            case REQ_PICK_C2:
                return R.drawable.c2;
            case REQ_PICK_C3:
                return R.drawable.c3;
            default:
                return 0;
        }
    }

    private void applyAvatarSelection(int requestCode) {
        int resId = getAvatarResIdForRequest(requestCode);
        if (resId == 0) return;
        if (requestCode == REQ_PICK_C0) {
            setAvatarBackground(resId);
        } else {
            setAvatarFrame(resId);
        }
    }

    private boolean copyAvatarUriToFile(Uri uri, File target) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyAvatar error", e);
            return false;
        }
    }

    private void initAvatarAnimation() {
        avatarMode = AvatarMode.IDLE;
        setAvatarBackground(R.drawable.c0);
        startIdleAnimation();
    }

    private void setAvatarBackground(int resId) {
        if (ivAvatarBackground != null) {
            Bitmap custom = null;
            if (resId == R.drawable.c0) {
                custom = avatarC0Bitmap;
            }
            if (custom != null) {
                ivAvatarBackground.setImageBitmap(custom);
            } else {
                ivAvatarBackground.setImageResource(resId);
            }
        }
    }

    private void setAvatarFrame(int resId) {
        if (ivAvatar != null) {
            Bitmap custom = null;
            if (resId == R.drawable.c1) {
                custom = avatarC1Bitmap;
            } else if (resId == R.drawable.c2) {
                custom = avatarC2Bitmap;
            } else if (resId == R.drawable.c3) {
                custom = avatarC3Bitmap;
            }
            if (custom != null) {
                ivAvatar.setImageBitmap(custom);
            } else {
                ivAvatar.setImageResource(resId);
            }
        }
    }

    private void scheduleNextBlink() {
        avatarHandler.removeCallbacks(blinkRunnable);
        avatarHandler.removeCallbacks(blinkResetRunnable);
        int delay = AVATAR_BLINK_MIN_MS
                + avatarRandom.nextInt(AVATAR_BLINK_MAX_MS - AVATAR_BLINK_MIN_MS + 1);
        avatarHandler.postDelayed(blinkRunnable, delay);
    }

    private void startIdleAnimation() {
        stopTalkAnimation();
        setAvatarFrame(R.drawable.c1);
        scheduleNextBlink();
    }

    private void stopIdleAnimation() {
        avatarHandler.removeCallbacks(blinkRunnable);
        avatarHandler.removeCallbacks(blinkResetRunnable);
    }

    private void startTalkAnimation() {
        stopIdleAnimation();
        talkFrameIndex = 0;
        avatarHandler.removeCallbacks(talkRunnable);
        avatarHandler.post(talkRunnable);
    }

    private void stopTalkAnimation() {
        avatarHandler.removeCallbacks(talkRunnable);
    }

    private void stopAvatarAnimation() {
        stopIdleAnimation();
        stopTalkAnimation();
    }

    private void updateAvatarAnimation() {
        boolean shouldTalk = ttsEnabled ? isTtsSpeaking : isStreamingResponse;
        runOnUiThread(() -> {
            if (shouldTalk) {
                if (avatarMode != AvatarMode.TALKING) {
                    avatarMode = AvatarMode.TALKING;
                    startTalkAnimation();
                }
            } else if (avatarMode != AvatarMode.IDLE) {
                avatarMode = AvatarMode.IDLE;
                startIdleAnimation();
            }
        });
    }

    private void setStreamingResponse(boolean active) {
        isStreamingResponse = active;
        updateAvatarAnimation();
    }

    private void cancelAutoChatter() {
        autoHandler.removeCallbacks(autoChatterRunnable);
    }

    private void scheduleAutoChatter() {
        cancelAutoChatter();
        if (!autoChatterEnabled || autoChatterSeconds <= 0) return;
        autoHandler.postDelayed(autoChatterRunnable, autoChatterSeconds * 1000L);
    }

    private void handleAssistantResponseComplete() {
        runOnUiThread(() -> {
            if (autoVoiceInputEnabled) {
                if (ttsEnabled && pendingUtterances.get() > 0) {
                    pendingAutoVoiceStart = true;
                } else {
                    startVoiceRecognition(true);
                }
            } else {
                scheduleAutoChatter();
            }
        });
    }

    private void checkAutoVoiceAfterTts() {
        if (pendingAutoVoiceStart && pendingUtterances.get() <= 0) {
            pendingAutoVoiceStart = false;
            runOnUiThread(() -> startVoiceRecognition(true));
        }
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
            autoChatterEnabled = s.optBoolean("autoChatterEnabled", autoChatterEnabled);
            autoVoiceInputEnabled = s.optBoolean("autoVoiceInputEnabled", autoVoiceInputEnabled);
            speechLang = s.optString("speechLang", speechLang);
            speechRate = (float) s.optDouble("speechRate", speechRate);
            speechPitch = (float) s.optDouble("speechPitch", speechPitch);
            systemPromptText = s.optString("systemPrompt", systemPromptText);
            historyLimit = s.optInt("historyLimit", historyLimit);
            autoChatterSeconds = s.optInt("autoChatterSeconds", autoChatterSeconds);
            webSearchEnabled = s.optBoolean("webSearchEnabled", webSearchEnabled);
            webSearchUrl = s.optString("webSearchUrl", webSearchUrl);
            webSearchApiKey = s.optString("webSearchApiKey", webSearchApiKey);
            if (historyLimit < 0) historyLimit = 0;
            if (autoChatterSeconds < 0) autoChatterSeconds = 0;
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
            s.put("autoChatterEnabled", autoChatterEnabled);
            s.put("autoVoiceInputEnabled", autoVoiceInputEnabled);
            s.put("speechLang", speechLang);
            s.put("speechRate", speechRate);
            s.put("speechPitch", speechPitch);
            s.put("systemPrompt", systemPromptText);
            s.put("historyLimit", historyLimit);
            s.put("autoChatterSeconds", autoChatterSeconds);
            s.put("webSearchEnabled", webSearchEnabled);
            s.put("webSearchUrl", webSearchUrl);
            s.put("webSearchApiKey", webSearchApiKey);

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
        autoChatterEnabled = switchAutoChatter.isChecked();
        autoVoiceInputEnabled = switchAutoVoiceInput.isChecked();
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
        try {
            historyLimit = Integer.parseInt(etHistoryLimit.getText().toString());
        } catch (Exception e) {
            historyLimit = 10;
        }
        if (historyLimit < 0) historyLimit = 0;
        try {
            autoChatterSeconds = Integer.parseInt(etAutoChatterSeconds.getText().toString());
        } catch (Exception e) {
            autoChatterSeconds = 30;
        }
        if (autoChatterSeconds < 0) autoChatterSeconds = 0;
        webSearchEnabled = switchWebSearch.isChecked();
        webSearchUrl = etWebSearchUrl.getText().toString().trim();
        if (webSearchUrl.isEmpty()) webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
        webSearchApiKey = etWebSearchApiKey.getText().toString().trim();
        if (!autoChatterEnabled) {
            cancelAutoChatter();
        }
        if (!autoVoiceInputEnabled) {
            pendingAutoVoiceStart = false;
        }
        if (spinnerModel.getSelectedItem() != null) {
            selectedModel = spinnerModel.getSelectedItem().toString();
        }
    }

    private void applySettingsToUi() {
        etOllamaUrl.setText(ollamaBaseUrl);
        switchStreaming.setChecked(streamingEnabled);
        switchTts.setChecked(ttsEnabled);
        switchVoiceInput.setChecked(voiceInputEnabled);
        switchAutoChatter.setChecked(autoChatterEnabled);
        switchAutoVoiceInput.setChecked(autoVoiceInputEnabled);
        etSpeechLang.setText(speechLang);
        etSpeechRate.setText(String.valueOf(speechRate));
        etSpeechPitch.setText(String.valueOf(speechPitch));
        etSystemPrompt.setText(systemPromptText);
        etHistoryLimit.setText(String.valueOf(historyLimit));
        etAutoChatterSeconds.setText(String.valueOf(autoChatterSeconds));
        switchWebSearch.setChecked(webSearchEnabled);
        etWebSearchUrl.setText(webSearchUrl);
        etWebSearchApiKey.setText(webSearchApiKey);

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
            ttsNeedsWarmup = true;
            applyTtsSettings();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (!isWarmupUtterance(utteranceId)) {
                        isTtsSpeaking = true;
                        updateAvatarAnimation();
                    }
                }
                @Override public void onDone(String utteranceId) {
                    decrementPendingUtterances();
                    if (!isWarmupUtterance(utteranceId)) {
                        isTtsSpeaking = false;
                    }
                    updateAvatarAnimation();
                    checkAutoVoiceAfterTts();
                }
                @Override public void onError(String utteranceId) {
                    decrementPendingUtterances();
                    if (!isWarmupUtterance(utteranceId)) {
                        isTtsSpeaking = false;
                    }
                    updateAvatarAnimation();
                    checkAutoVoiceAfterTts();
                }
            });
            drainPendingTtsQueue();
        } else {
            Log.w(TAG, "TTS init failed");
        }
    }

    private void decrementPendingUtterances() {
        int current;
        do {
            current = pendingUtterances.get();
            if (current <= 0) return;
        } while (!pendingUtterances.compareAndSet(current, current - 1));
    }

    private boolean isWarmupUtterance(String utteranceId) {
        return utteranceId != null && utteranceId.startsWith("warmup_");
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

    private void playTtsWarmupIfNeeded() {
        if (!ttsNeedsWarmup) return;
        ttsNeedsWarmup = false;
        if (tts != null) {
            int result = tts.playSilentUtterance(
                    TTS_WARMUP_MS,
                    TextToSpeech.QUEUE_FLUSH,
                    "warmup_" + System.currentTimeMillis());
            if (result == TextToSpeech.SUCCESS) {
                pendingUtterances.incrementAndGet();
            }
        }
    }

    private void drainPendingTtsQueue() {
        List<String> queued;
        synchronized (pendingTtsQueue) {
            if (pendingTtsQueue.isEmpty()) return;
            queued = new ArrayList<>(pendingTtsQueue);
            pendingTtsQueue.clear();
        }
        for (String sentence : queued) {
            speakSentence(sentence);
        }
    }

    private void speakSentence(String text) {
        if (!ttsEnabled) return;
        if (!ttsReady) {
            synchronized (pendingTtsQueue) {
                pendingTtsQueue.add(text);
            }
            return;
        }
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
        playTtsWarmupIfNeeded();
        pendingUtterances.incrementAndGet();
        updateAvatarAnimation();
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
        synchronized (pendingTtsQueue) {
            pendingTtsQueue.clear();
        }
        pendingUtterances.set(0);
        ttsNeedsWarmup = true;
        isTtsSpeaking = false;
        updateAvatarAnimation();
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
        cancelAutoChatter();
        etInput.setText("");
        appendConversation("You: " + userMsg + "\n");
        addToHistory("user", userMsg);

        if (webSearchEnabled && !webSearchApiKey.isEmpty()) {
            performWebSearchFlow(userMsg);
        } else {
            sendChat(null);
        }
    }

    /**
     * Web検索フロー:
     * 1. api/generate でユーザメッセージから検索キーワードを抽出
     * 2. SEARCH: が返れば Web検索APIを呼び出す
     * 3. 検索結果をユーザメッセージに付与して api/chat に渡す
     */
    private void performWebSearchFlow(String userMsg) {
        isProcessing = true;
        updateSendButton();

        new Thread(() -> {
            try {
                String keywords = extractSearchKeywords(userMsg);
                if (keywords != null) {
                    String searchResults = callWebSearchApi(keywords);
                    if (searchResults != null && !searchResults.isEmpty()) {
                        // 最後に追加したuserメッセージを検索結果付きに差し替え
                        synchronized (historyLock) {
                            if (conversationHistory.size() > 0) {
                                JSONObject lastMsg = conversationHistory.get(conversationHistory.size() - 1);
                                if ("user".equals(lastMsg.optString("role"))) {
                                    lastMsg.put("content", userMsg + "\n（検索結果: " + searchResults + "）");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Web search flow error", e);
            } finally {
                isProcessing = false;
                updateSendButton();
                runOnUiThread(() -> sendChat(null));
            }
        }).start();
    }

    /** api/generate を使って検索キーワードを抽出。NONE なら null を返す */
    private String extractSearchKeywords(String userMsg) {
        try {
            if (spinnerModel.getSelectedItem() != null) {
                selectedModel = spinnerModel.getSelectedItem().toString();
            }
            String prompt = "あなたの役割は「ユーザーの質問がインターネット検索を必要とするか判定し、必要なら検索キーワードを抽出する」ことです。\n\n"
                    + "出力は必ず次のどちらか一つだけにしてください：\n\n"
                    + "1. 検索が必要な場合：\nSEARCH: <検索キーワード>\n\n"
                    + "2. 検索が不要な場合：\nNONE\n\n"
                    + "制約：\n"
                    + "- 説明文や理由を書かない\n"
                    + "- 箇条書きや追加情報を含めない\n"
                    + "- キーワードは短く、検索エンジンで使える語句のみ\n"
                    + "- 複数キーワードが必要な場合はスペース区切りで1行にまとめる\n"
                    + "- 出力形式を絶対に変えない\n\n"
                    + "ユーザーの質問：\n「" + userMsg + "」";

            JSONObject body = new JSONObject();
            body.put("model", selectedModel);
            body.put("prompt", prompt);
            body.put("stream", false);

            RequestBody requestBody = RequestBody.create(body.toString(), JSON_MEDIA);
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/generate")
                    .post(requestBody)
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.w(TAG, "extractSearchKeywords HTTP error: " + response.code());
                return null;
            }
            String respBody = response.body().string();
            JSONObject json = new JSONObject(respBody);
            String result = json.optString("response", "").trim();

            if (result.startsWith("SEARCH:")) {
                String keywords = result.substring("SEARCH:".length()).trim();
                if (!keywords.isEmpty()) {
                    Log.d(TAG, "Web search keywords: " + keywords);
                    return keywords;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "extractSearchKeywords error", e);
            return null;
        }
    }

    /** Web検索APIを呼び出して結果のタイトル＋説明文を取得 */
    private String callWebSearchApi(String keywords) {
        try {
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8") + "&count=5";

            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");

            // Brave Search API uses x-subscription-token header
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
            }

            Request request = reqBuilder.build();
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.w(TAG, "callWebSearchApi HTTP error: " + response.code());
                return null;
            }
            String respBody = response.body().string();
            JSONObject json = new JSONObject(respBody);

            // Brave Search API format
            JSONObject webObj = json.optJSONObject("web");
            if (webObj == null) return null;
            JSONArray results = webObj.optJSONArray("results");
            if (results == null || results.length() == 0) return null;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                String title = item.optString("title", "");
                String description = item.optString("description", "");
                if (!title.isEmpty()) {
                    sb.append(title);
                    if (!description.isEmpty()) sb.append(" - ").append(description);
                    sb.append("; ");
                }
            }
            String result = sb.toString().trim();
            Log.d(TAG, "Web search results: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "callWebSearchApi error", e);
            return null;
        }
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
        cancelAutoChatter();
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
                boolean shouldFallback = currentPreferOffline && !triedOnlineFallback
                        && (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
                if (shouldFallback) {
                    triedOnlineFallback = true;
                    isListening = false;
                    updateSendButton();
                    Toast.makeText(MainActivity.this,
                            "オフライン音声認識に失敗したためオンラインに切り替えます",
                            Toast.LENGTH_SHORT).show();
                    startVoiceRecognition(false);
                    return;
                }
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(MainActivity.this, "音声認識に失敗しました", Toast.LENGTH_SHORT).show();
                }
                handleVoiceRecognitionFinished();
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : "";
                if (best.isEmpty()) {
                    handleVoiceRecognitionFinished();
                    return;
                }
                handleVoiceRecognitionFinished();
                submitUserMessage(best);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizer.startListening(buildRecognizerIntent(preferOffline));
    }

    private void handleVoiceRecognitionFinished() {
        isListening = false;
        updateSendButton();
        if (autoChatterEnabled) {
            scheduleAutoChatter();
        }
    }

    private void sendChat(String transientUserMessage) {
        if (spinnerModel.getSelectedItem() != null) {
            selectedModel = spinnerModel.getSelectedItem().toString();
        }
        // 設定パネルが開いていたら最新値を反映
        readSettingsFromUi();

        isProcessing = true;
        updateSendButton();
        stopTts();
        setStreamingResponse(false);
        cancelAutoChatter();
        pendingAutoVoiceStart = false;

        try {
            JSONArray messages = new JSONArray();
            synchronized (historyLock) {
                if (!conversationHistory.isEmpty()) {
                    JSONObject first = conversationHistory.get(0);
                    if ("system".equals(first.optString("role"))) {
                        messages.put(first);
                    }
                }
                int size = conversationHistory.size();
                int start = Math.max(1, size - Math.max(0, historyLimit));
                for (int i = start; i < size; i++) {
                    messages.put(conversationHistory.get(i));
                }
            }
            if (transientUserMessage != null) {
                JSONObject extra = new JSONObject();
                extra.put("role", "user");
                extra.put("content", transientUserMessage);
                messages.put(extra);
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
                setStreamingResponse(false);
                isProcessing = false;
                updateSendButton();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    appendConversation("HTTP error: " + response.code() + "\n");
                    setStreamingResponse(false);
                    isProcessing = false;
                    updateSendButton();
                    return;
                }

                StringBuilder fullResponse = new StringBuilder();
                boolean first = true;
                boolean streamingStarted = false;
                boolean completed = false;

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
                                    if (!streamingStarted) {
                                        setStreamingResponse(true);
                                        streamingStarted = true;
                                    }

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
                    completed = true;
                } catch (Exception e) {
                    appendConversation("Stream error: " + e.getMessage() + "\n");
                } finally {
                    setStreamingResponse(false);
                    isProcessing = false;
                    updateSendButton();
                    if (completed) {
                        handleAssistantResponseComplete();
                    }
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

                boolean completed = false;
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
                    completed = true;
                } catch (Exception e) {
                    appendConversation("Parse error: " + e.getMessage() + "\n");
                } finally {
                    isProcessing = false;
                    updateSendButton();
                    if (completed) {
                        handleAssistantResponseComplete();
                    }
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File target = getAvatarFileForRequest(requestCode);
        if (target == null) return;
        if (!copyAvatarUriToFile(uri, target)) {
            Toast.makeText(this, "画像の保存に失敗しました", Toast.LENGTH_SHORT).show();
            return;
        }
        reloadAvatarBitmaps();
        applyAvatarSelection(requestCode);
    }

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
        stopAvatarAnimation();
        autoHandler.removeCallbacksAndMessages(null);
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
