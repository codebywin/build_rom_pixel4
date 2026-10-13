package com.payout.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.URLEncoder

/**
 * Quản lý cấu hình Máy chủ (Chính & Dự phòng) cho thiết bị Payout Manager.
 * Lưu trữ bằng SharedPreferences, hỗ trợ nhập định dạng IP, IP:Port hoặc Domain.
 */
object ServerConfigManager {
    private const val TAG = "ServerConfigManager"
    private const val PREFS_NAME = "server_config"
    private const val KEY_PRIMARY_SERVER = "cfg_primary_server"
    private const val KEY_BACKUP_SERVER = "cfg_backup_server"

    const val DEFAULT_PRIMARY_SERVER = "103.182.19.35:3000"

    fun getPrimaryServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_PRIMARY_SERVER, null)
        return if (!value.isNullOrBlank()) value else DEFAULT_PRIMARY_SERVER
    }

    fun getBackupServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BACKUP_SERVER, "") ?: ""
    }

    fun setServers(context: Context, primary: String, backup: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PRIMARY_SERVER, primary.trim())
            .putString(KEY_BACKUP_SERVER, backup.trim())
            .apply()
        Log.i(TAG, "Đã lưu cấu hình máy chủ: Chính=$primary | Dự phòng=$backup")
    }

    /**
     * Chuẩn hóa địa chỉ do người dùng nhập thành WebSocket URL hoàn chỉnh
     * Ví dụ:
     *   "192.168.1.15" -> "ws://192.168.1.15:3000?role=phone&..."
     *   "192.168.1.15:8080" -> "ws://192.168.1.15:8080?role=phone&..."
     *   "wss://myserver.com" -> "wss://myserver.com?role=phone&..."
     */
    fun buildWsUrl(rawInput: String, context: Context): String {
        var trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""

        var scheme = "ws"
        if (trimmed.startsWith("wss://", ignoreCase = true)) {
            scheme = "wss"
            trimmed = trimmed.substring(6)
        } else if (trimmed.startsWith("ws://", ignoreCase = true)) {
            scheme = "ws"
            trimmed = trimmed.substring(5)
        } else if (trimmed.startsWith("https://", ignoreCase = true)) {
            scheme = "wss"
            trimmed = trimmed.substring(8)
        } else if (trimmed.startsWith("http://", ignoreCase = true)) {
            scheme = "ws"
            trimmed = trimmed.substring(7)
        }

        // Tách path hoặc query nếu người dùng lỡ dán URL dài
        val cleanHostAndPort = trimmed.split("/")[0].split("?")[0].trim()

        val finalHostAndPort = if (cleanHostAndPort.contains(":")) {
            cleanHostAndPort
        } else {
            // Không có port -> mặc định thêm port 3000
            "$cleanHostAndPort:3000"
        }

        val deviceId = DeviceInfoManager.getDeviceId(context)
        val deviceName = try {
            URLEncoder.encode(DeviceInfoManager.getDeviceName(context), "UTF-8")
        } catch (e: Exception) {
            "Phone"
        }
        val model = try {
            URLEncoder.encode(Build.MODEL, "UTF-8")
        } catch (e: Exception) {
            "Android"
        }

        return "$scheme://$finalHostAndPort?role=phone&deviceId=$deviceId&name=$deviceName&model=$model"
    }
}

