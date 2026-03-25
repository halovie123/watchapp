package com.example.watchapp;

import androidx.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.List;
import java.util.Random;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class OxygenActivity extends BaseActivity {
    private static final String TAG = "OxygenActivity";
    // UI
    private TextView tvOxygenLevel, tvStatus, tvAverage;
    private ChartView chartView;
    private Button btnMeasure, btnBack;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random = new Random();
    private HealthDataManager dataManager;
    // Firebase
    private DatabaseReference oxygenRef;
    private ValueEventListener oxygenListener;
    private DatabaseReference healthRecordsRef;
    private int lastValidSpo2 = 0;
    private String lastStatus = "";
    private Runnable fingerLostRunnable;


    // ─── BLE Broadcast Receiver ──────────────────────────────
    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case BLEService.ACTION_GATT_CONNECTED:
                    tvStatus.setText(R.string.ble_connected);
                    break;

                case BLEService.ACTION_GATT_DISCONNECTED:
                    tvStatus.setText(R.string.ble_disconnected);
                    tvOxygenLevel.setText("--");
                    break;

                case BLEService.ACTION_DATA_AVAILABLE:
                    handleSensorData(intent);
                    break;
            }
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oxygen);

        dataManager = HealthDataManager.getInstance(this);

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus = findViewById(R.id.tvStatus);
        tvAverage     = findViewById(R.id.tvAverage);
        chartView     = findViewById(R.id.chartView);
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Render stored history on entry
        refreshChart();

        // Lắng nghe Firebase — dữ liệu do MainActivity gửi lên mỗi 3s
        oxygenRef = FirebaseDatabase.getInstance().getReference("oxygen_level");
        healthRecordsRef = FirebaseDatabase.getInstance().getReference("health_records");
        startFirebaseListener();

    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
    }
    private void setStatus(String newStatus) {
        if (!newStatus.equals(lastStatus)) {
            tvStatus.setText(newStatus);
            lastStatus = newStatus;
        }
    }
    // ─── Data handling ────────────────────────────────────────
    private void handleSensorData(Intent intent) {
        int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2,   -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        // 👉 MẤT TAY (delay 500ms chống nháy)
        if (finger == 0) {

            if (fingerLostRunnable != null) {
                handler.removeCallbacks(fingerLostRunnable);
            }

            fingerLostRunnable = () -> {
                lastValidSpo2 = 0;
                setStatus("Chưa đặt tay lên cảm biến");
                tvOxygenLevel.setText("--");
            };

            handler.postDelayed(fingerLostRunnable, 500);
            return;
        }

        // 👉 CÓ TAY LẠI → hủy reset
        if (fingerLostRunnable != null) {
            handler.removeCallbacks(fingerLostRunnable);
        }

        // 👉 CHƯA CÓ DỮ LIỆU
        if (spo2 <= 0) {
            if (lastValidSpo2 == 0) {
                setStatus("Đang đo nồng độ oxy...");
                tvOxygenLevel.setText("--");
            } else {
                tvOxygenLevel.setText(lastValidSpo2 + "%");
            }
            return;
        }

        // 👉 DỮ LIỆU HỢP LỆ
        lastValidSpo2 = spo2;

        if (motion == 1) {
            setStatus("Cảnh báo: đang chuyển động");
        } else if (spo2 < 90) {
            setStatus("Nồng độ oxy nguy hiểm!");
        } else if (spo2 < 95) {
            setStatus("Nồng độ oxy thấp");
        } else {
            setStatus("Nồng độ oxy bình thường");
        }

        tvOxygenLevel.setText(spo2 + "%");

        dataManager.saveOxygenData(spo2);
        refreshChart();
    }

    private void pushToFirebase(int spo2) {
        String timestamp = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());

        int lastBpm = dataManager.getAverageHeartRate(); // dùng giá trị bpm mới nhất từ local

        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("timestamp", timestamp);
        record.put("heart_rate", lastBpm > 0 ? lastBpm : 0);
        record.put("spo2", spo2);
        record.put("fall_detection", random.nextBoolean() ? "yes" : "no");

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ Firebase SpO2=" + spo2))
                .addOnFailureListener(e -> Log.e(TAG, "❌ " + e.getMessage()));
    }

    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getOxygenData();

        // Chart: blue line, range 85–100 %
        chartView.setData(data, 0xFF1E88E5, 85, 100);

        // Average
        int avg = dataManager.getAverageOxygen();
        if (avg > 0) {
            tvAverage.setText(getString(R.string.average_value, avg) + "%");
        } else {
            tvAverage.setText(R.string.no_data);
        }
    }

    private void startFirebaseListener() {
        tvStatus.setText("⏳ Đang chờ dữ liệu...");

        oxygenListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvOxygenLevel.setText("--");
                    tvStatus.setText("Chưa có dữ liệu");
                    return;
                }

                Integer spo2 = snapshot.child("value").getValue(Integer.class);
                if (spo2 == null) return;

                Log.d(TAG, "Firebase → " + spo2 + "%");

                // Hiển thị lên vòng tròn
                tvOxygenLevel.setText(String.valueOf(spo2));

                // Trạng thái
                if      (spo2 < 95) tvStatus.setText("Nồng độ oxy thấp!");
                else if (spo2 < 97) tvStatus.setText("Nồng độ oxy hơi thấp");
                else                tvStatus.setText("Nồng độ oxy bình thường");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Lỗi: " + error.getMessage());
                tvStatus.setText("Lỗi kết nối Firebase");
            }
        };

        oxygenRef.addValueEventListener(oxygenListener);
    }





}