package com.example.watchapp;

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

public class OxygenActivity extends BaseActivity {

    private static final String TAG = "OxygenActivity";

    // UI
    private TextView tvOxygenLevel;
    private TextView tvStatus;
    private Button   btnBack;

    // Firebase
    private DatabaseReference oxygenRef;
    private ValueEventListener oxygenListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oxygen);

        tvOxygenLevel = findViewById(R.id.tvOxygenLevel);
        tvStatus      = findViewById(R.id.tvStatus);
        btnBack       = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Lắng nghe Firebase — dữ liệu do MainActivity gửi lên mỗi 3s
        oxygenRef = FirebaseDatabase.getInstance().getReference("oxygen_level");
        startFirebaseListener();
    }

    // =========================================================================
    //  ĐỌC DỮ LIỆU TỪ FIREBASE (realtime)
    // =========================================================================

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

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (oxygenRef != null && oxygenListener != null)
            oxygenRef.removeEventListener(oxygenListener);
    }
}