package com.example.watchapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final String TAG = "MainActivity";
    private TextView tvTime, tvDate, tvBatteryStatus;
    private TextView tvHeartRateAvg, tvOxygenAvg;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private ChartView heartRateChartView, oxygenChartView;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private HealthDataManager dataManager;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final float FALL_THRESHOLD = 25.0f;
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

        Log.d(TAG, "onCreate finished");
    }

    private void initViews() {
        tvTime = findViewById(R.id.tvTime);
        tvDate = findViewById(R.id.tvDate);
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus);
        tvHeartRateAvg = findViewById(R.id.tvHeartRateAvg);
        tvOxygenAvg = findViewById(R.id.tvOxygenAvg);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
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
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, dd MMMM yyyy", new Locale("vi"));

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
            Toast.makeText(this, "Cảnh báo vấp ngã đang hoạt động", Toast.LENGTH_SHORT).show();
        });

        cardDisplay.setOnClickListener(v -> {
            Toast.makeText(this, "Cài đặt hiển thị", Toast.LENGTH_SHORT).show();
        });

        cardAdvanced.setOnClickListener(v -> {
            Toast.makeText(this, "Cài đặt nâng cao", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "⚠️ Phát hiện té ngã! Bạn có ổn không?",
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Cập nhật biểu đồ khi quay lại màn hình
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