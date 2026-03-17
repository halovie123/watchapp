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
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class BLEService extends Service {
    private static final String TAG = "BLEService";

    // ================= UUIDs =================
    public static final UUID SERVICE_UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    public static final UUID CHARACTERISTIC_SENSOR_DATA_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    public static final UUID CHARACTERISTIC_COMMAND_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9");
    // [MỚI] UUID nhận fall alert trực tiếp từ ESP32 (khớp FALL_UUID trong task_ble.cpp)
    public static final UUID CHARACTERISTIC_FALL_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26aa");

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ================= ACTIONS =================
    public static final String ACTION_GATT_CONNECTED =
            "com.example.watchapp.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED =
            "com.example.watchapp.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.watchapp.ACTION_GATT_SERVICES_DISCOVERED";
    public static final String ACTION_DATA_AVAILABLE =
            "com.example.watchapp.ACTION_DATA_AVAILABLE";
    public static final String EXTRA_DATA =
            "com.example.watchapp.EXTRA_DATA";

    // ================= FIELDS =================
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private String deviceAddress;
    private int connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING   = 1;
    private static final int STATE_CONNECTED    = 2;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BLEService getService() {
            return BLEService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    // ================= PERMISSION CHECK =================
    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    // ================= GATT CALLBACK =================
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED;
                broadcastUpdate(ACTION_GATT_CONNECTED);
                Log.i(TAG, "Connected to GATT server");
                bluetoothGatt.discoverServices();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
                Log.i(TAG, "Disconnected from GATT server");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                // Đăng ký notify HR/SpO2 trước
                enableSensorDataNotifications();
                // [MỚI] Delay 600ms rồi đăng ký fall notify
                // (BLE stack cần xử lý writeDescriptor đầu tiên xong)
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> enableFallNotifications(), 600);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            // [MỚI] Tách riêng xử lý fall characteristic
            if (CHARACTERISTIC_FALL_UUID.equals(characteristic.getUuid())) {
                handleFallCharacteristic(characteristic);
            } else {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }
    };

    // ================= [MỚI] FALL CHARACTERISTIC HANDLER =================
    // Gọi khi ESP32 notify qua CHARACTERISTIC_FALL_UUID
    // Giữ nguyên checkFallRisk() cũ (dùng cho accel data từ sensor JSON)
    private void handleFallCharacteristic(BluetoothGattCharacteristic characteristic) {
        try {
            String jsonString = new String(characteristic.getValue());
            JSONObject json = new JSONObject(jsonString);

            if (!"FALL".equals(json.optString("type"))) return;

            float mag = (float) json.optDouble("mag", 0.0);
            Log.w(TAG, "FALL EVENT from ESP32! magnitude=" + mag + "g");

            // Broadcast giống cũ để các Activity đang lắng nghe vẫn nhận được
            Intent fallIntent = new Intent("com.example.watchapp.FALL_DETECTED");
            fallIntent.putExtra("magnitude", (double) mag);
            LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);

            // [MỚI] Hiển thị system notification — hoạt động cả khi app đóng
            FallNotificationHelper.showFallNotification(this, mag);

        } catch (JSONException e) {
            Log.e(TAG, "Fall JSON error: " + e.getMessage());
        }
    }

    // ================= BROADCAST =================
    private void broadcastUpdate(String action) {
        Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(String action,
                                 BluetoothGattCharacteristic characteristic) {
        Intent intent = new Intent(action);

        if (CHARACTERISTIC_SENSOR_DATA_UUID.equals(characteristic.getUuid())) {
            try {
                String jsonString = new String(characteristic.getValue());
                intent.putExtra(EXTRA_DATA, jsonString);
                parseSensorData(jsonString);
            } catch (Exception e) {
                Log.e(TAG, "Parse error: " + e.getMessage());
            }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ================= JSON PARSE =================
    private void parseSensorData(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);

            if (json.has("heartRate")) {
                HealthDataManager.getInstance(this)
                        .saveHeartRateData(json.getInt("heartRate"));
            }

            if (json.has("spo2")) {
                HealthDataManager.getInstance(this)
                        .saveOxygenData(json.getInt("spo2"));
            }

            if (json.has("accelX")) {
                double x = json.getDouble("accelX");
                double y = json.getDouble("accelY");
                double z = json.getDouble("accelZ");
                checkFallRisk(x, y, z); // Giữ nguyên logic cũ
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSON error: " + e.getMessage());
        }
    }

    // Giữ nguyên — dùng cho accel data trong sensor JSON
    private void checkFallRisk(double x, double y, double z) {
        double magnitude = Math.sqrt(x * x + y * y + z * z);

        if (magnitude > 25.0 || magnitude < 2.0) {
            Intent fallIntent = new Intent("com.example.watchapp.FALL_DETECTED");
            fallIntent.putExtra("magnitude", magnitude);
            LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);
        }
    }

    // ================= INIT =================
    public boolean initialize() {
        bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        return bluetoothAdapter != null;
    }

    // ================= CONNECT =================
    public boolean connect(String address) {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission");
            return false;
        }

        if (bluetoothAdapter == null || address == null) return false;

        if (deviceAddress != null && address.equals(deviceAddress)
                && bluetoothGatt != null) {
            bluetoothGatt.connect();
            connectionState = STATE_CONNECTING;
            return true;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) return false;

        bluetoothGatt     = device.connectGatt(this, false, gattCallback);
        deviceAddress     = address;
        connectionState   = STATE_CONNECTING;
        return true;
    }

    // ================= DISCONNECT =================
    public void disconnect() {
        if (!hasBluetoothConnectPermission()) return;
        if (bluetoothGatt != null) bluetoothGatt.disconnect();
    }

    public void close() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    // ================= ENABLE NOTIFY =================
    public void enableSensorDataNotifications() {
        enableNotifyForCharacteristic(CHARACTERISTIC_SENSOR_DATA_UUID);
    }

    // [MỚI] Đăng ký notify fall alert — gọi sau enableSensorDataNotifications()
    public void enableFallNotifications() {
        enableNotifyForCharacteristic(CHARACTERISTIC_FALL_UUID);
        Log.d(TAG, "Fall notifications enabled");
    }

    // Helper dùng chung để bật notify — tránh lặp code
    private void enableNotifyForCharacteristic(UUID charUuid) {
        if (!hasBluetoothConnectPermission()) return;
        if (bluetoothGatt == null) return;

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(charUuid);
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found: " + charUuid);
            return;
        }

        bluetoothGatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }

    // ================= SEND COMMAND =================
    public void sendCommand(byte[] command) {
        if (!hasBluetoothConnectPermission()) return;
        if (bluetoothGatt == null) return;

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID);
        if (characteristic == null) return;

        characteristic.setValue(command);
        bluetoothGatt.writeCharacteristic(characteristic);
    }

    // ================= API COMMANDS (giữ nguyên) =================
    public void changeSamplingMode(int mode) {
        sendCommand(new byte[]{0x01, (byte) mode});
    }

    public void updateMAX30102Config(int ledCurrent, int sampleRate) {
        sendCommand(new byte[]{
                0x02,
                (byte) ledCurrent,
                (byte) (sampleRate & 0xFF),
                (byte) ((sampleRate >> 8) & 0xFF)
        });
    }

    public void updateMPU6050Config(int accelRange, int gyroRange) {
        sendCommand(new byte[]{
                0x03,
                (byte) accelRange,
                (byte) gyroRange
        });
    }

    public void syncData() {
        sendCommand(new byte[]{0x10});
    }

    public boolean isConnected() {
        return connectionState == STATE_CONNECTED;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }
}