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

        // 2. Xác định chiều biến động: CỘNG TIỀN (PayIn) hay TRỪ TIỀN (PayOut)
        // Dấu hiệu nhận biết trừ tiền: -, tru, trừ, giam, giảm, chuyen di, chuyển đi, thanh toan, thanh toán
        // Dấu hiệu nhận biết cộng tiền: +, cong, cộng, tang, tăng, nhan duoc, nhận được, nhan tien, nhận tiền, chuyen den, chuyển đến, nap tien, nạp tiền
        var isDeduction: Boolean? = null

        // 2a. Thử bóc tách số tiền có dấu (+ / -) trước vì đây là dấu hiệu chính xác nhất
        var amount: Long = 0
        try {
            // Khớp các mẫu: +500,000VND, -500.000d, + 1,000,000 VND, - 250.000 đ
            val signedAmountPattern = Pattern.compile("""([+﹢\-−])\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]+)\s*(?:VND|vnd|đ|d|dong|đồng)?""")
            val matcher = signedAmountPattern.matcher(fullText)
            if (matcher.find()) {
                val sign = matcher.group(1) ?: ""
                val rawAmountStr = matcher.group(2)?.replace(".", "")?.replace(",", "")?.replace(" ", "") ?: ""
                amount = rawAmountStr.toLongOrNull() ?: 0L
                isDeduction = (sign == "-" || sign == "−")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi bóc tách số tiền có dấu", e)
        }

        // 2b. Nếu chưa bắt được qua dấu (+/-), tìm theo mẫu số tiền thông thường và từ khóa
        if (amount == 0L) {
            try {
                // Khớp số tiền sau các từ khóa như GD, GD:, So tien:, Số tiền: hoặc đứng độc lập kèm đơn vị tiền VND
                val genericAmountPattern = Pattern.compile("""(?i)(?:GD|So tien|Số tiền|Amount|So tien GD|Số tiền GD)?[:\s]*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,})\s*(?:VND|vnd|đ|d|dong|đồng)""")
                val matcher = genericAmountPattern.matcher(fullText)
                if (matcher.find()) {
                    val rawAmountStr = matcher.group(1)?.replace(".", "")?.replace(",", "")?.replace(" ", "") ?: ""
                    amount = rawAmountStr.toLongOrNull() ?: 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi bóc tách số tiền chung", e)
            }
        }

        if (amount <= 0L) {
            // Không trích xuất được số tiền hợp lệ -> Bỏ qua thông báo rác
            return null
        }

        // 2c. Xác định chiều nếu chưa rõ từ dấu:
        if (isDeduction == null) {
            val lower = fullText.lowercase()
            val hasCreditKeyword = lower.contains("+") ||
                    lower.contains("nhan duoc") || lower.contains("nhận được") ||
                    lower.contains("nhan tu") || lower.contains("nhận từ") ||
                    lower.contains("cong tien") || lower.contains("cộng tiền") ||
                    lower.contains("cộng:") || lower.contains("cong:") ||
                    lower.contains("tang:") || lower.contains("tăng:") ||
                    lower.contains("nap tien") || lower.contains("nạp tiền") ||
                    lower.contains("chuyen den") || lower.contains("chuyển đến")

            val hasDebitKeyword = lower.contains("-") ||
                    lower.contains("tru") || lower.contains("trừ") ||
                    lower.contains("giam") || lower.contains("giảm") ||
                    lower.contains("chuyen tien") || lower.contains("chuyển tiền") ||
                    lower.contains("thanh toan") || lower.contains("thanh toán") ||
                    lower.contains("chuyen di") || lower.contains("chuyển đi")

            isDeduction = when {
                hasCreditKeyword && !hasDebitKeyword -> false
                hasDebitKeyword && !hasCreditKeyword -> true
                hasDebitKeyword -> true
                else -> false // Mặc định nếu không rõ là PayIn để không bỏ lỡ tiền về
            }
        }

        // 3. Bóc tách mã giao dịch / Mã tham chiếu FT (Ví dụ: FT26091800987, GD123456, Ref: ...)
        var refCode: String? = null
        try {
            val refPattern = Pattern.compile("""(?i)(?:FT[0-9A-Z]{8,}|(?:GD|Ref|Trace|Ma GD|Mã GD|So GD|Số GD|TransId|TraceId)[:\s]*([0-9A-Za-z]+))""")
            val matcher = refPattern.matcher(fullText)
            if (matcher.find()) {
                refCode = matcher.group(1) ?: matcher.group(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi bóc tách mã giao dịch", e)
        }

        // 4. Bóc tách nội dung giao dịch (Memo / OrderId)
        var memo = ""
        try {
            val memoPattern = Pattern.compile("""(?i)(?:ND|Noi dung|Nội dung|ND CK|Ly do|Lý do|Mo ta|Mô tả)[:\s]*(.*?)(?:[\.;\n]|SD|Số dư|So du|Ref|$)""")
            val matcher = memoPattern.matcher(fullText)
            if (matcher.find()) {
                memo = matcher.group(1)?.trim() ?: ""
            } else {
                memo = fullText
            }
        } catch (e: Exception) {
            memo = fullText
        }

        // 5. Bóc tách số dư (nếu có)
        var balance: Long? = null
        try {
            val balPattern = Pattern.compile("""(?i)(?:SD|Số dư|So du|So du cuoi|Số dư cuối)[:\s]*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]+)\s*(?:VND|vnd|đ|d)?""")
            val matcher = balPattern.matcher(fullText)
            if (matcher.find()) {
                val rawBal = matcher.group(1)?.replace(".", "")?.replace(",", "")?.replace(" ", "") ?: ""
                balance = rawBal.toLongOrNull()
            }
        } catch (e: Exception) {
            // Không bắt buộc có số dư
        }

        return ParsedBankNotification(
            bankCode = bankCode,
            isDeduction = isDeduction,
            amount = amount,
            balance = balance,
            memo = memo,
            refCode = refCode,
            rawContent = fullText
        )
    }
}
