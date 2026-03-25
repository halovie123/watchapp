package com.example.watchapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BLEScanActivity extends AppCompatActivity {
    private static final String TAG = "BLEScanActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final long SCAN_PERIOD = 10000; // 10 giây

    // ── UUID lấy từ BLEService — dùng để xác thực khi click ─────────────────
    private static final ParcelUuid TARGET_SERVICE_UUID =
            ParcelUuid.fromString(BLEService.SERVICE_UUID.toString());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BLEDeviceAdapter deviceAdapter;
    private List<BluetoothDevice> deviceList;
    private Handler handler;
    private boolean scanning = false;

    // ── Lưu ScanResult theo MAC để dùng khi check UUID lúc click ────────────
    private final Map<String, ScanResult> scanResultMap = new HashMap<>();

    private RecyclerView recyclerView;
    private Button btnScan, btnBackToOnboarding, btnMockEnterMain;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private BLEService bleService;
    private boolean serviceBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_scan);

        initViews();
        setupRecyclerView();
        checkBluetooth();
        checkPermissions();

        Intent serviceIntent = new Intent(this, BLEService.class);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void initViews() {
        recyclerView       = findViewById(R.id.recyclerViewDevices);
        btnScan            = findViewById(R.id.btnScan);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        btnMockEnterMain   = findViewById(R.id.btnMockEnterMain);
        progressBar        = findViewById(R.id.progressBar);
        tvStatus           = findViewById(R.id.tvStatus);

        handler    = new Handler();
        deviceList = new ArrayList<>();

        btnScan.setOnClickListener(v -> {
            if (!scanning) {
                deviceList.clear();
                scanResultMap.clear();          // reset cache ScanResult
                deviceAdapter.notifyDataSetChanged();
                startScan();
            } else {
                stopScan();
            }
        });

        btnBackToOnboarding.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean("hasOnboarded", false).apply();
            finish();
        });

        btnMockEnterMain.setOnClickListener(v -> {
            Intent intent = new Intent(BLEScanActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }

    private void setupRecyclerView() {
        deviceAdapter = new BLEDeviceAdapter(deviceList, device -> {
            stopScan();
            connectToDevice(device);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceAdapter);
    }

    private void checkBluetooth() {
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ Bluetooth", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(enableBtIntent, 1);
            }
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (!permissionsNeeded.isEmpty())
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    private void startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth chưa bật", Toast.LENGTH_SHORT).show();
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            Toast.makeText(this, "BLE Scanner không khả dụng", Toast.LENGTH_SHORT).show();
            return;
        }

        scanning = true;
        btnScan.setText("Dừng quét");
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang quét thiết bị BLE...");

        // Scan bình thường, KHÔNG filter UUID — hiển thị tất cả thiết bị có tên
        bleScanner.startScan(scanCallback);

        handler.postDelayed(() -> {
            if (scanning) stopScan();
        }, SCAN_PERIOD);

        Log.d(TAG, "Started BLE scan (no UUID filter)");
    }

    private void stopScan() {
        if (!scanning) return;

        if (bleScanner != null) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }

        scanning = false;
        btnScan.setText("Quét thiết bị");
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Tìm thấy " + deviceList.size() + " thiết bị");

        Log.d(TAG, "Stopped BLE scan");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scan callback — lưu ScanResult vào map để dùng khi check UUID lúc click
    // ────────────────────────────────────────────────────────────────────────
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device  = result.getDevice();
            String deviceAddress    = device.getAddress();
            String deviceName       = getDeviceName(device);

            if (deviceName == null || deviceName.trim().isEmpty()) {
                Log.d(TAG, "Skipped unnamed device: " + deviceAddress);
                return;
            }

            // Lọc trùng theo MAC
            if (scanResultMap.containsKey(deviceAddress)) return;

            // ── Lưu ScanResult để dùng cho UUID check khi click ─────────────
            scanResultMap.put(deviceAddress, result);
            deviceList.add(device);

            runOnUiThread(() -> {
                deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                tvStatus.setText("Tìm thấy " + deviceList.size() + " thiết bị");
            });

            Log.d(TAG, "Added: " + deviceName + " (" + deviceAddress + ")");
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) onScanResult(0, result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Scan failed: " + errorCode);
            tvStatus.setText("Quét thất bại");
            stopScan();
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    // Kết nối — check UUID TRƯỚC khi cho vào MainActivity
    // ────────────────────────────────────────────────────────────────────────
    private void connectToDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String deviceAddress = device.getAddress();
        String deviceName    = getDeviceName(device);

        // ── BƯỚC 1: Kiểm tra UUID từ advertisement packet ───────────────────
        if (!hasTargetServiceUuid(deviceAddress)) {
            Log.w(TAG, "UUID không khớp: " + deviceAddress);
            Toast.makeText(this,
                    "Không thể kết nối\n\"" + (deviceName != null ? deviceName : deviceAddress)
                            + "\" không phải thiết bị WatchApp",
                    Toast.LENGTH_LONG).show();
            return; // Dừng — không connect
        }

        // ── BƯỚC 2: UUID hợp lệ → tiến hành kết nối ────────────────────────
        Log.d(TAG, "UUID hợp lệ, đang kết nối: " + deviceAddress);
        Toast.makeText(this,
                "Đang kết nối với " + (deviceName != null ? deviceName : deviceAddress),
                Toast.LENGTH_SHORT).show();

        if (serviceBound && bleService != null) {
            bleService.initialize();
            boolean success = bleService.connect(deviceAddress);

            if (success) {
                SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                prefs.edit()
                        .putString("connectedDeviceAddress", deviceAddress)
                        .putString("connectedDeviceName", deviceName != null ? deviceName : "Unknown")
                        .apply();

                handler.postDelayed(() -> {
                    Toast.makeText(this, "Đã kết nối!", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(BLEScanActivity.this, MainActivity.class);
                    intent.putExtra("device_address", deviceAddress);
                    intent.putExtra("device_name", deviceName);
                    startActivity(intent);
                    finish();
                }, 1500);
            } else {
                Toast.makeText(this, "Kết nối thất bại", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "BLE Service chưa sẵn sàng", Toast.LENGTH_SHORT).show();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helper: kiểm tra UUID trong advertisement packet của ScanResult
    // ────────────────────────────────────────────────────────────────────────
    private boolean hasTargetServiceUuid(String deviceAddress) {
        ScanResult result = scanResultMap.get(deviceAddress);
        if (result == null) {
            Log.w(TAG, "Không tìm thấy ScanResult cho: " + deviceAddress);
            return false;
        }

        ScanRecord record = result.getScanRecord();
        if (record == null) {
            Log.w(TAG, "ScanRecord null cho: " + deviceAddress);
            return false;
        }

        List<ParcelUuid> serviceUuids = record.getServiceUuids();

        // Log để debug
        Log.d(TAG, "UUID của " + deviceAddress + ": " + serviceUuids);
        Log.d(TAG, "Target UUID: " + TARGET_SERVICE_UUID);

        if (serviceUuids == null || serviceUuids.isEmpty()) {
            Log.w(TAG, "Device không quảng bá Service UUID: " + deviceAddress);
            return false;
        }

        return serviceUuids.contains(TARGET_SERVICE_UUID);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helper: lấy tên device an toàn (xử lý permission Android 12+)
    // ────────────────────────────────────────────────────────────────────────
    private String getDeviceName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                return device.getName();
            }
            return null;
        }
        return device.getName();
    }

    // Service Connection
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BLEService.LocalBinder binder = (BLEService.LocalBinder) service;
            bleService    = binder.getService();
            serviceBound  = true;
            if (!bleService.initialize()) Log.e(TAG, "Unable to initialize Bluetooth");
            Log.d(TAG, "BLE Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService   = null;
            serviceBound = false;
            Log.d(TAG, "BLE Service disconnected");
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Cần cấp quyền để quét BLE", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}