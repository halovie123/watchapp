package com.example.watchapp;

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
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import java.util.ArrayList;
import java.util.List;

public class AdvancedSettingsActivity extends AppCompatActivity {
    private LinearLayout contactsContainer;
    private Button btnAddContact;
    private Button btnBack;
    private NumberPicker pickerMinute, pickerSecond;
    private Button btnTimeOk;
    private RadioGroup radioGroupLanguage;
    private TextView tvWatchName, tvWatchVersion;
    private List<String> emergencyContacts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        emergencyContacts = new ArrayList<>();

        initViews();
        setupListeners();
        loadSettings();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        contactsContainer = findViewById(R.id.contactsContainer);
        btnAddContact = findViewById(R.id.btnAddContact);
        pickerMinute = findViewById(R.id.pickerMinute);
        pickerSecond = findViewById(R.id.pickerSecond);
        btnTimeOk = findViewById(R.id.btnTimeOk);
        radioGroupLanguage = findViewById(R.id.radioGroupLanguage);
        tvWatchName = findViewById(R.id.tvWatchName);
        tvWatchVersion = findViewById(R.id.tvWatchVersion);

        // Cấu hình NumberPicker cho Phút (0-59)
        pickerMinute.setMinValue(0);
        pickerMinute.setMaxValue(59);
        pickerMinute.setWrapSelectorWheel(true);

        // Cấu hình NumberPicker cho Giây (0-59)
        pickerSecond.setMinValue(0);
        pickerSecond.setMaxValue(59);
        pickerSecond.setWrapSelectorWheel(true);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnAddContact.setOnClickListener(v -> addContactField());

        btnTimeOk.setOnClickListener(v -> {
            int minute = pickerMinute.getValue();
            int second = pickerSecond.getValue();
            saveCheckTime(minute, second);
            Toast.makeText(this, "Đã lưu chu kỳ đo: " + minute + " phút " + second + " giây",
                    Toast.LENGTH_SHORT).show();
        });

        radioGroupLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioVietnamese) {
                saveLanguage("vi");
                Toast.makeText(this, "Đã chọn Tiếng Việt", Toast.LENGTH_SHORT).show();
            } else if (checkedId == R.id.radioEnglish) {
                saveLanguage("en");
                Toast.makeText(this, "Selected English", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addContactField() {
        View contactView = getLayoutInflater().inflate(R.layout.item_contact, contactsContainer, false);

        EditText etContact = contactView.findViewById(R.id.etContact);
        ImageButton btnDelete = contactView.findViewById(R.id.btnDeleteContact);

        btnDelete.setOnClickListener(v -> {
            contactsContainer.removeView(contactView);
            Toast.makeText(this, "Đã xóa người thân", Toast.LENGTH_SHORT).show();
        });

        contactsContainer.addView(contactView);
    }

    private void loadSettings() {
        // Load từ SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);

        // Load language
        String language = prefs.getString("language", "vi");
        if (language.equals("vi")) {
            ((RadioButton) findViewById(R.id.radioVietnamese)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.radioEnglish)).setChecked(true);
        }

        // Load watch info
        tvWatchName.setText("Tên đồng hồ: " + prefs.getString("watchName", "SmartWatch-1907"));
        tvWatchVersion.setText("Phiên bản: " + prefs.getString("watchVersion", "v1.1.0"));

        // Load time (phút và giây)
        int minute = prefs.getInt("checkMinute", 30);
        int second = prefs.getInt("checkSecond", 0);
        pickerMinute.setValue(minute);
        pickerSecond.setValue(second);
    }

    private void saveCheckTime(int minute, int second) {
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);
        prefs.edit()
                .putInt("checkMinute", minute)
                .putInt("checkSecond", second)
                .apply();
    }

    private void saveLanguage(String language) {
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);
        prefs.edit()
                .putString("language", language)
                .apply();
    }
}