package com.example.watchapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

public class BLEScanActivity extends AppCompatActivity {
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
    private Button btnScan, btnBack;
    private ProgressBar progressBar;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_scan);

        initViews();
        setupRecyclerView();
        checkBluetooth();
        checkPermissions();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewDevices);
        btnScan = findViewById(R.id.btnScan);
        btnBack = findViewById(R.id.btnBack);
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

        btnBack.setOnClickListener(v -> finish());
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
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            startActivityForResult(enableBtIntent, 1);
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Android 11 và thấp hơn
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }

        if (bleScanner == null) {
            Toast.makeText(this, "BLE Scanner không khả dụng", Toast.LENGTH_SHORT).show();
            return;
        }

        scanning = true;
        btnScan.setText("Dừng quét");
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang quét thiết bị BLE...");

        bleScanner.startScan(scanCallback);

        // Tự động dừng sau SCAN_PERIOD
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
            }
        }, SCAN_PERIOD);

        Log.d(TAG, "Started BLE scan");
    }

    private void stopScan() {
        if (!scanning) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        scanning = false;
        btnScan.setText("Quét thiết bị");
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Tìm thấy " + deviceList.size() + " thiết bị");

        if (bleScanner != null) {
            bleScanner.stopScan(scanCallback);
        }

        Log.d(TAG, "Stopped BLE scan");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();

            // Kiểm tra xem device đã có trong list chưa
            boolean deviceExists = false;
            for (BluetoothDevice d : deviceList) {
                if (d.getAddress().equals(device.getAddress())) {
                    deviceExists = true;
                    break;
                }
            }

            if (!deviceExists) {
                deviceList.add(device);
                deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                tvStatus.setText("Tìm thấy " + deviceList.size() + " thiết bị");
                Log.d(TAG, "Found device: " + device.getAddress());
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String deviceName = device.getName();
        String deviceAddress = device.getAddress();

        Log.d(TAG, "Connecting to device: " + deviceAddress);
        Toast.makeText(this, "Đang kết nối với " +
                (deviceName != null ? deviceName : deviceAddress), Toast.LENGTH_SHORT).show();

        // Trả về địa chỉ device cho MainActivity
        Intent resultIntent = new Intent();
        resultIntent.putExtra("device_address", deviceAddress);
        resultIntent.putExtra("device_name", deviceName != null ? deviceName : "Unknown");
        setResult(RESULT_OK, resultIntent);
        finish();
    }

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
    }
}