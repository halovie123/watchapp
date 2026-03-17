package com.example.watchapp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends BaseActivity implements SensorEventListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final float FALL_THRESHOLD = 25.0f;
    private static final int SIMULATE_INTERVAL_MS = 3000;

    // UI
    private TextView tvTime, tvDate, tvBatteryStatus;
    private TextView tvHeartRateAvg, tvOxygenAvg;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private FloatingActionButton fabChat;
    private ChartView heartRateChartView, oxygenChartView;

    // BLE
    private BLEService bleService;
    private boolean bleServiceBound = false;
    private String connectedDeviceAddress, connectedDeviceName;

    // Sensor (chỉ dùng accelerometer để phát hiện té ngã thật)
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private HealthDataManager dataManager;
    private long lastFallDetectionTime = 0;

    // Firebase — ghi vào health_records (push, không ghi đè, lưu lịch sử)
    private DatabaseReference healthRecordsRef;
    private ChildEventListener healthChildListener;

    // Firebase — ghi giá trị MỚI NHẤT để HeartRateActivity / OxygenActivity hiển thị
    private DatabaseReference heartRateDisplayRef;   // heart_rate/value
    private DatabaseReference oxygenDisplayRef;      // oxygen_level/value

    // Simulation — XÓA KHI CÓ CẢM BIẾN THẬT
    private Handler simulateHandler;
    private Random  random;
    private boolean isSimulating = false;

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataManager     = HealthDataManager.getInstance(this);
        simulateHandler = new Handler();
        random          = new Random();

        initViews();
        checkPermissions();
        setupSensors();
        startClock();
        setupClickListeners();
        setupFirebaseListener();

        Intent gattServiceIntent = new Intent(this, BLEService.class);
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE);
        registerBLEReceiver();
        loadSavedConnection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String savedLanguage   = LocaleHelper.getPersistedLanguage(this);
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();
        if (!savedLanguage.equals(currentLanguage)) { recreate(); return; }

        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        updateCharts();
        startSimulation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        // KHÔNG dừng simulation — tiếp tục gửi dù chuyển activity khác
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSimulation();
        if (healthRecordsRef != null && healthChildListener != null)
            healthRecordsRef.removeEventListener(healthChildListener);
        if (timeHandler != null) timeHandler.removeCallbacks(timeRunnable);
        if (bleServiceBound) { unbindService(serviceConnection); bleServiceBound = false; }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleUpdateReceiver);
    }

    // =========================================================================
    //  SIMULATION — random tất cả 4 loại dữ liệu mỗi 3 giây
    //  XÓA startSimulation() & stopSimulation() KHI CÓ CẢM BIẾN THẬT
    // =========================================================================

    private void startSimulation() {
        if (isSimulating) return;
        isSimulating = true;

        simulateHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isSimulating) return;

                // ── Heart rate: 60–100 BPM ────────────────────────────────────
                int bpm  = 60 + random.nextInt(41);

                // ── SpO2: 95–100% ─────────────────────────────────────────────
                int spo2 = 95 + random.nextInt(6);

                pushHealthRecord(bpm, spo2);
                simulateHandler.postDelayed(this, SIMULATE_INTERVAL_MS);
            }
        });
    }

    private void stopSimulation() {
        isSimulating = false;
        simulateHandler.removeCallbacksAndMessages(null);
    }

    private void pushHealthRecord(int bpm, int spo2) {

        String timestamp = new SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());

        // Random fall detection (yes/no)
        String fall = random.nextBoolean() ? "yes" : "no";

        Map<String, Object> record = new HashMap<>();
        record.put("timestamp", timestamp);
        record.put("heart_rate", bpm);
        record.put("spo2", spo2);
        record.put("fall_detection", fall);

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG,
                        " HR=" + bpm + " SpO2=" + spo2 + " FALL=" + fall))
                .addOnFailureListener(e -> Log.e(TAG, "❌ " + e.getMessage()));

        // Latest value (giữ nguyên)
        heartRateDisplayRef.setValue(bpm);
        oxygenDisplayRef.setValue(spo2);

        // Update local + chart
        dataManager.saveHeartRateData(bpm);
        dataManager.saveOxygenData(spo2);

        runOnUiThread(() -> {
            tvHeartRateAvg.setText(dataManager.getAverageHeartRate() + " BPM");
            tvOxygenAvg.setText(dataManager.getAverageOxygen() + "%");
            updateHeartRateChart();
            updateOxygenChart();
        });
    }

    // =========================================================================
    //  PHÂN LOẠI CƯỜNG ĐỘ
    // =========================================================================

    private String classifyMovement(float x, float y, float z) {
        float mag = (float) Math.sqrt(x*x + y*y + z*z) - 9.8f;
        if (mag < 0) mag = 0;
        if (mag < 1f)  return "none";
        if (mag < 4f)  return "low";
        if (mag < 10f) return "moderate";
        return "high";
    }

    private String classifyRotation(float x, float y, float z) {
        float mag = (float) Math.sqrt(x*x + y*y + z*z);
        if (mag < 0.1f) return "none";
        if (mag < 0.5f) return "low";
        if (mag < 2f)   return "moderate";
        return "high";
    }

    private double round2(float val) {
        return Math.round(val * 100.0) / 100.0;
    }

    // =========================================================================
    //  FIREBASE — LẮNG NGHE BẢN GHI MỚI → CẬP NHẬT BIỂU ĐỒ
    // =========================================================================

    private void setupFirebaseListener() {
        healthRecordsRef   = FirebaseDatabase.getInstance().getReference("health_records");
        heartRateDisplayRef = FirebaseDatabase.getInstance().getReference("heart_rate").child("value");
        oxygenDisplayRef    = FirebaseDatabase.getInstance().getReference("oxygen_level").child("value");

        healthChildListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                Integer bpm  = snapshot.child("heart_rate").getValue(Integer.class);
                Integer spo2 = snapshot.child("spo2").getValue(Integer.class);
                if (bpm  != null) dataManager.saveHeartRateData(bpm);
                if (spo2 != null) dataManager.saveOxygenData(spo2);
                runOnUiThread(() -> updateCharts());
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.e(TAG, "Listener error: " + e.getMessage());
            }
        };

        // Chỉ tải 50 bản ghi gần nhất
        healthRecordsRef.limitToLast(50).addChildEventListener(healthChildListener);
    }

    // =========================================================================
    //  BIỂU ĐỒ
    // =========================================================================

    private void updateHeartRateChart() {
        if (heartRateChartView == null) return;
        List<HealthDataManager.HealthDataPoint> data = dataManager.getHeartRateData();
        if (!data.isEmpty())
            heartRateChartView.setData(data, Color.parseColor("#E53935"), 50, 120);
    }

    private void updateOxygenChart() {
        if (oxygenChartView == null) return;
        List<HealthDataManager.HealthDataPoint> data = dataManager.getOxygenData();
        if (!data.isEmpty())
            oxygenChartView.setData(data, Color.parseColor("#1E88E5"), 90, 100);
    }

    private void updateCharts() {
        if (heartRateChartView == null || oxygenChartView == null) return;
        List<HealthDataManager.HealthDataPoint> hrData = dataManager.getHeartRateData();
        if (!hrData.isEmpty()) {
            heartRateChartView.setData(hrData, Color.parseColor("#E53935"), 50, 120);
            tvHeartRateAvg.setText(dataManager.getAverageHeartRate() + " BPM");
        } else {
            tvHeartRateAvg.setText("-- BPM");
            heartRateChartView.setData(null, Color.parseColor("#E53935"), 50, 120);
        }
        List<HealthDataManager.HealthDataPoint> o2Data = dataManager.getOxygenData();
        if (!o2Data.isEmpty()) {
            oxygenChartView.setData(o2Data, Color.parseColor("#1E88E5"), 90, 100);
            tvOxygenAvg.setText(dataManager.getAverageOxygen() + "%");
        } else {
            tvOxygenAvg.setText("--%");
            oxygenChartView.setData(null, Color.parseColor("#1E88E5"), 90, 100);
        }
    }

    // =========================================================================
    //  PHẦN GIỮ NGUYÊN
    // =========================================================================

    private void initViews() {
        tvTime              = findViewById(R.id.tvTime);
        tvDate              = findViewById(R.id.tvDate);
        tvBatteryStatus     = findViewById(R.id.tvBatteryStatus);
        tvHeartRateAvg      = findViewById(R.id.tvHeartRateAvg);
        tvOxygenAvg         = findViewById(R.id.tvOxygenAvg);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        fabChat             = findViewById(R.id.fabChat);
        cardHeartRate       = findViewById(R.id.cardHeartRate);
        cardOxygen          = findViewById(R.id.cardOxygen);
        cardFallDetection   = findViewById(R.id.cardFallDetection);
        cardDisplay         = findViewById(R.id.cardDisplay);
        cardAdvanced        = findViewById(R.id.cardAdvanced);
        heartRateChartView  = findViewById(R.id.heartRateChartView);
        oxygenChartView     = findViewById(R.id.oxygenChartView);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BODY_SENSORS,
                            Manifest.permission.ACTIVITY_RECOGNITION},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void startClock() {
        timeHandler  = new Handler();
        timeRunnable = new Runnable() {
            @Override public void run() {
                Locale locale = getResources().getConfiguration().locale;
                tvTime.setText(new SimpleDateFormat("HH:mm", locale).format(new Date()));
                tvDate.setText(new SimpleDateFormat("EEEE, dd MMMM yyyy", locale).format(new Date()));
                timeHandler.postDelayed(this, 1000);
            }
        };
        timeHandler.post(timeRunnable);
    }

    private void setupClickListeners() {
        btnBackToOnboarding.setOnClickListener(v -> {
            getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    .edit().putBoolean("hasOnboarded", false).apply();
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
        });
        cardHeartRate.setOnClickListener(v ->
                startActivity(new Intent(this, HeartRateActivity.class)));
        cardOxygen.setOnClickListener(v ->
                startActivity(new Intent(this, OxygenActivity.class)));
        cardFallDetection.setOnClickListener(v ->
                startActivity(new Intent(this, FallDetectionActivity.class)));
        cardDisplay.setOnClickListener(v ->
                Toast.makeText(this, "Cài đặt hiển thị", Toast.LENGTH_SHORT).show());
        cardAdvanced.setOnClickListener(v ->
                startActivity(new Intent(this, AdvancedSettingsActivity.class)));
        fabChat.setOnClickListener(v ->
                startActivity(new Intent(this, ChatboxActivity.class)));
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bleService = ((BLEService.LocalBinder) service).getService();
            bleServiceBound = true;
            if (!bleService.initialize()) Log.e(TAG, "Unable to initialize Bluetooth");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bleService = null; bleServiceBound = false;
        }
    };

    private final BroadcastReceiver bleUpdateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BLEService.ACTION_GATT_CONNECTED.equals(action)) {
                Toast.makeText(MainActivity.this, "Đã kết nối với đồng hồ", Toast.LENGTH_SHORT).show();
                tvBatteryStatus.setText("Đã kết nối với " + connectedDeviceName);
            } else if (BLEService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Toast.makeText(MainActivity.this, "Đã ngắt kết nối", Toast.LENGTH_SHORT).show();
                tvBatteryStatus.setText(R.string.battery_connected);
            } else if (BLEService.ACTION_DATA_AVAILABLE.equals(action)) {
                runOnUiThread(() -> updateCharts());
            } else if ("com.example.watchapp.FALL_DETECTED".equals(action)) {
                showFallAlert(intent.getDoubleExtra("magnitude", 0));
            }
        }
    };

    private void registerBLEReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_GATT_SERVICES_DISCOVERED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        filter.addAction("com.example.watchapp.FALL_DETECTED");
        LocalBroadcastManager.getInstance(this).registerReceiver(bleUpdateReceiver, filter);
    }

    private void loadSavedConnection() {
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        String savedAddress = prefs.getString("connectedDeviceAddress", null);
        String savedName    = prefs.getString("connectedDeviceName", null);
        if (savedAddress != null && savedName != null) {
            connectedDeviceAddress = savedAddress;
            connectedDeviceName    = savedName;
            new Handler().postDelayed(() -> {
                if (bleService != null && !bleService.isConnected()) {
                    bleService.connect(savedAddress);
                    tvBatteryStatus.setText("Đang kết nối với " + savedName + "...");
                }
            }, 1000);
        }
    }

    private void showFallAlert(double magnitude) {
        runOnUiThread(() ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Phát hiện té ngã!")
                        .setMessage(String.format(
                                "Phát hiện chuyển động mạnh (%.2f m/s²).\n\nBạn có ổn không?", magnitude))
                        .setPositiveButton("Tôi ổn", (d, w) -> d.dismiss())
                        .setNegativeButton("Gọi khẩn cấp", (d, w) ->
                                Toast.makeText(this, "Đang gọi số khẩn cấp...", Toast.LENGTH_SHORT).show())
                        .setCancelable(false).show()
        );
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            float acc = (float) Math.sqrt(x*x + y*y + z*z);
            long now = System.currentTimeMillis();
            if (acc > FALL_THRESHOLD && now - lastFallDetectionTime > 5000) {
                lastFallDetectionTime = now;
                runOnUiThread(() -> Toast.makeText(this,
                        "Phát hiện té ngã! Bạn có ổn không?", Toast.LENGTH_LONG).show());
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}