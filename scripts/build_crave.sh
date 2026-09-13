#!/usr/bin/env bash
set -e

echo "=========================================================="
echo " Starting Pixel 4 Build Script on Crave Devspace"
echo " Date: $(date)"
echo "=========================================================="

LUNCH_COMMAND="${1:-lunch lineage_flame-userdebug}"
BUILD_COMMAND="${2:-mka bacon}"
LOCAL_MANIFEST_URL="${3:-https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/manifests/flame_los20.xml}"
LOCAL_MANIFEST_BRANCH="${4:-lineage-20}"
BUILD_USERNAME="${5:-codebywin}"
REMOVALS="${6:-}"
BUILD_DIFFERENT_ROM="${7:-echo 'Build Starting!'}"

echo ">> Configuration:"
echo "   User:                  $BUILD_USERNAME"
echo "   Lunch:                 $LUNCH_COMMAND"
echo "   Build Command:         $BUILD_COMMAND"
echo "   Local Manifest URL:    $LOCAL_MANIFEST_URL"
echo "   Local Manifest Branch: $LOCAL_MANIFEST_BRANCH"
echo "   Removals:              $REMOVALS"

# 1. Clean manifests and removals if specified
if [ -n "$REMOVALS" ]; then
    echo ">> Removing specified folders: $REMOVALS"
    rm -rf .repo/local_manifests/ $REMOVALS
else
    rm -rf .repo/local_manifests/
fi

# 2. Local manifest & custom repo init
echo ">> Initializing repo & manifest..."
if [ -n "$BUILD_DIFFERENT_ROM" ] && [ "$BUILD_DIFFERENT_ROM" != "skip" ] && [ "$BUILD_DIFFERENT_ROM" != "echo 'Build Starting!'" ]; then
    echo ">> Running repo init override: $BUILD_DIFFERENT_ROM"
    eval "$BUILD_DIFFERENT_ROM" || true
fi

mkdir -p .repo/local_manifests
if [[ "$LOCAL_MANIFEST_URL" =~ \.xml(/)?$ ]]; then
    url="${LOCAL_MANIFEST_URL%/}"
    echo ">> Downloading local manifest from $url..."
    curl -sL "$url" -o .repo/local_manifests/local_manifest.xml
elif [[ "$LOCAL_MANIFEST_URL" =~ ^http || "$LOCAL_MANIFEST_URL" =~ ^git@ ]]; then
    echo ">> Cloning local manifest from $LOCAL_MANIFEST_URL (branch: $LOCAL_MANIFEST_BRANCH)..."
    git clone "$LOCAL_MANIFEST_URL" --depth 1 -b "$LOCAL_MANIFEST_BRANCH" .repo/local_manifests
elif [ -n "$LOCAL_MANIFEST_URL" ]; then
    echo ">> Running local manifest command: $LOCAL_MANIFEST_URL"
    eval "$LOCAL_MANIFEST_URL" || true
fi

# 3. Resync repositories
echo ">> Syncing repositories..."
if [ -f /usr/bin/resync ]; then
    /usr/bin/resync || true
else
    /opt/crave/resync.sh || true
fi

# 4. Set build environment
echo ">> Setting up build environment..."
export BUILD_USERNAME="$BUILD_USERNAME"
export BUILD_HOSTNAME="crave"
export WITH_DEXPREOPT=false
export WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=false

sudo apt-get update && sudo apt-get install -y libncurses5 libtinfo5 || true
sudo ln -sf /usr/lib/x86_64-linux-gnu/libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5 2>/dev/null || true
sudo ln -sf /usr/lib/x86_64-linux-gnu/libtinfo.so.6 /usr/lib/x86_64-linux-gnu/libtinfo.so.5 2>/dev/null || true
sudo ln -sf /lib/x86_64-linux-gnu/libncurses.so.6 /lib/x86_64-linux-gnu/libncurses.so.5 2>/dev/null || true
sudo ln -sf /lib/x86_64-linux-gnu/libtinfo.so.6 /lib/x86_64-linux-gnu/libtinfo.so.5 2>/dev/null || true

# 5. Clean git repositories thoroughly (reset tracked and remove untracked files) to prevent patch conflicts
echo ">> Cleaning git repos before patching..."
git -C frameworks/base reset --hard HEAD 2>/dev/null || true
git -C frameworks/base clean -fd 2>/dev/null || true

git -C system/core reset --hard HEAD 2>/dev/null || true
git -C system/core clean -fd 2>/dev/null || true

git -C device/google/coral reset --hard HEAD 2>/dev/null || true
git -C device/google/coral clean -fd 2>/dev/null || true

git -C build/make reset --hard HEAD 2>/dev/null || true
git -C build/make clean -fd 2>/dev/null || true

git -C packages/services/Telephony reset --hard HEAD 2>/dev/null || true
git -C packages/services/Telephony clean -fd 2>/dev/null || true

# 6. Configure BoardConfig.mk
if [ -f device/google/coral/BoardConfig.mk ]; then
    echo "WITH_DEXPREOPT := false" >> device/google/coral/BoardConfig.mk
    echo "WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY := false" >> device/google/coral/BoardConfig.mk
fi

# Helper function to apply a patch safely
apply_patch() {
    local target_dir="$1"
    local patch_name="$2"
    local patch_url="https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/patches/${patch_name}"
    local tmp_patch="/tmp/${patch_name}"

    echo ">> Fetching patch: ${patch_name} -> ${target_dir}"
    curl -sL "$patch_url" > "$tmp_patch"
    sed -i 's/\r$//' "$tmp_patch"

    if git -C "$target_dir" apply --ignore-space-change --ignore-whitespace --check "$tmp_patch" 2>/dev/null; then
        if git -C "$target_dir" apply --ignore-space-change --ignore-whitespace "$tmp_patch" 2>/dev/null; then
            echo "   [SUCCESS] Applied ${patch_name}"
        else
            echo "   [WARNING] Patch ${patch_name} check passed but apply failed, continuing..."
        fi
    else
        echo "   [WARNING] Patch ${patch_name} check failed or already applied, skipping."
    fi
}

# 7. Apply all patches
echo ">> Applying system & security patches..."
apply_patch "frameworks/base" "vcam_pixel4.patch"
apply_patch "device/google/coral" "sepolicy_vcam_coral.patch"
apply_patch "frameworks/base" "spoof_locked_bootloader.patch"
apply_patch "system/core" "init_spoof_bootloader.patch"
apply_patch "frameworks/base" "disable_flag_secure.patch"
apply_patch "frameworks/base" "hide_developer_options.patch"
apply_patch "frameworks/base" "hide_accessibility_services.patch"
apply_patch "frameworks/base" "bypass_overlay_tapjacking.patch"
apply_patch "frameworks/base" "hide_sensitive_packages.patch"
apply_patch "frameworks/base" "fix_acore_sqlite_tokenizer.patch"

# 8. Battery charging control & sepolicy fixes
echo ">> Configuring health HAL and charging deadline..."
find device/google/coral -name "init.*.rc" -exec sed -i '/panel0-backlight/a \    chown system system /sys/class/power_supply/battery/charge_deadline\n    chmod 0664 /sys/class/power_supply/battery/charge_deadline' {} + 2>/dev/null || true
mkdir -p device/google/coral/sepolicy/vendor
echo "allow hal_lineage_health_default sysfs_batteryinfo:file rw_file_perms;" >> device/google/coral/sepolicy/vendor/hal_lineage_health_default.te
find . -name "vendor.lineage.health-service.default.rc" -exec sed -i '/service vendor.lineage_health/a \    interface aidl vendor.lineage.health.IChargingControl/default' {} + 2>/dev/null || true

# 9. Release keys spoof in build system
echo ">> Setting release-keys in build/make..."
find build/make -name "sysprop.mk" -exec sed -i 's/BUILD_KEYS := test-keys/BUILD_KEYS := release-keys/g' {} + 2>/dev/null || true
find build/make -name "Makefile" -exec sed -i 's/BUILD_KEYS := test-keys/BUILD_KEYS := release-keys/g' {} + 2>/dev/null || true

# 10. Install CameraAssistant system app
echo ">> Setting up CameraAssistant app..."
mkdir -p packages/apps/CameraAssistant
curl -sL https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/org.lineageos.camera.assistant/CameraAssistant.apk > packages/apps/CameraAssistant/CameraAssistant.apk
curl -sL https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/patches/CameraAssistant_Android.bp > packages/apps/CameraAssistant/Android.bp
if [ -f device/google/coral/device.mk ]; then
    echo "PRODUCT_PACKAGES += CameraAssistant" >> device/google/coral/device.mk
fi
if [ -f device/google/coral/lineage_flame.mk ]; then
    echo "PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += %/CameraAssistant.apk" >> device/google/coral/lineage_flame.mk
fi
if [ -f device/google/coral/lineage_coral.mk ]; then
    echo "PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += %/CameraAssistant.apk" >> device/google/coral/lineage_coral.mk
fi

# 11. Fix telephony permissions
echo ">> Fixing telephony permissions..."
(curl -sL https://raw.githubusercontent.com/codebywin/build_rom_pixel4/main/patches/fix_telephony.py | python3) 2>/dev/null || true

# 12. Setup VCAM runtime control nodes in init.coral.rc
echo ">> Setting up VCAM control nodes in init.coral.rc..."
if [ -f device/google/coral/init.coral.rc ]; then
    cat << 'EOF' >> device/google/coral/init.coral.rc

on post-fs-data
    mkdir /data/local/tmp/vcam 0777 shell shell
    chmod 0777 /data/local/tmp
    write /data/local/tmp/vcam_pause 0
    write /data/local/tmp/vcam_disable 0
    write /data/local/tmp/vcam_mic_disable 0
    write /data/local/tmp/vcam_mic_mix 0
    write /data/local/tmp/vcam_kyc_flash 0
    write /data/local/tmp/vcam_color_sync 0
    write /data/local/tmp/vcam_rotation 0
    write /data/local/tmp/vcam_zoom 1.0
    write /data/local/tmp/vcam_pan_x 0.0
    write /data/local/tmp/vcam_pan_y 0.0
    write /data/local/tmp/vcam_pan.cfg 0.0,0.0
    write /data/local/tmp/vcam_mic_boost 1.0
    write /data/local/tmp/vcam_color_val normal
    write /data/local/tmp/vcam_reset 0
    chmod 0666 /data/local/tmp/vcam_pause
    chmod 0666 /data/local/tmp/vcam_disable
    chmod 0666 /data/local/tmp/vcam_mic_disable
    chmod 0666 /data/local/tmp/vcam_mic_mix
    chmod 0666 /data/local/tmp/vcam_kyc_flash
    chmod 0666 /data/local/tmp/vcam_color_sync
    chmod 0666 /data/local/tmp/vcam_rotation
    chmod 0666 /data/local/tmp/vcam_zoom
    chmod 0666 /data/local/tmp/vcam_pan_x
    chmod 0666 /data/local/tmp/vcam_pan_y
    chmod 0666 /data/local/tmp/vcam_pan.cfg
    chmod 0666 /data/local/tmp/vcam_mic_boost
    chmod 0666 /data/local/tmp/vcam_color_val
    chmod 0666 /data/local/tmp/vcam_reset
    chmod 0666 /data/local/tmp/vcam.mp4
    chmod 0666 /data/local/tmp/vcam.wav
EOF
fi

# 13. Source build environment and compile
echo ">> Sourcing build environment..."
source build/envsetup.sh
export LINEAGE_BUILDTYPE=RELEASE

echo ">> Running lunch: $LUNCH_COMMAND"
eval "$LUNCH_COMMAND"

echo ">> Cleaning install artifacts: make installclean..."
make installclean || true

echo ">> Cleaning stale kernel obj to prevent Ninja restat timestamp conflicts..."
rm -rf out/target/product/*/obj/KERNEL_OBJ
rm -rf out/target/product/*/obj/DTBO_OBJ
rm -rf out/target/product/*/obj/PACKAGING/depmod*

echo ">> Starting compilation: $BUILD_COMMAND"
eval "$BUILD_COMMAND"

echo "=========================================================="
echo " Compilation Finished Successfully!"
echo " Date: $(date)"
echo "=========================================================="
