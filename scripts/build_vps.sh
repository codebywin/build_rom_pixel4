#!/usr/bin/env bash
# ==============================================================================
# Script tự động chuẩn bị môi trường và Build ROM cho Google Pixel 4 (flame)
# Chạy trực tiếp trên VPS / Server Ubuntu (Khuyên dùng Ubuntu 20.04 / 22.04 LTS)
# ==============================================================================

set -eo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}       TOOL BUILD ROM CHO GOOGLE PIXEL 4 TRÊN VPS UBUNTU           ${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Kiểm tra thông tin phần cứng
echo -e "\n${BLUE}>> [1/6] Kiểm tra phần cứng VPS...${NC}"
CPU_CORES=$(nproc)
RAM_TOTAL=$(free -m | awk '/^Mem:/{print $2}')
DISK_FREE_GB=$(df -BG . | awk 'NR==2 {print $4}' | sed 's/G//')

echo -e "   - CPU Cores   : ${GREEN}${CPU_CORES} cores${NC}"
echo -e "   - RAM         : ${GREEN}$((RAM_TOTAL / 1024)) GB${NC}"
echo -e "   - Ổ cứng trống: ${GREEN}${DISK_FREE_GB} GB${NC}"

if [ "$DISK_FREE_GB" -lt 200 ]; then
    echo -e "${YELLOW}[CẢNH BÁO] Dung lượng ổ cứng trống dưới 200GB (hiện có ${DISK_FREE_GB}GB). Quá trình sync và build AOSP có thể bị tràn đĩa!${NC}"
    read -p "Bạn có muốn tiếp tục không? (y/N): " confirm_disk
    if [[ ! "$confirm_disk" =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 2. Cài đặt các gói phụ thuộc trên Ubuntu
echo -e "\n${BLUE}>> [2/6] Cập nhật và cài đặt thư viện cần thiết...${NC}"
sudo apt update
sudo apt install -y \
    git-core gnupg flex bison build-essential zip curl zlib1g-dev \
    libc6-dev-i386 libncurses5 x11proto-core-dev libx11-dev lib32z1-dev \
    libgl1-mesa-dev libxml2-utils xsltproc unzip fontconfig libssl-dev \
    python3 python-is-python3 rsync bc ccache libtinfo5 \
    libncurses5-dev libncursesw5-dev liblz4-tool libssl-dev

# Khắc phục symbolic link thư viện ncurses trên Ubuntu 22.04+
sudo ln -sf /usr/lib/x86_64-linux-gnu/libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5 2>/dev/null || true
sudo ln -sf /usr/lib/x86_64-linux-gnu/libtinfo.so.6 /usr/lib/x86_64-linux-gnu/libtinfo.so.5 2>/dev/null || true

# 3. Cài đặt công cụ 'repo' và thiết lập Git
echo -e "\n${BLUE}>> [3/6] Cấu hình công cụ Repo và Git...${NC}"
mkdir -p ~/bin
if ! command -v repo &>/dev/null; then
    curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
    chmod a+x ~/bin/repo
fi
export PATH=~/bin:$PATH
if ! grep -q 'export PATH=~/bin:$PATH' ~/.bashrc; then
    echo 'export PATH=~/bin:$PATH' >> ~/.bashrc
fi

# Đảm bảo Git đã có cấu hình danh tính
if [ -z "$(git config --global user.name)" ]; then
    git config --global user.name "Pixel4 Builder"
fi
if [ -z "$(git config --global user.email)" ]; then
    git config --global user.email "builder@pixel4.local"
fi

# Bật ccache để tăng tốc các lần build tiếp theo
export USE_CCACHE=1
export CCACHE_EXEC=/usr/bin/ccache
ccache -M 50G

# 4. Chọn chế độ build
echo -e "\n${BLUE}>> [4/6] Chọn loại ROM bạn muốn build:${NC}"
echo "  1) LineageOS 20 (Android 13) + Tích hợp VCam Mod & CameraAssistant (Khuyên dùng - Full driver từ TheMuppets)"
echo "  2) Google AOSP Stock (Android 13 - android-13.0.0_r13) + Driver gốc từ Google Developer"
echo "  3) LineageOS 21 (Android 14) + VCam Mod"
read -p "Lựa chọn của bạn [1, 2 hoặc 3, mặc định 1]: " BUILD_CHOICE
BUILD_CHOICE="${BUILD_CHOICE:-1}"

WORKDIR="$HOME/pixel4_build"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

# 5. Khởi tạo mã nguồn và đồng bộ
echo -e "\n${BLUE}>> [5/6] Khởi tạo mã nguồn và tải code...${NC}"

if [ "$BUILD_CHOICE" == "2" ]; then
    # CHẾ ĐỘ AOSP STOCK
    echo -e "${CYAN}>> Khởi tạo Google AOSP Stock (android-13.0.0_r13)...${NC}"
    repo init -u https://android.googlesource.com/platform/manifest -b android-13.0.0_r13 --depth=1
    
    echo -e "${CYAN}>> Syncing source code... (Đang tải bằng ${CPU_CORES} luồng)${NC}"
    repo sync -c -j"${CPU_CORES}" --no-clone-bundle --no-tags --force-sync

    # Tải Driver độc quyền Pixel 4
    echo -e "${CYAN}>> Tải Driver độc quyền Pixel 4 từ Google...${NC}"
    if [ ! -d "vendor/google_devices/flame" ]; then
        curl -O https://dl.google.com/dl/android/aosp/google_devices-flame-tp1a.221005.002.b2-c0e86b24.tgz
        tar -xvzf google_devices-flame-*.tgz
        printf "I ACCEPT\n" | ./extract-google_devices-flame.sh || ./extract-google_devices-flame.sh
        rm -f google_devices-flame-*.tgz extract-google_devices-flame.sh

        curl -O https://dl.google.com/dl/android/aosp/qcom-flame-tp1a.221005.002.b2-a63b02e9.tgz
        tar -xvzf qcom-flame-*.tgz
        printf "I ACCEPT\n" | ./extract-qcom-flame.sh || ./extract-qcom-flame.sh
        rm -f qcom-flame-*.tgz extract-qcom-flame.sh
    fi

    TARGET_LUNCH="aosp_flame-userdebug"
    BUILD_CMD="m"

else
    # CHẾ ĐỘ LINEAGEOS
    LOS_BRANCH="lineage-20"
    MANIFEST_URL="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/manifests/flame_los20.xml"
    if [ "$BUILD_CHOICE" == "3" ]; then
        LOS_BRANCH="lineage-21"
        MANIFEST_URL="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/manifests/flame_los21.xml"
    fi

    echo -e "${CYAN}>> Khởi tạo LineageOS branch ${LOS_BRANCH}...${NC}"
    repo init -u https://github.com/LineageOS/android.git -b "${LOS_BRANCH}" --git-lfs --depth=1

    mkdir -p .repo/local_manifests
    curl -sL "${MANIFEST_URL}" -o .repo/local_manifests/local_manifest.xml

    echo -e "${CYAN}>> Syncing source code... (Đang tải bằng ${CPU_CORES} luồng)${NC}"
    repo sync -c -j"${CPU_CORES}" --no-clone-bundle --no-tags --force-sync

    TARGET_LUNCH="lineage_flame-userdebug"
    BUILD_CMD="mka bacon"
fi

# Áp dụng các Patch tùy biến (VCam, Hide Developer, CameraAssistant...)
echo -e "\n${CYAN}>> Áp dụng các bản vá Patch từ repository...${NC}"
REPO_REF="main"
apply_patch() {
    local target_dir="$1"
    local patch_name="$2"
    local patch_url="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/patches/${patch_name}"
    local tmp_patch="/tmp/${patch_name}"

    if [ -d "$target_dir" ]; then
        curl -sL "$patch_url" > "$tmp_patch"
        sed -i 's/\r$//' "$tmp_patch"
        if git -C "$target_dir" apply --ignore-space-change --ignore-whitespace --check "$tmp_patch" 2>/dev/null; then
            git -C "$target_dir" apply --ignore-space-change --ignore-whitespace "$tmp_patch" 2>/dev/null && \
            echo -e "   [${GREEN}OK${NC}] Applied ${patch_name} -> ${target_dir}" || true
        else
            echo -e "   [${YELLOW}SKIP${NC}] ${patch_name} (đã apply hoặc không khớp branch)"
        fi
    fi
}

apply_patch "frameworks/base" "vcam_pixel4.patch"
apply_patch "frameworks/base" "spoof_locked_bootloader.patch"
apply_patch "system/core" "init_spoof_bootloader.patch"
apply_patch "frameworks/base" "disable_flag_secure.patch"
apply_patch "frameworks/base" "hide_developer_options.patch"
apply_patch "packages/providers/SettingsProvider" "hide_settings_provider.patch"
apply_patch "frameworks/base" "hide_accessibility_services.patch"
apply_patch "frameworks/base" "bypass_overlay_tapjacking.patch"
apply_patch "frameworks/base" "hide_sensitive_packages.patch"

# Cài đặt CameraAssistant App làm system app nếu có thư mục packages/apps
if [ -d "packages/apps" ]; then
    mkdir -p packages/apps/CameraAssistant
    curl -sL "https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/org.lineageos.camera.assistant/CameraAssistant.apk" > packages/apps/CameraAssistant/CameraAssistant.apk
    curl -sL "https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/patches/CameraAssistant_Android.bp" > packages/apps/CameraAssistant/Android.bp
    if [ -f device/google/coral/device.mk ]; then
        grep -q "CameraAssistant" device/google/coral/device.mk || echo "PRODUCT_PACKAGES += CameraAssistant" >> device/google/coral/device.mk
    fi
fi

# 6. Biên dịch ROM
echo -e "\n${BLUE}>> [6/6] Bắt đầu quá trình biên dịch (Compiling)...${NC}"
source build/envsetup.sh
lunch "$TARGET_LUNCH"

# Tối ưu hóa bộ nhớ cho 128GB RAM
export _JAVA_OPTIONS="-Xmx32g"

echo -e "${GREEN}>> Đang biên dịch với lệnh: ${BUILD_CMD} -j${CPU_CORES}${NC}"
$BUILD_CMD -j"${CPU_CORES}"

echo -e "\n${GREEN}==================================================================${NC}"
echo -e "${GREEN}                    BIÊN DỊCH THÀNH CÔNG!                         ${NC}"
echo -e "${GREEN}==================================================================${NC}"

OUTPUT_DIR="$HOME/pixel4_output_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"
find out/target/product/flame/ -maxdepth 1 \( -name "*.zip" -o -name "*.img" \) -exec cp {} "$OUTPUT_DIR/" \;

echo -e "File ROM thành phẩm đã được copy ra: ${CYAN}${OUTPUT_DIR}${NC}"
ls -lh "$OUTPUT_DIR"
