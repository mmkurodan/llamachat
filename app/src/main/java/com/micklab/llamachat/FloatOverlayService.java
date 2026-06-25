package com.micklab.llamachat;

import com.micklab.llamachat.calendar.CalendarActionJson;
import com.micklab.llamachat.calendar.CalendarPromptFactory;
import com.micklab.llamachat.calendar.CalendarRepository;
import com.micklab.llamachat.calendar.CalendarResultForChat;
import com.micklab.llamachat.calendar.CalendarRetryHandler;
import com.micklab.llamachat.calendar.CalendarUiState;
import com.micklab.llamachat.calendar.CalendarViewModel;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.pm.ServiceInfo;
import androidx.core.app.ServiceCompat;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FloatOverlayService extends Service {

    public static final String ACTION_SHOW_OVERLAY = "com.micklab.llamachat.action.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.micklab.llamachat.action.HIDE_OVERLAY";
    public static final String ACTION_OVERLAY_SYNC_UPDATED = "com.micklab.llamachat.action.OVERLAY_SYNC_UPDATED";
    public static final String EXTRA_FLOAT_CALENDAR_USER_MSG = "float_calendar_user_msg";
    private static final String ACTION_NOTIFICATION_REPLY = "com.micklab.llamachat.action.NOTIFICATION_REPLY";
    private static final String KEY_NOTIFICATION_REPLY = "notification_reply_text";

    private static final String SETTINGS_FILE = "chat_settings.json";
    private static final String OVERLAY_SYNC_LOG_FILE = "overlay_sync_log.jsonl";
    private static final String FLOAT_DISPLAY_MODE_AVATAR = "avatar";
    private static final String FLOAT_DISPLAY_MODE_ICON = "icon";
    private static final String AVATAR_C0_FILE = "avatar_c0.jpg";
    private static final String AVATAR_C1_FILE = "avatar_c1.jpg";
    private static final String AVATAR_C2_FILE = "avatar_c2.jpg";
    private static final String AVATAR_C3_FILE = "avatar_c3.jpg";
    private static final String DEFAULT_BASE_NAME_JA = "藍";
    private static final String DEFAULT_BASE_NAME_EN = "Ai";
    private static final String DEFAULT_SYSTEM_PROMPT_JA = "あなたはユーザの若い女性秘書です";
    private static final String DEFAULT_SYSTEM_PROMPT_EN = "You are the user's young female secretary.";
    private static final String SEARCH_SYSTEM_PROMPT =
            "You are a search-augmented assistant. When the user provides SEARCH_RESULTS, "
                    + "you must read them and base your answer strictly on that information.";
    private static final String NOTIFICATION_CHANNEL_ID = "llamachat_float_overlay";
    private static final int NOTIFICATION_ID = 2001;
    private static final int AVATAR_TALK_FRAME_MS = 120;
    private static final int AVATAR_BLINK_MIN_MS = 3000;
    private static final int AVATAR_BLINK_MAX_MS = 7000;
    private static final int AVATAR_BLINK_DURATION_MS = 120;
    private static final int THINKING_ANIMATION_INTERVAL_MS = 360;
    private static final int OVERLAY_SCROLL_SETTLE_MS = 72;
    private static final int OVERLAY_SCROLL_MIN_INTERVAL_MS = 96;
    private static final float FLOAT_AVATAR_HEIGHT_RATIO = 0.33f;
    private static final int FLOAT_BUBBLE_MARGIN_DP = 12;
    private static final int MIN_RETAINED_HISTORY_MESSAGES = 24;
    private static final int MAX_SYNC_LOG_LINES = 80;
    private static final int MAX_NOTIFICATION_TEXT_LENGTH = 240;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int CHAT_NUM_CTX = 8192;
    private static final int CHAT_NUM_PREDICT = 2048;
    private static final String CHAT_KEEP_ALIVE = "30m";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object historyLock = new Object();
    private final List<JSONObject> conversationHistory = new ArrayList<>();
    private final ExpertSelector expertSelector = new ExpertSelector();
    private final WebSearchExpertHandler webSearchExpertHandler = new WebSearchExpertHandler();
    private final ChatFlowController chatFlowController =
            new ChatFlowController(expertSelector, webSearchExpertHandler);
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(3600, TimeUnit.SECONDS)
            .build();

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams layoutParams;
    private ImageView floatVisualBackground;
    private ImageView floatVisual;
    private LinearLayout bubblePanel;
    private ScrollView messageScrollView;
    private LinearLayout messageContainer;
    private EditText inputView;
    private Button sendButton;
    private Button hideButton;
    private TextView bubbleTitleView;
    private GestureDetector gestureDetector;
    private MarkdownRenderer markdownRenderer;
    private TextView currentResponseBubble;
    private Bitmap avatarC0Bitmap;
    private Bitmap avatarC1Bitmap;
    private Bitmap avatarC2Bitmap;
    private Bitmap avatarC3Bitmap;
    private Call currentCall;
    private final Random avatarRandom = new Random();
    private int talkFrameIndex = 0;
    private final int[] talkFrames = new int[]{R.drawable.c1, R.drawable.c3};

    private String ollamaBaseUrl = "http://127.0.0.1:11434";
    private String selectedModel = "default";
    private String systemPromptText = DEFAULT_SYSTEM_PROMPT_JA;
    private String baseName = DEFAULT_BASE_NAME_JA;
    private String userName = "";
    private String appLanguage = Locale.getDefault().getLanguage().startsWith("ja") ? "ja" : "en";
    private String floatDisplayMode = FLOAT_DISPLAY_MODE_AVATAR;
    private String webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
    private String webSearchApiKey = "";
    private String expertModel = "default";
    private String speechLang = "ja-JP";
    private int historyLimit = 10;
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private boolean streamingEnabled = true;
    private boolean ttsEnabled = false;
    private boolean voiceInputEnabled = true;
    private boolean autoVoiceInputEnabled = false;
    private boolean webSearchEnabled = false;
    private boolean calendarExpertModeEnabled = false;
    private String structuredOutputMode = "OFF";
    private CalendarViewModel calendarViewModel;
    private final CalendarExpertHandler calendarExpertHandler = new CalendarExpertHandler();
    private boolean isProcessing = false;
    private boolean isListening = false;
    private int voiceSessionToken = 0;
    private boolean isStreamingResponse = false;
    private int messageMaxHeightPx = 0;
    private int avatarPosX = 0;
    private int avatarPosY = 0;
    private int bubblePosX = 0;
    private int bubblePosY = 0;
    private int avatarWidthPx = 0;
    private int avatarHeightPx = 0;
    private int activeResponseToken = 0;
    private boolean foregroundStarted = false;
    private boolean overlayInitialized = false;
    private boolean openingAppFromOverlay = false;
    private String latestNotificationResponse = "";
    private int thinkingDotStep = 0;
    private String thinkingLabel = "";
    private int thinkingToken = 0;
    private boolean scrollToBottomScheduled = false;
    private boolean pendingScrollToBottom = false;
    private long lastScrollToBottomAtMs = 0L;
    private final Runnable thinkingAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentResponseBubble == null || thinkingToken != activeResponseToken) return;
            thinkingDotStep = (thinkingDotStep + 1) % 3;
            applyThinkingText();
            mainHandler.postDelayed(this, THINKING_ANIMATION_INTERVAL_MS);
        }
    };

    private float touchStartX;
    private float touchStartY;
    private int initialX;
    private int initialY;
    private boolean dragging = false;
    private boolean draggingBubble = false;
    private int touchSlop = 0;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final AtomicBoolean ttsSpeaking = new AtomicBoolean(false);
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            setAvatarFrame(R.drawable.c2);
            mainHandler.postDelayed(blinkResetRunnable, AVATAR_BLINK_DURATION_MS);
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
            mainHandler.postDelayed(this, AVATAR_TALK_FRAME_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogger.log(this, "=== FloatOverlayService onCreate START ===");
        try {
            DebugLogger.log(this, "Getting ViewConfiguration");
            touchSlop = ViewConfigurationHolder.get(this);
            DebugLogger.log(this, "touchSlop=" + touchSlop);
            
            DebugLogger.log(this, "Loading settings");
            loadSettings();
            DebugLogger.log(this, "Settings loaded. floatDisplayMode=" + floatDisplayMode);
            calendarViewModel = new CalendarViewModel(new CalendarRepository(this));
            
            DebugLogger.log(this, "Loading avatar bitmaps");
            loadAvatarBitmaps();
            DebugLogger.log(this, "Avatar bitmaps loaded");

            initTts();
            
            DebugLogger.log(this, "Initializing conversation history");
            initConversationHistory();
            DebugLogger.log(this, "Conversation history initialized");
            
            DebugLogger.log(this, "Preparing notification service");
            ensureForegroundNotification();
            
            DebugLogger.log(this, "=== FloatOverlayService onCreate SUCCESS ===");
        } catch (Exception e) {
            DebugLogger.log(this, "=== FloatOverlayService onCreate FAILED ===");
            DebugLogger.log(this, "Exception: " + e.getMessage());
            DebugLogger.log(this, "StackTrace: " + android.util.Log.getStackTraceString(e));
            android.util.Log.e("FloatOverlay", "Failed to initialize overlay service", e);
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogger.log(this, "onStartCommand called");
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SHOW_OVERLAY.equals(action)) {
            ensureOverlayInitialized();
        } else if (ACTION_HIDE_OVERLAY.equals(action)) {
            teardownOverlay();
        } else if (ACTION_NOTIFICATION_REPLY.equals(action)) {
            handleNotificationReply(intent);
        }
        ensureForegroundNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        cancelVoiceRecognition();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        teardownOverlay();
        stopAvatarAnimation();
        ttsReady = false;
        ttsSpeaking.set(false);
        recycleBitmap(avatarC0Bitmap);
        recycleBitmap(avatarC1Bitmap);
        recycleBitmap(avatarC2Bitmap);
        recycleBitmap(avatarC3Bitmap);
        avatarC0Bitmap = null;
        avatarC1Bitmap = null;
        avatarC2Bitmap = null;
        avatarC3Bitmap = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initOverlay() {
        DebugLogger.log(this, "initOverlay: Starting");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            DebugLogger.log(this, "ERROR: initOverlay: WindowManager is null!");
            throw new RuntimeException("WindowManager is not available");
        }
        DebugLogger.log(this, "initOverlay: WindowManager obtained");

        try {
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_float, null);
            if (overlayView == null) {
                DebugLogger.log(this, "ERROR: initOverlay: overlayView inflation failed");
                throw new RuntimeException("Failed to inflate overlay layout");
            }
            DebugLogger.log(this, "initOverlay: Overlay layout inflated");
            
            floatVisualBackground = overlayView.findViewById(R.id.floatVisualBackground);
            floatVisual = overlayView.findViewById(R.id.floatVisual);
            bubblePanel = overlayView.findViewById(R.id.bubblePanel);
            messageScrollView = overlayView.findViewById(R.id.svFloatMessages);
            messageContainer = overlayView.findViewById(R.id.floatMessageContainer);
            inputView = overlayView.findViewById(R.id.etFloatInput);
            sendButton = overlayView.findViewById(R.id.btnFloatSend);
            hideButton = overlayView.findViewById(R.id.btnHideBubble);
            bubbleTitleView = overlayView.findViewById(R.id.tvBubbleTitle);
            
            if (floatVisual == null || bubblePanel == null || messageScrollView == null || messageContainer == null || inputView == null) {
                DebugLogger.log(this, "ERROR: initOverlay: Some views are null after findViewById");
                throw new RuntimeException("Required views not found in layout");
            }
            DebugLogger.log(this, "initOverlay: All views found");
    
            layoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.x = 0;
            layoutParams.y = dpToPx(160);
            DebugLogger.log(this, "initOverlay: LayoutParams configured");
            avatarPosY = layoutParams.y;
    
            gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleBubble();
                    return true;
                }
    
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    openApp();
                    return true;
                }
            });
    
            floatVisual.setOnTouchListener((v, event) -> {
                GestureDetector detector = gestureDetector;
                if (detector == null) {
                    return true;
                }
                detector.onTouchEvent(event);
                if (openingAppFromOverlay || layoutParams == null || floatVisual == null) {
                    return true;
                }
                boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
                boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
                boolean directWindowDrag = iconMode || !bubbleVisible;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        if (directWindowDrag) {
                            initialX = layoutParams.x;
                            initialY = layoutParams.y;
                        } else {
                            initialX = avatarPosX;
                            initialY = avatarPosY;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            if (directWindowDrag) {
                                int nextX = initialX + (int) dx;
                                int nextY = initialY + (int) dy;
                                if (!iconMode) {
                                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                                    int maxX = Math.max(0, screenWidth - avatarWidthPx);
                                    int maxY = Math.max(0, screenHeight - avatarHeightPx);
                                    nextX = Math.max(0, Math.min(nextX, maxX));
                                    nextY = Math.max(0, Math.min(nextY, maxY));
                                }
                                layoutParams.x = nextX;
                                layoutParams.y = nextY;
                                avatarPosX = layoutParams.x;
                                avatarPosY = layoutParams.y;
                                updateOverlayLayout();
                            } else {
                                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                                int maxX = Math.max(0, screenWidth - avatarWidthPx);
                                int maxY = Math.max(0, screenHeight - avatarHeightPx);
                                avatarPosX = Math.max(0, Math.min(initialX + (int) dx, maxX));
                                avatarPosY = Math.max(0, Math.min(initialY + (int) dy, maxY));
                                applyAvatarPosition();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            });
    
            sendButton.setOnClickListener(v -> {
                if (isListening) {
                    cancelVoiceRecognition();
                    return;
                }
                if (isProcessing) {
                    cancelCurrentRequest();
                    return;
                }
                String text = inputView.getText().toString().trim();
                if (text.isEmpty()) {
                    if (voiceInputEnabled) {
                        startVoiceRecognition(true);
                        return;
                    }
                    Toast.makeText(this, t("Please enter a message", "メッセージを入力してください"), Toast.LENGTH_SHORT).show();
                    return;
                }
                submitUserMessage(text);
            });
            hideButton.setOnClickListener(v -> showBubble(false));

            attachBubbleDragListener(bubblePanel, true);
            if (bubblePanel != null && bubblePanel.getChildCount() > 0) {
                attachBubbleDragListener(bubblePanel.getChildAt(0), false);
            }

            updateFloatVisual();
            DebugLogger.log(this, "initOverlay: Float visual updated");
            updateBubbleHeader();
            updateSendButton();
            
            DebugLogger.log(this, "initOverlay: Adding real overlay view to window manager");
            windowManager.addView(overlayView, layoutParams);
            DebugLogger.log(this, "initOverlay: Real overlay view added successfully");
        } catch (Exception e) {
            android.util.Log.e("FloatOverlay", "Failed during overlay initialization", e);
            e.printStackTrace();
            throw new RuntimeException("Cannot initialize overlay", e);
        }
    }

    private void openApp() {
        if (openingAppFromOverlay) {
            return;
        }
        openingAppFromOverlay = true;
        mainHandler.post(() -> {
            try {
                startActivity(createMainActivityIntent());
                teardownOverlay();
                ensureForegroundNotification();
            } finally {
                openingAppFromOverlay = false;
            }
        });
    }

    private Intent createMainActivityIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("disableFloatOverlay", true);
        return intent;
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "LlamaChat Floating Overlay",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = createMainActivityIntent();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        String statusText = !TextUtils.isEmpty(latestNotificationResponse)
                ? latestNotificationResponse
                : (isProcessing
                ? t("Generating response...", "応答を生成中...")
                : t("Tap Send Message to use quick chat.", "メッセージ送信をタップすると簡易チャットができます。"));
        builder
                .setContentTitle("Dual AI Chat")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        if (!TextUtils.isEmpty(statusText)) {
            builder.setStyle(new Notification.BigTextStyle().bigText(statusText));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent replyIntent = new Intent(this, FloatOverlayService.class);
            replyIntent.setAction(ACTION_NOTIFICATION_REPLY);
            PendingIntent replyPendingIntent = PendingIntent.getService(
                    this,
                    1001,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_NOTIFICATION_REPLY)
                    .setLabel(t("Type a message", "メッセージを入力"))
                    .build();
            Notification.Action replyAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    t("Send Message", "メッセージ送信"),
                    replyPendingIntent
            ).addRemoteInput(remoteInput).build();
            builder.addAction(replyAction);
        }
        return builder.build();
    }

    private Notification buildFallbackNotification() {
        Intent openIntent = createMainActivityIntent();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Dual AI Chat")
                .setContentText(t("Tap Send Message to use quick chat.", "メッセージ送信をタップすると簡易チャットができます。"))
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void ensureForegroundNotification() {
        logNotificationState("ensureForegroundNotification:start");
        try {
            Notification notification = buildNotification();
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    DebugLogger.log(this, "notification: ServiceCompat.startForeground with DATA_SYNC type success");
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startForeground(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: startForeground (API 31+) success");
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: startForeground (legacy) success");
                }
                foregroundStarted = true;
            } else {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                    DebugLogger.log(this, "notification: manager.notify success");
                } else {
                    DebugLogger.log(this, "notification: manager is null on update");
                }
            }
        } catch (Exception e) {
            DebugLogger.log(this, "start/update foreground failed: " + e.getMessage());
            try {
                Notification fallback = buildFallbackNotification();
                if (!foregroundStarted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            ServiceCompat.startForeground(this, NOTIFICATION_ID, fallback,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                            DebugLogger.log(this, "notification: fallback ServiceCompat.startForeground with DATA_SYNC type success");
                        } catch (Exception typeError) {
                            DebugLogger.log(this, "fallback with DATA_SYNC type failed: " + typeError.getMessage() + ", retrying without type");
                            startForeground(NOTIFICATION_ID, fallback);
                            DebugLogger.log(this, "notification: fallback startForeground (no type) success");
                        }
                    } else {
                        startForeground(NOTIFICATION_ID, fallback);
                        DebugLogger.log(this, "notification: fallback startForeground (legacy) success");
                    }
                    foregroundStarted = true;
                } else {
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (manager != null) {
                        manager.notify(NOTIFICATION_ID, fallback);
                        DebugLogger.log(this, "notification: fallback manager.notify success");
                    } else {
                        DebugLogger.log(this, "notification: manager is null on fallback update");
                    }
                }
            } catch (Exception retryError) {
                DebugLogger.log(this, "fallback foreground failed: " + retryError.getMessage());
            }
        }
        logNotificationState("ensureForegroundNotification:end");
    }

    private void logNotificationState(String stage) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean enabled = manager != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || manager.areNotificationsEnabled());
            boolean postNotifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            DebugLogger.log(this, "notification state [" + stage + "] enabled=" + enabled
                    + ", postPermission=" + postNotifGranted
                    + ", foregroundStarted=" + foregroundStarted
                    + ", latestText=" + latestNotificationResponse);
        } catch (Exception e) {
            DebugLogger.log(this, "notification state log failed: " + e.getMessage());
        }
    }

    private void handleNotificationReply(Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence reply = results.getCharSequence(KEY_NOTIFICATION_REPLY);
        if (reply == null) return;
        String text = reply.toString().trim();
        if (text.isEmpty()) return;
        submitUserMessage(text);
        if (overlayInitialized && bubblePanel != null) {
            showBubble(true);
        }
    }

    private void toggleBubble() {
        if (bubblePanel == null) return;
        showBubble(bubblePanel.getVisibility() != View.VISIBLE);
    }

    private void showBubble(boolean show) {
        if (bubblePanel == null || layoutParams == null) return;
        bubblePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        layoutParams.flags = show
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        updateFloatVisual();
        if (show) {
            updateBubblePanelPosition();
            adjustMessageAreaHeight();
            scrollMessagesToBottom();
            inputView.requestFocus();
            mainHandler.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 80L);
        } else {
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && inputView != null) {
            imm.hideSoftInputFromWindow(inputView.getWindowToken(), 0);
        }
        if (inputView != null) {
            inputView.clearFocus();
        }
    }

    private void updateOverlayLayout() {
        if (windowManager == null || overlayView == null) return;
        try {
            windowManager.updateViewLayout(overlayView, layoutParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void updateFloatVisual() {
        if (floatVisual == null || layoutParams == null) return;
        boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
        boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
        boolean compactWindow = iconMode || !bubbleVisible;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        avatarHeightPx = Math.max(1, Math.round(screenH * FLOAT_AVATAR_HEIGHT_RATIO));
        avatarWidthPx = Math.max(
                1,
                Math.min(screenW, Math.round(avatarHeightPx * getAvatarAspectRatio()))
        );
        layoutParams.width = compactWindow ? WindowManager.LayoutParams.WRAP_CONTENT : screenW;
        layoutParams.height = compactWindow ? WindowManager.LayoutParams.WRAP_CONTENT : screenH;
        if (compactWindow) {
            layoutParams.x = avatarPosX;
            layoutParams.y = avatarPosY;
        } else {
            layoutParams.x = 0;
            layoutParams.y = 0;
        }
        FrameLayout.LayoutParams params = floatVisual.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) floatVisual.getLayoutParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (iconMode) {
            int sizePx = dpToPx(56);
            params.width = sizePx;
            params.height = sizePx;
            params.leftMargin = 0;
            params.topMargin = 0;
        } else {
            int maxX = Math.max(0, screenW - avatarWidthPx);
            int maxY = Math.max(0, screenH - avatarHeightPx);
            avatarPosX = Math.max(0, Math.min(avatarPosX, maxX));
            avatarPosY = Math.max(0, Math.min(avatarPosY, maxY));
            params.width = avatarWidthPx;
            params.height = avatarHeightPx;
            if (compactWindow) {
                params.leftMargin = 0;
                params.topMargin = 0;
            } else {
                params.leftMargin = avatarPosX;
                params.topMargin = avatarPosY;
            }
        }
        floatVisual.setLayoutParams(params);
        if (bubblePanel != null) {
            FrameLayout.LayoutParams bubbleParams = bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) bubblePanel.getLayoutParams()
                    : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleParams.width = Math.min(dpToPx(300), Math.max(dpToPx(200), screenW - dpToPx(FLOAT_BUBBLE_MARGIN_DP * 2)));
            bubbleParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bubblePanel.setLayoutParams(bubbleParams);
            updateBubblePanelPosition();
        }
        if (floatVisualBackground != null) {
            floatVisualBackground.setVisibility(View.GONE);
        }
        floatVisual.setBackgroundColor(0x00000000);
        floatVisual.setContentDescription(t(
                iconMode ? "Floating icon" : "Floating avatar",
                iconMode ? "フロートアイコン" : "フロートアバター"
        ));
        if (iconMode) {
            stopAvatarAnimation();
            floatVisual.setImageResource(R.drawable.icon);
        } else {
            setAvatarFrame(R.drawable.c1);
            updateAvatarAnimation();
        }
        updateOverlayLayout();
        mainHandler.post(this::adjustMessageAreaHeight);
    }

    private void updateAvatarAnimation() {
        if (FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode)) return;
        if (isProcessing || ttsSpeaking.get()) {
            startTalkAnimation();
        } else {
            startIdleAnimation();
        }
    }

    private void updateBubbleHeader() {
        if (bubbleTitleView != null) {
            bubbleTitleView.setText(TextUtils.isEmpty(baseName)
                    ? t("Quick Chat", "フロートチャット")
                    : baseName);
        }
    }

    private void updateSendButton() {
        mainHandler.post(() -> {
            if (sendButton != null) {
                sendButton.setText((isProcessing || isListening) ? "STOP" : t("Send", "送信"));
            }
        });
    }

    private void submitUserMessage(String userMessage) {
        int requestToken = ++activeResponseToken;
        if (inputView != null) {
            inputView.setText("");
        }
        clearOverlayMessages();
        appendUserMessageBubble(userMessage);
        appendSharedConversationLog("user", userMessage);
        addToHistory("user", userMessage);
        isProcessing = true;
        startThinkingIndicator(t("Thinking", "思考中"), requestToken);
        updateNotificationResponse(t("Generating response...", "応答を生成中..."));
        updateAvatarAnimation();
        currentResponseBubble = null;
        updateSendButton();
        ChatFlowController.ChatFlowResult flowResult = chatFlowController.route(
                userMessage,
                isWebSearchExpertAvailable(),
                calendarExpertModeEnabled
        );
        if (flowResult.getExpertType() == ExpertType.WEB) {
            performWebSearchFlow(userMessage, flowResult.getWebSearchQuery(), requestToken);
        } else if (isCalendarExpertType(flowResult.getExpertType())) {
            ExpertType calType = flowResult.getExpertType();
            if (calType == ExpertType.CALENDAR_UPDATE || calType == ExpertType.CALENDAR_DELETE) {
                launchMainActivityForCalendarConfirm(userMessage, requestToken);
            } else {
                performCalendarExpertFlow(userMessage, calType, requestToken);
            }
        } else {
            sendChat(null, false, requestToken);
        }
    }

    private boolean isWebSearchExpertAvailable() {
        return webSearchEnabled && !webSearchApiKey.isEmpty();
    }

    private void launchMainActivityForCalendarConfirm(String userMsg, int requestToken) {
        finishResponse(t("Opening app for calendar confirmation.", "カレンダーの確認のためアプリを開きます。"), requestToken);
        Intent intent = createMainActivityIntent();
        intent.putExtra(EXTRA_FLOAT_CALENDAR_USER_MSG, userMsg);
        startActivity(intent);
    }

    private boolean isCalendarExpertType(ExpertType expertType) {
        return expertType == ExpertType.CALENDAR_CREATE
                || expertType == ExpertType.CALENDAR_QUERY
                || expertType == ExpertType.CALENDAR_UPDATE
                || expertType == ExpertType.CALENDAR_DELETE;
    }

    private void performCalendarExpertFlow(String userMsg, ExpertType expertType, int requestToken) {
        new Thread(() -> {
            try {
                CalendarActionJson action = calendarExpertHandler.resolveAction(
                        userMsg, expertType, this::extractCalendarAction);
                if (action == null || !action.getAction().requiresCalendarOperation()) {
                    mainHandler.post(() -> sendChat(null, false, requestToken));
                    return;
                }
                CountDownLatch latch = new CountDownLatch(1);
                final CalendarUiState[] uiHolder = new CalendarUiState[1];
                final CalendarResultForChat[] resultHolder = new CalendarResultForChat[1];
                calendarViewModel.handleCalendarAction(action, (uiState, resultForChat) -> {
                    uiHolder[0] = uiState;
                    resultHolder[0] = resultForChat;
                    latch.countDown();
                });
                if (!latch.await(60, TimeUnit.SECONDS)) {
                    mainHandler.post(() -> finishResponse(
                            t("Calendar processing timed out.", "Calendar 処理がタイムアウトしました。"),
                            requestToken));
                    return;
                }
                CalendarResultForChat result = resultHolder[0];
                if (result == null) {
                    mainHandler.post(() -> sendChat(null, false, requestToken));
                    return;
                }
                mainHandler.post(() -> {
                    if (requestToken != activeResponseToken) return;
                    startThinkingIndicator(t("Calendar explaining", "Calendar説明生成中"), requestToken);
                    sendCalendarExplanation(userMsg, result, requestToken);
                });
            } catch (Exception e) {
                mainHandler.post(() -> finishResponse(
                        t("Calendar processing failed.", "Calendar 処理に失敗しました。"),
                        requestToken));
            }
        }).start();
    }

    private CalendarActionJson extractCalendarAction(String userMsg, ExpertType expertType) {
        try {
            String model = resolveExpertModel();
            String nowIso8601 = OffsetDateTime.now(ZoneId.systemDefault()).toString();
            CalendarRetryHandler retryHandler = new CalendarRetryHandler(
                    this,
                    prompt -> requestCalendarJudgeResponse(model, prompt, expertType)
            );
            return retryHandler.resolveAction(userMsg, expertType, nowIso8601);
        } catch (Exception e) {
            return CalendarActionJson.none(userMsg, e.getMessage());
        }
    }

    private String requestCalendarJudgeResponse(String model, String prompt,
                                                 ExpertType expertType) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        applyOllamaOptions(body);
        StructuredOutput.applyCalendar(body, StructuredOutput.Mode.fromString(structuredOutputMode), expertType);
        RequestBody requestBody = RequestBody.create(body.toString(), JSON_MEDIA);
        Request request = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(requestBody)
                .build();
        Response response = client.newCall(request).execute();
        String respBody = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) {
            throw new IllegalStateException("Calendar judge HTTP error: " + response.code());
        }
        return respBody;
    }

    private void sendCalendarExplanation(String userMsg, CalendarResultForChat resultForChat, int requestToken) {
        try {
            JSONArray messages = new JSONArray();
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", CalendarPromptFactory.buildExplainSystemPrompt(this));
            messages.put(systemMessage);
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", CalendarPromptFactory.buildExplainUserPrompt(this, userMsg, resultForChat, null));
            messages.put(userMessage);
            JSONObject body = new JSONObject();
            body.put("model", resolveExpertModel());
            body.put("messages", messages);
            body.put("stream", streamingEnabled);
            applyOllamaOptions(body);
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            if (streamingEnabled) {
                sendStreaming(request, requestToken);
            } else {
                sendNonStreaming(request, requestToken);
            }
        } catch (Exception e) {
            finishResponse(t("Calendar processing failed.", "Calendar 処理に失敗しました。"), requestToken);
        }
    }

    private void applyOllamaOptions(JSONObject body) throws org.json.JSONException {
        JSONObject options = new JSONObject();
        options.put("num_ctx", CHAT_NUM_CTX);
        options.put("num_predict", CHAT_NUM_PREDICT);
        body.put("options", options);
        body.put("keep_alive", CHAT_KEEP_ALIVE);
    }

    private void cancelCurrentRequest() {
        activeResponseToken++;
        stopThinkingIndicator(activeResponseToken);
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        isProcessing = false;
        cancelVoiceRecognition();
        updateAvatarAnimation();
        updateSendButton();
        currentResponseBubble = null;
        setResponseText(t("Request cancelled", "リクエストをキャンセルしました"), activeResponseToken);
        updateNotificationResponse(t("Request cancelled", "リクエストをキャンセルしました"));
    }

    private void sendChat(String transientUserMessage, boolean replaceLastUserMessage, int requestToken) {
        try {
            JSONArray messages = new JSONArray();
            boolean shouldReplaceLastUser = replaceLastUserMessage && transientUserMessage != null;
            synchronized (historyLock) {
                if (!conversationHistory.isEmpty()) {
                    JSONObject first = conversationHistory.get(0);
                    if ("system".equals(first.optString("role"))) {
                        String systemContent = first.optString("content", "");
                        String searchSystemPrompt = ExpertPromptStore.getSearchSystemPrompt(this);
                        if (webSearchEnabled && !searchSystemPrompt.isEmpty() && !systemContent.contains(searchSystemPrompt)) {
                            systemContent = systemContent + "\n" + searchSystemPrompt;
                        }
                        JSONObject sys = new JSONObject();
                        sys.put("role", "system");
                        sys.put("content", systemContent);
                        messages.put(sys);
                    }
                }
                int size = conversationHistory.size();
                int start = Math.max(1, size - Math.max(0, historyLimit));
                for (int i = start; i < size; i++) {
                    JSONObject msg = conversationHistory.get(i);
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
            body.put("model", selectedModel);
            body.put("messages", messages);
            body.put("stream", streamingEnabled);

            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();

            if (streamingEnabled) {
                sendStreaming(request, requestToken);
            } else {
                sendNonStreaming(request, requestToken);
            }
        } catch (Exception e) {
            isProcessing = false;
            updateSendButton();
            setResponseText(errorText(e.getMessage()), requestToken);
        }
    }

    private void performWebSearchFlow(String userMsg, String searchKeywords, int requestToken) {
        updateThinkingLabel(t("Web searching", "Web検索中"), requestToken);
        new Thread(() -> {
            String augmentedMessage = null;
            try {
                String keywords = searchKeywords == null ? null : searchKeywords.trim();
                if (!TextUtils.isEmpty(keywords)) {
                    String keywordText = keywords.length() > 80 ? keywords.substring(0, 80) + "..." : keywords;
                    updateThinkingLabel(t("Web searching: ", "Web検索中: ") + keywordText, requestToken);
                    String searchResults = callWebSearchApi(keywords);
                    if (!TextUtils.isEmpty(searchResults)) {
                        augmentedMessage = buildSearchAugmentedUserMessage(userMsg, searchResults);
                    }
                }
            } catch (Exception e) {
                DebugLogger.log(this, "performWebSearchFlow error: " + e.getMessage());
            }
            String finalAugmented = augmentedMessage;
            mainHandler.post(() -> sendChat(finalAugmented, finalAugmented != null, requestToken));
        }).start();
    }

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
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
                reqBuilder.addHeader("Authorization", "Bearer " + webSearchApiKey);
                reqBuilder.addHeader("X-Api-Key", webSearchApiKey);
            }
            Response response = client.newCall(reqBuilder.build()).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) return null;
            JSONObject json = new JSONObject(respBody);
            StringBuilder extracted = new StringBuilder();
            extractAllStringValues(json, extracted, 0);
            String extractedText = extracted.toString().trim();
            if (extractedText.isEmpty()) return null;
            return "SEARCH_RESULTS:\n" + extractedText;
        } catch (Exception e) {
            DebugLogger.log(this, "callWebSearchApi error: " + e.getMessage());
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
            String url = webSearchUrl + "?q=" + java.net.URLEncoder.encode(keywords, "UTF-8") + "&count=8";
            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "ja-JP");
            if (!webSearchApiKey.isEmpty()) {
                reqBuilder.addHeader("X-Subscription-Token", webSearchApiKey);
            }
            Response response = client.newCall(reqBuilder.build()).execute();
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) return null;
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
                        if (!snippet.isEmpty()) extracted.append("\n").append(snippet);
                    }
                }
            }
            String extractedText = extracted.toString().trim();
            if (extractedText.isEmpty()) return null;
            return "SEARCH_RESULTS:\n" + extractedText;
        } catch (Exception e) {
            DebugLogger.log(this, "callBraveWebSearchApi error: " + e.getMessage());
            return null;
        }
    }

    private void extractAllStringValues(Object obj, StringBuilder sb, int depth) {
        if (depth > 10) return;
        try {
            if (obj instanceof JSONObject) {
                JSONObject json = (JSONObject) obj;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    extractAllStringValues(json.get(key), sb, depth + 1);
                }
            } else if (obj instanceof JSONArray) {
                JSONArray arr = (JSONArray) obj;
                for (int i = 0; i < arr.length() && i < 10; i++) {
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
            DebugLogger.log(this, "extractAllStringValues error: " + e.getMessage());
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
        DebugLogger.log(this, "startVoiceRecognition: voiceInputEnabled=" + voiceInputEnabled
                + " isProcessing=" + isProcessing + " isListening=" + isListening
                + " sessionToken=" + voiceSessionToken);
        if (!voiceInputEnabled || isProcessing || isListening) {
            DebugLogger.log(this, "startVoiceRecognition: blocked");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            DebugLogger.log(this, "startVoiceRecognition: recognition unavailable");
            Toast.makeText(this, t("Voice recognition unavailable", "音声認識が利用できません"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log(this, "startVoiceRecognition: RECORD_AUDIO permission missing");
            Toast.makeText(this, t("Microphone permission is required", "マイク権限が必要です"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            DebugLogger.log(this, "startVoiceRecognition: new recognizer created");
        } else {
            DebugLogger.log(this, "startVoiceRecognition: reusing existing recognizer");
        }
        final int sessionToken = ++voiceSessionToken;
        isListening = true;
        updateSendButton();
        DebugLogger.log(this, "startVoiceRecognition: started session=" + sessionToken);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                DebugLogger.log(FloatOverlayService.this, "voice onReadyForSpeech session=" + sessionToken);
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                DebugLogger.log(FloatOverlayService.this, "voice onEndOfSpeech session=" + sessionToken);
            }
            @Override
            public void onError(int error) {
                DebugLogger.log(FloatOverlayService.this, "voice onError error=" + error
                        + " session=" + sessionToken + " current=" + voiceSessionToken);
                stopVoiceRecognition();
                if (sessionToken != voiceSessionToken) return;
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(FloatOverlayService.this, t("Voice recognition failed", "音声認識に失敗しました"), Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onResults(Bundle results) {
                DebugLogger.log(FloatOverlayService.this, "voice onResults session=" + sessionToken
                        + " current=" + voiceSessionToken);
                stopVoiceRecognition();
                if (sessionToken != voiceSessionToken) {
                    DebugLogger.log(FloatOverlayService.this, "voice onResults: stale session, dropping");
                    return;
                }
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : "";
                DebugLogger.log(FloatOverlayService.this, "voice onResults: best=\"" + best + "\"");
                if (best.isEmpty()) return;
                if (bubblePanel != null && bubblePanel.getVisibility() != View.VISIBLE) {
                    showBubble(true);
                }
                submitUserMessage(best);
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        speechRecognizer.startListening(buildRecognizerIntent(preferOffline));
    }

    private void stopVoiceRecognition() {
        isListening = false;
        updateSendButton();
    }

    private void cancelVoiceRecognition() {
        voiceSessionToken++;
        isListening = false;
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        updateSendButton();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                applyTtsSettings();
            }
        });
    }

    private void applyTtsSettings() {
        if (!ttsReady || tts == null) return;
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

    private void speakText(String text) {
        if (!ttsEnabled || !ttsReady || tts == null || TextUtils.isEmpty(text)) return;
        String source = text.replaceAll("<think>[\\s\\S]*?</think>", " ");
        String clean = source
                .replaceAll("[\\n\\r\\t]", "、")
                .replaceAll("[!@#$%^&*()_+={}\\[\\]|\\\\:;<>.?/]", "、")
                .replaceAll("[,\u201c\u201d]", " ")
                .replaceAll("、+", "、")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return;
        applyTtsSettings();
        ttsSpeaking.set(true);
        updateAvatarAnimation();
        int result = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "float_utt_" + System.currentTimeMillis());
        if (result != TextToSpeech.SUCCESS) {
            ttsSpeaking.set(false);
            updateAvatarAnimation();
        } else {
            long estimateMs = Math.max(500L, Math.min(12000L, clean.length() * 60L));
            mainHandler.postDelayed(() -> {
                ttsSpeaking.set(false);
                updateAvatarAnimation();
            }, estimateMs);
        }
    }

    private void sendStreaming(Request request, int requestToken) {
        Call call = client.newCall(request);
        currentCall = call;
        isStreamingResponse = true;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                isStreamingResponse = false;
                finishResponse(errorText(getApiUnavailableMessage()), requestToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    currentCall = null;
                    isStreamingResponse = false;
                    finishResponse(errorText("HTTP error: " + response.code()), requestToken);
                    return;
                }

                String visibleResponse = "";
                try (InputStream is = response.body() != null ? response.body().byteStream() : null;
                     BufferedReader reader = is != null
                             ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                             : null) {
                    if (reader == null) {
                        isStreamingResponse = false;
                        finishResponse(t("(No response)", "（応答なし）"), requestToken);
                        return;
                    }

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        JSONObject json = new JSONObject(line);
                        if (json.has("message")) {
                            visibleResponse += json.getJSONObject("message").optString("content", "");
                            setResponseText(visibleResponse, requestToken);
                            updateNotificationResponse(visibleResponse);
                        }
                        if (json.optBoolean("done", false)) {
                            break;
                        }
                    }
                    finishResponse(visibleResponse.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : visibleResponse, requestToken);
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        isStreamingResponse = false;
                        finishResponse(errorText("Stream error: " + e.getMessage()), requestToken);
                    }
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void sendNonStreaming(Request request, int requestToken) {
        Call call = client.newCall(request);
        currentCall = call;
        isStreamingResponse = false;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                finishResponse(errorText(getApiUnavailableMessage()), requestToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        finishResponse(errorText("HTTP error: " + response.code()), requestToken);
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    String text = json.optJSONObject("message") != null
                            ? json.optJSONObject("message").optString("content", "")
                            : "";
                    finishResponse(text.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : text, requestToken);
                } catch (Exception e) {
                    finishResponse(errorText(e.getMessage()), requestToken);
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void finishResponse(String responseText, int requestToken) {
        if (requestToken != activeResponseToken) return;
        stopThinkingIndicator(requestToken);
        boolean hasAssistantContent = !TextUtils.isEmpty(responseText) && !responseText.startsWith(t("Error: ", "エラー: "));
        if (hasAssistantContent) {
            addToHistory("assistant", responseText);
            appendSharedConversationLog("assistant", responseText);
        }
        isProcessing = false;
        updateAvatarAnimation();
        updateSendButton();
        setResponseText(responseText, requestToken);
        isStreamingResponse = false;
        updateNotificationResponse(responseText);
        if (hasAssistantContent) {
            speakText(responseText);
            if (autoVoiceInputEnabled) {
                mainHandler.postDelayed(() -> startVoiceRecognition(true), 200L);
            }
        }
    }

    private String errorText(String message) {
        String detail = TextUtils.isEmpty(message) ? t("Unknown error", "不明なエラー") : message;
        return t("Error: ", "エラー: ") + detail;
    }

    private void setResponseText(String text, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            if (TextUtils.isEmpty(text)) return;
            stopThinkingIndicator(requestToken);
            if (currentResponseBubble == null) {
                currentResponseBubble = appendMessageBubble(baseName, text, false);
            } else {
                renderMessageBubble(currentResponseBubble, baseName, text);
                adjustMessageAreaHeight();
                scrollMessagesToBottom();
            }
            latestNotificationResponse = compactNotificationText(text);
            ensureForegroundNotification();
        });
    }

    private void startThinkingIndicator(String label, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            thinkingToken = requestToken;
            thinkingDotStep = 0;
            thinkingLabel = TextUtils.isEmpty(label) ? t("Thinking", "思考中") : label;
            if (currentResponseBubble == null) {
                currentResponseBubble = appendAssistantMessageBubble("");
            }
            applyThinkingText();
            mainHandler.removeCallbacks(thinkingAnimationRunnable);
            mainHandler.postDelayed(thinkingAnimationRunnable, THINKING_ANIMATION_INTERVAL_MS);
            ensureForegroundNotification();
        });
    }

    private void updateThinkingLabel(String label, int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            thinkingToken = requestToken;
            thinkingLabel = TextUtils.isEmpty(label) ? t("Thinking", "思考中") : label;
            applyThinkingText();
            ensureForegroundNotification();
        });
    }

    private void stopThinkingIndicator(int requestToken) {
        mainHandler.post(() -> {
            if (requestToken != activeResponseToken) return;
            mainHandler.removeCallbacks(thinkingAnimationRunnable);
            thinkingLabel = "";
            thinkingToken = 0;
            thinkingDotStep = 0;
        });
    }

    private void applyThinkingText() {
        if (currentResponseBubble == null || TextUtils.isEmpty(thinkingLabel)) return;
        int dots = (thinkingDotStep % 3) + 1;
        StringBuilder body = new StringBuilder(thinkingLabel);
        for (int i = 0; i < dots; i++) {
            body.append('.');
        }
        renderPlainMessageBubble(currentResponseBubble, baseName, body.toString());
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
        latestNotificationResponse = compactNotificationText(body.toString());
        ensureForegroundNotification();
    }

    private void updateNotificationResponse(String responseText) {
        mainHandler.post(() -> {
            latestNotificationResponse = compactNotificationText(responseText);
            ensureForegroundNotification();
        });
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void ensureOverlayInitialized() {
        loadSettings();
        if (overlayInitialized) {
            // 再表示時に残留した処理・認識状態をリセット
            if (isProcessing && currentCall == null) {
                isProcessing = false;
            }
            if (isListening) {
                cancelVoiceRecognition();
            }
            updateBubbleHeader();
            updateSendButton();
            updateFloatVisual();
            return;
        }
        if (!hasOverlayPermission()) {
            DebugLogger.log(this, "Overlay initialization skipped: permission missing");
            return;
        }
        initOverlay();
        overlayInitialized = true;
        DebugLogger.log(this, "Overlay initialized successfully");
    }

    private void teardownOverlay() {
        hideKeyboard();
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                DebugLogger.log(this, "Overlay teardown failed: " + e.getMessage());
            }
        }
        overlayView = null;
        layoutParams = null;
        floatVisualBackground = null;
        floatVisual = null;
        bubblePanel = null;
        messageScrollView = null;
        messageContainer = null;
        inputView = null;
        sendButton = null;
        hideButton = null;
        bubbleTitleView = null;
        gestureDetector = null;
        currentResponseBubble = null;
        overlayInitialized = false;
        openingAppFromOverlay = false;
    }

    private void clearOverlayMessages() {
        if (messageContainer == null) return;
        messageContainer.removeAllViews();
        currentResponseBubble = null;
        adjustMessageAreaHeight();
    }

    private void appendUserMessageBubble(String text) {
        String name = TextUtils.isEmpty(userName) ? t("User", "ユーザ") : userName;
        appendMessageBubble(name, text, true);
    }

    private TextView appendAssistantMessageBubble(String text) {
        return appendMessageBubble(baseName, text, false);
    }

    private void appendSystemMessage(String text) {
        appendMessageBubble("System", text, false);
    }

    private TextView appendMessageBubble(String name, String text, boolean isUserSide) {
        if (messageContainer == null) return null;
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
        bubble.setTextSize(14);
        bubble.setTextColor(0xFF000000);
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.7f);
        bubble.setMaxWidth(maxWidth);
        getMarkdownRenderer().prepare(bubble);
        renderMessageBubble(bubble, name, text);

        row.addView(bubble);
        messageContainer.addView(row);
        trimVisibleOverlayRows();
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
        return bubble;
    }

    private MarkdownRenderer getMarkdownRenderer() {
        if (markdownRenderer == null) {
            markdownRenderer = MarkdownRenderer.create(this);
        }
        return markdownRenderer;
    }

    private void renderMessageBubble(TextView bubble, String name, String text) {
        getMarkdownRenderer().render(bubble, name, text);
    }

    private void renderPlainMessageBubble(TextView bubble, String name, String text) {
        getMarkdownRenderer().renderPlain(bubble, name, text);
    }

    private void adjustMessageAreaHeight() {
        if (messageScrollView == null || messageContainer == null) return;
        if (messageMaxHeightPx <= 0) {
            messageMaxHeightPx = Math.max(1, getResources().getDisplayMetrics().heightPixels / 4);
        }
        messageContainer.post(() -> {
            ViewGroup.LayoutParams lp = messageScrollView.getLayoutParams();
            if (lp == null) return;
            int desired = messageContainer.getHeight() + messageScrollView.getPaddingTop() + messageScrollView.getPaddingBottom();
            int clamped = Math.min(Math.max(desired, dpToPx(56)), messageMaxHeightPx);
            if (lp.height != clamped) {
                lp.height = clamped;
                messageScrollView.setLayoutParams(lp);
            }
            updateBubblePanelPosition();
        });
    }

    private void scrollMessagesToBottom() {
        if (messageScrollView == null) return;
        pendingScrollToBottom = true;
        if (scrollToBottomScheduled) return;
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastScrollToBottomAtMs;
        long delay = Math.max(OVERLAY_SCROLL_SETTLE_MS, OVERLAY_SCROLL_MIN_INTERVAL_MS - Math.max(0L, elapsed));
        scrollToBottomScheduled = true;
        messageScrollView.postDelayed(() -> {
            scrollToBottomScheduled = false;
            if (messageScrollView == null || !pendingScrollToBottom) return;
            pendingScrollToBottom = false;
            View child = messageScrollView.getChildCount() > 0 ? messageScrollView.getChildAt(0) : null;
            if (child == null) return;
            int targetY = Math.max(0, child.getBottom() - messageScrollView.getHeight());
            messageScrollView.scrollTo(0, targetY);
            lastScrollToBottomAtMs = SystemClock.uptimeMillis();
        }, delay);
    }

    private void appendSharedConversationLog(String role, String content) {
        if (TextUtils.isEmpty(role) || TextUtils.isEmpty(content)) return;
        try {
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", content);
            try (FileOutputStream fos = openFileOutput(OVERLAY_SYNC_LOG_FILE, MODE_APPEND)) {
                fos.write((item.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            trimOverlaySyncLog();
            Intent syncIntent = new Intent(ACTION_OVERLAY_SYNC_UPDATED);
            syncIntent.setPackage(getPackageName());
            sendBroadcast(syncIntent);
        } catch (Exception e) {
            DebugLogger.log(this, "appendSharedConversationLog failed: " + e.getMessage());
        }
    }

    private void initConversationHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            addToHistoryLocked("system", buildSystemPromptWithName(systemPromptText, baseName));
        }
    }

    private void addToHistory(String role, String content) {
        synchronized (historyLock) {
            addToHistoryLocked(role, content);
        }
    }

    private void addToHistoryLocked(String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            conversationHistory.add(msg);
            trimConversationHistoryLocked();
        } catch (Exception ignored) {
        }
    }

    private void trimConversationHistoryLocked() {
        int keepStart = !conversationHistory.isEmpty()
                && "system".equals(conversationHistory.get(0).optString("role"))
                ? 1 : 0;
        int maxMessages = Math.max(MIN_RETAINED_HISTORY_MESSAGES, historyLimit * 4);
        while (conversationHistory.size() - keepStart > maxMessages) {
            conversationHistory.remove(keepStart);
        }
    }

    private void trimVisibleOverlayRows() {
        if (messageContainer == null) {
            return;
        }
        int maxRows = Math.max(24, historyLimit * 4);
        while (messageContainer.getChildCount() > maxRows) {
            messageContainer.removeViewAt(0);
        }
    }

    private void trimOverlaySyncLog() {
        try {
            List<String> retained = new ArrayList<>();
            try (FileInputStream fis = openFileInput(OVERLAY_SYNC_LOG_FILE);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    retained.add(trimmed);
                    if (retained.size() > MAX_SYNC_LOG_LINES) {
                        retained.remove(0);
                    }
                }
            }
            try (FileOutputStream fos = openFileOutput(OVERLAY_SYNC_LOG_FILE, MODE_PRIVATE)) {
                for (String line : retained) {
                    fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
                fos.flush();
            }
        } catch (Exception e) {
            DebugLogger.log(this, "trimOverlaySyncLog failed: " + e.getMessage());
        }
    }

    private String compactNotificationText(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= MAX_NOTIFICATION_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_NOTIFICATION_TEXT_LENGTH - 3) + "...";
    }

    private String buildSystemPromptWithName(String basePrompt, String name) {
        String trimmedPrompt = basePrompt == null ? "" : basePrompt.trim();
        String trimmedName = name == null ? "" : name.trim();
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

    private void loadSettings() {
        try (FileInputStream fis = openFileInput(SETTINGS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            applySettingsJson(new JSONObject(sb.toString()));
        } catch (FileNotFoundException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void saveSettings() {
        try {
            JSONObject settings = new JSONObject();
            try (FileInputStream fis = openFileInput(SETTINGS_FILE);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                if (sb.length() > 0) {
                    settings = new JSONObject(sb.toString());
                }
            } catch (FileNotFoundException ignored) {
            }
            settings.put("bubblePosX", bubblePosX);
            settings.put("bubblePosY", bubblePosY);
            settings.put("floatDisplayMode", normalizeFloatDisplayMode(floatDisplayMode));
            try (FileOutputStream fos = openFileOutput(SETTINGS_FILE, MODE_PRIVATE)) {
                fos.write(settings.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            DebugLogger.log(this, "saveSettings failed: " + e.getMessage());
        }
    }

    private void applySettingsJson(JSONObject settings) {
        appLanguage = settings.optString("appLanguage", appLanguage);
        ollamaBaseUrl = settings.optString("ollamaBaseUrl", ollamaBaseUrl);
        selectedModel = settings.optString("selectedModel", selectedModel);
        streamingEnabled = settings.optBoolean("streamingEnabled", streamingEnabled);
        ttsEnabled = settings.optBoolean("ttsEnabled", ttsEnabled);
        voiceInputEnabled = settings.optBoolean("voiceInputEnabled", voiceInputEnabled);
        autoVoiceInputEnabled = settings.optBoolean("autoVoiceInputEnabled", autoVoiceInputEnabled);
        speechLang = settings.optString("speechLang", speechLang);
        speechRate = (float) settings.optDouble("speechRate", speechRate);
        speechPitch = (float) settings.optDouble("speechPitch", speechPitch);
        webSearchEnabled = settings.optBoolean("webSearchEnabled", webSearchEnabled);
        webSearchUrl = settings.optString("webSearchUrl", webSearchUrl);
        webSearchApiKey = settings.optString("webSearchApiKey", webSearchApiKey);
        expertModel = settings.optString("expertModel", settings.optString("webSearchModel", expertModel));
        calendarExpertModeEnabled = settings.optBoolean("calendarExpertModeEnabled", calendarExpertModeEnabled);
        structuredOutputMode = StructuredOutput.Mode.fromString(
                settings.optString("structuredOutputMode", structuredOutputMode)).name();
        systemPromptText = settings.optString("systemPrompt", systemPromptText);
        baseName = settings.optString("baseName", baseName);
        userName = settings.optString("userName", userName);
        historyLimit = Math.max(0, settings.optInt("historyLimit", historyLimit));
        floatDisplayMode = normalizeFloatDisplayMode(settings.optString("floatDisplayMode", floatDisplayMode));
        bubblePosX = Math.max(0, settings.optInt("bubblePosX", bubblePosX));
        bubblePosY = Math.max(0, settings.optInt("bubblePosY", bubblePosY));
        if (TextUtils.isEmpty(baseName)) {
            baseName = defaultBaseName();
        }
        if (TextUtils.isEmpty(systemPromptText)) {
            systemPromptText = defaultSystemPrompt();
        }
        if (TextUtils.isEmpty(selectedModel)) {
            selectedModel = "default";
        }
        if (TextUtils.isEmpty(expertModel)) {
            expertModel = "default";
        }
        if (TextUtils.isEmpty(webSearchUrl)) {
            webSearchUrl = "https://api.search.brave.com/res/v1/web/search";
        }
        if (TextUtils.isEmpty(speechLang)) {
            speechLang = "ja-JP";
        }
        applyTtsSettings();
    }

    private String resolveExpertModel() {
        String model = expertModel == null ? "" : expertModel.trim();
        if (model.isEmpty()) {
            model = "default";
        }
        return model;
    }

    private String normalizeFloatDisplayMode(String value) {
        return FLOAT_DISPLAY_MODE_ICON.equals(value) ? FLOAT_DISPLAY_MODE_ICON : FLOAT_DISPLAY_MODE_AVATAR;
    }

    private void loadAvatarBitmaps() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        avatarC0Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C0_FILE));
        avatarC1Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C1_FILE));
        avatarC2Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C2_FILE));
        avatarC3Bitmap = loadAvatarBitmap(new File(dir, AVATAR_C3_FILE));
    }

    private Bitmap loadAvatarBitmap(File file) {
        if (file == null || !file.exists()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void setAvatarBackground(int resId) {
        if (floatVisualBackground == null) return;
        Bitmap custom = resId == R.drawable.c0 ? avatarC0Bitmap : null;
        if (custom != null) {
            floatVisualBackground.setImageBitmap(custom);
        } else {
            floatVisualBackground.setImageResource(resId);
        }
    }

    private void setAvatarFrame(int resId) {
        if (floatVisual == null) return;
        Bitmap custom = null;
        if (resId == R.drawable.c1) custom = avatarC1Bitmap;
        else if (resId == R.drawable.c2) custom = avatarC2Bitmap;
        else if (resId == R.drawable.c3) custom = avatarC3Bitmap;
        if (custom != null) {
            floatVisual.setImageBitmap(custom);
        } else {
            floatVisual.setImageResource(resId);
        }
    }

    private float getAvatarAspectRatio() {
        if (avatarC1Bitmap != null && !avatarC1Bitmap.isRecycled()
                && avatarC1Bitmap.getWidth() > 0 && avatarC1Bitmap.getHeight() > 0) {
            return (float) avatarC1Bitmap.getWidth() / avatarC1Bitmap.getHeight();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), R.drawable.c1, options);
        if (options.outWidth > 0 && options.outHeight > 0) {
            return (float) options.outWidth / options.outHeight;
        }
        return 1f;
    }

    private void applyAvatarPosition() {
        if (floatVisual == null) return;
        ViewGroup.LayoutParams rawParams = floatVisual.getLayoutParams();
        if (!(rawParams instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
        boolean iconMode = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode);
        boolean bubbleVisible = bubblePanel != null && bubblePanel.getVisibility() == View.VISIBLE;
        boolean compactWindow = iconMode || !bubbleVisible;
        if (compactWindow) {
            params.leftMargin = 0;
            params.topMargin = 0;
            if (layoutParams != null) {
                layoutParams.x = avatarPosX;
                layoutParams.y = avatarPosY;
                updateOverlayLayout();
            }
        } else {
            params.leftMargin = avatarPosX;
            params.topMargin = avatarPosY;
        }
        floatVisual.setLayoutParams(params);
        updateBubblePanelPosition();
    }

    private void attachBubbleDragListener(View target, boolean ignoreInteractiveHit) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            if (ignoreInteractiveHit && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && isBubbleInteractiveHit(event.getRawX(), event.getRawY())) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    draggingBubble = false;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    initialX = bubblePosX;
                    initialY = bubblePosY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchStartX;
                    float dy = event.getRawY() - touchStartY;
                    if (!draggingBubble && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        draggingBubble = true;
                    }
                    if (draggingBubble) {
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int screenH = getResources().getDisplayMetrics().heightPixels;
                        int bubbleW = (bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams)
                                ? ((FrameLayout.LayoutParams) bubblePanel.getLayoutParams()).width
                                : dpToPx(250);
                        if (bubbleW <= 0) bubbleW = dpToPx(250);
                        int bubbleH = bubblePanel.getHeight();
                        if (bubbleH <= 0) bubbleH = dpToPx(300);
                        int nextX = initialX + (int) dx;
                        int nextY = initialY + (int) dy;
                        int maxX = Math.max(0, screenW - bubbleW);
                        int maxY = Math.max(0, screenH - bubbleH);
                        bubblePosX = Math.max(0, Math.min(nextX, maxX));
                        bubblePosY = Math.max(0, Math.min(nextY, maxY));
                        updateBubblePanelPosition();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingBubble) {
                        draggingBubble = false;
                        saveSettings();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean isBubbleInteractiveHit(float rawX, float rawY) {
        return isPointInsideView(hideButton, rawX, rawY)
                || isPointInsideView(inputView, rawX, rawY)
                || isPointInsideView(sendButton, rawX, rawY)
                || isPointInsideView(messageScrollView, rawX, rawY);
    }

    private boolean isPointInsideView(View target, float rawX, float rawY) {
        if (target == null || target.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int right = left + target.getWidth();
        int bottom = top + target.getHeight();
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private void updateBubblePanelPosition() {
        if (bubblePanel == null || floatVisual == null) return;
        if (!(bubblePanel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams bubbleParams = (FrameLayout.LayoutParams) bubblePanel.getLayoutParams();
        int marginPx = dpToPx(FLOAT_BUBBLE_MARGIN_DP);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int bubbleW = bubbleParams.width > 0 ? bubbleParams.width : bubblePanel.getWidth();
        if (bubbleW <= 0) bubbleW = Math.min(dpToPx(300), Math.max(dpToPx(200), screenW - marginPx * 2));
        
        // If bubble has been manually positioned, use that position
        if (bubblePosX != 0 || bubblePosY != 0) {
            bubbleParams.gravity = Gravity.TOP | Gravity.START;
            bubbleParams.rightMargin = 0;
            bubbleParams.bottomMargin = 0;
            bubbleParams.leftMargin = bubblePosX;
            bubbleParams.topMargin = bubblePosY;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        
        if (FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode)) {
            bubbleParams.gravity = Gravity.BOTTOM | Gravity.START;
            bubbleParams.leftMargin = marginPx;
            bubbleParams.topMargin = 0;
            bubbleParams.rightMargin = marginPx;
            bubbleParams.bottomMargin = marginPx;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.rightMargin = 0;
        bubbleParams.bottomMargin = 0;
        int desiredLeft = avatarPosX + ((avatarWidthPx - bubbleW) / 2);
        int maxLeft = Math.max(0, screenW - bubbleW);
        int left = Math.max(0, Math.min(desiredLeft, maxLeft));
        if (bubblePanel.getVisibility() != View.VISIBLE) {
            bubbleParams.leftMargin = left;
            bubbleParams.topMargin = avatarPosY + avatarHeightPx;
            bubblePanel.setLayoutParams(bubbleParams);
            return;
        }
        int bubbleH = bubblePanel.getHeight();
        if (bubbleH <= 0) {
            bubblePanel.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
            );
            bubbleH = bubblePanel.getMeasuredHeight();
        }
        int belowTop = avatarPosY + avatarHeightPx;
        int maxTop = Math.max(0, screenH - bubbleH);
        int top = Math.min(belowTop, maxTop);
        bubbleParams.leftMargin = left;
        bubbleParams.topMargin = Math.max(0, top);
        bubblePanel.setLayoutParams(bubbleParams);
    }

    private void scheduleNextBlink() {
        mainHandler.removeCallbacks(blinkRunnable);
        mainHandler.removeCallbacks(blinkResetRunnable);
        int delay = AVATAR_BLINK_MIN_MS + avatarRandom.nextInt(AVATAR_BLINK_MAX_MS - AVATAR_BLINK_MIN_MS + 1);
        mainHandler.postDelayed(blinkRunnable, delay);
    }

    private void startIdleAnimation() {
        stopTalkAnimation();
        setAvatarFrame(R.drawable.c1);
        scheduleNextBlink();
    }

    private void startTalkAnimation() {
        stopIdleAnimation();
        talkFrameIndex = 0;
        mainHandler.removeCallbacks(talkRunnable);
        mainHandler.post(talkRunnable);
    }

    private void stopIdleAnimation() {
        mainHandler.removeCallbacks(blinkRunnable);
        mainHandler.removeCallbacks(blinkResetRunnable);
    }

    private void stopTalkAnimation() {
        mainHandler.removeCallbacks(talkRunnable);
    }

    private void stopAvatarAnimation() {
        stopIdleAnimation();
        stopTalkAnimation();
    }

    private String defaultBaseName() {
        return "ja".equals(appLanguage) ? DEFAULT_BASE_NAME_JA : DEFAULT_BASE_NAME_EN;
    }

    private String defaultSystemPrompt() {
        return "ja".equals(appLanguage) ? DEFAULT_SYSTEM_PROMPT_JA : DEFAULT_SYSTEM_PROMPT_EN;
    }

    private String getApiUnavailableMessage() {
        return t(
                "I'm very sorry, but the LLM API is not available. Please return to the app and check the API settings.",
                "大変申し訳ありませんが、LLM APIが有効になっていません。アプリ画面へ戻ってAPI設定を確認してください。"
        );
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static final class ViewConfigurationHolder {
        private static int get(Service service) {
            return android.view.ViewConfiguration.get(service).getScaledTouchSlop();
        }
    }
}
