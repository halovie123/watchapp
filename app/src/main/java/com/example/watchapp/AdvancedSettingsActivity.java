package com.example.watchapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.cardview.widget.CardView;
import java.util.ArrayList;
import java.util.List;

public class AdvancedSettingsActivity extends BaseActivity {
    private LinearLayout contactsContainer;
    private Button btnAddContact;
    private Button btnBack;
    private RadioGroup radioGroupLanguage;
    private TextView tvWatchName, tvWatchVersion;
    private List<String> emergencyContacts;
    private boolean isLoadingSettings = false; // Flag để tránh trigger listener khi load
    private boolean isRecreating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        emergencyContacts = new ArrayList<>();

        initViews();
        setupLanguageListener();
        loadSettings();
        setupListeners();
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        contactsContainer = findViewById(R.id.contactsContainer);
        btnAddContact = findViewById(R.id.btnAddContact);
        radioGroupLanguage = findViewById(R.id.radioGroupLanguage);
        tvWatchName = findViewById(R.id.tvWatchName);
        tvWatchVersion = findViewById(R.id.tvWatchVersion);



    }



    private void addContactField() {
        View contactView = getLayoutInflater().inflate(R.layout.item_contact, contactsContainer, false);

        EditText etContact = contactView.findViewById(R.id.etContact);
        ImageButton btnDelete = contactView.findViewById(R.id.btnDeleteContact);

        btnDelete.setOnClickListener(v -> {
            contactsContainer.removeView(contactView);
            Toast.makeText(this, R.string.contact_deleted, Toast.LENGTH_SHORT).show();
        });

        contactsContainer.addView(contactView);
    }

    private void loadSettings() {
        String language = LocaleHelper.getPersistedLanguage(this);

        if (language.equals("vi")) {
            radioGroupLanguage.check(R.id.radioVietnamese);
        } else {
            radioGroupLanguage.check(R.id.radioEnglish);
        }

        // Load watch info...
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);

        String watchName = prefs.getString("watchName", "SmartWatch-3CG");
        String watchVersion = prefs.getString("watchVersion", "v1.1.0");

        tvWatchName.setText(getString(R.string.watch_name, watchName));
        tvWatchVersion.setText(getString(R.string.watch_version, watchVersion));

        isInitializing = false;
    }

    private boolean isInitializing = true;

    private void setupLanguageListener() {
        radioGroupLanguage.setOnCheckedChangeListener((group, checkedId) -> {

            if (isInitializing) return; // ✅ CHẶN INIT

            String currentLanguage = LocaleHelper.getPersistedLanguage(this);
            String newLanguage;

            if (checkedId == R.id.radioVietnamese) {
                newLanguage = "vi";
            } else if (checkedId == R.id.radioEnglish) {
                newLanguage = "en";
            } else return;

            if (!currentLanguage.equals(newLanguage)) {
                LocaleHelper.setLocale(this, newLanguage);

                String toastMessage = newLanguage.equals("vi")
                        ? "Đã chọn Tiếng Việt"
                        : "Selected English";

                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();

                recreate(); // ✅ chỉ chạy khi user click thật
            }
        });
    }

    private void saveCheckTime(int minute, int second) {
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);
        prefs.edit()
                .putInt("checkMinute", minute)
                .putInt("checkSecond", second)
                .apply();
    }
}