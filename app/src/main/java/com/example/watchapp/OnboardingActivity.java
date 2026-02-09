package com.example.watchapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class OnboardingActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private Button btnGetStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Kiểm tra xem đã onboard chưa
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        boolean hasOnboarded = prefs.getBoolean("hasOnboarded", false);

        if (hasOnboarded) {
            // Kiểm tra đã có device chưa
            String deviceAddress = prefs.getString("connectedDeviceAddress", null);
            if (deviceAddress != null) {
                // Đã có device -> vào MainActivity
                startActivity(new Intent(this, MainActivity.class));
            } else {
                // Chưa có device -> vào BLEScanActivity
                startActivity(new Intent(this, BLEScanActivity.class));
            }
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);
        btnGetStarted = findViewById(R.id.btnGetStarted);

        OnboardingAdapter adapter = new OnboardingAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {}).attach();

        btnGetStarted.setOnClickListener(v -> {
            prefs.edit().putBoolean("hasOnboarded", true).apply();
            // Chuyển đến BLEScanActivity để kết nối device
            startActivity(new Intent(OnboardingActivity.this, BLEScanActivity.class));

        });
    }
}