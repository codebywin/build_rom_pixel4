# Hướng Dẫn Build & Flash ROM Android 13 Cho Google Pixel 4 (flame)

## ⚡ 1. Lệnh Build Tự Động 1 Dòng (Khuyến nghị trên VPS Ubuntu 20.04/22.04)

```bash
curl -sSL https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/scripts/build_stock_a13.sh | bash -s -- 1 1
```
*(Tham số `1 1`: Tuỳ chọn 1 = AOSP Stock A13 Signed, Tuỳ chọn 2 = Tích hợp sẵn MindTheGapps + Google Chrome + CameraAssistant)*

---

## 📱 2. Quy Trình Nạp ROM & Khóa Bootloader Phần Cứng (Titan M)

1. Tải file ZIP thành phẩm từ VPS về máy tính và giải nén.
2. Đưa điện thoại về chế độ **Bootloader (Fastboot Mode)**:
   - Tắt nguồn máy, nhấn giữ tổ hợp phím **Giảm âm lượng (Vol -) + Nguồn (Power)** cho đến khi màn hình Fastboot xuất hiện.
3. Cắm cáp kết nối điện thoại với máy tính.
4. **Bước 1 - Nạp ROM:** Chạy file **`flash-all.bat`** (Windows) hoặc **`./flash-all.sh`** (Linux/macOS):
   - Script sẽ tự động nạp khóa Root of Trust `avb_custom_key.bin` vào chip bảo mật Titan M.
   - Nạp toàn bộ các phân vùng hệ thống đã ký chữ ký số chuẩn (`boot`, `dtbo`, `vbmeta`, `vbmeta_system`, `super`/`system`, `vendor`, `product`).
   - Tự động wipe sạch userdata (`fastboot -w`) và khởi động máy vào Android.
5. **Lưu ý khi máy khởi động lần đầu:**
   - Sau lệnh `fastboot -w`, màn hình điện thoại có thể tối đen trong khoảng **15 - 30 giây** trong khi chip Titan M xác thực chữ ký và hệ điều hành khởi tạo mã hóa FBE (File-Based Encryption).
   - Hãy kiên nhẫn chờ, máy sẽ rung và hiện logo Google, sau đó vào màn hình thiết lập Android bình thường.
6. **Bước 2 - Khóa Bootloader thật:**
   - Sau khi máy đã khởi động vào màn hình chính Android thành công 100%, đưa máy về lại Fastboot Mode.
   - Nhấp đúp file **`lock-bootloader.bat`**.
   - Trên màn hình Pixel 4: Dùng phím **Giảm âm lượng** chuyển đến dòng **"LOCK THE BOOTLOADER"** và bấm phím **Nguồn** để xác nhận.
   - Máy sẽ khởi động lại với màn hình cảnh báo màu vàng (*Yellow State - Custom Root of Trust*) và vào Android với trạng thái Bootloader đã khóa phần cứng hoàn toàn (`Device state: locked` màu xanh trong Bootloader).

---

## 🆘 3. Thoát Nhanh Khỏi Chế Độ Màn Hình Tối / EDL (Nếu Có)

Nếu màn hình tắt đen và máy tính nhận cổng COM `Qualcomm HS-USB QDLoader 9008`:
1. Rút cáp sạc/kết nối ra khỏi điện thoại.
2. Bấm và **GIỮ CHẶT đồng thời 2 phím: NGUỒN + GIẢM ÂM LƯỢNG (Power + Vol Down)** liên tục trong vòng **35 đến 45 giây**.
3. Chip Snapdragon 855 sẽ kích hoạt Hard Hardware Reset, điện thoại sẽ rung nhẹ rồi hiện lại màn hình Fastboot (Bootloader Mode).