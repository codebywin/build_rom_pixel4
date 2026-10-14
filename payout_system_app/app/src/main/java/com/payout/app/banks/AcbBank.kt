package com.payout.app.banks

import android.content.Context
import android.util.Log
import com.payout.app.BankAppLauncher
import com.payout.app.RemoteForegroundService
import com.payout.app.RootTouchManager

/**
 * Xử lý tự động hóa ngân hàng ACB ONE
 */
object AcbBank : BankHandler {
    private const val TAG = "AcbBank"

    override val bankCode: String = "ACB"
    override val packageNames: List<String> = listOf("mobile.acb.com.vn", "com.acb.mobile")

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
            val err = "Không thể khởi chạy ứng dụng ACB ONE!"
            Log.e(TAG, "🔴 $err")
            RemoteForegroundService.reportPayoutFailure(err)
            return
        }

        kotlin.concurrent.thread(name = "AcbAutoLoginThread") {
            try {
                val appLoadWaitMs = if (isAlreadyOnScreen) BankAppLauncher.WARM_START_WAIT_MS else BankAppLauncher.COLD_START_WAIT_MS
                Log.i(
                    TAG,
                    "⏳ ACB ONE (${if (isAlreadyOnScreen) "ĐÃ MỞ SẴN - Warm Start" else "ĐANG TẮT - Cold Start"}). Chờ ${appLoadWaitMs}ms để sẵn sàng..."
                )
                Thread.sleep(appLoadWaitMs)

                // BƯỚC 1: Chạm nút ĐĂNG NHẬP ban đầu ở màn hình chính
                Log.i(TAG, "👉 [Bước 1] Chạm nút ĐĂNG NHẬP ban đầu tại (556, 1801)")
                RootTouchManager.tapSync(556, 1801)

                if (!isAlreadyOnScreen) {
                    Thread.sleep(1500L)
                    RootTouchManager.tapSync(556, 1801)
                }

                Thread.sleep(1800L)

                // BƯỚC 2: Chạm vào ô nhập mật khẩu
                Log.i(TAG, "👉 [Bước 2] Chạm vào ô nhập mật khẩu tại (320, 964)")
                RootTouchManager.tapSync(320, 964)
                Thread.sleep(350L)
                RootTouchManager.tapSync(320, 964)
                Thread.sleep(600L)

                // BƯỚC 3: Điền mật khẩu nếu có cấu hình từ Server
                if (password.isNotBlank()) {
                    Log.i(TAG, "⌨️ [Bước 3] Tự động điền mật khẩu ACB...")
                    RootTouchManager.typeTextSync(password)
                    Thread.sleep(800L)

                    // BƯỚC 4: Nhấn nút Đăng Nhập xác nhận
                    Log.i(TAG, "👉 [Bước 4] Chạm nút Login xác nhận tại (517, 1170)")
                    RootTouchManager.tapSync(517, 1170)

                    // VƯỢT LỖI XOAY MÀN HÌNH CỦA ACB BẰNG VÒNG ĐỜI ONRESUME
                    Log.i(TAG, "🔄 Chờ 2.5s để ACB gửi request xác thực tới máy chủ ngân hàng...")
                    Thread.sleep(2500L)

                    Log.i(TAG, "📱 Đưa ACB xuống chạy ẩn 1.2s (nhấn phím Home) để kích hoạt thoát khỏi vòng xoay...")
                    RootTouchManager.sendCommandSync("input keyevent 3")
                    Thread.sleep(1200L)

                    Log.i(TAG, "🚀 Mở lại ACB ONE để vào thẳng giao diện tài khoản!")
                    BankAppLauncher.launchBankApp(context, bankCode)

                    Log.i(TAG, "🎉 Đã hoàn tất chuỗi đăng nhập tự động vào ACB ONE!")
                } else {
                    Log.w(TAG, "⚠️ Chưa cấu hình mật khẩu ACB trên Server cho thiết bị này. Dừng ở ô nhập mật khẩu.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong chuỗi đăng nhập tự động ACB", e)
                RemoteForegroundService.reportPayoutFailure("Lỗi tự động hóa ACB: ${e.message}")
            }
        }
    }
}

