package com.github.qinsijie.hqpedlna

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Main screen views
    private lateinit var btnScanDevices: ImageButton
    private lateinit var btnOpenConfig: ImageButton
    private lateinit var cardTargetDevice: LinearLayout
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIpPort: TextView
    private lateinit var btnMasterDial: FrameLayout
    private lateinit var viewDialOuterRing: View
    private lateinit var viewDialEdgeDot: View
    private lateinit var tvDialAction: TextView
    private lateinit var tvDialSubtext: TextView
    private lateinit var layoutStatusPill: LinearLayout
    private lateinit var viewStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvHudSpec: TextView
    private lateinit var tvDiagWarning: TextView
    private lateinit var tvStreamUrl: TextView
    private lateinit var btnCopyUrl: MaterialButton

    // In-memory configuration
    private var currentDevName: String = "HQPlayer Embedded"
    private var currentHqpeIp: String = "10.10.10.120"
    private var currentHqpePort: Int = 49152
    private var currentLocalIp: String = ""
    private var activeControlUrl: String? = null

    // DLNA discovered devices
    private val discoveredDevices = mutableListOf<UpnpDevice>()
    private var dialRotateAnimation: RotateAnimation? = null

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

        // Bind main screen views
        btnScanDevices = findViewById(R.id.btn_scan_devices)
        btnOpenConfig = findViewById(R.id.btn_open_config)
        cardTargetDevice = findViewById(R.id.card_target_device)
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIpPort = findViewById(R.id.tv_device_ip_port)
        btnMasterDial = findViewById(R.id.btn_master_dial)
        viewDialOuterRing = findViewById(R.id.view_dial_outer_ring)
        viewDialEdgeDot = findViewById(R.id.view_dial_edge_dot)
        tvDialAction = findViewById(R.id.tv_dial_action)
        tvDialSubtext = findViewById(R.id.tv_dial_subtext)
        layoutStatusPill = findViewById(R.id.layout_status_pill)
        viewStatusDot = findViewById(R.id.view_status_dot)
        tvStatus = findViewById(R.id.tv_status)
        tvHudSpec = findViewById(R.id.tv_hud_spec)
        tvDiagWarning = findViewById(R.id.tv_diag_warning)
        tvStreamUrl = findViewById(R.id.tv_stream_url)
        btnCopyUrl = findViewById(R.id.btn_copy_url)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        loadSavedSettings()
        updateMainScreenViews()

        if (currentLocalIp.isBlank()) {
            autoDetectLocalIp(showToast = false)
        }

        // Click listeners
        btnScanDevices.setOnClickListener {
            performSsdpScan()
        }

        btnOpenConfig.setOnClickListener {
            openConfigBottomSheet()
        }

        cardTargetDevice.setOnClickListener {
            openConfigBottomSheet()
        }

        btnMasterDial.setOnClickListener {
            if (MediaCaptureService.isStreamingState.value) {
                stopCaptureAndHqpe()
            } else {
                checkPermissionsAndStart()
            }
        }

        btnCopyUrl.setOnClickListener {
            copyStreamUrlToClipboard()
        }

        // Observe Streaming state
        lifecycleScope.launch {
            MediaCaptureService.isStreamingState.collectLatest { isStreaming ->
                updateDialState(isStreaming)
            }
        }

        // Observe Diagnostics for dynamic Bit-Perfect HUD
        lifecycleScope.launch {
            MediaCaptureService.diagnostics.collectLatest { diag ->
                val rateKb = String.format("%.1f", diag.bytesPerSecond / 1024.0)
                val sampleRateKhz = String.format("%.1f", diag.sampleRate / 1000.0)
                val dbStr = String.format("%.1f", diag.rmsDecibels)

                if (diag.isRunning) {
                    tvHudSpec.text = "$sampleRateKhz kHz · ${diag.bitsPerSample}-Bit · 源码直通 | $rateKb KB/s | $dbStr dB"
                } else {
                    tvHudSpec.text = "48.0 kHz · 16-Bit · 源码直通 | 0.0 KB/s | 0 Underrun"
                }

                if (diag.errorMessage != null) {
                    tvDiagWarning.text = "❌ 采集异常: ${diag.errorMessage}"
                    tvDiagWarning.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                } else if (diag.isSilent) {
                    tvDiagWarning.text = "⚠️ 警告: 捕获到静音数据 (0x00)！请检查播放器内录权限"
                    tvDiagWarning.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                } else {
                    tvDiagWarning.text = "提示：若外放有声，按音量键调低即可，不影响推流与抓流。"
                    tvDiagWarning.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentLocalIp.isBlank()) {
            autoDetectLocalIp(showToast = false)
        }
    }

    private fun updateMainScreenViews() {
        tvDeviceName.text = currentDevName
        tvDeviceIpPort.text = "$currentHqpeIp:$currentHqpePort · 就绪"
        tvStreamUrl.text = if (currentLocalIp.isNotBlank()) "http://$currentLocalIp:8088/stream.wav" else "待探测本机 IP"
    }

    private fun updateDialState(isStreaming: Boolean) {
        if (isStreaming) {
            btnMasterDial.setBackgroundResource(R.drawable.bg_dial_active)
            tvDialAction.text = "正在串流 HQPE"
            tvDialAction.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
            tvDialSubtext.text = "TAP TO DISCONNECT"
            tvDialSubtext.setTextColor(ContextCompat.getColor(this, R.color.accent_red))

            layoutStatusPill.setBackgroundResource(R.drawable.bg_pill_status_active)
            viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.cyan_neon))
            tvStatus.text = "正在向 HQPE 成功推流"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))

            viewDialEdgeDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.cyan_neon))
            startDialRingSpin()
        } else {
            btnMasterDial.setBackgroundResource(R.drawable.bg_dial_idle)
            tvDialAction.text = "点击开启投送"
            tvDialAction.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvDialSubtext.text = "TAP TO ENGAGE"
            tvDialSubtext.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            layoutStatusPill.setBackgroundResource(R.drawable.bg_pill_status_idle)
            viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_muted))
            tvStatus.text = "待机待命 · 准备就绪"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            viewDialEdgeDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.cyan_subtle))
            stopDialRingSpin()
        }
    }

    private fun startDialRingSpin() {
        if (dialRotateAnimation != null) return
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 22000
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        dialRotateAnimation = rotate
        viewDialOuterRing.startAnimation(rotate)
    }

    private fun stopDialRingSpin() {
        viewDialOuterRing.clearAnimation()
        dialRotateAnimation = null
    }

    private fun copyStreamUrlToClipboard() {
        val url = tvStreamUrl.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("HQPE Stream URL", url)
        clipboard.setPrimaryClip(clip)

        btnCopyUrl.text = "已复制!"
        Toast.makeText(this, "推流端点已复制到剪贴板", Toast.LENGTH_SHORT).show()
        btnCopyUrl.postDelayed({
            btnCopyUrl.text = "复制"
        }, 1500)
    }

    private fun autoDetectLocalIp(showToast: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val detected = NetworkUtils.getLocalIpAddress(this@MainActivity, currentHqpeIp, currentHqpePort)
            withContext(Dispatchers.Main) {
                currentLocalIp = detected
                updateMainScreenViews()
                saveSettings()
                if (showToast) {
                    Toast.makeText(this@MainActivity, "已探测并切换至本机局域网 IP: $detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getEffectiveControlUrl(): String {
        val current = activeControlUrl
        if (current != null) {
            try {
                val parsed = URL(current)
                if (parsed.host == currentHqpeIp && (parsed.port == currentHqpePort || (parsed.port == -1 && currentHqpePort == 80))) {
                    return current
                }
            } catch (_: Exception) {}
        }
        return "http://$currentHqpeIp:$currentHqpePort/upnp/control/rendertransport"
    }

    private fun openConfigBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_config_bottom_sheet, null)
        dialog.setContentView(sheetView)

        val btnClose = sheetView.findViewById<ImageButton>(R.id.btn_sheet_close)
        val spinnerDevices = sheetView.findViewById<Spinner>(R.id.spinner_sheet_devices)
        val etDevName = sheetView.findViewById<TextInputEditText>(R.id.et_sheet_dev_name)
        val etHqpeIp = sheetView.findViewById<TextInputEditText>(R.id.et_sheet_hqpe_ip)
        val etHqpePort = sheetView.findViewById<TextInputEditText>(R.id.et_sheet_hqpe_port)
        val etLocalIp = sheetView.findViewById<TextInputEditText>(R.id.et_sheet_local_ip)
        val btnDetectIp = sheetView.findViewById<MaterialButton>(R.id.btn_sheet_detect_ip)
        val tvControlUrl = sheetView.findViewById<TextView>(R.id.tv_sheet_control_url)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btn_sheet_save)

        // Pre-fill values
        etDevName.setText(currentDevName)
        etHqpeIp.setText(currentHqpeIp)
        etHqpePort.setText(currentHqpePort.toString())
        etLocalIp.setText(currentLocalIp)
        tvControlUrl.text = "控制端点: ${getEffectiveControlUrl()}"

        // Populate spinner
        val spinnerItems = mutableListOf("手动输入 (自定义配置)")
        if (discoveredDevices.isNotEmpty()) {
            spinnerItems.addAll(discoveredDevices.map { "${it.friendlyName} (${it.ip}:${it.port})" })
        }
        val adapter = ArrayAdapter(this, R.layout.item_spinner_selected, spinnerItems)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerDevices.adapter = adapter

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position - 1 < discoveredDevices.size) {
                    val device = discoveredDevices[position - 1]
                    etDevName.setText(device.friendlyName)
                    etHqpeIp.setText(device.ip)
                    etHqpePort.setText(device.port.toString())
                    activeControlUrl = device.controlUrl
                    tvControlUrl.text = "控制端点: ${device.controlUrl}"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDetectIp.setOnClickListener {
            val targetIp = etHqpeIp.text?.toString()?.trim() ?: currentHqpeIp
            val targetPort = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: currentHqpePort
            lifecycleScope.launch(Dispatchers.IO) {
                val detected = NetworkUtils.getLocalIpAddress(this@MainActivity, targetIp, targetPort)
                withContext(Dispatchers.Main) {
                    etLocalIp.setText(detected)
                    Toast.makeText(this@MainActivity, "已探测本机 IP: $detected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            currentDevName = etDevName.text?.toString()?.trim().let { if (it.isNullOrBlank()) "HQPlayer Embedded" else it }
            currentHqpeIp = etHqpeIp.text?.toString()?.trim().let { if (it.isNullOrBlank()) "10.10.10.120" else it }
            currentHqpePort = etHqpePort.text?.toString()?.trim()?.toIntOrNull() ?: 49152
            currentLocalIp = etLocalIp.text?.toString()?.trim().orEmpty()

            saveSettings()
            updateMainScreenViews()
            dialog.dismiss()
            Toast.makeText(this, "渲染器与推流配置已更新", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun performSsdpScan() {
        btnScanDevices.isEnabled = false
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
        Toast.makeText(this, "正在扫描局域网 MediaRenderer/HQPE...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val devices = SsdpScanner.scan(this@MainActivity, timeoutMs = 3500)
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)

                if (devices.isEmpty()) {
                    Toast.makeText(this@MainActivity, "未扫描到 DLNA 设备，已保留当前配置", Toast.LENGTH_LONG).show()
                } else {
                    val first = devices[0]
                    currentDevName = first.friendlyName
                    currentHqpeIp = first.ip
                    currentHqpePort = first.port
                    activeControlUrl = first.controlUrl
                    updateMainScreenViews()
                    saveSettings()
                    autoDetectLocalIp(showToast = false)
                    Toast.makeText(this@MainActivity, "已自动连接发现的首选设备: ${first.friendlyName}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                btnScanDevices.clearAnimation()
                btnScanDevices.isEnabled = true
            }
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        currentDevName = prefs.getString("hqpe_dev_name", "HQPlayer Embedded") ?: "HQPlayer Embedded"
        currentHqpeIp = prefs.getString("hqpe_ip", "10.10.10.120") ?: "10.10.10.120"
        currentHqpePort = prefs.getString("hqpe_port", "49152")?.toIntOrNull() ?: 49152
        currentLocalIp = prefs.getString("hqpe_local_ip", "").orEmpty()
        activeControlUrl = prefs.getString("hqpe_control_url", null)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hqpe_dev_name", currentDevName)
            .putString("hqpe_ip", currentHqpeIp)
            .putString("hqpe_port", currentHqpePort.toString())
            .putString("hqpe_local_ip", currentLocalIp)
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
        val localIp = if (currentLocalIp.isNotBlank()) currentLocalIp else NetworkUtils.getLocalIpAddress(this, currentHqpeIp, currentHqpePort)
        val streamUrl = "http://$localIp:8088/stream.wav"

        tvStatus.text = "启动音频捕获与 HTTP 服务..."

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

            tvStatus.text = "向 HQPE 设置推流端点..."
            val setUriResult = HqpeClient.setUri(controlUrl, streamUrl)
            if (setUriResult.isFailure) {
                val err = setUriResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "SetAVTransportURI 失败 ($err)"
                Toast.makeText(this@MainActivity, "连接 HQPE 失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "正在触发播放 (Play)..."
            val playResult = HqpeClient.play(controlUrl)
            if (playResult.isFailure) {
                val err = playResult.exceptionOrNull()?.message ?: "未知网络错误"
                tvStatus.text = "Play 失败 ($err)"
                Toast.makeText(this@MainActivity, "HQPE Play 指令失败: $err", Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "正在向 HQPE 成功推流"
        }
    }

    private fun stopCaptureAndHqpe() {
        val controlUrl = getEffectiveControlUrl()

        tvStatus.text = "正在向 HQPE 发送 Stop 指令..."

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

            tvStatus.text = "待机待命 · 准备就绪"
        }
    }
}
