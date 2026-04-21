package com.micklab.llamachat;

import android.app.AlertDialog;
import android.app.Activity;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.util.Collections;
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
    private static final String SETTINGS_PROFILE_DIR = "settings_profiles";
    private static final String SETTINGS_PROFILE_SUFFIX = ".json";
    private static final String PROFILE_AVATAR_PREFIX = "profile_avatar_";
    private static final String PROFILE_AVATAR_C0_KEY = "avatarC0";
    private static final String PROFILE_AVATAR_C1_KEY = "avatarC1";
    private static final String PROFILE_AVATAR_C2_KEY = "avatarC2";
    private static final String PROFILE_AVATAR_C3_KEY = "avatarC3";
    private static final String PROFILE_AVATAR_CHATTER_C0_KEY = "avatarChatterC0";
    private static final String PROFILE_AVATAR_CHATTER_C1_KEY = "avatarChatterC1";
    private static final String PROFILE_AVATAR_CHATTER_C2_KEY = "avatarChatterC2";
    private static final String PROFILE_AVATAR_CHATTER_C3_KEY = "avatarChatterC3";
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
    private static final String DEFAULT_BASE_NAME_JA = "藍";
    private static final String DEFAULT_BASE_NAME_EN = "Ai";
    private static final String DEFAULT_CHATTER_NAME_JA = "リサ";
    private static final String DEFAULT_CHATTER_NAME_EN = "Lisa";
    private static final String DEFAULT_SPEECH_LANG_JA = "ja-JP";
    private static final String DEFAULT_SPEECH_LANG_EN = "en-US";
    private static final String DEFAULT_SYSTEM_PROMPT_JA = "あなたはユーザの若い女性秘書です";
    private static final String DEFAULT_SYSTEM_PROMPT_EN = "You are the user's young female secretary.";
    private static final String LLM_TESTER_PACKAGE = "com.micklab.llama";
    private static final String LLM_TESTER_SERVICE_CLASS = "com.micklab.llama.OllamaForegroundService";
    private static final String LLM_TESTER_SERVICE_ACTION_START = "com.micklab.llama.START_SERVICE";
    private static final String LLM_TESTER_PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.micklab.llama";
    private static final int DEFAULT_LLM_TESTER_API_PORT = 11434;
    private static final String OLLAMA_STATUS_AVAILABLE_TEXT = "LLM API Available";
    private static final String OLLAMA_STATUS_UNAVAILABLE_TEXT = "LLM API NOT Available";
    private static final int OLLAMA_STATUS_AVAILABLE_BG = 0xFF81C784;
    private static final int OLLAMA_STATUS_UNAVAILABLE_BG = 0xFFFFF59D;
    private static final int OLLAMA_STATUS_TEXT_COLOR = 0xFF000000;
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
    private Button btnResetLogs, btnProfileSave, btnProfileLoad, btnProfileDelete, btnLaunchLlmTester;
    private Button btnHelp, btnPrivacy, btnRights;
    private TextView tvC0Filename, tvC1Filename, tvC2Filename, tvC3Filename;
    private TextView tvChatterC0Filename, tvChatterC1Filename, tvChatterC2Filename, tvChatterC3Filename;
    private ScrollView scrollView;
    private View settingsPanel;
    private View topPanel;
    private LinearLayout chatPanel;
    private LinearLayout mainLayout;
    private View mainLayer;
    private View chatDragArea;
    private View chatDragHandle;
    private Spinner spinnerLanguage, spinnerModel, spinnerChatterModel, spinnerWebSearchModel;
    private RadioGroup groupMode, tabGroup;
    private RadioButton radioModeNormal, radioModeChatter, tabBase, tabChatter;
    private LinearLayout baseSettingsGroup, chatterSettingsGroup;
    private LinearLayout sectionGeneralContent, sectionChatContent, sectionExpertContent;
    private TextView tvSettingsTitle, tvSectionGeneral, tvSectionChat, tvSectionExpert, tvSectionInfo;
    private TextView tvLabelLanguage, tvLabelConfigProfile, tvLabelOllamaUrl, tvLabelUserName, tvOllamaStatus;
    private TextView tvLabelMode, tvLabelHistoryLimit, tvLabelChatterInterval;
    private TextView tvLabelWebSearchUrl, tvLabelWebSearchApiKey, tvLabelWebSearchModel;
    private TextView tvBaseSettingsTitle, tvBaseNameLabel, tvBaseModelLabel;
    private TextView tvBaseSpeechLangLabel, tvBaseSpeechRateLabel, tvBaseSpeechPitchLabel, tvBaseSystemPromptLabel, tvBaseAvatarTitle;
    private TextView tvChatterSettingsTitle, tvChatterNameLabel, tvChatterModelLabel;
    private TextView tvChatterSpeechLangLabel, tvChatterSpeechRateLabel, tvChatterSpeechPitchLabel, tvChatterSystemPromptLabel, tvChatterAvatarTitle;
    private View sectionGeneralHeader, sectionChatHeader, sectionExpertHeader;
    private Switch switchStreaming, switchTts, switchVoiceInput, switchAutoVoiceInput, switchWebSearch, switchDebug;
    private EditText etOllamaUrl, etSpeechLang, etSpeechRate, etSpeechPitch, etSystemPrompt;
    private EditText etChatterSpeechLang, etChatterSpeechRate, etChatterSpeechPitch, etChatterSystemPrompt;
    private EditText etBaseName, etChatterName;
    private EditText etHistoryLimit, etAutoChatterSeconds;
    private EditText etWebSearchUrl, etWebSearchApiKey, etProfileName, etUserName;
    private ImageView ivAvatarBackground;
    private ImageView ivAvatar;
    private FrameLayout counterpartMiniContainer;
    private ImageView ivCounterpartMiniBackground;
    private ImageView ivCounterpartMiniAvatar;

    // --- Settings (defaults) ---
    private String ollamaBaseUrl = "http://127.0.0.1:11434";
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
    private String webSearchModel = "default";
    private String speechLang = DEFAULT_SPEECH_LANG_JA;
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private String systemPromptText = DEFAULT_SYSTEM_PROMPT_JA;
    private String chatterSpeechLang = DEFAULT_SPEECH_LANG_JA;
    private float chatterSpeechRate = 1.0f;
    private float chatterSpeechPitch = 1.0f;
    private String chatterSystemPromptText = DEFAULT_SYSTEM_PROMPT_JA;
    private String baseName = DEFAULT_BASE_NAME_JA;
    private String chatterName = DEFAULT_CHATTER_NAME_JA;
    private String userName = "";
    private int historyLimit = 10;
    private int autoChatterSeconds = 10;
    private String appLanguage = Locale.getDefault().getLanguage().startsWith("ja") ? "ja" : "en";
    private boolean sectionGeneralExpanded = true;
    private boolean sectionChatExpanded = true;
    private boolean sectionExpertExpanded = false;
    private boolean suppressQuickStartPopup = false;
    private boolean ollamaApiAvailable = false;
    private boolean refreshModelsOnResume = false;

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
    private ArrayAdapter<String> webSearchModelAdapter;

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
    private static final String HELP_TEXT_EN =
            "Dual AI Chat — Manual\n\n" +
            "■ Quick Start\n" +
            "・At startup, the app shows a Quick Start popup unless you choose Do not show again.\n" +
            "・Before chatting, install and start LLM Tester with llama.cpp or Ollama, then enable the API server.\n" +
            "・In Settings, use Launch LLM API above the API status tile. If LLM Tester is missing, the Play Store page opens.\n\n" +
            "■ Screen\n" +
            "・Tap ⚙️ to open settings, then 💾 to save and close.\n" +
            "・Tap the top handle of the log area to expand/collapse the chat panel.\n\n" +
            "■ Settings\n" +
            "・Choose the app language from the dropdown as English or 日本語.\n" +
            "・Use Launch LLM API above the API status tile to launch LLM Tester.\n" +
            "・The tile under Ollama URL shows the /api/tags status.\n" +
            "・Use Settings to configure connection settings and avatar images.\n" +
            "・Use SELECT Template under Config Profile to load templates.\n" +
            "・On startup and each time settings opens, /api/tags refreshes model lists and the API status tile.\n\n" +
            "■ Sending\n" +
            "・Enter a message and press Send.\n" +
            "・If Voice Input is enabled, pressing Send with empty input starts voice input.\n" +
            "・While processing, the button shows STOP and can cancel the request.\n" +
            "・Note: On the first message, approximately 1GB of data may be downloaded for model downloads by the LLM API, which can take time.\n\n" +
            "■ Reset\n" +
            "・To reset the conversation, press Reset Conversation Log in Settings.\n\n" +
            "■ Modes\n" +
            "・Normal: Chat with a single model.\n" +
            "・Chatter: Base and Chatter Partner alternate turns.\n" +
            "・Chatter Interval sets the delay between turns (seconds).\n\n" +
            "■ Settings Tabs\n" +
            "・Use the Base/Chatter Partner tabs to switch settings.\n" +
            "・Configure Model/Name/System Prompt/Speech Language/Rate/Pitch.\n\n" +
            "■ Response\n" +
            "・Streaming shows responses in real time.\n" +
            "・Text-to-Speech reads responses aloud.\n" +
            "・History Limit controls how many past messages are sent.\n\n" +
            "■ Expert Settings\n" +
            "・Web Search: Enable to use the configured search API.\n" +
            "・Web Search Model is selected from /api/tags list (default: default).\n" +
            "・Brave endpoints use Brave-optimized search handling.\n" +
            "・Debug Mode shows API request/response logs.\n\n" +
            "■ Avatar\n" +
            "・c0: Background\n" +
            "・c1: Base\n" +
            "・c2: Blink\n" +
            "・c3: Talking\n" +
            "・You can set different images for Base and Chatter Partner.\n" +
            "・Press Clear to reset to default.\n\n" +
            "■ Other\n" +
            "・Settings, templates, popup preferences, and images are stored locally on the device.";

    private static final String HELP_TEXT_JA =
            "Dual AI Chat — マニュアル\n\n" +
            "■ クイックスタート\n" +
            "・起動時に、次回から表示しないを選ぶまでクイックスタートを表示します。\n" +
            "・チャットを使う前に、LLM Tester with llama.cppまたはOllamaをインストール/起動し、APIサーバを有効化してください。\n" +
            "・設定画面では、API状態タイルの上にある「LLM APIを起動」からLLM Testerを開けます。未インストール時はGoogle Playを開きます。\n\n" +
            "■ 画面\n" +
            "・⚙️ で設定を開き、💾で保存して閉じます。\n" +
            "・ログ上部のバーをタップするとチャットエリアが拡大/縮小します。\n\n" +
            "■ 設定\n" +
            "・言語はプルダウンからEnglishまたは日本語を選択します。\n" +
            "・API状態タイルの上にある「LLM APIを起動」からLLM Testerを起動できます。\n" +
            "・Ollama URLの下に/api/tagsの接続状態をタイル表示します。\n" +
            "・設定画面で接続設定やアバター画像を設定できます。\n" +
            "・Config Profileの下にある SELECT Template でテンプレートを読み込みます。\n" +
            "・起動時と設定画面を開くたびに/api/tagsを再取得し、モデル一覧とAPI状態を更新します。\n\n" +
            "■ 送信\n" +
            "・メッセージ入力後にSendで送信します。\n" +
            "・Voice Inputを有効にすると、未入力のままSendで音声入力を開始します。\n" +
            "・処理中はボタンがSTOPになり、タップで中止できます。\n" +
            "・注記: 初回のメッセージ送信時には約1GB程度の通信が発生します。これはLLM API側でモデルのダウンロードが行われることによるもので、反応まで時間がかかる場合があります。\n\n" +
            "■ リセット\n" +
            "・会話をリセットしたい場合は、設定画面のReset Conversation Logを押してください。\n\n" +
            "■ モード\n" +
            "・Normal: 1つのモデルでチャットします。\n" +
            "・おしゃべり: Baseとおしゃべり相手が交互に会話します。\n" +
            "・おしゃべり間隔で発話間隔（秒）を設定します。\n\n" +
            "■ 設定タブ\n" +
            "・Base/おしゃべり相手のタブで各モデル設定を切り替えます。\n" +
            "・Model/Name/System Prompt/Speech Language/Rate/Pitchを設定できます。\n\n" +
            "■ 応答\n" +
            "・Streaming: 応答をリアルタイム表示します。\n" +
            "・Text-to-Speech: 応答を音声で読み上げます。\n" +
            "・History Limitで送信する履歴数を調整します。\n\n" +
            "■ エキスパート設定\n" +
            "・Web Search: 有効にすると検索APIを使います。\n" +
            "・Web Search Modelは/api/tagsの一覧から選択できます（初期値: default）。\n" +
            "・Brave URLの場合はBrave向けに最適化した検索処理を使います。\n" +
            "・Debug Modeで通信ログを表示します。\n\n" +
            "■ アバター\n" +
            "・c0: 背景\n" +
            "・c1: 基本表情\n" +
            "・c2: まばたき\n" +
            "・c3: 会話中\n" +
            "・Baseとおしゃべり相手で別々に設定できます。\n" +
            "・Clearでデフォルトに戻します。\n\n" +
            "■ その他\n" +
            "・設定、テンプレート、ポップアップ表示設定、画像は端末内に保存されます。";

    private static final String PRIVACY_TEXT_EN =
            "Dual AI Chat — Privacy Policy\n\n" +
            "■ Data Collection\n" +
            "・The developer does not collect your conversation data.\n" +
            "・All conversations occur only with your configured Ollama-compatible server.\n" +
            "・On startup and when opening settings, the app sends a GET request to /api/tags to refresh model lists and update the API status tile.\n" +
            "・This request does not include conversation message content.\n\n" +
            "■ Local Data\n" +
            "・Settings, language selection, template names, quick start popup preference, and avatar images are stored locally on device.\n\n" +
            "■ Web Search\n" +
            "・When using Web Search, queries are sent to your configured API.\n" +
            "・If a Brave URL is configured, requests follow Brave Search API format.\n\n" +
            "■ Voice Recognition\n" +
            "・Voice recognition uses your device's system features.";

    private static final String PRIVACY_TEXT_JA =
            "Dual AI Chat — プライバシーポリシー\n\n" +
            "■ データの収集\n" +
            "・開発者は会話データを収集しません。\n" +
            "・すべての会話は設定されたOllama互換サーバーとの間でのみ行われます。\n" +
            "・起動時と設定画面を開いたときに、モデル一覧更新とAPI状態表示のため/api/tagsにGETリクエストを送信します。\n" +
            "・このリクエストに会話本文は含まれません。\n\n" +
            "■ ローカルデータ\n" +
            "・設定、言語選択、テンプレート名、クイックスタートの表示設定、アバター画像はデバイス内に保存されます。\n\n" +
            "■ Web検索\n" +
            "・Web検索機能を使用する場合、検索クエリは設定されたAPIに送信されます。\n" +
            "・Brave URLを設定した場合はBrave Search API形式で送信されます。\n\n" +
            "■ 音声認識\n" +
            "・音声認識はデバイスのシステム機能を使用します.";

    private static final String RIGHTS_TEXT_EN =
            "Dual AI Chat — Rights Information\n\n" +
            "■ Application\n" +
            "・This application is open source software.\n\n" +
            "■ Libraries Used\n" +
            "・OkHttp - Apache License 2.0\n" +
            "・Android SDK - Apache License 2.0\n\n" +
            "■ Disclaimer\n" +
            "・Developer is not responsible for AI response content.\n" +
            "・Use this app at your own risk.\n\n" +
            "■ Contact\n" +
            "・Email: micklab2026@gmail.com";

    private static final String RIGHTS_TEXT_JA =
            "Dual AI Chat — 権利情報\n\n" +
            "■ アプリケーション\n" +
            "・本アプリはオープンソースソフトウェアです。\n\n" +
            "■ 使用ライブラリ\n" +
            "・OkHttp - Apache License 2.0\n" +
            "・Android SDK - Apache License 2.0\n\n" +
            "■ 免責事項\n" +
            "・AIの応答内容について開発者は責任を負いません。\n" +
            "・本アプリの使用は自己責任でお願いします。\n\n" +
            "■ 連絡先\n" +
            "・Email: micklab2026@gmail.com";

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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_main);

        initViews();
        setupWindowInsets();
        initAvatarAssets();
        initAvatarAnimation();
        loadSettings();
        applySettingsToUi();
        initTts();
        initConversationHistory();
        setupListeners();
        if (savedInstanceState == null) {
            showQuickStartDialogIfNeeded();
        }
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
        btnResetLogs = findViewById(R.id.btnResetLogs);
        btnProfileSave = findViewById(R.id.btnProfileSave);
        btnProfileLoad = findViewById(R.id.btnProfileLoad);
        btnProfileDelete = findViewById(R.id.btnProfileDelete);
        btnLaunchLlmTester = findViewById(R.id.btnLaunchLlmTester);
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
        mainLayout = findViewById(R.id.mainLayout);
        mainLayer = findViewById(android.R.id.content);
        if (mainLayer != null) {
            mainLayer.setFocusable(true);
            mainLayer.setFocusableInTouchMode(true);
        }
        chatDragArea = findViewById(R.id.chatDragArea);
        chatDragHandle = findViewById(R.id.chatDragHandle);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
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
        etProfileName = findViewById(R.id.etProfileName);
        etOllamaUrl = findViewById(R.id.etOllamaUrl);
        etUserName = findViewById(R.id.etUserName);
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
        spinnerWebSearchModel = findViewById(R.id.etWebSearchModel);
        sectionGeneralHeader = findViewById(R.id.sectionGeneralHeader);
        sectionChatHeader = findViewById(R.id.sectionChatHeader);
        sectionExpertHeader = findViewById(R.id.sectionExpertHeader);
        sectionGeneralContent = findViewById(R.id.sectionGeneralContent);
        sectionChatContent = findViewById(R.id.sectionChatContent);
        sectionExpertContent = findViewById(R.id.sectionExpertContent);
        tvSettingsTitle = findViewById(R.id.tvSettingsTitle);
        tvSectionGeneral = findViewById(R.id.tvSectionGeneral);
        tvSectionChat = findViewById(R.id.tvSectionChat);
        tvSectionExpert = findViewById(R.id.tvSectionExpert);
        tvSectionInfo = findViewById(R.id.tvSectionInfo);
        tvLabelLanguage = findViewById(R.id.tvLabelLanguage);
        tvLabelConfigProfile = findViewById(R.id.tvLabelConfigProfile);
        tvLabelOllamaUrl = findViewById(R.id.tvLabelOllamaUrl);
        tvOllamaStatus = findViewById(R.id.tvOllamaStatus);
        tvLabelUserName = findViewById(R.id.tvLabelUserName);
        tvLabelMode = findViewById(R.id.tvLabelMode);
        tvLabelHistoryLimit = findViewById(R.id.tvLabelHistoryLimit);
        tvLabelChatterInterval = findViewById(R.id.tvLabelChatterInterval);
        tvLabelWebSearchUrl = findViewById(R.id.tvLabelWebSearchUrl);
        tvLabelWebSearchApiKey = findViewById(R.id.tvLabelWebSearchApiKey);
        tvLabelWebSearchModel = findViewById(R.id.tvLabelWebSearchModel);
        tvBaseSettingsTitle = findViewById(R.id.tvBaseSettingsTitle);
        tvBaseNameLabel = findViewById(R.id.tvBaseNameLabel);
        tvBaseModelLabel = findViewById(R.id.tvBaseModelLabel);
        tvBaseSpeechLangLabel = findViewById(R.id.tvBaseSpeechLangLabel);
        tvBaseSpeechRateLabel = findViewById(R.id.tvBaseSpeechRateLabel);
        tvBaseSpeechPitchLabel = findViewById(R.id.tvBaseSpeechPitchLabel);
        tvBaseSystemPromptLabel = findViewById(R.id.tvBaseSystemPromptLabel);
        tvBaseAvatarTitle = findViewById(R.id.tvBaseAvatarTitle);
        tvChatterSettingsTitle = findViewById(R.id.tvChatterSettingsTitle);
        tvChatterNameLabel = findViewById(R.id.tvChatterNameLabel);
        tvChatterModelLabel = findViewById(R.id.tvChatterModelLabel);
        tvChatterSpeechLangLabel = findViewById(R.id.tvChatterSpeechLangLabel);
        tvChatterSpeechRateLabel = findViewById(R.id.tvChatterSpeechRateLabel);
        tvChatterSpeechPitchLabel = findViewById(R.id.tvChatterSpeechPitchLabel);
        tvChatterSystemPromptLabel = findViewById(R.id.tvChatterSystemPromptLabel);
        tvChatterAvatarTitle = findViewById(R.id.tvChatterAvatarTitle);
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

        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"English", "日本語"}
        );
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languageAdapter);

        modelList.add("default");
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(modelAdapter);
        spinnerModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object obj = parent.getItemAtPosition(position);
                String model = obj != null ? obj.toString() : "";
                String current = spinnerWebSearchModel.getSelectedItem() != null
                        ? spinnerWebSearchModel.getSelectedItem().toString().trim() : "";
                if (model != null && !model.isEmpty() && (current.isEmpty() || "default".equals(current))) {
                    int webSearchIdx = modelList.indexOf(model);
                    if (webSearchIdx >= 0) spinnerWebSearchModel.setSelection(webSearchIdx);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        chatterModelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        chatterModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerChatterModel.setAdapter(chatterModelAdapter);
        webSearchModelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        webSearchModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerWebSearchModel.setAdapter(webSearchModelAdapter);
        updateOllamaStatusTile(ollamaApiAvailable);
    }

    private void setupListeners() {
        btnSettings.setOnClickListener(v -> {
            if (settingsPanel.getVisibility() == View.VISIBLE) {
                readSettingsFromUi();
                saveSettings();
                settingsPanel.setVisibility(View.GONE);
                reinitSystemPrompts();
                focusMainLayerAfterSettings();
                btnSettings.setText("⚙");
                btnSettings.setContentDescription("Open settings");
            } else {
                settingsPanel.setVisibility(View.VISIBLE);
                fetchModels();
                btnSettings.setText("💾");
                btnSettings.setContentDescription("Save settings");
            }
        });

        // Initialize settings button label based on panel visibility
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            btnSettings.setText("💾");
            btnSettings.setContentDescription("Save settings");
        } else {
            btnSettings.setText("⚙");
            btnSettings.setContentDescription("Open settings");
        }

        btnLaunchLlmTester.setOnClickListener(v -> openLlmTesterOrStore());

        groupMode.setOnCheckedChangeListener((group, checkedId) -> {
            autoChatterEnabled = checkedId == R.id.radioModeChatter;
            updateChatterModeUi();
        });

        tabGroup.setOnCheckedChangeListener((group, checkedId) -> updateSettingsTab());
        if (sectionGeneralHeader != null) {
            sectionGeneralHeader.setOnClickListener(v -> {
                sectionGeneralExpanded = !sectionGeneralExpanded;
                sectionGeneralContent.setVisibility(sectionGeneralExpanded ? View.VISIBLE : View.GONE);
                tvSectionGeneral.setText((sectionGeneralExpanded ? "▼ " : "▶ ") + t("General", "一般"));
            });
        }
        if (sectionChatHeader != null) {
            sectionChatHeader.setOnClickListener(v -> {
                sectionChatExpanded = !sectionChatExpanded;
                sectionChatContent.setVisibility(sectionChatExpanded ? View.VISIBLE : View.GONE);
                tvSectionChat.setText((sectionChatExpanded ? "▼ " : "▶ ") + t("Chat", "チャット"));
            });
        }
        if (sectionExpertHeader != null) {
            sectionExpertHeader.setOnClickListener(v -> {
                sectionExpertExpanded = !sectionExpertExpanded;
                sectionExpertContent.setVisibility(sectionExpertExpanded ? View.VISIBLE : View.GONE);
                tvSectionExpert.setText((sectionExpertExpanded ? "▼ " : "▶ ") + t("Expert", "エキスパート"));
            });
        }
        if (spinnerLanguage != null) {
            spinnerLanguage.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String newLang = position == 1 ? "ja" : "en";
                    if (!newLang.equals(appLanguage)) {
                        appLanguage = newLang;
                        applyLanguageSpecificDefaults();
                        syncLanguageSpecificFieldsToUi();
                        applyLanguageToUi();
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
        }
        if (chatDragArea != null) {
            chatDragArea.setOnClickListener(v -> setChatExpanded(!chatExpanded));
        }
        View contentView = findViewById(android.R.id.content);
        if (contentView != null) {
            contentView.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                if (oldFocus instanceof EditText && !(newFocus instanceof EditText)) {
                    hideKeyboard(oldFocus);
                }
            });
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
                Toast.makeText(this, t("Please enter a message", "メッセージを入力してください"), Toast.LENGTH_SHORT).show();
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
        btnResetLogs.setOnClickListener(v -> showResetConversationLogsConfirmation());
        btnProfileSave.setOnClickListener(v -> {
            String profileName = etProfileName.getText().toString().trim();
            if (profileName.isEmpty()) {
                Toast.makeText(this, t("Enter profile name", "プロフィール名を入力してください"), Toast.LENGTH_SHORT).show();
                return;
            }
            readSettingsFromUi();
            saveSettings();
            if (saveSettingsProfile(profileName)) {
                Toast.makeText(this, t("Profile saved", "プロフィールを保存しました"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, t("Failed to save profile", "プロフィールの保存に失敗しました"), Toast.LENGTH_SHORT).show();
            }
        });
        btnProfileLoad.setOnClickListener(v -> {
            List<String> profileNames = getSavedProfileNames();
            if (profileNames.isEmpty()) {
                Toast.makeText(this, t("No saved profiles", "保存済みプロフィールがありません"), Toast.LENGTH_SHORT).show();
                return;
            }
            String[] options = profileNames.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(t("Load Profile", "プロフィールの読み込み"))
                    .setItems(options, (dialog, which) -> {
                        String profileName = options[which];
                        etProfileName.setText(profileName);
                        if (loadSettingsProfile(profileName)) {
                            applySettingsToUi();
                            reinitSystemPrompts();
                            saveSettings();
                            Toast.makeText(this, t("Profile loaded", "プロフィールを読み込みました"), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, t("Profile not found", "プロフィールが見つかりません"), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });
        btnProfileDelete.setOnClickListener(v -> {
            String profileName = etProfileName.getText().toString().trim();
            if (profileName.isEmpty()) {
                Toast.makeText(this, t("Enter profile name", "プロフィール名を入力してください"), Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(t("Delete Profile", "プロフィールの削除"))
                    .setMessage(t("Delete profile", "プロフィール") + " \"" + profileName + "\" " + t("?", "を削除しますか？"))
                    .setPositiveButton(t("Delete", "削除"), (dialog, which) -> {
                        if (deleteSettingsProfile(profileName)) {
                            Toast.makeText(this, t("Profile deleted", "プロフィールを削除しました"), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, t("Profile not found", "プロフィールが見つかりません"), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(t("Cancel", "キャンセル"), null)
                    .show();
        });

        btnHelp.setOnClickListener(v -> showInfoDialog(
                t("Help", "ヘルプ"),
                "ja".equals(appLanguage) ? HELP_TEXT_JA : HELP_TEXT_EN));
        btnPrivacy.setOnClickListener(v -> showInfoDialog(
                t("Privacy Policy", "プライバシーポリシー"),
                "ja".equals(appLanguage) ? PRIVACY_TEXT_JA : PRIVACY_TEXT_EN));
        btnRights.setOnClickListener(v -> showInfoDialog(
                t("Rights", "権利情報"),
                "ja".equals(appLanguage) ? RIGHTS_TEXT_JA : RIGHTS_TEXT_EN));
    }

    private void focusMainLayerAfterSettings() {
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            hideKeyboard(focused);
        }
        focusMainLayer();
    }

    private void focusMainLayer() {
        View target = mainLayer != null ? mainLayer : findViewById(android.R.id.content);
        if (target != null) {
            target.setFocusable(true);
            target.setFocusableInTouchMode(true);
            target.requestFocus();
        }
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }

    private String defaultBaseNameForCurrentLanguage() {
        return "ja".equals(appLanguage) ? DEFAULT_BASE_NAME_JA : DEFAULT_BASE_NAME_EN;
    }

    private String defaultChatterNameForCurrentLanguage() {
        return "ja".equals(appLanguage) ? DEFAULT_CHATTER_NAME_JA : DEFAULT_CHATTER_NAME_EN;
    }

    private String defaultSpeechLangForCurrentLanguage() {
        return "ja".equals(appLanguage) ? DEFAULT_SPEECH_LANG_JA : DEFAULT_SPEECH_LANG_EN;
    }

    private String defaultSystemPromptForCurrentLanguage() {
        return "ja".equals(appLanguage) ? DEFAULT_SYSTEM_PROMPT_JA : DEFAULT_SYSTEM_PROMPT_EN;
    }

    private String defaultUserLabel() {
        return t("User", "ユーザ");
    }

    private boolean isDefaultBaseName(String value) {
        return DEFAULT_BASE_NAME_JA.equals(value) || DEFAULT_BASE_NAME_EN.equals(value);
    }

    private boolean isDefaultChatterName(String value) {
        return DEFAULT_CHATTER_NAME_JA.equals(value) || DEFAULT_CHATTER_NAME_EN.equals(value);
    }

    private boolean isDefaultSystemPrompt(String value) {
        return DEFAULT_SYSTEM_PROMPT_JA.equals(value) || DEFAULT_SYSTEM_PROMPT_EN.equals(value);
    }

    private void applyLanguageSpecificDefaults() {
        if (isDefaultBaseName(baseName)) baseName = defaultBaseNameForCurrentLanguage();
        if (isDefaultChatterName(chatterName)) chatterName = defaultChatterNameForCurrentLanguage();
        if (isDefaultSystemPrompt(systemPromptText)) systemPromptText = defaultSystemPromptForCurrentLanguage();
        if (isDefaultSystemPrompt(chatterSystemPromptText)) chatterSystemPromptText = defaultSystemPromptForCurrentLanguage();
        speechLang = defaultSpeechLangForCurrentLanguage();
        chatterSpeechLang = defaultSpeechLangForCurrentLanguage();
    }

    private void syncLanguageSpecificFieldsToUi() {
        if (etBaseName != null) etBaseName.setText(baseName);
        if (etChatterName != null) etChatterName.setText(chatterName);
        if (etSpeechLang != null) etSpeechLang.setText(speechLang);
        if (etChatterSpeechLang != null) etChatterSpeechLang.setText(chatterSpeechLang);
        if (etSystemPrompt != null) etSystemPrompt.setText(systemPromptText);
        if (etChatterSystemPrompt != null) etChatterSystemPrompt.setText(chatterSystemPromptText);
    }

    private void applyLanguageToUi() {
        if (tvSettingsTitle != null) tvSettingsTitle.setText("⚙️ " + t("Settings", "設定"));
        if (tvSectionGeneral != null) tvSectionGeneral.setText((sectionGeneralExpanded ? "▼ " : "▶ ") + t("General", "一般"));
        if (tvSectionChat != null) tvSectionChat.setText((sectionChatExpanded ? "▼ " : "▶ ") + t("Chat", "チャット"));
        if (tvSectionExpert != null) tvSectionExpert.setText((sectionExpertExpanded ? "▼ " : "▶ ") + t("Expert", "エキスパート"));
        if (tvSectionInfo != null) tvSectionInfo.setText(t("Information", "情報"));
        if (tvLabelLanguage != null) tvLabelLanguage.setText(t("Language", "言語"));
        if (tvLabelConfigProfile != null) tvLabelConfigProfile.setText(t("Config Profile", "設定テンプレート"));
        if (tvLabelOllamaUrl != null) tvLabelOllamaUrl.setText("Ollama URL");
        if (tvLabelUserName != null) tvLabelUserName.setText(t("User Name", "ユーザー名"));
        if (tvLabelMode != null) tvLabelMode.setText(t("Mode", "モード"));
        if (tvLabelHistoryLimit != null) tvLabelHistoryLimit.setText(t("History Limit", "履歴制限"));
        if (tvLabelChatterInterval != null) tvLabelChatterInterval.setText(t("Chatter Interval (sec)", "おしゃべり間隔（秒）"));
        if (tvLabelWebSearchUrl != null) tvLabelWebSearchUrl.setText(t("Web Search API URL", "Web検索 API URL"));
        if (tvLabelWebSearchApiKey != null) tvLabelWebSearchApiKey.setText(t("Web Search API Key", "Web検索 APIキー"));
        if (tvLabelWebSearchModel != null) tvLabelWebSearchModel.setText(t("Web Search Model", "Web検索モデル"));
        if (tvBaseSettingsTitle != null) tvBaseSettingsTitle.setText(t("Base Settings", "Baseの設定"));
        if (tvBaseNameLabel != null) tvBaseNameLabel.setText(t("Name", "名前"));
        if (tvBaseModelLabel != null) tvBaseModelLabel.setText(t("Model", "モデル"));
        if (tvBaseSpeechLangLabel != null) tvBaseSpeechLangLabel.setText(t("Speech Language", "音声言語"));
        if (tvBaseSpeechRateLabel != null) tvBaseSpeechRateLabel.setText(t("Speech Rate", "話速"));
        if (tvBaseSpeechPitchLabel != null) tvBaseSpeechPitchLabel.setText(t("Speech Pitch", "ピッチ"));
        if (tvBaseSystemPromptLabel != null) tvBaseSystemPromptLabel.setText(t("System Prompt", "システムプロンプト"));
        if (tvBaseAvatarTitle != null) tvBaseAvatarTitle.setText(t("Avatar Images", "アバター画像"));
        if (tvChatterSettingsTitle != null) tvChatterSettingsTitle.setText(t("Chatter Partner Settings", "おしゃべり相手の設定"));
        if (tvChatterNameLabel != null) tvChatterNameLabel.setText(t("Name", "名前"));
        if (tvChatterModelLabel != null) tvChatterModelLabel.setText(t("Model", "モデル"));
        if (tvChatterSpeechLangLabel != null) tvChatterSpeechLangLabel.setText(t("Speech Language", "音声言語"));
        if (tvChatterSpeechRateLabel != null) tvChatterSpeechRateLabel.setText(t("Speech Rate", "話速"));
        if (tvChatterSpeechPitchLabel != null) tvChatterSpeechPitchLabel.setText(t("Speech Pitch", "ピッチ"));
        if (tvChatterSystemPromptLabel != null) tvChatterSystemPromptLabel.setText(t("System Prompt", "システムプロンプト"));
        if (tvChatterAvatarTitle != null) tvChatterAvatarTitle.setText(t("Avatar Images", "アバター画像"));
        if (btnResetLogs != null) btnResetLogs.setText(t("Reset Conversation Log", "会話ログをリセット"));
        if (btnProfileLoad != null) btnProfileLoad.setText(t("SELECT Template", "テンプレート選択"));
        if (btnProfileSave != null) btnProfileSave.setText(t("Save", "保存"));
        if (btnProfileDelete != null) btnProfileDelete.setText(t("Delete", "削除"));
        if (btnLaunchLlmTester != null) btnLaunchLlmTester.setText(t("Launch LLM API", "LLM APIを起動"));
        if (btnHelp != null) btnHelp.setText(t("Help", "ヘルプ"));
        if (btnPrivacy != null) btnPrivacy.setText(t("Privacy", "プライバシー"));
        if (btnRights != null) btnRights.setText(t("Rights", "権利情報"));
        if (switchStreaming != null) switchStreaming.setText("Streaming");
        if (switchTts != null) switchTts.setText(t("Text-to-Speech", "音声読み上げ"));
        if (switchVoiceInput != null) switchVoiceInput.setText(t("Voice Input (on empty send)", "音声入力（空送信）"));
        if (switchAutoVoiceInput != null) switchAutoVoiceInput.setText(t("Auto Voice Input (after response)", "自動音声入力（応答後）"));
        if (switchWebSearch != null) switchWebSearch.setText(t("Web Search", "Web検索"));
        if (switchDebug != null) switchDebug.setText(t("Debug Mode", "デバッグモード"));
        if (radioModeNormal != null) radioModeNormal.setText(t("Normal", "ノーマル"));
        if (radioModeChatter != null) radioModeChatter.setText(t("Chatter", "おしゃべり"));
        if (tabBase != null) tabBase.setText("Base");
        if (tabChatter != null) tabChatter.setText(t("Chatter Partner", "おしゃべり相手"));
        if (etInput != null) etInput.setHint(t("Enter message", "メッセージを入力"));
        if (sectionGeneralContent != null) sectionGeneralContent.setVisibility(sectionGeneralExpanded ? View.VISIBLE : View.GONE);
        if (sectionChatContent != null) sectionChatContent.setVisibility(sectionChatExpanded ? View.VISIBLE : View.GONE);
        if (sectionExpertContent != null) sectionExpertContent.setVisibility(sectionExpertExpanded ? View.VISIBLE : View.GONE);
        updateSendButton();
    }

    private int languageSpinnerPositionForCurrentLanguage() {
        return "ja".equals(appLanguage) ? 1 : 0;
    }

    private void updateOllamaStatusTile(boolean available) {
        ollamaApiAvailable = available;
        runOnUiThread(() -> {
            if (tvOllamaStatus == null) return;
            tvOllamaStatus.setText(available ? OLLAMA_STATUS_AVAILABLE_TEXT : OLLAMA_STATUS_UNAVAILABLE_TEXT);
            tvOllamaStatus.setTextColor(OLLAMA_STATUS_TEXT_COLOR);
            if (tvOllamaStatus.getBackground() != null) {
                tvOllamaStatus.getBackground().mutate().setTint(
                        available ? OLLAMA_STATUS_AVAILABLE_BG : OLLAMA_STATUS_UNAVAILABLE_BG
                );
            }
        });
    }

    private void openLlmTesterOrStore() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(LLM_TESTER_PACKAGE);
        if (launchIntent == null) {
            openLlmTesterPlayStore();
            return;
        }

        boolean apiStartRequested = requestLlmTesterApiStart();
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        refreshModelsOnResume = true;
        try {
            startActivity(launchIntent);
            if (!apiStartRequested) {
                Toast.makeText(
                        this,
                        t("Opened LLM Tester. If needed, enable the API server there.",
                                "LLM Testerを開きました。必要に応じてアプリ側でAPIサーバを有効化してください。"),
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "LLM Tester launch activity not found", e);
            openLlmTesterPlayStore();
        }
    }

    private boolean requestLlmTesterApiStart() {
        Intent serviceIntent = new Intent();
        serviceIntent.setClassName(LLM_TESTER_PACKAGE, LLM_TESTER_SERVICE_CLASS);
        serviceIntent.setAction(LLM_TESTER_SERVICE_ACTION_START);
        serviceIntent.putExtra("port", resolveLlmTesterApiPort());
        try {
            return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? startForegroundService(serviceIntent)
                    : startService(serviceIntent)) != null;
        } catch (IllegalStateException e) {
            Log.w(TAG, "LLM Tester API start not allowed", e);
            return false;
        } catch (SecurityException e) {
            Log.w(TAG, "LLM Tester API start denied", e);
            return false;
        }
    }

    private int resolveLlmTesterApiPort() {
        String urlText = etOllamaUrl != null ? etOllamaUrl.getText().toString().trim() : ollamaBaseUrl;
        if (urlText.isEmpty()) {
            return DEFAULT_LLM_TESTER_API_PORT;
        }
        Uri uri = Uri.parse(urlText);
        int port = uri.getPort();
        return port > 0 ? port : DEFAULT_LLM_TESTER_API_PORT;
    }

    private void openLlmTesterPlayStore() {
        refreshModelsOnResume = true;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(LLM_TESTER_PLAY_STORE_URL));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Unable to open LLM Tester Play Store page", e);
            Toast.makeText(
                    this,
                    t("Unable to open Google Play for LLM Tester.",
                            "LLM Tester の Google Play を開けませんでした。"),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private String getApiUnavailableAssistantMessage() {
        return t(
                "I'm very sorry, but the LLM API is not available. Please use Open LLM Tester in Settings or start Ollama, then enable the API server. You can also check the details in Settings.",
                "大変申し訳ありませんが、LLM APIが有効になっておりません。設定画面の「LLM Tester を起動」またはOllamaを使って起動し、APIサーバを有効化して下さい。また、詳細は設定画面からご確認下さい。"
        );
    }

    private void appendApiUnavailableAssistantMessage() {
        switchActiveSpeaker(ChatSpeaker.BASE);
        appendAssistantMessage(ChatSpeaker.BASE, getApiUnavailableAssistantMessage());
    }

    private String getQuickStartMessage() {
        return t(
                "Before you begin:\n" +
                        "- In Settings, tap Launch LLM API above the API tile. If LLM Tester is not installed, the Play Store page opens.\n" +
                        "- Install and start LLM Tester with llama.cpp or Ollama, then enable the API server.\n" +
                        "- Check the Ollama URL tile in Settings. \"" + OLLAMA_STATUS_AVAILABLE_TEXT + "\" means /api/tags succeeded.\n\n" +
                        "Chat controls:\n" +
                        "- The Send button sends the message you typed.\n" +
                        "- If Voice Input is enabled, pressing Send with an empty input starts voice input.\n" +
                        "- While a response is being processed, the button changes to STOP so you can cancel it.\n" +
                        "- Note: On the first message, approximately 1GB of data may be downloaded for model downloads by the LLM API, which can take time.\n\n" +
                        "Settings:\n" +
                        "- Use Settings to configure connection settings and avatar images.\n" +
                        "- To reset the conversation, press Reset Conversation Log in Settings.",
                "はじめに:\n" +
                        "- 設定画面のAPI状態タイル上にある「LLM APIを起動」を押すと、LLM Testerを開きます。未インストール時はGoogle Playを開きます。\n" +
                        "- LLM Tester with llama.cppまたはOllamaをインストール/起動し、APIサーバを有効化してください。\n" +
                        "- 設定画面のOllama URL欄にあるステータスタイルで接続状態を確認できます。\n\n" +
                        "チャット操作:\n" +
                        "- Sendボタンで入力したメッセージを送信します。\n" +
                        "- Voice Inputが有効な場合、未入力のままSendを押すと音声入力を開始します。\n" +
                        "- 応答処理中はボタンがSTOPに変わり、タップで中断できます。\n" +
                        "- 注記: 初回のメッセージ送信時には約1GB程度の通信が発生します。これはLLM API側でモデルのダウンロードが行われることによるもので、反応まで時間がかかる場合があります。\n\n" +
                        "設定:\n" +
                        "- 設定画面で接続設定やアバター画像を設定できます。\n" +
                        "- 会話をリセットしたい場合は、設定画面のReset Conversation Logを押してください。"
        );
    }

    private void showQuickStartDialogIfNeeded() {
        if (suppressQuickStartPopup) return;

        int horizontalPadding = dpToPx(20);
        int topPadding = dpToPx(16);
        int bottomPadding = dpToPx(8);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);

        TextView messageView = new TextView(this);
        messageView.setText(getQuickStartMessage());
        messageView.setTextSize(15f);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(t("Do not show again", "次回から表示しない"));
        checkBox.setPadding(0, dpToPx(12), 0, 0);

        content.addView(messageView);
        content.addView(checkBox);

        ScrollView quickStartScrollView = new ScrollView(this);
        quickStartScrollView.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(t("Quick Start", "クイックスタート"))
                .setView(quickStartScrollView)
                .setPositiveButton(t("Close", "閉じる"), (dialog, which) -> {
                    if (checkBox.isChecked()) {
                        suppressQuickStartPopup = true;
                        saveSettings();
                    }
                })
                .show();
    }

    private void hideKeyboard(View target) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        android.os.IBinder token = (target != null && target.getWindowToken() != null)
                ? target.getWindowToken()
                : (getWindow() != null ? getWindow().getDecorView().getWindowToken() : null);
        if (imm != null && token != null) {
            imm.hideSoftInputFromWindow(token, 0);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect outRect = new Rect();
                focused.getGlobalVisibleRect(outRect);
                boolean touchedOutside = !outRect.contains((int) ev.getRawX(), (int) ev.getRawY());
                // deliver to children first so their click handlers still run
                boolean handled = super.dispatchTouchEvent(ev);
                if (touchedOutside) {
                    View newFocus = getCurrentFocus();
                    boolean movedToAnotherEditText = newFocus instanceof EditText && newFocus != focused;
                    if (!movedToAnotherEditText) {
                        focused.clearFocus();
                        focusMainLayer();
                        hideKeyboard(focused);
                    }
                }
                return handled;
            }
        }
        return super.dispatchTouchEvent(ev);
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
                .setTitle(t("Cancel Request", "リクエストのキャンセル"))
                .setMessage(t("Do you want to cancel the current request?", "現在のリクエストをキャンセルしますか？"))
                .setPositiveButton(t("Yes", "はい"), (dialog, which) -> cancelCurrentRequest())
                .setNegativeButton(t("No", "いいえ"), null)
                .show();
    }

    private void showResetConversationLogsConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(t("Reset Conversation Log", "会話ログをリセット"))
                .setMessage(t("Reset all conversation logs?", "会話ログをすべてリセットしますか？"))
                .setPositiveButton(t("Reset", "リセット"), (dialog, which) -> resetConversationLogs())
                .setNegativeButton(t("Cancel", "キャンセル"), null)
                .show();
    }

    private void resetConversationLogs() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        activeStreamingToken = streamingTokenCounter.incrementAndGet();
        resetStreamBuffer();
        hideThinkingIndicator();
        stopTts();
        isProcessing = false;
        setStreamingResponse(false, null);
        pendingAutoVoiceStart = false;
        pendingAutoChatterAfterTts = false;
        runOnUiThread(() -> {
            if (messageContainer != null) {
                messageContainer.removeAllViews();
            }
            currentStreamingBubble = null;
            currentThinkingBubble = null;
            initConversationHistory();
            updateSendButton();
            requestChatLayoutUpdate();
            Toast.makeText(this, t("Conversation log reset", "会話ログをリセットしました"), Toast.LENGTH_SHORT).show();
        });
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
        appendSystemMessage("System", t("Request cancelled", "リクエストがキャンセルされました"));
    }

    private void showInfoDialog(String title, String content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(content);
        builder.setPositiveButton(t("Close", "閉じる"), null);
        builder.setNeutralButton(t("Copy", "コピー"), (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(title, content);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, t("Copied to clipboard", "クリップボードにコピーしました"), Toast.LENGTH_SHORT).show();
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

    // ========== Window Insets (SafeArea + Keyboard) ==========

    @SuppressWarnings("deprecation")
    private void setupWindowInsets() {
        if (mainLayout == null) return;
        mainLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    top = sys.top;
                    bottom = Math.max(sys.bottom, ime.bottom);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                mainLayout.setPadding(0, top, 0, bottom);
                // Keep floating buttons below the status bar
                if (counterpartMiniContainer != null) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) counterpartMiniContainer.getLayoutParams();
                    lp.topMargin = top + dpToPx(8);
                    counterpartMiniContainer.setLayoutParams(lp);
                }
                if (btnSettings != null) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) btnSettings.getLayoutParams();
                    lp.topMargin = top + dpToPx(4);
                    btnSettings.setLayoutParams(lp);
                }
                return insets;
            }
        });
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
                int fallbackRes = resId;
                if (resId == R.drawable.c0 && activeSpeaker == ChatSpeaker.CHATTER) {
                    fallbackRes = R.drawable.c0c;
                }
                ivAvatarBackground.setImageResource(fallbackRes);
            }
        }
    }

    private void setAvatarFrame(int resId) {
        if (ivAvatar != null) {
            Bitmap custom = getAvatarFrameBitmapForSpeaker(activeSpeaker, resId);
            if (custom != null) {
                ivAvatar.setImageBitmap(custom);
            } else {
                int fallbackRes = resId;
                if (activeSpeaker == ChatSpeaker.CHATTER) {
                    if (resId == R.drawable.c1) fallbackRes = R.drawable.c1c;
                    else if (resId == R.drawable.c2) fallbackRes = R.drawable.c2c;
                    else if (resId == R.drawable.c3) fallbackRes = R.drawable.c3c;
                    else if (resId == R.drawable.c0) fallbackRes = R.drawable.c0c;
                }
                ivAvatar.setImageResource(fallbackRes);
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
            int fallbackRes = (speaker == ChatSpeaker.CHATTER) ? R.drawable.c0c : R.drawable.c0;
            ivCounterpartMiniBackground.setImageResource(fallbackRes);
        }
    }

    private void setCounterpartMiniAvatar(ChatSpeaker speaker) {
        if (ivCounterpartMiniAvatar == null) return;
        Bitmap custom = getAvatarFrameBitmapForSpeaker(speaker, R.drawable.c1);
        if (custom != null) {
            ivCounterpartMiniAvatar.setImageBitmap(custom);
        } else {
            int fallbackRes = (speaker == ChatSpeaker.CHATTER) ? R.drawable.c1c : R.drawable.c1;
            ivCounterpartMiniAvatar.setImageResource(fallbackRes);
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
            applySettingsJson(new JSONObject(sb.toString()));
        } catch (FileNotFoundException e) {
            // First run: use defaults
        } catch (Exception e) {
            Log.e(TAG, "loadSettings error", e);
        }
    }

    private void saveSettings() {
        try {
            JSONObject s = buildSettingsJson();
            FileOutputStream fos = openFileOutput(SETTINGS_FILE, MODE_PRIVATE);
            fos.write(s.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "saveSettings error", e);
        }
    }

    private JSONObject buildSettingsJson() throws Exception {
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
        s.put("userName", userName);
        s.put("historyLimit", historyLimit);
        s.put("autoChatterSeconds", autoChatterSeconds);
        s.put("appLanguage", appLanguage);
        s.put("webSearchEnabled", webSearchEnabled);
        s.put("debugEnabled", debugEnabled);
        s.put("webSearchUrl", webSearchUrl);
        s.put("webSearchApiKey", webSearchApiKey);
        s.put("webSearchModel", webSearchModel);
        s.put("suppressQuickStartPopup", suppressQuickStartPopup);
        s.put("avatarC0FileInfo", getAvatarFileInfo(avatarC0File));
        s.put("avatarC1FileInfo", getAvatarFileInfo(avatarC1File));
        s.put("avatarC2FileInfo", getAvatarFileInfo(avatarC2File));
        s.put("avatarC3FileInfo", getAvatarFileInfo(avatarC3File));
        s.put("avatarChatterC0FileInfo", getAvatarFileInfo(avatarChatterC0File));
        s.put("avatarChatterC1FileInfo", getAvatarFileInfo(avatarChatterC1File));
        s.put("avatarChatterC2FileInfo", getAvatarFileInfo(avatarChatterC2File));
        s.put("avatarChatterC3FileInfo", getAvatarFileInfo(avatarChatterC3File));
        return s;
    }

    private void applySettingsJson(JSONObject s) {
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
        userName = s.optString("userName", userName);
        historyLimit = s.optInt("historyLimit", historyLimit);
        autoChatterSeconds = s.optInt("autoChatterSeconds", autoChatterSeconds);
        appLanguage = s.optString("appLanguage", appLanguage);
        webSearchEnabled = s.optBoolean("webSearchEnabled", webSearchEnabled);
        debugEnabled = s.optBoolean("debugEnabled", debugEnabled);
        webSearchUrl = s.optString("webSearchUrl", webSearchUrl);
        webSearchApiKey = s.optString("webSearchApiKey", webSearchApiKey);
        webSearchModel = s.optString("webSearchModel", webSearchModel);
        suppressQuickStartPopup = s.optBoolean("suppressQuickStartPopup", suppressQuickStartPopup);
        if (speechLang.trim().isEmpty()) speechLang = defaultSpeechLangForCurrentLanguage();
        if (chatterSpeechLang.trim().isEmpty()) chatterSpeechLang = defaultSpeechLangForCurrentLanguage();
        if (baseName.trim().isEmpty()) baseName = defaultBaseNameForCurrentLanguage();
        if (chatterName.trim().isEmpty()) chatterName = defaultChatterNameForCurrentLanguage();

        if (webSearchModel.trim().isEmpty()) webSearchModel = "default";
        if (historyLimit < 0) historyLimit = 0;
        if (autoChatterSeconds < 0) autoChatterSeconds = 0;
    }

    private String getAvatarFileInfo(File file) {
        return file != null && file.exists() ? file.getName() : "";
    }

    private File getSettingsProfileDir(boolean createDir) {
        File dir = new File(getFilesDir(), SETTINGS_PROFILE_DIR);
        if (dir.exists()) return dir;
        if (!createDir) return null;
        if (dir.mkdirs()) return dir;
        Log.w(TAG, "Failed to create profile dir: " + dir.getAbsolutePath());
        return null;
    }

    private String normalizeProfileName(String profileName) {
        if (profileName == null) return "";
        String trimmed = profileName.trim();
        if (trimmed.isEmpty()) return "";
        String normalized = trimmed.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (normalized.startsWith(".")) {
            normalized = "_" + normalized.substring(1);
        }
        return normalized;
    }

    private File getSettingsProfileFile(String profileName, boolean createDir) {
        String normalized = normalizeProfileName(profileName);
        if (normalized.isEmpty()) return null;
        File dir = getSettingsProfileDir(createDir);
        if (dir == null) return null;
        return new File(dir, normalized + SETTINGS_PROFILE_SUFFIX);
    }

    private List<String> getSavedProfileNames() {
        List<String> names = new ArrayList<>();
        File dir = getSettingsProfileDir(false);
        if (dir == null || !dir.exists()) return names;
        File[] profileFiles = dir.listFiles((d, n) -> n.endsWith(SETTINGS_PROFILE_SUFFIX));
        if (profileFiles == null) return names;
        for (File profileFile : profileFiles) {
            String filename = profileFile.getName();
            String displayName = filename.substring(0, filename.length() - SETTINGS_PROFILE_SUFFIX.length());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String profileName = new JSONObject(sb.toString()).optString("profileName", "").trim();
                if (!profileName.isEmpty()) {
                    displayName = profileName;
                }
            } catch (Exception e) {
                Log.w(TAG, "getSavedProfileNames parse warning: " + filename, e);
            }
            names.add(displayName);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private String buildProfileAvatarFilename(String normalizedProfileName, String slotFileName) {
        return PROFILE_AVATAR_PREFIX + normalizedProfileName + "_" + slotFileName;
    }

    private String saveProfileAvatarFile(String normalizedProfileName, File source, String slotFileName) {
        if (source == null) return "";
        File dir = getExternalFilesDir(null);
        if (dir == null) return "";
        File backup = new File(dir, buildProfileAvatarFilename(normalizedProfileName, slotFileName));
        if (!source.exists()) {
            if (backup.exists()) backup.delete();
            return "";
        }
        if (!copyFile(source, backup)) {
            return "";
        }
        return backup.getName();
    }

    private void restoreProfileAvatarFile(JSONObject avatars, String key, File target) {
        if (avatars == null || target == null) return;
        String filename = avatars.optString(key, "");
        if (filename == null || filename.trim().isEmpty()) {
            if (target.exists()) target.delete();
            return;
        }
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File source = new File(dir, filename);
        if (!source.exists()) {
            if (target.exists()) target.delete();
            return;
        }
        if (!copyFile(source, target) && target.exists()) {
            target.delete();
        }
    }

    private void deleteProfileAvatarBackup(JSONObject avatars, String key) {
        if (avatars == null) return;
        String filename = avatars.optString(key, "");
        if (filename == null || filename.trim().isEmpty()) return;
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File backup = new File(dir, filename);
        if (backup.exists()) {
            backup.delete();
        }
    }

    private boolean saveSettingsProfile(String profileName) {
        try {
            String normalized = normalizeProfileName(profileName);
            if (normalized.isEmpty()) return false;
            File profileFile = getSettingsProfileFile(profileName, true);
            if (profileFile == null) return false;

            JSONObject root = new JSONObject();
            root.put("profileName", profileName.trim());
            JSONObject settingsJson = buildSettingsJson();
            settingsJson.remove("suppressQuickStartPopup");
            root.put("settings", settingsJson);

            JSONObject avatars = new JSONObject();
            avatars.put(PROFILE_AVATAR_C0_KEY, saveProfileAvatarFile(normalized, avatarC0File, AVATAR_C0_FILE));
            avatars.put(PROFILE_AVATAR_C1_KEY, saveProfileAvatarFile(normalized, avatarC1File, AVATAR_C1_FILE));
            avatars.put(PROFILE_AVATAR_C2_KEY, saveProfileAvatarFile(normalized, avatarC2File, AVATAR_C2_FILE));
            avatars.put(PROFILE_AVATAR_C3_KEY, saveProfileAvatarFile(normalized, avatarC3File, AVATAR_C3_FILE));
            avatars.put(PROFILE_AVATAR_CHATTER_C0_KEY, saveProfileAvatarFile(normalized, avatarChatterC0File, AVATAR_CHATTER_C0_FILE));
            avatars.put(PROFILE_AVATAR_CHATTER_C1_KEY, saveProfileAvatarFile(normalized, avatarChatterC1File, AVATAR_CHATTER_C1_FILE));
            avatars.put(PROFILE_AVATAR_CHATTER_C2_KEY, saveProfileAvatarFile(normalized, avatarChatterC2File, AVATAR_CHATTER_C2_FILE));
            avatars.put(PROFILE_AVATAR_CHATTER_C3_KEY, saveProfileAvatarFile(normalized, avatarChatterC3File, AVATAR_CHATTER_C3_FILE));
            root.put("avatarFiles", avatars);

            try (FileOutputStream fos = new FileOutputStream(profileFile)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveSettingsProfile error", e);
            return false;
        }
    }

    private boolean loadSettingsProfile(String profileName) {
        try {
            File profileFile = getSettingsProfileFile(profileName, false);
            if (profileFile == null || !profileFile.exists()) return false;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject settings = root.optJSONObject("settings");
            if (settings == null) {
                settings = root;
            }
            boolean currentSuppressQuickStart = suppressQuickStartPopup;
            applySettingsJson(settings);
            suppressQuickStartPopup = currentSuppressQuickStart;

            JSONObject avatars = root.optJSONObject("avatarFiles");
            if (avatars != null) {
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_C0_KEY, avatarC0File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_C1_KEY, avatarC1File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_C2_KEY, avatarC2File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_C3_KEY, avatarC3File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_CHATTER_C0_KEY, avatarChatterC0File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_CHATTER_C1_KEY, avatarChatterC1File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_CHATTER_C2_KEY, avatarChatterC2File);
                restoreProfileAvatarFile(avatars, PROFILE_AVATAR_CHATTER_C3_KEY, avatarChatterC3File);
            }
            reloadAvatarBitmaps();
            updateAllAvatarFilenameDisplays();
            setAvatarBackground(R.drawable.c0);
            if (avatarMode == AvatarMode.TALKING) {
                startTalkAnimation();
            } else {
                startIdleAnimation();
            }
            updateCounterpartMiniAvatar();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "loadSettingsProfile error", e);
            return false;
        }
    }

    private boolean deleteSettingsProfile(String profileName) {
        try {
            File profileFile = getSettingsProfileFile(profileName, false);
            if (profileFile == null || !profileFile.exists()) return false;

            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONObject avatars = root.optJSONObject("avatarFiles");
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_C0_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_C1_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_C2_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_C3_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_CHATTER_C0_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_CHATTER_C1_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_CHATTER_C2_KEY);
                deleteProfileAvatarBackup(avatars, PROFILE_AVATAR_CHATTER_C3_KEY);
            } catch (Exception e) {
                Log.w(TAG, "deleteSettingsProfile parse warning", e);
            }
            return profileFile.delete();
        } catch (Exception e) {
            Log.e(TAG, "deleteSettingsProfile error", e);
            return false;
        }
    }

    private boolean copyFile(File source, File target) {
        if (source == null || target == null || !source.exists()) return false;
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyFile error", e);
            return false;
        }
    }

    private void readSettingsFromUi() {
        ollamaBaseUrl = etOllamaUrl.getText().toString().trim();
        if (ollamaBaseUrl.isEmpty()) ollamaBaseUrl = "http://127.0.0.1:11434";
        streamingEnabled = switchStreaming.isChecked();
        ttsEnabled = switchTts.isChecked();
        voiceInputEnabled = switchVoiceInput.isChecked();
        autoChatterEnabled = radioModeChatter.isChecked();
        autoVoiceInputEnabled = switchAutoVoiceInput.isChecked();
        userName = etUserName.getText().toString().trim();
        baseName = etBaseName.getText().toString().trim();
        if (baseName.isEmpty()) baseName = defaultBaseNameForCurrentLanguage();
        speechLang = etSpeechLang.getText().toString().trim();
        if (speechLang.isEmpty()) speechLang = defaultSpeechLangForCurrentLanguage();
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
        if (chatterName.isEmpty()) chatterName = defaultChatterNameForCurrentLanguage();
        chatterSpeechLang = etChatterSpeechLang.getText().toString().trim();
        if (chatterSpeechLang.isEmpty()) chatterSpeechLang = defaultSpeechLangForCurrentLanguage();
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
            autoChatterSeconds = 10;
        }
        if (autoChatterSeconds < 0) autoChatterSeconds = 0;
        webSearchEnabled = switchWebSearch.isChecked();
        debugEnabled = switchDebug.isChecked();
        webSearchUrl = etWebSearchUrl.getText().toString().trim();
        if (webSearchUrl.isEmpty()) webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
        webSearchApiKey = etWebSearchApiKey.getText().toString().trim();
        webSearchModel = spinnerWebSearchModel.getSelectedItem() != null
                ? spinnerWebSearchModel.getSelectedItem().toString().trim() : "";
        if (webSearchModel.isEmpty()) webSearchModel = "default";
        if (spinnerLanguage != null) {
            appLanguage = spinnerLanguage.getSelectedItemPosition() == 1 ? "ja" : "en";
        }
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
        applyLanguageSpecificDefaults();
        etOllamaUrl.setText(ollamaBaseUrl);
        switchStreaming.setChecked(streamingEnabled);
        switchTts.setChecked(ttsEnabled);
        switchVoiceInput.setChecked(voiceInputEnabled);
        groupMode.check(autoChatterEnabled ? R.id.radioModeChatter : R.id.radioModeNormal);
        switchAutoVoiceInput.setChecked(autoVoiceInputEnabled);
        etUserName.setText(userName);
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
        int webSearchIdx = modelList.indexOf(webSearchModel);
        if (webSearchIdx >= 0) {
            spinnerWebSearchModel.setSelection(webSearchIdx);
        } else {
            spinnerWebSearchModel.setSelection(0);
        }
        updateChatterModeUi();
        if (spinnerLanguage != null) {
            spinnerLanguage.setSelection(languageSpinnerPositionForCurrentLanguage());
        }
        applyLanguageToUi();
        updateOllamaStatusTile(ollamaApiAvailable);
    }

    private void updateChatterModeUi() {
        if (autoChatterEnabled) {
            tabGroup.setVisibility(View.VISIBLE);
            updateSettingsTab();
            updateCounterpartMiniAvatar();
        } else {
            tabGroup.setVisibility(View.GONE);
            tabGroup.check(R.id.tabBase);
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
                sys.put("content", buildSystemPromptWithName(systemPromptText, baseName, autoChatterEnabled ? chatterName : ""));
                conversationHistory.add(sys);
                JSONObject chatterSys = new JSONObject();
                chatterSys.put("role", "system");
                chatterSys.put("content", buildSystemPromptWithName(chatterSystemPromptText, chatterName, autoChatterEnabled ? baseName : ""));
                chatterHistory.add(chatterSys);
            } catch (Exception e) {
                Log.e(TAG, "initHistory error", e);
            }
        }
    }

    private void reinitSystemPrompts() {
        synchronized (historyLock) {
            updateSystemPrompt(conversationHistory, buildSystemPromptWithName(systemPromptText, baseName, autoChatterEnabled ? chatterName : ""));
            updateSystemPrompt(chatterHistory, buildSystemPromptWithName(chatterSystemPromptText, chatterName, autoChatterEnabled ? baseName : ""));
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

    private String buildSystemPromptWithName(String basePrompt, String name, String counterpartName) {
        String trimmedPrompt = basePrompt == null ? "" : basePrompt.trim();
        String trimmedName = name == null ? "" : name.trim();
        String trimmedCounterpartName = counterpartName == null ? "" : counterpartName.trim();
        String trimmedUserName = userName == null ? "" : userName.trim();
        StringBuilder result = new StringBuilder();
        boolean isJapanese = "ja".equals(appLanguage);
        if (!trimmedName.isEmpty()) {
            if (isJapanese) {
                result.append("あなたは").append(trimmedName).append("という名前です。");
            } else {
                result.append("Your name is ").append(trimmedName).append(".");
            }
        }
        if (!trimmedPrompt.isEmpty()) {
            if (result.length() > 0) result.append("\n");
            result.append(trimmedPrompt);
        }
        if (!trimmedCounterpartName.isEmpty()) {
            if (result.length() > 0) result.append("\n");
            if (isJapanese) {
                result.append("相手の名前は").append(trimmedCounterpartName).append("です。");
            } else {
                result.append("The other speaker's name is ").append(trimmedCounterpartName).append(".");
            }
        }
        if (!trimmedUserName.isEmpty()) {
            if (result.length() > 0) result.append("\n");
            if (isJapanese) {
                result.append("ユーザの名前は").append(trimmedUserName).append("です。");
            } else {
                result.append("The user's name is ").append(trimmedUserName).append(".");
            }
        }
        return result.toString();
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
        try {
            String url = ollamaBaseUrl + "/api/tags";
            Request request = new Request.Builder().url(url).get().build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "fetchModels failed: " + e.getMessage(), e);
                    updateOllamaStatusTile(false);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            updateOllamaStatusTile(false);
                            return;
                        }

                        String body = response.body() != null ? response.body().string() : "";
                        JSONObject json = new JSONObject(body);
                        JSONArray models = json.getJSONArray("models");
                        List<String> names = new ArrayList<>();
                        names.add("default");
                        for (int i = 0; i < models.length(); i++) {
                            String name = models.getJSONObject(i).getString("name");
                            if (!names.contains(name)) names.add(name);
                        }
                        updateOllamaStatusTile(true);
                        runOnUiThread(() -> {
                            modelList.clear();
                            modelList.addAll(names);
                            modelAdapter.notifyDataSetChanged();
                            chatterModelAdapter.notifyDataSetChanged();
                            webSearchModelAdapter.notifyDataSetChanged();
                            int idx = modelList.indexOf(selectedModel);
                            if (idx >= 0) spinnerModel.setSelection(idx);
                            int chatterIdx = modelList.indexOf(chatterModel);
                            if (chatterIdx >= 0) spinnerChatterModel.setSelection(chatterIdx);
                            int webSearchIdx = modelList.indexOf(webSearchModel);
                            if (webSearchIdx >= 0) {
                                spinnerWebSearchModel.setSelection(webSearchIdx);
                            } else {
                                webSearchModel = "default";
                                spinnerWebSearchModel.setSelection(0);
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "fetchModels parse error", e);
                        updateOllamaStatusTile(false);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "fetchModels request build error", e);
            updateOllamaStatusTile(false);
        }
    }

    // ========== Chat Send ==========

    private void updateSendButton() {
        runOnUiThread(() -> {
            if (btnSend == null) return;
            btnSend.setEnabled(true);
            btnSend.setText(isProcessing ? "STOP" : t("Send", "送信"));
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
            String modelForWebSearch = webSearchModel != null ? webSearchModel.trim() : "";
            if (modelForWebSearch.isEmpty()) modelForWebSearch = "default";
            if (!modelList.contains(modelForWebSearch)) modelForWebSearch = "default";
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
            body.put("model", modelForWebSearch);
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
        if (isBraveWebSearchUrl(webSearchUrl)) {
            return callBraveWebSearchApi(keywords);
        }
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

    private boolean isBraveWebSearchUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("search.brave.com/res/v1/web/search")) return true;
        Uri uri = Uri.parse(url.trim());
        String host = uri.getHost();
        return host != null && host.toLowerCase(Locale.ROOT).contains("search.brave.com");
    }

    private String callBraveWebSearchApi(String keywords) {
        try {
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8")
                    + "&count=8";
            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
            }

            Request request = reqBuilder.build();
            if (debugEnabled) {
                appendDebug("Web API Request (Brave)", buildRequestDebugText(request, null));
            }
            Response response = client.newCall(request).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (debugEnabled) {
                appendDebug("Web API Response (Brave)", buildResponseDebugText(response, respBody));
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "callBraveWebSearchApi HTTP error: " + response.code());
                return null;
            }

            JSONObject json = new JSONObject(respBody);
            JSONObject web = json.optJSONObject("web");
            JSONArray results = web != null ? web.optJSONArray("results") : null;
            if (results == null || results.length() == 0) return null;

            StringBuilder extracted = new StringBuilder();
            int limit = Math.min(results.length(), 8);
            for (int i = 0; i < limit; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "").trim();
                String description = item.optString("description", "").trim();
                String resultUrl = item.optString("url", "").trim();
                JSONArray snippets = item.optJSONArray("extra_snippets");

                if (title.isEmpty() && description.isEmpty() && resultUrl.isEmpty()) continue;
                if (extracted.length() > 0) extracted.append("\n\n");
                extracted.append("[").append(i + 1).append("]");
                if (!title.isEmpty()) extracted.append(" ").append(title);
                if (!description.isEmpty()) extracted.append("\n").append(description);
                if (!resultUrl.isEmpty()) extracted.append("\n").append(resultUrl);
                if (snippets != null) {
                    for (int j = 0; j < snippets.length() && j < 2; j++) {
                        String snippet = snippets.optString(j, "").trim();
                        if (!snippet.isEmpty()) {
                            extracted.append("\n").append(snippet);
                        }
                    }
                }
            }

            String extractedText = extracted.toString().trim();
            if (extractedText.isEmpty()) return null;

            String result = "SEARCH_RESULTS:\n" + extractedText;
            Log.d(TAG, "Brave web search results: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "callBraveWebSearchApi error", e);
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
                updateOllamaStatusTile(false);
                appendApiUnavailableAssistantMessage();
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
                updateOllamaStatusTile(false);
                appendApiUnavailableAssistantMessage();
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
        String name = (userName == null || userName.trim().isEmpty()) ? defaultUserLabel() : userName.trim();
        appendMessage(name, text, true, true);
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
        appendStreamingMessage(chunk, token);
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
    protected void onResume() {
        super.onResume();
        if (refreshModelsOnResume) {
            refreshModelsOnResume = false;
            fetchModels();
        }
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
                Toast.makeText(this, t("Microphone permission required", "マイクの権限が必要です"), Toast.LENGTH_SHORT).show();
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
