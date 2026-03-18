package com.example.watchapp;

import androidx.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.List;
import java.util.Random;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class OxygenActivity extends BaseActivity {
    private static final String TAG = "OxygenActivity";
    // UI
    private TextView tvOxygenLevel, tvStatus, tvAverage;
    private ChartView chartView;
    private Button btnMeasure, btnBack;
    private boolean isMeasuring = false;
    private Handler handler;
    private Random random;
    private HealthDataManager dataManager;
    // Firebase
    private DatabaseReference oxygenRef;
    private ValueEventListener oxygenListener;


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
        tvStatus = findViewById(R.id.tvStatus);
        tvAverage     = findViewById(R.id.tvAverage);
        chartView     = findViewById(R.id.chartView);
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Render stored history on entry
        refreshChart();

        // Lắng nghe Firebase — dữ liệu do MainActivity gửi lên mỗi 3s
        oxygenRef = FirebaseDatabase.getInstance().getReference("oxygen_level");
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

    private void startFirebaseListener() {
        tvStatus.setText("⏳ Đang chờ dữ liệu...");

        oxygenListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvOxygenLevel.setText("--");
                    tvStatus.setText("Chưa có dữ liệu");
                    return;
                }

                Integer spo2 = snapshot.child("value").getValue(Integer.class);
                if (spo2 == null) return;

                Log.d(TAG, "Firebase → " + spo2 + "%");

                // Hiển thị lên vòng tròn
                tvOxygenLevel.setText(String.valueOf(spo2));

                // Trạng thái
                if      (spo2 < 95) tvStatus.setText("Nồng độ oxy thấp!");
                else if (spo2 < 97) tvStatus.setText("Nồng độ oxy hơi thấp");
                else                tvStatus.setText("Nồng độ oxy bình thường");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Lỗi: " + error.getMessage());
                tvStatus.setText("Lỗi kết nối Firebase");
            }
        };

        oxygenRef.addValueEventListener(oxygenListener);
    }





}