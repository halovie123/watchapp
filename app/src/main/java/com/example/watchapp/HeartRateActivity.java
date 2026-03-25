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

    // UI
    private TextView tvHeartRate, tvStatus, tvAverage;
    private Button btnBack;
    private ChartView chartView;

    private HealthDataManager dataManager;

    // Firebase
    private DatabaseReference heartRateRef;
    private DatabaseReference healthRecordsRef;
    private String fallStatus = "no";

    // ===============================
    // BLE RECEIVER (NHẬN DỮ LIỆU THẬT)
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
    // FALL RECEIVER (NHẬN FALL THẬT)
    // ===============================
    private final BroadcastReceiver fallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if ("com.example.watchapp.FALL_DETECTED".equals(intent.getAction())) {

                double magnitude = intent.getDoubleExtra("magnitude", 0);

                Log.d(TAG, "🚨 FALL DETECTED REAL: " + magnitude);

                int bpm = getCurrentBPM();

                // 🔥 PUSH FALL = YES
                pushToFirebase(bpm, "yes");
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

        startFirebaseListener();
        refreshChart();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // BLE
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter);

        // FALL
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
    private void handleSensorData(Intent intent) {

        int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM, -1);
        int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2, -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION, 0);

        // lưu SPO2 thật
        if (spo2 > 0) {
            dataManager.saveOxygenData(spo2);
        }

        if (finger == 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Chưa đặt tay lên cảm biến");
            return;
        }

        if (bpm <= 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Đang đo...");
            return;
        }

        tvHeartRate.setText(bpm + " BPM");

        if (motion == 1)        tvStatus.setText("Đang chuyển động");
        else if (bpm < 60)      tvStatus.setText("Nhịp tim thấp");
        else if (bpm > 100)     tvStatus.setText("Nhịp tim cao");
        else                    tvStatus.setText("Bình thường");

        dataManager.saveHeartRateData(bpm);
        refreshChart();

        // 🔥 LUÔN PUSH FULL DATA (NO FALL)
        pushToFirebase(bpm, fallStatus);
    }

    // ===============================
    // PUSH FIREBASE (QUAN TRỌNG NHẤT)
    // ===============================
    private void pushToFirebase(int bpm, String fallStatus) {

        String timestamp = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());

        int lastSpo2 = dataManager.getAverageOxygen();

        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("timestamp", timestamp);
        record.put("heart_rate", bpm);
        record.put("spo2", lastSpo2 > 0 ? lastSpo2 : 0);
        record.put("fall_detection", fallStatus); // ✅ LUÔN CÓ

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ Firebase push OK"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ " + e.getMessage()));
    }

    // ===============================
    // LẤY BPM HIỆN TẠI
    // ===============================
    private int getCurrentBPM() {
        try {
            String text = tvHeartRate.getText().toString().replace(" BPM", "");
            return Integer.parseInt(text);
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

                tvHeartRate.setText(String.valueOf(bpm));
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
        if (avg > 0) {
            tvAverage.setText("Avg: " + avg + " BPM");
        } else {
            tvAverage.setText("No data");
        }
    }
}