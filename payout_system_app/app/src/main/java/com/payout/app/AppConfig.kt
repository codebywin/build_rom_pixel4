package com.payout.app

/**
 * CẤU HÌNH HỆ THỐNG PAYOUT MANAGER
 * Mặc định kết nối tới Localhost / PC Server (127.0.0.1 qua USB hoặc IP Wi-Fi).
 */
object AppConfig {
    const val DEFAULT_VPS_HOST = "127.0.0.1"
    const val DEFAULT_VPS_PORT = 3000
    const val DEFAULT_CLOUDFLARE_URL = "https://1001.0x0134w.workers.dev/"
    const val NGROK_ORIGIN = "https://ngrok-free.dev"
    const val NGROK_BACKUP_URL = "wss://gents-underfoot-expend.ngrok-free.dev?role=phone"
    const val PHONE_HMAC_SECRET = "VCAM_SECURE_HMAC_SECRET_2026"
}
