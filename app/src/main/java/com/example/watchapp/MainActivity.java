package com.example.watchapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private TextView tvTime, tvDate, tvBatteryStatus;
    private CardView cardHeartRate, cardOxygen, cardFallDetection, cardDisplay, cardAdvanced;
    private Button btnBackToOnboarding;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Handler timeHandler;
    private Runnable timeRunnable;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final float FALL_THRESHOLD = 25.0f;
    private long lastFallDetectionTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        setupSensors();
        startClock();
        setupClickListeners();
    }

    private void initViews() {
        tvTime = findViewById(R.id.tvTime);
        tvDate = findViewById(R.id.tvDate);
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        cardHeartRate = findViewById(R.id.cardHeartRate);
        cardOxygen = findViewById(R.id.cardOxygen);
        cardFallDetection = findViewById(R.id.cardFallDetection);
        cardDisplay = findViewById(R.id.cardDisplay);
        cardAdvanced = findViewById(R.id.cardAdvanced);
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

        // Cập nhật trạng thái pin (giả lập)
        tvBatteryStatus.setText("Hãy kiểm tra trạng thái đồng hồ của bạn...");
    }

    private void setupClickListeners() {
        btnBackToOnboarding.setOnClickListener(v -> {
            // Xóa trạng thái đã onboard để quay lại màn hình Get Started
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean("hasOnboarded", false).apply();

            // Quay về OnboardingActivity
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
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
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
}