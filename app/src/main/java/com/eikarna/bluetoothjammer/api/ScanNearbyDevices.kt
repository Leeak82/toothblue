package com.eikarna.bluetoothjammer.api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Fixed scanner: keeps ONE de-duplicated map keyed by MAC (bonded + discovered),
 * emits snapshots every 1s without wiping discovery results.
 * Fixes the original "Devices List (bug)".
 */
class ScanNearbyDevices {

    private var isScanning = false
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var callback: ((List<BluetoothDeviceInfo>) -> Unit)? = null

    companion object {
        private var instance: ScanNearbyDevices? = null

        fun getInstance(): ScanNearbyDevices {
            if (instance == null) instance = ScanNearbyDevices()
            return instance!!
        }

        // Keyed by MAC so bonded + ACTION_FOUND results merge instead of clobbering.
        val devicesMap = LinkedHashMap<String, BluetoothDeviceInfo>()
    }

    fun startScanning(context: Context, cb: (List<BluetoothDeviceInfo>) -> Unit) {
        callback = cb
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (bluetoothAdapter?.isEnabled != true || isScanning) {
            Log.e("ScanNearbyDevices", "Bluetooth disabled or already scanning.")
            // Still emit whatever we have so the list isn't empty.
            cb(devicesMap.values.toList())
            return
        }
        isScanning = true

        // Seed with bonded devices once (don't wipe every tick like the original).
        try {
            @SuppressLint("MissingPermission")
            val paired = bluetoothAdapter.bondedDevices
            paired?.forEach { device ->
                try { device.fetchUuidsWithSdp() } catch (_: Exception) {}
                val addr = device.address ?: return@forEach
                devicesMap.getOrPut(addr) {
                    BluetoothDeviceInfo(
                        name = device.name ?: "Unknown Device",
                        address = addr,
                        paired = true
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e("ScanNearbyDevices", "Missing permission for bondedDevices", e)
        }
        cb(devicesMap.values.toList())

        runnable = object : Runnable {
            override fun run() {
                if (!isScanning) return
                Log.d("ScanNearbyDevices", "Tick: ${devicesMap.size} known devices")
                callback?.invoke(devicesMap.values.toList())
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable!!)

        // Kick off classic discovery so ACTION_FOUND fires for unpaired devices.
        try {
            @SuppressLint("MissingPermission")
            bluetoothAdapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.e("ScanNearbyDevices", "startDiscovery denied", e)
        }
    }

    /** Called from MainActivity's ACTION_FOUND receiver. Never clears the map. */
    fun onDeviceFound(info: BluetoothDeviceInfo) {
        val existing = devicesMap[info.address]
        if (existing == null) {
            devicesMap[info.address] = info
        } else {
            devicesMap[info.address] = existing.copy(
                name = if (info.name != "Unknown Device") info.name else existing.name,
                rssi = info.rssi,
                paired = existing.paired || info.paired
            )
        }
        // Push immediately so "FOUND NEW DEVICE" appears without waiting 1s.
        if (isScanning) callback?.invoke(devicesMap.values.toList())
    }

    @SuppressLint("MissingPermission")
    fun stopScanning(context: Context? = null) {
        isScanning = false
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        try {
            if (context != null) {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? android.bluetooth.BluetoothManager
                mgr?.adapter?.cancelDiscovery()
            }
        } catch (_: Exception) {}
    }

    fun resumeScanning() {
        if (isScanning) return
        // Only resume if we were started before (runnable exists pattern from original
        // crashed with UninitializedPropertyAccessException when resumed pre-start).
        if (callback == null) return
        isScanning = true
        runnable = object : Runnable {
            override fun run() {
                if (!isScanning) return
                callback?.invoke(devicesMap.values.toList())
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable!!)
    }

    fun snapshot(): List<BluetoothDeviceInfo> = devicesMap.values.toList()
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val paired: Boolean = false
)
