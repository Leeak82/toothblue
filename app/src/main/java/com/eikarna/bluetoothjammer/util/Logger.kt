package com.eikarna.bluetoothjammer.util

import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Logger {
    companion object {
        // Thread-safe: always appends on the UI thread, keeps last 100 lines.
        fun appendLog(textView: TextView, message: String) {
            val run = Runnable {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val current = formatter.format(Date())
                textView.append("\n[$current] $message")
                val lines = textView.text.split("\n")
                if (lines.size > 100) {
                    textView.text = lines.takeLast(100).joinToString("\n")
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run.run()
            else textView.post(run)
        }

        fun clear(textView: TextView) {
            textView.post { textView.text = "" }
        }
    }
}
