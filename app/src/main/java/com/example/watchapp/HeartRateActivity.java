package com.example.watchapp;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class HeartRateActivity extends BaseActivity implements SensorEventListener {

    private static final String TAG = "HeartRateActivity";

    // UI
    private TextView tvHeartRate;
    private TextView tvStatus;
    private Button   btnBack;

    // Sensor
    private SensorManager sensorManager;
    private Sensor        heartRateSensor;

    // Firebase
    private DatabaseReference heartRateRef;
    private ValueEventListener heartRateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heart_rate);

        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvStatus    = findViewById(R.id.tvStatus);
        btnBack     = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        sensorManager   = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);

        // Lắng nghe Firebase — dữ liệu do MainActivity gửi lên mỗi 3s
        heartRateRef = FirebaseDatabase.getInstance().getReference("heart_rate");
        startFirebaseListener();
    }

    // =========================================================================
    //  ĐỌC DỮ LIỆU TỪ FIREBASE (realtime)
    // =========================================================================

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

    // =========================================================================
    //  CẢM BIẾN THẬT (dùng khi có phần cứng)
    //  Khi có cảm biến thật: bỏ startFirebaseListener(),
    //  dùng onSensorChanged() → pushToFirebase() thay thế
    // =========================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        int bpm = Math.round(event.values[0]);
        if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) {
            tvStatus.setText("Đặt tay lên cảm biến!"); return;
        }
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || bpm <= 0) {
            tvStatus.setText("Đang hiệu chỉnh..."); return;
        }
        tvHeartRate.setText(String.valueOf(bpm));
        heartRateRef.child("value").setValue(bpm);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (heartRateRef != null && heartRateListener != null)
            heartRateRef.removeEventListener(heartRateListener);
        sensorManager.unregisterListener(this);
    }
}