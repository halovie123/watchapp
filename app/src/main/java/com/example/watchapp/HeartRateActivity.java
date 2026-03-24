package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;
import java.util.Random;

public class HeartRateActivity extends BaseActivity implements SensorEventListener {
    private static final String TAG = "HeartRateActivity";

    // ── UI ────────────────────────────────────────────────────────────────────
    private TextView tvHeartRate, tvStatus, tvAverage;
    private Button btnMeasure, btnBack;
    private ChartView chartView;

    // ── Sensor ────────────────────────────────────────────────────────────────
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random = new Random();
    private HealthDataManager dataManager;

    // ── Random fall detection ─────────────────────────────────────────────────
    private String currentFallState  = "no";
    private String previousFallState = "no"; // ← THÊM MỚI: theo dõi trạng thái trước đó
    private long   lastChangeTime    = 0;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private DatabaseReference heartRateRef;
    private ValueEventListener heartRateListener;
    private DatabaseReference healthRecordsRef;

    // =========================================================================
    //  BLE Broadcast Receiver (giữ nguyên)
    // =========================================================================

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
                    tvHeartRate.setText("--");
                    break;
                case BLEService.ACTION_DATA_AVAILABLE:
                    handleSensorData(intent);
                    break;
            }
        }
    };

    // =========================================================================
    //  LIFECYCLE (giữ nguyên)
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        dataManager = HealthDataManager.getInstance(this);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus    = findViewById(R.id.tvStatus);
        btnBack     = findViewById(R.id.btnBack);
        tvAverage   = findViewById(R.id.tvAverage);
        chartView   = findViewById(R.id.chartView);

        sensorManager  = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        refreshChart();

        heartRateRef    = FirebaseDatabase.getInstance().getReference("heart_rate");
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

        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
    }

    // =========================================================================
    //  FIREBASE LISTENER (giữ nguyên)
    // =========================================================================

    private void startFirebaseListener() {
        tvStatus.setText("Đang chờ dữ liệu...");

        heartRateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvHeartRate.setText("--");
                    tvStatus.setText("Chưa có dữ liệu");
                    return;
                }
                Integer bpm = snapshot.child("value").getValue(Integer.class);
                if (bpm == null) return;

                Log.d(TAG, "Firebase → " + bpm + " BPM");
                tvHeartRate.setText(String.valueOf(bpm));

                if      (bpm < 60)  tvStatus.setText("Nhịp tim thấp");
                else if (bpm > 100) tvStatus.setText("Nhịp tim cao");
                else                tvStatus.setText("Nhịp tim bình thường");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Lỗi: " + error.getMessage());
                tvStatus.setText("Lỗi kết nối Firebase");
            }
        };

        heartRateRef.addValueEventListener(heartRateListener);
    }

    // =========================================================================
    //  RANDOM FALL DETECTION (giữ nguyên logic, thêm transition detection)
    // =========================================================================

    /**
     * Trả về trạng thái giả lập:
     *  - "yes" trong 30 giây
     *  - "no"  trong 10 giây
     * Cứ thế luân phiên.
     */
    private String getFallStateControlled() {
        long now      = System.currentTimeMillis();
        long duration = currentFallState.equals("yes") ? 1000 : 30000;

        if (now - lastChangeTime > duration) {
            currentFallState = currentFallState.equals("yes") ? "no" : "yes";
            lastChangeTime   = now;
        }

        return currentFallState;
    }

    // =========================================================================
    //  DATA HANDLING (giữ nguyên)
    // =========================================================================

    private void handleSensorData(Intent intent) {
        int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM,    -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        if (finger == 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Chưa đặt tay lên cảm biến");
            return;
        }
        if (bpm <= 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Đang đo nhịp tim...");
            return;
        }

        tvHeartRate.setText(bpm + " BPM");

        if (motion == 1)        tvStatus.setText("Cảnh báo: đang chuyển động");
        else if (bpm < 60)      tvStatus.setText("Nhịp tim thấp");
        else if (bpm > 100)     tvStatus.setText("Nhịp tim cao");
        else                    tvStatus.setText("Nhịp tim bình thường");

        dataManager.saveHeartRateData(bpm);
        refreshChart();
        pushToFirebase(bpm);
    }

    /**
     * Đẩy bản ghi lên Firebase.
     *
     * ── THAY ĐỔI SO VỚI FILE GỐC ──────────────────────────────────────────────
     * Sau khi lấy currentFallState, so sánh với previousFallState.
     * Nếu chuyển từ "no" → "yes" (tức là vừa phát hiện té):
     *   → bắn LocalBroadcast FALL_DETECTED sang MainActivity
     *   → MainActivity sẽ hiển thị dialog đếm ngược 10s → hỏi user → gửi email
     * ──────────────────────────────────────────────────────────────────────────
     */
    private void pushToFirebase(int bpm) {
        String timestamp = new java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());

        int lastSpo2 = dataManager.getAverageOxygen();

        // Lấy trạng thái té ngã hiện tại
        String fallState = getFallStateControlled();

        // ── KÍCH HOẠT TÉ NGÃ ───────────────────────────────────────────────
        if ("yes".equals(fallState) && "no".equals(previousFallState)) {
            double simulatedMagnitude = 25.0 + random.nextDouble() * 10.0; // 25–35 m/s²
            notifyFallDetected(simulatedMagnitude);
            Log.d(TAG, "🚨 Kích hoạt té ngã giả lập -> Bắn tín hiệu về MainActivity");

            // QUAN TRỌNG: Đóng màn hình Nhịp tim lại ngay lập tức để lòi MainActivity ra.
            // Nhờ đó bạn mới nhìn thấy hộp thoại đếm ngược 10 giây!
            finish();
        }
        previousFallState = fallState;

        // ── GHI DỮ LIỆU LÊN FIREBASE ───────────────────────────────────────
        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("timestamp",      timestamp);
        record.put("heart_rate",     bpm);
        record.put("spo2",           lastSpo2 > 0 ? lastSpo2 : 0);
        // BỎ dòng đẩy fall_detection ở đây đi. Việc ghi té ngã lên Firebase
        // sẽ do MainActivity quyết định sau khi user bấm "Tôi ổn" hoặc "Tôi bị té".

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ Firebase HR=" + bpm))
                .addOnFailureListener(e -> Log.e(TAG, "❌ " + e.getMessage()));
    }

    /**
     * Gửi LocalBroadcast FALL_DETECTED tới MainActivity.
     * MainActivity đã đăng ký lắng nghe action này trong bleUpdateReceiver.
     */
    private void notifyFallDetected(double magnitude) {
        Intent fallIntent = new Intent("com.example.watchapp.FALL_DETECTED");
        fallIntent.putExtra("magnitude", magnitude);
        LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);
    }

    // =========================================================================
    //  SENSOR EVENT LISTENER (giữ nguyên)
    // =========================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            int bpm = (int) event.values[0];
            if (bpm > 0) {
                tvHeartRate.setText(String.valueOf(bpm));
                if      (bpm < 60)  tvStatus.setText("Nhịp tim thấp");
                else if (bpm > 100) tvStatus.setText("Nhịp tim cao");
                else                tvStatus.setText("Nhịp tim bình thường");

                dataManager.saveHeartRateData(bpm);
                refreshChart();
                pushToFirebase(bpm);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =========================================================================
    //  CHART (giữ nguyên)
    // =========================================================================

    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getHeartRateData();
        chartView.setData(data, 0xFFE53935, 40, 180);

        int avg = dataManager.getAverageHeartRate();
        if (avg > 0) {
            tvAverage.setText(getString(R.string.average_value, avg) + " BPM");
        } else {
            tvAverage.setText(R.string.no_data);
        }
    }
}