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
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.Random;

public class OxygenActivity extends AppCompatActivity {
    private TextView tvOxygenLevel, tvStatus;
    private Button btnMeasure, btnBack;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;
    private HealthDataManager dataManager;

    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BLEService.ACTION_DATA_AVAILABLE.equals(intent.getAction())) return;

            // Lấy trực tiếp từ Extra — BLEService đã parse sẵn
            int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2,   -1);
            int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);

            if (finger == 0) {
                tvStatus.setText("Chưa đặt tay lên cảm biến");
                tvOxygenLevel.setText("--");
                return;
            }

            if (spo2 > 0) {
                tvOxygenLevel.setText(spo2 + "%");
                // Không cần saveOxygenData — BLEService đã lưu rồi
                if (spo2 < 95) tvStatus.setText("Nồng độ oxy thấp");
                else           tvStatus.setText("Nồng độ oxy bình thường");
                Log.d("OxygenActivity", "SpO2 từ BLE: " + spo2);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oxygen);

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus      = findViewById(R.id.tvStatus);
        btnMeasure    = findViewById(R.id.btnMeasure);
        btnBack       = findViewById(R.id.btnBack);

        handler     = new Handler();
        random      = new Random();
        dataManager = HealthDataManager.getInstance(this);

        btnMeasure.setOnClickListener(v -> {
            if (!isMeasuring) startMeasurement();
            else               stopMeasurement();
        });
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(bleReceiver,
                        new IntentFilter(BLEService.ACTION_DATA_AVAILABLE));
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
    }

    private void startMeasurement() {
        isMeasuring = true;
        btnMeasure.setText("Dừng đo");
        tvStatus.setText("Đang chờ dữ liệu từ cảm biến...");
        simulateOxygenLevel(); // fallback nếu không có BLE
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
                    int o2 = 95 + random.nextInt(6);
                    tvOxygenLevel.setText(o2 + "%");
                    dataManager.saveOxygenData(o2);
                    if (o2 < 95) tvStatus.setText("Nồng độ oxy thấp");
                    else         tvStatus.setText("Nồng độ oxy bình thường");
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