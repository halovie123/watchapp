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
    public static final String EXTRA_DATA               = "com.example.watchapp.EXTRA_DATA";
    public static final String EXTRA_BPM                = "com.example.watchapp.EXTRA_BPM";
    public static final String EXTRA_SPO2               = "com.example.watchapp.EXTRA_SPO2";
    public static final String EXTRA_FINGER             = "com.example.watchapp.EXTRA_FINGER";

    private BluetoothManager  bluetoothManager;
    private BluetoothAdapter  bluetoothAdapter;
    private BluetoothGatt     bluetoothGatt;
    private String            deviceAddress;
    private int               connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING   = 1;
    private static final int STATE_CONNECTED    = 2;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BLEService getService() { return BLEService.this; }
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
                enableSensorDataNotifications();
            }
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

        // ── Parse "B:75,S:98,F:1" ─────────────────────────────────────────
        int bpm = -1, spo2 = -1, finger = -1;
        try {
            for (String part : raw.split(",")) {
                String[] kv = part.split(":");
                if (kv.length != 2) continue;
                switch (kv[0].trim()) {
                    case "B": bpm    = Integer.parseInt(kv[1].trim()); break;
                    case "S": spo2   = Integer.parseInt(kv[1].trim()); break;
                    case "F": finger = Integer.parseInt(kv[1].trim()); break;
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }

        Log.d(TAG, "Parsed → BPM=" + bpm + " SpO2=" + spo2 + " Finger=" + finger);

        // Lưu vào HealthDataManager nếu hợp lệ
        if (bpm > 0 && bpm <= 220) {
            intent.putExtra(EXTRA_BPM, bpm);
            HealthDataManager.getInstance(this).saveHeartRateData(bpm);
        }
        if (spo2 >= 70 && spo2 <= 100) {
            intent.putExtra(EXTRA_SPO2, spo2);
            HealthDataManager.getInstance(this).saveOxygenData(spo2);
        }
        if (finger >= 0) {
            intent.putExtra(EXTRA_FINGER, finger);
        }

        // Phát hiện té ngã từ dữ liệu gia tốc (nếu có thêm sau)
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
}