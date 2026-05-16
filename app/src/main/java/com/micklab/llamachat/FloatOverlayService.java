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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final String FLOAT_DISPLAY_MODE_AVATAR = "avatar";
    private static final String FLOAT_DISPLAY_MODE_ICON = "icon";
    private static final String AVATAR_C1_FILE = "avatar_c1.jpg";
    private static final String DEFAULT_BASE_NAME_JA = "藍";
    private static final String DEFAULT_BASE_NAME_EN = "Ai";
    private static final String DEFAULT_SYSTEM_PROMPT_JA = "あなたはユーザの若い女性秘書です";
    private static final String DEFAULT_SYSTEM_PROMPT_EN = "You are the user's young female secretary.";
    private static final String NOTIFICATION_CHANNEL_ID = "llamachat_float_overlay";
    private static final int NOTIFICATION_ID = 2001;
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
    private ImageView floatVisual;
    private View bubblePanel;
    private EditText inputView;
    private Button sendButton;
    private Button hideButton;
    private TextView bubbleTitleView;
    private TextView responseLabelView;
    private TextView responseView;
    private GestureDetector gestureDetector;
    private Bitmap avatarBitmap;
    private Call currentCall;

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

    private float touchStartX;
    private float touchStartY;
    private int initialX;
    private int initialY;
    private boolean dragging = false;
    private int touchSlop = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        touchSlop = ViewConfigurationHolder.get(this);
        loadSettings();
        loadAvatarBitmap();
        initConversationHistory();
        startForeground(NOTIFICATION_ID, buildNotification());
        initOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
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
        if (avatarBitmap != null) {
            avatarBitmap.recycle();
            avatarBitmap = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_float, null);
        floatVisual = overlayView.findViewById(R.id.floatVisual);
        bubblePanel = overlayView.findViewById(R.id.bubblePanel);
        inputView = overlayView.findViewById(R.id.etFloatInput);
        sendButton = overlayView.findViewById(R.id.btnFloatSend);
        hideButton = overlayView.findViewById(R.id.btnHideBubble);
        bubbleTitleView = overlayView.findViewById(R.id.tvBubbleTitle);
        responseLabelView = overlayView.findViewById(R.id.tvResponseLabel);
        responseView = overlayView.findViewById(R.id.tvFloatResponse);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 0;
        layoutParams.y = dpToPx(160);

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
        updateBubbleHeader();
        updateSendButton();
        responseView.setText(t("Tap to open quick chat.", "タップで簡易チャットを開きます。"));
        windowManager.addView(overlayView, layoutParams);
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
        if (floatVisual == null) return;
        int sizeDp = FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode) ? 56 : 88;
        ViewGroup.LayoutParams params = floatVisual.getLayoutParams();
        if (params != null) {
            int sizePx = dpToPx(sizeDp);
            params.width = sizePx;
            params.height = sizePx;
            floatVisual.setLayoutParams(params);
        }
        floatVisual.setBackgroundColor(0x00000000);
        floatVisual.setContentDescription(t(
                FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode) ? "Floating icon" : "Floating avatar",
                FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode) ? "フロートアイコン" : "フロートアバター"
        ));
        if (FLOAT_DISPLAY_MODE_ICON.equals(floatDisplayMode)) {
            floatVisual.setImageResource(R.drawable.icon);
        } else if (avatarBitmap != null) {
            floatVisual.setImageBitmap(avatarBitmap);
        } else {
            floatVisual.setImageResource(R.drawable.c1);
        }
    }

    private void updateBubbleHeader() {
        if (bubbleTitleView != null) {
            bubbleTitleView.setText(TextUtils.isEmpty(baseName)
                    ? t("Quick Chat", "フロートチャット")
                    : baseName);
        }
        if (responseLabelView != null) {
            responseLabelView.setText(t("Latest response", "最新の応答"));
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
        addToHistory("user", userMessage);
        isProcessing = true;
        updateSendButton();
        setResponseText(t("Waiting for response...", "応答を待っています..."));
        sendChat(null, false);
    }

    private void cancelCurrentRequest() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        isProcessing = false;
        updateSendButton();
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
        }
        isProcessing = false;
        updateSendButton();
        setResponseText(responseText);
    }

    private String errorText(String message) {
        String detail = TextUtils.isEmpty(message) ? t("Unknown error", "不明なエラー") : message;
        return t("Error: ", "エラー: ") + detail;
    }

    private void setResponseText(String text) {
        mainHandler.post(() -> {
            if (responseView != null) {
                responseView.setText(text);
            }
        });
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

    private void loadAvatarBitmap() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File avatarFile = new File(dir, AVATAR_C1_FILE);
        if (!avatarFile.exists()) return;
        avatarBitmap = BitmapFactory.decodeFile(avatarFile.getAbsolutePath());
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
