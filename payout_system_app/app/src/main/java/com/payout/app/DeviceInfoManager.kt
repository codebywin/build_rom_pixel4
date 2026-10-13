package com.payout.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

/**
 * Quản lý thông tin định danh thiết bị cho hệ thống đa máy (Multi-Device).
 * Sinh mã Device ID duy nhất, bền vững và theo dõi trạng thái pin/model máy.
 */
object DeviceInfoManager {
    private const val PREFS_NAME = "device_identity_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }

            val cleanBrand = Build.MANUFACTURER.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
            val cleanModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
            val suffix = if (!androidId.isNullOrBlank()) {
                androidId.takeLast(6).lowercase()
            } else {
                UUID.randomUUID().toString().take(6)
            }

            id = "dev_${cleanBrand}_${cleanModel}_${suffix}"
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var name = prefs.getString(KEY_DEVICE_NAME, null)
        if (name.isNullOrBlank()) {
            name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
        }
        return name
    }

    fun setDeviceName(context: Context, newName: String) {
        if (newName.isNotBlank()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_DEVICE_NAME, newName.trim()).apply()
        }
    }

    fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                    plugged == BatteryManager.BATTERY_PLUGGED_AC
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            Pair(percent, isCharging)
        } catch (e: Exception) {
            Pair(100, false)
        }
    }

    fun getDeviceInfoJson(context: Context): JSONObject {
        val (battery, isCharging) = getBatteryInfo(context)
        return JSONObject().apply {
            put("id", getDeviceId(context))
            put("name", getDeviceName(context))
            put("model", Build.MODEL)
            put("brand", Build.MANUFACTURER)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("battery", battery)
            put("isCharging", isCharging)
        }
    }
}

