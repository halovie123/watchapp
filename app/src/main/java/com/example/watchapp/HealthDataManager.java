package com.example.watchapp;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class HealthDataManager {
    private static HealthDataManager instance;
    private SharedPreferences prefs;
    private Gson gson;

    private static final String PREF_NAME = "HealthData";
    private static final String KEY_HEART_RATE_DATA = "heartRateData";
    private static final String KEY_OXYGEN_DATA = "oxygenData";
    private static final int MAX_DATA_POINTS = 24; // 24 giờ

    private HealthDataManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static synchronized HealthDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new HealthDataManager(context.getApplicationContext());
        }
        return instance;
    }

    // Lưu dữ liệu nhịp tim
    public void saveHeartRateData(int heartRate) {
        List<HealthDataPoint> dataList = getHeartRateData();

        long currentTime = System.currentTimeMillis();
        dataList.add(new HealthDataPoint(currentTime, heartRate));

        // Giữ tối đa 24 điểm dữ liệu
        if (dataList.size() > MAX_DATA_POINTS) {
            dataList.remove(0);
        }

        String json = gson.toJson(dataList);
        prefs.edit().putString(KEY_HEART_RATE_DATA, json).apply();
    }

    // Lấy dữ liệu nhịp tim
    public List<HealthDataPoint> getHeartRateData() {
        String json = prefs.getString(KEY_HEART_RATE_DATA, null);
        if (json == null) {
            return new ArrayList<>();
        }

        Type type = new TypeToken<List<HealthDataPoint>>(){}.getType();
        return gson.fromJson(json, type);
    }

    // Lưu dữ liệu oxy
    public void saveOxygenData(int oxygenLevel) {
        List<HealthDataPoint> dataList = getOxygenData();

        long currentTime = System.currentTimeMillis();
        dataList.add(new HealthDataPoint(currentTime, oxygenLevel));

        // Giữ tối đa 24 điểm dữ liệu
        if (dataList.size() > MAX_DATA_POINTS) {
            dataList.remove(0);
        }

        String json = gson.toJson(dataList);
        prefs.edit().putString(KEY_OXYGEN_DATA, json).apply();
    }

    // Lấy dữ liệu oxy
    public List<HealthDataPoint> getOxygenData() {
        String json = prefs.getString(KEY_OXYGEN_DATA, null);
        if (json == null) {
            return new ArrayList<>();
        }

        Type type = new TypeToken<List<HealthDataPoint>>(){}.getType();
        return gson.fromJson(json, type);
    }

    // Tính giá trị trung bình nhịp tim
    public int getAverageHeartRate() {
        List<HealthDataPoint> data = getHeartRateData();
        if (data.isEmpty()) return 0;

        int sum = 0;
        for (HealthDataPoint point : data) {
            sum += point.value;
        }
        return sum / data.size();
    }

    // Tính giá trị trung bình oxy
    public int getAverageOxygen() {
        List<HealthDataPoint> data = getOxygenData();
        if (data.isEmpty()) return 0;

        int sum = 0;
        for (HealthDataPoint point : data) {
            sum += point.value;
        }
        return sum / data.size();
    }

    // Class để lưu điểm dữ liệu
    public static class HealthDataPoint {
        public long timestamp;
        public int value;

        public HealthDataPoint(long timestamp, int value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
}