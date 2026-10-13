package com.payout.app.banks

import android.content.Context
import android.util.Log
import com.payout.app.BankAppLauncher
import com.payout.app.RootTouchManager

/**
 * Xử lý tự động hóa ngân hàng Techcombank
 */
object Techcombank : BankHandler {
    private const val TAG = "Techcombank"

    override val bankCode: String = "TCB"
    override val packageNames: List<String> = listOf("vn.com.techcombank.bb.app")

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
        if (!launched) return

        kotlin.concurrent.thread(name = "TcbPayoutThread") {
            try {
                val appLoadWaitMs = if (isAlreadyOnScreen) BankAppLauncher.WARM_START_WAIT_MS else BankAppLauncher.COLD_START_WAIT_MS
                Log.i(TAG, "⏳ Chờ ${appLoadWaitMs}ms để Techcombank sẵn sàng...")
                Thread.sleep(appLoadWaitMs)

                // TODO: Bổ sung tọa độ click và luồng thao tác riêng cho Techcombank tại đây
                Log.i(TAG, "👉 Techcombank Payout: Đang sẵn sàng kịch bản tự động...")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong chuỗi Payout Techcombank", e)
            }
        }
    }
}

