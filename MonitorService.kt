package com.example.airmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.airmonitor.action.START"
        const val ACTION_STOP = "com.example.airmonitor.action.STOP"
        const val STATUS_ACTION = "com.example.airmonitor.STATUS"
        private const val CHANNEL_ID = "airmonitor_channel"
        private const val NOTIF_ID = 1
    }

    private lateinit var audioManager: AudioManager

    @Volatile private var isCapturing = false
    private var captureThread: Thread? = null
    private var scoReceiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    broadcastStatus("ایرپاد وصل شد؛ در حال پخش زنده و ضبط")
                    if (!isCapturing) startCapture()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    broadcastStatus("اتصال صوتی بلوتوث قطع شد")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
    }

    private fun startMonitoring() {
        startForeground(NOTIF_ID, buildNotification("در حال اتصال به ایرپاد..."))

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(scoReceiver, filter)
        }
        scoReceiverRegistered = true

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isBluetoothScoOn = true
        audioManager.startBluetoothSco()
        // Force output to the phone's own speaker, so the live audio is
        // heard out loud on the phone instead of looping back to the AirPods.
        audioManager.isSpeakerphoneOn = true
    }

    private fun stopMonitoring() {
        isCapturing = false
        captureThread?.join(800)
        captureThread = null

        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
        audioManager.isBluetoothScoOn = false
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        if (scoReceiverRegistered) {
            try { unregisterReceiver(scoReceiver) } catch (_: Exception) {}
            scoReceiverRegistered = false
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startCapture() {
        isCapturing = true
        captureThread = Thread { captureLoop() }.apply { start() }
    }

    private fun captureLoop() {
        val sampleRates = intArrayOf(16000, 8000)
        var sampleRate = 8000
        var minBuf = 0
        var audioRecord: AudioRecord? = null

        for (rate in sampleRates) {
            val candidateMinBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (candidateMinBuf <= 0) continue
            try {
                val candidate = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    candidateMinBuf * 2
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate
                    sampleRate = rate
                    minBuf = candidateMinBuf
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {
                // try next sample rate
            }
        }

        if (audioRecord == null) {
            broadcastStatus("راه‌اندازی میکروفون ایرپاد ناموفق بود")
            isCapturing = false
            return
        }

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBuf * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        val dir = getExternalFilesDir("Recordings") ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val fileName = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val wavWriter = WavFileWriter(File(dir, fileName), sampleRate)

        audioRecord.startRecording()
        audioTrack.play()

        val buffer = ByteArray(minBuf)
        try {
            while (isCapturing) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    audioTrack.write(buffer, 0, read)
                    wavWriter.write(buffer, read)
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            try { audioTrack.stop() } catch (_: Exception) {}
            audioTrack.release()
            wavWriter.close()
            broadcastStatus("متوقف شد. فایل ذخیره شد: $fileName")
        }
    }

    private fun broadcastStatus(message: String) {
        val intent = Intent(STATUS_ACTION).setPackage(packageName).putExtra("message", message)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "پایش صدا", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirMonitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()
    }
}
