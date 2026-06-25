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
 * - セッションごとに SpeechRecognizer を新規生成し、終了・中断時には destroy して
 *   マイク／認識サービスを解放する（破棄せず放置するとゾンビ化し、後から自己回復して
 *   マイクを勝手に再起動することがある＝アプリを開いた途端マイクが点く症状の原因）。
 * - destroy 直後の再生成で ERROR_RECOGNIZER_BUSY が出た場合は短い遅延で1回リトライする。
 * - destroy+即再生成のレースで onReadyForSpeech 前に ERROR_SERVER_DISCONNECTED が誤発火する
 *   ことがあるが、多くは同一セッションが自己回復して成功する。即失敗にせず短時間待ち、
 *   回復しなければ再生成リトライ、上限到達で初めて失敗を通知する
 *   （「失敗しましたと出ても実際は成功」「2回目が起動しない」症状の対策）。
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
    /** SERVER_DISCONNECTED 後、自己回復（onReadyForSpeech）を待つ猶予。観測値は約30ms。 */
    private static final long SERVER_DISCONNECT_RECOVERY_MS = 300L;
    private static final int MAX_SERVER_DISCONNECT_RETRIES = 2;

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean currentPreferOffline = true;
    private boolean triedOnlineFallback = false;
    private boolean busyRetried = false;
    /** 現セッションで onReadyForSpeech を受信済みか（早期エラーの自己回復判定に使う）。 */
    private boolean readyReceived = false;
    /** 現セッションで SERVER_DISCONNECTED の回復待ちを既にスケジュール済みか。 */
    private boolean disconnectDeferred = false;
    /** ひとつの start() に対する SERVER_DISCONNECTED 再試行回数。 */
    private int serverDisconnectRetries = 0;
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
        serverDisconnectRetries = 0;
        startListeningInternal();
    }

    /** レコグナイザを破棄→再生成して認識を開始する（毎回クリーンな状態にする）。 */
    private void startListeningInternal() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        readyReceived = false;
        disconnectDeferred = false;
        final int gen = ++generation;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (gen != generation) return;
                readyReceived = true; // 早期 SERVER_DISCONNECTED から自己回復したことを示す
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
                        if (gen != generation || !isListening) return;
                        DebugLogger.log(context, "VoiceInput: BUSY retry");
                        startListeningInternal();
                    }, BUSY_RETRY_DELAY_MS);
                    return;
                }

                // destroy+即再生成のレースで onReadyForSpeech 前に SERVER_DISCONNECTED が誤発火することがある。
                // 多くは同一セッションが自己回復して onReadyForSpeech→onResults と成功するため、即失敗にしない。
                // 短時間待ち、回復しなければ destroy+再生成でリトライ、上限到達で初めて失敗を通知する。
                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                        && !readyReceived && !disconnectDeferred) {
                    disconnectDeferred = true;
                    DebugLogger.log(context, "VoiceInput: SERVER_DISCONNECTED defer (await recovery) gen=" + gen);
                    handler.postDelayed(() -> {
                        if (gen != generation || !isListening) return;
                        if (readyReceived) {
                            // 自己回復済み（マイク起動済み）。何もしない＝トーストを出さない。
                            DebugLogger.log(context, "VoiceInput: SERVER_DISCONNECTED self-recovered gen=" + gen);
                            return;
                        }
                        if (serverDisconnectRetries < MAX_SERVER_DISCONNECT_RETRIES) {
                            serverDisconnectRetries++;
                            DebugLogger.log(context, "VoiceInput: SERVER_DISCONNECTED retry "
                                    + serverDisconnectRetries + " gen=" + gen);
                            startListeningInternal();
                        } else {
                            DebugLogger.log(context, "VoiceInput: SERVER_DISCONNECTED give up gen=" + gen);
                            callback.toast("Voice recognition failed", "音声認識に失敗しました");
                            finishRecognition();
                        }
                    }, SERVER_DISCONNECT_RECOVERY_MS);
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
        generation++; // 終了後にこのレコグナイザから来る遅延コールバックを無効化する
        // 終了したレコグナイザは破棄してマイク／認識サービスを解放する。
        // 破棄せず放置するとゾンビ化し、後から自己回復してマイクを勝手に再起動することがある
        //（アプリを開いた途端マイクがアクティブになる症状の原因）。
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        callback.onListeningChanged(false);
        callback.onIdle();
    }

    /** ユーザー操作などによる中断。 */
    public void cancel() {
        handler.removeCallbacksAndMessages(null);
        generation++; // 進行中セッションのコールバックを無効化
        isListening = false;
        // cancel() のみだとレコグナイザが残存してマイク／認識サービスを保持しうるため破棄する。
        // 次回 start() は startListeningInternal で新規生成するので再利用しない。
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
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
