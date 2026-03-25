package com.example.watchapp;

import android.Manifest;
import android.app.AlertDialog;
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
import android.os.CountDownTimer;
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
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    // ── UI Components ─────────────────────────────────────────────────────────
    private TextView tvTime, tvDate, tvBatteryStatus;
    private TextView tvHeartRateAvg, tvOxygenAvg;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private FloatingActionButton fabChat;
    private ChartView heartRateChartView, oxygenChartView;

    // ── BLE Service ───────────────────────────────────────────────────────────
    private BLEService bleService;
    private boolean bleServiceBound = false;
    private String connectedDeviceAddress;
    private String connectedDeviceName;

    // ── Sensor ────────────────────────────────────────────────────────────────
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private HealthDataManager dataManager;
    private long lastFallDetectionTime = 0;
    private double lastMagnitude = 0; // ← lưu magnitude lần té gần nhất

    // ── Firebase ──────────────────────────────────────────────────────────────
    private DatabaseReference healthRecordsRef;
    private DatabaseReference fallStateRef;      // ← node riêng cho trạng thái té ngã
    private ChildEventListener healthChildListener;

    // ── Fall detection dialog ─────────────────────────────────────────────────
    private AlertDialog fallAlertDialog;         // ← giữ tham chiếu để dismiss khi timeout
    private CountDownTimer fallCountDownTimer;   // ← giữ tham chiếu để cancel nếu user phản hồi

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate started");
        dataManager = HealthDataManager.getInstance(this);

        initViews();
        checkPermissions();
        setupSensors();
        startClock();
        setupClickListeners();
        setupFirebaseListener();

        // ← Khởi tạo node fall_state trên Firebase
        fallStateRef = FirebaseDatabase.getInstance().getReference("fall_state");

        // Bind BLE Service
        Intent gattServiceIntent = new Intent(this, BLEService.class);
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE);

        // Register BLE broadcast receiver
        registerBLEReceiver();

        // Load saved connection
        loadSavedConnection();

        Log.d(TAG, "onCreate finished");
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (healthRecordsRef != null && healthChildListener != null)
            healthRecordsRef.removeEventListener(healthChildListener);
        if (timeHandler != null) timeHandler.removeCallbacks(timeRunnable);
        if (bleServiceBound) { unbindService(serviceConnection); bleServiceBound = false; }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleUpdateReceiver);

        // Hủy countdown nếu Activity bị destroy giữa chừng
        if (fallCountDownTimer != null) fallCountDownTimer.cancel();
        if (fallAlertDialog != null && fallAlertDialog.isShowing()) fallAlertDialog.dismiss();
    }

    // =========================================================================
    //  PHÂN LOẠI CƯỜNG ĐỘ (giữ nguyên từ file gốc)
    // =========================================================================

    private String classifyMovement(float x, float y, float z) {
        float mag = (float) Math.sqrt(x * x + y * y + z * z) - 9.8f;
        if (mag < 0) mag = 0;
        if (mag < 1f) return "none";
        if (mag < 4f) return "low";
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
    //  FIREBASE — LẮNG NGHE BẢN GHI MỚI → CẬP NHẬT BIỂU ĐỒ (giữ nguyên)
    // =========================================================================

    private void setupFirebaseListener() {
        healthRecordsRef = FirebaseDatabase.getInstance().getReference("health_records");

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

        healthRecordsRef.limitToLast(50).addChildEventListener(healthChildListener);
    }

    // =========================================================================
    //  BIỂU ĐỒ (giữ nguyên từ file gốc)
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
    //  INIT VIEWS (giữ nguyên từ file gốc)
    // =========================================================================

    private void initViews() {
        tvTime             = findViewById(R.id.tvTime);
        tvDate             = findViewById(R.id.tvDate);
        tvBatteryStatus    = findViewById(R.id.tvBatteryStatus);
        tvHeartRateAvg     = findViewById(R.id.tvHeartRateAvg);
        tvOxygenAvg        = findViewById(R.id.tvOxygenAvg);
        btnBackToOnboarding= findViewById(R.id.btnBackToOnboarding);
        fabChat            = findViewById(R.id.fabChat);
        cardHeartRate      = findViewById(R.id.cardHeartRate);
        cardOxygen         = findViewById(R.id.cardOxygen);
        cardFallDetection  = findViewById(R.id.cardFallDetection);
        cardDisplay        = findViewById(R.id.cardDisplay);
        cardAdvanced       = findViewById(R.id.cardAdvanced);
        heartRateChartView = findViewById(R.id.heartRateChartView);
        oxygenChartView    = findViewById(R.id.oxygenChartView);

        if (heartRateChartView == null) Log.e(TAG, "heartRateChartView is NULL!");
        if (oxygenChartView == null)    Log.e(TAG, "oxygenChartView is NULL!");

        Log.d(TAG, "Views initialized");
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
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void startClock() {
        timeHandler  = new Handler();
        timeRunnable = new Runnable() {
            @Override
            public void run() {
                updateDateTime();
                timeHandler.postDelayed(this, 1000);
            }
        };
        timeHandler.post(timeRunnable);
    }

    private void updateDateTime() {
        Locale currentLocale = getResources().getConfiguration().locale;
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", currentLocale);
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, dd MMMM yyyy", currentLocale);
        Date now = new Date();
        tvTime.setText(timeFormat.format(now));
        tvDate.setText(dateFormat.format(now));
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

    // =========================================================================
    //  BLE (giữ nguyên từ file gốc)
    // =========================================================================

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BLEService.LocalBinder binder = (BLEService.LocalBinder) service;
            bleService = binder.getService();
            bleServiceBound = true;
            if (!bleService.initialize()) Log.e(TAG, "Unable to initialize Bluetooth");
            Log.d(TAG, "BLE Service connected");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            bleServiceBound = false;
            Log.d(TAG, "BLE Service disconnected");
        }
    };

    private final BroadcastReceiver bleUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BLEService.ACTION_GATT_CONNECTED.equals(action)) {
                Toast.makeText(MainActivity.this, "Đã kết nối với đồng hồ", Toast.LENGTH_SHORT).show();
                tvBatteryStatus.setText("Đã kết nối với " + connectedDeviceName);
            } else if (BLEService.ACTION_GATT_DISCONNECTED.equals(action)) {
                Toast.makeText(MainActivity.this, "Đã ngắt kết nối", Toast.LENGTH_SHORT).show();
                tvBatteryStatus.setText(R.string.battery_connected);
            } else if (BLEService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                Log.d(TAG, "Services discovered");
            } else if (BLEService.ACTION_DATA_AVAILABLE.equals(action)) {
                String data = intent.getStringExtra(BLEService.EXTRA_DATA);
                handleReceivedData(data);
            } else if ("com.example.watchapp.FALL_DETECTED".equals(action)) {
                // ← Trước: gọi showFallAlert(magnitude) cũ (không có countdown, không gửi email)
                // ← Sau:   gọi showFallCountdownDialog(magnitude) — có đếm ngược + gửi email
                double magnitude = intent.getDoubleExtra("magnitude", 0);
                showFallCountdownDialog(magnitude);
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
        String savedName    = prefs.getString("connectedDeviceName",    null);
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

    private void handleReceivedData(String jsonData) {
        if (jsonData == null) return;
        Log.d(TAG, "Received data: " + jsonData);
        runOnUiThread(() -> updateCharts());
    }

    // =========================================================================
    //  ACCELEROMETER — phát hiện té ngã
    // =========================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float acceleration = (float) Math.sqrt(x * x + y * y + z * z);

            long currentTime = System.currentTimeMillis();
            if (acceleration > FALL_THRESHOLD &&
                    currentTime - lastFallDetectionTime > 5000) {
                lastFallDetectionTime = currentTime;
                lastMagnitude = acceleration; // ← lưu để truyền vào dialog
                showFallCountdownDialog(lastMagnitude); // ← gọi trực tiếp, bỏ detectFall() cũ
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =========================================================================
    //  FALL DETECTION FLOW — dialog đếm ngược + gửi email
    // =========================================================================

    /**
     * Hiển thị dialog hỏi người dùng sau khi phát hiện té.
     * Đếm ngược 10 giây — nếu không phản hồi sẽ tự động gửi cảnh báo.
     */
    private void showFallCountdownDialog(final double magnitude) {
        runOnUiThread(() -> {
            // Nếu đang có dialog cũ thì bỏ qua (tránh hiện 2 dialog chồng nhau)
            if (fallAlertDialog != null && fallAlertDialog.isShowing()) return;

            // View đếm ngược nhúng vào dialog
            TextView tvCountdown = new TextView(this);
            tvCountdown.setText("Tự động gọi khẩn cấp sau: 10 giây");
            tvCountdown.setPadding(64, 16, 64, 0);
            tvCountdown.setTextSize(14f);

            fallAlertDialog = new AlertDialog.Builder(this)
                    .setTitle("⚠️ Phát hiện té ngã!")
                    .setMessage(String.format(
                            "Phát hiện chuyển động mạnh (%.2f m/s²).\nBạn có ổn không?",
                            magnitude))
                    .setView(tvCountdown)
                    .setCancelable(false)
                    // Nút 1: người dùng ổn → Firebase = "no", KHÔNG gửi email
                    .setPositiveButton("Tôi ổn", (d, w) -> {
                        cancelCountdown();
                        onFallResponse(false, magnitude);
                    })
                    // Nút 2: xác nhận bị té → Firebase = "yes", gửi email
                    .setNegativeButton("Tôi bị té!", (d, w) -> {
                        cancelCountdown();
                        onFallResponse(true, magnitude);
                    })
                    .create();

            fallAlertDialog.show();

            // Bộ đếm ngược 10 giây
            fallCountDownTimer = new CountDownTimer(10_000, 1_000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long seconds = millisUntilFinished / 1_000;
                    runOnUiThread(() ->
                            tvCountdown.setText("Tự động gọi khẩn cấp sau: " + seconds + " giây"));
                }

                @Override
                public void onFinish() {
                    // Hết 10 giây, không phản hồi → coi như bị té
                    if (fallAlertDialog != null && fallAlertDialog.isShowing()) {
                        fallAlertDialog.dismiss();
                        fallAlertDialog = null;
                    }
                    onFallResponse(true, magnitude); // timeout → gửi cảnh báo
                }
            }.start();
        });
    }

    /** Hủy countdown khi user đã bấm nút */
    private void cancelCountdown() {
        if (fallCountDownTimer != null) {
            fallCountDownTimer.cancel();
            fallCountDownTimer = null;
        }
    }

    /**
     * Xử lý sau khi có phản hồi (user bấm nút hoặc timeout).
     *
     * @param isFall true  = xác nhận bị té → Firebase "yes" + gửi email
     *               false = người dùng ổn  → Firebase "no"
     */
    private void onFallResponse(boolean isFall, double magnitude) {
        if (isFall) {
            // 1. Cập nhật Firebase → "yes"
            updateFallStateFirebase("yes", magnitude);

            // 2. Lấy danh sách email từ AdvancedSettings
            List<String> recipients = AdvancedSettingsActivity.getEmergencyEmails(this);

            // 3. Tên đồng hồ
            String watchName = getSharedPreferences("WatchSettings", MODE_PRIVATE)
                    .getString("watchName", "SmartWatch");

            // 4. Gửi email (chạy trên background thread bên trong EmailSender)
            EmailSender.sendFallAlert(recipients, watchName, magnitude, (success, error) ->
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(this,
                                    "✅ Đã gửi email cảnh báo tới người thân!",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this,
                                    "❌ Gửi email thất bại: " + error,
                                    Toast.LENGTH_LONG).show();
                        }
                    })
            );

            // 5. Ghi vào lịch sử (để FallDetectionActivity hiển thị)
            recordFallEventToHistory(magnitude, true);

        } else {
            // Người dùng ổn
            updateFallStateFirebase("no", magnitude);
            recordFallEventToHistory(magnitude, false);
            Toast.makeText(this, "Đã ghi nhận — bạn ổn 👍", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Firebase: ghi trạng thái té vào node "fall_state" ────────────────────
    private void updateFallStateFirebase(String state, double magnitude) {
        Map<String, Object> data = new HashMap<>();
        data.put("state",     state);
        data.put("magnitude", magnitude);
        data.put("timestamp", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date()));

        fallStateRef.setValue(data)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ Firebase fall_state = " + state))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Firebase error: " + e.getMessage()));
    }

    // ── Ghi lịch sử vào SharedPreferences → FallDetectionActivity đọc ─────────
    private void recordFallEventToHistory(double magnitude, boolean confirmed) {
        SharedPreferences prefs = getSharedPreferences("WatchSettings", MODE_PRIVATE);
        String json = prefs.getString("fallHistory", null);

        Type type = new TypeToken<List<FallDetectionActivity.FallEvent>>() {}.getType();
        List<FallDetectionActivity.FallEvent> history = new ArrayList<>();
        if (json != null) {
            List<FallDetectionActivity.FallEvent> saved = new Gson().fromJson(json, type);
            if (saved != null) history = saved;
        }

        String dateTime = new SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale.getDefault())
                .format(new Date());
        history.add(new FallDetectionActivity.FallEvent(
                dateTime, magnitude,
                confirmed ? FallDetectionActivity.FallEvent.STATUS_CONFIRMED
                        : FallDetectionActivity.FallEvent.STATUS_CANCELLED));

        prefs.edit()
                .putString("fallHistory", new Gson().toJson(history))
                .apply();
    }
}