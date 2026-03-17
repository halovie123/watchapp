package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.Random;

public class HeartRateActivity extends AppCompatActivity implements SensorEventListener {
    private TextView tvHeartRate, tvStatus;
    private Button btnMeasure, btnBack;
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;
    private HealthDataManager dataManager;

    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BLEService.ACTION_DATA_AVAILABLE.equals(intent.getAction())) return;

            // Lấy trực tiếp từ Extra — BLEService đã parse sẵn
            int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM,    -1);
            int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);

            if (finger == 0) {
                tvStatus.setText("Chưa đặt tay lên cảm biến");
                tvHeartRate.setText("--");
                return;
            }

            if (bpm > 0) {
                tvHeartRate.setText(bpm + " BPM");
                // Không cần saveHeartRateData — BLEService đã lưu rồi
                if      (bpm < 60)  tvStatus.setText("Nhịp tim thấp");
                else if (bpm > 100) tvStatus.setText("Nhịp tim cao");
                else                tvStatus.setText("Nhịp tim bình thường");
                Log.d("HeartRateActivity", "BPM từ BLE: " + bpm);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus    = findViewById(R.id.tvStatus);
        btnMeasure  = findViewById(R.id.btnMeasure);
        btnBack     = findViewById(R.id.btnBack);

        sensorManager   = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        handler         = new Handler();
        random          = new Random();
        dataManager     = HealthDataManager.getInstance(this);

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

        if (heartRateSensor != null) {
            sensorManager.registerListener(this, heartRateSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            simulateHeartRate(); // fallback nếu không có BLE
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
                    int hr = 60 + random.nextInt(40);
                    tvHeartRate.setText(hr + " BPM");
                    dataManager.saveHeartRateData(hr);
                    if      (hr < 60)  tvStatus.setText("Nhịp tim thấp");
                    else if (hr > 100) tvStatus.setText("Nhịp tim cao");
                    else               tvStatus.setText("Nhịp tim bình thường");
                    handler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            int hr = (int) event.values[0];
            tvHeartRate.setText(hr + " BPM");
            dataManager.saveHeartRateData(hr);
            if      (hr < 60)  tvStatus.setText("Nhịp tim thấp");
            else if (hr > 100) tvStatus.setText("Nhịp tim cao");
            else               tvStatus.setText("Nhịp tim bình thường");
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMeasurement();
    }
}