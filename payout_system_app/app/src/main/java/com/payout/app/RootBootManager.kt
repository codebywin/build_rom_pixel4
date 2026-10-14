package com.payout.app

import android.content.Context
import android.util.Log

/**
 * Quản lý tự khởi động ngầm khi máy bật nguồn bằng quyền Root:
 * 1. Tự cài đặt script vào /data/adb/service.d/ (Magisk / KernelSU).
 * 2. Đưa app vào danh sách trắng Doze mode (dumpsys deviceidle whitelist).
 * 3. Cho phép quyền AppOps: RUN_IN_BACKGROUND, BOOT_COMPLETED, AUTO_START.
 */
object RootBootManager {
    private const val TAG = "RootBootManager"

    fun setupAutoStart(context: Context) {
        try {
            // 1. Cho phép chạy ngầm và bypass tiết kiệm pin
            RootTouchManager.executeCommand("dumpsys deviceidle whitelist +com.payout.app")
            RootTouchManager.executeCommand("cmd appops set com.payout.app RUN_IN_BACKGROUND allow")
            RootTouchManager.executeCommand("cmd appops set com.payout.app BOOT_COMPLETED allow")
            RootTouchManager.executeCommand("cmd appops set com.payout.app AUTO_START allow")
            Log.i(TAG, "Đã thiết lập cấu hình chạy ngầm & whitelist pin cho app thành công.")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi cài đặt tự khởi động", e)
        }
    }
}

