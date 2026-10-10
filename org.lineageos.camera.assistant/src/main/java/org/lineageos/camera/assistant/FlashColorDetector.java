package org.lineageos.camera.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FlashColorDetector — phát hiện flash màu của app KYC và ghi vào SHM
 * để native hook apply vào video output.
 *
 * Hỗ trợ 2 mode:
 *   MODE_FRAME_DIFF  - phân tích thay đổi brightness/màu giữa các frame camera
 *   MODE_SCREEN_CAP  - MediaProjection chụp màn hình, lấy dominant color
 */
public class FlashColorDetector {
    private static final String TAG = "VcamFlashDetector";

    // ── SHM constants (phải khớp với vcam_flash.h) ──────────────────────────
    private static final String SHM_PATH      = "/data/local/tmp/vcam_flash.shm";
    private static final String CFG_PATH      = "/data/local/tmp/vcam_flash.cfg";
    private static final long   FLASH_MAGIC   = 0x464C5348L; // 'FLSH'
    private static final int    FLASH_VERSION = 2;
    private static final int    SHM_SIZE      = 40; // bytes

    // Flash mode constants
    public static final int MODE_OFF    = 0;
    public static final int MODE_RED    = 1;
    public static final int MODE_GREEN  = 2;
    public static final int MODE_BLUE   = 3;
    public static final int MODE_WHITE  = 4;
    public static final int MODE_CUSTOM = 5;

    // Detect mode
    public static final int DETECT_FRAME_DIFF = 0;
    public static final int DETECT_SCREEN_CAP = 1;

    // ── State ────────────────────────────────────────────────────────────────
    private final Context mContext;
    private int mDetectMode = DETECT_FRAME_DIFF;
    private int mIntensity  = 35;  // 0..100
    private final AtomicBoolean mEnabled  = new AtomicBoolean(false);
    private final AtomicInteger mSeq      = new AtomicInteger(0);

    // Screen capture
    private MediaProjection     mProjection;
    private VirtualDisplay      mVirtualDisplay;
    private ImageReader         mScreenReader;
    private HandlerThread       mScreenThread;
    private Handler             mScreenHandler;

    // Frame diff
    private HandlerThread       mDiffThread;
    private Handler             mDiffHandler;
    private int mPrevAvgY  = -1;
    private int mPrevAvgU  = -1;
    private int mPrevAvgV  = -1;
    private boolean mFlashActive = false;
    private long    mFlashStartMs = 0;
    private static final int FLASH_HOLD_MS   = 220;
    private static final int DIFF_THRESHOLD  = 18;  // delta Y

    // Dynamic Brightness Baseline & Flash tracking for SCREEN_CAP
    private float mBaselineBrightnessA = -1f;
    private float mBaselineBrightnessB = -1f;
    private long  mFlashActiveUntilMs  = 0;
    private int   mLastScreenFlashMode = MODE_OFF;
    private int   mLastScreenFlashRgb  = 0;
    private long  mLastProcessScreenMs = 0;

    // Screen cap interval — 33ms (~30fps) for ultra-responsive 0% CPU lag
    private static final int SCREEN_INTERVAL_MS = 33;
    private Runnable mScreenRunnable;

    public FlashColorDetector(Context context) {
        mContext = context;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setDetectMode(int mode) {
        mDetectMode = mode;
        if (mEnabled.get() && mode == DETECT_SCREEN_CAP && mScreenHandler != null && mScreenRunnable != null) {
            mScreenHandler.removeCallbacks(mScreenRunnable);
            mScreenHandler.post(mScreenRunnable);
        }
        Log.i(TAG, "DetectMode = " + (mode == DETECT_SCREEN_CAP ? "SCREEN_CAP" : "FRAME_DIFF"));
    }

    public void setIntensity(int intensity) {
        mIntensity = Math.max(0, Math.min(100, intensity));
    }

    public void setEnabled(boolean enabled) {
        mEnabled.set(enabled);
        if (enabled) {
            if (mDetectMode == DETECT_SCREEN_CAP && mScreenHandler != null && mScreenRunnable != null) {
                mScreenHandler.removeCallbacks(mScreenRunnable);
                mScreenHandler.post(mScreenRunnable);
            }
        } else {
            writeFlashState(MODE_OFF, 0, 0, false);
        }
        Log.i(TAG, "FlashDetector enabled=" + enabled);
    }

    public boolean isEnabled() {
        return mEnabled.get();
    }

    /**
     * Start Screen Capture mode.
     * resultCode and data come from Activity.onActivityResult for MEDIA_PROJECTION.
     */
    public void startScreenCapMode(int resultCode, Intent data) {
        stopScreenCapMode();

        MediaProjectionManager mpm = (MediaProjectionManager)
                mContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { Log.e(TAG, "No MediaProjectionManager"); return; }

        mProjection = mpm.getMediaProjection(resultCode, data);
        if (mProjection == null) { Log.e(TAG, "getMediaProjection failed"); return; }

        mScreenThread = new HandlerThread("VcamFlashScreenThread");
        mScreenThread.start();
        mScreenHandler = new Handler(mScreenThread.getLooper());

        // Hardware GPU downscale: 32x32 buffer is scaled by Hardware Composer in < 0.5ms (0% CPU load)
        int capW = 32, capH = 32;
        mScreenReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

        mScreenReader.setOnImageAvailableListener(reader -> {
            if (mEnabled.get() && mDetectMode == DETECT_SCREEN_CAP) {
                processScreenFrame();
            }
        }, mScreenHandler);

        mVirtualDisplay = mProjection.createVirtualDisplay(
                "VcamFlashCapture",
                capW, capH,
                mContext.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mScreenReader.getSurface(),
                null, mScreenHandler
        );

        mScreenRunnable = new Runnable() {
            @Override
            public void run() {
                if (mScreenHandler == null) return;
                try {
                    if (mEnabled.get() && mDetectMode == DETECT_SCREEN_CAP) {
                        processScreenFrame();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Screen loop err: " + t.getMessage());
                }
                if (mScreenHandler != null) {
                    mScreenHandler.postDelayed(this, SCREEN_INTERVAL_MS);
                }
            }
        };
        mScreenHandler.post(mScreenRunnable);
        Log.i(TAG, "Screen capture mode started " + capW + "x" + capH);
    }

    public void stopScreenCapMode() {
        if (mScreenHandler != null && mScreenRunnable != null) {
            mScreenHandler.removeCallbacks(mScreenRunnable);
        }
        if (mVirtualDisplay != null) { mVirtualDisplay.release(); mVirtualDisplay = null; }
        if (mScreenReader  != null) { mScreenReader.close();  mScreenReader  = null; }
        if (mProjection    != null) { mProjection.stop();     mProjection    = null; }
        if (mScreenThread  != null) { mScreenThread.quitSafely(); mScreenThread = null; }
        mBaselineBrightnessA = -1f;
        mBaselineBrightnessB = -1f;
        mFlashActiveUntilMs  = 0;
        mLastScreenFlashMode = MODE_OFF;
    }

    /**
     * Start Frame Diff mode (call this if you have a camera frame byte[] NV21).
     * Call processFrameDiff() each frame from your camera callback.
     */
    public void startFrameDiffMode() {
        mDiffThread = new HandlerThread("VcamFlashDiffThread");
        mDiffThread.start();
        mDiffHandler = new Handler(mDiffThread.getLooper());
        Log.i(TAG, "Frame diff mode started");
    }

    public void stopFrameDiffMode() {
        if (mDiffThread != null) { mDiffThread.quitSafely(); mDiffThread = null; }
    }

    /**
     * Process a camera preview frame (NV21 format, most common on Android).
     * Call this from your camera preview callback on any thread.
     * @param data  NV21 frame bytes
     * @param width frame width
     * @param height frame height
     */
    public void processFrameDiff(final byte[] data, final int width, final int height) {
        if (!mEnabled.get() || mDetectMode != DETECT_FRAME_DIFF) return;
        if (mDiffHandler == null) return;
        mDiffHandler.post(() -> doFrameDiff(data, width, height));
    }

    public void release() {
        setEnabled(false);
        stopScreenCapMode();
        stopFrameDiffMode();
    }

    private static class FlashDetectionResult {
        String zone = "";
        boolean isFlash = false;
        int mode = MODE_OFF;
        int rgb = 0;
        int r = 0, g = 0, b = 0;
        int brightness = 0;
    }

    private FlashDetectionResult evaluateZoneFlash(String zone, int r, int g, int b, int brightness, float baseline) {
        FlashDetectionResult res = new FlashDetectionResult();
        res.zone = zone;
        res.r = r;
        res.g = g;
        res.b = b;
        res.brightness = brightness;
        res.rgb = (r << 16) | (g << 8) | b;

        int maxCh = Math.max(r, Math.max(g, b));
        int minCh = Math.min(r, Math.min(g, b));
        int delta = maxCh - minCh;

        // Tính bước nhảy độ sáng so với baseline tĩnh (nếu có)
        float deltaBright = (baseline >= 0) ? (brightness - baseline) : 0;

        // Tiêu chuẩn 1: Bước nhảy độ sáng đột biến (Flash chớp sáng)
        boolean hasJump = (deltaBright >= 26);

        // Tiêu chuẩn 2: Độ sáng cao tuyệt đối (Màn hình/viền sáng rực)
        boolean isHighBright = (brightness >= 135 || maxCh >= 170);

        // Nếu cả 2 tiêu chuẩn đều không đạt -> Bỏ qua (tránh bẫy màu nền tĩnh, xanh navy, nền tối)
        if (!hasJump && !isHighBright) {
            res.isFlash = false;
            return res;
        }

        // --- PHÂN LOẠI MÀU FLASH ---
        // 1. Trắng (White): Độ sáng cao, 3 kênh cân bằng
        if (r >= 140 && g >= 140 && b >= 140 && delta <= 40 && brightness >= 135) {
            res.mode = MODE_WHITE;
            res.isFlash = true;
            return res;
        }

        // 2. Đỏ (Red): Kênh R vượt trội hoàn toàn
        if (r >= 125 && r > g * 1.45f && r > b * 1.45f) {
            res.mode = MODE_RED;
            res.isFlash = true;
            return res;
        }

        // 3. Xanh Lá (Green): Kênh G vượt trội hoàn toàn
        if (g >= 125 && g > r * 1.30f && g > b * 1.30f) {
            res.mode = MODE_GREEN;
            res.isFlash = true;
            return res;
        }

        // 4. Xanh Dương (Blue): Kênh B vượt trội và phải sáng thật sự (B >= 145 để không nhầm Navy B=119)
        if (b >= 145 && b > r * 1.45f && b > g * 1.25f) {
            res.mode = MODE_BLUE;
            res.isFlash = true;
            return res;
        }

        // 5. Tùy chỉnh (Custom): Ví dụ vàng, cam, tím hoặc độ lệch màu rõ khi có bước nhảy sáng
        if (hasJump && delta >= 35 && maxCh >= 120) {
            res.mode = MODE_CUSTOM;
            res.isFlash = true;
            return res;
        }

        if (isHighBright && delta >= 45) {
            res.mode = MODE_CUSTOM;
            res.isFlash = true;
            return res;
        }

        res.isFlash = false;
        return res;
    }

    private void processScreenFrame() {
        if (mScreenReader == null) return;
        long now = System.currentTimeMillis();
        if (now - mLastProcessScreenMs < 30) return;
        mLastProcessScreenMs = now;

        Image img = null;
        try {
            img = mScreenReader.acquireLatestImage();
            if (img == null) return;

            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride  = plane.getRowStride();
            int pixStride  = plane.getPixelStride();
            int w = img.getWidth();
            int h = img.getHeight();

            // ==============================================================
            // ZONE A: OUTER MARGINS (Top banner, Left, Right, Bottom)
            // ==============================================================
            int[][] regionsA = new int[][] {
                { (int)(w * 0.15f), (int)(h * 0.03f), (int)(w * 0.85f), (int)(h * 0.14f) }, // Top banner
                { (int)(w * 0.02f), (int)(h * 0.20f), (int)(w * 0.12f), (int)(h * 0.70f) }, // Left border
                { (int)(w * 0.88f), (int)(h * 0.20f), (int)(w * 0.98f), (int)(h * 0.70f) }, // Right border
                { (int)(w * 0.15f), (int)(h * 0.84f), (int)(w * 0.85f), (int)(h * 0.96f) }  // Bottom controls
            };

            long sumRA = 0, sumGA = 0, sumBA = 0;
            int cntA = 0;
            int stepA = 3;

            for (int[] reg : regionsA) {
                int x0 = reg[0], y0 = reg[1], x1 = reg[2], y1 = reg[3];
                for (int y = y0; y < y1; y += stepA) {
                    for (int x = x0; x < x1; x += stepA) {
                        int pos = y * rowStride + x * pixStride;
                        if (pos + 3 < buf.limit()) {
                            sumRA += (buf.get(pos)     & 0xFF);
                            sumGA += (buf.get(pos + 1) & 0xFF);
                            sumBA += (buf.get(pos + 2) & 0xFF);
                            cntA++;
                        }
                    }
                }
            }

            int avgRA = cntA > 0 ? (int)(sumRA / cntA) : 0;
            int avgGA = cntA > 0 ? (int)(sumGA / cntA) : 0;
            int avgBA = cntA > 0 ? (int)(sumBA / cntA) : 0;
            int brightA = (avgRA * 77 + avgGA * 150 + avgBA * 29) >> 8;

            // ==============================================================
            // ZONE B: CENTER OVAL PERIMETER RING (Viền khuôn mặt KYC)
            // Lấy mẫu tại vành elip quanh tâm (cx, cy) với bán kính (rx, ry)
            // ==============================================================
            float cx = w * 0.50f;
            float cy = h * 0.44f;
            float rx = w * 0.33f;
            float ry = h * 0.23f;

            long sumRB = 0, sumGB = 0, sumBB = 0;
            int cntB = 0;
            int numPoints = 20;

            for (int i = 0; i < numPoints; i++) {
                double angle = (2.0 * Math.PI * i) / numPoints;
                int ox = (int)(cx + rx * Math.cos(angle));
                int oy = (int)(cy + ry * Math.sin(angle));

                // Lấy mẫu cụm 3x3 pixel quanh mỗi điểm viền oval
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = ox + dx;
                        int sy = oy + dy;
                        if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                            int pos = sy * rowStride + sx * pixStride;
                            if (pos + 3 < buf.limit()) {
                                sumRB += (buf.get(pos)     & 0xFF);
                                sumGB += (buf.get(pos + 1) & 0xFF);
                                sumBB += (buf.get(pos + 2) & 0xFF);
                                cntB++;
                            }
                        }
                    }
                }
            }

            int avgRB = cntB > 0 ? (int)(sumRB / cntB) : 0;
            int avgGB = cntB > 0 ? (int)(sumGB / cntB) : 0;
            int avgBB = cntB > 0 ? (int)(sumBB / cntB) : 0;
            int brightB = (avgRB * 77 + avgGB * 150 + avgBB * 29) >> 8;

            // Phân tích trạng thái Flash cho từng Zone
            FlashDetectionResult resA = evaluateZoneFlash("Outer", avgRA, avgGA, avgBA, brightA, mBaselineBrightnessA);
            FlashDetectionResult resB = evaluateZoneFlash("OvalRing", avgRB, avgGB, avgBB, brightB, mBaselineBrightnessB);

            FlashDetectionResult activeResult = null;
            // Ưu tiên zone có flash rõ ràng nhất
            if (resB.isFlash && (!resA.isFlash || resB.brightness >= resA.brightness)) {
                activeResult = resB;
            } else if (resA.isFlash) {
                activeResult = resA;
            }

            if (activeResult != null) {
                mFlashActiveUntilMs = now + FLASH_HOLD_MS;
                mLastScreenFlashMode = activeResult.mode;
                mLastScreenFlashRgb = activeResult.rgb;
                writeFlashState(activeResult.mode, mIntensity, activeResult.rgb, true);
                Log.d(TAG, String.format("ScreenFlash [ACTIVE]: zone=%s mode=%d rgb=#%06X (R=%d,G=%d,B=%d, bright=%d)",
                        activeResult.zone, activeResult.mode, activeResult.rgb,
                        activeResult.r, activeResult.g, activeResult.b, activeResult.brightness));
            } else {
                // Kiểm tra xem có đang trong thời gian giữ flash (debounce hold) không
                if (now < mFlashActiveUntilMs && mLastScreenFlashMode != MODE_OFF) {
                    writeFlashState(mLastScreenFlashMode, mIntensity, mLastScreenFlashRgb, true);
                } else {
                    mLastScreenFlashMode = MODE_OFF;
                    writeFlashState(MODE_OFF, 0, 0, false);

                    // Cập nhật baseline độ sáng nền tĩnh khi màn hình không có flash
                    if (mBaselineBrightnessA < 0) {
                        mBaselineBrightnessA = brightA;
                    } else {
                        mBaselineBrightnessA = mBaselineBrightnessA * 0.90f + brightA * 0.10f;
                    }

                    if (mBaselineBrightnessB < 0) {
                        mBaselineBrightnessB = brightB;
                    } else {
                        mBaselineBrightnessB = mBaselineBrightnessB * 0.90f + brightB * 0.10f;
                    }
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "processScreenFrame error: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    // ── Frame diff processing ─────────────────────────────────────────────────

    private void doFrameDiff(byte[] data, int width, int height) {
        // NV21: Y plane = data[0..w*h-1], VU interleaved = data[w*h..]
        int ySize  = width * height;
        int uvSize = ySize / 2;
        if (data.length < ySize + uvSize) return;

        // Compute average Y, U, V (sample every 8th pixel)
        long sumY = 0, sumU = 0, sumV = 0;
        int cntY = 0, cntUV = 0;
        int step = 8;

        for (int i = 0; i < ySize; i += step) {
            sumY += (data[i] & 0xFF);
            cntY++;
        }
        // NV21: VU interleaved (V first, then U)
        for (int i = ySize; i < ySize + uvSize - 1; i += step * 2) {
            sumV += (data[i]     & 0xFF); // V
            sumU += (data[i + 1] & 0xFF); // U
            cntUV++;
        }

        int avgY = cntY  > 0 ? (int)(sumY  / cntY)  : 128;
        int avgU = cntUV > 0 ? (int)(sumU  / cntUV) : 128;
        int avgV = cntUV > 0 ? (int)(sumV  / cntUV) : 128;

        long now = System.currentTimeMillis();

        if (mPrevAvgY < 0) {
            mPrevAvgY = avgY; mPrevAvgU = avgU; mPrevAvgV = avgV;
            return;
        }

        int dy = avgY - mPrevAvgY;
        int du = avgU - mPrevAvgU;
        int dv = avgV - mPrevAvgV;

        // Detect sudden brightness jump
        if (dy > DIFF_THRESHOLD) {
            mFlashActive   = true;
            mFlashStartMs  = now;

            // Classify color from UV shift
            int flashMode;
            int rgb;
            int absDu = Math.abs(du);
            int absDv = Math.abs(dv);

            if (absDu < 8 && absDv < 8) {
                flashMode = MODE_WHITE; rgb = 0xFFFFFF;
            } else if (du > 5 && dv < 0) {
                flashMode = MODE_BLUE; rgb = 0x0000FF;
            } else if (dv > 5 && du < 0) {
                flashMode = MODE_RED; rgb = 0xFF0000;
            } else if (du < -3 && dv < -3) {
                flashMode = MODE_GREEN; rgb = 0x00FF00;
            } else {
                // Reconstruct approximate RGB from average YUV
                int R = clamp((int)(avgY + 1.402f * (avgV - 128)), 0, 255);
                int G = clamp((int)(avgY - 0.344f * (avgU - 128) - 0.714f * (avgV - 128)), 0, 255);
                int B = clamp((int)(avgY + 1.772f * (avgU - 128)), 0, 255);
                flashMode = MODE_CUSTOM;
                rgb = (R << 16) | (G << 8) | B;
            }

            writeFlashState(flashMode, mIntensity, rgb, true);
            Log.d(TAG, String.format("FrameFlash: dy=%d du=%d dv=%d mode=%d rgb=#%06X",
                    dy, du, dv, flashMode, rgb));
        }

        // Expire
        if (mFlashActive && (now - mFlashStartMs > FLASH_HOLD_MS)) {
            mFlashActive = false;
            writeFlashState(MODE_OFF, 0, 0, false);
        }

        mPrevAvgY = avgY; mPrevAvgU = avgU; mPrevAvgV = avgV;
    }

    // ── SHM writer ────────────────────────────────────────────────────────────

    /**
     * Write flash state to binary SHM file (read by native hook).
     * Layout (little-endian, matches VcamFlashShm struct, pack=1, total 40 bytes):
     *  +0  uint32 magic
     *  +4  uint32 version
     *  +8  uint32 seq
     * +12  uint32 mode
     * +16  int32  intensity
     * +20  uint32 rgb
     * +24  uint64 screen_ts_ms
     * +32  uint32 enabled
     * +36  uint32 detect_mode
     * (total 40 bytes, last 4 bytes of reserved not needed in v2)
     */
    private void writeFlashState(int mode, int intensity, int rgb, boolean enabled) {
        int seq = mSeq.incrementAndGet();
        long tsMs = System.currentTimeMillis();

        ByteBuffer buf = ByteBuffer.allocate(SHM_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt((int) FLASH_MAGIC);    // +0
        buf.putInt(FLASH_VERSION);         // +4
        buf.putInt(seq);                   // +8
        buf.putInt(mode);                  // +12
        buf.putInt(intensity);             // +16
        buf.putInt(rgb);                   // +20
        buf.putLong(tsMs);                 // +24  (8 bytes)
        buf.putInt(enabled ? 1 : 0);       // +32
        buf.putInt(mDetectMode);           // +36

        // 1. Write binary SHM to /data/local/tmp and /sdcard
        try {
            RandomAccessFile raf = new RandomAccessFile(SHM_PATH, "rw");
            raf.seek(0);
            raf.write(buf.array());
            raf.close();
        } catch (IOException ignored) {}

        try {
            java.io.File caDir = new java.io.File("/sdcard/CameraAssistant");
            if (!caDir.exists()) caDir.mkdirs();
            RandomAccessFile rafSd = new RandomAccessFile("/sdcard/CameraAssistant/vcam_flash.shm", "rw");
            rafSd.seek(0);
            rafSd.write(buf.array());
            rafSd.close();
            new java.io.File("/sdcard/vcam_flash.shm").delete();
        } catch (IOException ignored) {}

        // 2. Also write human-readable cfg for debugging & native fallback
        String cfg = mode + " " + intensity + " " + String.format("%06X", rgb)
                + " " + (enabled ? 1 : 0) + " " + mDetectMode + "\n";
        byte[] cfgBytes = cfg.getBytes();
        try {
            FileOutputStream fos = new FileOutputStream(CFG_PATH, false);
            fos.write(cfgBytes);
            fos.close();
        } catch (IOException ignored) {}

        try {
            FileOutputStream fosSd = new FileOutputStream("/sdcard/CameraAssistant/vcam_flash.cfg", false);
            fosSd.write(cfgBytes);
            fosSd.close();
            new java.io.File("/sdcard/vcam_flash.cfg").delete();
        } catch (IOException ignored) {}
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
