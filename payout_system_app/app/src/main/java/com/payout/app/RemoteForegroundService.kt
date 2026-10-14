package com.payout.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import kotlin.concurrent.thread

class RemoteForegroundService : Service() {

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL_ID = "payout_manager_channel"
        private const val NOTIFICATION_ID = 1001

        data class ServerEndpoint(val name: String, val url: String, val shortName: String)

        fun getServers(context: Context): List<ServerEndpoint> {
            val list = mutableListOf<ServerEndpoint>()
            val primary = ServerConfigManager.getPrimaryServer(context).trim()
            if (primary.isNotBlank()) {
                val url = ServerConfigManager.buildWsUrl(primary, context)
                list.add(ServerEndpoint("Máy chủ chính ($primary)", url, primary))
            }
            val backup = ServerConfigManager.getBackupServer(context).trim()
            if (backup.isNotBlank()) {
                val url = ServerConfigManager.buildWsUrl(backup, context)
                list.add(ServerEndpoint("Máy chủ dự phòng ($backup)", url, backup))
            }
            if (list.isEmpty()) {
                val def = ServerConfigManager.DEFAULT_PRIMARY_SERVER
                list.add(ServerEndpoint("Máy chủ chính ($def)", ServerConfigManager.buildWsUrl(def, context), def))
            }
            return list
        }

        var isRunning = false
            private set

        @Volatile
        var currentStatusText: String = "🔄 Đang khởi tạo kết nối..."
            private set

        @Volatile
        var activePayoutJob: JSONObject? = null
            private set

        var onStatusListener: ((String) -> Unit)? = null
        private var instance: RemoteForegroundService? = null

        fun handleBankNotification(parsed: ParsedBankNotification) {
            instance?.let { service ->
                service.processBankNotification(parsed)
            }
        }

        fun reconnectNow() {
            instance?.let { service ->
                service.mainHandler.post {
                    service.currentServerIndex = 0
                    service.startConnect(forceNext = false, forceReconnect = true)
                }
            }
        }

        fun startService(context: Context) {
            try {
                val intent = Intent(context, RemoteForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting service safely", e)
            }
        }
    }

    enum class ConnectionState {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    @Volatile private var wsClient: RemoteWsClient? = null
    @Volatile private var currentServerIndex = 0
    @Volatile private var connectionState = ConnectionState.IDLE
    @Volatile private var lastConnectAttempt = 0L
    private var reconnectRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        Log.i(TAG, "=== RemoteForegroundService OnCreate ===")

        // 1. Tạo Notification để Android cho phép chạy ngầm vĩnh viễn (Payout Manager)
        createNotificationChannel()
        val notif = buildNotification("Dịch vụ Payout Manager đang chạy ngầm")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting foreground with type", e)
            try {
                startForeground(NOTIFICATION_ID, notif)
            } catch (ignored: Throwable) {}
        }

        // 2. Khóa CPU WakeLock không cho vào trạng thái ngủ sâu (Doze Mode)
        acquireWakeLock()

        // 3. Khởi tạo Root, Bypass tiết kiệm pin và ẩn thông báo qua Root nếu cần
        thread(name = "ServiceInitThread") {
            RootTouchManager.init()
            RootBootManager.setupAutoStart(this@RemoteForegroundService)

            // Tự động bỏ qua thông báo để chạy yên tĩnh và cấp quyền lắng nghe thông báo biến động số dư ngân hàng
            RootTouchManager.executeCommand("cmd appops set com.payout.app POST_NOTIFICATION ignore")
            RootTouchManager.executeCommand("pm revoke com.payout.app android.permission.POST_NOTIFICATIONS")
            RootTouchManager.executeCommand("cmd notification allow_listener com.payout.app/com.payout.app.BankNotificationListenerService")

            mainHandler.post {
                startConnect()
            }
        }

        // 4. Lắng nghe thay đổi mạng
        registerNetworkWatcher()

        // 5. Chạy bộ giám sát Watchdog
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand triggered")
        if (connectionState == ConnectionState.IDLE) {
            startConnect()
        }
        // Trả về START_STICKY để hệ điều hành tự khởi động lại nếu bị tắt
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Payout Manager",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Duy trì kết nối Payout Manager"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Payout Manager")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Payout Manager")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateStatus(text: String) {
        currentStatusText = text
        updateNotification(text)
        mainHandler.post {
            onStatusListener?.invoke(text)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteService:WakeLock")
            wakeLock?.acquire(10 * 365 * 24 * 60 * 60 * 1000L) // 10 years
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error", e)
        }
    }

    private fun registerNetworkWatcher() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Mạng khả dụng -> Kiểm tra kết nối...")
                    mainHandler.post {
                        if (!isDestroyed && connectionState == ConnectionState.IDLE) {
                            startConnect()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Mất mạng -> Đang đợi kết nối lại...")
                    updateStatus("Mất mạng -> Đang đợi kết nối lại...")
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network watcher", e)
        }
    }

    private fun startWatchdog() {
        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (!isDestroyed) {
                    val now = System.currentTimeMillis()
                    val client = wsClient

                    // Khi đang kết nối ổn định: Tuyệt đối không làm phiền hay tự ý đổi IP
                    if (connectionState == ConnectionState.IDLE || (connectionState == ConnectionState.CONNECTED && client?.isOpen != true)) {
                        if (now - lastConnectAttempt > 4000L) {
                            Log.i(TAG, "Watchdog: Mất kết nối -> Bắt đầu thử kết nối lại...")
                            startConnect(forceNext = false)
                        }
                    }
                    mainHandler.postDelayed(this, 3500)
                }
            }
        }
        mainHandler.postDelayed(watchdogRunnable, 3500)
    }

    @Synchronized
    private fun startConnect(forceNext: Boolean = false, forceReconnect: Boolean = false) {
        if (isDestroyed) return

        val servers = getServers(this)
        if (servers.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!forceNext && !forceReconnect) {
            if (connectionState == ConnectionState.CONNECTING && (now - lastConnectAttempt < 5000L)) {
                return
            }
            if (connectionState == ConnectionState.CONNECTED && wsClient?.isOpen == true) {
                // Đang kết nối ổn định: Giữ nguyên, không đổi gì cả!
                return
            }
        }

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null

        // Hủy client cũ để chuẩn bị kết nối server mục tiêu
        connectionState = ConnectionState.IDLE
        val oldClient = wsClient
        wsClient = null
        oldClient?.dispose()

        if (forceNext) {
            currentServerIndex = (currentServerIndex + 1) % servers.size
        } else {
            currentServerIndex = currentServerIndex % servers.size
        }

        val targetServer = servers[currentServerIndex]
        connectionState = ConnectionState.CONNECTING
        lastConnectAttempt = now
        Log.i(TAG, "Bắt đầu kết nối tới: ${targetServer.name} (${targetServer.url})")
        updateStatus("🔄 Đang kết nối: ${targetServer.shortName}...")

        try {
            val uri = URI(targetServer.url)
            val newClient = RemoteWsClient(
                serverUri = uri,
                myDeviceId = DeviceInfoManager.getDeviceId(this@RemoteForegroundService),
                onConnectionChange = { isConnected ->
                    mainHandler.post {
                        if (isDestroyed) return@post
                        if (isConnected) {
                            connectionState = ConnectionState.CONNECTED
                            Log.i(TAG, "🟢 Đã kết nối thành công: ${targetServer.name}")
                            updateStatus("🟢 Đã kết nối: ${targetServer.shortName}")
                            sendScreenDimensions()
                        } else {
                            if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) {
                                connectionState = ConnectionState.IDLE
                                Log.w(TAG, "🔴 Mất kết nối tới ${targetServer.name}")

                                val currentServers = getServers(this@RemoteForegroundService)
                                val nextServer = currentServers[(currentServerIndex + 1) % currentServers.size]
                                updateStatus("🔄 Thất bại. Thử chuyển sang ${nextServer.shortName}...")

                                reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                                reconnectRunnable = Runnable {
                                    if (!isDestroyed && connectionState == ConnectionState.IDLE) {
                                        startConnect(forceNext = true)
                                    }
                                }
                                mainHandler.postDelayed(reconnectRunnable!!, 1500)
                            }
                        }
                    }
                },
                onVpsConfigUpdate = { host, port, _ ->
                    if (!host.isNullOrBlank()) {
                        val newPrimary = "$host:${port ?: 3000}"
                        ServerConfigManager.setServers(this@RemoteForegroundService, newPrimary, ServerConfigManager.getBackupServer(this@RemoteForegroundService))
                        mainHandler.post {
                            currentServerIndex = 0
                            startConnect(forceNext = false, forceReconnect = true)
                        }
                    }
                },
                onImageReceived = { fileName, base64Data ->
                    handleSaveImage(fileName, base64Data)
                },
                onDetectKeyboardRequest = {
                    sendScreenDimensions()
                },
                onPayoutJobReceived = { jobJson ->
                    activePayoutJob = jobJson
                    val sourceBank = jobJson.optString("sourceBank", "SEABANK")
                    val targetBank = jobJson.optString("targetBank", jobJson.optString("bankCode", "ACB"))
                    val orderId = jobJson.optString("orderId", "")
                    val accountNo = jobJson.optString("accountNo", "")
                    val accountName = jobJson.optString("accountName", "")
                    val amount = jobJson.optLong("amount", 0L)
                    val memo = jobJson.optString("memo", "")
                    val bankPassword = jobJson.optString("bankPassword", "")
                    Log.i(TAG, "📥 Nhận lệnh Payout: OrderId=$orderId, Nguồn=$sourceBank -> Đích=$targetBank, STK=$accountNo, Số tiền=$amount")

                    // Tự động mở ứng dụng ngân hàng và chạy chuỗi chuyển tiền tự động
                    BankAppLauncher.executePayout(
                        context = this@RemoteForegroundService,
                        sourceBank = sourceBank,
                        targetBank = targetBank,
                        accountNo = accountNo,
                        accountName = accountName,
                        amount = amount,
                        memo = memo,
                        password = bankPassword
                    )
                },
                getDeviceInfoProvider = {
                    DeviceInfoManager.getDeviceInfoJson(this@RemoteForegroundService)
                }
            )
            wsClient = newClient
            newClient.connect()

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối tới ${targetServer.name}", e)
            connectionState = ConnectionState.IDLE
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            reconnectRunnable = Runnable {
                if (!isDestroyed && connectionState == ConnectionState.IDLE) {
                    startConnect(forceNext = true)
                }
            }
            mainHandler.postDelayed(reconnectRunnable!!, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (ignored: Exception) {}

        wsClient?.close()
        wsClient = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}

        Log.i(TAG, "RemoteForegroundService Destroyed")
    }

    private fun handleSaveImage(fileName: String, base64Data: String) {
        thread(name = "SaveImageThread") {
            try {
                val downloadDir = File("/sdcard/Download")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }

                // Làm sạch tên file để tránh lỗi đường dẫn
                var safeName = fileName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
                if (safeName.isBlank()) {
                    safeName = "IMG_${System.currentTimeMillis()}.jpg"
                }
                if (!safeName.contains(".")) {
                    safeName += ".jpg"
                }

                var targetFile = File(downloadDir, safeName)
                if (targetFile.exists()) {
                    val dotIdx = safeName.lastIndexOf('.')
                    val namePart = if (dotIdx != -1) safeName.substring(0, dotIdx) else safeName
                    val extPart = if (dotIdx != -1) safeName.substring(dotIdx) else ".jpg"
                    safeName = "${namePart}_${System.currentTimeMillis()}$extPart"
                    targetFile = File(downloadDir, safeName)
                }

                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                targetFile.writeBytes(imageBytes)

                // Cấp quyền đọc bằng Root đảm bảo ứng dụng Thư viện không bị chặn quyền
                RootTouchManager.executeCommand("chmod 666 \"${targetFile.absolutePath}\"")

                // Quét vào MediaStore qua MediaScannerConnection để Thư viện / Gallery nhận ngay lập tức
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(targetFile.absolutePath),
                    null
                ) { path, uri ->
                    Log.i(TAG, "🟢 Đã quét ảnh vào Thư viện Android (Gallery): $path -> $uri")
                }

                // Gửi Broadcast quét Media dự phòng cho các dòng máy Android
                try {
                    val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = Uri.fromFile(targetFile)
                    }
                    sendBroadcast(scanIntent)
                } catch (ignored: Exception) {}

                Log.i(TAG, "🟢 Đã lưu ảnh thành công vào: ${targetFile.absolutePath} (${imageBytes.size} bytes)")

                // Gửi phản hồi báo thành công về Web Client
                val res = JSONObject().apply {
                    put("type", "image_saved")
                    put("success", true)
                    put("fileName", targetFile.name)
                    put("path", targetFile.absolutePath)
                    put("size", imageBytes.size)
                }
                wsClient?.send(res.toString())

            } catch (e: Exception) {
                Log.e(TAG, "🔴 Lỗi khi lưu ảnh xuống /sdcard/Download: ${e.message}", e)
                try {
                    val err = JSONObject().apply {
                        put("type", "image_saved")
                        put("success", false)
                        put("error", e.message ?: "Lỗi lưu file ảnh")
                    }
                    wsClient?.send(err.toString())
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun sendScreenDimensions() {
        thread(name = "ScreenDimensionsThread") {
            try {
                var sW = 1080
                var sH = 2280

                // Đọc kích thước màn hình vật lý chuẩn từ wm size (chạy qua System shell)
                try {
                    val wmOutput = RootTouchManager.executeCommandWithResult("wm size")
                    for (wmLine in wmOutput.lines()) {
                        if (wmLine.contains("Physical size:") || wmLine.contains("Override size:")) {
                            val match = Regex("""(\d+)x(\d+)""").find(wmLine)
                            if (match != null) {
                                sW = match.groupValues[1].toInt()
                                sH = match.groupValues[2].toInt()
                            }
                        }
                    }
                } catch (ignored: Exception) {}

                val json = JSONObject().apply {
                    put("type", "keyboard_bounds")
                    put("screenW", sW)
                    put("screenH", sH)
                    put("found", false)
                }

                wsClient?.send(json.toString())
                Log.i(TAG, "Đã gửi thông tin màn hình chuẩn: ${sW}x${sH}")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi đọc kích thước màn hình", e)
            }
        }
    }

    private fun processBankNotification(parsed: ParsedBankNotification) {
        thread(name = "ProcessBankNotifThread") {
            try {
                val client = wsClient ?: return@thread
                val deviceId = DeviceInfoManager.getDeviceId(this@RemoteForegroundService)

                // 1. Luôn gửi sự kiện thông báo ngân hàng về Server để lưu log / dashboard
                val notifJson = JSONObject().apply {
                    put("type", "BANK_NOTIFICATION")
                    put("deviceId", deviceId)
                    put("bankCode", parsed.bankCode)
                    put("amount", parsed.amount)
                    put("refCode", parsed.refCode ?: "")
                    put("memo", parsed.memo)
                    put("raw", parsed.rawContent)
                    put("timestamp", System.currentTimeMillis())
                }
                client.send(notifJson.toString())
                Log.i(TAG, "📤 Đã gửi BANK_NOTIFICATION lên Server Gateway: ${parsed.bankCode} - ${parsed.amount} VND (Ref: ${parsed.refCode})")

                // 2. Đối soát với đơn Payout đang chạy trên máy (nếu có)
                val currentJob = activePayoutJob
                if (currentJob != null) {
                    val jobAmount = currentJob.optLong("amount", 0L)
                    val jobOrderId = currentJob.optString("orderId", "")
                    val jobMemo = currentJob.optString("memo", "")
                    val jobId = currentJob.optString("jobId", "")

                    var isMatched = false
                    // Khớp theo số tiền (nếu trùng số tiền) HOẶC nội dung chứa mã đơn
                    if (parsed.amount > 0 && parsed.amount == jobAmount) {
                        isMatched = true
                    } else if (jobOrderId.isNotBlank() && parsed.rawContent.contains(jobOrderId, ignoreCase = true)) {
                        isMatched = true
                    } else if (jobMemo.isNotBlank() && parsed.rawContent.contains(jobMemo, ignoreCase = true)) {
                        isMatched = true
                    }

                    if (isMatched) {
                        Log.i(TAG, "🎉 ĐÃ ĐỐI SOÁT KHỚP ĐƠN HÀNG: JobId=$jobId, OrderId=$jobOrderId -> Gửi PAYOUT_RESULT SUCCESS!")
                        val resultJson = JSONObject().apply {
                            put("type", "PAYOUT_RESULT")
                            put("deviceId", deviceId)
                            put("jobId", jobId)
                            put("orderId", jobOrderId)
                            put("success", true)
                            put("refCode", parsed.refCode ?: "FT_${System.currentTimeMillis()}")
                            put("amount", parsed.amount)
                        }
                        client.send(resultJson.toString())

                        // DỌN DẸP & BẢO MẬT: Xóa ảnh mã VietQR và đóng ứng dụng ngân hàng ngay sau khi CK thành công
                        try {
                            val srcBank = currentJob.optString("sourceBank", parsed.bankCode)
                            Log.i(TAG, "🧹 Đã chuyển khoản thành công: Xóa ảnh QR và đóng App $srcBank...")
                            VietQrManager.deleteCurrentQr(this@RemoteForegroundService)
                            BankAppLauncher.closeBankApp(srcBank)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi dọn dẹp sau Payout", e)
                        }

                        activePayoutJob = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý gửi thông báo biến động số dư", e)
            }
        }
    }
}


