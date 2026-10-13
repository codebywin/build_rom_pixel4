# 📱 Payout Manager (`com.payout.app`)

Hệ thống điều khiển và tự động hóa thanh toán Payout Android độc lập qua mạng LAN/Internet với độ trễ thấp, kết nối WebSocket và tự động đối soát thông báo biến động số dư.

---

## 📁 Cấu trúc dự án
- **`app/`**: Mã nguồn ứng dụng Android độc lập (Kotlin, Package: `com.payout.app`).
  - `MainActivity.kt`: Giao diện hiển thị trạng thái kết nối máy chủ VPS/Ngrok/Cloudflare.
  - `RemoteForegroundService.kt`: Dịch vụ chạy ngầm 24/7 duy trì kết nối WebSocket và điều phối Payout.
  - `BankNotificationListenerService.kt` & `BankNotificationParser.kt`: Lắng nghe và đối soát biến động số dư ngân hàng/SMS.
  - `BankAppLauncher.kt` & `VietQrManager.kt`: Tự động mở app ngân hàng, tải và quét mã VietQR Napas.
  - `RootTouchManager.kt`: Quản lý cảm ứng, click và gõ phím tự động.
  - `ServerConfigManager.kt`: Tự động đồng bộ IP máy chủ VPS mới nhất từ Cloudflare KV / bộ nhớ máy.
- **`server/`**: Máy chủ Node.js trung gian hỗ trợ xác thực 2FA TOTP và bảo mật đa lớp.
- **`web_client/`**: Giao diện điều khiển Web HTML5/CSS3 tương tác thời gian thực (`index.html`).
- **`cloudflare/`**: Cloudflare Worker quản lý Dynamic IP Discovery qua KV.

---

## 🚀 Hướng dẫn cài đặt & Biên dịch

### 1. Build file APK độc lập
* **Cách 1:** Chạy file `build_apk.bat` và chọn chế độ build mong muốn.
* **Cách 2:** Mở dự án trong Android Studio và nhấn **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
* **Cách 3:** Dùng dòng lệnh Gradle:
  ```powershell
  .\gradlew.bat assembleDebug
  ```
  File APK sau khi build sẽ nằm tại: `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Cài đặt lên điện thoại
1. Cài đặt file APK lên thiết bị Android đã **Root** (qua Magisk / KernelSU / APatch).
2. Kích hoạt module trong **LSPosed** nếu muốn bypass màn hình đen `FLAG_SECURE`.
3. Mở ứng dụng và bấm **Cấp quyền Root (Grant)**.

### 3. Khởi chạy Server & Web Client
1. Chạy file `run_server.bat` (hoặc `node server/server.js`).
2. Mở trình duyệt truy cập `web_client/index.html` để bắt đầu điều khiển và giám sát thiết bị.
