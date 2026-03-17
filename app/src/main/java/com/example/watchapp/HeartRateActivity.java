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
 * HeartRateActivity — displays real-time HR data streamed from ESP32 via BLE.
 * No manual measure button; data arrives automatically from BLEService.
 */
public class HeartRateActivity extends BaseActivity {

    private TextView tvHeartRate;
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
                    tvHeartRate.setText("--");
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
        setContentView(R.layout.activity_heart_rate);

        dataManager = HealthDataManager.getInstance(this);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus    = findViewById(R.id.tvStatus);
        tvAverage   = findViewById(R.id.tvAverage);
        chartView   = findViewById(R.id.chartView);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Initial chart render with stored history
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

            if (!json.has("heartRate")) return;
            int hr = json.getInt("heartRate");

            // -1 means algorithm has no valid result yet (finger not placed etc.)
            if (hr < 0) {
                tvHeartRate.setText("--");
                tvStatus.setText(R.string.heart_rate_waiting);
                return;
            }

            // Display live value
            tvHeartRate.setText(hr + " BPM");

            // Status based on physiological range
            if (hr < 60) {
                tvStatus.setText(R.string.heart_rate_low);
            } else if (hr > 100) {
                tvStatus.setText(R.string.heart_rate_high);
            } else {
                tvStatus.setText(R.string.heart_rate_normal);
            }

            // Show motion warning if significant noise was detected
            if (json.has("motionPct") && json.getInt("motionPct") > 50) {
                tvStatus.setText(R.string.heart_rate_motion_warning);
            }

            // Update chart and average (data already saved by BLEService →
            //   HealthDataManager.saveHeartRateData)
            refreshChart();

        } catch (JSONException e) {
            tvStatus.setText(R.string.data_parse_error);
        }
    }

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