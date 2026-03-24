package com.example.watchapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class AdvancedSettingsActivity extends BaseActivity {

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final String KEY_EMERGENCY_EMAILS = "emergencyEmails";
    private static final String PREFS_NAME = "WatchSettings";

    // ── Views ─────────────────────────────────────────────────────────────────
    private LinearLayout contactsContainer;
    private Button btnAddContact;
    private Button btnBack;
    private RadioGroup radioGroupLanguage;
    private TextView tvWatchName, tvWatchVersion;

    // ── State ─────────────────────────────────────────────────────────────────
    private List<String> emergencyEmails = new ArrayList<>();
    private boolean isLoadingSettings = false;

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        initViews();
        loadSettings();
        setupListeners();
    }

    // =========================================================================
    //  INIT
    // =========================================================================

    private void initViews() {
        btnBack           = findViewById(R.id.btnBack);
        contactsContainer = findViewById(R.id.contactsContainer);
        btnAddContact     = findViewById(R.id.btnAddContact);
        radioGroupLanguage= findViewById(R.id.radioGroupLanguage);
        tvWatchName       = findViewById(R.id.tvWatchName);
        tvWatchVersion    = findViewById(R.id.tvWatchVersion);
    }

    // =========================================================================
    //  LISTENERS
    // =========================================================================

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        // Thêm ô email mới — chưa lưu cho đến khi user bấm ✅ trên ô đó
        btnAddContact.setOnClickListener(v -> addContactField(""));

        // Ngôn ngữ
        radioGroupLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            if (isLoadingSettings) return;

            String currentLanguage = LocaleHelper.getPersistedLanguage(this);
            String newLanguage;

            if      (checkedId == R.id.radioVietnamese) newLanguage = "vi";
            else if (checkedId == R.id.radioEnglish)    newLanguage = "en";
            else return;

            if (!currentLanguage.equals(newLanguage)) {
                LocaleHelper.setLocale(this, newLanguage);
                String msg = newLanguage.equals("vi") ? "Đã chọn Tiếng Việt" : "Selected English";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                new android.os.Handler().postDelayed(this::recreate, 300);
            }
        });
    }

    // =========================================================================
    //  LOAD / SAVE — SharedPreferences
    // =========================================================================

    private void loadSettings() {
        isLoadingSettings = true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Ngôn ngữ
        String language = LocaleHelper.getPersistedLanguage(this);
        ((RadioButton) findViewById(language.equals("vi")
                ? R.id.radioVietnamese : R.id.radioEnglish)).setChecked(true);

        // Thông tin đồng hồ
        String watchName    = prefs.getString("watchName",    "SmartWatch-3CG");
        String watchVersion = prefs.getString("watchVersion", "v1.1.0");
        tvWatchName.setText(getString(R.string.watch_name, watchName));
        tvWatchVersion.setText(getString(R.string.watch_version, watchVersion));

        // Contacts — load từ JSON
        String json = prefs.getString(KEY_EMERGENCY_EMAILS, null);
        if (json != null) {
            Type type = new TypeToken<List<String>>() {}.getType();
            List<String> saved = new Gson().fromJson(json, type);
            if (saved != null) emergencyEmails = saved;
        }

        // Render lên UI
        contactsContainer.removeAllViews();
        for (String email : emergencyEmails) {
            addContactField(email);
        }

        isLoadingSettings = false;
    }

    /** Lưu toàn bộ emergencyEmails vào SharedPreferences */
    private void saveContacts() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_EMERGENCY_EMAILS, new Gson().toJson(emergencyEmails))
                .apply();
    }

    // =========================================================================
    //  CONTACT FIELD — mỗi hàng: [📧 EditText]  [✅ Lưu]  [🗑️ Xóa]
    // =========================================================================

    private void addContactField(String email) {
        View contactView = getLayoutInflater()
                .inflate(R.layout.item_contact, contactsContainer, false);

        EditText    etContact = contactView.findViewById(R.id.etContact);
        ImageButton btnSave   = contactView.findViewById(R.id.btnSaveContact);
        ImageButton btnDelete = contactView.findViewById(R.id.btnDeleteContact);

        etContact.setText(email);

        // ── Nút ✅ LƯU ────────────────────────────────────────────────────────
        btnSave.setOnClickListener(v -> {
            String input = etContact.getText().toString().trim();

            // Validate không rỗng
            if (input.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập email!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate định dạng email
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(input).matches()) {
                Toast.makeText(this, "Email không hợp lệ!", Toast.LENGTH_SHORT).show();
                return;
            }

            int pos = contactsContainer.indexOfChild(contactView);

            if (pos >= 0 && pos < emergencyEmails.size()) {
                // Cập nhật email đã có sẵn trong danh sách
                emergencyEmails.set(pos, input);
            } else {
                // Email mới (user vừa bấm "Thêm người thân") → thêm vào list
                emergencyEmails.add(input);
            }

            saveContacts();
            Toast.makeText(this, "Đã lưu: " + input, Toast.LENGTH_SHORT).show();

            // Ẩn bàn phím
            etContact.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etContact.getWindowToken(), 0);
        });

        // ── Nút 🗑️ XÓA ───────────────────────────────────────────────────────
        btnDelete.setOnClickListener(v -> {
            int pos = contactsContainer.indexOfChild(contactView);
            if (pos >= 0 && pos < emergencyEmails.size()) {
                emergencyEmails.remove(pos);
                saveContacts();
            }
            contactsContainer.removeView(contactView);
            Toast.makeText(this, R.string.contact_deleted, Toast.LENGTH_SHORT).show();
        });

        contactsContainer.addView(contactView);
    }

    // =========================================================================
    //  STATIC HELPER — gọi từ MainActivity để lấy email gửi cảnh báo té ngã
    // =========================================================================

    public static List<String> getEmergencyEmails(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_EMERGENCY_EMAILS, null);
        if (json == null) return new ArrayList<>();
        Type type = new TypeToken<List<String>>() {}.getType();
        List<String> list = new Gson().fromJson(json, type);
        return list != null ? list : new ArrayList<>();
    }
}