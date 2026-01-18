package com.example.watchapp;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Random;

public class HeartRateActivity extends AppCompatActivity implements SensorEventListener {
    private TextView tvHeartRate, tvStatus;
    private Button btnMeasure, btnBack;
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus = findViewById(R.id.tvStatus);
        btnMeasure = findViewById(R.id.btnMeasure);
        btnBack = findViewById(R.id.btnBack);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        handler = new Handler();
        random = new Random();

        btnMeasure.setOnClickListener(v -> {
            if (!isMeasuring) {
                startMeasurement();
            } else {
                stopMeasurement();
            }
        });

        btnBack.setOnClickListener(v -> finish());
    }

    private void startMeasurement() {
        isMeasuring = true;
        btnMeasure.setText("Dừng đo");
        tvStatus.setText("Đang đo nhịp tim...");

        if (heartRateSensor != null) {
            sensorManager.registerListener(this, heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            // Mô phỏng nếu không có cảm biến
            simulateHeartRate();
        }
    }

    private void stopMeasurement() {
        isMeasuring = false;
        btnMeasure.setText("Bắt đầu đo");
        tvStatus.setText("Nhấn nút để đo nhịp tim");
        sensorManager.unregisterListener(this);
        handler.removeCallbacksAndMessages(null);
    }

    private void simulateHeartRate() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMeasuring) {
                    int heartRate = 60 + random.nextInt(40); // 60-100 BPM
                    tvHeartRate.setText(heartRate + " BPM");

                    if (heartRate < 60) {
                        tvStatus.setText("Nhịp tim thấp");
                    } else if (heartRate > 100) {
                        tvStatus.setText("Nhịp tim cao");
                    } else {
                        tvStatus.setText("Nhịp tim bình thường");
                    }

                    handler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            float heartRate = event.values[0];
            tvHeartRate.setText((int)heartRate + " BPM");

            if (heartRate < 60) {
                tvStatus.setText("Nhịp tim thấp");
            } else if (heartRate > 100) {
                tvStatus.setText("Nhịp tim cao");
            } else {
                tvStatus.setText("Nhịp tim bình thường");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMeasurement();
    }
}