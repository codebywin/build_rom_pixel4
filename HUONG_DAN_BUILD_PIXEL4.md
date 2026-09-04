# Hướng dẫn Build ROM Tự động cho Google Pixel 4 bằng `crave_aosp_builder` qua GitHub Actions

Kho lưu trữ này đã được tích hợp đầy đủ công cụ và cấu hình của **crave_aosp_builder** để bạn có thể biên dịch ROM Android (LineageOS, PixelOS, DerpFest, RisingOS,...) trên máy chủ đám mây siêu tốc của **Crave.io** (CPU đa nhân, RAM 64-128GB, SSD NVMe) hoàn toàn tự động thông qua **GitHub Actions**.

---

## 1. Chuẩn bị tài khoản Crave.io

1. Truy cập [foss.crave.io](https://foss.crave.io) và đăng ký / đăng nhập tài khoản.
2. Sau khi đăng nhập, nhấp vào **Avatar / Tên người dùng** ở góc trên bên phải -> Chọn **API Keys**.
3. Nhấp **Download `crave.conf`** để tải file chứng thực về máy.
4. Mở file `crave.conf` bằng Notepad/VS Code, bạn sẽ thấy thông tin dạng:
   ```json
   {
     "username": "email_cua_ban@example.com",
     "headers": {
       "Authorization": "Bearer eyJhbGciOi...",
       ...
     }
   }
   ```

---

## 2. Đẩy kho mã nguồn lên GitHub của bạn

1. Tạo một repository mới trên tài khoản GitHub cá nhân của bạn (ví dụ đặt tên: `build_rom_pixel4`).
2. Mở terminal tại thư mục này và chạy các lệnh:
   ```bash
   git add .
   git commit -m "feat: setup crave aosp builder for pixel 4 flame"
   git branch -M main
   git remote set-url origin https://github.com/codebywin/build_rom_pixel4.git
   git push -u origin main --force
   ```

---

## 3. Cấu hình Secrets và Quyền hạn trên GitHub

### A. Thêm Repository Secrets
Vào repository trên GitHub -> **Settings** -> **Secrets and variables** -> **Actions** -> bấm **New repository secret**:

1. Secret thứ nhất:
   - **Name**: `CRAVE_USERNAME`
   - **Secret**: Điền giá trị `username` trong file `crave.conf` của bạn.
2. Secret thứ hai:
   - **Name**: `CRAVE_TOKEN`
   - **Secret**: Điền toàn bộ chuỗi `Authorization` trong file `crave.conf` (bao gồm cả chữ `Bearer ` nếu có).

### B. Cấp quyền Workflow (Bắt buộc)
1. Vào **Settings** -> **Actions** -> **General**.
2. Cuộn xuống mục **Workflow permissions**:
   - Chọn **Read and write permissions**.
   - Tích chọn: **Allow GitHub Actions to create and approve pull requests**.
3. Nhấn **Save**.

---

## 4. Khởi tạo Self-Hosted Runner trên Crave

> **Tại sao cần Self-Hosted Runner?**  
> GitHub Actions giới hạn thời gian chạy 6 tiếng và cấm các tác vụ biên dịch nặng trên runner mặc định. `crave_aosp_builder` sẽ biến môi trường Crave Devspace thành Runner riêng của bạn, chạy vĩnh viễn không lo giới hạn thời gian.

1. Vào GitHub repo của bạn -> **Settings** -> **Actions** -> **Runners** -> Nhấp **New runner** -> **New self-hosted runner**.
2. Cuộn xuống phần lệnh cấu hình, tìm và **copy mã Token** sau cờ `--token` (Ví dụ: `ARXXXX...`).
3. Chuyển sang tab **Actions** trên GitHub repository:
   - Chọn workflow **Create Selfhosted Runner** ở cột bên trái.
   - Nhấp **Run workflow**.
   - Dán mã `RUNNER_TOKEN` vừa copy vào ô.
   - Nhấp **Run workflow**.
4. Chờ 2 - 3 phút để workflow hoàn tất.
5. Vào lại **Settings** -> **Actions** -> **Runners**, bạn sẽ thấy một runner mới với trạng thái chấm xanh **Idle / Active**.

*(Lưu ý: Nếu sau này runner bị offline do Crave devspace ngủ đông, bạn chỉ cần vào Actions và chạy workflow **Start/Restart Selfhosted Runner** để đánh thức).*

---

## 5. Bắt đầu Build ROM cho Google Pixel 4 (`flame`)

1. Vào tab **Actions** -> Chọn workflow **Crave Builder(self-hosted)** ở thanh bên trái.
2. Nhấp vào nút **Run workflow** và điền các thông số:

| Trường thông số | Giá trị kiến nghị (Pixel 4 - LineageOS 21 / Android 14) |
| :--- | :--- |
| **Choose a base project** | `LineageOS 21.0` *(hoặc `LineageOS 20.0` nếu muốn Android 13)* |
| **Command to initialize a different 'repo' project** | Giữ mặc định `echo 'Build Starting!'` |
| **Personal local manifest [repository or raw]** | Đã đặt mặc định sẵn, hoặc điền link raw:<br>`https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/manifests/flame_los21.xml`<br>*(hoặc `flame_los20.xml` nếu chọn LOS 20)* |
| **Personal local manifest's branch** | `lineage-21` *(hoặc `lineage-20`)* |
| **Device's codename** | `flame` *(Lưu ý: Nếu bạn dùng Pixel 4 XL thì điền `coral`)* |
| **Product to build** | `lineage_flame` *(hoặc `lineage_coral`)* |
| **Command to be used for compiling** | `mka bacon` |
| **Type of build** | `userdebug` |
| **Build with a clean workspace?** | `no` *(Lần đầu không cần, giữ cache giúp build siêu nhanh)* |

3. Bấm **Run workflow** màu xanh để bắt đầu quá trình biên dịch!

---

## 6. Theo dõi tiến trình và Lấy file ROM thành phẩm

1. GitHub Actions sẽ kết nối đến Self-hosted runner trên Crave và tự động:
   - Đồng bộ mã nguồn ROM từ LineageOS + Trees thiết bị Pixel 4 + Vendor blobs TheMuppets.
   - Chạy lệnh `lunch lineage_flame-userdebug` và `mka bacon`.
2. Sau khi build thành công:
   - File cài đặt ROM `.zip` (cùng `boot.img`, `recovery.img`) sẽ được tạo ra tại thư mục `out/target/product/flame/`.
   - Workflow có cơ chế tự động release file thành phẩm lên mục **Releases** của GitHub (đối với các file nén phù hợp dung lượng).
   - Ngoài ra, bạn luôn có thể kết nối vào Crave CLI / Devspace (`crave devspace`) để copy hoặc tải file bất kỳ lúc nào.

