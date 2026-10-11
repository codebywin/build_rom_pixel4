# Hướng Dẫn Build & Flash ROM Android 13 Cho Google Pixel 4 (flame)

## ⚡ 1. Lệnh Build Tự Động 1 Dòng (Khuyến nghị trên VPS Ubuntu 20.04/22.04)

```bash
curl -sSL https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/scripts/build_stock_a13.sh | bash -s -- 1 1
```
*(Tham số `1 1`: Tuỳ chọn 1 = AOSP Stock A13, Tuỳ chọn 2 = Tích hợp sẵn MindTheGapps + Google Chrome + CameraAssistant)*

---

## 📱 2. Hướng Dẫn Nạp ROM An Toàn (Tránh Brick EDL 9008)

1. Tải file ZIP thành phẩm từ VPS về máy tính và giải nén.
2. Đưa điện thoại về chế độ **Bootloader (Fastboot Mode)**:
   - Tắt nguồn, giữ tổ hợp phím **Giảm âm lượng (Vol -) + Nguồn (Power)** cho đến khi màn hình Fastboot xuất hiện.
3. Cắm cáp kết nối điện thoại với máy tính.
4. Chạy file **`flash-all.bat`** (Windows) hoặc **`./flash-all.sh`** (Linux/macOS):
   - Script sẽ tự động xóa custom key Titan M (nếu có).
   - Tự động nạp `vbmeta.img` và `vbmeta_system.img` với cờ `--disable-verity --disable-verification` để triệt tiêu lỗi dm-verity Kernel Panic.
   - Nạp toàn bộ các phân vùng hệ thống (`boot`, `dtbo`, `super`/`system`, `vendor`, `product`).
   - Tự động wipe sạch userdata và khởi động máy vào Android.

> [!CAUTION]
> **TUYỆT ĐỐI KHÔNG KHÓA BOOTLOADER BẰNG TAY (`fastboot flashing lock`)!**  
> Bản ROM này đã được vá spoof locked bootloader (`ro.boot.flash.locked=1`, Green state) ngay từ nhân Kernel và Init, nên các ứng dụng ngân hàng và KYC sẽ thấy máy ở trạng thái locked hoàn toàn. Khóa bootloader vật lý trên ROM mod sẽ khiến Titan M từ chối nạp và rơi vào EDL!

---

## 🆘 3. Cứu Máy Nếu Điện Thoại Đang Bị Rơi Vào EDL (Qualcomm HS-USB QDLoader 9008)

Nếu màn hình tắt đen thui và máy tính nhận cổng COM `Qualcomm HS-USB QDLoader 9008`:
1. Rút cáp sạc/kết nối ra khỏi điện thoại.
2. Bấm và **GIỮ CHẶT đồng thời 2 phím: NGUỒN + GIẢM ÂM LƯỢNG (Power + Vol Down)** liên tục trong vòng **35 đến 45 giây**.
3. Chip Snapdragon 855 sẽ kích hoạt Hard Hardware Reset và điện thoại sẽ rung nhẹ rồi hiện lại màn hình Fastboot (Bootloader Mode).
4. Cắm lại cáp vào máy tính và chạy lại `flash-all.bat` mới để nạp đúng vbmeta disable-verity.