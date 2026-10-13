package com.payout.app.banks

import android.util.Log

/**
 * Trung tâm quản lý và điều phối các ngân hàng trong hệ thống.
 * Tự động tìm kiếm Handler tương ứng khi nhận được lệnh Payout hoặc thông báo ngân hàng.
 */
object BankRegistry {
    private const val TAG = "BankRegistry"

    private val handlersByCode = mutableMapOf<String, BankHandler>()
    private val handlersByPackage = mutableMapOf<String, BankHandler>()

    // Danh sách Package Name dự phòng cho các ngân hàng chưa viết Handler riêng
    private val defaultBankPackages = mapOf(
        "ACB" to listOf("mobile.acb.com.vn", "com.acb.mobile"),
        "VCB" to listOf("com.VCB"),
        "MB" to listOf("com.mbmobile"),
        "TCB" to listOf("vn.com.techcombank.bb.app"),
        "VPB" to listOf("com.vpb.mbs.res", "com.vpb.mb"),
        "BIDV" to listOf("com.vnpay.bidv"),
        "CTG" to listOf("mobile.idplus.vietinbank", "com.vietinbank.ipay"),
        "TPB" to listOf("com.tpbank"),
        "MSB" to listOf("com.msb.mb"),
        "STB" to listOf("com.sacombank.ewallet"),
        "SEABANK" to listOf("vn.com.seabank.mb1", "vn.com.seabank.mobile"),
        "SEAB" to listOf("vn.com.seabank.mb1", "vn.com.seabank.mobile")
    )

    init {
        // Đăng ký các Handler ngân hàng vào Registry
        register(AcbBank)
        register(SeabankBank)
        register(VcbBank)
        register(MbBank)
        register(Techcombank)
    }

    /**
     * Đăng ký một ngân hàng mới vào hệ thống
     */
    fun register(handler: BankHandler) {
        val code = handler.bankCode.uppercase().trim()
        handlersByCode[code] = handler

        // Hỗ trợ alias (ví dụ SEAB -> SEABANK)
        if (code == "SEABANK") {
            handlersByCode["SEAB"] = handler
        }

        for (pkg in handler.packageNames) {
            handlersByPackage[pkg] = handler
        }
        Log.d(TAG, "Đã nạp Handler ngân hàng: $code (${handler.packageNames.joinToString(", ")})")
    }

    /**
     * Lấy Handler theo mã ngân hàng (ACB, SEABANK, VCB...)
     */
    fun getHandler(bankCode: String): BankHandler? {
        val upper = bankCode.uppercase().trim()
        return handlersByCode[upper]
    }

    /**
     * Lấy Handler theo Package Name ứng dụng
     */
    fun getHandlerByPackage(packageName: String): BankHandler? {
        return handlersByPackage[packageName]
    }

    /**
     * Lấy danh sách package names cho một ngân hàng bất kỳ
     */
    fun getPackagesForBank(bankCode: String): List<String> {
        val upper = bankCode.uppercase().trim()
        return handlersByCode[upper]?.packageNames ?: defaultBankPackages[upper] ?: listOf(upper)
    }

    /**
     * Lấy toàn bộ bản đồ Bank -> Packages
     */
    fun getAllBankPackages(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        result.putAll(defaultBankPackages)
        for ((code, handler) in handlersByCode) {
            result[code] = handler.packageNames
        }
        return result
    }
}

