package com.micklab.llamachat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FloatOverlayService extends Service {

    public static final String ACTION_SHOW_OVERLAY = "com.micklab.llamachat.action.SHOW_OVERLAY";

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
    private static final String NOTIFICATION_CHANNEL_ID = "llamachat_float_overlay";
    private static final int NOTIFICATION_ID = 2001;
    private static final int AVATAR_TALK_FRAME_MS = 120;
    private static final int AVATAR_BLINK_MIN_MS = 3000;
    private static final int AVATAR_BLINK_MAX_MS = 7000;
    private static final int AVATAR_BLINK_DURATION_MS = 120;
    private static final float FLOAT_AVATAR_HEIGHT_RATIO = 0.33f;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object historyLock = new Object();
    private final List<JSONObject> conversationHistory = new ArrayList<>();
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
    private View bubblePanel;
    private ScrollView messageScrollView;
    private LinearLayout messageContainer;
    private EditText inputView;
    private Button sendButton;
    private Button hideButton;
    private TextView bubbleTitleView;
    private GestureDetector gestureDetector;
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
    private int historyLimit = 10;
    private boolean streamingEnabled = true;
    private boolean isProcessing = false;
    private int messageMaxHeightPx = 0;

    private float touchStartX;
    private float touchStartY;
    private int initialX;
    private int initialY;
    private boolean dragging = false;
    private int touchSlop = 0;
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
            
            DebugLogger.log(this, "Loading avatar bitmaps");
            loadAvatarBitmaps();
            DebugLogger.log(this, "Avatar bitmaps loaded");
            
            DebugLogger.log(this, "Initializing conversation history");
            initConversationHistory();
            DebugLogger.log(this, "Conversation history initialized");
            
            DebugLogger.log(this, "Initializing overlay");
            initOverlay();
            DebugLogger.log(this, "Overlay initialized successfully");
            
            DebugLogger.log(this, "Building notification");
            Notification notification = buildNotification();
            DebugLogger.log(this, "Starting foreground");
            try {
                startForeground(NOTIFICATION_ID, notification);
                DebugLogger.log(this, "Foreground notification started");
            } catch (Exception e) {
                DebugLogger.log(this, "WARNING: startForeground failed: " + e.getMessage());
                DebugLogger.log(this, "Continuing anyway since overlay is already added");
            }
            
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        stopAvatarAnimation();
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
        
        // First, try adding a simple test view to verify window manager works
        try {
            android.widget.LinearLayout testLayout = new android.widget.LinearLayout(this);
            testLayout.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                    dpToPx(100), dpToPx(100)
            ));
            testLayout.setBackgroundColor(0xFF00FF00); // Green for testing
            
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            
            WindowManager.LayoutParams testParams = new WindowManager.LayoutParams(
                    dpToPx(100),
                    dpToPx(100),
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            testParams.gravity = Gravity.TOP | Gravity.START;
            testParams.x = 0;
            testParams.y = dpToPx(160);
            
            windowManager.addView(testLayout, testParams);
            DebugLogger.log(this, "initOverlay: Test view added successfully!");
            
            // If test view works, add the real overlay layout
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
                gestureDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            layoutParams.x = initialX + (int) dx;
                            layoutParams.y = initialY + (int) dy;
                            updateOverlayLayout();
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
                if (isProcessing) {
                    cancelCurrentRequest();
                    return;
                }
                String text = inputView.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(this, t("Please enter a message", "メッセージを入力してください"), Toast.LENGTH_SHORT).show();
                    return;
                }
                submitUserMessage(text);
            });
            hideButton.setOnClickListener(v -> showBubble(false));
    
            updateFloatVisual();
            DebugLogger.log(this, "initOverlay: Float visual updated");
            updateBubbleHeader();
            updateSendButton();
            appendSystemMessage(t("Tap to open quick chat.", "タップで簡易チャットを開きます。"));
            
            DebugLogger.log(this, "initOverlay: Adding real overlay view to window manager");
            windowManager.addView(overlayView, layoutParams);
            DebugLogger.log(this, "initOverlay: Real overlay view added successfully");
            
            // Remove test view after adding real one
            windowManager.removeView(testLayout);
            DebugLogger.log(this, "initOverlay: Test view removed");
            
        } catch (Exception e) {
            android.util.Log.e("FloatOverlay", "Failed during overlay initialization", e);
            e.printStackTrace();
            throw new RuntimeException("Cannot initialize overlay", e);
        }
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        stopSelf();
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

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
                .setContentTitle(t("Floating chat active", "フロートチャット起動中"))
                .setContentText(t("Double tap the overlay to return to the app.", "オーバーレイをダブルタップするとアプリへ戻ります。"))
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void toggleBubble() {
        showBubble(bubblePanel.getVisibility() != View.VISIBLE);
    }

    private void showBubble(boolean show) {
        bubblePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        layoutParams.flags = show
                ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateOverlayLayout();
        if (show) {
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
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int avatarHeightPx = Math.max(1, Math.round(screenH * FLOAT_AVATAR_HEIGHT_RATIO));
        int avatarWidthPx = Math.max(
                1,
                Math.min(screenW, Math.round(avatarHeightPx * getAvatarAspectRatio()))
        );
        layoutParams.width = iconMode ? WindowManager.LayoutParams.WRAP_CONTENT : screenW;
        layoutParams.height = iconMode ? WindowManager.LayoutParams.WRAP_CONTENT : screenH;
        if (!iconMode) {
            layoutParams.x = 0;
            layoutParams.y = 0;
        }
        ViewGroup.LayoutParams params = floatVisual.getLayoutParams();
        if (params != null) {
            if (iconMode) {
                int sizePx = dpToPx(56);
                params.width = sizePx;
                params.height = sizePx;
            } else {
                params.width = avatarWidthPx;
                params.height = avatarHeightPx;
            }
            floatVisual.setLayoutParams(params);
        }
        if (bubblePanel != null) {
            ViewGroup.LayoutParams bubbleParams = bubblePanel.getLayoutParams();
            if (bubbleParams != null) {
                bubbleParams.width = iconMode ? dpToPx(300) : ViewGroup.LayoutParams.MATCH_PARENT;
                bubblePanel.setLayoutParams(bubbleParams);
            }
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
        if (isProcessing) {
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
                sendButton.setText(isProcessing ? "STOP" : t("Send", "送信"));
            }
        });
    }

    private void submitUserMessage(String userMessage) {
        inputView.setText("");
        appendUserMessageBubble(userMessage);
        appendSharedConversationLog("user", userMessage);
        addToHistory("user", userMessage);
        isProcessing = true;
        updateAvatarAnimation();
        currentResponseBubble = null;
        updateSendButton();
        appendSystemMessage(t("Waiting for response...", "応答を待っています..."));
        sendChat(null, false);
    }

    private void cancelCurrentRequest() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        isProcessing = false;
        updateAvatarAnimation();
        updateSendButton();
        currentResponseBubble = null;
        setResponseText(t("Request cancelled", "リクエストをキャンセルしました"));
    }

    private void sendChat(String transientUserMessage, boolean replaceLastUserMessage) {
        try {
            JSONArray messages = new JSONArray();
            boolean shouldReplaceLastUser = replaceLastUserMessage && transientUserMessage != null;
            synchronized (historyLock) {
                if (!conversationHistory.isEmpty()) {
                    JSONObject first = conversationHistory.get(0);
                    if ("system".equals(first.optString("role"))) {
                        JSONObject sys = new JSONObject();
                        sys.put("role", "system");
                        sys.put("content", first.optString("content", ""));
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
                sendStreaming(request);
            } else {
                sendNonStreaming(request);
            }
        } catch (Exception e) {
            isProcessing = false;
            updateSendButton();
            setResponseText(errorText(e.getMessage()));
        }
    }

    private void sendStreaming(Request request) {
        Call call = client.newCall(request);
        currentCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                finishResponse(errorText(getApiUnavailableMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    currentCall = null;
                    finishResponse(errorText("HTTP error: " + response.code()));
                    return;
                }

                String visibleResponse = "";
                try (InputStream is = response.body() != null ? response.body().byteStream() : null;
                     BufferedReader reader = is != null
                             ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                             : null) {
                    if (reader == null) {
                        finishResponse(t("(No response)", "（応答なし）"));
                        return;
                    }

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        JSONObject json = new JSONObject(line);
                        if (json.has("message")) {
                            visibleResponse += json.getJSONObject("message").optString("content", "");
                            setResponseText(visibleResponse);
                        }
                        if (json.optBoolean("done", false)) {
                            break;
                        }
                    }
                    finishResponse(visibleResponse.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : visibleResponse);
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        finishResponse(errorText("Stream error: " + e.getMessage()));
                    }
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void sendNonStreaming(Request request) {
        Call call = client.newCall(request);
        currentCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentCall = null;
                if (call.isCanceled()) {
                    return;
                }
                finishResponse(errorText(getApiUnavailableMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        finishResponse(errorText("HTTP error: " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    String text = json.optJSONObject("message") != null
                            ? json.optJSONObject("message").optString("content", "")
                            : "";
                    finishResponse(text.trim().isEmpty()
                            ? t("(No response)", "（応答なし）")
                            : text);
                } catch (Exception e) {
                    finishResponse(errorText(e.getMessage()));
                } finally {
                    currentCall = null;
                }
            }
        });
    }

    private void finishResponse(String responseText) {
        if (!TextUtils.isEmpty(responseText) && !responseText.startsWith(t("Error: ", "エラー: "))) {
            addToHistory("assistant", responseText);
            appendSharedConversationLog("assistant", responseText);
        }
        isProcessing = false;
        updateAvatarAnimation();
        updateSendButton();
        setResponseText(responseText);
        currentResponseBubble = null;
    }

    private String errorText(String message) {
        String detail = TextUtils.isEmpty(message) ? t("Unknown error", "不明なエラー") : message;
        return t("Error: ", "エラー: ") + detail;
    }

    private void setResponseText(String text) {
        mainHandler.post(() -> {
            if (TextUtils.isEmpty(text)) return;
            if (currentResponseBubble == null) {
                currentResponseBubble = appendAssistantMessageBubble(text);
            } else {
                currentResponseBubble.setText(formatMessageText(baseName, text));
                adjustMessageAreaHeight();
                scrollMessagesToBottom();
            }
        });
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
        bubble.setText(formatMessageText(name, text));

        row.addView(bubble);
        messageContainer.addView(row);
        adjustMessageAreaHeight();
        scrollMessagesToBottom();
        return bubble;
    }

    private String formatMessageText(String name, String text) {
        if (TextUtils.isEmpty(name)) return text == null ? "" : text;
        return name + "\n" + (text == null ? "" : text);
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
        });
    }

    private void scrollMessagesToBottom() {
        if (messageScrollView == null) return;
        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
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
        } catch (Exception ignored) {
        }
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

    private void applySettingsJson(JSONObject settings) {
        appLanguage = settings.optString("appLanguage", appLanguage);
        ollamaBaseUrl = settings.optString("ollamaBaseUrl", ollamaBaseUrl);
        selectedModel = settings.optString("selectedModel", selectedModel);
        streamingEnabled = settings.optBoolean("streamingEnabled", streamingEnabled);
        systemPromptText = settings.optString("systemPrompt", systemPromptText);
        baseName = settings.optString("baseName", baseName);
        userName = settings.optString("userName", userName);
        historyLimit = Math.max(0, settings.optInt("historyLimit", historyLimit));
        floatDisplayMode = normalizeFloatDisplayMode(settings.optString("floatDisplayMode", floatDisplayMode));
        if (TextUtils.isEmpty(baseName)) {
            baseName = defaultBaseName();
        }
        if (TextUtils.isEmpty(systemPromptText)) {
            systemPromptText = defaultSystemPrompt();
        }
        if (TextUtils.isEmpty(selectedModel)) {
            selectedModel = "default";
        }
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
