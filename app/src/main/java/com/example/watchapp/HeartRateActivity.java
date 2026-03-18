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
import java.util.List;
import java.util.Random;
import android.view.View;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class HeartRateActivity extends BaseActivity implements SensorEventListener {
    private static final String TAG = "HeartRateActivity";

    // UI
    private TextView tvHeartRate, tvStatus, tvAverage;
    private Button btnMeasure, btnBack;
    private ChartView chartView;    // Sensor
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;
    private HealthDataManager dataManager;

    // Firebase
    private DatabaseReference heartRateRef;
    private ValueEventListener heartRateListener;

    // ─── BLE Broadcast Receiver ──────────────────────────────
    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case BLEService.ACTION_GATT_CONNECTED:
                    tvStatus.setText(R.string.ble_connected);
                    break;

                case BLEService.ACTION_GATT_DISCONNECTED:
                    tvStatus.setText(R.string.ble_disconnected);
                    tvHeartRate.setText("--");
                    break;

                case BLEService.ACTION_DATA_AVAILABLE:
                    handleSensorData(intent);
                    break;
            }
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        dataManager = HealthDataManager.getInstance(this);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus = findViewById(R.id.tvStatus);
        btnBack = findViewById(R.id.btnBack);
        tvAverage = findViewById(R.id.tvAverage);
        chartView = findViewById(R.id.chartView);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        // Initial chart render with stored history
        refreshChart();

        // Lắng nghe Firebase — dữ liệu do MainActivity gửi lên mỗi 3s
        heartRateRef = FirebaseDatabase.getInstance().getReference("heart_rate");
        startFirebaseListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter);


        btnBack.setOnClickListener(v -> finish());
    }


    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
    }


    private void startFirebaseListener() {
        tvStatus.setText("Đang chờ dữ liệu...");

        heartRateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvHeartRate.setText("--");
                    tvStatus.setText("Chưa có dữ liệu");
                    return;
                }

                Integer bpm = snapshot.child("value").getValue(Integer.class);
                if (bpm == null) return;

                Log.d(TAG, "Firebase → " + bpm + " BPM");

                // Hiển thị lên vòng tròn
                tvHeartRate.setText(String.valueOf(bpm));

                // Trạng thái
                if      (bpm < 60)  tvStatus.setText("Nhịp tim thấp");
                else if (bpm > 100) tvStatus.setText("Nhịp tim cao");
                else                tvStatus.setText("Nhịp tim bình thường");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Lỗi: " + error.getMessage());
                tvStatus.setText("Lỗi kết nối Firebase");
            }
        };

        heartRateRef.addValueEventListener(heartRateListener);
    }





    // ─── Data handling ────────────────────────────────────────
    private void handleSensorData(Intent intent) {
        int bpm    = intent.getIntExtra(BLEService.EXTRA_BPM,    -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        if (finger == 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Chưa đặt tay lên cảm biến");
            return;
        }
        if (bpm <= 0) {
            tvHeartRate.setText("--");
            tvStatus.setText("Đang đo nhịp tim...");
            return;
        }
        tvHeartRate.setText(bpm + " BPM");

        if (motion == 1) {
            tvStatus.setText("Cảnh báo: đang chuyển động");
        } else if (bpm < 60) {
            tvStatus.setText("Nhịp tim thấp");
        } else if (bpm > 100) {
            tvStatus.setText("Nhịp tim cao");
        } else {
            tvStatus.setText("Nhịp tim bình thường");
        }

        refreshChart();
    }

    // ─── SensorEventListener ─────────────────────────────────
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            int bpm = (int) event.values[0];
            if (bpm > 0) {
                tvHeartRate.setText(String.valueOf(bpm));
                if      (bpm < 60)  tvStatus.setText("Nhịp tim thấp");
                else if (bpm > 100) tvStatus.setText("Nhịp tim cao");
                else                tvStatus.setText("Nhịp tim bình thường");

                dataManager.saveHeartRateData(bpm);
                refreshChart();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getHeartRateData();

        // Chart: red line, range 40–180 bpm
        chartView.setData(data, 0xFFE53935, 40, 180);

        // Average
        int avg = dataManager.getAverageHeartRate();
        if (avg > 0) {
            tvAverage.setText(getString(R.string.average_value, avg) + " BPM");
        } else {
            tvAverage.setText(R.string.no_data);
        }
    }


}