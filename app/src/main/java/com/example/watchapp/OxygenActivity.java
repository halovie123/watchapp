package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.List;

/**
 * OxygenActivity — displays real-time SpO2 data streamed from ESP32 via BLE.
 * No manual measure button; data arrives automatically from BLEService.
 */
public class OxygenActivity extends BaseActivity {

    private TextView tvOxygenLevel;
    private TextView tvStatus;
    private TextView tvAverage;
    private ChartView chartView;
    private HealthDataManager dataManager;

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
                    tvOxygenLevel.setText("--");
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
        setContentView(R.layout.activity_oxygen);

        dataManager = HealthDataManager.getInstance(this);

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus      = findViewById(R.id.tvStatus);
        tvAverage     = findViewById(R.id.tvAverage);
        chartView     = findViewById(R.id.chartView);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Render stored history on entry
        refreshChart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_GATT_CONNECTED);
        filter.addAction(BLEService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BLEService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
    }

    // ─── Data handling ────────────────────────────────────────
    private void handleSensorData(Intent intent) {
        int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2,   -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        if (finger == 0) {
            tvOxygenLevel.setText("--");
            tvStatus.setText("Chưa đặt tay lên cảm biến");
            return;
        }
        if (spo2 <= 0) {
            tvOxygenLevel.setText("--");
            tvStatus.setText("Đang đo nồng độ oxy...");
            return;
        }

        tvOxygenLevel.setText(spo2 + "%");

        if (motion == 1) {
            tvStatus.setText("Cảnh báo: đang chuyển động");
        } else if (spo2 < 90) {
            tvStatus.setText("Nồng độ oxy nguy hiểm!");
        } else if (spo2 < 95) {
            tvStatus.setText("Nồng độ oxy thấp");
        } else {
            tvStatus.setText("Nồng độ oxy bình thường");
        }

        refreshChart();
    }

    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getOxygenData();

        // Chart: blue line, range 85–100 %
        chartView.setData(data, 0xFF1E88E5, 85, 100);

        // Average
        int avg = dataManager.getAverageOxygen();
        if (avg > 0) {
            tvAverage.setText(getString(R.string.average_value, avg) + "%");
        } else {
            tvAverage.setText(R.string.no_data);
        }
    }
}