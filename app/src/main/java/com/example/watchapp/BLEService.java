package com.example.watchapp;

import android.app.Service;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.Manifest;
import android.content.pm.PackageManager;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BLEService — nhận dữ liệu từ ESP32 SmartWatch (NimBLE 2.x)
 *   ESP32 "M:1.03|0.12|..."
 *        │
 *        ▼  parseMpuBatch()
 *   mag_g (đơn vị g, chuẩn trọng trường 1g ≈ 9.81 m/s²)
 *        │
 *        ├─► accel_ms2 = mag_g × 9.81  → Spike check (10–50 m/s²)
 *        │                                 Rule-based: cảnh báo ngay khi spike
 *        │
 *        └─► FallDetectionModel.addDataPoint(mag_g)
 *                 Tích lũy 512 mẫu (sliding window, ~7.68 s)
 *                 → TFLite inference sau mỗi batch khi đủ 512 mẫu
 *                 → Xác nhận té ngã khi probability > FALL_THRESHOLD
 *
 * Chuyển đổi đơn vị:
 *   M:1.03  →  1.03 g  →  1.03 × 9.81 = 10.1 m/s²  (gần ngưỡng cảnh báo)
 *   Đứng yên: M ≈ 1.0 g ≈ 9.81 m/s²
 *   Rơi tự do: M ≈ 0 g ≈ 0 m/s²
 *   Va chạm:   M ≈ 2–5 g ≈ 20–50 m/s²  ← vùng cảnh báo
 */
public class BLEService extends Service {
    private static final String TAG = "BLEService";

    // ── UUID — khớp CHÍNH XÁC với ESP32 main.cpp ─────────────────────────────
    public static final UUID SERVICE_UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");

    /** MPU batch: ASCII "M:1.03|1.02|0.98|..." — 8 mẫu/gói, mỗi 120 ms */
    public static final UUID CHARACTERISTIC_MPU_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    /** Health data: ASCII "B:75,S:98,F:1" — mỗi ~1 s */
    public static final UUID CHARACTERISTIC_HEALTH_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26aa");

    /** Command write (ESP32 nhận lệnh) */
    public static final UUID CHARACTERISTIC_COMMAND_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9");

    /** Datetime write — phone gửi "YYYY-MM-DD HH:MM:SS" mỗi khi kết nối */
    public static final UUID CHARACTERISTIC_DATETIME_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ab");

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ── Broadcast Actions ─────────────────────────────────────────────────────
    public static final String ACTION_GATT_CONNECTED            = "com.example.watchapp.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED         = "com.example.watchapp.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_GATT_SERVICES_DISCOVERED  = "com.example.watchapp.ACTION_GATT_SERVICES_DISCOVERED";
    public static final String ACTION_DATA_AVAILABLE            = "com.example.watchapp.ACTION_DATA_AVAILABLE";
    public static final String ACTION_FALL_DETECTED             = "com.example.watchapp.FALL_DETECTED";

    // ── Extras ────────────────────────────────────────────────────────────────
    public static final String EXTRA_DATA             = "com.example.watchapp.EXTRA_DATA";
    public static final String EXTRA_BPM              = "com.example.watchapp.EXTRA_BPM";
    public static final String EXTRA_SPO2             = "com.example.watchapp.EXTRA_SPO2";
    public static final String EXTRA_FINGER           = "com.example.watchapp.EXTRA_FINGER";
    public static final String EXTRA_MOTION           = "com.example.watchapp.EXTRA_MOTION";
    public static final String EXTRA_MAG              = "com.example.watchapp.EXTRA_MAG";       // m/s²
    public static final String EXTRA_FALL_PROBABILITY = "com.example.watchapp.EXTRA_FALL_PROBABILITY";
    /** FALL flag từ ESP32 hardware (1 = ESP phát hiện ngã, 0 = bình thường) */
    public static final String EXTRA_FALL_ESP         = "com.example.watchapp.EXTRA_FALL_ESP";

    // ── Fall detection constants ───────────────────────────────────────────────
    /**
     * Ngưỡng spike (m/s²): gia tốc nằm trong [SPIKE_LOW, SPIKE_HIGH] → khả năng té ngã.
     *   M:1.03 → 1.03×9.81 = 10.1 m/s²  (ngay trên SPIKE_LOW)
     *   M:2.0  → 19.6 m/s²
     *   M:5.0  → 49.1 m/s²
     * Đứng yên ~9.81 m/s² → đặt SPIKE_LOW > 9.81 để tránh false positive khi nghỉ.
     */
    private static final float SPIKE_LOW_MS2      = 16.0f;  // m/s², dưới đây là bình thường
    private static final float SPIKE_HIGH_MS2     = 60.0f;  // m/s², trên đây là va đập quá mạnh
    private static final float G_MS2              = 9.81f;  // hệ số chuyển đổi g → m/s²

    private static final float MEAN = -0.030605216f;
    private static final float STD = 0.10517566f;

    /**
     * Cooldown 5 s giữa hai lần cảnh báo.
     * Dùng chung cho cả rule-based spike và model-based detection.
     */
    private static final long  FALL_ALERT_COOLDOWN_MS = 5_000L;

    // ── GATT state ────────────────────────────────────────────────────────────
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt    bluetoothGatt;
    private String           deviceAddress;
    private int              connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING   = 1;
    private static final int STATE_CONNECTED    = 2;

    // ── Fall detection state ──────────────────────────────────────────────────
    private FallDetectionModel fallDetectionModel;
    private long lastFallAlertTime  = 0;
    private int  inferenceCounter   = 0;

    /**
     * Cache: gia tốc cuối nhận được, đơn vị m/s².
     * Cập nhật mỗi sample trong parseMpuBatch().
     * Dùng bởi broadcastUpdate() (health packet) để gắn kèm EXTRA_MAG.
     */
    private volatile float lastAccelMs2 = G_MS2;  // khởi tạo = trọng trường

    // ── Binder ───────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BLEService getService() { return BLEService.this; }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        fallDetectionModel = new FallDetectionModel(this);
        if (!fallDetectionModel.isModelReady()) {
            Log.e(TAG, "Fall detection model failed to load — chỉ dùng rule-based spike");
        } else {
            Log.d(TAG, "Fall detection model initialized (window=512 samples)");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fallDetectionModel != null) fallDetectionModel.close();
    }

    @Override
    public IBinder onBind(Intent intent)  { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { close(); return super.onUnbind(intent); }

    // ── Permission helper ─────────────────────────────────────────────────────
    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }


    //  GATT Callback

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED;
                broadcastSimple(ACTION_GATT_CONNECTED);
                if (hasPermission()) gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                broadcastSimple(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered failed: " + status);
                return;
            }
            broadcastSimple(ACTION_GATT_SERVICES_DISCOVERED);
            // ESP32 NimBLE MTU=64 — Android request 64 để negotiate đúng
            if (hasPermission()) gatt.requestMtu(64);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU negotiated: " + mtu + " (status=" + status + ")");
            // Bật notification tuần tự:
            // GATT spec yêu cầu writeDescriptor xong rồi mới writeDescriptor tiếp
            // → enableMpuNotifications() trước, health notifications trong onDescriptorWrite
            enableMpuNotifications();
        }

        /**
         * onDescriptorWrite: bật notification thứ 2 sau khi thứ 1 hoàn thành.
         * Ghi 2 descriptor đồng thời có thể bị mất gói trên một số thiết bị.
         */
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            UUID charUuid = descriptor.getCharacteristic().getUuid();
            if (CHARACTERISTIC_MPU_UUID.equals(charUuid)) {
                Log.d(TAG, "✓ MPU notify enabled → enabling Health notify");
                enableHealthNotifications();
            } else if (CHARACTERISTIC_HEALTH_UUID.equals(charUuid)) {
                Log.d(TAG, "✓ Health notify enabled → sending datetime to ESP32");
                // Gửi datetime ngay sau khi tất cả notify đã được bật
                sendDatetimeToEsp();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();

            if (CHARACTERISTIC_MPU_UUID.equals(uuid)) {
                // ASCII "M:1.03|1.02|0.98|..." → 8 mẫu/gói
                parseMpuBatch(new String(characteristic.getValue()).trim());

            } else if (CHARACTERISTIC_HEALTH_UUID.equals(uuid)) {
                // ASCII "B:75,S:98,F:1"
                parseHealthData(new String(characteristic.getValue()).trim());
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                    CHARACTERISTIC_HEALTH_UUID.equals(characteristic.getUuid())) {
                parseHealthData(new String(characteristic.getValue()).trim());
            }
        }
    };


    //  Parser 1 — MPU batch "M:1.03|1.02|0.98|..."
    //
    //  Format gửi từ taskMPU (ESP32):
    //    "M:" + 8 giá trị ngăn cách "|"
    //    Mỗi giá trị = magnitude gia tốc, đơn vị g (MPU6050 ±2g / 16384 LSB/g)
    //
    //  Chuyển đổi:
    //    mag_g       = giá trị parse được (vd 1.03)
    //    accel_ms2   = mag_g × 9.81          (vd 10.1 m/s²)
    //
    //  Pipeline:
    //    → Spike rule: SPIKE_LOW_MS2 ≤ accel_ms2 ≤ SPIKE_HIGH_MS2 → cảnh báo ngay
    //    → Model:      feed mag_g vào sliding window 512 mẫu
    //                  inference mỗi batch sau khi đủ 512 mẫu

    private void parseMpuBatch(String raw) {
        // raw vd: "M:1.03|1.02|0.98|1.01|1.00|1.04|0.99|1.02"
        if (raw == null || !raw.startsWith("M:")) {
            Log.w(TAG, "MPU packet không hợp lệ: " + raw);
            return;
        }

        String[] tokens = raw.substring(2).split("\\|");
        if (tokens.length == 0) {
            Log.w(TAG, "MPU packet rỗng sau 'M:'");
            return;
        }

        boolean spikeInBatch   = false;   // có mẫu nào vượt ngưỡng trong batch không
        float   maxAccelInBatch = 0f;     // giá trị accel lớn nhất trong batch (m/s²)

        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;

            float magG;
            try {
                magG = Float.parseFloat(token);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Bỏ qua token không parse được: '" + token + "'");
                continue;
            }

            // ── Chuyển đổi sang m/s² ─────────────────────────────────────
            float accelMs2 = magG * G_MS2;
            lastAccelMs2   = accelMs2;  // cập nhật cache để broadcastUpdate dùng

            // ── Spike rule-based detection ────────────────────────────────
            // Ngưỡng [10, 50] m/s²:
            //   < 10.0: bình thường hoặc rơi tự do (gần 0g)
            //   10–50:  khả năng va chạm / té ngã
            //   > 50:   cú va chạm cực mạnh, có thể là rung lắc thiết bị
            if (accelMs2 >= SPIKE_LOW_MS2 && accelMs2 <= SPIKE_HIGH_MS2) {
                spikeInBatch    = true;
                if (accelMs2 > maxAccelInBatch) maxAccelInBatch = accelMs2;
            }

            // ── Feed vào model (đơn vị g — khớp với FallDetectionModel) ──
            if (fallDetectionModel != null && fallDetectionModel.isModelReady()) {
                float magNoG = magG - 1.0f;
                float normalized = (magNoG - MEAN) / STD;
                fallDetectionModel.addDataPoint(normalized);
                Log.d("MODEL_INPUT", String.format(
                        "raw=%.3f | noG=%.3f | norm=%.3f",
                        magG, magNoG, normalized
                ));
            }
        }

        Log.d(TAG, String.format(java.util.Locale.US,
                "[MPU] %d mẫu | maxAccel=%.2f m/s² | spike=%b | buf=%s",
                tokens.length, maxAccelInBatch, spikeInBatch,
                fallDetectionModel != null ? fallDetectionModel.getBufferStats() : "N/A"));

        // ── Broadcast accel hiện tại cho UI ──────────────────────────────
        Intent uiIntent = new Intent(ACTION_DATA_AVAILABLE);
        uiIntent.putExtra(EXTRA_MAG, lastAccelMs2);
        uiIntent.putExtra(EXTRA_MOTION, spikeInBatch ? 1 : 0);
        LocalBroadcastManager.getInstance(this).sendBroadcast(uiIntent);

        // ── Chạy inference TFLite sau mỗi batch đủ 512 mẫu ──────────────
        runFallInference(spikeInBatch, maxAccelInBatch);
    }


    //  Fall inference — gọi sau mỗi MPU batch
    //
    //  Logic kép:
    //    1. Model TFLite (primary):   probability > FALL_THRESHOLD → cảnh báo
    //    2. Rule-based spike (backup): spikeInBatch = true VÀ model chưa sẵn sàng
    //       (buffer chưa đủ 512 mẫu) → cảnh báo dựa trên spike
    //
    //  Sliding window: inference mỗi batch (mỗi 8 mẫu mới), model tự dùng
    //  512 mẫu gần nhất trong FallDetectionModel.dataBuffer.

    private void runFallInference(boolean spikeInBatch, float maxAccelInBatch) {
        if (fallDetectionModel == null) {
            return;
        }

        if (!spikeInBatch) {
            return;
        }

        float variance = fallDetectionModel.calculateVariance();

        if (variance < 0.0005f) {
            Log.d(TAG, "[Fall] Ignored - too static (variance=" + variance + ")");
            return;
        }

        Log.d(TAG, String.format(
                "DEBUG → variance=%.6f | spike=%b | accel=%.2f",
                variance, spikeInBatch, lastAccelMs2
        ));

        boolean fallDetected   = false;
        float   probability    = -1f;
        String  detectionType  = "";

        if (fallDetectionModel.isReadyForInference()) {
            // ── Primary: TFLite model ─────────────────────────────────────
            probability = fallDetectionModel.predict();
            inferenceCounter++;

            if (inferenceCounter % 10 == 0) {      // log mỗi 10 lần (~1.2 s)
                Log.d(TAG, String.format(java.util.Locale.US,
                        "[Fall] inference #%d → P=%.3f | lastAccel=%.1f m/s²",
                        inferenceCounter, probability, lastAccelMs2));
            }

            // Broadcast probability để UI hiển thị gauge
            Intent probIntent = new Intent(ACTION_DATA_AVAILABLE);
            probIntent.putExtra(EXTRA_FALL_PROBABILITY, probability);
            LocalBroadcastManager.getInstance(this).sendBroadcast(probIntent);

            if (probability > fallDetectionModel.getFallThreshold() && spikeInBatch) {
                fallDetected  = true;
                detectionType = "MODEL";
            }
        }

        // ── Backup: rule-based (khi model chưa sẵn sàng hoặc xác nhận thêm) ─
        // Dùng khi: buffer chưa đủ 512 mẫu (< 7.68 s đầu tiên sau khi kết nối)
        if (!fallDetected && spikeInBatch && !fallDetectionModel.isReadyForInference()) {
            // Spike đơn lẻ mạnh (> 15 m/s² = 1.53g): cảnh báo sớm
            if (maxAccelInBatch > 15.0f) {
                fallDetected  = true;
                probability   = -1f;  // chưa có model output
                detectionType = "SPIKE_RULE";
            }
        }

        if (!fallDetected) return;

        // ── Cooldown: tránh cảnh báo liên tục ────────────────────────────
        long now = System.currentTimeMillis();
        if (now - lastFallAlertTime < FALL_ALERT_COOLDOWN_MS) {
            Log.d(TAG, "[Fall] Cooldown còn " +
                    (FALL_ALERT_COOLDOWN_MS - (now - lastFallAlertTime)) + " ms");
            return;
        }
        lastFallAlertTime = now;

        Log.w(TAG, String.format(java.util.Locale.US,
                "🚨 FALL DETECTED [%s] P=%.3f accel=%.1f m/s²",
                detectionType, probability, lastAccelMs2));

        // ── Gửi FALL:YES về ESP32 để hiển thị trên OLED ──────────────────
        sendFallToEsp(true);

        // Broadcast fall event
        Intent fallIntent = new Intent(ACTION_FALL_DETECTED);
        fallIntent.putExtra("magnitude",    (double) lastAccelMs2);
        fallIntent.putExtra("probability",  probability);
        fallIntent.putExtra("detectionType", detectionType);
        LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);

        // System notification (âm thanh + rung)
        FallNotificationHelper.showFallNotification(this, lastAccelMs2);

        // Clear buffer để tránh re-trigger ngay lập tức
        fallDetectionModel.clearBuffer();
        inferenceCounter = 0;
    }


    //  Parser 2 — Health packet "B:75,S:98,F:1"
    //
    //  Format gửi từ taskMAX30102 (ESP32), mỗi ~1 s:
    //    B:<bpm>, S:<spo2>, F:<0|1>

    private void parseHealthData(String raw) {
        Log.d(TAG, "[Health] " + raw);

        Intent intent = new Intent(ACTION_DATA_AVAILABLE);
        intent.putExtra(EXTRA_DATA, raw);

        int bpm    = -1;
        int spo2   = -1;
        int finger = -1;
        int fallEsp = 0;  // FALL flag từ ESP32 hardware (0 = OK, 1 = ngã)

        try {
            for (String part : raw.split(",")) {
                part = part.trim();
                if      (part.startsWith("B:"))    bpm     = Integer.parseInt(part.substring(2));
                else if (part.startsWith("S:"))    spo2    = Integer.parseInt(part.substring(2));
                else if (part.startsWith("F:"))    finger  = Integer.parseInt(part.substring(2));
                else if (part.startsWith("FALL:")) fallEsp = Integer.parseInt(part.substring(5));
            }
        } catch (Exception e) {
            Log.e(TAG, "Health parse error: " + e.getMessage() + " | raw='" + raw + "'");
        }

        Log.d(TAG, String.format(java.util.Locale.US,
                "[Health] BPM=%d SpO2=%d Finger=%d FallESP=%d", bpm, spo2, finger, fallEsp));

        if (bpm > 0 && bpm <= 220) {
            intent.putExtra(EXTRA_BPM, bpm);
            HealthDataManager.getInstance(this).saveHeartRateData(bpm);
        }
        if (spo2 >= 70 && spo2 <= 100) {
            intent.putExtra(EXTRA_SPO2, spo2);
            HealthDataManager.getInstance(this).saveOxygenData(spo2);
        }
        if (finger >= 0) intent.putExtra(EXTRA_FINGER, finger);

        // Truyền FALL flag của ESP32 về UI
        intent.putExtra(EXTRA_FALL_ESP, fallEsp);

        // Nếu ESP32 phát hiện ngã và chưa trong cooldown → broadcast riêng
        if (fallEsp == 1) {
            long now = System.currentTimeMillis();
            if (now - lastFallAlertTime >= FALL_ALERT_COOLDOWN_MS) {
                lastFallAlertTime = now;
                Log.w(TAG, "[Health] 🚨 FALL reported by ESP32 hardware");
                Intent fallIntent = new Intent(ACTION_FALL_DETECTED);
                fallIntent.putExtra("magnitude",     (double) lastAccelMs2);
                fallIntent.putExtra("probability",   1.0f);
                fallIntent.putExtra("detectionType", "ESP32_HW");
                LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);
                FallNotificationHelper.showFallNotification(this, lastAccelMs2);
            }
        }

        // Gắn kèm accel hiện tại (lấy từ cache MPU)
        intent.putExtra(EXTRA_MAG, lastAccelMs2);
        intent.putExtra(EXTRA_MOTION, lastAccelMs2 >= SPIKE_LOW_MS2 ? 1 : 0);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    //  Datetime sync — gửi giờ điện thoại cho ESP32


    /**
     * Gửi ngày giờ hiện tại của điện thoại lên ESP32 qua CHARACTERISTIC_DATETIME_UUID.
     * Format: "YYYY-MM-DD HH:MM:SS"  (19 bytes ASCII, khớp với DatetimeCallbacks trên ESP32)
     *
     * Gọi tự động sau khi Health notify được enable (kết nối xong).
     * Cũng có thể gọi thủ công từ Activity khi muốn resync.
     */
    public void sendDatetimeToEsp() {
        if (!hasPermission() || bluetoothGatt == null) {
            Log.w(TAG, "[Datetime] Không gửi được — chưa kết nối hoặc thiếu permission");
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "[Datetime] Service không tìm thấy");
            return;
        }

        BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_DATETIME_UUID);
        if (ch == null) {
            Log.e(TAG, "[Datetime] Characteristic không tìm thấy (" + CHARACTERISTIC_DATETIME_UUID + ")");
            return;
        }

        // Lấy thời gian điện thoại, format "YYYY-MM-DD HH:MM:SS"
        String datetime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        ch.setValue(datetime.getBytes());
        boolean ok = bluetoothGatt.writeCharacteristic(ch);
        Log.i(TAG, "[Datetime] Gửi \"" + datetime + "\" → " + (ok ? "OK" : "FAIL"));
    }

    //  BLE notification helpers


    /**
     * Bước 1: Bật notify cho CHARACTERISTIC_MPU_UUID ("...26a8").
     * Sau khi writeDescriptor thành công, onDescriptorWrite() sẽ gọi enableHealthNotifications().
     */
    public void enableMpuNotifications() {
        setNotification(CHARACTERISTIC_MPU_UUID, "MPU");
    }

    /**
     * Bước 2: Bật notify cho CHARACTERISTIC_HEALTH_UUID ("...26aa").
     * Gọi từ onDescriptorWrite() sau khi MPU notify đã được enable.
     */
    public void enableHealthNotifications() {
        setNotification(CHARACTERISTIC_HEALTH_UUID, "Health");
    }

    /** Helper chung để bật notification cho một characteristic */
    private void setNotification(UUID charUuid, String label) {
        if (!hasPermission() || bluetoothGatt == null) return;

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) { Log.e(TAG, "Service not found"); return; }

        BluetoothGattCharacteristic ch = service.getCharacteristic(charUuid);
        if (ch == null) {
            Log.e(TAG, label + " characteristic not found (" + charUuid + ")");
            return;
        }

        bluetoothGatt.setCharacteristicNotification(ch, true);

        BluetoothGattDescriptor desc = ch.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(desc);
            Log.d(TAG, "→ Enabling " + label + " notifications...");
        } else {
            Log.e(TAG, label + " CCCD descriptor not found");
        }
    }


    //  GATT connection management


    public boolean initialize() {
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        return bluetoothAdapter != null;
    }

    public boolean connect(String address) {
        if (!hasPermission() || bluetoothAdapter == null || address == null) return false;

        if (address.equals(deviceAddress) && bluetoothGatt != null) {
            connectionState = STATE_CONNECTING;
            return bluetoothGatt.connect();
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) return false;

        bluetoothGatt    = device.connectGatt(this, false, gattCallback);
        deviceAddress    = address;
        connectionState  = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (hasPermission() && bluetoothGatt != null) bluetoothGatt.disconnect();
    }

    public void close() {
        if (bluetoothGatt != null) { bluetoothGatt.close(); bluetoothGatt = null; }
    }

    public void sendCommand(byte[] command) {
        if (!hasPermission() || bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID);
        if (ch == null) return;
        ch.setValue(command);
        bluetoothGatt.writeCharacteristic(ch);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void broadcastSimple(String action) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(action));
    }

    // ── Fall → ESP32 ──────────────────────────────────────────────────────────

    /**
     * Gửi kết quả fall detection từ TFLite về ESP32 qua CHARACTERISTIC_COMMAND_UUID.
     * ESP32 CommandCallbacks nhận "FALL:YES" / "FALL:NO" → cập nhật g_fallDetected
     * → hiển thị trên OLED.
     *
     * Gọi nội bộ từ runFallInference() — không cần gọi từ bên ngoài.
     *
     * @param isFall true = phát hiện té ngã, false = bình thường
     */
    private void sendFallToEsp(boolean isFall) {
        if (!hasPermission() || bluetoothGatt == null) {
            Log.w(TAG, "[sendFallToEsp] Không gửi được — chưa kết nối hoặc thiếu permission");
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "[sendFallToEsp] Service không tìm thấy");
            return;
        }

        BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID);
        if (ch == null) {
            Log.e(TAG, "[sendFallToEsp] Command characteristic không tìm thấy");
            return;
        }

        String command = isFall ? "FALL:YES" : "FALL:NO";
        ch.setValue(command.getBytes());
        boolean ok = bluetoothGatt.writeCharacteristic(ch);
        Log.i(TAG, "[sendFallToEsp] Gửi \"" + command + "\" → " + (ok ? "OK" : "FAIL"));
    }

    // ── Public API ────────────────────────────────────────────────────────────
    public boolean isConnected()          { return connectionState == STATE_CONNECTED; }
    public String  getDeviceAddress()     { return deviceAddress; }
    public boolean isFallModelReady()     { return fallDetectionModel != null && fallDetectionModel.isModelReady(); }
    public String  getFallModelStats()    { return fallDetectionModel != null ? fallDetectionModel.getBufferStats() : "N/A"; }
    public float   getLastAccelMs2()      { return lastAccelMs2; }
    public float   getLastAccelG()        { return lastAccelMs2 / G_MS2; }
}