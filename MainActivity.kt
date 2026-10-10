package com.example.airmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var folderHintText: TextView
    private lateinit var startStopButton: Button
    private lateinit var playLastButton: Button
    private var isRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("message") ?: return
            statusText.text = msg
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startMonitorService()
            } else {
                statusText.text = "برای کارکرد برنامه باید همه دسترسی‌ها داده بشن"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        folderHintText = findViewById(R.id.folderHintText)
        startStopButton = findViewById(R.id.startStopButton)
        playLastButton = findViewById(R.id.playLastButton)

        folderHintText.text = "فایل‌های ضبط‌شده اینجا ذخیره می‌شن:\n" +
            (getExternalFilesDir("Recordings")?.absolutePath ?: "")

        startStopButton.setOnClickListener { toggleMonitoring() }
        playLastButton.setOnClickListener { playLastRecording() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MonitorService.STATUS_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun toggleMonitoring() {
        if (!isRunning) {
            checkPermissionsAndStart()
        } else {
            stopMonitorService()
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startMonitorService()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        isRunning = true
        startStopButton.text = "توقف پایش"
        statusText.text = "در حال اتصال به ایرپاد..."
    }

    private fun stopMonitorService() {
        val intent = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
        startService(intent)
        isRunning = false
        startStopButton.text = "شروع پایش"
    }

    private fun playLastRecording() {
        val dir = getExternalFilesDir("Recordings")
        val last = dir?.listFiles()?.maxByOrNull { it.lastModified() }
        if (last == null) {
            Toast.makeText(this, "هنوز فایلی ضبط نشده", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(last.absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            Toast.makeText(this, "در حال پخش: ${last.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در پخش فایل", Toast.LENGTH_SHORT).show()
        }
    }
}
