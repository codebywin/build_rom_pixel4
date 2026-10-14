package com.payout.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvDeviceSubtitle: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var edtDeviceName: EditText
    private lateinit var btnCopyId: Button
    private lateinit var btnSaveName: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var edtPrimaryServer: EditText
    private lateinit var edtBackupServer: EditText
    private lateinit var btnSaveServer: Button
    private lateinit var btnReconnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDeviceSubtitle = findViewById(R.id.tvDeviceSubtitle)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        edtDeviceName = findViewById(R.id.edtDeviceName)
        btnCopyId = findViewById(R.id.btnCopyId)
        btnSaveName = findViewById(R.id.btnSaveName)
        tvStatus = findViewById(R.id.tvStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvDetail = findViewById(R.id.tvDetail)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        edtPrimaryServer = findViewById(R.id.edtPrimaryServer)
        edtBackupServer = findViewById(R.id.edtBackupServer)
        btnSaveServer = findViewById(R.id.btnSaveServer)
        btnReconnect = findViewById(R.id.btnReconnect)

        // 1. Hiển thị thông tin định danh máy
        val deviceId = DeviceInfoManager.getDeviceId(this)
        val deviceName = DeviceInfoManager.getDeviceName(this)
        val (battery, isCharging) = DeviceInfoManager.getBatteryInfo(this)

        tvDeviceId.text = deviceId
        edtDeviceName.setText(deviceName)
        tvDeviceSubtitle.text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) • Pin: $battery% ${if (isCharging) "⚡" else ""}"

        // Điền giá trị Server chính & dự phòng hiện tại
        edtPrimaryServer.setText(ServerConfigManager.getPrimaryServer(this))
        edtBackupServer.setText(ServerConfigManager.getBackupServer(this))

        // Nút sao chép Device ID
        btnCopyId.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Device ID", deviceId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép Device ID: $deviceId", Toast.LENGTH_SHORT).show()
        }

        // Nút lưu tên gợi nhớ của máy
        btnSaveName.setOnClickListener {
            val newName = edtDeviceName.text.toString().trim()
            if (newName.isNotBlank()) {
                DeviceInfoManager.setDeviceName(this, newName)
                Toast.makeText(this, "Đã lưu tên thiết bị: $newName", Toast.LENGTH_SHORT).show()
                RemoteForegroundService.reconnectNow()
            }
        }

        // Nút lưu địa chỉ IP máy chủ (Chính & Dự phòng)
        btnSaveServer.setOnClickListener {
            val primary = edtPrimaryServer.text.toString().trim()
            val backup = edtBackupServer.text.toString().trim()

            if (primary.isBlank()) {
                Toast.makeText(this, "Vui lòng nhập IP/Host máy chủ chính!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ServerConfigManager.setServers(this, primary, backup)
            updateUiServerLabels()
            Toast.makeText(this, "Đã lưu cấu hình máy chủ!", Toast.LENGTH_SHORT).show()
            RemoteForegroundService.reconnectNow()
        }

        // Nút kết nối lại thủ công
        btnReconnect.setOnClickListener {
            RemoteForegroundService.reconnectNow()
            Toast.makeText(this, "Đang kết nối lại tới Server...", Toast.LENGTH_SHORT).show()
        }

        // 2. Hiển thị thông tin máy chủ
        updateUiServerLabels()
        tvStatus.text = RemoteForegroundService.currentStatusText

        // Lắng nghe thay đổi trạng thái kết nối
        RemoteForegroundService.onStatusListener = { status ->
            runOnUiThread {
                tvStatus.text = status
                updateUiServerLabels()
                if (status.contains("🟢")) {
                    tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
                } else if (status.contains("🔄")) {
                    tvStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                } else {
                    tvStatus.setTextColor(getColor(android.R.color.white))
                }
            }
        }

        // 3. Khởi tạo quyền HỆ THỐNG (UID 1000) và Dịch vụ nền ngầm
        thread(name = "MainInitThread") {
            val isSystem = RootTouchManager.isSystemUid
            val isReady = RootTouchManager.init()
            runOnUiThread {
                if (isSystem) {
                    tvDetail.text = "Quyền HỆ THỐNG (UID 1000 • INJECT_EVENTS): Sẵn sàng ✓"
                    tvDetail.setTextColor(getColor(android.R.color.holo_green_light))
                } else if (isReady) {
                    tvDetail.text = "Quyền SHELL: Sẵn sàng ✓"
                    tvDetail.setTextColor(getColor(android.R.color.holo_green_light))
                } else {
                    tvDetail.text = "Cảnh báo: Chưa cấp quyền HỆ THỐNG"
                    tvDetail.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }

            if (isSystem || isReady) {
                RootBootManager.setupAutoStart(this@MainActivity)
            }

            RemoteForegroundService.startService(this@MainActivity)
        }
    }

    private fun updateUiServerLabels() {
        val primary = ServerConfigManager.getPrimaryServer(this)
        val backup = ServerConfigManager.getBackupServer(this)
        val backupDisplay = if (backup.isNotBlank()) backup else "Không có"

        tvServerUrl.text = "🌐 Máy chủ chính: $primary\n🛡️ Dự phòng: $backupDisplay"
    }

    override fun onResume() {
        super.onResume()
        updateUiServerLabels()
        tvStatus.text = RemoteForegroundService.currentStatusText
    }

    override fun onDestroy() {
        super.onDestroy()
        RemoteForegroundService.onStatusListener = null
    }
}
