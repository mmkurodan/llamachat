package com.micklab.llamachat;

import android.content.Context;
import android.text.TextUtils;

import com.micklab.llamachat.calendar.CalendarResultForChat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExpertPromptStore {
    public static final String FEATURE_WEB_SEARCH = "web_search";
    public static final String FEATURE_CALENDAR = "calendar";

    public static final String KEY_EXPERT_ROUTER = "expertRouterPrompt";
    public static final String KEY_WEB_SEARCH_KEYWORD = "webSearchKeywordPrompt";
    public static final String KEY_WEB_SEARCH_SYSTEM = "webSearchSystemPrompt";
    public static final String KEY_CALENDAR_JUDGE = "calendarJudgePrompt";
    public static final String KEY_CALENDAR_CREATE_JUDGE = "calendarCreateJudgePrompt";
    public static final String KEY_CALENDAR_QUERY_JUDGE = "calendarQueryJudgePrompt";
    public static final String KEY_CALENDAR_UPDATE_JUDGE = "calendarUpdateJudgePrompt";
    public static final String KEY_CALENDAR_DELETE_JUDGE = "calendarDeleteJudgePrompt";
    public static final String KEY_CALENDAR_RETRY = "calendarRetryPrompt";
    public static final String KEY_CALENDAR_EXPLAIN_SYSTEM = "calendarExplainSystemPrompt";
    public static final String KEY_CALENDAR_EXPLAIN_USER = "calendarExplainUserPrompt";

    private static final String FILE_NAME = "expert_prompts.json";

    private static final PromptSpec[] PROMPT_SPECS = new PromptSpec[]{
            new PromptSpec(
                    KEY_EXPERT_ROUTER,
                    "Expert route check",
                    "エキスパート要否判定",
                    "あなたの役割は、ユーザー入力に対して利用すべき補助機能だけを選別することです。\n"
                            + "補助機能の候補は毎回、呼び方や並び順を変えて提示されます。表示名に引きずられず、用途説明から判断してください。\n\n"
                            + "利用可能な候補:\n"
                            + "{{enabled_features_block}}\n\n"
                            + "出力は JSON オブジェクト 1 つのみで、前後に説明文を付けないでください。\n"
                            + "{\"required_functions\":[\"web_search\",\"calendar\"],\"note\":\"short reason\"}\n\n"
                            + "制約:\n"
                            + "- required_functions には必要な機能だけを必要順で入れる\n"
                            + "- 不要なら空配列 []\n"
                            + "- 機能IDは web_search / calendar だけを使う\n"
                            + "- note は 1 文で簡潔に書く\n\n"
                            + "ユーザー入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_WEB_SEARCH_KEYWORD,
                    "Web search keyword extraction",
                    "Web検索キーワード抽出",
                    "あなたの役割は「ユーザーの質問がインターネット検索を必要とするか判定し、必要なら検索キーワードを抽出する」ことです。\n\n"
                            + "出力は必ず次のどちらか一つだけにしてください:\n\n"
                            + "1. 検索が必要な場合:\nSEARCH: <検索キーワード>\n\n"
                            + "2. 検索が不要な場合:\nNONE\n\n"
                            + "制約:\n"
                            + "- 説明文や理由を書かない\n"
                            + "- 箇条書きや追加情報を含めない\n"
                            + "- キーワードは短く、検索エンジンで使える語句のみ\n"
                            + "- 複数キーワードが必要な場合はスペース区切りで 1 行にまとめる\n"
                            + "- 出力形式を絶対に変えない\n\n"
                            + "ユーザーの質問:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_WEB_SEARCH_SYSTEM,
                    "Web search system prompt",
                    "Web検索システムプロンプト",
                    "You are a search-augmented assistant. When the user provides SEARCH_RESULTS, you must read them and base your answer strictly on that information."
            ),
            new PromptSpec(
                    KEY_CALENDAR_JUDGE,
                    "Calendar action judge (legacy)",
                    "Calendar操作判定（旧）",
                    "Google Calendar 操作判定です。\n"
                            + "【出力】\n"
                            + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                            + "【title 抽出】\n"
                            + "- 「◯◯の予定」「◯◯という予定」→ title = 「◯◯」\n"
                            + "- 「予定」「イベント」「日程」「スケジュール」は title に含めない\n"
                            + "- 「今日」「本日」「明日」「明後日」「今週」「来週」「今月」「来月」などの日付表現は title に入れず、start/end 側の日付として表現する\n"
                            + "- 曖昧でも null に逃げず、最も自然な名詞を title にする\n"
                            + "【DELETE】\n"
                            + "- DELETE の start/end は必ず null\n"
                            + "- 期間指定があっても start/end を生成しない\n"
                            + "【時間】\n"
                            + "- 「11時から2時間」→ 絶対時間 11:00〜13:00\n"
                            + "- 「今から2時間」だけ相対時間\n"
                            + "- タイムゾーン +09:00 は加算しない。付与するだけ\n"
                            + "【JSON 仕様】\n"
                            + "{\"action\":\"NONE|QUERY|CREATE|UPDATE|DELETE\",\"title\":\"string or null\",\"start\":\"ISO8601 string or null\",\"end\":\"ISO8601 string or null\",\"eventId\":\"string or null\",\"additional\":{\"rawText\":\"元のユーザ入力\",\"notes\":\"補足\"}}\n"
                            + "現在日時:\n"
                            + "{{now_iso8601}}\n"
                            + "ユーザ入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_CREATE_JUDGE,
                    "Calendar create judge",
                    "Calendar登録判定",
                    "Google Calendar 登録判定です。\n"
                            + "【出力】\n"
                            + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                            + "- action は CREATE または NONE のみ。\n"
                            + "- eventId は必ず null。\n"
                            + "【title ルール】\n"
                            + "- title には予定の名前だけを入れる。日時・期間は含めない。\n"
                            + "- 「◯◯の予定」「◯◯という予定」→ title = 「◯◯」（「予定」等は末尾から除く）。\n"
                            + "- 「今日」「本日」「明日」「△時から」などの日時表現は title に入れず、必ず start/end に変換する。\n"
                            + "【時間ルール】\n"
                            + "- 時刻が分かる場合は必ず start に入れる。end が不明なら null でよい（アプリが自動補完する）。\n"
                            + "- 「△時から○時間」→ start=△時:00, end=△時+○時間 を ISO8601 で計算して入れる。\n"
                            + "- 「△時から」「△時に」（終了時刻/時間が不明）→ start=△時:00, end=null。\n"
                            + "- 「今から」のみ相対計算（現在日時を起点）。\n"
                            + "- 時刻が一切不明な場合だけ start を null にしてよい。\n"
                            + "- タイムゾーン +09:00 は加算しない。付与するだけ。\n"
                            + "【変換例】\n"
                            + "現在日時が 2026-06-23T09:00:00+09:00 のとき:\n"
                            + "入力: 本日の11時から作戦会議を1時間入れて\n"
                            + "→ {\"action\":\"CREATE\",\"title\":\"作戦会議\",\"start\":\"2026-06-23T11:00:00+09:00\",\"end\":\"2026-06-23T12:00:00+09:00\",\"eventId\":null,\"additional\":{\"rawText\":\"本日の11時から作戦会議を1時間入れて\",\"notes\":\"\"}}\n"
                            + "入力: 明日の午後3時からチームミーティングを2時間\n"
                            + "→ {\"action\":\"CREATE\",\"title\":\"チームミーティング\",\"start\":\"2026-06-24T15:00:00+09:00\",\"end\":\"2026-06-24T17:00:00+09:00\",\"eventId\":null,\"additional\":{\"rawText\":\"明日の午後3時からチームミーティングを2時間\",\"notes\":\"\"}}\n"
                            + "入力: 明日の12時からランチを予定して\n"
                            + "→ {\"action\":\"CREATE\",\"title\":\"ランチ\",\"start\":\"2026-06-24T12:00:00+09:00\",\"end\":null,\"eventId\":null,\"additional\":{\"rawText\":\"明日の12時からランチを予定して\",\"notes\":\"\"}}\n"
                            + "【JSON 仕様】\n"
                            + "{\"action\":\"CREATE|NONE\",\"title\":\"string or null\",\"start\":\"ISO8601 string or null\",\"end\":\"ISO8601 string or null\",\"eventId\":null,\"additional\":{\"rawText\":\"元のユーザ入力\",\"notes\":\"補足\"}}\n"
                            + "現在日時:\n"
                            + "{{now_iso8601}}\n"
                            + "ユーザ入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_QUERY_JUDGE,
                    "Calendar query judge",
                    "Calendar検索判定",
                    "Google Calendar 検索判定です。\n"
                            + "【出力】\n"
                            + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                            + "- action は QUERY または NONE のみ。\n"
                            + "- eventId は必ず null。\n"
                            + "【title 抽出】\n"
                            + "- 検索に使う予定名やキーワードを title に入れる。\n"
                            + "- 「予定」「イベント」「日程」「スケジュール」は title に含めない。\n"
                            + "- 「今日」「本日」「明日」「明後日」「今週」「来週」「今月」「来月」などの日付表現は title に入れず、start/end 側の日付として表現する。\n"
                            + "- タイトルが取れない場合だけ null にしてよい。\n"
                            + "【時間ルール】\n"
                            + "- start/end には必ず ISO8601 形式の文字列を入れる。\"today\"\"tomorrow\" などの英単語は禁止。\n"
                            + "- 期間指定がない場合は start/end を null にしてよい。\n"
                            + "- 「今日」→ start=今日の 00:00:00+09:00、end=翌日の 00:00:00+09:00\n"
                            + "- 「明日」→ start=明日の 00:00:00+09:00、end=明後日の 00:00:00+09:00\n"
                            + "- 「今週」→ start=今週月曜 00:00:00+09:00、end=来週月曜 00:00:00+09:00\n"
                            + "【変換例】\n"
                            + "現在日時が 2026-06-25T09:00:00+09:00 のとき:\n"
                            + "入力: 今日の予定を教えて\n"
                            + "→ {\"action\":\"QUERY\",\"title\":null,\"start\":\"2026-06-25T00:00:00+09:00\",\"end\":\"2026-06-26T00:00:00+09:00\",\"eventId\":null,\"additional\":{\"rawText\":\"今日の予定を教えて\",\"notes\":\"\"}}\n"
                            + "入力: 明日のランチミーティングを確認して\n"
                            + "→ {\"action\":\"QUERY\",\"title\":\"ランチミーティング\",\"start\":\"2026-06-26T00:00:00+09:00\",\"end\":\"2026-06-27T00:00:00+09:00\",\"eventId\":null,\"additional\":{\"rawText\":\"明日のランチミーティングを確認して\",\"notes\":\"\"}}\n"
                            + "【JSON 仕様】\n"
                            + "{\"action\":\"QUERY|NONE\",\"title\":\"string or null\",\"start\":\"ISO8601 string or null\",\"end\":\"ISO8601 string or null\",\"eventId\":null,\"additional\":{\"rawText\":\"元のユーザ入力\",\"notes\":\"補足\"}}\n"
                            + "現在日時:\n"
                            + "{{now_iso8601}}\n"
                            + "ユーザ入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_UPDATE_JUDGE,
                    "Calendar update judge",
                    "Calendar変更判定",
                    "Google Calendar 変更判定です。\n"
                            + "【出力】\n"
                            + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                            + "- action は UPDATE または NONE のみ。\n"
                            + "- eventId は必ず null。対象選択はアプリ側で行う。\n"
                            + "【targetQuery ルール】\n"
                            + "- targetQuery には変更対象の予定名だけを入れる。日時・「予定」等は含めない。\n"
                            + "- 「今日」「本日」「明日」などの日付表現は targetQuery に入れない。予定名のみ。\n"
                            + "【title ルール】\n"
                            + "- title には変更後の予定名だけを入れる。名前を変えない場合は null。\n"
                            + "【時間ルール】\n"
                            + "- start/end は変更後の日時を ISO8601 で入れる。変更しない項目は null。\n"
                            + "- 「11時から2時間」→ start=11:00, end=13:00。\n"
                            + "【変換例】\n"
                            + "入力: 本日の作戦会議を明日の10時に変更\n"
                            + "→ targetQuery=\"作戦会議\", title=null, start=<明日>T10:00:00+09:00, end=null\n"
                            + "入力: 提案作戦会議の名前を戦略会議に変更して開始を11時にして\n"
                            + "→ targetQuery=\"提案作戦会議\", title=\"戦略会議\", start=<今日>T11:00:00+09:00, end=null\n"
                            + "【JSON 仕様】\n"
                            + "{\"action\":\"UPDATE|NONE\",\"title\":\"変更後の予定名 or null\",\"start\":\"ISO8601 string or null\",\"end\":\"ISO8601 string or null\",\"eventId\":null,\"additional\":{\"rawText\":\"元のユーザ入力\",\"targetQuery\":\"予定名のみ\",\"notes\":\"補足\"}}\n"
                            + "現在日時:\n"
                            + "{{now_iso8601}}\n"
                            + "ユーザ入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_DELETE_JUDGE,
                    "Calendar delete judge",
                    "Calendar削除判定",
                    "Google Calendar 削除判定です。\n"
                            + "【出力】\n"
                            + "- JSON オブジェクト 1 つのみ。前後に何も付けない。\n"
                            + "- action は DELETE または NONE のみ。\n"
                            + "- eventId は必ず null。対象選択はアプリ側で行う。\n"
                            + "【targetQuery ルール】\n"
                            + "- additional.targetQuery には予定名だけを入れる。日時や「予定」等は含めない。\n"
                            + "- 「今日」「本日」「明日」「明後日」「今週」「来週」「今月」「来月」などの日付表現は targetQuery に入れない。\n"
                            + "- 「予定」「イベント」「日程」「スケジュール」は targetQuery に含めない。\n"
                            + "- title は null でよい。\n"
                            + "【時間】\n"
                            + "- DELETE の start/end は必ず null。\n"
                            + "【変換例】\n"
                            + "入力: 本日の提案作戦会議の予定を削除\n"
                            + "→ targetQuery=\"提案作戦会議\"  (「本日の」「予定」は含めない)\n"
                            + "入力: 明日の10時からの会議を消して\n"
                            + "→ targetQuery=\"会議\"  (「明日の10時からの」は含めない)\n"
                            + "【JSON 仕様】\n"
                            + "{\"action\":\"DELETE|NONE\",\"title\":null,\"start\":null,\"end\":null,\"eventId\":null,\"additional\":{\"rawText\":\"元のユーザ入力\",\"targetQuery\":\"予定名のみ\",\"notes\":\"補足\"}}\n"
                            + "現在日時:\n"
                            + "{{now_iso8601}}\n"
                            + "ユーザ入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_RETRY,
                    "Calendar retry prompt",
                    "Calendar再判定",
                    "LLM 出力が不正でした。理由:\n"
                            + "{{reasons}}\n"
                            + "上記を修正し、正しい JSON オブジェクト 1 つだけを返してください。\n\n"
                            + "{{calendar_judge_prompt}}"
            ),
            new PromptSpec(
                    KEY_CALENDAR_EXPLAIN_SYSTEM,
                    "Calendar explanation system prompt",
                    "Calendar説明システムプロンプト",
                    "あなたはユーザ向けの説明を生成するアシスタントです。CALENDAR_OPERATION_RESULT の内容だけを使って、自然な日本語で簡潔に説明してください。余計な推測、創作、補足、追加情報は禁止です。"
            ),
            new PromptSpec(
                    KEY_CALENDAR_EXPLAIN_USER,
                    "Calendar explanation user prompt",
                    "Calendar説明ユーザープロンプト",
                    "あなたはユーザ向けの説明を生成するアシスタントです。\n"
                            + "以下はアプリが実行した Google Calendar API の結果です。\n\n"
                            + "{{calendar_result_block}}\n"
                            + "{{search_results_block}}\n"
                            + "元のユーザ入力:\n"
                            + "{{user_input}}\n\n"
                            + "上記の内容を踏まえて、ユーザに自然な日本語で簡潔に説明してください。\n"
                            + "余計な推測や追加情報は含めないでください。"
            )
    };

    private ExpertPromptStore() {
    }

    public static List<PromptSpec> getPromptSpecs() {
        List<PromptSpec> visibleSpecs = new ArrayList<>();
        for (PromptSpec spec : PROMPT_SPECS) {
            if (!KEY_EXPERT_ROUTER.equals(spec.getKey())
                    && !KEY_CALENDAR_JUDGE.equals(spec.getKey())) {
                visibleSpecs.add(spec);
            }
        }
        return Collections.unmodifiableList(visibleSpecs);
    }

    public static PromptSpec getPromptSpec(String key) {
        for (PromptSpec spec : PROMPT_SPECS) {
            if (spec.getKey().equals(key)) {
                return spec;
            }
        }
        return PROMPT_SPECS[0];
    }

    public static synchronized String getPrompt(Context context, String key) {
        PromptSpec spec = getPromptSpec(key);
        JSONObject json = readJson(context);
        String saved = json.optString(key, "");
        return TextUtils.isEmpty(saved) ? spec.getDefaultValue() : saved;
    }

    public static synchronized void savePrompt(Context context, String key, String value) {
        JSONObject json = readJson(context);
        try {
            json.put(key, value == null ? "" : value);
            writeJson(context, json);
        } catch (Exception ignored) {
        }
    }

    public static String getSearchSystemPrompt(Context context) {
        return getPrompt(context, KEY_WEB_SEARCH_SYSTEM).trim();
    }

    public static String buildExpertRouterPrompt(Context context, List<String> enabledFeatures, String userInput) {
        Map<String, String> values = new HashMap<>();
        values.put("enabled_features_block", buildEnabledFeaturesBlock(enabledFeatures, userInput));
        values.put("user_input", safe(userInput));
        return applyTemplate(getPrompt(context, KEY_EXPERT_ROUTER), values);
    }

    public static String buildWebSearchKeywordPrompt(Context context, String userInput) {
        Map<String, String> values = new HashMap<>();
        values.put("user_input", safe(userInput));
        return applyTemplate(getPrompt(context, KEY_WEB_SEARCH_KEYWORD), values);
    }

    public static String buildCalendarJudgePrompt(Context context,
                                                  ExpertType expertType,
                                                  String userInput,
                                                  String nowIso8601) {
        Map<String, String> values = new HashMap<>();
        values.put("user_input", safe(userInput));
        values.put("now_iso8601", safe(nowIso8601));
        return applyTemplate(getCalendarJudgePromptTemplate(context, expertType), values);
    }

    public static String buildCalendarRetryPrompt(Context context,
                                                  ExpertType expertType,
                                                  String userInput,
                                                  String nowIso8601,
                                                  List<String> reasons) {
        Map<String, String> values = new HashMap<>();
        values.put("reasons", buildReasonBlock(reasons));
        values.put("calendar_judge_prompt", buildCalendarJudgePrompt(context, expertType, userInput, nowIso8601));
        values.put("user_input", safe(userInput));
        values.put("now_iso8601", safe(nowIso8601));
        return applyTemplate(getPrompt(context, KEY_CALENDAR_RETRY), values);
    }

    public static String buildCalendarExplainSystemPrompt(Context context) {
        return getPrompt(context, KEY_CALENDAR_EXPLAIN_SYSTEM).trim();
    }

    public static String buildCalendarExplainUserPrompt(Context context,
                                                        String originalUserInput,
                                                        CalendarResultForChat resultForChat,
                                                        String searchResultsBlock) {
        Map<String, String> values = new HashMap<>();
        values.put("calendar_result_block", buildCalendarResultBlock(resultForChat));
        values.put("search_results_block", buildSearchResultsSection(searchResultsBlock));
        values.put("user_input", safe(originalUserInput));
        return applyTemplate(getPrompt(context, KEY_CALENDAR_EXPLAIN_USER), values);
    }

    public static ExpertRouteDecision parseExpertRouteDecision(String rawResponse, List<String> enabledFeatures) {
        List<String> allowed = enabledFeatures == null ? Collections.emptyList() : enabledFeatures;
        try {
            JSONObject outer = new JSONObject(rawResponse);
            String inner = outer.optString("response", "").trim();
            if (inner.isEmpty()) {
                return new ExpertRouteDecision(Collections.emptyList(), "");
            }
            JSONObject json = new JSONObject(inner);
            JSONArray required = json.optJSONArray("required_functions");
            List<String> ordered = new ArrayList<>();
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    String id = required.optString(i, "").trim();
                    if (!ordered.contains(id) && allowed.contains(id)) {
                        ordered.add(id);
                    }
                }
            }
            return new ExpertRouteDecision(ordered, json.optString("note", ""));
        } catch (Exception ignored) {
            return new ExpertRouteDecision(Collections.emptyList(), "");
        }
    }

    public static ExpertRouteDecision createRouteDecision(List<String> orderedFunctionIds, String note) {
        return new ExpertRouteDecision(orderedFunctionIds, note);
    }

    private static String buildEnabledFeaturesBlock(List<String> enabledFeatures, String userInput) {
        List<FeatureDescriptor> descriptors = new ArrayList<>();
        if (enabledFeatures != null) {
            for (String id : enabledFeatures) {
                FeatureDescriptor descriptor = FeatureDescriptor.forId(id);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
        }
        if (descriptors.isEmpty()) {
            return "- (none)";
        }
        int seed = Math.abs((userInput == null ? "" : userInput).hashCode());
        if (descriptors.size() > 1) {
            Collections.rotate(descriptors, seed % descriptors.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < descriptors.size(); i++) {
            FeatureDescriptor descriptor = descriptors.get(i);
            String alias = descriptor.aliases[(seed + i) % descriptor.aliases.length];
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- id: ").append(descriptor.id)
                    .append(" / 呼称: ").append(alias)
                    .append(" / 用途: ").append(descriptor.description);
        }
        return sb.toString();
    }

    private static String buildReasonBlock(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "- JSON が不正です";
        }
        StringBuilder sb = new StringBuilder();
        for (String reason : reasons) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- ").append(TextUtils.isEmpty(reason) ? "不明な理由" : reason);
        }
        return sb.toString();
    }

    public static String calendarPromptKeyFor(ExpertType expertType) {
        if (expertType == ExpertType.CALENDAR_CREATE) {
            return KEY_CALENDAR_CREATE_JUDGE;
        }
        if (expertType == ExpertType.CALENDAR_UPDATE) {
            return KEY_CALENDAR_UPDATE_JUDGE;
        }
        if (expertType == ExpertType.CALENDAR_DELETE) {
            return KEY_CALENDAR_DELETE_JUDGE;
        }
        return KEY_CALENDAR_QUERY_JUDGE;
    }

    private static String getCalendarJudgePromptTemplate(Context context, ExpertType expertType) {
        String key = calendarPromptKeyFor(expertType);
        String prompt = getPrompt(context, key);
        return prompt.trim().isEmpty() ? getPromptSpec(key).getDefaultValue() : prompt;
    }

    private static String buildCalendarResultBlock(CalendarResultForChat resultForChat) {
        StringBuilder sb = new StringBuilder();
        sb.append("CALENDAR_OPERATION_RESULT:\n");
        sb.append("action: ").append(resultForChat == null ? "NONE" : resultForChat.getAction()).append("\n");
        sb.append("success: ").append(resultForChat != null && resultForChat.isSuccess()).append("\n");
        sb.append("message: ").append(resultForChat == null ? "Calendar result is unavailable." : safe(resultForChat.getMessageForSystem())).append("\n");
        sb.append("events:\n");
        List<String> summaries = resultForChat == null ? null : resultForChat.getEventSummaries();
        if (summaries == null || summaries.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (String summary : summaries) {
                sb.append("- ").append(safe(summary)).append("\n");
            }
        }
        if (resultForChat != null && (!TextUtils.isEmpty(resultForChat.getErrorType()) || !TextUtils.isEmpty(resultForChat.getErrorDetail()))) {
            sb.append("errorType: ").append(safe(resultForChat.getErrorType())).append("\n");
            sb.append("errorDetail: ").append(safe(resultForChat.getErrorDetail())).append("\n");
        }
        return sb.toString().trim();
    }

    private static String buildSearchResultsSection(String searchResultsBlock) {
        if (TextUtils.isEmpty(searchResultsBlock)) {
            return "";
        }
        return "補助的な外部情報:\n" + searchResultsBlock.trim() + "\n\n";
    }

    private static String applyTemplate(String template, Map<String, String> values) {
        String resolved = template == null ? "" : template;
        if (values == null || values.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return resolved;
    }

    private static synchronized JSONObject readJson(Context context) {
        JSONObject json = new JSONObject();
        if (context == null) {
            return json;
        }
        try (FileInputStream fis = context.openFileInput(FILE_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (sb.length() > 0) {
                json = new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    private static synchronized void writeJson(Context context, JSONObject json) {
        if (context == null || json == null) {
            return;
        }
        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
            fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class PromptSpec {
        private final String key;
        private final String labelEn;
        private final String labelJa;
        private final String defaultValue;

        private PromptSpec(String key, String labelEn, String labelJa, String defaultValue) {
            this.key = key;
            this.labelEn = labelEn;
            this.labelJa = labelJa;
            this.defaultValue = defaultValue;
        }

        public String getKey() {
            return key;
        }

        public String getLabel(String appLanguage) {
            return "ja".equals(appLanguage) ? labelJa : labelEn;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    public static final class ExpertRouteDecision {
        private final List<String> orderedFunctionIds;
        private final String note;

        private ExpertRouteDecision(List<String> orderedFunctionIds, String note) {
            this.orderedFunctionIds = orderedFunctionIds == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(orderedFunctionIds));
            this.note = note == null ? "" : note;
        }

        public List<String> getOrderedFunctionIds() {
            return orderedFunctionIds;
        }

        public String getNote() {
            return note;
        }

        public boolean requires(String featureId) {
            return orderedFunctionIds.contains(featureId);
        }
    }

    private static final class FeatureDescriptor {
        private static final Map<String, FeatureDescriptor> BY_ID;

        static {
            Map<String, FeatureDescriptor> values = new LinkedHashMap<>();
            values.put(FEATURE_WEB_SEARCH, new FeatureDescriptor(
                    FEATURE_WEB_SEARCH,
                    new String[]{"外部情報の確認", "オンライン調査", "公開Web参照"},
                    "公開Web上の最新情報や事実確認、参考情報の補完が必要な場合に使う。"
            ));
            values.put(FEATURE_CALENDAR, new FeatureDescriptor(
                    FEATURE_CALENDAR,
                    new String[]{"予定表の確認や更新", "日程管理", "スケジュール操作"},
                    "Google Calendar の照会、作成、更新、削除が必要な場合に使う。"
            ));
            BY_ID = Collections.unmodifiableMap(values);
        }

        private final String id;
        private final String[] aliases;
        private final String description;

        private FeatureDescriptor(String id, String[] aliases, String description) {
            this.id = id;
            this.aliases = aliases;
            this.description = description;
        }

        private static FeatureDescriptor forId(String id) {
            return BY_ID.get(id);
        }
    }
}
