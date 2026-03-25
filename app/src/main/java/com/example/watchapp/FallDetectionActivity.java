package com.example.watchapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FallDetectionActivity extends BaseActivity {
    private static final String TAG = "FallDetectionActivity";

    // ── Views ──────────────────────────────────────────────────────────────────
    private Button btnBack;
    private SwitchCompat switchFallDetection;
    private TextView tvStatusBadge;
    private TextView tvTotalFalls, tvConfirmedFalls, tvCancelledFalls;
    private LinearLayout fallHistoryContainer;
    private LinearLayout emptyHistoryView;
    private Button btnClearHistory;

    // ── Data ───────────────────────────────────────────────────────────────────
    private List<FallEvent> fallHistory = new ArrayList<>();
    private boolean isLoadingSettings = false; // tương tự AdvancedSettingsActivity

    private static final String PREFS_NAME  = "WatchSettings";
    private static final String KEY_ENABLED = "fallDetectionEnabled";
    private static final String KEY_HISTORY = "fallHistory";

    // ==========================================================================
    //  LIFECYCLE
    // ==========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fall_detection);

        initViews();
        loadSettings();   // load trước
        setupListeners(); // setup listener sau — giống AdvancedSettingsActivity
    }

    // ==========================================================================
    //  INIT
    // ==========================================================================

    private void initViews() {
        btnBack             = findViewById(R.id.btnBack);
        switchFallDetection = findViewById(R.id.switchFallDetection);
        tvStatusBadge       = findViewById(R.id.tvStatusBadge);
        tvTotalFalls        = findViewById(R.id.tvTotalFalls);
        tvConfirmedFalls    = findViewById(R.id.tvConfirmedFalls);
        tvCancelledFalls    = findViewById(R.id.tvCancelledFalls);
        fallHistoryContainer= findViewById(R.id.fallHistoryContainer);
        emptyHistoryView    = findViewById(R.id.emptyHistoryView);
        btnClearHistory     = findViewById(R.id.btnClearHistory);
    }

    // ==========================================================================
    //  LOAD / SAVE  (SharedPreferences — cùng file "WatchSettings" với Advanced)
    // ==========================================================================

    private void loadSettings() {
        isLoadingSettings = true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Trạng thái switch
        boolean enabled = prefs.getBoolean(KEY_ENABLED, true);
        switchFallDetection.setChecked(enabled);
        updateStatusBadge(enabled);

        // Lịch sử từ JSON
        String json = prefs.getString(KEY_HISTORY, null);
        if (json != null) {
            Type type = new TypeToken<List<FallEvent>>() {}.getType();
            List<FallEvent> saved = new Gson().fromJson(json, type);
            if (saved != null) fallHistory = saved;
        }

        refreshHistory();

        isLoadingSettings = false;
    }

    private void saveEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private void saveHistory() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, new Gson().toJson(fallHistory))
                .apply();
    }

    // ==========================================================================
    //  LISTENERS
    // ==========================================================================

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        switchFallDetection.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isLoadingSettings) return; // tránh trigger khi load — giống Advanced

            saveEnabled(isChecked);
            updateStatusBadge(isChecked);

            String msg = isChecked
                    ? getString(R.string.fall_detection_turned_on)
                    : getString(R.string.fall_detection_turned_off);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        btnClearHistory.setOnClickListener(v -> confirmClearHistory());
    }

    // ==========================================================================
    //  UI HELPERS
    // ==========================================================================

    private void updateStatusBadge(boolean enabled) {
        if (enabled) {
            tvStatusBadge.setText(getString(R.string.fall_detection_active));
            tvStatusBadge.setBackgroundResource(R.drawable.badge_active_bg);
        } else {
            tvStatusBadge.setText(getString(R.string.fall_detection_inactive));
            tvStatusBadge.setBackgroundResource(R.drawable.badge_inactive_bg);
        }
    }

    /** Vẽ lại toàn bộ danh sách lịch sử */
    private void refreshHistory() {
        fallHistoryContainer.removeAllViews();

        if (fallHistory.isEmpty()) {
            emptyHistoryView.setVisibility(View.VISIBLE);
        } else {
            emptyHistoryView.setVisibility(View.GONE);
            // Hiển thị mới nhất lên trên
            for (int i = fallHistory.size() - 1; i >= 0; i--) {
                boolean hideDivider = (i == 0);
                addHistoryRow(fallHistory.get(i), hideDivider);
            }
        }

        updateStats();
    }

    private void addHistoryRow(FallEvent event, boolean hideDivider) {
        View row = getLayoutInflater().inflate(R.layout.item_fall_history, fallHistoryContainer, false);

        TextView tvDateTime  = row.findViewById(R.id.tvFallDateTime);
        TextView tvMagnitude = row.findViewById(R.id.tvFallMagnitude);
        TextView tvStatus    = row.findViewById(R.id.tvFallStatus);
        View     divider     = row.findViewById(R.id.dividerFallItem);

        tvDateTime.setText(event.dateTime);
        tvMagnitude.setText(getString(R.string.fall_magnitude, event.magnitude));

        boolean confirmed = FallEvent.STATUS_CONFIRMED.equals(event.status);
        tvStatus.setText(confirmed
                ? getString(R.string.fall_status_confirmed)
                : getString(R.string.fall_status_cancelled));
        tvStatus.setBackgroundResource(confirmed
                ? R.drawable.badge_confirmed_bg
                : R.drawable.badge_cancelled_bg);

        if (hideDivider) divider.setVisibility(View.GONE);

        fallHistoryContainer.addView(row);
    }

    private void updateStats() {
        int confirmed = 0, cancelled = 0;
        for (FallEvent e : fallHistory) {
            if (FallEvent.STATUS_CONFIRMED.equals(e.status)) confirmed++;
            else cancelled++;
        }
        tvTotalFalls.setText(String.valueOf(fallHistory.size()));
        tvConfirmedFalls.setText(String.valueOf(confirmed));
        tvCancelledFalls.setText(String.valueOf(cancelled));
    }

    private void confirmClearHistory() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.fall_clear_history))
                .setMessage(getString(R.string.fall_clear_history_confirm))
                .setPositiveButton(getString(R.string.confirm), (d, w) -> {
                    fallHistory.clear();
                    saveHistory();
                    refreshHistory();
                    Toast.makeText(this, R.string.fall_history_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    // ==========================================================================
    //  PUBLIC API — gọi từ MainActivity khi nhận FALL_DETECTED broadcast
    // ==========================================================================

    /**
     * Ghi một sự kiện té ngã mới.
     * Gọi sau khi người dùng nhấn "Tôi ổn" (confirmed=false)
     * hoặc "Gọi khẩn cấp" (confirmed=true) trong AlertDialog của MainActivity.
     */
    public void recordFallEvent(double magnitude, boolean confirmed) {
        String dateTime = new SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale.getDefault())
                .format(new Date());
        fallHistory.add(new FallEvent(
                dateTime,
                magnitude,
                confirmed ? FallEvent.STATUS_CONFIRMED : FallEvent.STATUS_CANCELLED
        ));
        saveHistory();
        refreshHistory();
    }

    // ==========================================================================
    //  MODEL
    // ==========================================================================

    public static class FallEvent {
        public static final String STATUS_CONFIRMED = "CONFIRMED";
        public static final String STATUS_CANCELLED = "CANCELLED";

        public String dateTime;
        public double magnitude;
        public String status;

        public FallEvent() {}

        public FallEvent(String dateTime, double magnitude, String status) {
            this.dateTime  = dateTime;
            this.magnitude = magnitude;
            this.status    = status;
        }
    }
}