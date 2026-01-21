package com.example.watchapp;

import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class OxygenActivity extends AppCompatActivity {
    private TextView tvOxygenLevel, tvStatus;
    private Button btnMeasure, btnBack;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;
    private HealthDataManager dataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oxygen);

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus = findViewById(R.id.tvStatus);
        btnMeasure = findViewById(R.id.btnMeasure);
        btnBack = findViewById(R.id.btnBack);

        handler = new Handler();
        random = new Random();
        dataManager = HealthDataManager.getInstance(this);

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
        tvStatus.setText("Đang đo nồng độ oxy...");

        simulateOxygenLevel();
    }

    private void stopMeasurement() {
        isMeasuring = false;
        btnMeasure.setText("Bắt đầu đo");
        tvStatus.setText("Nhấn nút để đo SpO2");
        handler.removeCallbacksAndMessages(null);
    }

    private void simulateOxygenLevel() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMeasuring) {
                    int oxygenLevel = 95 + random.nextInt(6); // 95-100%
                    tvOxygenLevel.setText(oxygenLevel + "%");

                    // Lưu dữ liệu vào HealthDataManager
                    dataManager.saveOxygenData(oxygenLevel);

                    if (oxygenLevel < 95) {
                        tvStatus.setText("Nồng độ oxy thấp");
                    } else {
                        tvStatus.setText("Nồng độ oxy bình thường");
                    }

                    handler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMeasurement();
    }
}