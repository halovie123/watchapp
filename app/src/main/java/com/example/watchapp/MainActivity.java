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

import com.google.firebase.database.ValueEventListener;

public class MainActivity extends BaseActivity implements SensorEventListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final float FALL_THRESHOLD = 25.0f;

    // ── UI Components ─────────────────────────────────────────────────────────
    private TextView tvTime, tvDate, tvBatteryStatus;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private FloatingActionButton fabChat;

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
    private double lastMagnitude = 0;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private DatabaseReference healthRecordsRef;
    private DatabaseReference fallStateRef;
    private ChildEventListener healthChildListener;

    // ── Fall state ────────────────────────────────────────────────────────────
    private String currentFallState = "no";

    // ── Fall detection dialog ─────────────────────────────────────────────────
    private AlertDialog fallAlertDialog;
    private CountDownTimer fallCountDownTimer;

    // ── Pending fall: lưu magnitude khi detect lúc activity không ở foreground
    // -1 = không có fall đang chờ
    private double pendingFallMagnitude = -1;

    // ── Cờ theo dõi activity có đang visible không ────────────────────────────
    private boolean isActivityVisible = false;

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

        fallStateRef = FirebaseDatabase.getInstance().getReference("fall_state");

        Intent gattServiceIntent = new Intent(this, BLEService.class);
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE);

        registerBLEReceiver();
        loadSavedConnection();

        Log.d(TAG, "onCreate finished");
    }

    @Override
    protected void onResume() {
        super.onResume();

        isActivityVisible = true;

        String savedLanguage   = LocaleHelper.getPersistedLanguage(this);
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();
        if (!savedLanguage.equals(currentLanguage)) { recreate(); return; }

        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        // ── Hiện dialog nếu có fall bị pending lúc activity không ở foreground ──
        if (pendingFallMagnitude >= 0 && isFallDetectionEnabled()) {
            double mag = pendingFallMagnitude;
            pendingFallMagnitude = -1;
            // Delay nhỏ để activity kịp resume hoàn toàn trước khi show dialog
            new Handler(getMainLooper()).postDelayed(() -> showFallCountdownDialog(mag), 300);
        } else {
            pendingFallMagnitude = -1; // xoá pending dù tính năng bị tắt
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
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

        if (fallCountDownTimer != null) fallCountDownTimer.cancel();
        if (fallAlertDialog != null && fallAlertDialog.isShowing()) fallAlertDialog.dismiss();
    }

    // =========================================================================
    //  FIREBASE — LƯU DỮ LIỆU VÀO CACHE
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
    //  INIT VIEWS
    // =========================================================================

    private void initViews() {
        tvTime              = findViewById(R.id.tvTime);
        tvDate              = findViewById(R.id.tvDate);
        tvBatteryStatus     = findViewById(R.id.tvBatteryStatus);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        fabChat             = findViewById(R.id.fabChat);
        cardHeartRate       = findViewById(R.id.cardHeartRate);
        cardOxygen          = findViewById(R.id.cardOxygen);
        cardFallDetection   = findViewById(R.id.cardFallDetection);
        cardDisplay         = findViewById(R.id.cardDisplay);
        cardAdvanced        = findViewById(R.id.cardAdvanced);

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
    //  BLE
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
                int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM,    -1);
                int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2,   -1);
                int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);

                if (finger != 0 && bpm > 0) {
                    if (spo2 > 0) dataManager.saveOxygenData(spo2);
                    dataManager.saveHeartRateData(bpm);

                    // FIX: khi tính năng tắt → luôn gửi "no" lên Firebase,
                    // KHÔNG để currentFallState = "yes" lọt vào health_records
                    String fallStateToReport = isFallDetectionEnabled() ? currentFallState : "no";
                    pushHealthRecord(bpm, spo2, fallStateToReport);
                }

            } else if ("com.example.watchapp.FALL_DETECTED".equals(action)) {
                double magnitude = intent.getDoubleExtra("magnitude", 0);
                handleFallDetected(magnitude);
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

    // =========================================================================
    //  HELPER — trạng thái fall detection
    // =========================================================================

    private boolean isFallDetectionEnabled() {
        return getSharedPreferences("WatchSettings", MODE_PRIVATE)
                .getBoolean("fallDetectionEnabled", true);
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
                lastMagnitude = acceleration;
                // Model vẫn chạy/infer — chỉ chặn output khi tắt
                handleFallDetected(lastMagnitude);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =========================================================================
    //  FALL DETECTION — điểm vào duy nhất xử lý fall
    // =========================================================================

    /**
     * Điểm vào duy nhất khi phát hiện té ngã (từ accelerometer hoặc BLE broadcast).
     *
     * Nếu tính năng BỊ TẮT  → bỏ qua hoàn toàn (không dialog, không Firebase, không email).
     * Nếu activity VISIBLE   → hiện dialog ngay.
     * Nếu activity ở nền     → lưu vào pendingFallMagnitude, dialog sẽ hiện khi onResume.
     */
    private void handleFallDetected(double magnitude) {
        // Guard #1: tính năng bị tắt → dừng tại đây
        if (!isFallDetectionEnabled()) return;

        if (isActivityVisible) {
            showFallCountdownDialog(magnitude);
        } else {
            // Lưu pending, sẽ hiện dialog khi user quay về MainActivity
            pendingFallMagnitude = magnitude;
            Log.d(TAG, "Fall detected while activity in background, pending magnitude=" + magnitude);
        }
    }

    // =========================================================================
    //  FALL DETECTION FLOW
    // =========================================================================

    private void showFallCountdownDialog(final double magnitude) {
        runOnUiThread(() -> {
            if (fallAlertDialog != null && fallAlertDialog.isShowing()) return;

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
                    .setPositiveButton("Tôi ổn", (d, w) -> {
                        cancelCountdown();
                        onFallResponse(false, magnitude);
                    })
                    .setNegativeButton("Tôi bị té!", (d, w) -> {
                        cancelCountdown();
                        onFallResponse(true, magnitude);
                    })
                    .create();

            fallAlertDialog.show();

            fallCountDownTimer = new CountDownTimer(10_000, 1_000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long seconds = millisUntilFinished / 1_000;
                    runOnUiThread(() ->
                            tvCountdown.setText("Tự động gọi khẩn cấp sau: " + seconds + " giây"));
                }

                @Override
                public void onFinish() {
                    if (fallAlertDialog != null && fallAlertDialog.isShowing()) {
                        fallAlertDialog.dismiss();
                        fallAlertDialog = null;
                    }
                    onFallResponse(true, magnitude);
                }
            }.start();
        });
    }

    private void cancelCountdown() {
        if (fallCountDownTimer != null) {
            fallCountDownTimer.cancel();
            fallCountDownTimer = null;
        }
    }

    private void onFallResponse(boolean isFall, double magnitude) {
        // Guard #2: defense-in-depth — không xử lý nếu tính năng bị tắt
        // (trường hợp dialog đã show trước khi user tắt switch)
        if (!isFallDetectionEnabled()) return;

        if (isFall) {
            currentFallState = "yes";
            updateFallStateFirebase("yes", magnitude);

            List<String> recipients = AdvancedSettingsActivity.getEmergencyEmails(this);
            String watchName = getSharedPreferences("WatchSettings", MODE_PRIVATE)
                    .getString("watchName", "SmartWatch");

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

            recordFallEventToHistory(magnitude, true);

            new Handler(getMainLooper()).postDelayed(() -> {
                currentFallState = "no";
            }, 5000);

        } else {
            currentFallState = "no";
            updateFallStateFirebase("no", magnitude);
            recordFallEventToHistory(magnitude, false);
            Toast.makeText(this, "Đã ghi nhận — bạn ổn 👍", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    //  FIREBASE PUSH
    // =========================================================================

    private void updateFallStateFirebase(String state, double magnitude) {
        Map<String, Object> data = new HashMap<>();
        data.put("state",     state);
        data.put("magnitude", magnitude);
        data.put("timestamp", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date()));

        fallStateRef.setValue(data)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ fall_state = " + state))
                .addOnFailureListener(e -> Log.e(TAG, "❌ fall_state FAIL: " + e.getMessage()));
    }

    private void cleanupOldRecords() {
        healthRecordsRef.orderByKey().limitToLast(200)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        long total = snapshot.getChildrenCount();
                        if (total < 200) return;

                        int count = 0;
                        for (DataSnapshot child : snapshot.getChildren()) {
                            count++;
                            if (count <= 100) child.getRef().removeValue();
                        }
                    }
                    @Override
                    public void onCancelled(DatabaseError error) {}
                });
    }

    private void pushHealthRecord(int bpm, int spo2, String fallDetection) {
        String timestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date());

        Map<String, Object> record = new HashMap<>();
        record.put("fall_detection", fallDetection);
        record.put("heart_rate",     bpm);
        record.put("spo2",           spo2 > 0 ? spo2 : 0);
        record.put("timestamp",      timestamp);

        Log.d(TAG, "🔥 pushHealthRecord: bpm=" + bpm + " spo2=" + spo2 + " fall=" + fallDetection);

        healthRecordsRef.push().setValue(record)
                .addOnSuccessListener(u -> Log.d(TAG, "✅ health_records OK: HR=" + bpm))
                .addOnFailureListener(e -> Log.e(TAG, "❌ health_records FAIL: " + e.getMessage()));
        cleanupOldRecords();
    }

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