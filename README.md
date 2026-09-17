# LineageOS 23.2 (Android 16) VCam for Google Pixel 4 (flame) 🚀

Dự án tích hợp **Virtual Camera (VCam)** vào hệ điều hành **Android 16 (LineageOS 23.2)** cho điện thoại **Google Pixel 4 (`flame`)**.

---

## 🌟 Tính Năng Nổi Bật
* **Nền tảng Android 16 (LineageOS 23.2):** Mượt mà, hỗ trợ tốt các ứng dụng ngân hàng mới nhất (ACB ONE, VPBank NEO...) không lo bị chặn hay phát hiện can thiệp root.
* **Tích hợp sâu trong hệ thống (Framework-level VCam):** Can thiệp ở tầng `Camera.java` (Camera 1) và `CameraDeviceImpl.java` (Camera 2), hoạt động trên mọi ứng dụng gọi camera.
* **Bản quyền an toàn RSA-2048:** Kiểm tra bản quyền theo số serial máy (`isLicenseActive()`).
* **Ứng dụng quản lý hệ thống (CameraAssistant):**
  * Tích hợp sẵn làm App Hệ Thống (`/system/app/CameraAssistant/CameraAssistant.apk`).
  * Cửa sổ nổi (Floating Window) hỗ trợ Zoom, D-Pad điều hướng, Tạm dừng trực tiếp.
  * Chọn video từ thư viện máy, nạp video tức thì không cần khởi động lại ứng dụng.

---

## 🛠️ Hướng Dẫn Sử Dụng Tool Nạp Nhanh (Khi Flash Lại ROM Gốc)
Nếu bạn flash bản ROM LineageOS 23.2 Official về máy và muốn nạp VCam ngay lập tức:

1. Bật **Gỡ lỗi USB (USB Debugging)** trên điện thoại Pixel 4.
2. Cắm cáp kết nối điện thoại với máy tính.
3. Chạy file:
   ```cmd
   flash_vcam_a16.bat
   ```
   *(Hoặc chạy `flash_vcam_a16.ps1` bằng PowerShell).*
4. Tool sẽ tự động:
   * Remount `/system` sang chế độ ghi chép (RW).
   * Nạp `framework_vcam.jar` vào `/system/framework/framework.jar`.
   * Cài đặt `CameraAssistant.apk` làm ứng dụng hệ thống.
   * Cấp quyền đọc/ghi cho thư mục video `/data/local/tmp`.
   * Khởi động lại máy.

---

## ☁️ Build ROM Từ Mã Nguồn (Crave / GitHub Actions)
Repository này hỗ trợ tự động build ROM xuất xưởng trên máy chủ đám mây Crave:
1. **Full Manifest Official (20260910):** `manifests/build-manifest.xml` (Ghim chính xác toàn bộ mã nguồn theo bản official trên freedif mirror + Vendor Blobs TheMuppets).
2. **Local Manifest Devspace:** `manifests/flame_los23.xml` (Dành cho việc tái sử dụng cache có sẵn trên devspace Lineage23).
3. **Patch VCam Android 16:** `patches/vcam_pixel4_a16.patch`
4. **Patch SELinux:** `patches/sepolicy_vcam_coral.patch`
5. **Workflow tự động:** `.github/workflows/selfhosted.yml`

