package com.payout.app

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteWsClient(
    serverUri: URI,
    private val myDeviceId: String,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onVpsConfigUpdate: ((String?, Int?, String?) -> Unit)? = null,
    private val onImageReceived: ((String, String) -> Unit)? = null,
    private val onDetectKeyboardRequest: (() -> Unit)? = null,
    private val onPayoutJobReceived: ((JSONObject) -> Unit)? = null,
    private val getDeviceInfoProvider: (() -> JSONObject)? = null
) : WebSocketClient(
    serverUri,
    mapOf(
        "Origin" to if (serverUri.scheme?.equals("wss", ignoreCase = true) == true) {
            AppConfig.NGROK_ORIGIN
        } else {
            "http://${serverUri.host}:${if (serverUri.port > 0) serverUri.port else 80}"
        }
    )
) {

    companion object {
        private const val TAG = "RemoteWsClient"
    }

    @Volatile private var isConnected = false
    @Volatile var isDisposed = false

    fun dispose() {
        isDisposed = true
        isConnected = false
        try {
            close()
        } catch (ignored: Exception) {}
    }

    init {
        isTcpNoDelay = true
        connectionLostTimeout = 5

        if (serverUri.scheme?.equals("wss", ignoreCase = true) == true) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                setSocketFactory(sslContext.socketFactory)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SSL Socket Factory", e)
            }
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.i(TAG, "Connected successfully to: $uri (DeviceId: $myDeviceId)")
        isConnected = true
        onConnectionChange(true)
        try {
            val info = getDeviceInfoProvider?.invoke()
            if (info != null && isOpen) {
                send(JSONObject().apply {
                    put("type", "device_info")
                    put("info", info)
                }.toString())
                Log.i(TAG, "Sent device_info to server: ${info.optString("id")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device_info onOpen", e)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.w(TAG, "Disconnected: $reason (code: $code, remote: $remote)")
        isConnected = false
        onConnectionChange(false)
    }

    override fun onMessage(message: String?) {
        if (isDisposed || message == null) return

        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            // Kiểm tra xem lệnh này có dành cho máy này không (hoặc là Broadcast tới tất cả)
            val targetId = json.optString("targetDeviceId", "")
            val isBroadcast = json.optBoolean("broadcast", false) || targetId == "ALL" || targetId.isEmpty()
            if (!isBroadcast && targetId != myDeviceId) {
                return
            }

            when (type) {
                "tap" -> RootTouchManager.tap(json.getInt("x"), json.getInt("y"))
                "swipe" -> RootTouchManager.swipe(
                    json.getInt("x1"), json.getInt("y1"),
                    json.getInt("x2"), json.getInt("y2"),
                    json.optInt("duration", 200)
                )
                "key" -> RootTouchManager.keyEvent(json.getInt("code"))
                "text" -> RootTouchManager.typeText(json.getString("text"))
                "ping" -> {
                    if (isOpen) {
                        send(JSONObject().apply {
                            put("type", "pong")
                            put("deviceId", myDeviceId)
                            put("time", System.currentTimeMillis())
                        }.toString())
                    }
                }
                "request_device_info" -> {
                    val info = getDeviceInfoProvider?.invoke()
                    if (info != null && isOpen) {
                        send(JSONObject().apply {
                            put("type", "device_info")
                            put("info", info)
                        }.toString())
                    }
                }
                "update_vps_config" -> {
                    val host = json.optString("host", "").takeIf { it.isNotBlank() }
                    val port = if (json.has("port")) json.getInt("port") else null
                    val cfUrl = json.optString("cloudflare_url", "").takeIf { it.isNotBlank() }
                    onVpsConfigUpdate?.invoke(host, port, cfUrl)
                }
                "sync_cloudflare" -> {
                    val cfUrl = json.optString("cloudflare_url", "").takeIf { it.isNotBlank() }
                    onVpsConfigUpdate?.invoke(null, null, cfUrl)
                }
                "send_image" -> {
                    val fileName = json.optString("fileName", "IMG_${System.currentTimeMillis()}.jpg")
                    val base64Data = json.optString("data", "")
                    if (base64Data.isNotBlank()) {
                        onImageReceived?.invoke(fileName, base64Data)
                    }
                }
                "EXECUTE_PAYOUT", "execute_payout" -> {
                    onPayoutJobReceived?.invoke(json)
                }
                "detect_keyboard" -> {
                    onDetectKeyboardRequest?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing touch event", e)
        }
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "WebSocket Client Error: ${ex?.message}", ex)
        if (!isOpen && !isConnected) {
            onConnectionChange(false)
        }
    }
}

