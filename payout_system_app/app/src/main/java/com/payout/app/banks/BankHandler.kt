package com.payout.app.banks

import android.content.Context
import com.payout.app.ParsedBankNotification

/**
 * Interface chuẩn cho từng ngân hàng.
 * Mỗi ngân hàng sẽ là một Class độc lập, tự quản lý luồng Payout và cú pháp thông báo của riêng mình.
 */
interface BankHandler {
    /** Mã viết tắt của ngân hàng (ACB, SEABANK, VCB, MB, TCB...) */
    val bankCode: String

    /** Danh sách Package Name của ứng dụng ngân hàng này */
    val packageNames: List<String>

    /**
     * Thực thi chuỗi tự động hóa Payout (mở app, đăng nhập, quét QR hoặc điền STK, chuyển tiền)
     */
    fun executePayout(
        context: Context,
        targetBank: String,
        accountNo: String,
        accountName: String,
        amount: Long,
        memo: String,
        password: String,
        isAlreadyOnScreen: Boolean
    )

    /**
     * Bóc tách thông báo đặc thù của ngân hàng này (tùy chọn).
     * Trả về null nếu muốn dùng parser chung mặc định.
     */
    fun parseNotification(title: String?, content: String?, fullText: String): ParsedBankNotification? {
        return null
    }
}

