package com.micklab.llamachat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * MainActivity と FloatOverlayService が共有する音声認識コントローラ。
 *
 * 両者で完全に同一の挙動とするための単一実装。
 * - セッションごとに SpeechRecognizer を destroy + 再生成して確実に起動する
 *   （Service オーバーレイでは cancel() 再利用だと2回目以降 startListening が起動しないことがある）。
 * - destroy 直後の再生成で ERROR_RECOGNIZER_BUSY が出た場合は短い遅延で1回リトライする。
 * - オフライン認識が失敗した場合はオンラインへフォールバックする。
 * - 世代カウンタで、破棄済みレコグナイザからの遅延コールバックを無視する。
 * 呼び出し側固有の処理（権限要求・トースト・アイドル復帰）は {@link Callback} で抽象化する。
 */
public final class VoiceInputController {

    public interface Callback {
        void onListeningChanged(boolean listening);
        void onResult(String text);
        /** 録音権限を確認・要求する。利用可能なら true。false の場合 start を中断。 */
        boolean ensureRecordPermission();
        void toast(String enText, String jaText);
        /** 認識が終了しアイドルへ戻ったとき（MainActivity の auto-chatter 再開などに使用）。 */
        void onIdle();
    }

    private static final long BUSY_RETRY_DELAY_MS = 350L;

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean currentPreferOffline = true;
    private boolean triedOnlineFallback = false;
    private boolean busyRetried = false;
    private int generation = 0;
    private String speechLang = "ja-JP";

    public VoiceInputController(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void setLanguage(String lang) {
        if (lang != null && !lang.trim().isEmpty()) {
            this.speechLang = lang.trim();
        }
    }

    public boolean isListening() {
        return isListening;
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

    public void start(boolean preferOffline) {
        DebugLogger.log(context, "VoiceInput.start: isListening=" + isListening + " preferOffline=" + preferOffline);
        if (isListening) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.toast("Voice recognition unavailable", "音声認識が利用できません");
            return;
        }
        if (!callback.ensureRecordPermission()) {
            return;
        }
        isListening = true;
        callback.onListeningChanged(true);
        currentPreferOffline = preferOffline;
        triedOnlineFallback = !preferOffline;
        busyRetried = false;
        startListeningInternal();
    }

    /** レコグナイザを破棄→再生成して認識を開始する（毎回クリーンな状態にする）。 */
    private void startListeningInternal() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        final int gen = ++generation;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (gen != generation) return;
                DebugLogger.log(context, "VoiceInput.onReadyForSpeech gen=" + gen);
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                if (gen != generation) {
                    DebugLogger.log(context, "VoiceInput.onError stale gen=" + gen + " (cur=" + generation + ") error=" + error);
                    return;
                }
                DebugLogger.log(context, "VoiceInput.onError error=" + error + " gen=" + gen);

                // 破棄直後の再生成でレコグナイザが解放されていない場合（BUSY）は短い遅延で1回だけ再試行。
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !busyRetried) {
                    busyRetried = true;
                    handler.postDelayed(() -> {
                        if (isListening) {
                            DebugLogger.log(context, "VoiceInput: BUSY retry");
                            startListeningInternal();
                        }
                    }, BUSY_RETRY_DELAY_MS);
                    return;
                }

                boolean shouldFallback = currentPreferOffline && !triedOnlineFallback
                        && (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
                if (shouldFallback) {
                    triedOnlineFallback = true;
                    busyRetried = false;
                    currentPreferOffline = false; // オンライン認識へ切り替える
                    callback.toast("Offline recognition failed, switching to online",
                            "オフライン認識に失敗、オンラインに切替");
                    startListeningInternal();
                    return;
                }
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    callback.toast("Voice recognition failed", "音声認識に失敗しました");
                }
                finishRecognition();
            }

            @Override
            public void onResults(Bundle results) {
                if (gen != generation) {
                    DebugLogger.log(context, "VoiceInput.onResults stale gen=" + gen);
                    return;
                }
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : "";
                DebugLogger.log(context, "VoiceInput.onResults best=\"" + best + "\" gen=" + gen);
                finishRecognition();
                if (!best.isEmpty()) {
                    callback.onResult(best);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        speechRecognizer.startListening(buildRecognizerIntent(currentPreferOffline));
    }

    private void finishRecognition() {
        isListening = false;
        callback.onListeningChanged(false);
        callback.onIdle();
    }

    /** ユーザー操作などによる中断。 */
    public void cancel() {
        handler.removeCallbacksAndMessages(null);
        generation++; // 進行中セッションのコールバックを無効化
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        isListening = false;
        callback.onListeningChanged(false);
    }

    /** 破棄。onDestroy で呼ぶ。 */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        isListening = false;
    }
}
