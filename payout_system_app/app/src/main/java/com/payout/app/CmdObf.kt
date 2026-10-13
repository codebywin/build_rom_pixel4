package com.payout.app

/**
 * Mã hóa chuỗi lệnh Root Shell dưới dạng Byte động, chống quét lệnh trong JADX/Apktool.
 */
object CmdObf {
    private const val M: Byte = 0x5A

    fun d(b: ByteArray): String {
        val o = ByteArray(b.size)
        for (i in b.indices) o[i] = (b[i].toInt() xor M.toInt()).toByte()
        return String(o, Charsets.UTF_8)
    }

    val SU get() = d(byteArrayOf(41, 47))
    val MINUS_C get() = d(byteArrayOf(119, 57))
    val INPUT_TAP get() = d(byteArrayOf(51, 52, 42, 47, 46, 122, 46, 59, 42, 122))
    val INPUT_SWIPE get() = d(byteArrayOf(51, 52, 42, 47, 46, 122, 41, 45, 51, 42, 63, 122))
    val INPUT_KEY get() = d(byteArrayOf(51, 52, 42, 47, 46, 122, 49, 63, 35, 63, 44, 63, 52, 46, 122))
    val INPUT_TEXT get() = d(byteArrayOf(51, 52, 42, 47, 46, 122, 46, 63, 34, 46, 122))
    val GETEVENT_L get() = d(byteArrayOf(61, 63, 46, 63, 44, 63, 52, 46, 122, 119, 54))
    val WM_SIZE get() = d(byteArrayOf(45, 55, 122, 41, 51, 32, 63))
}

