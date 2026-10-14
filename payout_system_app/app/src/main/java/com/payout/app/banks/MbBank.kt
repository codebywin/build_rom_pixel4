package com.payout.app.banks

import android.content.Context
import android.util.Log
import com.payout.app.BankAppLauncher
import com.payout.app.RemoteForegroundService
import com.payout.app.RootTouchManager

/**
 * Xử lý tự động hóa ngân hàng MBBank
 */
object MbBank : BankHandler {
    private const val TAG = "MbBank"

    override val bankCode: String = "MB"
    override val packageNames: List<String> = listOf("com.mbmobile")

    override fun executePayout(
        context: Context,
        targetBank: String,
        accountNo: String,
        accountName: String,
        amount: Long,
        memo: String,
        password: String,
        isAlreadyOnScreen: Boolean
    ) {
        val launched = BankAppLauncher.launchBankApp(context, bankCode)
        if (!launched) {
            val err = "Không thể khởi chạy ứng dụng MBBank!"
            Log.e(TAG, "🔴 $err")
            RemoteForegroundService.reportPayoutFailure(err)
            return
        }

        kotlin.concurrent.thread(name = "MbPayoutThread") {
            try {
                val appLoadWaitMs = if (isAlreadyOnScreen) BankAppLauncher.WARM_START_WAIT_MS else BankAppLauncher.COLD_START_WAIT_MS
                Log.i(TAG, "⏳ Chờ ${appLoadWaitMs}ms để MBBank sẵn sàng...")
                Thread.sleep(appLoadWaitMs)

                // TODO: Bổ sung tọa độ click và luồng thao tác riêng cho MBBank tại đây
                Log.i(TAG, "👉 MBBank Payout: Đang sẵn sàng kịch bản tự động...")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong chuỗi Payout MBBank", e)
                RemoteForegroundService.reportPayoutFailure("Lỗi tự động hóa MBBank: ${e.message}")
            }
        }
    }
}

