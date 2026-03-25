package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.database.*;

import java.util.List;


public class HeartRateActivity extends BaseActivity {

    private static final String TAG = "HeartRateActivity";

    private TextView tvHeartRate, tvStatus, tvAverage;
    private Button btnBack;
    private ChartView chartView;
    private HealthDataManager dataManager;

    // Firebase
    private DatabaseReference heartRateRef;
    private DatabaseReference healthRecordsRef;
    private DatabaseReference fallStateRef;      // ✅ THÊM MỚI

    private String fallStatus = "no";


    private int lastValidBpm = 0;
    private String lastStatus = "";

    // ===============================
    // BLE RECEIVER
    // ===============================
    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case BLEService.ACTION_GATT_CONNECTED:
                    tvStatus.setText("Đã kết nối BLE");
                    break;
                case BLEService.ACTION_GATT_DISCONNECTED:
                    tvStatus.setText("Mất kết nối BLE");
                    tvHeartRate.setText("--");
                    break;
                case BLEService.ACTION_DATA_AVAILABLE:
                    handleSensorData(intent);
                    break;
            }
        }
    };

    // ===============================
    // FALL RECEIVER
    // ===============================
    private final BroadcastReceiver fallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.watchapp.FALL_DETECTED".equals(intent.getAction())) {

                double magnitude = intent.getDoubleExtra("magnitude", 0);
                Log.d(TAG, "🚨 FALL DETECTED: " + magnitude);

                fallStatus = "yes";

                int bpm = getCurrentBPM();

                // ✅ Gửi cả 2 node
                pushFallState(magnitude, "yes");
                pushHealthRecord(bpm, "yes");

                // Reset sau 5 giây
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    fallStatus = "no";
                }, 5000);
            }
        }
    };

    // ===============================
    // LIFECYCLE
    // ===============================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        dataManager = HealthDataManager.getInstance(this);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus    = findViewById(R.id.tvStatus);
        tvAverage   = findViewById(R.id.tvAverage);
        chartView   = findViewById(R.id.chartView);
        btnBack     = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        heartRateRef     = FirebaseDatabase.getInstance().getReference("heart_rate");
        healthRecordsRef = FirebaseDatabase.getInstance().getReference("health_records");
        fallStateRef     = FirebaseDatabase.getInstance().getReference("fall_state"); // ✅

        startFirebaseListener();
        refreshChart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter);

        IntentFilter fallFilter = new IntentFilter("com.example.watchapp.FALL_DETECTED");
        LocalBroadcastManager.getInstance(this).registerReceiver(fallReceiver, fallFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fallReceiver);
    }

    // ===============================
    // XỬ LÝ DỮ LIỆU BLE
    // ===============================

    private void setStatus(String newStatus) {
        if (!newStatus.equals(lastStatus)) {
            tvStatus.setText(newStatus);
            lastStatus = newStatus;
        }
    }
    private void handleSensorData(Intent intent) {
        int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM,    -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        // 👉 THẢ TAY
        if (finger == 0) {
            lastValidBpm = 0;

            setStatus("Chưa đặt tay lên cảm biến");
            tvHeartRate.setText("--");
            return;
        }

        if (bpm <= 0) {
            if (lastValidBpm == 0) {
                setStatus("Đang đo...");
                tvHeartRate.setText("--");
            } else {
                tvHeartRate.setText(lastValidBpm + " BPM");
                // ❌ KHÔNG update status nữa
            }
            return;
        }

        lastValidBpm = bpm;

        if (motion == 1)    setStatus("Đang chuyển động");
        else if (bpm < 60)  setStatus("Nhịp tim thấp");
        else if (bpm > 100) setStatus("Nhịp tim cao");
        else                setStatus("Bình thường");

        tvHeartRate.setText(bpm + " BPM");
    }
    // ===============================
    // PUSH fall_state
    // {magnitude, state, timestamp}
    // ===============================
    private void pushFallState(double magnitude, String state) {
        String timestamp = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());

        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("magnitude", magnitude);
        record.put("state",     state);
        record.put("timestamp", timestamp);

        fallStateRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ fall_state OK: " + state))
                .addOnFailureListener(e -> Log.e(TAG, "❌ fall_state FAIL: " + e.getMessage()));
    }

    // ===============================
    // PUSH health_records
    // {fall_detection, heart_rate, spo2, timestamp}
    // ===============================
    private void pushHealthRecord(int bpm, String fallDetection) {
        String timestamp = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());

        int lastSpo2 = dataManager.getAverageOxygen();

        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("fall_detection", fallDetection);
        record.put("heart_rate",     bpm);
        record.put("spo2",           lastSpo2 > 0 ? lastSpo2 : 0);
        record.put("timestamp",      timestamp);

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ health_records OK: HR=" + bpm))
                .addOnFailureListener(e -> Log.e(TAG, "❌ health_records FAIL: " + e.getMessage()));
    }

    // ===============================
    // LẤY BPM HIỆN TẠI
    // ===============================
    private int getCurrentBPM() {
        try {
            return Integer.parseInt(tvHeartRate.getText().toString().replace(" BPM", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ===============================
    // FIREBASE LISTENER
    // ===============================
    private void startFirebaseListener() {
        heartRateRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer bpm = snapshot.child("value").getValue(Integer.class);
                if (bpm == null) return;
//                tvHeartRate.setText(String.valueOf(bpm));
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvStatus.setText("Lỗi Firebase");
            }
        });
    }

    // ===============================
    // CHART
    // ===============================
    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getHeartRateData();
        chartView.setData(data, 0xFFE53935, 40, 180);
        int avg = dataManager.getAverageHeartRate();
        tvAverage.setText(avg > 0 ? "Avg: " + avg + " BPM" : "No data");
    }
}