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
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseActivity implements SensorEventListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final float FALL_THRESHOLD = 25.0f;

    // UI Components
    private TextView tvTime, tvDate, tvBatteryStatus;
    private TextView tvHeartRateAvg, tvOxygenAvg;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private FloatingActionButton fabChat;
    private ChartView heartRateChartView, oxygenChartView;

    // BLE Service (background only)
    private BLEService bleService;
    private boolean bleServiceBound = false;
    private String connectedDeviceAddress;
    private String connectedDeviceName;

    // Sensors
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private HealthDataManager dataManager;
    private long lastFallDetectionTime = 0;

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

        // Bind BLE Service
        Intent gattServiceIntent = new Intent(this, BLEService.class);
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE);

        // Register BLE broadcast receiver
        registerBLEReceiver();

        // Load saved connection
        loadSavedConnection();

        Log.d(TAG, "onCreate finished");
    }

    private void initViews() {
        tvTime = findViewById(R.id.tvTime);
        tvDate = findViewById(R.id.tvDate);
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus);
        tvHeartRateAvg = findViewById(R.id.tvHeartRateAvg);
        tvOxygenAvg = findViewById(R.id.tvOxygenAvg);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        fabChat = findViewById(R.id.fabChat);
        cardHeartRate = findViewById(R.id.cardHeartRate);
        cardOxygen = findViewById(R.id.cardOxygen);
        cardFallDetection = findViewById(R.id.cardFallDetection);
        cardDisplay = findViewById(R.id.cardDisplay);
        cardAdvanced = findViewById(R.id.cardAdvanced);
        heartRateChartView = findViewById(R.id.heartRateChartView);
        oxygenChartView = findViewById(R.id.oxygenChartView);

        // Kiểm tra null
        if (heartRateChartView == null) {
            Log.e(TAG, "heartRateChartView is NULL!");
        }
        if (oxygenChartView == null) {
            Log.e(TAG, "oxygenChartView is NULL!");
        }

        Log.d(TAG, "Views initialized");
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BODY_SENSORS,
                            Manifest.permission.ACTIVITY_RECOGNITION
                    }, PERMISSION_REQUEST_CODE);
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
        timeHandler = new Handler();
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

        SimpleDateFormat timeFormat =
                new SimpleDateFormat("HH:mm", currentLocale);

        SimpleDateFormat dateFormat =
                new SimpleDateFormat("EEEE, dd MMMM yyyy", currentLocale);

        Date now = new Date();
        tvTime.setText(timeFormat.format(now));
        tvDate.setText(dateFormat.format(now));
    }

    private void setupClickListeners() {
        btnBackToOnboarding.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean("hasOnboarded", false).apply();

            startActivity(new Intent(MainActivity.this, OnboardingActivity.class));
            finish();
        });

        cardHeartRate.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HeartRateActivity.class));
        });

        cardOxygen.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, OxygenActivity.class));
        });

        cardFallDetection.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, FallDetectionActivity.class));
        });

        cardDisplay.setOnClickListener(v -> {
            Toast.makeText(this, "Cài đặt hiển thị", Toast.LENGTH_SHORT).show();
        });

        cardAdvanced.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, AdvancedSettingsActivity.class));
        });

        // Floating Action Button for Chat
        fabChat.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ChatboxActivity.class));
        });
    }

    // BLE Service Connection
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BLEService.LocalBinder binder = (BLEService.LocalBinder) service;
            bleService = binder.getService();
            bleServiceBound = true;

            if (!bleService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }

            Log.d(TAG, "BLE Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            bleServiceBound = false;
            Log.d(TAG, "BLE Service disconnected");
        }
    };

    // BLE Broadcast Receiver
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
                double magnitude = intent.getDoubleExtra("magnitude", 0);
                showFallAlert(magnitude);
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
        String savedName = prefs.getString("connectedDeviceName", null);

        if (savedAddress != null && savedName != null) {
            connectedDeviceAddress = savedAddress;
            connectedDeviceName = savedName;

            // Tự động kết nối lại
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

        // Dữ liệu đã được parse và lưu trong BLEService
        // Chỉ cần cập nhật UI
        runOnUiThread(() -> updateCharts());
    }

    private void showFallAlert(double magnitude) {
        runOnUiThread(() -> {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("Phát hiện té ngã!");
            builder.setMessage(String.format("Phát hiện chuyển động mạnh (%.2f m/s²).\n\nBạn có ổn không?", magnitude));
            builder.setPositiveButton("Tôi ổn", (dialog, which) -> {
                dialog.dismiss();
            });
            builder.setNegativeButton("Gọi khẩn cấp", (dialog, which) -> {
                Toast.makeText(this, "Đang gọi số khẩn cấp...", Toast.LENGTH_SHORT).show();
            });
            builder.setCancelable(false);
            builder.show();
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float acceleration = (float) Math.sqrt(x*x + y*y + z*z);

            long currentTime = System.currentTimeMillis();
            if (acceleration > FALL_THRESHOLD &&
                    currentTime - lastFallDetectionTime > 5000) {
                lastFallDetectionTime = currentTime;
                detectFall();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void detectFall() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Phát hiện té ngã! Bạn có ổn không?",
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }

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
        if (timeHandler != null) {
            timeHandler.removeCallbacks(timeRunnable);
        }

        // Unbind BLE Service
        if (bleServiceBound) {
            unbindService(serviceConnection);
            bleServiceBound = false;
        }

        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleUpdateReceiver);
    }

    // Cập nhật biểu đồ
    private void updateCharts() {
        Log.d(TAG, "updateCharts called");

        // Kiểm tra views có null không
        if (heartRateChartView == null || oxygenChartView == null) {
            Log.e(TAG, "ChartViews are null! Cannot update charts.");
            return;
        }

        // Lấy dữ liệu nhịp tim
        List<HealthDataManager.HealthDataPoint> heartRateData = dataManager.getHeartRateData();
        Log.d(TAG, "Heart rate data size: " + heartRateData.size());

        if (!heartRateData.isEmpty()) {
            heartRateChartView.setData(heartRateData, Color.parseColor("#E53935"), 50, 120);
            int avgHeartRate = dataManager.getAverageHeartRate();
            tvHeartRateAvg.setText(avgHeartRate + " BPM");
            Log.d(TAG, "Heart rate chart updated with avg: " + avgHeartRate);
        } else {
            tvHeartRateAvg.setText("-- BPM");
            heartRateChartView.setData(null, Color.parseColor("#E53935"), 50, 120);
            Log.d(TAG, "No heart rate data");
        }

        // Lấy dữ liệu oxy
        List<HealthDataManager.HealthDataPoint> oxygenData = dataManager.getOxygenData();
        Log.d(TAG, "Oxygen data size: " + oxygenData.size());

        if (!oxygenData.isEmpty()) {
            oxygenChartView.setData(oxygenData, Color.parseColor("#1E88E5"), 90, 100);
            int avgOxygen = dataManager.getAverageOxygen();
            tvOxygenAvg.setText(avgOxygen + "%");
            Log.d(TAG, "Oxygen chart updated with avg: " + avgOxygen);
        } else {
            tvOxygenAvg.setText("--%");
            oxygenChartView.setData(null, Color.parseColor("#1E88E5"), 90, 100);
            Log.d(TAG, "No oxygen data");
        }
    }
}