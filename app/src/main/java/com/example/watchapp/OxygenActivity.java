package com.example.watchapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.List;

public class OxygenActivity extends BaseActivity {

    private static final String TAG = "OxygenActivity";

    private TextView tvOxygenLevel, tvStatus, tvCurrentSpo2;
    private ChartView chartView;
    private Button btnBack;
    private HealthDataManager dataManager;

    private Handler handler;
    private Runnable fingerLostRunnable;

    private int lastValidSpo2 = 0;
    private String lastStatus = "";

    // ===============================
    // BLE RECEIVER
    // ===============================
    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case BLEService.ACTION_GATT_CONNECTED:
                    tvStatus.setText("Đã kết nối BLE");
                    break;
                case BLEService.ACTION_GATT_DISCONNECTED:
                    tvStatus.setText("Mất kết nối BLE");
                    tvOxygenLevel.setText("--");
                    break;
                case BLEService.ACTION_DATA_AVAILABLE:
                    handleSensorData(intent);
                    break;
            }
        }
    };

    // ===============================
    // LIFECYCLE
    // ===============================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oxygen);

        dataManager = HealthDataManager.getInstance(this);
        handler = new Handler(getMainLooper());

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus      = findViewById(R.id.tvStatus);
        tvCurrentSpo2 = findViewById(R.id.tvCurrentSpo2);
        chartView     = findViewById(R.id.chartView);
        btnBack       = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

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

    // ===============================
    // XỬ LÝ DỮ LIỆU BLE
    // ===============================
    private void setStatus(String newStatus) {
        if (!newStatus.equals(lastStatus)) {
            tvStatus.setText(newStatus);
            lastStatus = newStatus;
        }
    }

    private void handleSensorData(Intent intent) {
        int spo2   = intent.getIntExtra(BLEService.EXTRA_SPO2,   -1);
        int finger = intent.getIntExtra(BLEService.EXTRA_FINGER, -1);
        int motion = intent.getIntExtra(BLEService.EXTRA_MOTION,  0);

        // 👉 THẢ TAY (delay 500ms chống nháy)
        if (finger == 0) {
            if (fingerLostRunnable != null) {
                handler.removeCallbacks(fingerLostRunnable);
            }
            fingerLostRunnable = () -> {
                lastValidSpo2 = 0;
                setStatus("Chưa đặt tay lên cảm biến");
                tvOxygenLevel.setText("--");
                refreshChart();
            };
            handler.postDelayed(fingerLostRunnable, 500);
            return;
        }

        // 👉 CÓ TAY LẠI → hủy reset
        if (fingerLostRunnable != null) {
            handler.removeCallbacks(fingerLostRunnable);
        }

        // 👉 CHƯA ĐO ĐƯỢC
        if (spo2 <= 0) {
            if (lastValidSpo2 == 0) {
                setStatus("Đang đo nồng độ oxy...");
                tvOxygenLevel.setText("--");
            } else {
                tvOxygenLevel.setText(lastValidSpo2 + "%");
                refreshChart(); // ✅ fix: cập nhật tvCurrentSpo2
            }
            return;
        }

        // 👉 DỮ LIỆU HỢP LỆ
        lastValidSpo2 = spo2;

        if (motion == 1)    setStatus("Cảnh báo: đang chuyển động");
        else if (spo2 < 60) setStatus("Nồng độ oxy nguy hiểm!");
        else if (spo2 < 80) setStatus("Nồng độ oxy thấp");
        else                setStatus("Nồng độ oxy bình thường");

        tvOxygenLevel.setText(spo2 + "%");

        dataManager.saveOxygenData(spo2);
        refreshChart();
    }

    // ===============================
    // CHART
    // ===============================
    private void refreshChart() {
        List<HealthDataManager.HealthDataPoint> data = dataManager.getOxygenData();
        chartView.setData(data, 0xFF1E88E5, 85, 100);
        tvCurrentSpo2.setText(lastValidSpo2 > 0 ? lastValidSpo2 + "%" : "No data");
    }
}