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

public class BLEService extends Service {
    private static final String TAG = "BLEService";

    public static final UUID SERVICE_UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    public static final UUID CHARACTERISTIC_SENSOR_DATA_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    public static final UUID CHARACTERISTIC_COMMAND_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final String ACTION_GATT_CONNECTED    = "com.example.watchapp.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED = "com.example.watchapp.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_GATT_SERVICES_DISCOVERED = "com.example.watchapp.ACTION_GATT_SERVICES_DISCOVERED";
    public static final String ACTION_DATA_AVAILABLE    = "com.example.watchapp.ACTION_DATA_AVAILABLE";
    public static final String ACTION_FALL_DETECTED     = "com.example.watchapp.FALL_DETECTED";

    public static final String EXTRA_DATA               = "com.example.watchapp.EXTRA_DATA";
    public static final String EXTRA_BPM                = "com.example.watchapp.EXTRA_BPM";
    public static final String EXTRA_SPO2               = "com.example.watchapp.EXTRA_SPO2";
    public static final String EXTRA_FINGER             = "com.example.watchapp.EXTRA_FINGER";
    public static final String EXTRA_ACCEL_X            = "com.example.watchapp.EXTRA_ACCEL_X";
    public static final String EXTRA_ACCEL_Y            = "com.example.watchapp.EXTRA_ACCEL_Y";
    public static final String EXTRA_ACCEL_Z            = "com.example.watchapp.EXTRA_ACCEL_Z";
    public static final String EXTRA_MOTION             = "com.example.watchapp.EXTRA_MOTION";
    public static final String EXTRA_MAG                = "com.example.watchapp.EXTRA_MAG";
    public static final String EXTRA_FALL_PROBABILITY   = "com.example.watchapp.EXTRA_FALL_PROBABILITY";

    private BluetoothManager  bluetoothManager;
    private BluetoothAdapter  bluetoothAdapter;
    private BluetoothGatt     bluetoothGatt;
    private String            deviceAddress;
    private int               connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING   = 1;
    private static final int STATE_CONNECTED    = 2;

    // ── Fall Detection Model ───────────────────────────────────────────
    private FallDetectionModel fallDetectionModel;
    private long lastFallAlertTime = 0;
    private static final long FALL_ALERT_COOLDOWN = 5000; // 5 giây giữa các lần cảnh báo
    private int inferenceCounter = 0; // Đếm số lần inference (để log thưa hơn)

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BLEService getService() { return BLEService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Khởi tạo fall detection model
        fallDetectionModel = new FallDetectionModel(this);
        if (!fallDetectionModel.isModelReady()) {
            Log.e(TAG, "⚠️ Fall detection model failed to load!");
        } else {
            Log.d(TAG, "✓ Fall detection model initialized");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fallDetectionModel != null) {
            fallDetectionModel.close();
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { close(); return super.onUnbind(intent); }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED;
                broadcastUpdate(ACTION_GATT_CONNECTED);
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                // Yêu cầu MTU trước — notification sẽ được bật sau khi MTU OK
                Log.d(TAG, "Requesting MTU=64...");
                gatt.requestMtu(64);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to: " + mtu + " status=" + status);
            // MTU đã được negotiate xong → bây giờ mới enable notify an toàn
            enableSensorDataNotifications();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }
    };

    private void broadcastUpdate(String action) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(action));
    }

    private void broadcastUpdate(String action, BluetoothGattCharacteristic characteristic) {
        if (!CHARACTERISTIC_SENSOR_DATA_UUID.equals(characteristic.getUuid())) return;

        String raw = new String(characteristic.getValue()).trim();
        Log.d(TAG, "Raw BLE data: " + raw);

        Intent intent = new Intent(action);
        intent.putExtra(EXTRA_DATA, raw); // gửi raw string để Activity tự parse

        //  PARSE FORMAT MỚI: "B:0,S:0,F:0,AX:0.83,AY:-0.41,AZ:-0.52,M:10.33"
        int   bpm    = -1;
        int   spo2   = -1;
        int   finger = -1;
        float ax     = 0f;
        float ay     = 0f;
        float az     = 0f;
        float mag    = -1f;

        try {
            // Split theo dấu phẩy
            String[] parts = raw.split(",");

            for (String part : parts) {
                part = part.trim();

                if (part.startsWith("B:")) {
                    bpm = Integer.parseInt(part.substring(2));
                } else if (part.startsWith("S:")) {
                    spo2 = Integer.parseInt(part.substring(2));
                } else if (part.startsWith("F:")) {
                    finger = Integer.parseInt(part.substring(2));
                } else if (part.startsWith("AX:")) {
                    ax = Float.parseFloat(part.substring(3));
                } else if (part.startsWith("AY:")) {
                    ay = Float.parseFloat(part.substring(3));
                } else if (part.startsWith("AZ:")) {
                    az = Float.parseFloat(part.substring(3));
                } else if (part.startsWith("M:")) {
                    mag = Float.parseFloat(part.substring(2));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage() + " raw=" + raw);
        }

        Log.d(TAG, String.format("Parsed → BPM=%d SpO2=%d Finger=%d AX=%.2f AY=%.2f AZ=%.2f M=%.2f",
                bpm, spo2, finger, ax, ay, az, mag));

        // ── Lưu và đính Extra ─────────────────────────────────────────────
        if (bpm > 0 && bpm <= 220) {
            intent.putExtra(EXTRA_BPM, bpm);
            HealthDataManager.getInstance(this).saveHeartRateData(bpm);
        }
        if (spo2 >= 70 && spo2 <= 100) {
            intent.putExtra(EXTRA_SPO2, spo2);
            HealthDataManager.getInstance(this).saveOxygenData(spo2);
        }
        if (finger >= 0) intent.putExtra(EXTRA_FINGER, finger);

        intent.putExtra(EXTRA_ACCEL_X, ax);
        intent.putExtra(EXTRA_ACCEL_Y, ay);
        intent.putExtra(EXTRA_ACCEL_Z, az);

        if (mag >= 0) {
            intent.putExtra(EXTRA_MAG, mag);

            //  FALL DETECTION with TensorFlow Lite Model
            // Convert M từ m/s² sang g (1g = 9.8 m/s²)
            float magInG = mag / 9.8f;

            // Thêm data point vào model buffer
            if (fallDetectionModel != null && fallDetectionModel.isModelReady()) {
                fallDetectionModel.addDataPoint(magInG);

                // Chạy inference khi buffer đủ 512 điểm
                if (fallDetectionModel.isReadyForInference()) {
                    inferenceCounter++;

                    float probability = fallDetectionModel.predict();
                    intent.putExtra(EXTRA_FALL_PROBABILITY, probability);

                    // Log thưa hơn (mỗi 10 lần)
                    if (inferenceCounter % 10 == 0) {
                        Log.d(TAG, "Fall detection inference #" + inferenceCounter +
                                " → Probability: " + String.format("%.3f", probability));
                    }

                    // Kiểm tra fall detection
                    long currentTime = System.currentTimeMillis();
                    if (probability > fallDetectionModel.getFallThreshold() &&
                            currentTime - lastFallAlertTime > FALL_ALERT_COOLDOWN) {

                        lastFallAlertTime = currentTime;

                        Log.w(TAG, "🚨 FALL DETECTED! Probability: " +
                                String.format("%.3f", probability) +
                                " (threshold: " + fallDetectionModel.getFallThreshold() + ")");

                        // Gửi broadcast fall detected
                        Intent fallIntent = new Intent(ACTION_FALL_DETECTED);
                        fallIntent.putExtra("magnitude", (double) mag);
                        fallIntent.putExtra("probability", probability);
                        LocalBroadcastManager.getInstance(BLEService.this).sendBroadcast(fallIntent);

                        // Hiển thị notification
                        FallNotificationHelper.showFallNotification(BLEService.this, mag);

                        // Clear buffer để tránh trigger liên tục
                        fallDetectionModel.clearBuffer();
                        inferenceCounter = 0;
                    }
                }
            }
        }

        // Motion: phát hiện chuyển động đơn giản (ngưỡng 0.3g)
        int motion = 0;
        if (mag > 0) {
            float magInG = mag / 9.8f;
            float diff = Math.abs(magInG - 1.0f);
            motion = (diff > 0.3f) ? 1 : 0;
        }
        intent.putExtra(EXTRA_MOTION, motion);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public boolean initialize() {
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        return bluetoothAdapter != null;
    }

    public boolean connect(String address) {
        if (!hasPermission() || bluetoothAdapter == null || address == null) return false;

        if (deviceAddress != null && address.equals(deviceAddress) && bluetoothGatt != null) {
            connectionState = STATE_CONNECTING;
            return bluetoothGatt.connect();
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) return false;

        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        deviceAddress = address;
        connectionState = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (hasPermission() && bluetoothGatt != null) bluetoothGatt.disconnect();
    }

    public void close() {
        if (bluetoothGatt != null) { bluetoothGatt.close(); bluetoothGatt = null; }
    }

    public void enableSensorDataNotifications() {
        if (!hasPermission() || bluetoothGatt == null) return;

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) { Log.e(TAG, "Service not found"); return; }

        BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_SENSOR_DATA_UUID);
        if (ch == null) { Log.e(TAG, "Characteristic not found"); return; }

        bluetoothGatt.setCharacteristicNotification(ch, true);

        BluetoothGattDescriptor desc = ch.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(desc);
        }
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

    public boolean isConnected() { return connectionState == STATE_CONNECTED; }
    public String getDeviceAddress() { return deviceAddress; }

    // ── Public API cho testing ────────────────────────────────────────

    public String getFallModelStats() {
        if (fallDetectionModel == null) return "Model not initialized";
        return fallDetectionModel.getBufferStats();
    }

    public boolean isFallModelReady() {
        return fallDetectionModel != null && fallDetectionModel.isModelReady();
    }
}