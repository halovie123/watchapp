package com.example.watchapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BLEScanActivity extends AppCompatActivity {
    private static final String TAG = "BLEScanActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_ENABLE_BT = 102;
    private static final int REQUEST_ENABLE_LOCATION = 103;
    private static final long SCAN_PERIOD = 10000; // 10 giây

    // Bluetooth components
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean scanning = false;
    private Handler handler;

    // UI components
    private RecyclerView recyclerView;
    private Button btnScan;
    private Button btnBackToOnboarding;
    private Button btnMockEnterMain;
    private ProgressBar progressBar;
    private TextView tvStatus;

    // Data
    private List<BluetoothDevice> deviceList;
    private BLEDeviceAdapter deviceAdapter;

    // BLE Service
    private BLEService bleService;
    private boolean serviceBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_scan);

        initViews();
        setupRecyclerView();
        handler = new Handler();

        // Kiểm tra Bluetooth
        checkBluetooth();

        // Kiểm tra và request permissions
        checkPermissions();

        // Bind BLE Service
        Intent serviceIntent = new Intent(this, BLEService.class);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewDevices);
        btnScan = findViewById(R.id.btnScan);
        btnBackToOnboarding = findViewById(R.id.btnBackToOnboarding);
        btnMockEnterMain = findViewById(R.id.btnMockEnterMain);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);

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

        btnMockEnterMain.setOnClickListener(v -> {
            Intent intent = new Intent(BLEScanActivity.this, MainActivity.class);
            startActivity(intent);
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
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ Bluetooth", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Android 12+ (API 31+)
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

        // Location permissions - bắt buộc cho Android 10+
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        } else {
            // Đã có permissions, kiểm tra Location
            checkLocationAndStart();
        }
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }

        // Kiểm tra cả GPS và Network location
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        Log.d(TAG, "Location status - GPS: " + isGpsEnabled + ", Network: " + isNetworkEnabled);

        return isGpsEnabled || isNetworkEnabled;
    }

    private void showLocationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Yêu cầu bật vị trí");
        builder.setMessage("Để quét thiết bị BLE, Android 10 yêu cầu bật dịch vụ vị trí. " +
                "Điều này giúp bảo vệ quyền riêng tư của bạn.\n\n" +
                "Bạn có thể tắt vị trí sau khi đã kết nối với thiết bị.");
        builder.setPositiveButton("Mở cài đặt", (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivityForResult(intent, REQUEST_ENABLE_LOCATION);
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> {
            tvStatus.setText("Cần bật vị trí để quét BLE");
            Toast.makeText(this, "Cần bật vị trí để quét thiết bị BLE", Toast.LENGTH_LONG).show();
        });
        builder.setCancelable(false);
        builder.show();
    }

    private void checkLocationAndStart() {
        Log.d(TAG, "checkLocationAndStart called");

        // Kiểm tra Location đã bật chưa (quan trọng cho Android 10)
        if (!isLocationEnabled()) {
            Log.d(TAG, "Location is disabled");
            showLocationDialog();
            return;
        }

        Log.d(TAG, "Location is enabled");

        // Kiểm tra Bluetooth
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is disabled");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }

        // Tất cả đã sẵn sàng
        Log.d(TAG, "All conditions met, ready to scan");
        tvStatus.setText("Sẵn sàng quét BLE");
        Toast.makeText(this, "Sẵn sàng quét BLE", Toast.LENGTH_SHORT).show();
    }

    private void startScan() {
        Log.d(TAG, "startScan called on Android " + Build.VERSION.SDK_INT);

        // Kiểm tra Location đã bật chưa (Android 10 bắt buộc)
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location is disabled, cannot start scan");
            showLocationDialog();
            return;
        }

        // Kiểm tra Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            checkPermissions();
            return;
        }

        // Kiểm tra Bluetooth permission cho Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Bluetooth scan permission not granted");
                checkPermissions();
                return;
            }
        }

        // Kiểm tra Bluetooth adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null");
            Toast.makeText(this, "Không hỗ trợ Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is not enabled");
            Toast.makeText(this, "Bluetooth chưa bật", Toast.LENGTH_SHORT).show();
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner is null");
            Toast.makeText(this, "BLE Scanner không khả dụng", Toast.LENGTH_SHORT).show();
            return;
        }

        scanning = true;
        btnScan.setText("Dừng quét");
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang quét thiết bị BLE...");

        try {
            bleScanner.startScan(scanCallback);
            Log.d(TAG, "Started BLE scan successfully on Android " + Build.VERSION.SDK_INT);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when starting scan: " + e.getMessage());
            Toast.makeText(this, "Không có quyền để quét BLE", Toast.LENGTH_SHORT).show();
            scanning = false;
            btnScan.setText("Quét thiết bị");
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("Không có quyền quét");
            return;
        } catch (Exception e) {
            Log.e(TAG, "Exception when starting scan: " + e.getMessage());
            Toast.makeText(this, "Lỗi khi bắt đầu quét: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            scanning = false;
            btnScan.setText("Quét thiết bị");
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("Lỗi quét");
            return;
        }

        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
            }
        }, SCAN_PERIOD);
    }

    private void stopScan() {
        if (!scanning) return;

        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
                Log.d(TAG, "Stopped BLE scan");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan: " + e.getMessage());
            }
        }

        scanning = false;
        btnScan.setText("Quét thiết bị");
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Tìm thấy " + deviceList.size() + " thiết bị");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            int rssi = result.getRssi();

            // Lấy tên thiết bị tùy theo Android version
            String deviceName = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(BLEScanActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    deviceName = device.getName();
                }
            } else {
                deviceName = device.getName();
            }

            Log.d(TAG, "Found device: name=" + deviceName + ", address=" + deviceAddress +
                    ", rssi=" + rssi + ", callbackType=" + callbackType);

            // Bỏ qua thiết bị không có tên
            if (deviceName == null || deviceName.trim().isEmpty()) {
                Log.d(TAG, "Skipped unnamed device: " + deviceAddress);
                return;
            }

            // Kiểm tra trùng lặp
            for (BluetoothDevice d : deviceList) {
                if (d.getAddress().equals(deviceAddress)) {
                    return;
                }
            }

            // Thêm vào danh sách
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
            Log.d(TAG, "onBatchScanResults: " + (results != null ? results.size() : 0) + " results");
            if (results != null) {
                for (ScanResult result : results) {
                    onScanResult(0, result);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Scan failed with error code: " + errorCode);

            String errorMessage;
            switch (errorCode) {
                case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    errorMessage = "Quét đã được bắt đầu";
                    break;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    errorMessage = "Đăng ký ứng dụng thất bại";
                    break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "Tính năng không được hỗ trợ";
                    break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    errorMessage = "Lỗi nội bộ";
                    break;
                default:
                    errorMessage = "Lỗi không xác định: " + errorCode;
            }

            runOnUiThread(() -> {
                tvStatus.setText("Quét thất bại: " + errorMessage);
                Toast.makeText(BLEScanActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            });
            stopScan();
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Không có quyền kết nối Bluetooth", Toast.LENGTH_SHORT).show();
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
                        .putBoolean("hasOnboarded", true)
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

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BLEService.LocalBinder binder = (BLEService.LocalBinder) service;
            bleService = binder.getService();
            serviceBound = true;

            if (!bleService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                Toast.makeText(BLEScanActivity.this, "Không thể khởi tạo Bluetooth", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "BLE Service connected and initialized");
            }
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

            if (allGranted) {
                Log.d(TAG, "All permissions granted");
                checkLocationAndStart();
            } else {
                Log.w(TAG, "Some permissions were denied");
                Toast.makeText(this, "Cần cấp quyền để quét BLE", Toast.LENGTH_LONG).show();
                tvStatus.setText("Thiếu quyền, không thể quét");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // Bluetooth đã được bật
                Log.d(TAG, "Bluetooth enabled by user");
                checkLocationAndStart();
            } else {
                Log.w(TAG, "User refused to enable Bluetooth");
                Toast.makeText(this, "Cần bật Bluetooth để quét", Toast.LENGTH_SHORT).show();
                tvStatus.setText("Bluetooth chưa được bật");
            }
        } else if (requestCode == REQUEST_ENABLE_LOCATION) {
            // Sau khi quay lại từ cài đặt Location
            Log.d(TAG, "Returned from location settings");
            checkLocationAndStart();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();

        if (serviceBound) {
            try {
                unbindService(serviceConnection);
                serviceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service: " + e.getMessage());
            }
        }

        Log.d(TAG, "BLEScanActivity destroyed");
    }
}