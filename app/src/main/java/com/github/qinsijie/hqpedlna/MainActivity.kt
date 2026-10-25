package com.github.qinsijie.hqpedlna

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var etLocalIp: TextInputEditText
    private lateinit var btnRefreshIp: MaterialButton
    private lateinit var tvStreamUrl: TextView
    private lateinit var etHqpeIp: TextInputEditText
    private lateinit var etHqpePort: TextInputEditText
    private lateinit var tvControlUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCaptureDiag: TextView
    private lateinit var tvDiagWarning: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    // DLNA 扫描与设备选择
    private lateinit var btnScanDevices: ImageButton
    private lateinit var tvScanHint: TextView
    private lateinit var spinnerDevices: Spinner
    private val discoveredDevices = mutableListOf<UpnpDevice>()
    private var activeControlUrl: String? = null

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
            val msg = if (result.resultCode != Activity.RESULT_OK) "未授予屏幕/音频捕获权限 (代码: ${result.resultCode})" else "录屏授权返回数据为空"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            tvStatus.text = "状态: $msg"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etLocalIp = findViewById(R.id.et_local_ip)
        btnRefreshIp = findViewById(R.id.btn_refresh_ip)
        tvStreamUrl = findViewById(R.id.tv_stream_url)
        etHqpeIp = findViewById(R.id.et_hqpe_ip)
        etHqpePort = findViewById(R.id.et_hqpe_port)
        tvControlUrl = findViewById(R.id.tv_control_url)
        tvStatus = findViewById(R.id.tv_status)
        tvCaptureDiag = findViewById(R.id.tv_capture_diag)
        tvDiagWarning = findViewById(R.id.tv_diag_warning)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        btnScanDevices = findViewById(R.id.btn_scan_devices)
        tvScanHint = findViewById(R.id.tv_scan_hint)
        spinnerDevices = findViewById(R.id.spinner_devices)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initDeviceSpinner()
        loadSavedSettings()
        updateControlUrlDisplay()

        if (etLocalIp.text.isNullOrBlank()) {
            autoDetectLocalIp(showToast = false)
        } else {
            updateStreamUrlDisplay()
        }

        etHqpeIp.doAfterTextChanged {
            updateControlUrlDisplay()
        }
        etHqpePort.doAfterTextChanged {
            updateControlUrlDisplay()
        }
        etLocalIp.doAfterTextChanged {
            updateStreamUrlDisplay()
            saveSettings()
        }

        btnRefreshIp.setOnClickListener {
            autoDetectLocalIp(showToast = true)
        }

        btnScanDevices.setOnClickListener {
            performSsdpScan()
        }

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
                btnScanDevices.isEnabled = !isStreaming
                btnScanDevices.alpha = if (isStreaming) 0.5f else 1.0f
                btnRefreshIp.isEnabled = !isStreaming
                if (!isStreaming && tvStatus.text.contains("正在向 HQPE 成功推流")) {
                    tvStatus.text = "状态: 投送已停止"
                }
            }
        }

        lifecycleScope.launch {
            MediaCaptureService.diagnostics.collectLatest { diag ->
                val statusDot = if (diag.isRunning) "🟢 RUNNING" else "⚪ STOPPED"
                val rateKb = String.format("%.1f", diag.bytesPerSecond / 1024.0)
                val dbStr = String.format("%.1f", diag.rmsDecibels)
                tvCaptureDiag.text = "$statusDot  |  $rateKb KB/s  |  $dbStr dB"

                if (diag.errorMessage != null) {
                    tvDiagWarning.visibility = View.VISIBLE
                    tvDiagWarning.text = "❌ 采集异常: ${diag.errorMessage}"
                } else if (diag.isSilent) {
                    tvDiagWarning.visibility = View.VISIBLE
                    tvDiagWarning.text = "⚠️ 警告: 捕获到静音数据 (0x00)！当前播放器可能阻止了系统内录，请尝试更换播放器测试 (如网易云/QQ音乐/系统自带音乐)"
                } else {
                    tvDiagWarning.visibility = View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (etLocalIp.text.isNullOrBlank()) {
            autoDetectLocalIp(showToast = false)
        }
    }

    private fun autoDetectLocalIp(showToast: Boolean = false) {
        val targetIp = etHqpeIp.text?.toString()?.trim()
        val targetPort = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: 80

        lifecycleScope.launch(Dispatchers.IO) {
            val detected = NetworkUtils.getLocalIpAddress(this@MainActivity, targetIp, targetPort)
            withContext(Dispatchers.Main) {
                etLocalIp.setText(detected)
                updateStreamUrlDisplay()
                saveSettings()
                if (showToast) {
                    Toast.makeText(this@MainActivity, "已探测并切换至本机局域网 IP: $detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateStreamUrlDisplay() {
        val localIp = etLocalIp.text?.toString()?.trim().orEmpty()
        tvStreamUrl.text = if (localIp.isNotEmpty()) {
            "推流地址: http://$localIp:8088/stream.wav"
        } else {
            "推流地址: 请输入本机 IP"
        }
    }

    private fun initDeviceSpinner() {
        val initialItems = listOf("手动输入 (默认)")
        val adapter = ArrayAdapter(this, R.layout.item_spinner_selected, initialItems)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerDevices.adapter = adapter

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position - 1 < discoveredDevices.size) {
                    val device = discoveredDevices[position - 1]
                    activeControlUrl = device.controlUrl
                    etHqpeIp.setText(device.ip)
                    etHqpePort.setText(device.port.toString())
                    updateControlUrlDisplay()
                    autoDetectLocalIp(showToast = false)
                    Toast.makeText(this@MainActivity, "已选择: ${device.friendlyName}", Toast.LENGTH_SHORT).show()
                } else {
                    activeControlUrl = null
                    updateControlUrlDisplay()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getEffectiveControlUrl(): String {
        val ip = etHqpeIp.text?.toString()?.trim().orEmpty()
        val port = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: 49152
        val current = activeControlUrl

        if (current != null) {
            try {
                val parsed = URL(current)
                if (parsed.host == ip && (parsed.port == port || (parsed.port == -1 && port == 80))) {
                    return current
                }
            } catch (_: Exception) {}
        }

        return "http://$ip:$port/upnp/control/rendertransport"
    }

    private fun updateControlUrlDisplay() {
        val url = getEffectiveControlUrl()
        tvControlUrl.text = "控制端点: $url"
    }

    private fun startScanAnimation() {
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 900
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        btnScanDevices.startAnimation(rotate)
    }

    private fun stopScanAnimation() {
        btnScanDevices.clearAnimation()
    }

    private fun performSsdpScan() {
        btnScanDevices.isEnabled = false
        startScanAnimation()
        tvScanHint.text = "正在扫描局域网 MediaRenderer/HQPE..."

        lifecycleScope.launch {
            try {
                val devices = SsdpScanner.scan(this@MainActivity, timeoutMs = 3500)
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)

                if (devices.isEmpty()) {
                    tvScanHint.text = "未扫描到 DLNA 设备，可直接手动输入 IP 与端口"
                    Toast.makeText(this@MainActivity, "未扫描到设备，可直接手动输入 IP 与端口", Toast.LENGTH_LONG).show()
                } else {
                    tvScanHint.text = "已扫描到 ${devices.size} 台媒体设备，已自动选择首选设备"
                    val spinnerItems = mutableListOf("手动输入 (自定义)")
                    spinnerItems.addAll(devices.map { "${it.friendlyName} (${it.ip}:${it.port})" })

                    val adapter = ArrayAdapter(this@MainActivity, R.layout.item_spinner_selected, spinnerItems)
                    adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
                    spinnerDevices.adapter = adapter
                    spinnerDevices.setSelection(1) // Auto-select the first discovered device
                }
            } finally {
                stopScanAnimation()
                btnScanDevices.isEnabled = true
            }
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("hqpe_ip", "10.10.10.120")
        val savedPort = prefs.getString("hqpe_port", "49152")
        val savedLocalIp = prefs.getString("hqpe_local_ip", null)
        activeControlUrl = prefs.getString("hqpe_control_url", null)

        etHqpeIp.setText(savedIp)
        etHqpePort.setText(savedPort)
        if (!savedLocalIp.isNullOrBlank()) {
            etLocalIp.setText(savedLocalIp)
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hqpe_ip", etHqpeIp.text?.toString()?.trim())
            .putString("hqpe_port", etHqpePort.text?.toString()?.trim())
            .putString("hqpe_local_ip", etLocalIp.text?.toString()?.trim())
            .putString("hqpe_control_url", activeControlUrl)
            .apply()
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
        val controlUrl = getEffectiveControlUrl()
        val localIp = etLocalIp.text?.toString()?.trim().let {
            if (it.isNullOrBlank()) NetworkUtils.getLocalIpAddress(this, etHqpeIp.text?.toString(), etHqpePort.text?.toString()?.toIntOrNull() ?: 80)
            else it
        }
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
            delay(500)

            tvStatus.text = "状态: 正在向 $controlUrl 设置推流地址..."
            val setUriResult = HqpeClient.setUri(controlUrl, streamUrl)
            if (setUriResult.isFailure) {
                val err = setUriResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "状态: SetAVTransportURI 失败 ($err)"
                Toast.makeText(this@MainActivity, "连接 HQPE 失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "状态: 正在触发播放 (Play)..."
            val playResult = HqpeClient.play(controlUrl)
            if (playResult.isFailure) {
                val err = playResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "状态: Play 失败 ($err)"
                Toast.makeText(this@MainActivity, "HQPE Play 指令失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "状态: 正在向 HQPE 成功推流中 ($streamUrl)"
        }
    }

    private fun stopCaptureAndHqpe() {
        val controlUrl = getEffectiveControlUrl()

        tvStatus.text = "状态: 正在向 HQPE 发送 Stop 指令..."

        lifecycleScope.launch {
            val stopResult = HqpeClient.stop(controlUrl)
            if (stopResult.isFailure) {
                val err = stopResult.exceptionOrNull()?.message
                Toast.makeText(this@MainActivity, "HQPE Stop 指令返回: $err", Toast.LENGTH_SHORT).show()
            }

            // Stop service
            val serviceIntent = Intent(this@MainActivity, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_STOP
            }
            startService(serviceIntent)

            tvStatus.text = "状态: 已停止投送"
        }
    }
}
