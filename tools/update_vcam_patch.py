import sys
import os

def main():
    patch_path = os.path.join(os.path.dirname(__file__), '..', 'patches', 'vcam_pixel4.patch')
    temp_renderer_path = os.path.join(os.path.dirname(__file__), '..', 'temp_VcamRenderer.java')

    with open(patch_path, 'r', encoding='utf-8') as f:
        patch_lines = f.readlines()

    # Find boundaries
    cfg_start = None
    ver_start = None
    ren_start = None
    col_start = None

    for i, l in enumerate(patch_lines):
        if 'diff --git a/core/java/android/hardware/camera2/impl/VcamConfig.java' in l:
            cfg_start = i
        elif 'diff --git a/core/java/android/hardware/camera2/impl/VcamLicenseVerifier.java' in l:
            ver_start = i
        elif 'diff --git a/core/java/android/hardware/camera2/impl/VcamRenderer.java' in l:
            ren_start = i
        elif 'diff --git a/core/java/android/hardware/camera2/impl/VcamColorSync.java' in l:
            col_start = i

    assert cfg_start is not None and ver_start is not None, 'VcamConfig bounds not found'
    assert ren_start is not None and col_start is not None, 'VcamRenderer bounds not found'

    # Prepare new VcamConfig.java content
    vcam_config_code = """package android.hardware.camera2.impl;

import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

public class VcamConfig {
    public static final String DISABLE_FLAG = "vcam_disable";
    public static final String MIC_DISABLE_FLAG = "vcam_mic_disable";
    public static final String MIC_MIX_FLAG = "vcam_mic_mix";
    public static final String KYC_FLASH_FLAG = "vcam_kyc_flash";
    public static final String ZOOM_FILE = "vcam_zoom.cfg";
    public static final String PAN_FILE = "vcam_pan.cfg";
    public static final String TARGET_VIDEO = "vcam.mp4";
    public static final String TARGET_AUDIO = "vcam.wav";
    public static final String RESET_FLAG = "vcam_reset";
    public static final String ROTATION_FILE = "vcam_rotation";
    public static final String FACE_DISABLE_FLAG = "vcam_face_disable";
    public static final String FACE_FILE = "vcam_face";
    public static final String RAW_EXP_FILE = "vcam_raw_exp";
    public static final String NOISE_FILE = "vcam_noise";
    public static final String LIGHT_FILE = "vcam_light";
    public static final String STREAM_PORT_FILE = "vcam_stream_port";

    private static final Uri PROVIDER_URI = Uri.parse("content://org.lineageos.camera.assistant.provider");
    private static volatile Bundle sCachedProviderBundle = null;
    private static volatile long sLastProviderCheck = 0;

    private static Bundle getProviderConfig() {
        long now = System.currentTimeMillis();
        if (now - sLastProviderCheck < 80 && sCachedProviderBundle != null) {
            return sCachedProviderBundle;
        }
        sLastProviderCheck = now;
        try {
            Application app = ActivityThread.currentApplication();
            if (app != null) {
                ContentResolver cr = app.getContentResolver();
                if (cr != null) {
                    Bundle b = cr.call(PROVIDER_URI, "getConfig", null, null);
                    if (b != null) {
                        sCachedProviderBundle = b;
                        return b;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return sCachedProviderBundle;
    }

    public static boolean isFaceDisabled() {
        return isFlagActive(FACE_DISABLE_FLAG);
    }

    private static final String[] SEARCH_DIRS = new String[] {
        "/data/local/tmp/",
        "/sdcard/CameraAssistant/",
        "/sdcard/"
    };

    public static final String PAUSE_FLAG = "vcam_pause";

    public static boolean isVcamDisabled() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("disable")) {
            return b.getBoolean("disable", false);
        }
        return isFlagActive(DISABLE_FLAG);
    }

    public static boolean isPaused() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("pause")) {
            return b.getBoolean("pause", false);
        }
        return isFlagActive(PAUSE_FLAG);
    }

    public static boolean isMicDisabled() {
        return isFlagActive(MIC_DISABLE_FLAG);
    }

    public static boolean isMicMix() {
        return isFlagActive(MIC_MIX_FLAG);
    }

    public static boolean isKycFlashEnabled() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("kyc_flash")) {
            return b.getBoolean("kyc_flash", false);
        }
        return isFlagActive(KYC_FLASH_FLAG);
    }

    public static boolean isSwapUv() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("swap_uv")) {
            return b.getBoolean("swap_uv", false);
        }
        return isFlagActive("vcam_swap_uv");
    }

    public static float getBrightnessFactor() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("bright_factor")) {
            float bf = b.getFloat("bright_factor", 1.0f);
            if (bf > 0.05f && bf < 3.0f) return bf;
        }
        if (b != null && b.containsKey("raw_bright")) {
            int rb = b.getInt("raw_bright", 65);
            if (rb > 0) return rb / 65.0f;
        }
        String val = readFileContent(RAW_EXP_FILE);
        if (val.isEmpty()) val = readFileContent("vcam_raw_bright");
        if (!val.isEmpty()) {
            try {
                int rb = Integer.parseInt(val.trim());
                if (rb > 0) return rb / 65.0f;
            } catch (Throwable ignored) {}
        }
        return 1.0f;
    }

    public static File getActiveVideoFile() {
        if (isVcamDisabled()) return null;
        for (String dir : SEARCH_DIRS) {
            File f = new File(dir + TARGET_VIDEO);
            if (f.exists() && f.canRead() && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    private static volatile float sCachedZoom = 1.0f;
    private static volatile long sLastZoomCheck = 0;
    private static volatile float sCachedPanX = 0.0f;
    private static volatile float sCachedPanY = 0.0f;
    private static volatile long sLastPanCheck = 0;

    public static float getZoomScale() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("zoom")) {
            float z = b.getFloat("zoom", 1.0f);
            if (z >= 1.0f && z <= 5.0f) {
                sCachedZoom = z;
                return z;
            }
        }
        long now = System.currentTimeMillis();
        if (now - sLastZoomCheck < 200) return sCachedZoom;
        sLastZoomCheck = now;
        String val = readFileContent("vcam_zoom");
        if (val.isEmpty()) val = readFileContent(ZOOM_FILE);
        if (!val.isEmpty()) {
            try {
                float z = Float.parseFloat(val);
                if (z >= 1.0f && z <= 5.0f) {
                    sCachedZoom = z;
                    return z;
                }
            } catch (Throwable ignored) {}
        }
        sCachedZoom = 1.0f;
        return 1.0f;
    }

    public static float getPanX() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("pan_x")) {
            sCachedPanX = b.getFloat("pan_x", 0.0f);
            sCachedPanY = b.getFloat("pan_y", 0.0f);
            return sCachedPanX;
        }
        long now = System.currentTimeMillis();
        if (now - sLastPanCheck < 200) return sCachedPanX;
        sLastPanCheck = now;
        String valX = readFileContent("vcam_pan_x");
        if (!valX.isEmpty()) {
            try {
                sCachedPanX = Float.parseFloat(valX.trim());
            } catch (Throwable ignored) {}
        }
        String valY = readFileContent("vcam_pan_y");
        if (!valY.isEmpty()) {
            try {
                sCachedPanY = Float.parseFloat(valY.trim());
            } catch (Throwable ignored) {}
        }
        if (valX.isEmpty() && valY.isEmpty()) {
            String val = readFileContent(PAN_FILE);
            if (!val.isEmpty()) {
                try {
                    String[] parts = val.split(",");
                    if (parts.length >= 1) sCachedPanX = Float.parseFloat(parts[0].trim());
                    if (parts.length >= 2) sCachedPanY = Float.parseFloat(parts[1].trim());
                } catch (Throwable ignored) {}
            }
        }
        return sCachedPanX;
    }

    public static float getPanY() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("pan_y")) {
            sCachedPanY = b.getFloat("pan_y", 0.0f);
            return sCachedPanY;
        }
        long now = System.currentTimeMillis();
        if (now - sLastPanCheck < 200) return sCachedPanY;
        getPanX(); // refreshes panX and panY together
        return sCachedPanY;
    }

    public static boolean isFlagActive(String flagName) {
        for (String dir : SEARCH_DIRS) {
            File f = new File(dir + flagName);
            if (f.exists()) {
                if (f.length() == 0) return true;
                String val = readFileContent(flagName);
                if ("1".equals(val)) return true;
                return false;
            }
        }
        return false;
    }

    private static volatile long sCachedResetTs = 0;
    private static volatile long sLastResetCheck = 0;

    public static long getRewindTimestamp() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("reset_ts")) {
            long ts = b.getLong("reset_ts", 0L);
            if (ts > 0) {
                sCachedResetTs = ts;
                return ts;
            }
        }
        long now = System.currentTimeMillis();
        if (now - sLastResetCheck < 150) return sCachedResetTs;
        sLastResetCheck = now;
        String val = readFileContent(RESET_FLAG);
        if (!val.isEmpty()) {
            try {
                sCachedResetTs = Long.parseLong(val.trim());
            } catch (Throwable ignored) {}
        }
        return sCachedResetTs;
    }

    private static volatile int sCachedRotation = 0;
    private static volatile long sLastRotationCheck = 0;

    public static int getRotation() {
        Bundle b = getProviderConfig();
        int userRot = 0;
        if (b != null && b.containsKey("rotation")) {
            userRot = b.getInt("rotation", 0);
            int totalRot = (180 + userRot) % 360;
            if (totalRot < 0) totalRot += 360;
            sCachedRotation = totalRot;
            return totalRot;
        }
        long now = System.currentTimeMillis();
        if (now - sLastRotationCheck < 150) return sCachedRotation;
        sLastRotationCheck = now;
        String val = readFileContent(ROTATION_FILE);
        if (val.isEmpty()) {
            val = readFileContent("vcam_rotate");
        }
        if (!val.isEmpty()) {
            try {
                userRot = Integer.parseInt(val.trim());
            } catch (Throwable ignored) {}
        }
        // Base 180 deg to mirror native_hook camera_hook.cpp behavior exactly
        int totalRot = (180 + userRot) % 360;
        if (totalRot < 0) totalRot += 360;
        sCachedRotation = totalRot;
        return totalRot;
    }

    private static volatile int sCachedNoise = 0;
    private static volatile long sLastNoiseCheck = 0;

    public static int getNoiseLevel() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("noise")) {
            int n = b.getInt("noise", 0);
            if (n < 0) n = 0;
            if (n > 10) n = 10;
            sCachedNoise = n;
            return n;
        }
        long now = System.currentTimeMillis();
        if (now - sLastNoiseCheck < 200) return sCachedNoise;
        sLastNoiseCheck = now;
        String val = readFileContent(NOISE_FILE);
        if (!val.isEmpty()) {
            try {
                int n = Integer.parseInt(val.trim());
                if (n < 0) n = 0;
                if (n > 10) n = 10;
                sCachedNoise = n;
                return n;
            } catch (Throwable ignored) {}
        }
        sCachedNoise = 0;
        return 0;
    }

    private static final float[] sCachedLight = new float[] { 0.0f, 0.0f, 1.2f, 0.0f };
    private static volatile long sLastLightCheck = 0;

    public static float[] getLightParams() {
        Bundle b = getProviderConfig();
        String val = null;
        if (b != null && b.containsKey("light")) {
            val = b.getString("light", "");
        }
        if (val == null || val.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - sLastLightCheck < 200) return sCachedLight;
            sLastLightCheck = now;
            val = readFileContent(LIGHT_FILE);
        }
        if (val != null && !val.isEmpty()) {
            try {
                String[] parts = val.trim().split("[,\\s]+");
                if (parts.length >= 1) {
                    int intensity = Integer.parseInt(parts[0]);
                    if (intensity < -100) intensity = -100;
                    if (intensity > 100) intensity = 100;
                    float posX = 0.0f;
                    float posY = 0.0f;
                    float radius = 1.2f;
                    if (parts.length >= 2) posX = Float.parseFloat(parts[1]);
                    if (parts.length >= 3) posY = Float.parseFloat(parts[2]);
                    if (parts.length >= 4) radius = Float.parseFloat(parts[3]);

                    if (posX < -1.5f) posX = -1.5f; if (posX > 1.5f) posX = 1.5f;
                    if (posY < -1.5f) posY = -1.5f; if (posY > 1.5f) posY = 1.5f;
                    if (radius < 0.2f) radius = 0.2f; if (radius > 3.0f) radius = 3.0f;

                    sCachedLight[0] = posX;
                    sCachedLight[1] = posY;
                    sCachedLight[2] = radius;
                    sCachedLight[3] = intensity / 100.0f;
                    return sCachedLight;
                }
            } catch (Throwable ignored) {}
        }
        sCachedLight[0] = 0.0f;
        sCachedLight[1] = 0.0f;
        sCachedLight[2] = 1.2f;
        sCachedLight[3] = 0.0f;
        return sCachedLight;
    }

    public static int getStreamPort() {
        String val = readFileContent(STREAM_PORT_FILE);
        if (!val.isEmpty()) {
            try {
                int p = Integer.parseInt(val.trim());
                if (p > 1024 && p < 65535) return p;
            } catch (Throwable ignored) {}
        }
        return 7777; // default port for OBS streaming (USB / WiFi)
    }

    private static String readFileContent(String filename) {
        for (String dir : SEARCH_DIRS) {
            File f = new File(dir + filename);
            if (f.exists() && f.canRead()) {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                    String line = br.readLine();
                    br.close();
                    if (line != null) return line.trim();
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }
}"""

    cfg_lines = [l + '\n' for l in vcam_config_code.strip().split('\n')]
    cfg_diff = [
        'diff --git a/core/java/android/hardware/camera2/impl/VcamConfig.java b/core/java/android/hardware/camera2/impl/VcamConfig.java\n',
        'new file mode 100644\n',
        '--- /dev/null\n',
        '+++ b/core/java/android/hardware/camera2/impl/VcamConfig.java\n',
        f'@@ -0,0 +1,{len(cfg_lines)} @@\n'
    ] + ['+' + l for l in cfg_lines]

    # Read and modify VcamRenderer.java
    with open(temp_renderer_path, 'r', encoding='utf-8') as f:
        ren_code = f.read()

    # Update FRAGMENT_SHADER
    old_fs_vars = (
        '        "uniform float uNoiseIntensity;\\n" +\n'
        '        "uniform float uNoiseSeed;\\n" +\n'
        '        "uniform float uAuthKey;\\n" +'
    )

    new_fs_vars = (
        '        "uniform float uNoiseIntensity;\\n" +\n'
        '        "uniform float uNoiseSeed;\\n" +\n'
        '        "uniform float uBrightness;\\n" +\n'
        '        "uniform float uSwapUv;\\n" +\n'
        '        "uniform float uAuthKey;\\n" +'
    )

    assert old_fs_vars in ren_code, 'old_fs_vars not found'
    ren_code = ren_code.replace(old_fs_vars, new_fs_vars, 1)

    old_fs_body = (
        '        "void main() {\\n" +\n'
        '        "  vec4 c = texture2D(sTexture, vTextureCoord);\\n" +\n'
        '        "  if (uLightParams.w != 0.0) {\\n"'
    )

    new_fs_body = (
        '        "void main() {\\n" +\n'
        '        "  vec4 c = texture2D(sTexture, vTextureCoord);\\n" +\n'
        '        "  if (uSwapUv > 0.5) {\\n" +\n'
        '        "    float tmp = c.r;\\n" +\n'
        '        "    c.r = c.b;\\n" +\n'
        '        "    c.b = tmp;\\n" +\n'
        '        "  }\\n" +\n'
        '        "  if (uBrightness > 0.0) {\\n" +\n'
        '        "    c.rgb *= uBrightness;\\n" +\n'
        '        "  }\\n" +\n'
        '        "  if (uLightParams.w != 0.0) {\\n"'
    )

    assert old_fs_body in ren_code, 'old_fs_body not found'
    ren_code = ren_code.replace(old_fs_body, new_fs_body, 1)

    # Update handles declaration
    old_handles = (
        '    private int muNoiseIntensityHandle = 0;\n'
        '    private int muNoiseSeedHandle = 0;\n'
        '    private int muAuthKeyHandle = 0;'
    )

    new_handles = (
        '    private int muNoiseIntensityHandle = 0;\n'
        '    private int muNoiseSeedHandle = 0;\n'
        '    private int muBrightnessHandle = 0;\n'
        '    private int muSwapUvHandle = 0;\n'
        '    private int muAuthKeyHandle = 0;'
    )

    assert old_handles in ren_code, 'old_handles not found'
    ren_code = ren_code.replace(old_handles, new_handles, 1)

    # Update init uniform locations
    old_locs = (
        '            muNoiseIntensityHandle = GLES20.glGetUniformLocation(mProgram, "uNoiseIntensity");\n'
        '            muNoiseSeedHandle = GLES20.glGetUniformLocation(mProgram, "uNoiseSeed");\n'
        '            muAuthKeyHandle = GLES20.glGetUniformLocation(mProgram, "uAuthKey");'
    )

    new_locs = (
        '            muNoiseIntensityHandle = GLES20.glGetUniformLocation(mProgram, "uNoiseIntensity");\n'
        '            muNoiseSeedHandle = GLES20.glGetUniformLocation(mProgram, "uNoiseSeed");\n'
        '            muBrightnessHandle = GLES20.glGetUniformLocation(mProgram, "uBrightness");\n'
        '            muSwapUvHandle = GLES20.glGetUniformLocation(mProgram, "uSwapUv");\n'
        '            muAuthKeyHandle = GLES20.glGetUniformLocation(mProgram, "uAuthKey");'
    )

    assert old_locs in ren_code, 'old_locs not found'
    ren_code = ren_code.replace(old_locs, new_locs, 1)

    # Update renderFrame uniform passing
    old_uniforms = (
        '                        GLES20.glUniform4fv(muLightParamsHandle, 1, lightParams, 0);\n'
        '                        GLES20.glUniform1f(muNoiseIntensityHandle, (float) noiseLevel);\n'
        '                        GLES20.glUniform1f(muNoiseSeedHandle, mNoiseSeed);'
    )

    new_uniforms = (
        '                        GLES20.glUniform4fv(muLightParamsHandle, 1, lightParams, 0);\n'
        '                        GLES20.glUniform1f(muNoiseIntensityHandle, (float) noiseLevel);\n'
        '                        GLES20.glUniform1f(muNoiseSeedHandle, mNoiseSeed);\n'
        '                        GLES20.glUniform1f(muBrightnessHandle, VcamConfig.getBrightnessFactor());\n'
        '                        GLES20.glUniform1f(muSwapUvHandle, VcamConfig.isSwapUv() ? 1.0f : 0.0f);'
    )

    assert old_uniforms in ren_code, 'old_uniforms not found'
    ren_code = ren_code.replace(old_uniforms, new_uniforms, 1)

    ren_lines = [l + '\n' for l in ren_code.strip().split('\n')]
    ren_diff = [
        'diff --git a/core/java/android/hardware/camera2/impl/VcamRenderer.java b/core/java/android/hardware/camera2/impl/VcamRenderer.java\n',
        'new file mode 100644\n',
        '--- /dev/null\n',
        '+++ b/core/java/android/hardware/camera2/impl/VcamRenderer.java\n',
        f'@@ -0,0 +1,{len(ren_lines)} @@\n'
    ] + ['+' + l for l in ren_lines]

    # Construct final patch
    final_lines = (
        patch_lines[:cfg_start] +
        cfg_diff +
        patch_lines[ver_start:ren_start] +
        ren_diff +
        patch_lines[col_start:]
    )

    with open(patch_path, 'w', encoding='utf-8') as f:
        f.writelines(final_lines)

    print('SUCCESS! Updated patches/vcam_pixel4.patch successfully.')
    print('VcamConfig lines:', len(cfg_lines))
    print('VcamRenderer lines:', len(ren_lines))
    print('Total patch lines:', len(final_lines))

if __name__ == '__main__':
    main()
