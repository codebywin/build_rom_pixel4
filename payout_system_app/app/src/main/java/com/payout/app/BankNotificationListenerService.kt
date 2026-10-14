package com.payout.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class BankNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "BankNotifListener"
        var isConnected = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "🟢 BankNotificationListenerService đã kết nối và bắt đầu lắng nghe thông báo ngân hàng!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "🔴 BankNotificationListenerService bị ngắt kết nối.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // Chỉ xử lý các ứng dụng ngân hàng hoặc tin nhắn SMS
        if (!BankNotificationParser.BANK_PACKAGES.containsKey(packageName)) {
            return
        }

        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getString(Notification.EXTRA_TITLE)
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

            Log.i(TAG, "📩 Nhận thông báo từ [${packageName}]: Title='$title', Text='$text'")

            val parsed = BankNotificationParser.parse(packageName, title, text)
            if (parsed != null) {
                val dir = if (parsed.isDeduction) "TRỪ TIỀN (DEBIT/PAYOUT)" else "CỘNG TIỀN (CREDIT/PAYIN)"
                Log.i(TAG, "🎯 PHÁT HIỆN BIẾN ĐỘNG $dir: Bank=${parsed.bankCode}, Amount=${parsed.amount}, Ref=${parsed.refCode}, Memo='${parsed.memo}'")

                // Chuyển tiếp tới RemoteForegroundService để bắn WebSocket về Server Gateway
                RemoteForegroundService.handleBankNotification(parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích thông báo ngân hàng", e)
        }
    }
}

