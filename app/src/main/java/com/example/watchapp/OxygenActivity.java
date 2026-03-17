package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.json.JSONException;
import org.json.JSONObject;
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
                    String json = intent.getStringExtra(BLEService.EXTRA_DATA);
                    if (json != null) handleSensorData(json);
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
    private void handleSensorData(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);

            if (!json.has("spo2")) return;
            int spo2 = json.getInt("spo2");

            // -1 = algorithm not yet converged
            if (spo2 < 0) {
                tvOxygenLevel.setText("--");
                tvStatus.setText(R.string.oxygen_waiting);
                return;
            }

            // Display live value
            tvOxygenLevel.setText(spo2 + "%");

            // Status based on clinical threshold
            if (spo2 < 90) {
                tvStatus.setText(R.string.oxygen_critical);
            } else if (spo2 < 95) {
                tvStatus.setText(R.string.oxygen_low);
            } else {
                tvStatus.setText(R.string.oxygen_normal);
            }

            // Motion warning
            if (json.has("motionPct") && json.getInt("motionPct") > 50) {
                tvStatus.setText(R.string.oxygen_motion_warning);
            }

            // Refresh chart (BLEService already saved via HealthDataManager)
            refreshChart();

        } catch (JSONException e) {
            tvStatus.setText(R.string.data_parse_error);
        }
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