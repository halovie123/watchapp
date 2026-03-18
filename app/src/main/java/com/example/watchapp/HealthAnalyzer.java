package com.example.watchapp;

import android.content.Context;
import android.util.Log;

import java.util.List;


public class HealthAnalyzer {

    private static final String TAG = "HealthAnalyzer";

    // =========================================================================
    //  DATA MODEL
    // =========================================================================

    public static class AnalysisResult {
        public int    avgBpm, latestBpm, minBpm, maxBpm;
        public int    avgSpo2, latestSpo2;
        public float  avgAccelMag, avgGyroMag;
        public String activityLevel;    // REST / LIGHT / MODERATE / ACTIVE / INTENSE
        public String heartStatus;      // NORMAL / LOW / HIGH / CRITICAL_LOW / CRITICAL_HIGH
        public String oxygenStatus;     // NORMAL / BORDERLINE / LOW / CRITICAL
        public String heartTrend;       // STABLE / RISING / FALLING
        public String oxygenTrend;      // STABLE / RISING / FALLING
        public int    riskScore;        // 0–100
        public int    totalRecords;

        // Raw NN output (0-3 mỗi cái)
        public int nnHrRisk, nnSpo2Risk, nnActivity, nnOverall;
    }

    public static class HealthRecord {
        public int    heartRate, spo2;
        public String fall;
        public String timestamp;

        public HealthRecord(int heartRate, int spo2, String fall, String timestamp) {
            this.heartRate = heartRate;
            this.spo2      = spo2;
            this.fall      = fall;
            this.timestamp = timestamp;
        }
    }

    // =========================================================================
    //  ANALYZE
    // =========================================================================

    public static AnalysisResult analyze(Context ctx, List<HealthRecord> records) {
        AnalysisResult r = new AnalysisResult();
        r.totalRecords = records.size();

        if (records.isEmpty()) {
            r.heartStatus  = "NO_DATA"; r.oxygenStatus = "NO_DATA";
            r.activityLevel= "UNKNOWN"; r.heartTrend = r.oxygenTrend = "STABLE";
            r.riskScore = 0;
            return r;
        }

        // ── Tính thống kê ─────────────────────────────────────────────────────
        int sumBpm=0, sumSpo2=0;
        float sumAccel=0, sumGyro=0;
        r.minBpm = Integer.MAX_VALUE;
        r.maxBpm = Integer.MIN_VALUE;

        for (HealthRecord rec : records) {
            sumBpm   += rec.heartRate;
            sumSpo2  += rec.spo2;
            if (rec.heartRate < r.minBpm) r.minBpm = rec.heartRate;
            if (rec.heartRate > r.maxBpm) r.maxBpm = rec.heartRate;
        }

        int n = records.size();
        r.avgBpm      = sumBpm  / n;
        r.avgSpo2     = sumSpo2 / n;
        r.avgAccelMag = sumAccel / n;
        r.avgGyroMag  = sumGyro  / n;
        r.latestBpm   = records.get(n-1).heartRate;
        r.latestSpo2  = records.get(n-1).spo2;

        // ── Neural Network prediction (dùng giá trị trung bình) ──────────────
        NNInference nn = NNInference.load(ctx);
        if (nn != null) {
            int[] pred = nn.predict(r.avgBpm, r.avgSpo2, r.avgAccelMag, r.avgGyroMag);
            r.nnHrRisk   = pred[0];
            r.nnSpo2Risk = pred[1];
            r.nnActivity = pred[2];
            r.nnOverall  = pred[3];
        } else {
            // Fallback nếu load model thất bại
            Log.w(TAG, "Model load failed, using rule-based fallback");
            r.nnHrRisk   = r.avgBpm  < 60||r.avgBpm  >100 ? 2 : 0;
            r.nnSpo2Risk = r.avgSpo2 < 95              ? 2 : 0;
            r.nnActivity = r.avgAccelMag > 14 ? 3 : r.avgAccelMag > 11 ? 1 : 0;
            r.nnOverall  = Math.max(r.nnHrRisk, r.nnSpo2Risk);
        }

        // ── Map NN output → string labels ────────────────────────────────────
        r.heartStatus  = hrRiskLabel(r.nnHrRisk,   r.avgBpm);
        r.oxygenStatus = spo2RiskLabel(r.nnSpo2Risk, r.avgSpo2);
        r.activityLevel= activityLabel(r.nnActivity, r.avgAccelMag);

        // ── Xu hướng ─────────────────────────────────────────────────────────
        r.heartTrend  = calcTrend(records, true);
        r.oxygenTrend = calcTrend(records, false);

        // ── Điểm rủi ro (0–100) ──────────────────────────────────────────────
        r.riskScore = r.nnOverall * 25                               // 0/25/50/75
                + (r.heartTrend.equals("STABLE")  ? 0 : 5)
                + (r.oxygenTrend.equals("STABLE") ? 0 : 5);
        r.riskScore = Math.min(100, r.riskScore);

        return r;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static String hrRiskLabel(int risk, int bpm) {
        if (risk == 0) return "NORMAL";
        if (risk == 1) return bpm < 60 ? "LOW" : "HIGH";
        if (risk == 2) return bpm < 60 ? "LOW" : "HIGH";
        return bpm < 60 ? "CRITICAL_LOW" : "CRITICAL_HIGH";
    }

    private static String spo2RiskLabel(int risk, int spo2) {
        if (risk == 0) return "NORMAL";
        if (risk == 1) return "BORDERLINE";
        if (risk == 2) return "LOW";
        return "CRITICAL";
    }

    private static String activityLabel(int act, float accel) {
        switch (act) {
            case 0: return "REST";
            case 1: return "LIGHT";
            case 2: return "ACTIVE";
            case 3: return "INTENSE";
            default: return "MODERATE";
        }
    }

    private static String calcTrend(List<HealthRecord> records, boolean isHR) {
        if (records.size() < 4) return "STABLE";
        int half = records.size() / 2;
        int s1=0, s2=0;
        for (int i=0; i<half; i++)
            s1 += isHR ? records.get(i).heartRate : records.get(i).spo2;
        for (int i=half; i<records.size(); i++)
            s2 += isHR ? records.get(i).heartRate : records.get(i).spo2;
        int diff = s2/(records.size()-half) - s1/half;
        return diff > 3 ? "RISING" : diff < -3 ? "FALLING" : "STABLE";
    }
}