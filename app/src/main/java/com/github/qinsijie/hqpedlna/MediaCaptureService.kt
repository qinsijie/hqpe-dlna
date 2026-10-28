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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureDiagnostics(
    val isRunning: Boolean = false,
    val bytesPerSecond: Long = 0,
    val rmsDecibels: Double = -96.0,
    val isSilent: Boolean = false,
    val errorMessage: String? = null
)

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
                    val errMsg = "未获取到有效的录屏/录音授权结果，无法启动服务"
                    Log.e(tag, errMsg)
                    showToast(errMsg)
                    _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
        }

        return START_NOT_STICKY
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
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
            .setSmallIcon(R.drawable.ic_stat_streaming)
            .setContentIntent(activityPendingIntent)
            .addAction(R.drawable.ic_stat_streaming, "停止投送", stopPendingIntent)
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
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                val checkVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (checkVol == 0) {
                    isMutedByService = true
                    Log.i(tag, "已将手机外放媒体音量强制置零 (原音量: $currentVol)")
                } else {
                    Log.w(tag, "系统限制了 setStreamVolume(STREAM_MUSIC, 0)，当前外放音量仍为: $checkVol")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "强制外放静音失败: ${e.message}")
        }
    }

    private fun restoreDeviceAudio() {
        if (isMutedByService) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                previousMediaVolume?.let { vol ->
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                    Log.i(tag, "已恢复手机外放媒体音量至: $vol")
                }
            } catch (e: Exception) {
                Log.w(tag, "恢复外放音量失败: ${e.message}")
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
                val errMsg = "获取 MediaProjection 失败，请确认系统录屏权限已允许"
                Log.e(tag, errMsg)
                showToast(errMsg)
                _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
                stopCapture()
                return
            }
            mediaProjection = projection
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            // 3. AudioPlaybackCaptureConfiguration
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            // 4. Mute phone physical speaker output
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
                val errMsg = "AudioRecord 初始化失败 (state: ${record.state})，请检查权限与录屏状态"
                Log.e(tag, errMsg)
                showToast(errMsg)
                _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
                stopCapture()
                return
            }

            audioRecord = record
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    val errMsg = "AudioRecord 录音状态异常: ${record.recordingState}"
                    Log.e(tag, errMsg)
                    showToast(errMsg)
                    _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
                    stopCapture()
                    return
                }
            } catch (e: Exception) {
                val errMsg = "AudioRecord startRecording 失败: ${e.message}"
                Log.e(tag, errMsg, e)
                showToast(errMsg)
                _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
                stopCapture()
                return
            }

            isCapturing.set(true)
            _isStreamingState.value = true
            _diagnostics.value = CaptureDiagnostics(isRunning = true)

            // 6. Start audio reading loop with throughput & RMS calculation
            captureThread = Thread({
                Log.i(tag, "Audio capture loop started")
                val buffer = ByteArray(4096)
                var bytesReadSecond = 0L
                var lastLogTime = System.currentTimeMillis()
                var consecutiveZeroChunks = 0
                var logCounter = 0
                var currentRmsDb = -96.0

                while (isCapturing.get()) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        bytesReadSecond += bytesRead

                        // Check zeros & calculate RMS
                        var isAllZeros = true
                        var sumSquares = 0.0
                        var sampleCount = 0
                        var i = 0
                        while (i < bytesRead - 1) {
                            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                            val normalized = sample.toShort()
                            if (normalized.toInt() != 0) {
                                isAllZeros = false
                            }
                            sumSquares += normalized.toDouble() * normalized.toDouble()
                            sampleCount++
                            i += 2
                        }

                        if (sampleCount > 0) {
                            val rms = Math.sqrt(sumSquares / sampleCount)
                            currentRmsDb = if (rms > 0) 20 * Math.log10(rms / 32767.0) else -96.0
                        }

                        if (isAllZeros) {
                            consecutiveZeroChunks++
                        } else {
                            consecutiveZeroChunks = 0
                        }

                        if (++logCounter % 25 == 0) {
                            Log.d(tag, "[AudioRecord] 正在采集并向 HQPE 写入字节: $bytesRead bytes")
                        }

                        localHttpServer?.writeAudioData(buffer, 0, bytesRead)

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastLogTime
                        if (elapsed >= 1000) {
                            val rate = bytesReadSecond * 1000 / elapsed
                            val isSilent = consecutiveZeroChunks >= 30 // ~1.5 - 2s of silence
                            if (isSilent) {
                                Log.w(tag, "[AudioRecord] 警告: 连续检测到全零空数据！当前播放应用可能使用了系统独占音频模式或未允许内录。")
                            }

                            _diagnostics.value = CaptureDiagnostics(
                                isRunning = true,
                                bytesPerSecond = rate,
                                rmsDecibels = currentRmsDb,
                                isSilent = isSilent
                            )

                            bytesReadSecond = 0
                            lastLogTime = now
                        }
                    } else if (bytesRead < 0) {
                        val errMsg = "AudioRecord 读取异常错误码: $bytesRead"
                        Log.e(tag, errMsg)
                        showToast(errMsg)
                        _diagnostics.value = CaptureDiagnostics(isRunning = false, errorMessage = errMsg)
                        break
                    }
                }
                Log.i(tag, "Audio capture loop finished")
            }, "AudioCaptureThread").apply { start() }

        } catch (e: Exception) {
            val errMsg = "启动音频采集异常: ${e.message}"
            Log.e(tag, errMsg, e)
            showToast(errMsg)
            _diagnostics.value = CaptureDiagnostics(errorMessage = errMsg)
            stopCapture()
        }
    }

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) {
            restoreDeviceAudio()
            _diagnostics.value = CaptureDiagnostics(isRunning = false)
            stopSelf()
            return
        }

        _isStreamingState.value = false
        _diagnostics.value = CaptureDiagnostics(isRunning = false)

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

        private val _diagnostics = MutableStateFlow(CaptureDiagnostics())
        val diagnostics = _diagnostics.asStateFlow()
    }
}
