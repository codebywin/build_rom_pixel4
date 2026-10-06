#!/usr/bin/env bash
# ==============================================================================
# Script tự động Build ROM STOCK AOSP Android 13 cho Google Pixel 4 (flame)
# Dành riêng cho VPS Ubuntu (Đã tối ưu hóa cho RAM 128GB, SSD 500GB)
# Android Version: Android 13.0.0 (Tag: android-13.0.0_r13 - Build: TP1A.221005.002.B2)
# ==============================================================================

set -eo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}     BẮT ĐẦU QUY TRÌNH BUILD ROM STOCK A13 CHO PIXEL 4 (FLAME)     ${NC}"
echo -e "${CYAN}==================================================================${NC}"

# Hỏi người dùng về việc có tích hợp VCam hay không
echo -e "\n${YELLOW}Bạn muốn build ROM Stock theo dạng nào?${NC}"
echo "  1) ROM Stock A13 + TÍCH HỢP CORE VCAM & Bypass bảo mật (Tạm tắt CameraAssistant để test)"
echo "  2) ROM Stock A13 NGUYÊN BẢN 100% (Thuần Google gốc, không mod gì)"
read -p "Nhập lựa chọn của bạn [1 hoặc 2, mặc định 1]: " STOCK_MODE
STOCK_MODE="${STOCK_MODE:-1}"

# 1. Kiểm tra tài nguyên
CPU_CORES=$(nproc)
RAM_GB=$(free -g | awk '/^Mem:/{print $2}')
echo -e "\n${BLUE}>> [1/6] Cấu hình máy chủ phát hiện:${NC}"
echo -e "   - CPU: ${GREEN}${CPU_CORES} Cores${NC}"
echo -e "   - RAM: ${GREEN}${RAM_GB} GB${NC} (Rất mạnh, build siêu tốc)"

# Tự động nhận diện root hoặc sudo
SUDO=""
if [ "$EUID" -ne 0 ]; then
    if command -v sudo &>/dev/null; then
        SUDO="sudo"
    else
        echo -e "${RED}[LỖI] Bạn không phải root và máy chưa cài sudo. Vui lòng chạy lệnh: su - để chuyển sang root!${NC}"
        exit 1
    fi
fi

# 2. Cài đặt các gói phụ thuộc trên Debian / Ubuntu
echo -e "\n${BLUE}>> [2/6] Kiểm tra và cài đặt gói thư viện biên dịch...${NC}"
$SUDO apt update
$SUDO apt install -y \
    git-core gnupg flex bison build-essential zip curl zlib1g-dev \
    libc6-dev-i386 x11proto-core-dev libx11-dev lib32z1-dev \
    libgl1-mesa-dev libxml2-utils xsltproc unzip fontconfig libssl-dev \
    python3 python-is-python3 rsync bc ccache \
    liblz4-tool tar wget 2>/dev/null || true

# Cài đặt thư viện ncurses tương thích cả Debian 11/12 và Ubuntu
$SUDO apt install -y libncurses5 libtinfo5 2>/dev/null || $SUDO apt install -y libncurses6 libtinfo6 2>/dev/null || true
$SUDO apt install -y libncurses5-dev libncursesw5-dev 2>/dev/null || true

# Tạo symlink thư viện cho ncurses (để các công cụ AOSP đời cũ không bị thiếu libncurses.so.5)
for libpath in /usr/lib/x86_64-linux-gnu /lib/x86_64-linux-gnu /usr/lib64; do
    if [ -f "$libpath/libncurses.so.6" ] && [ ! -f "$libpath/libncurses.so.5" ]; then
        $SUDO ln -sf "$libpath/libncurses.so.6" "$libpath/libncurses.so.5" 2>/dev/null || true
    fi
    if [ -f "$libpath/libtinfo.so.6" ] && [ ! -f "$libpath/libtinfo.so.5" ]; then
        $SUDO ln -sf "$libpath/libtinfo.so.6" "$libpath/libtinfo.so.5" 2>/dev/null || true
    fi
done

# 3. Cài đặt công cụ repo và cấu hình Git
echo -e "\n${BLUE}>> [3/6] Thiết lập Git & Repo...${NC}"
mkdir -p ~/bin
if ! command -v repo &>/dev/null; then
    curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
    chmod a+x ~/bin/repo
fi
export PATH=~/bin:$PATH
grep -q 'export PATH=~/bin:$PATH' ~/.bashrc || echo 'export PATH=~/bin:$PATH' >> ~/.bashrc

if [ -z "$(git config --global user.name)" ]; then
    git config --global user.name "Pixel4 Builder"
fi
if [ -z "$(git config --global user.email)" ]; then
    git config --global user.email "builder@pixel4.local"
fi

# Tắt ccache triệt để để tránh cơ chế Sandbox của Android 13 chặn ghi ra ngoài (Read-only file system)
# Với 88 luồng CPU và 128GB RAM, Clang chạy trực tiếp siêu tốc!
unset USE_CCACHE
unset CCACHE_EXEC
unset CCACHE_DIR
export USE_CCACHE=0
export TEMPORARY_DISABLE_PATH_RESTRICTIONS=true

# 4. Tải mã nguồn Android 13 Stock AOSP
WORKDIR="$HOME/aosp_pixel4_a13"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo -e "\n${BLUE}>> [4/6] Khởi tạo và đồng bộ mã nguồn AOSP Android 13...${NC}"
echo -e "   Tag: ${GREEN}android-13.0.0_r13${NC} (Pixel 4 TP1A.221005.002.B2)"

if [ ! -d ".repo" ]; then
    repo init -u https://android.googlesource.com/platform/manifest -b android-13.0.0_r13 --depth=1
fi

# Giới hạn số luồng tải git từ Google xuống 4 luồng (tránh bị Google Git chặn HTTP 429 RESOURCE_EXHAUSTED)
SYNC_JOBS=4
echo -e "${CYAN}>> Đang tải mã nguồn (Chạy an toàn ${SYNC_JOBS} luồng để không bị giới hạn Google Quota)...${NC}"
until repo sync -c -j"${SYNC_JOBS}" --no-clone-bundle --no-tags; do
    echo -e "${YELLOW}>> Bị nghẽn mạng hoặc chạm quota Google, nghỉ 15 giây rồi tự động tải nối tiếp...${NC}"
    sleep 15
done

# 5. Tải Driver độc quyền chính hãng Pixel 4 (Google & Qualcomm Vendor Blobs)
echo -e "\n${BLUE}>> [5/6] Tải và giải nén Driver chính hãng cho Pixel 4 (flame)...${NC}"
export PAGER=cat

if [ ! -d "vendor/google_devices/flame" ]; then
    echo ">> Tải Google Driver..."
    rm -f google_devices-flame-* extract-google_devices-flame*
    curl -sL -O https://dl.google.com/dl/android/aosp/google_devices-flame-tp1a.221005.002.b2-22399ead.tgz
    tar -xzf google_devices-flame-*.tgz
    printf "I ACCEPT\n" | ./extract-google_devices-flame.sh
    rm -f google_devices-flame-*.tgz extract-google_devices-flame.sh
fi

if [ ! -d "vendor/qcom/flame" ]; then
    echo ">> Tải Qualcomm Driver..."
    rm -f qcom-flame-* extract-qcom-flame*
    curl -sL -O https://dl.google.com/dl/android/aosp/qcom-flame-tp1a.221005.002.b2-358af558.tgz
    tar -xzf qcom-flame-*.tgz
    printf "I ACCEPT\n" | ./extract-qcom-flame.sh
    rm -f qcom-flame-*.tgz extract-qcom-flame.sh
fi

# Áp dụng bản vá VCam nếu được chọn
if [ "$STOCK_MODE" == "1" ]; then
    echo -e "\n${CYAN}>> Đang tích hợp VCam & Công cụ hỗ trợ vào ROM Stock...${NC}"
    REPO_REF="main"
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    LOCAL_PATCH_DIR="${SCRIPT_DIR}/../patches"
    
    apply_patch() {
        local target_dir="$1"
        local patch_name="$2"
        local patch_url="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/patches/${patch_name}"
        local tmp_patch="/tmp/${patch_name}"

        if [ -d "$target_dir" ]; then
            if [ -f "${LOCAL_PATCH_DIR}/${patch_name}" ]; then
                echo -e "   [LOCAL] Dùng patch: ${patch_name} -> ${target_dir}"
                cp "${LOCAL_PATCH_DIR}/${patch_name}" "$tmp_patch"
            else
                echo -e "   [ONLINE] Tải patch: ${patch_name} -> ${target_dir}"
                curl -sL "$patch_url" > "$tmp_patch"
            fi
            sed -i 's/\r$//' "$tmp_patch"

            if git -C "$target_dir" apply --ignore-space-change --ignore-whitespace --check "$tmp_patch" 2>/dev/null; then
                git -C "$target_dir" apply --ignore-space-change --ignore-whitespace "$tmp_patch" 2>/dev/null && \
                echo -e "   [${GREEN}OK${NC}] Applied ${patch_name} -> ${target_dir}" || true
            else
                echo -e "   [${YELLOW}SKIP${NC}] ${patch_name} (đã apply hoặc conflict)"
            fi
        fi
    }

    echo -e "   [CLEAN] Đưa các repo về trạng thái sạch trước khi apply..."
    for clean_repo in frameworks/base system/core device/google/coral device/google/coral-sepolicy; do
        if [ -d "$clean_repo" ]; then
            git -C "$clean_repo" checkout -f 2>/dev/null || true
            git -C "$clean_repo" clean -fd 2>/dev/null || true
        fi
    done

    apply_patch "frameworks/base" "vcam_pixel4.patch"
    apply_patch "frameworks/base" "spoof_locked_bootloader.patch"
    apply_patch "system/core" "init_spoof_bootloader.patch"
    apply_patch "frameworks/base" "disable_flag_secure.patch"
    apply_patch "frameworks/base" "hide_developer_options.patch"
    apply_patch "frameworks/base" "hide_accessibility_services.patch"
    apply_patch "frameworks/base" "bypass_overlay_tapjacking.patch"
    apply_patch "frameworks/base" "hide_sensitive_packages.patch"
    apply_patch "device/google/coral" "sepolicy_vcam_coral.patch"
    apply_patch "device/google/coral-sepolicy" "sepolicy_vcam_coral.patch"

    # [TẠM THỜI TẮT] Chưa nhúng CameraAssistant App để build test core VCam & ROM trước
    # mkdir -p packages/apps/CameraAssistant
    # if [ -f "${SCRIPT_DIR}/../org.lineageos.camera.assistant/CameraAssistant.apk" ]; then
    #     cp "${SCRIPT_DIR}/../org.lineageos.camera.assistant/CameraAssistant.apk" packages/apps/CameraAssistant/CameraAssistant.apk
    #     cp "${LOCAL_PATCH_DIR}/CameraAssistant_Android.bp" packages/apps/CameraAssistant/Android.bp
    # else
    #     curl -sL "https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/patches/CameraAssistant_Android.bp" > packages/apps/CameraAssistant/Android.bp
    # fi
    # if [ -f device/google/coral/device.mk ]; then
    #     grep -q "CameraAssistant" device/google/coral/device.mk || echo "PRODUCT_PACKAGES += CameraAssistant" >> device/google/coral/device.mk
    # fi

    # Cấu hình node điều khiển VCam trong init.coral.rc
    if [ -f device/google/coral/init.coral.rc ] && ! grep -q "vcam_pause" device/google/coral/init.coral.rc; then
        cat << 'EOF' >> device/google/coral/init.coral.rc

on post-fs-data
    mkdir /data/local/tmp/vcam 0777 shell shell
    chmod 0777 /data/local/tmp
    write /data/local/tmp/vcam_pause 0
    write /data/local/tmp/vcam_disable 0
    write /data/local/tmp/vcam_mic_disable 0
    write /data/local/tmp/vcam_mic_mix 0
    chmod 0666 /data/local/tmp/vcam_pause
    chmod 0666 /data/local/tmp/vcam_disable
    chmod 0666 /data/local/tmp/vcam_mic_disable
    chmod 0666 /data/local/tmp/vcam_mic_mix
    chmod 0666 /data/local/tmp/vcam.mp4
    chmod 0666 /data/local/tmp/vcam.wav
EOF
    fi
else
    echo -e "\n${CYAN}>> Chế độ Stock 100%: Dọn dẹp sạch mã nguồn về nguyên bản Google...${NC}"
    for clean_repo in frameworks/base system/core device/google/coral device/google/coral-sepolicy; do
        if [ -d "$clean_repo" ]; then
            git -C "$clean_repo" checkout -f 2>/dev/null || true
            git -C "$clean_repo" clean -fd 2>/dev/null || true
        fi
    done
fi

# 6. Thiết lập môi trường và Biên dịch
echo -e "\n${BLUE}>> [6/6] Sắp xếp môi trường và bắt đầu Build AOSP...${NC}"
source build/envsetup.sh
lunch aosp_flame-userdebug

# Tối ưu hóa JVM cho RAM 128GB
export _JAVA_OPTIONS="-Xmx32g"

echo -e "${GREEN}>> Đang build AOSP với ${CPU_CORES} luồng...${NC}"
m -j"${CPU_CORES}"

echo -e "\n${GREEN}==================================================================${NC}"
echo -e "${GREEN}               BUILD ROM STOCK A13 HOÀN TẤT!                      ${NC}"
echo -e "${GREEN}==================================================================${NC}"

# Gom các file image thành phẩm
OUTPUT_DIR="$HOME/pixel4_stock_a13_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"

IMG_SRC="out/target/product/flame"
cp "${IMG_SRC}"/boot.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/dtbo.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/vbmeta.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/super.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/system.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/system_ext.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/vendor.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/product.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/userdata.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/*.zip "$OUTPUT_DIR/" 2>/dev/null || true

# Tạo script flash nhanh bằng fastboot cho người dùng
cat << 'EOF' > "$OUTPUT_DIR/flash-all.bat"
@echo off
echo ========================================================
echo Flashing Stock A13 ROM to Google Pixel 4 (flame)
echo ========================================================
fastboot reboot bootloader
ping 127.0.0.1 -n 5 > nul

if exist boot.img fastboot flash boot boot.img
if exist dtbo.img fastboot flash dtbo dtbo.img
if exist vbmeta.img fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img

if exist super.img (
    fastboot flash super super.img
) else (
    echo Entering fastbootd for dynamic partitions...
    fastboot reboot fastboot
    ping 127.0.0.1 -n 8 > nul
    fastboot flash system system.img
    if exist system_ext.img fastboot flash system_ext system_ext.img
    if exist vendor.img fastboot flash vendor vendor.img
    if exist product.img fastboot flash product product.img
)

if exist userdata.img fastboot flash userdata userdata.img
echo Wiping data...
fastboot -w
echo Rebooting to system...
fastboot reboot
echo DONE!
pause
EOF

cat << 'EOF' > "$OUTPUT_DIR/flash-all.sh"
#!/bin/bash
echo "========================================================"
echo "Flashing Stock A13 ROM to Google Pixel 4 (flame)"
echo "========================================================"
fastboot reboot bootloader
sleep 4

[ -f boot.img ] && fastboot flash boot boot.img
[ -f dtbo.img ] && fastboot flash dtbo dtbo.img
[ -f vbmeta.img ] && fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img

if [ -f super.img ]; then
    fastboot flash super super.img
else
    echo "Entering fastbootd for dynamic partitions..."
    fastboot reboot fastboot
    sleep 6
    [ -f system.img ] && fastboot flash system system.img
    [ -f system_ext.img ] && fastboot flash system_ext system_ext.img
    [ -f vendor.img ] && fastboot flash vendor vendor.img
    [ -f product.img ] && fastboot flash product product.img
fi

[ -f userdata.img ] && fastboot flash userdata userdata.img
fastboot -w
fastboot reboot
echo "DONE!"
EOF
chmod +x "$OUTPUT_DIR/flash-all.sh"

echo -e "Toàn bộ file ROM Stock A13 và tool flash tự động đã được lưu tại:\n${CYAN}${OUTPUT_DIR}${NC}"
ls -lh "$OUTPUT_DIR"
