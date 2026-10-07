# 🔒 Hướng Dẫn Chi Tiết Quy Trình Khóa Bootloader (Lock Bootloader) Từ A - Z
### Google Pixel 4 / 4 XL (`flame` / `coral`) – Chip Bảo Mật Titan M

Tài liệu này ghi lại toàn bộ quy trình chuẩn kỹ thuật để nạp khóa chứng thực phần cứng (**Custom Root of Trust**) vào chip bảo mật **Titan M** và khóa **Bootloader vật lý** (`Device state: locked` màu xanh), giúp ROM AOSP/VCam đạt chuẩn an toàn cao nhất, không bị các ứng dụng ngân hàng phát hiện mở Bootloader.

---

## ⚠️ ĐIỀU KIỆN TIÊN QUYẾT (BẮT BUỘC ĐỌC KỸ TRƯỚC KHI THỰC HIỆN)

> [!CAUTION]
> **QUY TẮC SỐNG CÒN ĐỂ TRÁNH BRICK MÁY:**
> 1. **TUYỆT ĐỐI KHÔNG KHÓA BOOTLOADER** nếu máy chưa boot lên màn hình chính Android thành công ít nhất 1 lần sau khi flash ROM mới.
> 2. **BẮT BUỘC PHẢI DÙNG BẢN ROM ĐÃ ĐƯỢC KÝ (SIGNED ROM):** ROM phải được build dạng `user`, ký bằng bộ khóa release-keys riêng và có AVB signature (`vbmeta.img`).
> 3. **BẬT "MỞ KHÓA OEM" TRONG ANDROID:** Vào *Cài đặt* -> *Tùy chọn nhà phát triển* -> Đảm bảo mục **Mở khóa OEM (OEM Unlocking)** luôn được **BẬT**. Nếu có sự cố, bạn vẫn có thể mở khóa lại dễ dàng.

---

## 📦 1. Chuẩn Bị File Khóa Root of Trust (`avb_custom_key.bin`)

Chip Titan M trên Google Pixel quản lý việc khởi động an toàn bằng Android Verified Boot (AVB 2.0). Để Titan M cho phép boot bản ROM tùy biến khi đã Lock Bootloader, ta phải nạp Public Key của bộ khóa đã ký ROM vào chip.

### Cách trích xuất file `avb_custom_key.bin` từ private key (nếu chưa có):
Trên VPS / máy tính (nơi lưu file `rsa4096.pem` hoặc `avb.pem` dùng để ký ROM):
```bash
python3 avbtool.py extract_public_key \
    --key rsa4096.pem \
    --output avb_custom_key.bin
```
*File `avb_custom_key.bin` thành phẩm sẽ có kích thước khoảng 1KB (RSA-4096) hoặc 520 bytes (RSA-2048).*

---

## 🚀 2. Quy Trình Khóa Bootloader Chi Tiết (5 Bước)

### Bước 1: Flash toàn bộ bản ROM đã ký và nạp khóa Titan M vào Pixel 4
1. Đưa Pixel 4 về chế độ Fastboot (Tắt nguồn -> Giữ **Giảm âm lượng + Nguồn**).
2. **Cách 1 (Khuyên dùng - Nhanh nhất):**
   Chạy file **`flash-all.bat`** đi kèm trong thư mục ROM:
   * File script này đã tích hợp đầy đủ:
     * Nạp khóa Custom Root of Trust vào chip Titan M.
     * Flash đè toàn bộ phân vùng hệ thống (`boot`, `dtbo`, `vbmeta`, `super`).
     * Tự động chạy lệnh **`fastboot -w`** để format trắng toàn bộ máy (Clean 100%).
     * Tự động khởi động máy lên Android.
3. **Cách 2 (Nếu gõ lệnh thủ công trong CMD):**
   ```bash
   # Nạp khóa Titan M trước
   fastboot erase avb_custom_key
   fastboot flash avb_custom_key avb_custom_key.bin

   # Flash toàn bộ phân vùng và wipe sạch (-w tương đương trong flash-all.bat)
   fastboot -w flashall
   ```
4. Chờ máy tự khởi động lên màn hình chào mừng Android 13 hoàn tất. Thiết lập cơ bản vào màn hình chính.

---

### Bước 2: Đưa điện thoại quay lại chế độ Bootloader
Từ máy tính (đã cắm cáp USB nối với điện thoại):
```bash
adb reboot bootloader
```
*(Hoặc tắt nguồn máy và giữ phím **Giảm âm lượng + Nguồn**).*

Kiểm tra kết nối Fastboot:
```bash
fastboot devices
```
*Màn hình Fastboot lúc này vẫn đang có dòng chữ đỏ: `Device state: unlocked`.*

---

### Bước 3: Nạp khóa Root of Trust vào chip Titan M
Thực hiện lần lượt 2 lệnh sau:

```bash
# 1. Xóa khóa tùy biến cũ lưu trong Titan M (nếu có)
fastboot erase avb_custom_key

# 2. Nạp khóa Custom Root of Trust mới vào Titan M
fastboot flash avb_custom_key avb_custom_key.bin
```
*Kết quả trả về trên màn hình console:*
```text
Erasing 'avb_custom_key'              OKAY [  0.030s]
Sending 'avb_custom_key' (1 KB)       OKAY [  0.015s]
Writing 'avb_custom_key'              OKAY [  0.040s]
Finished. Total time: 0.085s
```

---

### Bước 4: Phát lệnh Khóa Bootloader
Chạy lệnh khóa phần cứng:
```bash
fastboot flashing lock
```

---

### Bước 5: Xác nhận trên màn hình Pixel 4 (Thao tác vật lý)
Lúc này, trên màn hình điện thoại Pixel 4 sẽ xuất hiện bảng cảnh báo:
```text
Lock the bootloader?
If you lock the bootloader, your device will be wiped...
```

1. Dùng **Phím Tăng / Giảm Âm lượng** để di chuyển vệt chọn đến dòng:
   👉 **`LOCK THE BOOTLOADER`**
2. Nhấn **Phím Nguồn (Power)** một lần để xác nhận khóa.

---

## 📱 3. Trạng Thái Sau Khi Khóa Thành Công

1. **Quá trình Factory Reset:** Máy sẽ tự động xóa sạch dữ liệu người dùng (`Wiping data / Factory Reset`) theo tiêu chuẩn bảo mật của Google.
2. **Màn hình khởi động màu vàng (Yellow Boot State):**
   * Mỗi khi bật nguồn, máy sẽ hiển thị màn hình cảnh báo nền đen chữ vàng:
     > *"Your device is loading a different operating system..."*  
     > *(Kèm theo dòng mã hash ID của Custom Root of Trust).*
   * **Đây là trạng thái chuẩn 100% của Google Pixel** khi chạy ROM tùy biến có khóa Bootloader bằng Root of Trust riêng.
   * Sau khoảng 5 giây máy sẽ tự động vào thẳng Android mà không cần bấm gì.
3. **Kiểm tra trạng thái Bootloader trong Fastboot:**
   * Dòng trạng thái chuyển thành màu xanh lá cây:
     👉 **`Device state: locked`**
   * Kiểm tra qua lệnh:
     ```bash
     fastboot getvar unlocked
     # Kết quả: unlocked: no
     ```

---

## 🛠️ File Script Tự Động (`lock-bootloader.bat`)

Để thuận tiện thao tác cho những lần sau, bạn có thể lưu nội dung dưới đây thành file `lock-bootloader.bat` đặt cùng thư mục với `avb_custom_key.bin` và `fastboot.exe`:

```bat
@echo off
chcp 65001 >nul
echo ========================================================
echo   KHÓA BOOTLOADER PHẦN CỨNG (GENUINE HARDWARE LOCK)
echo   Google Pixel 4 (flame) - Chip Bảo Mật Titan M
echo ========================================================
echo [CHÚ Ý]: Chỉ chạy script này SAU KHI điện thoại đã khởi
echo động vào màn hình Android thành công ít nhất 1 lần!
echo.
pause

echo.
echo [1/3] Đưa điện thoại về chế độ Bootloader...
fastboot reboot bootloader
timeout /t 4 /nobreak >nul

if exist avb_custom_key.bin (
    echo [2/3] Nạp khóa Custom Root of Trust vào chip Titan M...
    fastboot erase avb_custom_key
    fastboot flash avb_custom_key avb_custom_key.bin
) else (
    echo [LỖI] Không tìm thấy file avb_custom_key.bin! Dừng lại để tránh brick!
    pause
    exit /b 1
)

echo.
echo [3/3] Đang gửi lệnh khóa Bootloader tới Titan M...
fastboot flashing lock

echo.
echo ========================================================
echo TRÊN MÀN HÌNH PIXEL 4 LÚC NÀY:
echo 1. Dùng phím Âm lượng để chọn dòng "LOCK THE BOOTLOADER"
echo 2. Nhấn phím Nguồn (Power) để xác nhận!
echo.
echo Máy sẽ khởi động lại với Bootloader LOCKED (Màu xanh)!
echo ========================================================
pause
```

---

## 🔄 Khi Cần Mở Khóa Lại (Unlock Bootloader)
Nếu trong tương lai bạn cần nạp lại ROM hoặc mở khóa lại:
1. Đưa máy về Fastboot.
2. Chạy lệnh:
   ```bash
   fastboot flashing unlock
   ```
3. Trên màn hình điện thoại, chọn **`UNLOCK THE BOOTLOADER`** và bấm phím **Nguồn**.
