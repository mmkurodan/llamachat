package com.micklab.llamachat;

import android.app.AlertDialog;
import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private static final String SEARCH_SYSTEM_PROMPT =
            "You are a search-augmented assistant. When the user provides SEARCH_RESULTS, you must read them and base your answer strictly on that information.";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_PICK_C0 = 2000;
    private static final int REQ_PICK_C1 = 2001;
    private static final int REQ_PICK_C2 = 2002;
    private static final int REQ_PICK_C3 = 2003;
    private static final int REQ_PICK_CHATTER_C0 = 2010;
    private static final int REQ_PICK_CHATTER_C1 = 2011;
    private static final int REQ_PICK_CHATTER_C2 = 2012;
    private static final int REQ_PICK_CHATTER_C3 = 2013;
    private static final String AVATAR_C0_FILE = "avatar_c0.jpg";
    private static final String AVATAR_C1_FILE = "avatar_c1.jpg";
    private static final String AVATAR_C2_FILE = "avatar_c2.jpg";
    private static final String AVATAR_C3_FILE = "avatar_c3.jpg";
    private static final String AVATAR_CHATTER_C0_FILE = "avatar_chatter_c0.jpg";
    private static final String AVATAR_CHATTER_C1_FILE = "avatar_chatter_c1.jpg";
    private static final String AVATAR_CHATTER_C2_FILE = "avatar_chatter_c2.jpg";
    private static final String AVATAR_CHATTER_C3_FILE = "avatar_chatter_c3.jpg";
    private static final int TTS_WARMUP_MS = 120;
    private static final int AVATAR_TALK_FRAME_MS = 120;
    private static final int AVATAR_BLINK_MIN_MS = 3000;
    private static final int AVATAR_BLINK_MAX_MS = 7000;
    private static final int AVATAR_BLINK_DURATION_MS = 120;
    private static final int STREAM_FLUSH_INTERVAL_MS = 33;
    private static final int THINKING_ANIMATION_INTERVAL_MS = 360;
    private static final String[] REASONING_OPEN_TAGS = new String[]{
            "<think>", "<analysis>", "<|thought|>"
    };

    // --- UI ---
    private LinearLayout messageContainer;
    private EditText etInput;
    private Button btnSend, btnSettings, btnPickC0, btnPickC1, btnPickC2, btnPickC3;
    private Button btnClearC0, btnClearC1, btnClearC2, btnClearC3;
    private Button btnPickChatterC0, btnPickChatterC1, btnPickChatterC2, btnPickChatterC3;
    private Button btnClearChatterC0, btnClearChatterC1, btnClearChatterC2, btnClearChatterC3;
    private Button btnHelp, btnPrivacy, btnRights;
    private TextView tvC0Filename, tvC1Filename, tvC2Filename, tvC3Filename;
    private TextView tvChatterC0Filename, tvChatterC1Filename, tvChatterC2Filename, tvChatterC3Filename;
    private ScrollView scrollView;
    private View settingsPanel;
    private View topPanel;
    private LinearLayout chatPanel;
    private View chatDragArea;
    private View chatDragHandle;
    private Spinner spinnerModel, spinnerChatterModel;
    private RadioGroup groupMode, tabGroup;
    private RadioButton radioModeNormal, radioModeChatter, tabBase, tabChatter;
    private LinearLayout baseSettingsGroup, chatterSettingsGroup;
    private Switch switchStreaming, switchTts, switchVoiceInput, switchAutoVoiceInput, switchWebSearch, switchDebug;
    private EditText etOllamaUrl, etSpeechLang, etSpeechRate, etSpeechPitch, etSystemPrompt;
    private EditText etChatterSpeechLang, etChatterSpeechRate, etChatterSpeechPitch, etChatterSystemPrompt;
    private EditText etBaseName, etChatterName;
    private EditText etHistoryLimit, etAutoChatterSeconds;
    private EditText etWebSearchUrl, etWebSearchApiKey;
    private ImageView ivAvatarBackground;
    private ImageView ivAvatar;
    private FrameLayout counterpartMiniContainer;
    private ImageView ivCounterpartMiniBackground;
    private ImageView ivCounterpartMiniAvatar;

    // --- Settings (defaults) ---
    private String ollamaBaseUrl = "http://localhost:11434";
    private String selectedModel = "default";
    private String chatterModel = "default";
    private boolean streamingEnabled = true;
    private boolean ttsEnabled = false;
    private boolean voiceInputEnabled = true;
    private boolean autoChatterEnabled = false;
    private boolean autoVoiceInputEnabled = false;
    private boolean webSearchEnabled = false;
    private boolean debugEnabled = false;
    private String webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
    private String webSearchApiKey = "";
    private String speechLang = "ja-JP";
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String systemPromptText = "あなたはユーザの若い女性秘書です";
    private String chatterSpeechLang = "ja-JP";
    private float chatterSpeechRate = 1.0f;
    private float chatterSpeechPitch = 1.0f;
    private String chatterSystemPromptText = "あなたはユーザの若い女性秘書です";
    private String baseName = "アシスタント";
    private String chatterName = "おしゃべり相手";
    private int historyLimit = 10;
    private int autoChatterSeconds = 30;

    // --- TTS ---
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsNeedsWarmup = true;
    private boolean isTtsSpeaking = false;
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private final AtomicInteger pendingUtterances = new AtomicInteger(0);
    private final List<PendingTtsItem> pendingTtsQueue = new ArrayList<>();
    private boolean pendingAutoVoiceStart = false;
    private boolean pendingAutoChatterAfterTts = false;

    private enum AvatarMode { IDLE, TALKING }
    private enum ChatSpeaker { BASE, CHATTER }

    private static class PendingTtsItem {
        private final String text;
        private final ChatSpeaker speaker;

        private PendingTtsItem(String text, ChatSpeaker speaker) {
            this.text = text;
            this.speaker = speaker;
        }
    }

    private static class ReasoningFilterResult {
        private final String visibleText;
        private final boolean reasoningActive;

        private ReasoningFilterResult(String visibleText, boolean reasoningActive) {
            this.visibleText = visibleText;
            this.reasoningActive = reasoningActive;
        }
    }

    // --- Avatar ---
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
    private File avatarChatterC0File;
    private File avatarChatterC1File;
    private File avatarChatterC2File;
    private File avatarChatterC3File;
    private Bitmap avatarC0Bitmap;
    private Bitmap avatarC1Bitmap;
    private Bitmap avatarC2Bitmap;
    private Bitmap avatarC3Bitmap;
    private Bitmap avatarChatterC0Bitmap;
    private Bitmap avatarChatterC1Bitmap;
    private Bitmap avatarChatterC2Bitmap;
    private Bitmap avatarChatterC3Bitmap;
    private int talkFrameIndex = 0;
    private boolean isStreamingResponse = false;
    private AvatarMode avatarMode = AvatarMode.IDLE;
    private ChatSpeaker activeSpeaker = ChatSpeaker.BASE;
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
            sendChatterTurn();
        }
    };

    // --- Model List ---
    private final List<String> modelList = new ArrayList<>();
    private ArrayAdapter<String> modelAdapter;
    private ArrayAdapter<String> chatterModelAdapter;

    // --- Chat ---
    private final List<JSONObject> conversationHistory = new ArrayList<>();
    private final List<JSONObject> chatterHistory = new ArrayList<>();
    private volatile boolean isProcessing = false;
    private volatile boolean isListening = false;
    private final Object historyLock = new Object();
    private ChatSpeaker nextChatterSpeaker = ChatSpeaker.BASE;
    private String lastBaseResponse = null;
    private String lastChatterResponse = null;
    private TextView currentStreamingBubble;
    private TextView currentThinkingBubble;
    private ChatSpeaker currentStreamingSpeaker = ChatSpeaker.BASE;
    private ChatSpeaker currentThinkingSpeaker = ChatSpeaker.BASE;
    private ChatSpeaker currentTtsSpeaker = ChatSpeaker.BASE;
    private int thinkingDotStep = 0;
    private boolean chatExpanded = false;
    private float chatDragStartY = 0f;
    private int chatDragThresholdPx = 0;
    private int chatPanelMarginPx = 0;
    private int autoScrollThresholdPx = 0;
    private boolean wasSettingsPanelVisible = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Object streamBufferLock = new Object();
    private final StringBuilder streamBuffer = new StringBuilder();
    private boolean streamFlushScheduled = false;
    private final StringBuilder streamingTextBuffer = new StringBuilder();
    private final AtomicInteger streamingTokenCounter = new AtomicInteger(0);
    private volatile int activeStreamingToken = 0;
    private volatile boolean layoutUpdateScheduled = false;
    private Call currentCall = null;
    private final Runnable thinkingAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentThinkingBubble == null) return;
            thinkingDotStep = (thinkingDotStep + 1) % 3;
            updateThinkingBubbleText();
            uiHandler.postDelayed(this, THINKING_ANIMATION_INTERVAL_MS);
        }
    };

    // --- Voice Recognition ---
    private SpeechRecognizer speechRecognizer;
    private boolean pendingVoiceStart = false;
    private boolean triedOnlineFallback = false;
    private boolean currentPreferOffline = true;

    // --- Help/Privacy/Rights Content ---
    private static final String HELP_TEXT =
            "【使い方 / How to Use】\n\n" +
            "■ 画面 / Screen\n" +
            "・⚙️ Settings を押すと設定を開閉します。\n" +
            "・Tap ⚙️ Settings to open/close the settings panel.\n\n" +
            "・ログ上部のバーをタップするとチャットエリアが拡大/縮小します。\n" +
            "・Tap the top handle of the log area to expand/collapse the chat panel.\n\n" +
            "■ 送信 / Sending\n" +
            "・メッセージ入力後にSendで送信します。\n" +
            "・Enter a message and press Send.\n\n" +
            "・送信中はボタンがSTOPになり、タップで中止できます。\n" +
            "・While processing, the button shows STOP and can cancel the request.\n\n" +
            "■ 音声 / Voice\n" +
            "・Voice Inputを有効にすると空欄送信で音声入力します。\n" +
            "・When Voice Input is enabled, sending empty starts voice input.\n\n" +
            "・Auto Voice Inputは応答後に自動で音声入力を開始します。\n" +
            "・Auto Voice Input starts voice input after each response.\n\n" +
            "■ モード / Modes\n" +
            "・Normal: 1つのモデルでチャットします。\n" +
            "・Normal: Chat with a single model.\n" +
            "・Chatter: BaseとChatter Partnerが交互に会話します。\n" +
            "・Chatter: Base and Chatter Partner alternate turns.\n" +
            "・Chatter Intervalで発話間隔（秒）を設定します。\n" +
            "・Chatter Interval sets the delay between turns (seconds).\n\n" +
            "■ 設定タブ / Settings Tabs\n" +
            "・Base/Chatter Partnerのタブで各モデル設定を切り替えます。\n" +
            "・Use the Base/Chatter Partner tabs to switch settings.\n\n" +
            "・Model/Name/System Prompt/Speech Language/Rate/Pitchを設定できます。\n" +
            "・Configure Model/Name/System Prompt/Speech Language/Rate/Pitch.\n\n" +
            "■ 応答 / Response\n" +
            "・Streaming: 応答をリアルタイム表示します。\n" +
            "・Streaming shows responses in real time.\n\n" +
            "・Text-to-Speech: 応答を音声で読み上げます。\n" +
            "・Text-to-Speech reads responses aloud.\n\n" +
            "・History Limitで送信する履歴数を調整します。\n" +
            "・History Limit controls how many past messages are sent.\n\n" +
            "■ Web Search\n" +
            "・Web Searchを有効にすると検索APIを使います。\n" +
            "・Enable Web Search to use the configured search API.\n" +
            "・URLとAPI Keyを入力します。\n" +
            "・Set the Web Search API URL and API Key.\n\n" +
            "■ アバター / Avatar\n" +
            "・c0: 背景 / Background\n" +
            "・c1: 基本表情 / Base\n" +
            "・c2: まばたき / Blink\n" +
            "・c3: 会話中 / Talking\n" +
            "・BaseとChatter Partnerで別々に設定できます。\n" +
            "・You can set different images for Base and Chatter Partner.\n" +
            "・Clearでデフォルトに戻します。\n" +
            "・Press Clear to reset to default.\n\n" +
            "■ その他 / Other\n" +
            "・Debug Modeで通信ログを表示します。\n" +
            "・Debug Mode shows API request/response logs.\n" +
            "・設定と画像は端末内に保存されます。\n" +
            "・Settings and images are stored locally on the device.";

    private static final String PRIVACY_TEXT =
            "【プライバシーポリシー / Privacy Policy】\n\n" +
            "■ データの収集 / Data Collection\n" +
            "・本アプリはユーザーの会話データをサーバーに送信しません。\n" +
            "・This app does not send conversation data to any server.\n\n" +
            "・すべての会話は設定されたOllamaサーバーとの間でのみ行われます。\n" +
            "・All conversations occur only with your configured Ollama server.\n\n" +
            "■ ローカルデータ / Local Data\n" +
            "・設定とアバター画像はデバイス内にのみ保存されます。\n" +
            "・Settings and avatar images are stored locally on device only.\n\n" +
            "■ Web検索 / Web Search\n" +
            "・Web検索機能を使用する場合、検索クエリは設定されたAPIに送信されます。\n" +
            "・When using Web Search, queries are sent to your configured API.\n\n" +
            "■ 音声認識 / Voice Recognition\n" +
            "・音声認識はデバイスのシステム機能を使用します。\n" +
            "・Voice recognition uses your device's system features.";

    private static final String RIGHTS_TEXT =
            "【権利情報 / Rights Information】\n\n" +
            "■ アプリケーション / Application\n" +
            "・本アプリはオープンソースソフトウェアです。\n" +
            "・This application is open source software.\n\n" +
            "■ 使用ライブラリ / Libraries Used\n" +
            "・OkHttp - Apache License 2.0\n" +
            "・Android SDK - Apache License 2.0\n\n" +
            "■ 免責事項 / Disclaimer\n" +
            "・AIの応答内容について開発者は責任を負いません。\n" +
            "・Developer is not responsible for AI response content.\n\n" +
            "・本アプリの使用は自己責任でお願いします。\n" +
            "・Use this app at your own risk.\n\n" +
            "■ 連絡先 / Contact\n" +
            "・GitHub: https://github.com/micklab";

    // --- Network ---
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

    // ========== UI Initialization ==========

    private void initViews() {
        messageContainer = findViewById(R.id.messageContainer);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        btnSettings = findViewById(R.id.btnSettings);
        btnPickC0 = findViewById(R.id.btnPickC0);
        btnPickC1 = findViewById(R.id.btnPickC1);
        btnPickC2 = findViewById(R.id.btnPickC2);
        btnPickC3 = findViewById(R.id.btnPickC3);
        btnClearC0 = findViewById(R.id.btnClearC0);
        btnClearC1 = findViewById(R.id.btnClearC1);
        btnClearC2 = findViewById(R.id.btnClearC2);
        btnClearC3 = findViewById(R.id.btnClearC3);
        btnPickChatterC0 = findViewById(R.id.btnPickChatterC0);
        btnPickChatterC1 = findViewById(R.id.btnPickChatterC1);
        btnPickChatterC2 = findViewById(R.id.btnPickChatterC2);
        btnPickChatterC3 = findViewById(R.id.btnPickChatterC3);
        btnClearChatterC0 = findViewById(R.id.btnClearChatterC0);
        btnClearChatterC1 = findViewById(R.id.btnClearChatterC1);
        btnClearChatterC2 = findViewById(R.id.btnClearChatterC2);
        btnClearChatterC3 = findViewById(R.id.btnClearChatterC3);
        btnHelp = findViewById(R.id.btnHelp);
        btnPrivacy = findViewById(R.id.btnPrivacy);
        btnRights = findViewById(R.id.btnRights);
        tvC0Filename = findViewById(R.id.tvC0Filename);
        tvC1Filename = findViewById(R.id.tvC1Filename);
        tvC2Filename = findViewById(R.id.tvC2Filename);
        tvC3Filename = findViewById(R.id.tvC3Filename);
        tvChatterC0Filename = findViewById(R.id.tvChatterC0Filename);
        tvChatterC1Filename = findViewById(R.id.tvChatterC1Filename);
        tvChatterC2Filename = findViewById(R.id.tvChatterC2Filename);
        tvChatterC3Filename = findViewById(R.id.tvChatterC3Filename);
        ivAvatarBackground = findViewById(R.id.ivAvatarBackground);
        ivAvatar = findViewById(R.id.ivAvatar);
        counterpartMiniContainer = findViewById(R.id.counterpartMiniContainer);
        ivCounterpartMiniBackground = findViewById(R.id.ivCounterpartMiniBackground);
        ivCounterpartMiniAvatar = findViewById(R.id.ivCounterpartMiniAvatar);
        scrollView = findViewById(R.id.scrollView);
        settingsPanel = findViewById(R.id.settingsPanel);
        topPanel = findViewById(R.id.topPanel);
        chatPanel = findViewById(R.id.chatPanel);
        chatDragArea = findViewById(R.id.chatDragArea);
        chatDragHandle = findViewById(R.id.chatDragHandle);
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerChatterModel = findViewById(R.id.spinnerChatterModel);
        groupMode = findViewById(R.id.groupMode);
        radioModeNormal = findViewById(R.id.radioModeNormal);
        radioModeChatter = findViewById(R.id.radioModeChatter);
        tabGroup = findViewById(R.id.tabGroup);
        tabBase = findViewById(R.id.tabBase);
        tabChatter = findViewById(R.id.tabChatter);
        baseSettingsGroup = findViewById(R.id.baseSettingsGroup);
        chatterSettingsGroup = findViewById(R.id.chatterSettingsGroup);
        switchStreaming = findViewById(R.id.switchStreaming);
        switchTts = findViewById(R.id.switchTts);
        switchVoiceInput = findViewById(R.id.switchVoiceInput);
        switchAutoVoiceInput = findViewById(R.id.switchAutoVoiceInput);
        etOllamaUrl = findViewById(R.id.etOllamaUrl);
        etBaseName = findViewById(R.id.etBaseName);
        etSpeechLang = findViewById(R.id.etSpeechLang);
        etSpeechRate = findViewById(R.id.etSpeechRate);
        etSpeechPitch = findViewById(R.id.etSpeechPitch);
        etSystemPrompt = findViewById(R.id.etSystemPrompt);
        etChatterName = findViewById(R.id.etChatterName);
        etChatterSpeechLang = findViewById(R.id.etChatterSpeechLang);
        etChatterSpeechRate = findViewById(R.id.etChatterSpeechRate);
        etChatterSpeechPitch = findViewById(R.id.etChatterSpeechPitch);
        etChatterSystemPrompt = findViewById(R.id.etChatterSystemPrompt);
        etHistoryLimit = findViewById(R.id.etHistoryLimit);
        etAutoChatterSeconds = findViewById(R.id.etAutoChatterSeconds);
        switchWebSearch = findViewById(R.id.switchWebSearch);
        switchDebug = findViewById(R.id.switchDebug);
        etWebSearchUrl = findViewById(R.id.etWebSearchUrl);
        etWebSearchApiKey = findViewById(R.id.etWebSearchApiKey);
        chatDragThresholdPx = dpToPx(24);
        chatPanelMarginPx = dpToPx(12);
        autoScrollThresholdPx = dpToPx(64);
        float elevation = dpToPx(2);
        if (topPanel != null) {
            topPanel.setElevation(elevation);
            topPanel.bringToFront();
        }
        if (chatPanel != null) {
            chatPanel.setElevation(elevation);
            chatPanel.bringToFront();
        }
        if (chatDragArea != null) {
            chatDragArea.bringToFront();
        }
        updateCounterpartMiniSize();

        modelList.add("default");
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(modelAdapter);
        chatterModelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        chatterModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerChatterModel.setAdapter(chatterModelAdapter);
    }

    private void setupListeners() {
        btnSettings.setOnClickListener(v -> {
            if (settingsPanel.getVisibility() == View.VISIBLE) {
                readSettingsFromUi();
                saveSettings();
                settingsPanel.setVisibility(View.GONE);
                reinitSystemPrompts();
                focusChatInputAfterSettings();
            } else {
                settingsPanel.setVisibility(View.VISIBLE);
            }
        });

        groupMode.setOnCheckedChangeListener((group, checkedId) -> {
            autoChatterEnabled = checkedId == R.id.radioModeChatter;
            updateChatterModeUi();
        });

        tabGroup.setOnCheckedChangeListener((group, checkedId) -> updateSettingsTab());
        if (chatDragArea != null) {
            chatDragArea.setOnClickListener(v -> setChatExpanded(!chatExpanded));
        }

        btnSend.setOnClickListener(v -> {
            if (isProcessing) {
                showCancelConfirmation();
                return;
            }
            String userMsg = etInput.getText().toString().trim();
            if (userMsg.isEmpty()) {
                if (voiceInputEnabled) {
                    startVoiceRecognition(true);
                } else {
                    Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            submitUserMessage(userMsg);
        });

        btnPickC0.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C0));
        btnPickC1.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C1));
        btnPickC2.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C2));
        btnPickC3.setOnClickListener(v -> pickAvatarImage(REQ_PICK_C3));
        btnClearC0.setOnClickListener(v -> clearAvatarImage(REQ_PICK_C0));
        btnClearC1.setOnClickListener(v -> clearAvatarImage(REQ_PICK_C1));
        btnClearC2.setOnClickListener(v -> clearAvatarImage(REQ_PICK_C2));
        btnClearC3.setOnClickListener(v -> clearAvatarImage(REQ_PICK_C3));
        btnPickChatterC0.setOnClickListener(v -> pickAvatarImage(REQ_PICK_CHATTER_C0));
        btnPickChatterC1.setOnClickListener(v -> pickAvatarImage(REQ_PICK_CHATTER_C1));
        btnPickChatterC2.setOnClickListener(v -> pickAvatarImage(REQ_PICK_CHATTER_C2));
        btnPickChatterC3.setOnClickListener(v -> pickAvatarImage(REQ_PICK_CHATTER_C3));
        btnClearChatterC0.setOnClickListener(v -> clearAvatarImage(REQ_PICK_CHATTER_C0));
        btnClearChatterC1.setOnClickListener(v -> clearAvatarImage(REQ_PICK_CHATTER_C1));
        btnClearChatterC2.setOnClickListener(v -> clearAvatarImage(REQ_PICK_CHATTER_C2));
        btnClearChatterC3.setOnClickListener(v -> clearAvatarImage(REQ_PICK_CHATTER_C3));

        btnHelp.setOnClickListener(v -> showInfoDialog("Help", HELP_TEXT));
        btnPrivacy.setOnClickListener(v -> showInfoDialog("Privacy Policy", PRIVACY_TEXT));
        btnRights.setOnClickListener(v -> showInfoDialog("Rights", RIGHTS_TEXT));
    }

    private void focusChatInputAfterSettings() {
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && focused.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
        if (etInput == null) return;
        etInput.post(() -> {
            etInput.requestFocus();
            etInput.setSelection(etInput.getText().length());
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void updateCounterpartMiniSize() {
        if (counterpartMiniContainer == null) return;
        int width = getResources().getDisplayMetrics().widthPixels / 4;
        int height = getResources().getDisplayMetrics().heightPixels / 4;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) counterpartMiniContainer.getLayoutParams();
        if (params == null) return;
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        counterpartMiniContainer.setLayoutParams(params);
    }

    private void showCancelConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Request")
                .setMessage("Do you want to cancel the current request?\n現在のリクエストをキャンセルしますか？")
                .setPositiveButton("Yes / はい", (dialog, which) -> cancelCurrentRequest())
                .setNegativeButton("No / いいえ", null)
                .show();
    }

    private void cancelCurrentRequest() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        activeStreamingToken = streamingTokenCounter.incrementAndGet();
        resetStreamBuffer();
        hideThinkingIndicator();
        isProcessing = false;
        setStreamingResponse(false, null);
        stopTts();
        updateSendButton();
        appendSystemMessage("System", "Request cancelled / リクエストがキャンセルされました");
    }

    private void showInfoDialog(String title, String content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(content);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Copy", (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(title, content);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private void clearAvatarImage(int requestCode) {
        File target = getAvatarFileForRequest(requestCode);
        if (target != null && target.exists()) {
            target.delete();
        }
        reloadAvatarBitmaps();
        applyAvatarSelection(requestCode);
        updateAvatarFilenameDisplay(requestCode, null);
    }

    private void updateAvatarFilenameDisplay(int requestCode, String filename) {
        TextView tv = getFilenameTextViewForRequest(requestCode);
        if (tv != null) {
            tv.setText(filename != null ? filename : "(default)");
        }
    }

    private TextView getFilenameTextViewForRequest(int requestCode) {
        switch (requestCode) {
            case REQ_PICK_C0: return tvC0Filename;
            case REQ_PICK_C1: return tvC1Filename;
            case REQ_PICK_C2: return tvC2Filename;
            case REQ_PICK_C3: return tvC3Filename;
            case REQ_PICK_CHATTER_C0: return tvChatterC0Filename;
            case REQ_PICK_CHATTER_C1: return tvChatterC1Filename;
            case REQ_PICK_CHATTER_C2: return tvChatterC2Filename;
            case REQ_PICK_CHATTER_C3: return tvChatterC3Filename;
            default: return null;
        }
    }

    private void updateAllAvatarFilenameDisplays() {
        updateAvatarFilenameDisplay(REQ_PICK_C0, avatarC0File != null && avatarC0File.exists() ? avatarC0File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_C1, avatarC1File != null && avatarC1File.exists() ? avatarC1File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_C2, avatarC2File != null && avatarC2File.exists() ? avatarC2File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_C3, avatarC3File != null && avatarC3File.exists() ? avatarC3File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_CHATTER_C0, avatarChatterC0File != null && avatarChatterC0File.exists() ? avatarChatterC0File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_CHATTER_C1, avatarChatterC1File != null && avatarChatterC1File.exists() ? avatarChatterC1File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_CHATTER_C2, avatarChatterC2File != null && avatarChatterC2File.exists() ? avatarChatterC2File.getName() : null);
        updateAvatarFilenameDisplay(REQ_PICK_CHATTER_C3, avatarChatterC3File != null && avatarChatterC3File.exists() ? avatarChatterC3File.getName() : null);
    }

    // ========== Avatar ==========

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
        avatarChatterC0File = new File(dir, AVATAR_CHATTER_C0_FILE);
        avatarChatterC1File = new File(dir, AVATAR_CHATTER_C1_FILE);
        avatarChatterC2File = new File(dir, AVATAR_CHATTER_C2_FILE);
        avatarChatterC3File = new File(dir, AVATAR_CHATTER_C3_FILE);
        reloadAvatarBitmaps();
        updateAllAvatarFilenameDisplays();
    }

    private void reloadAvatarBitmaps() {
        avatarC0Bitmap = loadAvatarBitmap(avatarC0File);
        avatarC1Bitmap = loadAvatarBitmap(avatarC1File);
        avatarC2Bitmap = loadAvatarBitmap(avatarC2File);
        avatarC3Bitmap = loadAvatarBitmap(avatarC3File);
        avatarChatterC0Bitmap = loadAvatarBitmap(avatarChatterC0File);
        avatarChatterC1Bitmap = loadAvatarBitmap(avatarChatterC1File);
        avatarChatterC2Bitmap = loadAvatarBitmap(avatarChatterC2File);
        avatarChatterC3Bitmap = loadAvatarBitmap(avatarChatterC3File);
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
            Toast.makeText(this, "External storage unavailable", Toast.LENGTH_SHORT).show();
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
            case REQ_PICK_CHATTER_C0:
                return avatarChatterC0File;
            case REQ_PICK_CHATTER_C1:
                return avatarChatterC1File;
            case REQ_PICK_CHATTER_C2:
                return avatarChatterC2File;
            case REQ_PICK_CHATTER_C3:
                return avatarChatterC3File;
            default:
                return null;
        }
    }

    private int getAvatarResIdForRequest(int requestCode) {
        switch (requestCode) {
            case REQ_PICK_C0:
            case REQ_PICK_CHATTER_C0:
                return R.drawable.c0;
            case REQ_PICK_C1:
            case REQ_PICK_CHATTER_C1:
                return R.drawable.c1;
            case REQ_PICK_C2:
            case REQ_PICK_CHATTER_C2:
                return R.drawable.c2;
            case REQ_PICK_C3:
            case REQ_PICK_CHATTER_C3:
                return R.drawable.c3;
            default:
                return 0;
        }
    }

    private void applyAvatarSelection(int requestCode) {
        int resId = getAvatarResIdForRequest(requestCode);
        if (resId == 0) return;
        ChatSpeaker target = getAvatarSpeakerForRequest(requestCode);
        if (target == activeSpeaker) {
            if (isAvatarBackgroundRequest(requestCode)) {
                setAvatarBackground(resId);
            } else {
                setAvatarFrame(resId);
            }
        }
        updateCounterpartMiniAvatar();
    }

    private boolean isAvatarBackgroundRequest(int requestCode) {
        return requestCode == REQ_PICK_C0 || requestCode == REQ_PICK_CHATTER_C0;
    }

    private ChatSpeaker getAvatarSpeakerForRequest(int requestCode) {
        switch (requestCode) {
            case REQ_PICK_CHATTER_C0:
            case REQ_PICK_CHATTER_C1:
            case REQ_PICK_CHATTER_C2:
            case REQ_PICK_CHATTER_C3:
                return ChatSpeaker.CHATTER;
            default:
                return ChatSpeaker.BASE;
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
        updateCounterpartMiniAvatar();
    }

    private void setAvatarBackground(int resId) {
        if (ivAvatarBackground != null) {
            Bitmap custom = null;
            if (resId == R.drawable.c0) {
                custom = getAvatarBackgroundBitmapForSpeaker(activeSpeaker);
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
            Bitmap custom = getAvatarFrameBitmapForSpeaker(activeSpeaker, resId);
            if (custom != null) {
                ivAvatar.setImageBitmap(custom);
            } else {
                ivAvatar.setImageResource(resId);
            }
        }
    }

    private Bitmap getAvatarBackgroundBitmapForSpeaker(ChatSpeaker speaker) {
        return speaker == ChatSpeaker.CHATTER ? avatarChatterC0Bitmap : avatarC0Bitmap;
    }

    private Bitmap getAvatarFrameBitmapForSpeaker(ChatSpeaker speaker, int resId) {
        if (speaker == ChatSpeaker.CHATTER) {
            if (resId == R.drawable.c1) return avatarChatterC1Bitmap;
            if (resId == R.drawable.c2) return avatarChatterC2Bitmap;
            if (resId == R.drawable.c3) return avatarChatterC3Bitmap;
            return null;
        }
        if (resId == R.drawable.c1) return avatarC1Bitmap;
        if (resId == R.drawable.c2) return avatarC2Bitmap;
        if (resId == R.drawable.c3) return avatarC3Bitmap;
        return null;
    }

    private void setCounterpartMiniBackground(ChatSpeaker speaker) {
        if (ivCounterpartMiniBackground == null) return;
        Bitmap custom = getAvatarBackgroundBitmapForSpeaker(speaker);
        if (custom != null) {
            ivCounterpartMiniBackground.setImageBitmap(custom);
        } else {
            ivCounterpartMiniBackground.setImageResource(R.drawable.c0);
        }
    }

    private void setCounterpartMiniAvatar(ChatSpeaker speaker) {
        if (ivCounterpartMiniAvatar == null) return;
        Bitmap custom = getAvatarFrameBitmapForSpeaker(speaker, R.drawable.c1);
        if (custom != null) {
            ivCounterpartMiniAvatar.setImageBitmap(custom);
        } else {
            ivCounterpartMiniAvatar.setImageResource(R.drawable.c1);
        }
    }

    private void updateCounterpartMiniAvatar() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::updateCounterpartMiniAvatar);
            return;
        }
        if (counterpartMiniContainer == null
                || ivCounterpartMiniBackground == null
                || ivCounterpartMiniAvatar == null) {
            return;
        }
        if (!autoChatterEnabled) {
            counterpartMiniContainer.setVisibility(View.GONE);
            return;
        }
        updateCounterpartMiniSize();
        ChatSpeaker counterpart = activeSpeaker == ChatSpeaker.BASE
                ? ChatSpeaker.CHATTER
                : ChatSpeaker.BASE;
        setCounterpartMiniBackground(counterpart);
        setCounterpartMiniAvatar(counterpart);
        counterpartMiniContainer.setVisibility(View.VISIBLE);
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

    private void switchActiveSpeaker(ChatSpeaker speaker) {
        if (speaker == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> applyActiveSpeaker(speaker));
            return;
        }
        applyActiveSpeaker(speaker);
    }

    private void applyActiveSpeaker(ChatSpeaker speaker) {
        if (speaker == null || speaker == activeSpeaker) return;
        activeSpeaker = speaker;
        setAvatarBackground(R.drawable.c0);
        if (avatarMode == AvatarMode.TALKING) {
            startTalkAnimation();
        } else {
            startIdleAnimation();
        }
        updateCounterpartMiniAvatar();
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

    private void setStreamingResponse(boolean active, ChatSpeaker speaker) {
        if (active && speaker != null) {
            switchActiveSpeaker(speaker);
        }
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

    private void sendChatterTurn() {
        if (!autoChatterEnabled) return;
        if (nextChatterSpeaker == ChatSpeaker.BASE) {
            String prompt = (lastChatterResponse == null || lastChatterResponse.trim().isEmpty())
                    ? "さらに続けて" : null;
            sendChatForSpeaker(ChatSpeaker.BASE, prompt, false);
        } else {
            String prompt = (lastBaseResponse == null || lastBaseResponse.trim().isEmpty())
                    ? "さらに続けて" : null;
            sendChatForSpeaker(ChatSpeaker.CHATTER, prompt, false);
        }
    }

    private void handleAssistantResponseComplete(ChatSpeaker speaker, String responseText) {
        runOnUiThread(() -> {
            if (autoChatterEnabled) {
                if (responseText != null && !responseText.trim().isEmpty()) {
                    if (speaker == ChatSpeaker.BASE) {
                        lastBaseResponse = responseText;
                        addToHistory(chatterHistory, "user", responseText);
                    } else {
                        lastChatterResponse = responseText;
                        addToHistory(conversationHistory, "user", responseText);
                    }
                }
                nextChatterSpeaker = speaker == ChatSpeaker.BASE ? ChatSpeaker.CHATTER : ChatSpeaker.BASE;
                pendingAutoVoiceStart = false;
                pendingAutoChatterAfterTts = true;
                if (ttsEnabled && pendingUtterances.get() > 0) {
                    return;
                }
                scheduleAutoChatter();
                return;
            }
            if (autoVoiceInputEnabled) {
                pendingAutoChatterAfterTts = false;
                if (ttsEnabled && pendingUtterances.get() > 0) {
                    // TTS is playing; defer voice input until TTS finishes.
                    pendingAutoVoiceStart = true;
                } else {
                    startVoiceRecognition(true);
                }
                return;
            }
            pendingAutoVoiceStart = false;
            if (ttsEnabled) {
                // Defer auto chatter until TTS finishes (even if no utterances, treat as completed).
                pendingAutoChatterAfterTts = autoChatterEnabled;
                if (pendingUtterances.get() <= 0) {
                    checkAutoVoiceAfterTts();
                }
            } else {
                pendingAutoChatterAfterTts = false;
                scheduleAutoChatter();
            }
        });
    }

    private void checkAutoVoiceAfterTts() {
        if (pendingUtterances.get() <= 0) {
            if (pendingAutoVoiceStart) {
                pendingAutoVoiceStart = false;
                runOnUiThread(() -> startVoiceRecognition(true));
            } else if (pendingAutoChatterAfterTts) {
                pendingAutoChatterAfterTts = false;
                runOnUiThread(() -> {
                    if (autoChatterEnabled) scheduleAutoChatter();
                });
            }
        }
    }

    // ========== Settings I/O (JSON file) ==========

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
            chatterModel = s.optString("chatterModel", chatterModel);
            streamingEnabled = s.optBoolean("streamingEnabled", streamingEnabled);
            ttsEnabled = s.optBoolean("ttsEnabled", ttsEnabled);
            voiceInputEnabled = s.optBoolean("voiceInputEnabled", voiceInputEnabled);
            autoChatterEnabled = s.optBoolean("autoChatterEnabled", autoChatterEnabled);
            autoVoiceInputEnabled = s.optBoolean("autoVoiceInputEnabled", autoVoiceInputEnabled);
            speechLang = s.optString("speechLang", speechLang);
            speechRate = (float) s.optDouble("speechRate", speechRate);
            speechPitch = (float) s.optDouble("speechPitch", speechPitch);
            systemPromptText = s.optString("systemPrompt", systemPromptText);
            chatterSpeechLang = s.optString("chatterSpeechLang", chatterSpeechLang);
            chatterSpeechRate = (float) s.optDouble("chatterSpeechRate", chatterSpeechRate);
            chatterSpeechPitch = (float) s.optDouble("chatterSpeechPitch", chatterSpeechPitch);
            chatterSystemPromptText = s.optString("chatterSystemPrompt", chatterSystemPromptText);
            baseName = s.optString("baseName", baseName);
            chatterName = s.optString("chatterName", chatterName);
            historyLimit = s.optInt("historyLimit", historyLimit);
            autoChatterSeconds = s.optInt("autoChatterSeconds", autoChatterSeconds);
            webSearchEnabled = s.optBoolean("webSearchEnabled", webSearchEnabled);
            debugEnabled = s.optBoolean("debugEnabled", debugEnabled);
            webSearchUrl = s.optString("webSearchUrl", webSearchUrl);
            webSearchApiKey = s.optString("webSearchApiKey", webSearchApiKey);
            if (historyLimit < 0) historyLimit = 0;
            if (autoChatterSeconds < 0) autoChatterSeconds = 0;
        } catch (FileNotFoundException e) {
            // First run: use defaults
        } catch (Exception e) {
            Log.e(TAG, "loadSettings error", e);
        }
    }

    private void saveSettings() {
        try {
            JSONObject s = new JSONObject();
            s.put("ollamaBaseUrl", ollamaBaseUrl);
            s.put("selectedModel", selectedModel);
            s.put("chatterModel", chatterModel);
            s.put("streamingEnabled", streamingEnabled);
            s.put("ttsEnabled", ttsEnabled);
            s.put("voiceInputEnabled", voiceInputEnabled);
            s.put("autoChatterEnabled", autoChatterEnabled);
            s.put("autoVoiceInputEnabled", autoVoiceInputEnabled);
            s.put("speechLang", speechLang);
            s.put("speechRate", speechRate);
            s.put("speechPitch", speechPitch);
            s.put("systemPrompt", systemPromptText);
            s.put("chatterSpeechLang", chatterSpeechLang);
            s.put("chatterSpeechRate", chatterSpeechRate);
            s.put("chatterSpeechPitch", chatterSpeechPitch);
            s.put("chatterSystemPrompt", chatterSystemPromptText);
            s.put("baseName", baseName);
            s.put("chatterName", chatterName);
            s.put("historyLimit", historyLimit);
            s.put("autoChatterSeconds", autoChatterSeconds);
            s.put("webSearchEnabled", webSearchEnabled);
            s.put("debugEnabled", debugEnabled);
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
        autoChatterEnabled = radioModeChatter.isChecked();
        autoVoiceInputEnabled = switchAutoVoiceInput.isChecked();
        baseName = etBaseName.getText().toString().trim();
        if (baseName.isEmpty()) baseName = "アシスタント";
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
        chatterName = etChatterName.getText().toString().trim();
        if (chatterName.isEmpty()) chatterName = "おしゃべり相手";
        chatterSpeechLang = etChatterSpeechLang.getText().toString().trim();
        if (chatterSpeechLang.isEmpty()) chatterSpeechLang = "ja-JP";
        try {
            chatterSpeechRate = Float.parseFloat(etChatterSpeechRate.getText().toString());
        } catch (Exception e) {
            chatterSpeechRate = 1.0f;
        }
        try {
            chatterSpeechPitch = Float.parseFloat(etChatterSpeechPitch.getText().toString());
        } catch (Exception e) {
            chatterSpeechPitch = 1.0f;
        }
        chatterSystemPromptText = etChatterSystemPrompt.getText().toString().trim();
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
        debugEnabled = switchDebug.isChecked();
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
        if (spinnerChatterModel.getSelectedItem() != null) {
            chatterModel = spinnerChatterModel.getSelectedItem().toString();
        }
    }

    private void applySettingsToUi() {
        etOllamaUrl.setText(ollamaBaseUrl);
        switchStreaming.setChecked(streamingEnabled);
        switchTts.setChecked(ttsEnabled);
        switchVoiceInput.setChecked(voiceInputEnabled);
        radioModeChatter.setChecked(autoChatterEnabled);
        radioModeNormal.setChecked(!autoChatterEnabled);
        switchAutoVoiceInput.setChecked(autoVoiceInputEnabled);
        etBaseName.setText(baseName);
        etSpeechLang.setText(speechLang);
        etSpeechRate.setText(String.valueOf(speechRate));
        etSpeechPitch.setText(String.valueOf(speechPitch));
        etSystemPrompt.setText(systemPromptText);
        etChatterName.setText(chatterName);
        etChatterSpeechLang.setText(chatterSpeechLang);
        etChatterSpeechRate.setText(String.valueOf(chatterSpeechRate));
        etChatterSpeechPitch.setText(String.valueOf(chatterSpeechPitch));
        etChatterSystemPrompt.setText(chatterSystemPromptText);
        etHistoryLimit.setText(String.valueOf(historyLimit));
        etAutoChatterSeconds.setText(String.valueOf(autoChatterSeconds));
        switchWebSearch.setChecked(webSearchEnabled);
        switchDebug.setChecked(debugEnabled);
        etWebSearchUrl.setText(webSearchUrl);
        etWebSearchApiKey.setText(webSearchApiKey);

        int idx = modelList.indexOf(selectedModel);
        if (idx >= 0) spinnerModel.setSelection(idx);
        int chatterIdx = modelList.indexOf(chatterModel);
        if (chatterIdx >= 0) spinnerChatterModel.setSelection(chatterIdx);
        updateChatterModeUi();
    }

    private void updateChatterModeUi() {
        if (autoChatterEnabled) {
            tabGroup.setVisibility(View.VISIBLE);
            updateSettingsTab();
            updateCounterpartMiniAvatar();
        } else {
            tabGroup.setVisibility(View.GONE);
            tabBase.setChecked(true);
            baseSettingsGroup.setVisibility(View.VISIBLE);
            chatterSettingsGroup.setVisibility(View.GONE);
            cancelAutoChatter();
            pendingAutoChatterAfterTts = false;
            updateCounterpartMiniAvatar();
        }
    }

    private void updateSettingsTab() {
        boolean showChatter = tabChatter.isChecked();
        baseSettingsGroup.setVisibility(showChatter ? View.GONE : View.VISIBLE);
        chatterSettingsGroup.setVisibility(showChatter ? View.VISIBLE : View.GONE);
    }

    private void setChatExpanded(boolean expanded) {
        if (chatExpanded == expanded) return;
        chatExpanded = expanded;
        runOnUiThread(() -> {
            if (topPanel != null) {
                LinearLayout.LayoutParams topParams = (LinearLayout.LayoutParams) topPanel.getLayoutParams();
                if (expanded) {
                    topParams.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    topParams.weight = 0f;
                } else {
                    topParams.height = 0;
                    topParams.weight = 1f;
                }
                topPanel.setLayoutParams(topParams);
                if (settingsPanel != null && expanded) {
                    wasSettingsPanelVisible = settingsPanel.getVisibility() == View.VISIBLE;
                    settingsPanel.setVisibility(View.GONE);
                } else if (settingsPanel != null && !expanded && wasSettingsPanelVisible) {
                    settingsPanel.setVisibility(View.VISIBLE);
                    wasSettingsPanelVisible = false;
                }
            }
            if (chatPanel != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chatPanel.getLayoutParams();
                int margin = expanded ? 0 : chatPanelMarginPx;
                params.weight = expanded ? 2f : 1f;
                params.height = 0;
                params.setMargins(margin, margin, margin, margin);
                chatPanel.setLayoutParams(params);
            }
            requestChatLayoutUpdate();
        });
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
            applyTtsSettings(ChatSpeaker.BASE);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (!isWarmupUtterance(utteranceId)) {
                        isTtsSpeaking = true;
                        switchActiveSpeaker(currentTtsSpeaker);
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

    private void applyTtsSettings(ChatSpeaker speaker) {
        if (!ttsReady) return;
        String lang = speaker == ChatSpeaker.CHATTER ? chatterSpeechLang : speechLang;
        float rate = speaker == ChatSpeaker.CHATTER ? chatterSpeechRate : speechRate;
        float pitch = speaker == ChatSpeaker.CHATTER ? chatterSpeechPitch : speechPitch;
        try {
            String[] parts = lang.split("-");
            Locale locale = parts.length >= 2 ? new Locale(parts[0], parts[1]) : new Locale(parts[0]);
            tts.setLanguage(locale);
        } catch (Exception e) {
            tts.setLanguage(Locale.JAPAN);
        }
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
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
        List<PendingTtsItem> queued;
        synchronized (pendingTtsQueue) {
            if (pendingTtsQueue.isEmpty()) return;
            queued = new ArrayList<>(pendingTtsQueue);
            pendingTtsQueue.clear();
        }
        for (PendingTtsItem item : queued) {
            speakSentence(item.text, item.speaker);
        }
    }

    private void speakSentence(String text, ChatSpeaker speaker) {
        if (!ttsEnabled) return;
        if (!ttsReady) {
            synchronized (pendingTtsQueue) {
                pendingTtsQueue.add(new PendingTtsItem(text, speaker));
            }
            return;
        }
        // Text normalization (similar to ollama-chat speak())
        String source = stripReasoningSegments(text);
        String clean = source
                .replaceAll("[\\n\\r\\t]", "、")
                .replaceAll("[!@#$%^&*()_+={}\\[\\]|\\\\:;<>.?/]", "、")
                .replaceAll("[,\u201c\u201d]", " ")
                .replaceAll("、+", "、")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return;

        currentTtsSpeaker = speaker;
        switchActiveSpeaker(speaker);
        applyTtsSettings(speaker);
        playTtsWarmupIfNeeded();
        pendingUtterances.incrementAndGet();
        updateAvatarAnimation();
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "utt_" + System.currentTimeMillis());
    }

    /** ストリーミング中のチャンク処理: 文末で区切って逐次読み上げ */
    private void processChunkForTts(String chunk, ChatSpeaker speaker) {
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
                    speakSentence(trimmed, speaker);
                }
            }
        }
    }

    /** バッファに残ったテキストを読み上げ */
    private void flushSentenceBuffer(ChatSpeaker speaker) {
        String remaining = sentenceBuffer.toString().trim();
        sentenceBuffer.setLength(0);
        if (!remaining.isEmpty()) {
            speakSentence(remaining, speaker);
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

    // ========== Conversation History ==========

    private void initConversationHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            chatterHistory.clear();
            nextChatterSpeaker = ChatSpeaker.BASE;
            lastBaseResponse = null;
            lastChatterResponse = null;
            try {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", buildSystemPromptWithName(systemPromptText, baseName));
                conversationHistory.add(sys);
                JSONObject chatterSys = new JSONObject();
                chatterSys.put("role", "system");
                chatterSys.put("content", buildSystemPromptWithName(chatterSystemPromptText, chatterName));
                chatterHistory.add(chatterSys);
            } catch (Exception e) {
                Log.e(TAG, "initHistory error", e);
            }
        }
    }

    private void reinitSystemPrompts() {
        synchronized (historyLock) {
            updateSystemPrompt(conversationHistory, buildSystemPromptWithName(systemPromptText, baseName));
            updateSystemPrompt(chatterHistory, buildSystemPromptWithName(chatterSystemPromptText, chatterName));
        }
    }

    private void updateSystemPrompt(List<JSONObject> history, String prompt) {
        if (history.isEmpty()) return;
        try {
            JSONObject sys = history.get(0);
            if ("system".equals(sys.optString("role"))) {
                sys.put("content", prompt);
            }
        } catch (Exception e) {
            Log.e(TAG, "reinitSystemPrompt error", e);
        }
    }

    private String buildSystemPromptWithName(String basePrompt, String name) {
        String trimmed = basePrompt == null ? "" : basePrompt.trim();
        if (name == null || name.trim().isEmpty()) return trimmed;
        String suffix = "あなたの名前は" + name.trim() + "です。";
        if (trimmed.isEmpty()) return suffix;
        if (trimmed.contains(suffix)) return trimmed;
        return trimmed + "\n" + suffix;
    }

    private void addToHistory(List<JSONObject> history, String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            synchronized (historyLock) {
                history.add(msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "addToHistory error", e);
        }
    }

    // ========== Model Fetch (/api/tags) ==========

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
                        chatterModelAdapter.notifyDataSetChanged();
                        int idx = modelList.indexOf(selectedModel);
                        if (idx >= 0) spinnerModel.setSelection(idx);
                        int chatterIdx = modelList.indexOf(chatterModel);
                        if (chatterIdx >= 0) spinnerChatterModel.setSelection(chatterIdx);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "fetchModels parse error", e);
                }
            }
        });
    }

    // ========== Chat Send ==========

    private void updateSendButton() {
        runOnUiThread(() -> {
            if (btnSend == null) return;
            btnSend.setEnabled(true);
            btnSend.setText(isProcessing ? "STOP" : "Send");
        });
    }

    private void submitUserMessage(String userMsg) {
        if (userMsg == null || userMsg.trim().isEmpty()) return;
        cancelAutoChatter();
        etInput.setText("");
        appendUserMessage(userMsg);
        addToHistory(conversationHistory, "user", userMsg);

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

        final String[] augmentedMessageHolder = new String[1];
        new Thread(() -> {
            try {
                String keywords = extractSearchKeywords(userMsg);
                if (keywords != null) {
                    String searchResults = callWebSearchApi(keywords);
                    if (searchResults != null && !searchResults.isEmpty()) {
                        augmentedMessageHolder[0] = buildSearchAugmentedUserMessage(userMsg, searchResults);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Web search flow error", e);
            } finally {
                isProcessing = false;
                updateSendButton();
                String augmentedMessage = augmentedMessageHolder[0];
                runOnUiThread(() -> {
                    if (augmentedMessage != null) {
                        sendChat(augmentedMessage, true);
                    } else {
                        sendChat(null);
                    }
                });
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
            if (debugEnabled) {
                appendDebug("/api/generate 送信", buildRequestDebugText(request, body.toString()));
            }

            Response response = client.newCall(request).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (debugEnabled) {
                appendDebug("/api/generate 返信", buildResponseDebugText(response, respBody));
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "extractSearchKeywords HTTP error: " + response.code());
                return null;
            }
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

    /** Call Web Search API and get structured results (generic) */
    private String callWebSearchApi(String keywords) {
        try {
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8");

            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");

            // Support multiple API key header formats
            if (!webSearchApiKey.isEmpty()) {
                // Try common API key header formats
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey); // Brave
                reqBuilder.addHeader("Authorization", "Bearer " + webSearchApiKey); // Many APIs
                reqBuilder.addHeader("X-Api-Key", webSearchApiKey); // Generic
            }

            Request request = reqBuilder.build();
            if (debugEnabled) {
                appendDebug("Web API Request", buildRequestDebugText(request, null));
            }
            Response response = client.newCall(request).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (debugEnabled) {
                appendDebug("Web API Response", buildResponseDebugText(response, respBody));
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "callWebSearchApi HTTP error: " + response.code());
                return null;
            }
            JSONObject json = new JSONObject(respBody);

            // Try generic extraction: collect all string values from JSON
            StringBuilder extracted = new StringBuilder();
            extractAllStringValues(json, extracted, 0);
            String extractedText = extracted.toString().trim();
            
            if (extractedText.isEmpty()) return null;
            
            String result = "SEARCH_RESULTS:\n" + extractedText;
            Log.d(TAG, "Web search results: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "callWebSearchApi error", e);
            return null;
        }
    }

    /** Recursively extract all string values from JSON */
    private void extractAllStringValues(Object obj, StringBuilder sb, int depth) {
        if (depth > 10) return; // Prevent infinite recursion
        try {
            if (obj instanceof JSONObject) {
                JSONObject json = (JSONObject) obj;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = json.get(key);
                    extractAllStringValues(value, sb, depth + 1);
                }
            } else if (obj instanceof JSONArray) {
                JSONArray arr = (JSONArray) obj;
                for (int i = 0; i < arr.length() && i < 10; i++) { // Limit array items
                    extractAllStringValues(arr.get(i), sb, depth + 1);
                }
            } else if (obj instanceof String) {
                String str = ((String) obj).trim();
                if (!str.isEmpty() && str.length() > 10 && !str.startsWith("http")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(str);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractAllStringValues error", e);
        }
    }

    private String buildSearchAugmentedUserMessage(String userMsg, String searchResultsBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下はWeb検索結果です。SEARCH_RESULTSとして扱ってください。\n");
        sb.append(searchResultsBlock);
        sb.append("\n\n質問: ").append(userMsg);
        return sb.toString();
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
            Toast.makeText(this, "Voice recognition unavailable", Toast.LENGTH_SHORT).show();
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
                            "Offline recognition failed, switching to online",
                            Toast.LENGTH_SHORT).show();
                    startVoiceRecognition(false);
                    return;
                }
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(MainActivity.this, "Voice recognition failed", Toast.LENGTH_SHORT).show();
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
        sendChat(transientUserMessage, false);
    }

    private void sendChat(String transientUserMessage, boolean replaceLastUserMessage) {
        sendChatForSpeaker(ChatSpeaker.BASE, transientUserMessage, replaceLastUserMessage);
    }

    private void sendChatForSpeaker(ChatSpeaker speaker, String transientUserMessage, boolean replaceLastUserMessage) {
        if (speaker == ChatSpeaker.BASE) {
            if (spinnerModel.getSelectedItem() != null) {
                selectedModel = spinnerModel.getSelectedItem().toString();
            }
        } else if (spinnerChatterModel.getSelectedItem() != null) {
            chatterModel = spinnerChatterModel.getSelectedItem().toString();
        }
        // Apply settings from UI if panel is open
        readSettingsFromUi();
        reinitSystemPrompts();

        isProcessing = true;
        updateSendButton();
        stopTts();
        setStreamingResponse(false, null);
        cancelAutoChatter();
        pendingAutoVoiceStart = false;
        resetStreamBuffer();
        int streamToken = startStreamingSession();

        try {
            JSONArray messages = new JSONArray();
            boolean shouldReplaceLastUser = replaceLastUserMessage && transientUserMessage != null;
            List<JSONObject> history = getHistoryForSpeaker(speaker);
            synchronized (historyLock) {
                if (!history.isEmpty()) {
                    JSONObject first = history.get(0);
                    if ("system".equals(first.optString("role"))) {
                        String systemContent = first.optString("content", "");
                        if (speaker == ChatSpeaker.BASE
                                && webSearchEnabled
                                && !systemContent.contains(SEARCH_SYSTEM_PROMPT)) {
                            systemContent = systemContent + "\n" + SEARCH_SYSTEM_PROMPT;
                        }
                        JSONObject sys = new JSONObject();
                        sys.put("role", "system");
                        sys.put("content", systemContent);
                        messages.put(sys);
                    }
                }
                int size = history.size();
                int start = Math.max(1, size - Math.max(0, historyLimit));
                for (int i = start; i < size; i++) {
                    JSONObject msg = history.get(i);
                    if (shouldReplaceLastUser && i == size - 1 && "user".equals(msg.optString("role"))) {
                        JSONObject replaced = new JSONObject(msg.toString());
                        replaced.put("content", transientUserMessage);
                        messages.put(replaced);
                    } else {
                        messages.put(msg);
                    }
                }
            }
            if (transientUserMessage != null && !shouldReplaceLastUser) {
                JSONObject extra = new JSONObject();
                extra.put("role", "user");
                extra.put("content", transientUserMessage);
                messages.put(extra);
            }

            JSONObject body = new JSONObject();
            body.put("model", speaker == ChatSpeaker.BASE ? selectedModel : chatterModel);
            body.put("messages", messages);
            body.put("stream", streamingEnabled);

            String requestJson = body.toString();
            RequestBody requestBody = RequestBody.create(requestJson, JSON_MEDIA);
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(requestBody)
                    .build();
            if (debugEnabled) {
                appendDebug("/api/chat Request", buildRequestDebugText(request, requestJson));
            }

            if (streamingEnabled) {
                sendStreaming(request, speaker, streamToken);
            } else {
                sendNonStreaming(request, speaker);
            }
        } catch (Exception e) {
            appendErrorMessage(e.getMessage());
            isProcessing = false;
            updateSendButton();
        }
    }

    private List<JSONObject> getHistoryForSpeaker(ChatSpeaker speaker) {
        return speaker == ChatSpeaker.CHATTER ? chatterHistory : conversationHistory;
    }

    /** Streaming mode: Display chunks + sentence-by-sentence TTS */
    private void sendStreaming(Request request, ChatSpeaker speaker, int token) {
        Call call = client.newCall(request);
        currentCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                setThinkingIndicator(false, speaker, token);
                if (call.isCanceled()) {
                    return; // Don't show error for cancelled requests
                }
                appendErrorMessage(e.getMessage());
                appendDebug("/api/chat Response (Failed)", e.toString());
                setStreamingResponse(false, null);
                isProcessing = false;
                updateSendButton();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    if (debugEnabled) {
                        appendDebug("/api/chat Response", buildResponseDebugText(response, errorBody));
                    }
                    appendErrorMessage("HTTP error: " + response.code());
                    setThinkingIndicator(false, speaker, token);
                    setStreamingResponse(false, null);
                    isProcessing = false;
                    updateSendButton();
                    return;
                }

                StringBuilder rawResponse = new StringBuilder();
                String visibleResponse = "";
                boolean first = true;
                boolean streamingStarted = false;
                boolean completed = false;
                StringBuilder debugRaw = debugEnabled ? new StringBuilder() : null;

                try (InputStream is = response.body().byteStream();
                     BufferedReader reader = new BufferedReader(
                              new InputStreamReader(is, StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        if (debugRaw != null) {
                            debugRaw.append(line).append("\n");
                        }
                        try {
                            JSONObject json = new JSONObject(line);
                            boolean done = json.optBoolean("done", false);

                            if (json.has("message")) {
                                String content = json.getJSONObject("message")
                                        .optString("content", "");
                                if (!content.isEmpty()) {
                                    rawResponse.append(content);
                                    ReasoningFilterResult filterResult =
                                            filterReasoningSegments(rawResponse.toString());
                                    String filtered = filterResult.visibleText;
                                    setThinkingIndicator(filterResult.reasoningActive, speaker, token);
                                    if (!filtered.startsWith(visibleResponse)) {
                                        if (first && !filtered.isEmpty()) {
                                            beginStreamingMessage(speaker, token);
                                            first = false;
                                        }
                                        resetStreamBuffer();
                                        setStreamingMessage(filtered, token);
                                        if (!streamingStarted && !filtered.isEmpty()) {
                                            setStreamingResponse(true, speaker);
                                            streamingStarted = true;
                                        }
                                        if (ttsEnabled) {
                                            sentenceBuffer.setLength(0);
                                        }
                                        visibleResponse = filtered;
                                        continue;
                                    }
                                    String visibleDelta = filtered.substring(visibleResponse.length());
                                    if (!visibleDelta.isEmpty()) {
                                        if (first) {
                                            beginStreamingMessage(speaker, token);
                                            first = false;
                                        }
                                        queueStreamingChunk(visibleDelta, token);
                                    }
                                    if (!streamingStarted && !filtered.isEmpty()) {
                                        setStreamingResponse(true, speaker);
                                        streamingStarted = true;
                                    }
                                    if (ttsEnabled && !visibleDelta.isEmpty()) {
                                        processChunkForTts(visibleDelta, speaker);
                                    }
                                    visibleResponse = filtered;
                                }
                            }
                            if (done) break;
                        } catch (Exception e) {
                            Log.e(TAG, "Stream parse error", e);
                        }
                    }

                    if (debugRaw != null) {
                        appendDebug("/api/chat Response", buildResponseDebugText(response, debugRaw.toString()));
                    }
                    setThinkingIndicator(false, speaker, token);
                    flushStreamingBuffer(token);
                    finishStreamingMessage(token);

                    if (ttsEnabled) {
                        flushSentenceBuffer(speaker);
                    }

                    String text = visibleResponse;
                    if (!text.trim().isEmpty()) {
                        addToHistory(getHistoryForSpeaker(speaker), "assistant", text);
                    } else {
                        appendAssistantMessage(speaker, "(No response)");
                    }
                    completed = true;
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        appendErrorMessage("Stream error: " + e.getMessage());
                    }
                } finally {
                    currentCall = null;
                    setThinkingIndicator(false, speaker, token);
                    setStreamingResponse(false, null);
                    isProcessing = false;
                    updateSendButton();
                    if (completed) {
                        handleAssistantResponseComplete(speaker, visibleResponse);
                    }
                }
            }
        });
    }

    /** Non-streaming mode: Display full response + TTS */
    private void sendNonStreaming(Request request, ChatSpeaker speaker) {
        Call call = client.newCall(request);
        currentCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                currentCall = null;
                if (c.isCanceled()) {
                    return;
                }
                appendErrorMessage(e.getMessage());
                appendDebug("/api/chat Response (Failed)", e.toString());
                isProcessing = false;
                updateSendButton();
            }

            @Override
            public void onResponse(Call c, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (debugEnabled) {
                    appendDebug("/api/chat Response", buildResponseDebugText(response, body));
                }
                if (!response.isSuccessful()) {
                    appendErrorMessage("HTTP error: " + response.code());
                    currentCall = null;
                    isProcessing = false;
                    updateSendButton();
                    return;
                }

                boolean completed = false;
                String content = "";
                try {
                    JSONObject json = new JSONObject(body);
                    if (json.has("message")) {
                        content = json.getJSONObject("message").optString("content", "");
                    }
                    content = stripReasoningSegments(content);

                    switchActiveSpeaker(speaker);
                    if (!content.trim().isEmpty()) {
                        appendAssistantMessage(speaker, content);
                        addToHistory(getHistoryForSpeaker(speaker), "assistant", content);

                        if (ttsEnabled) {
                            // Sentence-by-sentence TTS
                            for (String sentence : content.split("(?<=[。！？.!?\\n])")) {
                                String trimmed = sentence.trim();
                                if (!trimmed.isEmpty()) {
                                    speakSentence(trimmed, speaker);
                                }
                            }
                        }
                    } else {
                        appendAssistantMessage(speaker, "(No response)");
                    }
                    completed = true;
                } catch (Exception e) {
                    appendErrorMessage("Parse error: " + e.getMessage());
                } finally {
                    currentCall = null;
                    isProcessing = false;
                    updateSendButton();
                    if (completed) {
                        handleAssistantResponseComplete(speaker, content);
                    }
                }
            }
        });
    }

    // ========== UI Helpers ==========

    private String stripReasoningSegments(String text) {
        return filterReasoningSegments(text).visibleText;
    }

    private ReasoningFilterResult filterReasoningSegments(String text) {
        if (text == null || text.isEmpty()) {
            return new ReasoningFilterResult("", false);
        }
        String normalized = collapseExtraNewlineAfterReasoningClose(text);
        String filtered = normalized
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<analysis>.*?</analysis>", "")
                .replaceAll("(?s)<\\|thought\\|>.*?<\\|endthought\\|>", "")
                .replaceAll("(?s)<think>.*$", "")
                .replaceAll("(?s)<analysis>.*$", "")
                .replaceAll("(?s)<\\|thought\\|>.*$", "");
        int trimLen = trailingReasoningOpenPrefixLength(filtered);
        if (trimLen > 0) {
            filtered = filtered.substring(0, filtered.length() - trimLen);
        }
        return new ReasoningFilterResult(filtered, hasUnclosedReasoningBlock(normalized));
    }

    private String collapseExtraNewlineAfterReasoningClose(String text) {
        return text.replaceAll(
                "(</think>|</analysis>|<\\|endthought\\|>)\\r?\\n\\r?\\n",
                "$1\n"
        );
    }

    private boolean hasUnclosedReasoningBlock(String text) {
        return hasUnclosedTag(text, "<think>", "</think>")
                || hasUnclosedTag(text, "<analysis>", "</analysis>")
                || hasUnclosedTag(text, "<|thought|>", "<|endthought|>");
    }

    private boolean hasUnclosedTag(String text, String openTag, String closeTag) {
        int searchFrom = 0;
        while (true) {
            int openIdx = text.indexOf(openTag, searchFrom);
            if (openIdx < 0) return false;
            int closeIdx = text.indexOf(closeTag, openIdx + openTag.length());
            if (closeIdx < 0) return true;
            searchFrom = closeIdx + closeTag.length();
        }
    }

    private int trailingReasoningOpenPrefixLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        int max = 0;
        for (String openTag : REASONING_OPEN_TAGS) {
            int limit = Math.min(openTag.length() - 1, text.length());
            for (int len = 1; len <= limit; len++) {
                if (text.regionMatches(text.length() - len, openTag, 0, len)) {
                    max = Math.max(max, len);
                }
            }
        }
        return max;
    }

    private void appendDebug(String title, String detail) {
        if (!debugEnabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append("【DEBUG】").append(title).append("\n");
        if (detail != null && !detail.isEmpty()) {
            sb.append(detail);
            if (!detail.endsWith("\n")) sb.append("\n");
        }
        appendSystemMessage("DEBUG", sb.toString().trim());
    }

    private String buildRequestDebugText(Request request, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(" ").append(request.url()).append("\n");
        String headers = request.headers().toString();
        sb.append("Headers:\n").append(headers.isEmpty() ? "(none)\n" : headers);
        if (body != null) {
            sb.append("Body:\n").append(body).append("\n");
        } else {
            sb.append("Body: (none)\n");
        }
        return sb.toString();
    }

    private String buildResponseDebugText(Response response, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(response.code());
        String message = response.message();
        if (message != null && !message.isEmpty()) {
            sb.append(" ").append(message);
        }
        sb.append("\n");
        String headers = response.headers().toString();
        sb.append("Headers:\n").append(headers.isEmpty() ? "(none)\n" : headers);
        if (body != null) {
            sb.append("Body:\n").append(body).append("\n");
        } else {
            sb.append("Body: (none)\n");
        }
        return sb.toString();
    }

    private void appendUserMessage(String text) {
        appendMessage("You", text, true, true);
    }

    private void appendAssistantMessage(ChatSpeaker speaker, String text) {
        appendMessage(
                getSpeakerName(speaker),
                stripReasoningSegments(text),
                isUserSideForSpeaker(speaker),
                false
        );
    }

    private void appendErrorMessage(String text) {
        appendSystemMessage("Error", text);
    }

    private void appendSystemMessage(String name, String text) {
        appendMessage(name, text, false, false);
    }

    private void setThinkingIndicator(boolean active, ChatSpeaker speaker, int token) {
        runOnUiThread(() -> {
            if (token != activeStreamingToken) return;
            if (!active) {
                hideThinkingIndicatorInternal();
                return;
            }
            if (currentThinkingBubble != null) return;
            boolean shouldScroll = isNearBottom();
            currentThinkingSpeaker = speaker;
            currentThinkingBubble = createMessageBubble(getSpeakerName(speaker), isUserSideForSpeaker(speaker));
            thinkingDotStep = 0;
            updateThinkingBubbleText();
            uiHandler.removeCallbacks(thinkingAnimationRunnable);
            uiHandler.postDelayed(thinkingAnimationRunnable, THINKING_ANIMATION_INTERVAL_MS);
            requestChatLayoutUpdate();
            maybeScrollToBottom(shouldScroll);
        });
    }

    private void updateThinkingBubbleText() {
        if (currentThinkingBubble == null) return;
        int dots = (thinkingDotStep % 3) + 1;
        StringBuilder body = new StringBuilder("Thinking");
        for (int i = 0; i < dots; i++) {
            body.append('.');
        }
        String header = getSpeakerName(currentThinkingSpeaker);
        currentThinkingBubble.setText(formatMessageText(header, body.toString()));
        currentThinkingBubble.requestLayout();
        currentThinkingBubble.invalidate();
        requestChatLayoutUpdate();
    }

    private void hideThinkingIndicator() {
        runOnUiThread(this::hideThinkingIndicatorInternal);
    }

    private void hideThinkingIndicatorInternal() {
        uiHandler.removeCallbacks(thinkingAnimationRunnable);
        if (currentThinkingBubble == null) return;
        View row = (View) currentThinkingBubble.getParent();
        if (row != null) {
            Object parent = row.getParent();
            if (parent instanceof LinearLayout) {
                ((LinearLayout) parent).removeView(row);
            }
        }
        currentThinkingBubble = null;
        requestChatLayoutUpdate();
    }

    private void beginStreamingMessage(ChatSpeaker speaker, int token) {
        runOnUiThread(() -> {
            if (token != activeStreamingToken) return;
            boolean shouldScroll = isNearBottom();
            currentStreamingSpeaker = speaker;
            currentStreamingBubble = createMessageBubble(getSpeakerName(speaker), isUserSideForSpeaker(speaker));
            String header = getSpeakerName(speaker);
            streamingTextBuffer.setLength(0);
            currentStreamingBubble.setText(formatMessageText(header, ""));
            flushStreamingBuffer(token);
            requestChatLayoutUpdate();
            maybeScrollToBottom(shouldScroll);
        });
    }

    private void appendStreamingMessage(String content, int token) {
        runOnUiThread(() -> appendStreamingMessageInternal(content, token));
    }

    private void setStreamingMessage(String content, int token) {
        runOnUiThread(() -> setStreamingMessageInternal(content, token));
    }

    private void setStreamingMessageInternal(String content, int token) {
        if (token != activeStreamingToken) return;
        if (currentStreamingBubble == null) return;
        boolean shouldScroll = isNearBottom();
        streamingTextBuffer.setLength(0);
        if (content != null) {
            streamingTextBuffer.append(content);
        }
        String header = getSpeakerName(currentStreamingSpeaker);
        currentStreamingBubble.setText(formatMessageText(header, streamingTextBuffer.toString()));
        currentStreamingBubble.requestLayout();
        currentStreamingBubble.invalidate();
        requestChatLayoutUpdate();
        maybeScrollToBottom(shouldScroll);
    }

    private void appendStreamingMessageInternal(String content, int token) {
        if (token != activeStreamingToken) return;
        if (currentStreamingBubble == null) return;
        boolean shouldScroll = isNearBottom();
        streamingTextBuffer.append(content);
        String header = getSpeakerName(currentStreamingSpeaker);
        currentStreamingBubble.setText(formatMessageText(header, streamingTextBuffer.toString()));
        currentStreamingBubble.requestLayout();
        currentStreamingBubble.invalidate();
        requestChatLayoutUpdate();
        maybeScrollToBottom(shouldScroll);
    }

    private void finishStreamingMessage(int token) {
        runOnUiThread(() -> {
            if (token != activeStreamingToken) return;
            currentStreamingBubble = null;
            streamingTextBuffer.setLength(0);
            requestChatLayoutUpdate();
            maybeScrollToBottom(false);
        });
    }

    private void appendMessage(String name, String text, boolean isUserSide, boolean forceScroll) {
        runOnUiThread(() -> {
            boolean shouldScroll = forceScroll || isNearBottom();
            TextView bubble = createMessageBubble(name, isUserSide);
            bubble.setText(formatMessageText(name, text));
            requestChatLayoutUpdate();
            maybeScrollToBottom(shouldScroll);
        });
    }

    private TextView createMessageBubble(String name, boolean isUserSide) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(isUserSide ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dpToPx(4);
        rowParams.bottomMargin = dpToPx(4);
        row.setLayoutParams(rowParams);

        TextView bubble = new TextView(this);
        bubble.setBackgroundResource(isUserSide ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_assistant);
        bubble.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));
        bubble.setTextSize(15);
        bubble.setTextColor(0xFF000000);
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.7f);
        bubble.setMaxWidth(maxWidth);

        row.addView(bubble);
        messageContainer.addView(row);
        return bubble;
    }

    private String formatMessageText(String name, String text) {
        if (name == null || name.trim().isEmpty()) return text == null ? "" : text;
        String body = text == null ? "" : text;
        return name + "\n" + body;
    }

    private void maybeScrollToBottom(boolean force) {
        if (scrollView == null) return;
        if (!force && !isNearBottom()) return;
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private boolean isNearBottom() {
        if (scrollView == null) return true;
        if (scrollView.getChildCount() == 0) return true;
        View child = scrollView.getChildAt(0);
        int diff = child.getBottom() - (scrollView.getHeight() + scrollView.getScrollY());
        return diff <= autoScrollThresholdPx;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void requestChatLayoutUpdate() {
        if (scrollView == null) return;
        if (layoutUpdateScheduled) return;
        layoutUpdateScheduled = true;
        scrollView.post(() -> {
            layoutUpdateScheduled = false;
            if (messageContainer != null) {
                messageContainer.requestLayout();
                messageContainer.invalidate();
                messageContainer.postInvalidateOnAnimation();
            }
            scrollView.requestLayout();
            scrollView.invalidate();
            scrollView.postInvalidateOnAnimation();
            if (chatPanel != null) {
                chatPanel.requestLayout();
                chatPanel.invalidate();
                chatPanel.postInvalidateOnAnimation();
            }
        });
    }

    private void queueStreamingChunk(String content, int token) {
        if (token != activeStreamingToken) return;
        synchronized (streamBufferLock) {
            if (token != activeStreamingToken) return;
            streamBuffer.append(content);
            if (streamFlushScheduled) return;
            streamFlushScheduled = true;
        }
        uiHandler.postDelayed(() -> flushStreamingBuffer(token), STREAM_FLUSH_INTERVAL_MS);
    }

    private void flushStreamingBuffer(int token) {
        if (token != activeStreamingToken) return;
        String chunk;
        synchronized (streamBufferLock) {
            if (token != activeStreamingToken) return;
            if (streamBuffer.length() == 0) {
                streamFlushScheduled = false;
                return;
            }
            chunk = streamBuffer.toString();
            streamBuffer.setLength(0);
            streamFlushScheduled = false;
        }
        if (currentStreamingBubble == null) {
            synchronized (streamBufferLock) {
                if (token != activeStreamingToken) return;
                streamBuffer.append(chunk);
                if (streamFlushScheduled) {
                    return;
                }
                streamFlushScheduled = true;
            }
            uiHandler.postDelayed(() -> flushStreamingBuffer(token), STREAM_FLUSH_INTERVAL_MS);
            return;
        }
        appendStreamingMessageInternal(chunk, token);
    }

    private void resetStreamBuffer() {
        synchronized (streamBufferLock) {
            streamBuffer.setLength(0);
            streamFlushScheduled = false;
        }
        streamingTextBuffer.setLength(0);
    }

    private int startStreamingSession() {
        int token = streamingTokenCounter.incrementAndGet();
        activeStreamingToken = token;
        runOnUiThread(() -> {
            hideThinkingIndicatorInternal();
            currentStreamingBubble = null;
            streamingTextBuffer.setLength(0);
        });
        return token;
    }

    private String getSpeakerName(ChatSpeaker speaker) {
        return speaker == ChatSpeaker.CHATTER ? chatterName : baseName;
    }

    private boolean isUserSideForSpeaker(ChatSpeaker speaker) {
        return speaker == ChatSpeaker.CHATTER;
    }

    // ========== Lifecycle ==========

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File target = getAvatarFileForRequest(requestCode);
        if (target == null) return;
        if (!copyAvatarUriToFile(uri, target)) {
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
            return;
        }
        reloadAvatarBitmaps();
        applyAvatarSelection(requestCode);
        // Get filename from URI for display
        String filename = getFilenameFromUri(uri);
        updateAvatarFilenameDisplay(requestCode, filename != null ? filename : target.getName());
    }

    private String getFilenameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
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
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
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
        hideThinkingIndicator();
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
