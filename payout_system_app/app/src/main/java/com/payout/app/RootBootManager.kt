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
            RootTouchManager.executeCommand("appops set com.payout.app RUN_IN_BACKGROUND allow")
            RootTouchManager.executeCommand("appops set com.payout.app BOOT_COMPLETED allow")
            RootTouchManager.executeCommand("appops set com.payout.app AUTO_START allow")

            // 2. Cài đặt Magisk service.d script để tự khởi động bằng Root khi máy vừa bật nguồn
            val scriptLines = arrayOf(
                "mkdir -p /data/adb/service.d",
                "echo '#!/system/bin/sh' > /data/adb/service.d/payout_autostart.sh",
                "echo 'while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'sleep 4' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'dumpsys deviceidle whitelist +com.payout.app' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'appops set com.payout.app RUN_IN_BACKGROUND allow' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'appops set com.payout.app AUTO_START allow' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'cmd appops set com.payout.app POST_NOTIFICATION ignore' >> /data/adb/service.d/payout_autostart.sh",
                "echo 'am start-foreground-service -n com.payout.app/.RemoteForegroundService' >> /data/adb/service.d/payout_autostart.sh",
                "chmod 755 /data/adb/service.d/payout_autostart.sh"
            )
            for (line in scriptLines) {
                RootTouchManager.executeCommand(line)
            }
            Log.i(TAG, "Đã thiết lập tự khởi động cùng hệ thống qua Root thành công.")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi cài đặt tự khởi động", e)
        }
    }
}

