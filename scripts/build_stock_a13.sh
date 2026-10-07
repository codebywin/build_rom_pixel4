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

prompt_input() {
    local prompt_text="$1"
    local default_value="$2"
    local result=""
    if [ -t 0 ]; then
        read -p "$prompt_text" result
    elif [ -e /dev/tty ]; then
        read -p "$prompt_text" result < /dev/tty 2>/dev/null || true
    fi
    echo "${result:-$default_value}"
}

BUILD_CHOICE="${1:-$BUILD_CHOICE}"

if [ -z "$BUILD_CHOICE" ]; then
    echo -e "\n${YELLOW}==================================================================${NC}"
    echo -e "${YELLOW}         CHỌN BẢN DỰNG ROM CHO PIXEL 4 (CHỈ 1 BƯỚC BẤM)           ${NC}"
    echo -e "${YELLOW}==================================================================${NC}"
    echo "  1) ROM Stock A13 Chuẩn Gốc + VCAM + KÈM GAPPS (CH Play & Play Services) [Mặc định]"
    echo "  2) ROM Stock A13 + VCAM (BỎ QUA GApps / Thuần sạch không có CH Play)"
    echo "  3) ROM Stock A13 NGUYÊN BẢN 100% (Thuần Google gốc, không mod VCam)"
    BUILD_CHOICE=$(prompt_input "Nhập lựa chọn của bạn [1, 2 hoặc 3, mặc định 1]: " "1")
fi

BUILD_CHOICE="${BUILD_CHOICE:-1}"

case "$BUILD_CHOICE" in
    1)
        STOCK_MODE="1"
        GAPPS_MODE="1"
        echo -e "\n${GREEN}>> ĐÃ CHỌN: [1] ROM Stock A13 Chuẩn Gốc + VCAM + KÈM GAPPS (CH Play & Play Services)${NC}"
        ;;
    2)
        STOCK_MODE="1"
        GAPPS_MODE="2"
        echo -e "\n${GREEN}>> ĐÃ CHỌN: [2] ROM Stock A13 + VCAM (BỎ QUA GApps / Thuần sạch)${NC}"
        ;;
    3)
        STOCK_MODE="2"
        GAPPS_MODE="2"
        echo -e "\n${GREEN}>> ĐÃ CHỌN: [3] ROM Stock A13 NGUYÊN BẢN 100% (Thuần Google gốc)${NC}"
        ;;
    *)
        echo -e "\n${YELLOW}>> Lựa chọn không hợp lệ, mặc định chọn [1] ROM Stock A13 + VCAM + KÈM GAPPS${NC}"
        STOCK_MODE="1"
        GAPPS_MODE="1"
        ;;
esac

echo -e "\n${CYAN}==================================================================${NC}"
echo -e "${CYAN}>> CẤU HÌNH BIÊN DỊCH:${NC}"
[ "$STOCK_MODE" == "1" ] && echo -e "   - Nhân VCam: ${GREEN}TÍCH HỢP (Camera1, Camera2, KYC ColorSync Flash, Titan M lock)${NC}" || echo -e "   - Nhân VCam: ${YELLOW}KHÔNG (ROM nguyên bản)${NC}"
[ "$GAPPS_MODE" == "1" ] && echo -e "   - Dịch vụ Google: ${GREEN}CÓ (MindTheGapps: CH Play + Play Services)${NC}" || echo -e "   - Dịch vụ Google: ${GREEN}BỎ QUA (Siêu nhẹ, sạch 100%)${NC}"
echo -e "${CYAN}==================================================================${NC}"

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
unset TEMPORARY_DISABLE_PATH_RESTRICTIONS

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
        local patch_url="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/${REPO_REF}/patches/${patch_name}?v=$(date +%s)"
        local tmp_patch="/tmp/${patch_name}"

        if [ -d "$target_dir" ]; then
            if [ -f "${LOCAL_PATCH_DIR}/${patch_name}" ]; then
                echo -e "   [LOCAL] Dùng patch: ${patch_name} -> ${target_dir}"
                cp "${LOCAL_PATCH_DIR}/${patch_name}" "$tmp_patch"
            else
                echo -e "   [ONLINE] Tải patch mới nhất: ${patch_name} -> ${target_dir}"
                rm -f "$tmp_patch"
                curl -fsSL "$patch_url" -o "$tmp_patch" || true
            fi

            if [ ! -s "$tmp_patch" ]; then
                echo -e "   [${RED}LỖI${NC}] Không tải được patch ${patch_name} (file trống hoặc lỗi mạng)!"
                if [ "$patch_name" == "vcam_pixel4.patch" ]; then exit 1; fi
                return 1
            fi

            sed -i 's/\r$//' "$tmp_patch"

            if git -C "$target_dir" apply --ignore-space-change --ignore-whitespace --check "$tmp_patch" 2>/dev/null; then
                git -C "$target_dir" apply --ignore-space-change --ignore-whitespace "$tmp_patch" && \
                echo -e "   [${GREEN}OK${NC}] Applied ${patch_name} -> ${target_dir}" || true
            else
                # Thử áp dụng bằng 3way
                if git -C "$target_dir" apply --3way --ignore-space-change --ignore-whitespace "$tmp_patch" 2>/dev/null; then
                    echo -e "   [${GREEN}OK-3WAY${NC}] Applied ${patch_name} -> ${target_dir}"
                else
                    echo -e "   [${RED}THẤT BẠI${NC}] Không thể apply ${patch_name} -> ${target_dir}"
                    git -C "$target_dir" apply --ignore-space-change --ignore-whitespace --check "$tmp_patch" || true
                    if [ "$patch_name" == "vcam_pixel4.patch" ]; then
                        echo -e "${RED}[LỖI NGHIÊM TRỌNG] vcam_pixel4.patch không thể áp dụng! Dừng build để tránh tạo ROM không có VCam!${NC}"
                        exit 1
                    fi
                fi
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

    # Kiểm tra xác thực file VCam bắt buộc phải hiện diện trong frameworks/base
    if [ ! -f "frameworks/base/core/java/android/hardware/VcamCamera.java" ]; then
        echo -e "${RED}[LỖI] VcamCamera.java không tồn tại trong frameworks/base! vcam_pixel4.patch chưa được áp dụng thành công. Dừng build!${NC}"
        exit 1
    else
        echo -e "   [${GREEN}XÁC THỰC THÀNH CÔNG${NC}] Native VCam core đã được tích hợp trọn vẹn vào frameworks/base!"
    fi
    apply_patch "frameworks/base" "spoof_locked_bootloader.patch"
    apply_patch "system/core" "init_spoof_bootloader.patch"
    apply_patch "frameworks/base" "disable_flag_secure.patch"
    apply_patch "frameworks/base" "hide_developer_options.patch"
    apply_patch "frameworks/base" "hide_accessibility_services.patch"
    apply_patch "frameworks/base" "bypass_overlay_tapjacking.patch"
    apply_patch "frameworks/base" "hide_sensitive_packages.patch"
    apply_patch "device/google/coral" "sepolicy_vcam_coral.patch"
    apply_patch "device/google/coral-sepolicy" "sepolicy_vcam_coral.patch"

    # Cấu hình AVB 2.0 (Android Verified Boot) để hỗ trợ khóa Bootloader phần cứng (Device state: locked)
    echo -e "   [AVB] Cấu hình Android Verified Boot (AVB 2.0) để hỗ trợ Khóa Bootloader phần cứng (Titan M)..."
    mkdir -p certs
    if [ ! -f certs/avb.pem ]; then
        openssl genrsa -out certs/avb.pem 4096
    fi
    python3 external/avb/avbtool.py extract_public_key --key certs/avb.pem --output certs/avb_custom_key.bin

    sed -i '/BOARD_AVB_ENABLE/d' device/google/coral/BoardConfig.mk 2>/dev/null || true
    sed -i '/BOARD_AVB_KEY_PATH/d' device/google/coral/BoardConfig.mk 2>/dev/null || true
    sed -i '/BOARD_AVB_ALGORITHM/d' device/google/coral/BoardConfig.mk 2>/dev/null || true
    sed -i '/BOARD_AVB_ROLLBACK_INDEX/d' device/google/coral/BoardConfig.mk 2>/dev/null || true
    sed -i '/BOARD_AVB_VBMETA_SYSTEM/d' device/google/coral/BoardConfig.mk 2>/dev/null || true

    cat << 'EOF' >> device/google/coral/BoardConfig.mk

# Hardware AVB 2.0 Signing Configuration for Bootloader Locking
BOARD_AVB_ENABLE := true
BOARD_AVB_KEY_PATH := certs/avb.pem
BOARD_AVB_ALGORITHM := SHA256_RSA4096
BOARD_AVB_ROLLBACK_INDEX := 1788220800

BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := certs/avb.pem
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA4096
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := 1788220800
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 1
EOF

    # Đổi BUILD_ID thành chuẩn Google Stock chính hãng TP1A.221005.002.B2
    if [ -f build/make/core/build_id.mk ]; then
        echo 'BUILD_ID=TP1A.221005.002.B2' > build/make/core/build_id.mk
    fi

    # Đổi nhãn test-keys thành release-keys trong Makefile của AOSP
    sed -i 's/BUILD_KEYS := test-keys/BUILD_KEYS := release-keys/g' build/make/core/Makefile 2>/dev/null || true

    # Loại bỏ triệt để file su (root binary) để app ngân hàng không phát hiện custom ROM / root
    sed -i '/[[:space:]]su[[:space:]]*\\/d' build/make/target/product/base_system.mk 2>/dev/null || true

    # Đổi Brand và Model từ "AOSP on flame" sang chuẩn "Google Pixel 4"
    if [ -f device/google/coral/aosp_flame.mk ]; then
        sed -i 's/PRODUCT_MODEL := AOSP on flame/PRODUCT_MODEL := Pixel 4/g' device/google/coral/aosp_flame.mk 2>/dev/null || true
        sed -i 's/PRODUCT_BRAND := Android/PRODUCT_BRAND := google/g' device/google/coral/aosp_flame.mk 2>/dev/null || true
        sed -i 's/PRODUCT_MANUFACTURER := Android/PRODUCT_MANUFACTURER := Google/g' device/google/coral/aosp_flame.mk 2>/dev/null || true
    fi

    # Cấu hình Fingerprint chính hãng Pixel 4 vào device.mk
    if [ -f device/google/coral/device.mk ]; then
        sed -i '/BUILD_FINGERPRINT/d' device/google/coral/device.mk 2>/dev/null || true
        sed -i '/BUILD_ID/d' device/google/coral/device.mk 2>/dev/null || true
        cat << 'EOF' >> device/google/coral/device.mk

# Google Stock Certified Fingerprint & Properties (100% Gốc)
PRODUCT_BUILD_PROP_OVERRIDES += \
    PRODUCT_NAME=flame \
    TARGET_DEVICE=flame \
    BUILD_ID=TP1A.221005.002.B2 \
    BUILD_DISPLAY_ID=TP1A.221005.002.B2 \
    BUILD_FINGERPRINT="google/flame/flame:13/TP1A.221005.002.B2/9382335:user/release-keys" \
    PRIVATE_BUILD_DESC="flame-user 13 TP1A.221005.002.B2 9382335 release-keys"
EOF
    fi

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
    write /data/local/tmp/vcam_color_sync 1
    write /data/local/tmp/vcam_kyc_flash 1
    chmod 0666 /data/local/tmp/vcam_pause
    chmod 0666 /data/local/tmp/vcam_disable
    chmod 0666 /data/local/tmp/vcam_mic_disable
    chmod 0666 /data/local/tmp/vcam_mic_mix
    chmod 0666 /data/local/tmp/vcam_color_sync
    chmod 0666 /data/local/tmp/vcam_kyc_flash
    chmod 0666 /data/local/tmp/vcam_color_val
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

# Tích hợp Google Play Services & CH Play (MindTheGapps) nếu được chọn
if [ "$GAPPS_MODE" == "1" ]; then
    echo -e "\n${CYAN}>> Đang tích hợp Google Play Services & CH Play (MindTheGapps)...${NC}"
    if [ ! -d "vendor/gapps" ]; then
        echo -e "   [CLONE] Tải MindTheGapps (nhánh tau - Android 13 ARM64)..."
        git clone --depth=1 https://gitlab.com/MindTheGapps/vendor_gapps.git -b tau vendor/gapps
    else
        echo -e "   [EXISTS] Thư mục vendor/gapps đã tồn tại."
    fi

    # Tối ưu siêu nhẹ: Chỉ giữ lại đúng Play Services & CH Play, loại bỏ ELF .so lỗi và bloatware
    if [ -d vendor/gapps/arm64 ]; then
        echo -e "   [LIGHT] Tối ưu hóa siêu nhẹ (Chỉ giữ CH Play & Play Services, loại bỏ ELF lỗi và bloatware)..."
        cat << 'EOF' > vendor/gapps/arm64/arm64-vendor.mk
# MindTheGapps Ultra-Light (Pixel 4 Core GApps: Play Store + Play Services)
PRODUCT_SOONG_NAMESPACES += \
    vendor/gapps/arm64

PRODUCT_PACKAGES += \
    GmsCore \
    Phonesky

$(call inherit-product, vendor/gapps/common/common-vendor.mk)
EOF
    fi

    if [ -f device/google/coral/device.mk ]; then
        if ! grep -q "vendor/gapps/arm64/arm64-vendor.mk" device/google/coral/device.mk; then
            echo -e "   [CONFIG] Thêm MindTheGapps vào device.mk..."
            echo '' >> device/google/coral/device.mk
            echo '# Include MindTheGapps (Google Play Services & CH Play)' >> device/google/coral/device.mk
            echo '$(call inherit-product, vendor/gapps/arm64/arm64-vendor.mk)' >> device/google/coral/device.mk
        fi
    fi

    if [ -f device/google/coral/BoardConfig.mk ]; then
        if ! grep -q "BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES" device/google/coral/BoardConfig.mk; then
            echo -e "   [CONFIG] Cấu hình bỏ qua kiểm tra prebuilt ELF..."
            echo 'BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true' >> device/google/coral/BoardConfig.mk
        fi
    fi
else
    echo -e "\n${YELLOW}>> Bỏ qua tích hợp GApps (ROM thuần không có CH Play).${NC}"
    if [ -d "vendor/gapps" ]; then
        rm -rf vendor/gapps
    fi
    if [ -f device/google/coral/device.mk ]; then
        sed -i '/vendor\/gapps/d' device/google/coral/device.mk 2>/dev/null || true
    fi
fi

# 6. Thiết lập môi trường và Biên dịch
echo -e "\n${BLUE}>> [6/6] Sắp xếp môi trường và bắt đầu Build AOSP...${NC}"
source build/envsetup.sh
lunch aosp_flame-user

# Tối ưu hóa JVM cho RAM 128GB
export _JAVA_OPTIONS="-Xmx32g"

if [ "$STOCK_MODE" == "1" ]; then
    echo -e "${CYAN}>> Đồng bộ và phê duyệt API stubs cho Metalava...${NC}"
    m api-stubs-docs-non-updatable-update-current-api || true
fi

if [ "$GAPPS_MODE" == "1" ]; then
    echo -e "${CYAN}>> Làm sạch thư mục ảnh đĩa phân vùng để tích hợp GApps (installclean)...${NC}"
    m installclean 2>/dev/null || true
fi

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
cp "${IMG_SRC}"/vbmeta_system.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "certs/avb_custom_key.bin" "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/super.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/system.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/system_ext.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/vendor.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/product.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/userdata.img "$OUTPUT_DIR/" 2>/dev/null || true
cp "${IMG_SRC}"/*.zip "$OUTPUT_DIR/" 2>/dev/null || true

# Tạo script flash toàn diện hỗ trợ Khóa Bootloader phần cứng thật (Titan M)
cat << 'EOF' > "$OUTPUT_DIR/flash-all.bat"
@echo off
echo ========================================================
echo Flashing Signed Stock A13 ROM to Google Pixel 4 (flame)
echo Support Genuine Hardware Bootloader Lock (Titan M)
echo ========================================================
fastboot reboot bootloader
ping 127.0.0.1 -n 5 > nul

if exist avb_custom_key.bin (
    echo [1/6] Nap khoa Custom Root of Trust vao chip Titan M...
    fastboot erase avb_custom_key
    fastboot flash avb_custom_key avb_custom_key.bin
)

echo [2/6] Nap boot, dtbo va cac phan vung xac thuc AVB 2.0 (Verity BAT)...
if exist boot.img fastboot flash boot boot.img
if exist dtbo.img fastboot flash dtbo dtbo.img
if exist vbmeta.img fastboot flash vbmeta vbmeta.img
if exist vbmeta_system.img fastboot flash vbmeta_system vbmeta_system.img

echo [3/6] Nap cac phan vung he thong...
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
echo [4/6] Wiping userdata...
fastboot -w
echo [5/6] Khoi dong may de kiem tra boot truoc khi khoa...
fastboot reboot
echo ========================================================
echo [6/6] Sau khi may khoi dong vao man hinh Android OK,
echo hay chay tiep file lock-bootloader.bat de KHOA BOOTLOADER THAT!
echo ========================================================
pause
EOF

cat << 'EOF' > "$OUTPUT_DIR/lock-bootloader.bat"
@echo off
echo ========================================================
echo KHOA BOOTLOADER PHAN CUNG (GENUINE HARDWARE LOCK)
echo Google Pixel 4 (flame) - Chip Bao Mat Titan M
echo ========================================================
echo [CHU Y]: Chi chay script nay SAU KHI da chay flash-all.bat
echo va dien thoai da khoi dong vao Android thanh cong!
echo.
pause
fastboot reboot bootloader
ping 127.0.0.1 -n 5 > nul
echo.
echo Dang gui lenh khoa Bootloader toi Titan M...
fastboot flashing lock
echo.
echo ========================================================
echo TREN MAN HINH PIXEL 4 LUC NAY:
echo 1. Dung phim Am luong de chon dong "LOCK THE BOOTLOADER"
echo 2. Nhan phim Nguon (Power) de xac nhan!
echo.
echo May se hien canh bao mau vang (Yellow State):
echo "Your device is loading a different operating system..."
echo Va may se tu dong boot vao Android voi Bootloader LOCKED 100%!
echo (Vao Fastboot kiem tra se thay: Device state: locked mau xanh!)
echo ========================================================
pause
EOF

cat << 'EOF' > "$OUTPUT_DIR/flash-all.sh"
#!/bin/bash
echo "========================================================"
echo "Flashing Signed Stock A13 ROM to Google Pixel 4 (flame)"
echo "Support Genuine Hardware Bootloader Lock (Titan M)"
echo "========================================================"
fastboot reboot bootloader
sleep 4

if [ -f avb_custom_key.bin ]; then
    echo "[1/6] Enrolling Custom Root of Trust to Titan M..."
    fastboot erase avb_custom_key
    fastboot flash avb_custom_key avb_custom_key.bin
fi

echo "[2/6] Flashing boot, dtbo and AVB partitions..."
[ -f boot.img ] && fastboot flash boot boot.img
[ -f dtbo.img ] && fastboot flash dtbo dtbo.img
[ -f vbmeta.img ] && fastboot flash vbmeta vbmeta.img
[ -f vbmeta_system.img ] && fastboot flash vbmeta_system vbmeta_system.img

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
echo "DONE! Boot into Android to verify, then run fastboot flashing lock!"
EOF
chmod +x "$OUTPUT_DIR/flash-all.sh"

echo -e "Toàn bộ file ROM Stock A13 và tool flash tự động đã được lưu tại:\n${CYAN}${OUTPUT_DIR}${NC}"
ls -lh "$OUTPUT_DIR"
