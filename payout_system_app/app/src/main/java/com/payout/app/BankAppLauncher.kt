package com.payout.app

import android.content.Context
import android.util.Log
import com.payout.app.banks.BankRegistry

/**
 * Bộ khởi chạy và điều phối ứng dụng ngân hàng.
 * Phân phối lệnh Payout tới từng Class BankHandler tương ứng.
 */
object BankAppLauncher {

    private const val TAG = "BankAppLauncher"

    val BANK_APP_PACKAGES: Map<String, List<String>>
        get() = BankRegistry.getAllBankPackages()

    // Cấu hình thời gian chờ thích ứng:
    // - WARM_START_WAIT_MS: Thời gian chờ khi App ĐÃ MỞ SẴN trên màn hình (1.8s)
    // - COLD_START_WAIT_MS: Thời gian chờ khi App ĐANG TẮT (11.5s để load xong Splash Screen & Security)
    const val WARM_START_WAIT_MS = 1800L
    const val COLD_START_WAIT_MS = 11500L

    /**
     * Kiểm tra xem một ứng dụng có đang hiển thị trực tiếp trên màn hình hay không
     */
    fun isAppForeground(pkg: String): Boolean {
        return try {
            val focus = RootTouchManager.executeCommandWithResult(
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
                timeoutMs = 3000L
            )
            focus.contains(pkg)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bỏ dấu tiếng Việt để gõ vào input Android shell không bị lỗi ký tự
     */
    fun removeVietnameseAccents(str: String): String {
        val n = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(n).replaceAll("").replace('đ', 'd').replace('Đ', 'D')
    }

    /**
     * Tương thích ngược: Mở ứng dụng ngân hàng và tự động đăng nhập
     */
    fun launchAndAutoLogin(context: Context, bankCode: String, password: String) {
        executePayout(
            context = context,
            sourceBank = bankCode,
            targetBank = "ACB",
            accountNo = "",
            accountName = "",
            amount = 0L,
            memo = "",
            password = password
        )
    }

    /**
     * Thực thi lệnh Payout: Tự động điều hướng đến Handler riêng của từng ngân hàng
     */
    fun executePayout(
        context: Context,
        sourceBank: String,
        targetBank: String,
        accountNo: String,
        accountName: String,
        amount: Long,
        memo: String,
        password: String
    ) {
        val handler = BankRegistry.getHandler(sourceBank)
        if (handler == null) {
            Log.e(TAG, "🔴 Hệ thống chưa hỗ trợ tự động hóa Payout cho ngân hàng: $sourceBank")
            return
        }

        val primaryPkg = handler.packageNames.firstOrNull() ?: ""
        val isAlreadyOnScreen = if (primaryPkg.isNotBlank()) isAppForeground(primaryPkg) else false

        Log.i(TAG, "🚀 Bắt đầu Payout [${handler.bankCode}]: Nguồn=$sourceBank -> Đích=$targetBank, STK=$accountNo, Số tiền=$amount (OnScreen: $isAlreadyOnScreen)")
        handler.executePayout(
            context = context,
            targetBank = targetBank,
            accountNo = accountNo,
            accountName = accountName,
            amount = amount,
            memo = memo,
            password = password,
            isAlreadyOnScreen = isAlreadyOnScreen
        )
    }

    /**
     * Đóng hoàn toàn ứng dụng ngân hàng bằng lệnh force-stop sau khi hoàn tất giao dịch
     */
    fun closeBankApp(bankCode: String) {
        val upper = bankCode.uppercase().trim()
        val packages = BankRegistry.getPackagesForBank(upper)
        for (pkg in packages) {
            try {
                RootTouchManager.sendCommandSync("am force-stop $pkg")
                Log.i(TAG, "🛑 Đã đóng ứng dụng ngân hàng ($upper): $pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi đóng app $pkg", e)
            }
        }
    }

    /**
     * Mở ứng dụng ngân hàng
     */
    fun launchBankApp(context: Context, bankCode: String): Boolean {
        val upper = bankCode.uppercase().trim()
        val packages = BankRegistry.getPackagesForBank(upper)

        for (pkg in packages) {
            // 1. Thử lấy ComponentName Activity chính của ngân hàng và mở bằng am start
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                val component = launchIntent?.component?.flattenToString()
                if (!component.isNullOrBlank()) {
                    RootTouchManager.sendCommandSync("am start -n $component -f 0x10000000")
                    Log.i(TAG, "🟢 Đã mở App ngân hàng $upper ($pkg) qua am start: $component")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không lấy được component từ PackageManager cho $pkg", e)
            }

            // 2. Fallback monkey launcher nếu không biết ComponentName
            try {
                RootTouchManager.sendCommandSync("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                Log.i(TAG, "🟢 Đã mở App ngân hàng $upper ($pkg) bằng monkey launcher!")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi mở app $pkg qua monkey", e)
            }
        }
        return false
    }
}
