package com.example.watchapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
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
import java.util.List;

public class BLEScanActivity extends BaseActivity {
    private static final String TAG = "BLEScanActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final long SCAN_PERIOD = 10000; // 10 giây

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BLEDeviceAdapter deviceAdapter;
    private List<BluetoothDevice> deviceList;
    private Handler handler;
    private boolean scanning = false;

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

        // Bind BLE Service
        Intent serviceIntent = new Intent(this, BLEService.class);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        String savedLanguage = LocaleHelper.getPersistedLanguage(this);
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();

        if (!savedLanguage.equals(currentLanguage)) {
            recreate();
            return;
        }
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewDevices);
        btnScan = findViewById(R.id.btnScan);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        btnMockEnterMain = findViewById(R.id.btnMockEnterMain);

        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);

        handler = new Handler();
        deviceList = new ArrayList<>();

        btnScan.setOnClickListener(v -> {
            if (!scanning) {
                deviceList.clear();
                deviceAdapter.notifyDataSetChanged();
                startScan();
            } else {
                stopScan();
            }
        });

        btnBackToOnboarding.setOnClickListener(v -> {
            // Xóa thông tin đã onboard để quay về onboarding
            SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean("hasOnboarded", false).apply();
            finish();
        });

        btnMockEnterMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // (1) Giả lập đã kết nối BLE
                SharedPreferences prefs =
                        getSharedPreferences("AppPrefs", MODE_PRIVATE);
                prefs.edit()
                        .putBoolean("ble_connected", true)
                        .apply();

                // (2) Vào MainActivity
                Intent intent =
                        new Intent(BLEScanActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
            }
        });

    }

    private void setupRecyclerView() {
        deviceAdapter = new BLEDeviceAdapter(deviceList, device -> {
            // Khi click vào device
            stopScan();
            connectToDevice(device);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceAdapter);
    }

    private void checkBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
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
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void startScan() {
        // Android 8 yêu cầu Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                checkPermissions();
                return;
            }
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
        btnScan.setText(R.string.stop_scan);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.scanning_ble_devices);

        bleScanner.startScan(scanCallback);

        handler.postDelayed(() -> {
            if (scanning) stopScan();
        }, SCAN_PERIOD);

        Log.d(TAG, "Started BLE scan");
    }


    private void stopScan() {
        if (!scanning) return;

        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }

        scanning = false;
        btnScan.setText(R.string.scan_devices);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(R.string.found + deviceList.size() + R.string.devices);

        Log.d(TAG, "Stopped BLE scan");
    }


    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();

            // Log devices - PHẢI kiểm tra permission trước
            String deviceName = "Unknown";
            String deviceAddress = "Unknown";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(BLEScanActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    deviceName = device.getName() != null ? device.getName() : "Unknown";
                    deviceAddress = device.getAddress();
                    Log.d(TAG, "Found: " + deviceName + " (" + deviceAddress + ")");
                }
            } else {
                deviceName = device.getName() != null ? device.getName() : "Unknown";
                deviceAddress = device.getAddress();
                Log.d(TAG, "Found: " + deviceName + " (" + deviceAddress + ")");
            }

            // Kiểm tra xem device đã có trong list chưa
            boolean deviceExists = false;
            for (BluetoothDevice d : deviceList) {
                // Also need permission check here
                String existingAddress = "Unknown";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(BLEScanActivity.this,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        existingAddress = d.getAddress();
                    }
                } else {
                    existingAddress = d.getAddress();
                }

                if (existingAddress.equals(deviceAddress)) {
                    deviceExists = true;
                    break;
                }
            }

            if (!deviceExists) {
                deviceList.add(device);
                deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                tvStatus.setText(R.string.found + deviceList.size() + R.string.devices);
                Log.d(TAG, "Added device: " + deviceAddress);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                onScanResult(0, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Scan failed with error: " + errorCode);
            tvStatus.setText("Quét thất bại");
            stopScan();
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        String deviceName = device.getName();
        String deviceAddress = device.getAddress();

        Log.d(TAG, "Connecting to device: " + deviceAddress);
        Toast.makeText(this, "Đang kết nối với " +
                (deviceName != null ? deviceName : deviceAddress), Toast.LENGTH_SHORT).show();

        // Kết nối qua BLE Service
        if (serviceBound && bleService != null) {
            bleService.initialize();
            boolean success = bleService.connect(deviceAddress);

            if (success) {
                // Lưu thông tin device
                SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                prefs.edit()
                        .putString("connectedDeviceAddress", deviceAddress)
                        .putString("connectedDeviceName", deviceName != null ? deviceName : "Unknown")
                        .apply();

                // Chờ 1.5 giây rồi chuyển sang MainActivity
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

    // Service Connection
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BLEService.LocalBinder binder = (BLEService.LocalBinder) service;
            bleService = binder.getService();
            serviceBound = true;

            if (!bleService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
            Log.d(TAG, "BLE Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            serviceBound = false;
            Log.d(TAG, "BLE Service disconnected");
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Cần cấp quyền để quét BLE", Toast.LENGTH_LONG).show();
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