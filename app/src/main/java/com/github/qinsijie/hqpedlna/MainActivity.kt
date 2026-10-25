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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tvLocalIp: TextView
    private lateinit var tvStreamUrl: TextView
    private lateinit var etHqpeIp: TextInputEditText
    private lateinit var etHqpePort: TextInputEditText
    private lateinit var tvControlUrl: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // DLNA 扫描与设备选择
    private lateinit var btnScanDevices: Button
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
        tvControlUrl = findViewById(R.id.tv_control_url)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        btnScanDevices = findViewById(R.id.btn_scan_devices)
        tvScanHint = findViewById(R.id.tv_scan_hint)
        spinnerDevices = findViewById(R.id.spinner_devices)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initDeviceSpinner()
        loadSavedSettings()
        updateNetworkInfo()
        updateControlUrlDisplay()

        etHqpeIp.doAfterTextChanged { updateControlUrlDisplay() }
        etHqpePort.doAfterTextChanged { updateControlUrlDisplay() }

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

    private fun initDeviceSpinner() {
        val initialItems = listOf("手动输入 (默认)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, initialItems)
        spinnerDevices.adapter = adapter

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position - 1 < discoveredDevices.size) {
                    val device = discoveredDevices[position - 1]
                    activeControlUrl = device.controlUrl
                    etHqpeIp.setText(device.ip)
                    etHqpePort.setText(device.port.toString())
                    updateControlUrlDisplay()
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

    private fun performSsdpScan() {
        btnScanDevices.isEnabled = false
        tvScanHint.text = "正在扫描局域网 UPnP/DLNA 渲染器 (SSDP)..."

        lifecycleScope.launch {
            val devices = SsdpScanner.scan(this@MainActivity, timeoutMs = 3500)
            btnScanDevices.isEnabled = true

            discoveredDevices.clear()
            discoveredDevices.addAll(devices)

            if (devices.isEmpty()) {
                tvScanHint.text = "未扫描到 DLNA 设备，请确认设备开启了 DLNA 并在同一局域网"
                Toast.makeText(this@MainActivity, "未扫描到设备，可直接手动输入 IP 与端口", Toast.LENGTH_LONG).show()
            } else {
                tvScanHint.text = "扫描到 ${devices.size} 台设备，已自动选择首选设备"
                val spinnerItems = mutableListOf("手动输入 (自定义)")
                spinnerItems.addAll(devices.map { "${it.friendlyName} (${it.ip}:${it.port})" })

                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, spinnerItems)
                spinnerDevices.adapter = adapter
                spinnerDevices.setSelection(1) // Auto-select the first discovered device
            }
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("hqpe_ip", "10.10.10.120")
        val savedPort = prefs.getString("hqpe_port", "49152")
        activeControlUrl = prefs.getString("hqpe_control_url", null)
        etHqpeIp.setText(savedIp)
        etHqpePort.setText(savedPort)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("hqpe_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hqpe_ip", etHqpeIp.text?.toString()?.trim())
            .putString("hqpe_port", etHqpePort.text?.toString()?.trim())
            .putString("hqpe_control_url", activeControlUrl)
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
        val controlUrl = getEffectiveControlUrl()
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
