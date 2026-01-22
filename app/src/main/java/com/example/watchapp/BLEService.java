package com.example.watchapp;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class BLEService extends Service {
    private static final String TAG = "BLEService";

    // UUIDs cho ESP32 BLE Server
    // Bạn cần đổi các UUID này cho khớp với ESP32
    public static final UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    public static final UUID CHARACTERISTIC_SENSOR_DATA_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    public static final UUID CHARACTERISTIC_COMMAND_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9");

    // Client Characteristic Configuration Descriptor
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Broadcast actions
    public static final String ACTION_GATT_CONNECTED = "com.example.watchapp.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED = "com.example.watchapp.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_GATT_SERVICES_DISCOVERED = "com.example.watchapp.ACTION_GATT_SERVICES_DISCOVERED";
    public static final String ACTION_DATA_AVAILABLE = "com.example.watchapp.ACTION_DATA_AVAILABLE";
    public static final String EXTRA_DATA = "com.example.watchapp.EXTRA_DATA";

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private String deviceAddress;
    private int connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BLEService getService() {
            return BLEService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    // Callback cho GATT operations
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                connectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");

                // Discover services
                Log.i(TAG, "Attempting to start service discovery:" +
                        bluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                Log.i(TAG, "Services discovered");

                // Tự động enable notifications cho sensor data
                enableSensorDataNotifications();
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
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
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write successful");
            } else {
                Log.e(TAG, "Characteristic write failed: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful");
            } else {
                Log.e(TAG, "Descriptor write failed: " + status);
            }
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // Kiểm tra nếu là sensor data characteristic
        if (CHARACTERISTIC_SENSOR_DATA_UUID.equals(characteristic.getUuid())) {
            try {
                // Parse JSON data từ ESP32
                String jsonString = new String(characteristic.getValue());
                Log.d(TAG, "Received data: " + jsonString);

                intent.putExtra(EXTRA_DATA, jsonString);

                // Parse và lưu dữ liệu
                parseSensorData(jsonString);

            } catch (Exception e) {
                Log.e(TAG, "Error parsing sensor data: " + e.getMessage());
            }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void parseSensorData(String jsonString) {
        try {
            JSONObject jsonData = new JSONObject(jsonString);

            // Lấy dữ liệu từ JSON
            if (jsonData.has("heartRate")) {
                int heartRate = jsonData.getInt("heartRate");
                HealthDataManager.getInstance(this).saveHeartRateData(heartRate);
                Log.d(TAG, "Heart Rate: " + heartRate);
            }

            if (jsonData.has("spo2")) {
                int spo2 = jsonData.getInt("spo2");
                HealthDataManager.getInstance(this).saveOxygenData(spo2);
                Log.d(TAG, "SpO2: " + spo2);
            }

            if (jsonData.has("accelX") && jsonData.has("accelY") && jsonData.has("accelZ")) {
                double accelX = jsonData.getDouble("accelX");
                double accelY = jsonData.getDouble("accelY");
                double accelZ = jsonData.getDouble("accelZ");
                Log.d(TAG, String.format("Accel: X=%.2f, Y=%.2f, Z=%.2f", accelX, accelY, accelZ));

                // Tính toán và kiểm tra nguy cơ té ngã
                checkFallRisk(accelX, accelY, accelZ);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON: " + e.getMessage());
        }
    }

    private void checkFallRisk(double x, double y, double z) {
        // Tính độ lớn gia tốc
        double magnitude = Math.sqrt(x*x + y*y + z*z);

        // Ngưỡng phát hiện té ngã (có thể điều chỉnh)
        if (magnitude > 25.0 || magnitude < 2.0) {
            Log.w(TAG, "Potential fall detected! Magnitude: " + magnitude);
            // Gửi broadcast để hiển thị cảnh báo
            Intent fallIntent = new Intent("com.example.watchapp.FALL_DETECTED");
            fallIntent.putExtra("magnitude", magnitude);
            LocalBroadcastManager.getInstance(this).sendBroadcast(fallIntent);
        }
    }

    public boolean initialize() {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean connect(final String address) {
        if (bluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Nếu đã kết nối với device này, sử dụng lại connection
        if (deviceAddress != null && address.equals(deviceAddress)
                && bluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing BluetoothGatt for connection.");
            if (bluetoothGatt.connect()) {
                connectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            return false;
        }

        // Connect to the GATT server
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        deviceAddress = address;
        connectionState = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.disconnect();
    }

    public void close() {
        if (bluetoothGatt == null) {
            return;
        }
        bluetoothGatt.close();
        bluetoothGatt = null;
    }

    // Enable notifications cho sensor data characteristic
    public void enableSensorDataNotifications() {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "Service not found!");
            return;
        }

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(CHARACTERISTIC_SENSOR_DATA_UUID);
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found!");
            return;
        }

        // Enable local notifications
        bluetoothGatt.setCharacteristicNotification(characteristic, true);

        // Enable remote notifications
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
            Log.d(TAG, "Notifications enabled for sensor data");
        }
    }

    // Gửi lệnh đến ESP32
    public void sendCommand(byte[] command) {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.e(TAG, "Service not found!");
            return;
        }

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID);
        if (characteristic == null) {
            Log.e(TAG, "Command characteristic not found!");
            return;
        }

        characteristic.setValue(command);
        boolean success = bluetoothGatt.writeCharacteristic(characteristic);
        Log.d(TAG, "Command write " + (success ? "initiated" : "failed"));
    }

    // Các phương thức tiện ích để gửi lệnh
    public void changeSamplingMode(int mode) {
        byte[] command = new byte[]{0x01, (byte)mode};
        sendCommand(command);
        Log.d(TAG, "Changing sampling mode to: " + mode);
    }

    public void updateMAX30102Config(int ledCurrent, int sampleRate) {
        byte[] command = new byte[]{
                0x02,
                (byte)ledCurrent,
                (byte)(sampleRate & 0xFF),
                (byte)((sampleRate >> 8) & 0xFF)
        };
        sendCommand(command);
        Log.d(TAG, "Updating MAX30102 config");
    }

    public void updateMPU6050Config(int accelRange, int gyroRange) {
        byte[] command = new byte[]{
                0x03,
                (byte)accelRange,
                (byte)gyroRange
        };
        sendCommand(command);
        Log.d(TAG, "Updating MPU6050 config");
    }

    public void syncData() {
        byte[] command = new byte[]{0x10};  // Command để sync
        sendCommand(command);
        Log.d(TAG, "Sync data request sent");
    }

    public boolean isConnected() {
        return connectionState == STATE_CONNECTED;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }
}