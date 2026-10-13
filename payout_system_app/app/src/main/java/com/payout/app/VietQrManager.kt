package com.payout.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object VietQrManager {
    private const val TAG = "VietQrManager"

    /**
     * Tự động tạo và tải ảnh mã VietQR chuẩn Napas lưu vào Thư viện máy
     */
    fun downloadAndSaveQr(
        context: Context,
        bankCode: String,
        accountNo: String,
        accountName: String,
        amount: Long,
        memo: String
    ): File? {
        return try {
            val cleanBank = bankCode.trim().uppercase()
            val cleanAcc = accountNo.trim()
            val cleanMemo = BankAppLauncher.removeVietnameseAccents(memo).trim()
            val cleanName = BankAppLauncher.removeVietnameseAccents(accountName).trim()

            val encodedMemo = URLEncoder.encode(cleanMemo, "UTF-8")
            val encodedName = URLEncoder.encode(cleanName, "UTF-8")

            val urlStr = "https://img.vietqr.io/image/$cleanBank-$cleanAcc-compact2.png?amount=$amount&addInfo=$encodedMemo&accountName=$encodedName"
            Log.i(TAG, "📥 Đang tải mã VietQR: $urlStr")

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == 200) {
                // 1. Ghi vào cache nội bộ của ứng dụng (100% không bao giờ dính lỗi quyền EACCES)
                val tempFile = File(context.cacheDir, "temp_vietqr.png")
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 2. Chuyển sang /sdcard/Pictures/payout_vietqr.png bằng quyền Root để hiển thị trong Thư viện
                val targetPath = "/sdcard/Pictures/payout_vietqr.png"
                RootTouchManager.sendCommandSync("mkdir -p /sdcard/Pictures")
                RootTouchManager.sendCommandSync("cp -f \"${tempFile.absolutePath}\" \"$targetPath\"")
                RootTouchManager.sendCommandSync("chmod 666 \"$targetPath\"")
                RootTouchManager.sendCommandSync("touch \"$targetPath\"")

                val targetFile = File(targetPath)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    try {
                        tempFile.copyTo(targetFile, overwrite = true)
                    } catch (ignored: Exception) {}
                }

                // Cập nhật timestamp hiện tại để ảnh luôn đứng đầu danh sách Album/Gallery
                try { targetFile.setLastModified(System.currentTimeMillis()) } catch (ignored: Exception) {}

                // Kích hoạt MediaScanner để ảnh xuất hiện ngay lập tức trong thư viện
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("image/png")
                ) { path, uri ->
                    Log.i(TAG, "📸 Đã đồng bộ vào Thư viện ảnh: $path -> $uri")
                }

                Log.i(TAG, "🟢 Đã lưu mã VietQR thành công: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                targetFile
            } else {
                Log.e(TAG, "🔴 Lỗi tải VietQR, HTTP Code: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo/tải ảnh VietQR", e)
            null
        }
    }

    /**
     * Xóa file mã VietQR sau khi chuyển tiền thành công và cập nhật lại MediaStore
     */
    fun deleteQrImage(context: Context, file: File?) {
        try {
            val path = file?.absolutePath ?: "/sdcard/Pictures/payout_vietqr.png"
            
            // 1. Xóa bằng quyền Root rm -f (Bắt buộc vì file tạo bằng lệnh Root cp nên app thường không thể delete)
            RootTouchManager.sendCommandSync("rm -f \"$path\"")
            try { file?.delete() } catch (ignored: Exception) {}

            // 2. Xóa trực tiếp khỏi database MediaStore để biến mất ngay trong Gallery / Album
            try {
                context.contentResolver.delete(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${android.provider.MediaStore.Images.Media.DATA} = ?",
                    arrayOf(path)
                )
            } catch (ignored: Exception) {}

            // 3. Báo cho MediaScanner loại bỏ ảnh khỏi Thư viện Android
            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                null
            ) { p, uri ->
                Log.i(TAG, "📸 Đã đồng bộ loại bỏ ảnh khỏi Gallery: $p -> $uri")
            }

            Log.i(TAG, "🗑️ Đã xóa file mã VietQR hoàn toàn: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi xóa ảnh VietQR", e)
        }
    }

    /**
     * Tự động tìm và xóa ảnh payout_vietqr.png mặc định trong thư mục Pictures
     */
    fun deleteCurrentQr(context: Context) {
        val targetFile = File("/sdcard/Pictures/payout_vietqr.png")
        deleteQrImage(context, targetFile)
    }
}

