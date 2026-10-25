package com.github.qinsijie.hqpedlna

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class MediaCaptureService : Service() {

    private val tag = "MediaCaptureService"

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private var localHttpServer: LocalHttpServer? = null

    // Phone speaker auto-mute tracking
    private var previousMediaVolume: Int? = null
    private var isMutedByService = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(tag, "MediaProjection was stopped by system or user")
            stopCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startForegroundWithNotification()
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(tag, "Missing resultCode or resultData in ACTION_START")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HQPE-DLNA Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for streaming system audio to HQPlayer"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, MediaCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HQPE-DLNA 音频串流")
            .setContentText("正在捕获媒体音频并通过 HTTP 广播 (端口 8088)")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(activityPendingIntent)
            .addAction(R.drawable.ic_launcher, "停止投送", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun muteDeviceAudio() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            previousMediaVolume = currentVol
            if (currentVol > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
                isMutedByService = true
                Log.i(tag, "Muted phone STREAM_MUSIC output to avoid duplicate sound (previous volume: $currentVol)")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to mute STREAM_MUSIC: ${e.message}")
        }
    }

    private fun restoreDeviceAudio() {
        if (isMutedByService) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                previousMediaVolume?.let { vol ->
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                }
                Log.i(tag, "Restored phone STREAM_MUSIC to volume $previousMediaVolume")
            } catch (e: Exception) {
                Log.w(tag, "Failed to restore STREAM_MUSIC: ${e.message}")
            }
            isMutedByService = false
            previousMediaVolume = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (isCapturing.get()) return

        try {
            // 1. Start embedded HTTP live server
            localHttpServer = LocalHttpServer(8088).apply { start() }

            // 2. Initialize MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(tag, "Failed to obtain MediaProjection")
                stopCapture()
                return
            }
            mediaProjection = projection
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            // 3. AudioPlaybackCaptureConfiguration:
            // Match USAGE_MEDIA, USAGE_UNKNOWN and USAGE_GAME to ensure third-party music players
            // (e.g., LX Music/Flutter audio tracks) are captured properly.
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            // 4. Mute phone physical speaker output so only HQPE plays audio
            muteDeviceAudio()

            // 5. Audio format: 48000Hz, 16bit, Stereo
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufferSize * 4).coerceAtLeast(16384)

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord initialization failed, state: ${record.state}")
                stopCapture()
                return
            }

            audioRecord = record
            record.startRecording()
            isCapturing.set(true)
            _isStreamingState.value = true

            // 6. Start audio reading loop with throughput & zero-data monitoring
            captureThread = Thread({
                Log.i(tag, "Audio capture loop started")
                val buffer = ByteArray(4096)
                var bytesReadSecond = 0L
                var lastLogTime = System.currentTimeMillis()
                var consecutiveZeroChunks = 0

                while (isCapturing.get()) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        bytesReadSecond += bytesRead

                        // Check if chunk is all zeros
                        var isAllZeros = true
                        for (i in 0 until bytesRead) {
                            if (buffer[i] != 0.toByte()) {
                                isAllZeros = false
                                break
                            }
                        }

                        if (isAllZeros) {
                            consecutiveZeroChunks++
                        } else {
                            consecutiveZeroChunks = 0
                        }

                        localHttpServer?.writeAudioData(buffer, 0, bytesRead)

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastLogTime
                        if (elapsed >= 1000) {
                            val rate = bytesReadSecond * 1000 / elapsed
                            Log.i(tag, "AudioCapture: $rate bytes/sec throughput (nominal: 192000 B/s)")
                            if (consecutiveZeroChunks >= 20) {
                                Log.w(tag, "AudioCapture warning: Captured $consecutiveZeroChunks consecutive zero chunks! App may be idle, paused, or using exclusive audio mode.")
                            }
                            bytesReadSecond = 0
                            lastLogTime = now
                        }
                    } else if (bytesRead < 0) {
                        Log.w(tag, "AudioRecord read error code: $bytesRead")
                        break
                    }
                }
                Log.i(tag, "Audio capture loop finished")
            }, "AudioCaptureThread").apply { start() }

        } catch (e: Exception) {
            Log.e(tag, "Error starting audio capture", e)
            stopCapture()
        }
    }

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            restoreDeviceAudio()
            stopSelf()
            return
        }

        _isStreamingState.value = false

        restoreDeviceAudio()

        try {
            captureThread?.interrupt()
            captureThread = null
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing MediaProjection", e)
        }
        mediaProjection = null

        try {
            localHttpServer?.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping LocalHttpServer", e)
        }
        localHttpServer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(tag, "MediaCaptureService fully stopped")
    }

    override fun onDestroy() {
        stopCapture()
        restoreDeviceAudio()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.github.qinsijie.hqpedlna.action.START"
        const val ACTION_STOP = "com.github.qinsijie.hqpedlna.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "hqpe_capture_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isStreamingState = MutableStateFlow(false)
        val isStreamingState = _isStreamingState.asStateFlow()
    }
}
