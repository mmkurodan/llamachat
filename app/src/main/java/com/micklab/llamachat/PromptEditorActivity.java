package com.micklab.llamachat;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class PromptEditorActivity extends Activity {
    private Spinner spinnerPromptType;
    private EditText etPromptBody;
    private TextView tvPromptEditorTitle;
    private Button btnPromptSave;
    private final List<ExpertPromptStore.PromptSpec> promptSpecs = new ArrayList<>();
    private String appLanguage = "en";
    private String currentPromptKey = ExpertPromptStore.KEY_WEB_SEARCH_KEYWORD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt_editor);

        appLanguage = getIntent() != null ? getIntent().getStringExtra("appLanguage") : "en";
        if (appLanguage == null || appLanguage.trim().isEmpty()) {
            appLanguage = "en";
        }

        spinnerPromptType = findViewById(R.id.spinnerPromptType);
        etPromptBody = findViewById(R.id.etPromptBody);
        tvPromptEditorTitle = findViewById(R.id.tvPromptEditorTitle);
        btnPromptSave = findViewById(R.id.btnPromptSave);

        if (tvPromptEditorTitle != null) {
            tvPromptEditorTitle.setText(t("Prompt Editor", "プロンプト編集"));
        }
        if (btnPromptSave != null) {
            btnPromptSave.setText(t("Save", "保存"));
        }
        if (etPromptBody != null) {
            etPromptBody.setHint(t("Edit the selected prompt", "選択中のプロンプトを編集"));
            etPromptBody.setTextIsSelectable(true);
            etPromptBody.setLongClickable(true);
        }

        promptSpecs.clear();
        promptSpecs.addAll(ExpertPromptStore.getPromptSpecs());

        List<String> labels = new ArrayList<>();
        for (ExpertPromptStore.PromptSpec spec : promptSpecs) {
            labels.add(spec.getLabel(appLanguage));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPromptType.setAdapter(adapter);
        spinnerPromptType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= promptSpecs.size()) {
                    return;
                }
                currentPromptKey = promptSpecs.get(position).getKey();
                loadCurrentPrompt();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        if (btnPromptSave != null) {
            btnPromptSave.setOnClickListener(v -> {
                Editable editable = etPromptBody != null ? etPromptBody.getText() : null;
                String value = editable == null ? "" : editable.toString();
                ExpertPromptStore.savePrompt(this, currentPromptKey, value);
                Toast.makeText(this, t("Saved", "保存しました"), Toast.LENGTH_SHORT).show();
            });
        }

        loadCurrentPrompt();
    }

    private void loadCurrentPrompt() {
        if (etPromptBody == null) {
            return;
        }
        etPromptBody.setText(ExpertPromptStore.getPrompt(this, currentPromptKey));
        etPromptBody.setSelection(etPromptBody.getText().length());
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }
}
