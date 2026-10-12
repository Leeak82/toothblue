package com.eikarna.bluetoothjammer

import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.eikarna.bluetoothjammer.api.AttackManager
import com.eikarna.bluetoothjammer.util.Logger
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.TextInputEditText

class AttackActivity : AppCompatActivity() {

    private lateinit var viewDeviceName: MaterialTextView
    private lateinit var viewDeviceAddress: MaterialTextView
    private lateinit var viewStats: MaterialTextView
    private lateinit var viewThreads: TextInputEditText
    private lateinit var buttonStartStop: MaterialButton
    private lateinit var logAttack: MaterialTextView
    private lateinit var switchLog: MaterialSwitch

    private lateinit var deviceName: String
    private lateinit var address: String
    private var threads: Int = 8

    private val attackManager = AttackManager()

    companion object {
        var FrameworkVersion = 1.1 // bumped: stop-fix release
        var loggingStatus = true
    }

    val isAttacking: Boolean get() = attackManager.isRunning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.attack_layout)

        deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unknown Device"
        address = intent.getStringExtra("ADDRESS") ?: "Unknown Address"
        threads = intent.getIntExtra("THREADS", 8).coerceIn(1, 1024)

        viewDeviceName = findViewById(R.id.textViewDeviceName)
        viewDeviceAddress = findViewById(R.id.textViewAddress)
        viewStats = findViewById(R.id.textViewStats)
        viewThreads = findViewById(R.id.editTextThreads)
        buttonStartStop = findViewById(R.id.buttonStartStop)
        logAttack = findViewById(R.id.logTextView)
        switchLog = findViewById(R.id.switchLogView)

        viewDeviceName.text = "Device Name: $deviceName"
        viewDeviceAddress.text = "Address: $address"
        viewThreads.setText("$threads")
        viewThreads.filters = arrayOf(InputFilter.LengthFilter(4))
        viewStats.text = "Idle — press Start."
        switchLog.isChecked = loggingStatus
        Logger.appendLog(logAttack, "Bluetooth Jammer Framework Version: $FrameworkVersion (stop-fix)")

        buttonStartStop.setOnClickListener {
            if (attackManager.isRunning) stopAttack() else confirmAndStart()
        }

        viewThreads.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) threads = parseThreads()
        }

        switchLog.setOnCheckedChangeListener { _, isChecked ->
            loggingStatus = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Logging Enabled! You may degrade performance issue."
                else "Logging Disabled!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun parseThreads(): Int {
        val v = viewThreads.text?.toString()?.toIntOrNull() ?: 8
        return v.coerceIn(1, 1024).also { viewThreads.setText("$it") }
    }

    private fun confirmAndStart() {
        threads = parseThreads()
        if (threads > 256) {
            AlertDialog.Builder(this)
                .setTitle("High thread count")
                .setMessage("$threads threads will heat up your phone and may freeze the UI log. Start with 8 for real tests.\n\nStart anyway?")
                .setPositiveButton("Start") { _, _ -> startAttack() }
                .setNegativeButton("Cancel", null)
                .show()
        } else startAttack()
    }

    private fun startAttack() {
        buttonStartStop.text = "Stop"
        Logger.appendLog(logAttack, "Attack Started! Address: $address ($deviceName) | Threads: $threads")
        Toast.makeText(this, "Attack running — Stop now halts cleanly.", Toast.LENGTH_SHORT).show()
        viewStats.text = "Running $threads thread(s)…"
        attackManager.start(this, address, threads) { msg ->
            runOnUiThread {
                if (loggingStatus) Logger.appendLog(logAttack, msg)
                viewStats.text =
                    "Threads: $threads | log lines: ${logAttack.text.split("\n").size} | attempts: ${attackManager.attemptCount.get()}"
            }
        }
    }

    private fun stopAttack() {
        attackManager.stop()
        buttonStartStop.text = "Start"
        viewStats.text = "Stopped. attempts: ${attackManager.attemptCount.get()}"
        Logger.appendLog(logAttack, "Attack Stopped! All $threads worker(s) killed.")
        Toast.makeText(this, "Attack stopped.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        attackManager.stop()
    }

    // NOTE: no longer stops on onPause — user may check another app mid-test.
    // Stop explicitly with the button or by leaving (back press destroys).
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onBackPressed() {
        if (attackManager.isRunning) {
            AlertDialog.Builder(this)
                .setTitle("Stop attack?")
                .setMessage("An attack is running. Stop it and go back?")
                .setPositiveButton("Stop + back") { _, _ ->
                    stopAttack()
                    super.onBackPressed()
                }
                .setNegativeButton("Stay", null)
                .show()
        } else super.onBackPressed()
    }
}
