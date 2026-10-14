package com.payout.app.banks

import android.content.Context
import android.util.Log
import com.payout.app.BankAppLauncher
import com.payout.app.RemoteForegroundService
import com.payout.app.RootTouchManager

/**
 * Xử lý tự động hóa ngân hàng Vietcombank (VCB Digibank)
 */
object VcbBank : BankHandler {
    private const val TAG = "VcbBank"

    override val bankCode: String = "VCB"
    override val packageNames: List<String> = listOf("com.VCB")

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
            val err = "Không thể khởi chạy ứng dụng VCB Digibank!"
            Log.e(TAG, "🔴 $err")
            RemoteForegroundService.reportPayoutFailure(err)
            return
        }

        kotlin.concurrent.thread(name = "VcbPayoutThread") {
            try {
                val appLoadWaitMs = if (isAlreadyOnScreen) BankAppLauncher.WARM_START_WAIT_MS else BankAppLauncher.COLD_START_WAIT_MS
                Log.i(TAG, "⏳ Chờ ${appLoadWaitMs}ms để VCB Digibank sẵn sàng...")
                Thread.sleep(appLoadWaitMs)

                // TODO: Bổ sung tọa độ click và luồng thao tác riêng cho VCB tại đây
                Log.i(TAG, "👉 VCB Payout: Đang sẵn sàng kịch bản tự động...")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong chuỗi Payout VCB", e)
                RemoteForegroundService.reportPayoutFailure("Lỗi tự động hóa VCB: ${e.message}")
            }
        }
    }
}

