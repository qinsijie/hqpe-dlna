package com.github.qinsijie.hqpedlna

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var tvLocalIp: TextView
    private lateinit var tvStreamUrl: TextView
    private lateinit var etHqpeIp: TextInputEditText
    private lateinit var etHqpePort: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            launchScreenCapture()
        } else {
            Toast.makeText(this, "需要录音权限以捕获媒体音频", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureAndHqpePlayback(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "用户取消了音频捕获授权", Toast.LENGTH_SHORT).show()
            tvStatus.text = "状态: 授权已取消"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLocalIp = findViewById(R.id.tv_local_ip)
        tvStreamUrl = findViewById(R.id.tv_stream_url)
        etHqpeIp = findViewById(R.id.et_hqpe_ip)
        etHqpePort = findViewById(R.id.et_hqpe_port)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        loadSavedSettings()
        updateNetworkInfo()

        btnStart.setOnClickListener {
            checkPermissionsAndStart()
        }

        btnStop.setOnClickListener {
            stopCaptureAndHqpe()
        }

        lifecycleScope.launch {
            MediaCaptureService.isStreamingState.collectLatest { isStreaming ->
                btnStart.isEnabled = !isStreaming
                btnStop.isEnabled = isStreaming
                if (!isStreaming && tvStatus.text.contains("正在向 HQPE 推流")) {
                    tvStatus.text = "状态: 投送已停止"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNetworkInfo()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("hqpe_ip", "10.10.10.120")
        val savedPort = prefs.getString("hqpe_port", "49152")
        etHqpeIp.setText(savedIp)
        etHqpePort.setText(savedPort)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hqpe_ip", etHqpeIp.text?.toString()?.trim())
            .putString("hqpe_port", etHqpePort.text?.toString()?.trim())
            .apply()
    }

    private fun updateNetworkInfo() {
        val localIp = getLocalIpAddress()
        tvLocalIp.text = "本机 IP: $localIp"
        tvStreamUrl.text = "推流地址: http://$localIp:8088/stream.wav"
    }

    private fun checkPermissionsAndStart() {
        saveSettings()

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            launchScreenCapture()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startCaptureAndHqpePlayback(resultCode: Int, resultData: Intent) {
        val hqpeIp = etHqpeIp.text?.toString()?.trim().orEmpty()
        val hqpePort = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: 49152
        val localIp = getLocalIpAddress()
        val streamUrl = "http://$localIp:8088/stream.wav"

        tvStatus.text = "状态: 正在启动音频捕获与 HTTP 服务..."

        // 1. Start MediaCaptureService
        val serviceIntent = Intent(this, MediaCaptureService::class.java).apply {
            action = MediaCaptureService.ACTION_START
            putExtra(MediaCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // 2. Dispatch SOAP to HQPE
        lifecycleScope.launch {
            // Brief pause to allow local HTTP server to bind
            delay(500)

            tvStatus.text = "状态: 正在向 HQPE ($hqpeIp:$hqpePort) 设置播放流..."
            val setUriResult = HqpeClient.setUri(hqpeIp, hqpePort, streamUrl)
            if (setUriResult.isFailure) {
                val err = setUriResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "状态: SetAVTransportURI 失败 ($err)"
                Toast.makeText(this@MainActivity, "连接 HQPE 失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "状态: 正在触发 HQPE 播放..."
            val playResult = HqpeClient.play(hqpeIp, hqpePort)
            if (playResult.isFailure) {
                val err = playResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "状态: Play 失败 ($err)"
                Toast.makeText(this@MainActivity, "HQPE Play 指令失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "状态: 正在向 HQPE 推流中 ($streamUrl)"
        }
    }

    private fun stopCaptureAndHqpe() {
        val hqpeIp = etHqpeIp.text?.toString()?.trim().orEmpty()
        val hqpePort = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: 49152

        tvStatus.text = "状态: 正在向 HQPE 发送 Stop 指令..."

        lifecycleScope.launch {
            val stopResult = HqpeClient.stop(hqpeIp, hqpePort)
            if (stopResult.isFailure) {
                val err = stopResult.exceptionOrNull()?.message
                Toast.makeText(this@MainActivity, "HQPE Stop 指令返回异常: $err", Toast.LENGTH_SHORT).show()
            }

            // Stop service
            val serviceIntent = Intent(this@MainActivity, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_STOP
            }
            startService(serviceIntent)

            tvStatus.text = "状态: 已停止投送"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
