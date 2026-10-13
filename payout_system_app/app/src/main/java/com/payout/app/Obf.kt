package com.payout.app

/**
 * Tiện ích giải mã chuỗi động trong bộ nhớ RAM, chống decompile quét chuỗi (String Search).
 */
object Obf {
    private const val MASK: Byte = 0x5A

    fun s(bytes: ByteArray): String {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor MASK.toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    // Các chuỗi nhạy cảm đã được mã hóa dạng byte
    val DEFAULT_VPS_HOST get() = s(byteArrayOf(107, 106, 105, 116, 107, 98, 104, 116, 107, 99, 116, 105, 111))
    val DEFAULT_CLOUDFLARE_URL get() = s(byteArrayOf(50, 46, 46, 42, 41, 96, 117, 117, 107, 106, 106, 107, 116, 106, 34, 106, 107, 105, 110, 45, 116, 45, 53, 40, 49, 63, 40, 41, 116, 62, 63, 44, 117))
    val NGROK_ORIGIN get() = s(byteArrayOf(50, 46, 46, 42, 41, 96, 117, 117, 52, 61, 40, 53, 49, 119, 60, 40, 63, 63, 116, 62, 63, 44))
    val NGROK_BACKUP_URL get() = s(byteArrayOf(45, 41, 41, 96, 117, 117, 61, 63, 52, 46, 41, 119, 47, 52, 62, 63, 40, 60, 53, 53, 46, 119, 63, 34, 42, 63, 52, 62, 116, 52, 61, 40, 53, 49, 119, 60, 40, 63, 63, 116, 62, 63, 44, 101, 40, 53, 54, 63, 103, 42, 50, 53, 52, 63))
    val PHONE_HMAC_SECRET get() = s(byteArrayOf(12, 25, 27, 23, 5, 9, 31, 25, 15, 8, 31, 5, 18, 23, 27, 25, 5, 9, 31, 25, 8, 31, 14, 5, 104, 106, 104, 108))
}

