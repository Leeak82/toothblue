package com.eikarna.bluetoothjammer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eikarna.bluetoothjammer.api.BluetoothDeviceInfo
import com.eikarna.bluetoothjammer.api.ScanNearbyDevices
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private lateinit var scanBtn: Button
    private lateinit var discoverableBtn: Button
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val shownDevices = mutableListOf<BluetoothDeviceInfo>()
    private val scanner = ScanNearbyDevices.getInstance()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.deviceListView)
        statusView = findViewById(R.id.scanStatusView)
        scanBtn = findViewById(R.id.scanToggleButton)
        discoverableBtn = findViewById(R.id.discoverableButton)

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = deviceListAdapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in shownDevices.indices) showDeviceInfo(shownDevices[position])
        }

        scanBtn.setOnClickListener {
            checkBluetoothStatusAndPermissions()
        }
        discoverableBtn.setOnClickListener {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 1200)
                }
                startActivity(intent)
            } catch (ignored: Exception) {
                Toast.makeText(this, "Discoverable request failed", Toast.LENGTH_SHORT).show()
            }
        }

        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_NAME_CHANGED))
        registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))

        checkBluetoothStatusAndPermissions()
    }

    private fun checkBluetoothStatusAndPermissions() {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth off")
                .setMessage("Enable Bluetooth to scan for nearby devices.")
                .setPositiveButton("Enable") { _, _ ->
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        checkPermissionsAndStartScanning()
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = getDeviceExtra(intent)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                    val info = BluetoothDeviceInfo(
                        name = device?.name ?: "Unknown Device",
                        address = device?.address ?: return,
                        rssi = rssi,
                        paired = false
                    )
                    scanner.onDeviceFound(info)
                    Toast.makeText(
                        this@MainActivity,
                        "FOUND NEW DEVICE!\n\nName: ${info.name}\nAddress: ${info.address}\n\n${Date()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device: BluetoothDevice? = getDeviceExtra(intent)
                    val realName = device?.name
                    val addr = device?.address
                    if (realName != null && addr != null) {
                        scanner.onDeviceFound(
                            BluetoothDeviceInfo(
                                name = realName,
                                address = addr,
                                rssi = 0,
                                paired = false
                            )
                        )
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    statusView.text = "Discovery finished — ${scanner.snapshot().size} device(s). Restarting…"
                    // Keep discovery cycling while activity is visible.
                    try {
                        val mgr = getSystemService(BluetoothManager::class.java)
                        mgr.adapter?.startDiscovery()
                    } catch (ignored: SecurityException) {}
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun checkPermissionsAndStartScanning() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        } else {
            startScanningForDevices()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScanningForDevices()
            } else {
                Toast.makeText(this, "Permissions are required to scan for Bluetooth devices", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScanningForDevices() {
        statusView.text = "Scanning…"
        scanBtn.text = "Scanning… (tap to restart)"
        scanner.startScanning(this) { discoveredDevices ->
            runOnUiThread {
                shownDevices.clear()
                shownDevices.addAll(discoveredDevices)
                val names = discoveredDevices.map {
                    val tag = if (it.paired) "paired" else "found"
                    "${it.name} (${it.address}) [$tag]"
                }
                deviceListAdapter.clear()
                deviceListAdapter.addAll(names)
                deviceListAdapter.notifyDataSetChanged()
                statusView.text = "Scanning… ${discoveredDevices.size} device(s)"
            }
        }
    }

    private fun showDeviceInfo(device: BluetoothDeviceInfo) {
        val message = "Name: ${device.name}\nAddress: ${device.address}"
        AlertDialog.Builder(this)
            .setTitle("Device Info")
            .setMessage(message)
            .setPositiveButton("Attack") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(this, AttackActivity::class.java).apply {
                    putExtra("DEVICE_NAME", device.name)
                    putExtra("ADDRESS", device.address)
                    putExtra("THREADS", 8)
                }
                startActivity(intent)
            }
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Copy Info") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device Info", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Device info copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (ignored: Exception) {}
        scanner.stopScanning(this)
    }

    override fun onPause() {
        super.onPause()
        // Keep the cached list but pause radio churn while hidden.
        scanner.stopScanning(this)
        statusView.text = "Paused"
    }

    override fun onResume() {
        super.onResume()
        // Only resume if we had started before (scanner guards null callback).
        scanner.resumeScanning()
    }
}
