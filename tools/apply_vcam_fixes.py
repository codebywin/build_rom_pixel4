import os, sys

def update_vcam_config():
    path = os.path.join('temp_patch_src', 'VcamConfig.java')
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Use reflection for ActivityThread.currentApplication()
    content = content.replace("import android.app.ActivityThread;\n", "")
    old_call = """            Application app = ActivityThread.currentApplication();"""
    new_call = """            Application app = null;
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                java.lang.reflect.Method m = atClass.getMethod("currentApplication");
                app = (Application) m.invoke(null);
            } catch (Throwable ignored) {}"""
    if old_call in content:
        content = content.replace(old_call, new_call)

    target = """    public static boolean isKycFlashEnabled() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("kyc_flash")) {
            return b.getBoolean("kyc_flash", false);
        }
        return isFlagActive(KYC_FLASH_FLAG);
    }"""

    replacement = """    public static boolean isKycFlashEnabled() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("kyc_flash")) {
            return b.getBoolean("kyc_flash", false);
        }
        return isFlagActive(KYC_FLASH_FLAG);
    }

    public static boolean isColorSyncEnabled() {
        Bundle b = getProviderConfig();
        if (b != null && b.containsKey("color_sync")) {
            return b.getBoolean("color_sync", false);
        }
        return isFlagActive("vcam_color_sync");
    }"""

    if target in content and "isColorSyncEnabled" not in content:
        content = content.replace(target, replacement)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print("Updated VcamConfig.java successfully.")
    else:
        print("VcamConfig.java: already updated or target not found.")

def update_vcam_renderer():
    path = os.path.join('temp_patch_src', 'VcamRenderer.java')
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Update FRAGMENT_SHADER
    old_fs = """    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\\n" +
        "precision mediump float;\\n" +
        "varying vec2 vTextureCoord;\\n" +
        "uniform samplerExternalOES sTexture;\\n" +
        "uniform vec4 uFlashColor;\\n" +
        "uniform vec4 uLightParams;\\n" +
        "uniform float uNoiseIntensity;\\n" +
        "uniform float uNoiseSeed;\\n" +
        "uniform float uBrightness;\\n" +
        "uniform float uSwapUv;\\n" +
        "uniform float uAuthKey;\\n" +
        "float rand(vec2 co, float seed) {\\n" +
        "  return fract(sin(dot(co * (seed + 1.0), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\\n" +
        "}\\n" +
        "void main() {\\n" +
        "  vec4 c = texture2D(sTexture, vTextureCoord);\\n" +
        "  if (uSwapUv > 0.5) {\\n" +
        "    float tmp = c.r;\\n" +
        "    c.r = c.b;\\n" +
        "    c.b = tmp;\\n" +
        "  }\\n" +
        "  if (uBrightness > 0.0) {\\n" +
        "    c.rgb *= uBrightness;\\n" +
        "  }\\n" +
        "  if (uLightParams.w != 0.0) {\\n" +
        "    vec2 ndc = vTextureCoord * 2.0 - 1.0;\\n" +
        "    vec2 diff = (ndc - uLightParams.xy) / max(uLightParams.z, 0.1);\\n" +
        "    float d2 = dot(diff, diff);\\n" +
        "    float lightMult = uLightParams.w * 0.85;\\n" +
        "    float dl = (1.0 - d2) * lightMult;\\n" +
        "    if (uLightParams.w > 0.0) {\\n" +
        "      dl = clamp(dl, -uLightParams.w * 0.60, uLightParams.w * 0.85);\\n" +
        "    } else {\\n" +
        "      dl = clamp(dl, uLightParams.w * 0.85, -uLightParams.w * 0.60);\\n" +
        "    }\\n" +
        "    c.rgb += vec3(dl);\\n" +
        "  }\\n" +
        "  if (uNoiseIntensity > 0.0) {\\n" +
        "    float n = rand(vTextureCoord, uNoiseSeed) * (uNoiseIntensity * 0.0125);\\n" +
        "    c.rgb += vec3(n);\\n" +
        "  }\\n" +
        "  c.rgb = clamp(c.rgb, 0.0, 1.0);\\n" +
        "  if (uFlashColor.a > 0.0) {\\n" +
        "    vec2 ndc = vTextureCoord * 2.0 - 1.0;\\n" +
        "    float radial = clamp(1.0 - ndc.x * ndc.x * 0.60, 0.35, 1.0);\\n" +
        "    vec3 tinted = mix(c.rgb, uFlashColor.rgb, uFlashColor.a * radial);\\n" +
        "    gl_FragColor = vec4(tinted, c.a);\\n" +
        "  } else {\\n" +
        "    gl_FragColor = c;\\n" +
        "  }\\n" +
        "  gl_FragColor.rgb *= uAuthKey;\\n" +
        "}\\n";"""

    new_fs = """    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\\n" +
        "precision highp float;\\n" +
        "varying vec2 vTextureCoord;\\n" +
        "uniform samplerExternalOES sTexture;\\n" +
        "uniform vec4 uFlashColor;\\n" +
        "uniform vec4 uLightParams;\\n" +
        "uniform float uNoiseIntensity;\\n" +
        "uniform float uNoiseSeed;\\n" +
        "uniform float uBrightness;\\n" +
        "uniform float uSwapUv;\\n" +
        "uniform float uAuthKey;\\n" +
        "float rand(vec2 co, float seed) {\\n" +
        "  return fract(sin(dot(co * (seed + 1.0), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\\n" +
        "}\\n" +
        "void main() {\\n" +
        "  vec4 c = texture2D(sTexture, vTextureCoord);\\n" +
        "  if (uSwapUv > 0.5) {\\n" +
        "    float tmp = c.r;\\n" +
        "    c.r = c.b;\\n" +
        "    c.b = tmp;\\n" +
        "  }\\n" +
        "  if (uBrightness > 0.0 && abs(uBrightness - 1.0) > 0.001) {\\n" +
        "    c.rgb *= uBrightness;\\n" +
        "  }\\n" +
        "  if (uLightParams.w != 0.0) {\\n" +
        "    vec2 ndc = vTextureCoord * 2.0 - 1.0;\\n" +
        "    vec2 diff = (ndc - uLightParams.xy) / max(uLightParams.z, 0.1);\\n" +
        "    float d2 = dot(diff, diff);\\n" +
        "    float lightMult = uLightParams.w * 0.85;\\n" +
        "    float dl = (1.0 - d2) * lightMult;\\n" +
        "    if (uLightParams.w > 0.0) {\\n" +
        "      dl = clamp(dl, -uLightParams.w * 0.60, uLightParams.w * 0.85);\\n" +
        "    } else {\\n" +
        "      dl = clamp(dl, uLightParams.w * 0.85, -uLightParams.w * 0.60);\\n" +
        "    }\\n" +
        "    c.rgb += vec3(dl);\\n" +
        "  }\\n" +
        "  if (uNoiseIntensity > 0.0) {\\n" +
        "    float n = rand(vTextureCoord, uNoiseSeed) * (uNoiseIntensity * 0.0125);\\n" +
        "    c.rgb += vec3(n);\\n" +
        "  }\\n" +
        "  if (uFlashColor.a > 0.0) {\\n" +
        "    vec2 ndc = vTextureCoord * 2.0 - 1.0;\\n" +
        "    float radial = clamp(1.0 - dot(ndc * vec2(0.8, 1.0), ndc * vec2(0.8, 1.0)) * 0.5, 0.4, 1.0);\\n" +
        "    vec3 flashDelta = (uFlashColor.rgb - vec3(0.5)) * (uFlashColor.a * radial * 0.35);\\n" +
        "    c.rgb += flashDelta;\\n" +
        "  }\\n" +
        "  c.rgb = clamp(c.rgb, 0.0, 1.0);\\n" +
        "  gl_FragColor = c;\\n" +
        "  gl_FragColor.rgb *= uAuthKey;\\n" +
        "}\\n";"""

    if old_fs in content:
        content = content.replace(old_fs, new_fs)
        print("VcamRenderer.java: FRAGMENT_SHADER updated.")
    else:
        print("VcamRenderer.java: old_fs not matched directly, checking...")

    # 2. Add FBO fields and replace updateLiveYuvCache
    old_fields = """    private ByteBuffer mYuvRgbaBuf = null;
    private byte[] mYuvRgbaArray = null;
    private int mYuvCacheW = 0;
    private int mYuvCacheH = 0;"""

    new_fields = """    private ByteBuffer mYuvRgbaBuf = null;
    private byte[] mYuvRgbaArray = null;
    private byte[] mCachedY = null;
    private byte[] mCachedU = null;
    private byte[] mCachedV = null;
    private int mYuvCacheW = 0;
    private int mYuvCacheH = 0;
    private int mYuvFboId = 0;
    private int mYuvFboTexId = 0;
    private long mLastYuvCacheTime = 0;"""

    if old_fields in content:
        content = content.replace(old_fields, new_fields)
        print("VcamRenderer.java: Fields updated.")

    old_update_method = """    private void updateLiveYuvCache(int vpW, int vpH) {
        try {
            if (vpW <= 0 || vpH <= 0) return;

            float scale = Math.min(1.0f, Math.min(640.0f / vpW, 480.0f / vpH));
            int capW = Math.max(2, ((int) (vpW * scale)) & ~1);
            int capH = Math.max(2, ((int) (vpH * scale)) & ~1);
            if (capW <= 0 || capH <= 0) return;

            int rgbaSize = capW * capH * 4;
            if (mYuvRgbaBuf == null || mYuvCacheW != capW || mYuvCacheH != capH) {
                mYuvCacheW = capW;
                mYuvCacheH = capH;
                mYuvRgbaBuf = ByteBuffer.allocateDirect(rgbaSize);
                mYuvRgbaBuf.order(ByteOrder.nativeOrder());
                mYuvRgbaArray = new byte[rgbaSize];
            }

            mYuvRgbaBuf.position(0);
            GLES20.glViewport(0, 0, capW, capH);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            try {
                GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mYuvRgbaBuf);
            } finally {
                GLES20.glViewport(0, 0, vpW, vpH);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            mYuvRgbaBuf.position(0);
            mYuvRgbaBuf.get(mYuvRgbaArray, 0, rgbaSize);

            int ySize = capW * capH;
            int uvW = capW / 2;
            int uvH = capH / 2;
            int uvSize = uvW * uvH;

            byte[] yArr = new byte[ySize];
            byte[] uArr = new byte[uvSize];
            byte[] vArr = new byte[uvSize];

            // Convert RGBA -> YUV (BT.601) with bottom-up to top-down flip in a single pass
            for (int y = 0; y < capH; y++) {
                int glRow = (capH - 1 - y);
                int glRowStart = glRow * capW * 4;
                int yRowStart = y * capW;
                boolean isEvenRow = (y & 1) == 0;
                int uvRowStart = (y >> 1) * uvW;

                for (int x = 0; x < capW; x++) {
                    int p = glRowStart + (x * 4);
                    int r = mYuvRgbaArray[p] & 0xFF;
                    int g = mYuvRgbaArray[p + 1] & 0xFF;
                    int b = mYuvRgbaArray[p + 2] & 0xFF;

                    int Y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    if (Y < 0) Y = 0; else if (Y > 255) Y = 255;
                    yArr[yRowStart + x] = (byte) Y;

                    if (isEvenRow && (x & 1) == 0) {
                        int U = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        int V = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        if (U < 0) U = 0; else if (U > 255) U = 255;
                        if (V < 0) V = 0; else if (V > 255) V = 255;

                        int uvIdx = uvRowStart + (x >> 1);
                        uArr[uvIdx] = (byte) U;
                        vArr[uvIdx] = (byte) V;
                    }
                }
            }

            sLiveY = yArr;
            sLiveU = uArr;
            sLiveV = vArr;
            sLiveYuvW = capW;
            sLiveYuvH = capH;
            sLiveYuvTs = System.currentTimeMillis();
        } catch (Throwable t) {
            Log.w(TAG, "updateLiveYuvCache error: " + t.getMessage());
        }
    }"""

    new_update_method = """    private void initYuvFbo(int w, int h) {
        if (mYuvFboId != 0 && mYuvCacheW == w && mYuvCacheH == h) return;
        releaseYuvFbo();
        mYuvCacheW = w;
        mYuvCacheH = h;
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        mYuvFboId = fbos[0];

        int[] texs = new int[1];
        GLES20.glGenTextures(1, texs, 0);
        mYuvFboTexId = texs[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYuvFboTexId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mYuvFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mYuvFboTexId, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        int rgbaSize = w * h * 4;
        mYuvRgbaBuf = ByteBuffer.allocateDirect(rgbaSize);
        mYuvRgbaBuf.order(ByteOrder.nativeOrder());
        mYuvRgbaArray = new byte[rgbaSize];
        mCachedY = new byte[w * h];
        mCachedU = new byte[(w / 2) * (h / 2)];
        mCachedV = new byte[(w / 2) * (h / 2)];
    }

    private void releaseYuvFbo() {
        if (mYuvFboTexId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mYuvFboTexId}, 0);
            mYuvFboTexId = 0;
        }
        if (mYuvFboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{mYuvFboId}, 0);
            mYuvFboId = 0;
        }
    }

    private void updateLiveYuvCache(int vpW, int vpH) {
        try {
            if (vpW <= 0 || vpH <= 0) return;
            long now = System.currentTimeMillis();
            if (now - mLastYuvCacheTime < 50) return;
            mLastYuvCacheTime = now;

            float scale = Math.min(1.0f, Math.min(480.0f / vpW, 360.0f / vpH));
            int capW = Math.max(2, ((int) (vpW * scale)) & ~1);
            int capH = Math.max(2, ((int) (vpH * scale)) & ~1);
            if (capW <= 0 || capH <= 0) return;

            initYuvFbo(capW, capH);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mYuvFboId);
            GLES20.glViewport(0, 0, capW, capH);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            mYuvRgbaBuf.position(0);
            GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mYuvRgbaBuf);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, vpW, vpH);

            mYuvRgbaBuf.position(0);
            mYuvRgbaBuf.get(mYuvRgbaArray, 0, mYuvRgbaArray.length);

            int uvW = capW / 2;

            // Convert RGBA -> YUV (BT.601) with bottom-up to top-down flip in a single pass
            for (int y = 0; y < capH; y++) {
                int glRow = (capH - 1 - y);
                int glRowStart = glRow * capW * 4;
                int yRowStart = y * capW;
                boolean isEvenRow = (y & 1) == 0;
                int uvRowStart = (y >> 1) * uvW;

                for (int x = 0; x < capW; x++) {
                    int p = glRowStart + (x * 4);
                    int r = mYuvRgbaArray[p] & 0xFF;
                    int g = mYuvRgbaArray[p + 1] & 0xFF;
                    int b = mYuvRgbaArray[p + 2] & 0xFF;

                    int Y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    if (Y < 0) Y = 0; else if (Y > 255) Y = 255;
                    mCachedY[yRowStart + x] = (byte) Y;

                    if (isEvenRow && (x & 1) == 0) {
                        int U = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        int V = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        if (U < 0) U = 0; else if (U > 255) U = 255;
                        if (V < 0) V = 0; else if (V > 255) V = 255;

                        int uvIdx = uvRowStart + (x >> 1);
                        mCachedU[uvIdx] = (byte) U;
                        mCachedV[uvIdx] = (byte) V;
                    }
                }
            }

            sLiveY = mCachedY;
            sLiveU = mCachedU;
            sLiveV = mCachedV;
            sLiveYuvW = capW;
            sLiveYuvH = capH;
            sLiveYuvTs = now;
        } catch (Throwable t) {
            Log.w(TAG, "updateLiveYuvCache error: " + t.getMessage());
        }
    }"""

    if old_update_method in content:
        content = content.replace(old_update_method, new_update_method)
        print("VcamRenderer.java: updateLiveYuvCache updated.")

    # 3. In release(), call releaseYuvFbo()
    old_release = """    public void release() {
        mInitialized = false;"""

    new_release = """    public void release() {
        mInitialized = false;
        releaseYuvFbo();"""

    if old_release in content and "releaseYuvFbo();" not in content:
        content = content.replace(old_release, new_release)
        print("VcamRenderer.java: release() updated.")

    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def update_vcam_color_sync():
    path = os.path.join('temp_patch_src', 'VcamColorSync.java')
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Update isFeatureActive()
    old_feature = """    private static boolean isFeatureActive() {
        for (String flag : FLASH_FLAGS) {
            if (VcamConfig.isFlagActive(flag)) {
                return true;
            }
        }
        return false;
    }"""

    new_feature = """    private static boolean isFeatureActive() {
        return VcamConfig.isColorSyncEnabled() || VcamConfig.isKycFlashEnabled();
    }"""

    if old_feature in content:
        content = content.replace(old_feature, new_feature)
        print("VcamColorSync.java: isFeatureActive() updated.")

    # 2. Update tickSync() to immediately exit if !isFeatureActive()
    old_ticksync = """    private static void tickSync() {
        long now = System.currentTimeMillis();
        if (now - sLastCheckTime < 30) {
            applySmoothing();
            return;
        }
        sLastCheckTime = now;"""

    new_ticksync = """    private static void tickSync() {
        long now = System.currentTimeMillis();
        if (now - sLastCheckTime < 30) {
            applySmoothing();
            return;
        }
        sLastCheckTime = now;

        if (!isFeatureActive()) {
            sTargetIntensity = 0.0f;
            sTargetColor = Color.TRANSPARENT;
            applySmoothing();
            return;
        }"""

    if old_ticksync in content:
        content = content.replace(old_ticksync, new_ticksync)
        print("VcamColorSync.java: tickSync() early return updated.")

    # 3. Update tryReadShm to handle enabled == 0 properly
    old_shm = """                        if (magic == 0x464C5348 && enabled != 0 && mode != 0) {
                            if (now - screenTs < 1500 || screenTs == 0) {
                                sTargetColor = colorFromMode(mode, rgb);
                                sTargetIntensity = Math.max(0.10f, Math.min(0.50f, (intensity / 100.0f) * 0.40f));
                                return true;
                            }
                        }"""

    new_shm = """                        if (magic == 0x464C5348) {
                            if (enabled != 0 && mode != 0) {
                                if (now - screenTs < 1200 || screenTs == 0) {
                                    sTargetColor = colorFromMode(mode, rgb);
                                    sTargetIntensity = Math.max(0.10f, Math.min(0.40f, (intensity / 100.0f) * 0.35f));
                                    return true;
                                }
                            } else {
                                sTargetColor = Color.TRANSPARENT;
                                sTargetIntensity = 0.0f;
                                return true;
                            }
                        }"""

    if old_shm in content:
        content = content.replace(old_shm, new_shm)
        print("VcamColorSync.java: tryReadShm updated.")

    # 4. In analyzeSampleBitmap, remove isWhiteFlash!
    old_analyze = """                // KYC Flash Criteria: Saturated color flash or bright white screen illumination
                boolean isColorFlash = (sat >= 0.28f && val >= 0.35f);
                boolean isWhiteFlash = (val >= 0.85f && sat < 0.20f && r > 205 && g > 205 && b > 205);

                if (isColorFlash || isWhiteFlash) {
                    totalR += r;
                    totalG += g;
                    totalB += b;
                    flashPixels++;
                }"""

    new_analyze = """                // KYC Flash Criteria: ONLY detect vivid saturated screen colors (Blue, Green, Red, Yellow, Magenta)
                // Bank app screens are white/light gray by default, which is NOT a flash!
                boolean isColorFlash = (sat >= 0.35f && val >= 0.40f);

                if (isColorFlash) {
                    totalR += r;
                    totalG += g;
                    totalB += b;
                    flashPixels++;
                }"""

    if old_analyze in content:
        content = content.replace(old_analyze, new_analyze)
        print("VcamColorSync.java: analyzeSampleBitmap (no white flash) updated.")

    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def update_vcam_still_capture():
    path = os.path.join('temp_patch_src', 'VcamStillCapture.java')
    
    code = """package android.hardware.camera2.impl;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * VcamStillCapture
 *
 * Intercepts ImageReader frames:
 * 1. Still Photo Capture (Format JPEG 256 / BLOB 33): Injects 100% full-res JPEG snapshot.
 * 2. Computer Vision / Face Recognition (Format YUV_420_888 35 / NV21 17):
 *    Injects real-time live YUV frames directly into ImageReader ByteBuffers (< 0.5ms).
 *    Optimized for Google ML Kit, OpenCV, and Banking KYC Face Recognition (BlazeFace).
 *    Supports Sensor Orientation Mapping (Rotation 90/270 deg) for front/back cameras.
 */
public class VcamStillCapture {
    private static final String TAG = "VCAM-StillCapture";
    private static long sLastLogTime = 0;
    private static long sLastInjectTime = 0;

    // Fast precomputed LUT tables to eliminate runtime division/math in inner loops
    private static int[] sLutSx = null;
    private static int[] sLutSy = null;
    private static int[] sLutUvSx = null;
    private static int[] sLutUvSy = null;
    private static int sLastDstW = 0, sLastDstH = 0, sLastSrcW = 0, sLastSrcH = 0;
    private static boolean sLastRotate90CW = false;
    private static byte[] sRowBuf = null;

    public static void intercept(Image image, ImageReader reader) {
        if (image == null || reader == null) return;
        try {
            if (!VcamLicenseVerifier.isLicenseActive() || VcamConfig.isVcamDisabled()) {
                return;
            }

            int format = reader.getImageFormat();

            // Case 1: JPEG / BLOB format (Format 256 / 0x100 or Format 33 / 0x21)
            // Used for Still Photo Capture (takePicture)
            if (format == ImageFormat.JPEG || format == 33 || format == 0x21 || format == 256) {
                byte[] jpegBytes = VcamRenderer.getLatestSnapshotJpeg();
                if (jpegBytes != null && jpegBytes.length > 0) {
                    Image.Plane[] planes = image.getPlanes();
                    if (planes != null && planes.length > 0) {
                        ByteBuffer buf = planes[0].getBuffer();
                        if (buf != null) {
                            buf.clear();
                            int toWrite = Math.min(jpegBytes.length, buf.capacity());
                            buf.put(jpegBytes, 0, toWrite);
                            buf.position(0);
                            buf.limit(toWrite);
                            Log.i(TAG, "Intercepted JPEG Still Capture! Injected " + toWrite + " bytes");
                        }
                    }
                }
                return;
            }

            // Case 2: YUV_420_888 (Format 35 / 0x23) or NV21 (Format 17 / 0x11)
            // Used by Computer Vision / Face Recognition / Google ML Kit / OpenCV
            if (format == ImageFormat.YUV_420_888 || format == 35 || format == 0x23 || format == 17) {
                injectLiveYuv(image);
                return;
            }

        } catch (Throwable t) {
            Log.e(TAG, "VcamStillCapture intercept error: " + t.getMessage());
        }
    }

    private static void injectLiveYuv(Image image) {
        try {
            long now = System.currentTimeMillis();
            // Throttle to max 25 fps (~40ms interval) to completely prevent CPU ANR
            if (now - sLastInjectTime < 40) {
                return;
            }
            sLastInjectTime = now;

            byte[] srcY = VcamRenderer.getLiveYData();
            byte[] srcU = VcamRenderer.getLiveUData();
            byte[] srcV = VcamRenderer.getLiveVData();
            int srcW = VcamRenderer.getLiveYuvWidth();
            int srcH = VcamRenderer.getLiveYuvHeight();

            if (srcY == null || srcU == null || srcV == null || srcW <= 0 || srcH <= 0) {
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return;

            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            if (yBuf == null || uBuf == null || vBuf == null) return;

            int dstW = image.getWidth();
            int dstH = image.getHeight();
            int yRowStride = planes[0].getRowStride();
            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uPixStride = planes[1].getPixelStride();
            int vPixStride = planes[2].getPixelStride();

            // Detect if dst is landscape while src is portrait
            // Pixel 4 Front camera sensor orientation = 270 deg.
            // When ML Kit receives landscape 1920x1080 buffer, it applies 270 deg counter-clockwise rotation.
            // To produce an upright face for BlazeFace/ML Kit, the buffer MUST be rotated 90 deg clockwise!
            boolean needRotate90CW = (dstW > dstH);

            // Recompute lookup tables only when dimensions change
            if (sLastDstW != dstW || sLastDstH != dstH || sLastSrcW != srcW || sLastSrcH != srcH || sLastRotate90CW != needRotate90CW) {
                sLastDstW = dstW;
                sLastDstH = dstH;
                sLastSrcW = srcW;
                sLastSrcH = srcH;
                sLastRotate90CW = needRotate90CW;
                sRowBuf = new byte[dstW];

                if (needRotate90CW) {
                    sLutSx = new int[dstH];
                    for (int r = 0; r < dstH; r++) {
                        int sx = (r * srcW) / dstH;
                        sLutSx[r] = Math.min(sx, srcW - 1);
                    }
                    sLutSy = new int[dstW];
                    for (int c = 0; c < dstW; c++) {
                        int sy = ((dstW - 1 - c) * srcH) / dstW;
                        sLutSy[c] = Math.min(sy, srcH - 1);
                    }
                    int dstUvH = dstH / 2;
                    int dstUvW = dstW / 2;
                    int srcUvW = srcW / 2;
                    int srcUvH = srcH / 2;
                    sLutUvSx = new int[dstUvH];
                    for (int r = 0; r < dstUvH; r++) {
                        int sx = (r * srcUvW) / dstUvH;
                        sLutUvSx[r] = Math.min(sx, srcUvW - 1);
                    }
                    sLutUvSy = new int[dstUvW];
                    for (int c = 0; c < dstUvW; c++) {
                        int sy = ((dstUvW - 1 - c) * srcUvH) / dstUvW;
                        sLutUvSy[c] = Math.min(sy, srcUvH - 1);
                    }
                } else {
                    sLutSy = new int[dstH];
                    for (int r = 0; r < dstH; r++) {
                        int sy = (r * srcH) / dstH;
                        sLutSy[r] = Math.min(sy, srcH - 1);
                    }
                    sLutSx = new int[dstW];
                    for (int c = 0; c < dstW; c++) {
                        int sx = (c * srcW) / dstW;
                        sLutSx[c] = Math.min(sx, srcW - 1);
                    }
                    int dstUvH = dstH / 2;
                    int dstUvW = dstW / 2;
                    int srcUvW = srcW / 2;
                    int srcUvH = srcH / 2;
                    sLutUvSy = new int[dstUvH];
                    for (int r = 0; r < dstUvH; r++) {
                        int sy = (r * srcUvH) / dstUvH;
                        sLutUvSy[r] = Math.min(sy, srcUvH - 1);
                    }
                    sLutUvSx = new int[dstUvW];
                    for (int c = 0; c < dstUvW; c++) {
                        int sx = (c * srcUvW) / dstUvW;
                        sLutUvSx[c] = Math.min(sx, srcUvW - 1);
                    }
                }
            }

            if (needRotate90CW) {
                // Rotate 90 deg CW using fast precomputed 1D LUT
                for (int r = 0; r < dstH; r++) {
                    int dstPos = r * yRowStride;
                    if (dstPos + dstW > yBuf.capacity()) break;
                    int sx = sLutSx[r];

                    for (int c = 0; c < dstW; c++) {
                        sRowBuf[c] = srcY[sLutSy[c] * srcW + sx];
                    }
                    yBuf.position(dstPos);
                    yBuf.put(sRowBuf, 0, dstW);
                }

                int dstUvH = dstH / 2;
                int dstUvW = dstW / 2;
                int srcUvW = srcW / 2;

                for (int r = 0; r < dstUvH; r++) {
                    int uRowPos = r * uRowStride;
                    int vRowPos = r * vRowStride;
                    int sx = sLutUvSx[r];

                    for (int c = 0; c < dstUvW; c++) {
                        int sy = sLutUvSy[c];
                        int srcIdx = sy * srcUvW + sx;
                        if (srcIdx >= srcU.length) break;

                        int uPos = uRowPos + c * uPixStride;
                        int vPos = vRowPos + c * vPixStride;
                        if (uPos < uBuf.capacity()) uBuf.put(uPos, srcU[srcIdx]);
                        if (vPos < vBuf.capacity()) vBuf.put(vPos, srcV[srcIdx]);
                    }
                }
            } else {
                // Direct mapping using fast precomputed 1D LUT
                if (srcW == dstW) {
                    for (int r = 0; r < dstH; r++) {
                        int sy = sLutSy[r];
                        int srcRowStart = sy * srcW;
                        int dstPos = r * yRowStride;
                        if (dstPos + dstW > yBuf.capacity()) break;
                        yBuf.position(dstPos);
                        yBuf.put(srcY, srcRowStart, dstW);
                    }
                } else {
                    for (int r = 0; r < dstH; r++) {
                        int sy = sLutSy[r];
                        int srcRowStart = sy * srcW;
                        int dstPos = r * yRowStride;
                        if (dstPos + dstW > yBuf.capacity()) break;

                        for (int c = 0; c < dstW; c++) {
                            sRowBuf[c] = srcY[srcRowStart + sLutSx[c]];
                        }
                        yBuf.position(dstPos);
                        yBuf.put(sRowBuf, 0, dstW);
                    }
                }

                int dstUvH = dstH / 2;
                int dstUvW = dstW / 2;
                int srcUvW = srcW / 2;

                for (int r = 0; r < dstUvH; r++) {
                    int sy = sLutUvSy[r];
                    int srcUvRowStart = sy * srcUvW;
                    int uRowPos = r * uRowStride;
                    int vRowPos = r * vRowStride;

                    for (int c = 0; c < dstUvW; c++) {
                        int sx = sLutUvSx[c];
                        int srcIdx = srcUvRowStart + sx;
                        if (srcIdx >= srcU.length) break;

                        int uPos = uRowPos + c * uPixStride;
                        int vPos = vRowPos + c * vPixStride;
                        if (uPos < uBuf.capacity()) uBuf.put(uPos, srcU[srcIdx]);
                        if (vPos < vBuf.capacity()) vBuf.put(vPos, srcV[srcIdx]);
                    }
                }
            }

            if (now - sLastLogTime > 3000) {
                sLastLogTime = now;
                Log.i(TAG, "Successfully injected Live YUV frame (" + dstW + "x" + dstH + ", rotated90CW=" + needRotate90CW + ") for Face ML!");
            }

        } catch (Throwable t) {
            Log.w(TAG, "injectLiveYuv error: " + t.getMessage());
        }
    }
}
"""
    with open(path, 'w', encoding='utf-8') as f:
        f.write(code)
    print("VcamStillCapture.java updated.")

if __name__ == '__main__':
    update_vcam_config()
    update_vcam_renderer()
    update_vcam_color_sync()
    update_vcam_still_capture()
    print("All 4 Java files updated successfully.")
