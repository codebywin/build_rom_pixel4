package com.payout.app

import android.util.Log
import com.payout.app.banks.BankRegistry
import java.util.regex.Pattern

data class ParsedBankNotification(
    val bankCode: String,
    val isDeduction: Boolean, // true nếu là biến động giảm (trừ tiền rút)
    val amount: Long,         // Số tiền biến động
    val balance: Long?,       // Số dư còn lại nếu có
    val memo: String,         // Nội dung chuyển khoản (chứa mã đơn OrderId)
    val refCode: String?,     // Mã giao dịch / Mã tham chiếu (FT...)
    val rawContent: String
)

object BankNotificationParser {

    private const val TAG = "BankNotifParser"

    val BANK_PACKAGES: Map<String, String> get() {
        val map = mutableMapOf<String, String>()
        for ((code, pkgs) in BankRegistry.getAllBankPackages()) {
            for (p in pkgs) map[p] = code
        }
        map["com.google.android.apps.messaging"] = "SMS"
        map["com.android.mms"] = "SMS"
        return map
    }

    fun parse(packageName: String, title: String?, content: String?): ParsedBankNotification? {
        val fullText = "${title ?: ""} ${content ?: ""}".trim()
        if (fullText.isBlank()) return null

        // 1. Kiểm tra xem ngân hàng này có bộ Parser riêng biệt trong BankHandler không
        val handler = BankRegistry.getHandlerByPackage(packageName)
        val customParsed = handler?.parseNotification(title, content, fullText)
        if (customParsed != null) {
            Log.d(TAG, "Đã parse thành công thông báo qua Handler riêng của ${handler.bankCode}")
            return customParsed
        }

        val bankCode = handler?.bankCode ?: when {
            packageName.contains("messaging", ignoreCase = true) || packageName.contains("mms", ignoreCase = true) -> "SMS"
            packageName.contains("VCB", ignoreCase = true) -> "VCB"
            packageName.contains("mbmobile", ignoreCase = true) -> "MB"
            packageName.contains("techcombank", ignoreCase = true) -> "TCB"
            packageName.contains("bidv", ignoreCase = true) -> "BIDV"
            packageName.contains("vpb", ignoreCase = true) -> "VPB"
            packageName.contains("vietinbank", ignoreCase = true) -> "CTG"
            packageName.contains("acb", ignoreCase = true) -> "ACB"
            packageName.contains("seabank", ignoreCase = true) -> "SEABANK"
            else -> "UNKNOWN_BANK"
        }

        // 2. Kiểm tra xem có phải là biến động trừ tiền (Payout thành công) không
        val isDeduction = fullText.contains("-") ||
                fullText.contains("tru", ignoreCase = true) ||
                fullText.contains("trừ", ignoreCase = true) ||
                fullText.contains("giam", ignoreCase = true) ||
                fullText.contains("giảm", ignoreCase = true) ||
                fullText.contains("chuyen tien", ignoreCase = true) ||
                fullText.contains("chuyển tiền", ignoreCase = true) ||
                fullText.contains("thanh toan", ignoreCase = true) ||
                fullText.contains("thanh toán", ignoreCase = true)

        if (!isDeduction) {
            // Không phải thông báo trừ tiền, bỏ qua
            return null
        }

        // 3. Bóc tách số tiền bị trừ (Ví dụ: -500,000 VND hoặc -500.000d)
        var amount: Long = 0
        try {
            val amountPattern = Pattern.compile("""[-−]\s*([0-9.,]+)\s*(?:VND|vnd|đ|d|dong|đồng)?""")
            val matcher = amountPattern.matcher(fullText)
            if (matcher.find()) {
                val rawAmountStr = matcher.group(1)?.replace(".", "")?.replace(",", "")?.replace(" ", "") ?: ""
                amount = rawAmountStr.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi bóc tách số tiền", e)
        }

        // 4. Bóc tách mã giao dịch / Mã tham chiếu FT (Ví dụ: FT26091800987, GD123456, Ref: ...)
        var refCode: String? = null
        try {
            val refPattern = Pattern.compile("""(?i)(?:FT[0-9A-Z]{8,}|(?:GD|Ref|Trace|Ma GD|Mã GD|So GD|Số GD|TransId)[:\s]*([0-9A-Za-z]+))""")
            val matcher = refPattern.matcher(fullText)
            if (matcher.find()) {
                refCode = matcher.group(1) ?: matcher.group(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi bóc tách mã giao dịch", e)
        }

        // 5. Bóc tách nội dung giao dịch (Memo / OrderId)
        var memo = ""
        try {
            val memoPattern = Pattern.compile("""(?i)(?:ND|Noi dung|Nội dung|ND CK|Ly do|Lý do)[:\s]*(.*?)(?:[\.;\n]|SD|Số dư|So du|Ref|$)""")
            val matcher = memoPattern.matcher(fullText)
            if (matcher.find()) {
                memo = matcher.group(1)?.trim() ?: ""
            } else {
                memo = fullText
            }
        } catch (e: Exception) {
            memo = fullText
        }

        return ParsedBankNotification(
            bankCode = bankCode,
            isDeduction = isDeduction,
            amount = amount,
            balance = null,
            memo = memo,
            refCode = refCode,
            rawContent = fullText
        )
    }
}
