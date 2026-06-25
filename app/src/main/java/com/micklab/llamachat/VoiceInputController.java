package com.micklab.llamachat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * MainActivity と FloatOverlayService が共有する音声認識コントローラ。
 *
 * 実装は MainActivity.startVoiceRecognition の移植であり、両者で完全に同一仕様。
 * オフライン認識が失敗した場合にオンラインへフォールバックする挙動を含む。
 * 呼び出し側固有の処理（権限要求・トースト・アイドル復帰フック）は {@link Callback} で抽象化する。
 */
public final class VoiceInputController {

    public interface Callback {
        /** 認識中状態が変化したとき。送信ボタン表示などの更新に使う。 */
        void onListeningChanged(boolean listening);

        /** 認識テキストが確定したとき。呼び出し側が submitUserMessage 等へ渡す。 */
        void onResult(String text);

        /** 録音権限を確認・要求する。利用可能なら true。false の場合 start を中断する。 */
        boolean ensureRecordPermission();

        /** ローカライズ済みトースト表示。 */
        void toast(String enText, String jaText);

        /** 認識が終了しアイドルへ戻ったとき（MainActivity の auto-chatter 再開などに使用）。 */
        void onIdle();
    }

    private final Context context;
    private final Callback callback;

    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean currentPreferOffline = true;
    private boolean triedOnlineFallback = false;
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

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
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
                    callback.onListeningChanged(false);
                    callback.toast("Offline recognition failed, switching to online",
                            "オフライン認識に失敗、オンラインに切替");
                    start(false);
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
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String best = (matches != null && !matches.isEmpty()) ? matches.get(0).trim() : "";
                if (best.isEmpty()) {
                    finishRecognition();
                    return;
                }
                finishRecognition();
                callback.onResult(best);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizer.startListening(buildRecognizerIntent(preferOffline));
    }

    private void finishRecognition() {
        isListening = false;
        callback.onListeningChanged(false);
        callback.onIdle();
    }

    /** ユーザー操作などによる中断。 */
    public void cancel() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        isListening = false;
        callback.onListeningChanged(false);
    }

    /** 破棄。onDestroy で呼ぶ。 */
    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        isListening = false;
    }
}
