package com.payout.app.banks

import android.content.Context
import android.util.Log
import com.payout.app.BankAppLauncher
import com.payout.app.RemoteForegroundService
import com.payout.app.RootTouchManager
import com.payout.app.VietQrManager
import java.io.File

/**
 * Xử lý tự động hóa ngân hàng SeABank (SeAMobile)
 * Hỗ trợ quét mã VietQR từ album và nhập thủ công dự phòng.
 */
object SeabankBank : BankHandler {
    private const val TAG = "SeabankBank"

    override val bankCode: String = "SEABANK"
    override val packageNames: List<String> = listOf("vn.com.seabank.mb1", "vn.com.seabank.mobile")

    private fun enterSoftOtpPin(pin: String = "1122") {
        for (char in pin) {
            when (char) {
                '1' -> RootTouchManager.tapSync(200, 1600)
                '2' -> RootTouchManager.tapSync(540, 1600)
                '3' -> RootTouchManager.tapSync(880, 1600)
                '4' -> RootTouchManager.tapSync(200, 1780)
                '5' -> RootTouchManager.tapSync(540, 1780)
                '6' -> RootTouchManager.tapSync(880, 1780)
                '7' -> RootTouchManager.tapSync(200, 1960)
                '8' -> RootTouchManager.tapSync(540, 1960)
                '9' -> RootTouchManager.tapSync(880, 1960)
                '0' -> RootTouchManager.tapSync(540, 2140)
            }
            Thread.sleep(350L)
        }
    }

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
        kotlin.concurrent.thread(name = "SeabankPayoutThread") {
            var qrFile: File? = null
            try {
                // BƯỚC 0: TỰ ĐỘNG TẢI MÃ VIETQR TRƯỚC (NẾU CÓ THÔNG TIN TÀI KHOẢN ĐÍCH)
                if (accountNo.isNotBlank()) {
                    Log.i(TAG, "📥 [Bước 0] Đang tải ảnh mã VietQR Napas trước khi mở App SeABank...")
                    qrFile = VietQrManager.downloadAndSaveQr(
                        context = context,
                        bankCode = targetBank,
                        accountNo = accountNo,
                        accountName = accountName,
                        amount = amount,
                        memo = memo
                    )

                    if (qrFile == null || !qrFile.exists()) {
                        val err = "Không thể tải mã VietQR Napas! Hủy mở App ngân hàng."
                        Log.e(TAG, "🔴 LỖI: $err")
                        RemoteForegroundService.reportPayoutFailure(err)
                        return@thread
                    }
                    Log.i(TAG, "🟢 [Bước 0] Đã tải ảnh VietQR thành công: ${qrFile.absolutePath} (${qrFile.length()} bytes)")
                }

                // BƯỚC 1: KÍCH HOẠT MỞ SEABANK
                Log.i(TAG, "🚀 [Bước 1] Kích hoạt mở SeABank...")
                val launched = BankAppLauncher.launchBankApp(context, bankCode)
                if (!launched) {
                    val err = "Không thể khởi chạy ứng dụng SeABank!"
                    Log.e(TAG, "🔴 $err")
                    RemoteForegroundService.reportPayoutFailure(err)
                    return@thread
                }

                val appLoadWaitMs = if (isAlreadyOnScreen) BankAppLauncher.WARM_START_WAIT_MS else BankAppLauncher.COLD_START_WAIT_MS
                Log.i(
                    TAG,
                    "⏳ Chờ ${appLoadWaitMs}ms để SeABank sẵn sàng (${if (isAlreadyOnScreen) "Warm Start" else "Cold Start"})..."
                )
                Thread.sleep(appLoadWaitMs)

                // BƯỚC 2: Chạm vào ô nhập mật khẩu SeABank (Tọa độ X=453, Y=1250)
                Log.i(TAG, "👉 [Bước 2] Chạm vào ô nhập mật khẩu SEABANK tại (453, 1250)...")
                RootTouchManager.tapSync(453, 1250)
                Thread.sleep(350L)
                RootTouchManager.tapSync(453, 1250)
                Thread.sleep(600L)

                // BƯỚC 3: Điền mật khẩu và Đăng nhập
                if (password.isNotBlank()) {
                    Log.i(TAG, "⌨️ [Bước 3] Tự động điền mật khẩu SEABANK...")
                    RootTouchManager.typeTextSync(password)
                    Thread.sleep(800L)

                    // BƯỚC 4: Ẩn bàn phím và chạm nút Đăng nhập màu đỏ tại (540, 1500)
                    Log.i(TAG, "👉 [Bước 4] Ẩn bàn phím và chạm nút Đăng nhập tại (540, 1500)...")
                    RootTouchManager.sendCommandSync("input keyevent 4")
                    Thread.sleep(400L)
                    RootTouchManager.tapSync(540, 1500)
                    Thread.sleep(250L)
                    RootTouchManager.sendCommandSync("input keyevent 66")

                    Log.i(TAG, "⏳ Chờ 3.5s để vào màn hình chính Dashboard...")
                    Thread.sleep(3500L)
                } else {
                    Log.w(TAG, "⚠️ Chưa cấu hình mật khẩu SEABANK trên Server.")
                }

                // BƯỚC 5: NẾU CÓ ẢNH VIETQR -> QUÉT QR TỪ THƯ VIỆN ẢNH (ALBUM)
                if (qrFile != null && qrFile.exists()) {
                    Log.i(TAG, "📸 [Bước 5] Mở tính năng Quét mã QR trên màn hình chính tại (880, 1400)...")
                    RootTouchManager.tapSync(880, 1400)
                    Thread.sleep(2000L)

                    Log.i(TAG, "🖼️ [Bước 6] Chạm nút 'Chọn ảnh từ Thư viện/Album' tại (608, 2080)...")
                    RootTouchManager.tapSync(608, 2080)
                    Thread.sleep(1800L)

                    Log.i(TAG, "👆 [Bước 7] Chạm chọn ảnh VietQR mới nhất trong Thư viện (ô đầu tiên) tại (236, 1057)...")
                    RootTouchManager.tapSync(236, 1057)
                    // Chờ 4.5s để SeABank quét xong mã và gọi Napas tải xong tên chủ TK 100%
                    Thread.sleep(4500L)

                    Log.i(TAG, "👉 [Bước 8] SeABank đã đọc mã VietQR. Bấm Tiếp tục tại (683, 2104)...")
                    RootTouchManager.tapSync(544, 2030)
                    Thread.sleep(1500L)
                    RootTouchManager.tapSync(544, 2030)
                    Thread.sleep(3000L)

                    Log.i(TAG, "👉 [Bước 9] Tiếp tục tại (544, 2030)...")
                    RootTouchManager.tapSync(544, 2030)
                    Thread.sleep(3000L)

                    Log.i(TAG, "👉 [Bước 10] Tiếp tục tại (544, 2030)...")
                    RootTouchManager.tapSync(544, 2030)
                    Thread.sleep(3000L)

                    Log.i(TAG, "👉 [Bước 11] Chạm vào ô nhập PIN Soft OTP tại (315, 1039)...")
                    RootTouchManager.tapSync(315, 1039)
                    Thread.sleep(800L)

                    Log.i(TAG, "👉 [Bước 11] Tự động gõ mã PIN Soft OTP (1122)...")
                    enterSoftOtpPin("1122")
                    Thread.sleep(2500L)

                    Log.i(TAG, "👉 [Bước 12] Chạm nút đỏ 'Tiếp tục' xác nhận chuyển tiền tại (540, 2010)...")
                    RootTouchManager.tapSync(540, 2010)
                    Thread.sleep(3500L)

                    Log.i(TAG, "🎉 Giao dịch thành công! Chờ 3s trước khi ẩn app xuống...")
                    Thread.sleep(3000L)

                    Log.i(TAG, "👉 [Bước 13] Chạm 'Giao dịch khác' tại (760, 2035) để reset trạng thái...")
                    RootTouchManager.tapSync(760, 2035)
                    Thread.sleep(800L)

                    Log.i(TAG, "🏠 Ẩn ứng dụng xuống màn hình chính (Home)...")
                    RootTouchManager.sendCommandSync("input keyevent 3")
                    Thread.sleep(500L)

                    Log.i(TAG, "🎉 Đã hoàn tất chuỗi quét VietQR chuyển tiền tự động trên SEABANK!")
                } else if (accountNo.isNotBlank()) {
                    // KỊCH BẢN DỰ PHÒNG: TỰ NHẬP BẰNG TAY NẾU KHÔNG DÙNG VIETQR
                    Log.i(TAG, "👉 [Thủ công] Chạm nút Chuyển tiền tại (158, 1390)")
                    RootTouchManager.tapSync(158, 1390)
                    Thread.sleep(1500L)

                    RootTouchManager.tapSync(412, 388)
                    Thread.sleep(1500L)

                    RootTouchManager.tapSync(503, 601)
                    Thread.sleep(1200L)

                    RootTouchManager.tapSync(488, 847)
                    Thread.sleep(1000L)

                    val searchBankKey = if (targetBank.isNotBlank()) targetBank.uppercase().trim() else "ACB"
                    RootTouchManager.tapSync(505, 190)
                    Thread.sleep(350L)
                    RootTouchManager.typeTextSync(searchBankKey)
                    Thread.sleep(1000L)

                    RootTouchManager.sendCommandSync("input keyevent 111")
                    Thread.sleep(500L)
                    RootTouchManager.tapSync(500, 360)
                    Thread.sleep(1500L)

                    RootTouchManager.tapSync(470, 680)
                    Thread.sleep(400L)
                    RootTouchManager.typeTextSync(accountNo)
                    Thread.sleep(1800L)

                    if (amount > 0L) {
                        RootTouchManager.tapSync(470, 880)
                        Thread.sleep(400L)
                        RootTouchManager.typeTextSync(amount.toString())
                        Thread.sleep(800L)
                    }

                    if (memo.isNotBlank()) {
                        val cleanMemo = BankAppLauncher.removeVietnameseAccents(memo)
                        RootTouchManager.tapSync(470, 1100)
                        Thread.sleep(400L)
                        RootTouchManager.typeTextSync(cleanMemo)
                        Thread.sleep(800L)
                    }

                    RootTouchManager.tapSync(540, 2050)
                    Thread.sleep(1500L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong chuỗi chuyển tiền tự động SEABANK", e)
                RemoteForegroundService.reportPayoutFailure("Lỗi tự động hóa SeABank: ${e.message}")
            } finally {
                // DỌN DẸP: Xóa ảnh mã VietQR ngay sau khi quét xong để không làm rác máy
                if (qrFile != null) {
                    try {
                        VietQrManager.deleteQrImage(context, qrFile)
                    } catch (ignored: Exception) {}
                }
            }
        }
    }
}

