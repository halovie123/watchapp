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
    private NumberPicker pickerMinute, pickerSecond;
    private Button btnTimeOk;
    private RadioGroup radioGroupLanguage;
    private TextView tvWatchName, tvWatchVersion;
    private List<String> emergencyContacts;
    private boolean isLoadingSettings = false; // Flag để tránh trigger listener khi load


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        emergencyContacts = new ArrayList<>();

        initViews();
        loadSettings(); // Load trước khi setup listener
        setupListeners(); // Setup listener sau
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
            String message = getString(R.string.saved_check_cycle, minute, second);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });

        radioGroupLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            // QUAN TRỌNG: Chỉ xử lý khi KHÔNG đang load settings
            if (isLoadingSettings) {
                return;
            }

            // Lấy ngôn ngữ hiện tại
            String currentLanguage = LocaleHelper.getPersistedLanguage(this);
            String newLanguage;

            if (checkedId == R.id.radioVietnamese) {
                newLanguage = "vi";
            } else if (checkedId == R.id.radioEnglish) {
                newLanguage = "en";
            } else {
                return; // Không làm gì nếu không phải 2 nút này
            }

            // QUAN TRỌNG: Chỉ recreate nếu ngôn ngữ THẬT SỰ thay đổi
            if (!currentLanguage.equals(newLanguage)) {
                // Lưu ngôn ngữ mới
                LocaleHelper.setLocale(this, newLanguage);

                // Hiển thị toast
                String toastMessage = newLanguage.equals("vi")
                        ? "Đã chọn Tiếng Việt"
                        : "Selected English";
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();

                // Delay một chút để toast hiển thị trước khi recreate
                new android.os.Handler().postDelayed(() -> {
                    recreate();
                }, 300);
            }
        });
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
        // BẬT flag để tránh trigger listener
        isLoadingSettings = true;

        // Load từ SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);

        // Load language
        String language = LocaleHelper.getPersistedLanguage(this);
        if (language.equals("vi")) {
            ((RadioButton) findViewById(R.id.radioVietnamese)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.radioEnglish)).setChecked(true);
        }

        // Load watch info
        String watchName = prefs.getString("watchName", "SmartWatch-3CG");
        String watchVersion = prefs.getString("watchVersion", "v1.1.0");

        tvWatchName.setText(getString(R.string.watch_name, watchName));
        tvWatchVersion.setText(getString(R.string.watch_version, watchVersion));

        // Load time (phút và giây)
        int minute = prefs.getInt("checkMinute", 30);
        int second = prefs.getInt("checkSecond", 0);
        pickerMinute.setValue(minute);
        pickerSecond.setValue(second);

        // TẮT flag sau khi load xong
        isLoadingSettings = false;
    }

    private void saveCheckTime(int minute, int second) {
        android.content.SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);
        prefs.edit()
                .putInt("checkMinute", minute)
                .putInt("checkSecond", second)
                .apply();
    }
}