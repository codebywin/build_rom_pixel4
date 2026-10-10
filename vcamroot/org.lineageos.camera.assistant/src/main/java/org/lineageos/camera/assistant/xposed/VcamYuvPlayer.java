package org.lineageos.camera.assistant.xposed;

import android.graphics.Rect;
import android.hardware.Camera;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VcamYuvPlayer extends Thread {
    private static final String TAG = "VcamYuvPlayer";

    private static final Map<Camera, VcamYuvPlayer> sActivePlayers = new ConcurrentHashMap<>();
    private static volatile byte[] sGlobalLatestNv21Frame = null;

    private final Camera camera;
    private final File videoFile;
    private final int dstW;
    private final int dstH;
    private final int facing;
    private final int orientation;

    private volatile boolean running = true;
    private volatile byte[] currentNv21Frame = null;

    private final byte[] bufferA;
    private final byte[] bufferB;
    private boolean useA = true;

    public static synchronized void startPlayer(Camera cam, File file, int width, int height, int facing, int orientation) {
        if (cam == null || file == null || !file.exists()) return;
        VcamYuvPlayer existing = sActivePlayers.get(cam);
        if (existing != null && existing.isAlive() && existing.dstW == width && existing.dstH == height
                && existing.facing == facing && existing.orientation == orientation) {
            return;
        }
        stopPlayer(cam);

        VcamYuvPlayer player = new VcamYuvPlayer(cam, file, width, height, facing, orientation);
        sActivePlayers.put(cam, player);
        player.start();
        Log.i(TAG, "Started VcamYuvPlayer for camera " + cam + " (" + width + "x" + height + ", facing=" + facing + ", orient=" + orientation + ")");
    }

    public static synchronized void stopPlayer(Camera cam) {
        if (cam == null) return;
        VcamYuvPlayer player = sActivePlayers.remove(cam);
        if (player != null) {
            player.stopPlayback();
            Log.i(TAG, "Stopped VcamYuvPlayer for camera " + cam);
        }
    }

    public static void stopAll() {
        for (Camera cam : sActivePlayers.keySet()) {
            stopPlayer(cam);
        }
    }

    public static byte[] getCurrentNv21Frame(Camera cam) {
        if (cam != null) {
            VcamYuvPlayer player = sActivePlayers.get(cam);
            if (player != null && player.currentNv21Frame != null) {
                return player.currentNv21Frame;
            }
        }
        return sGlobalLatestNv21Frame;
    }

    public VcamYuvPlayer(Camera camera, File file, int width, int height, int facing, int orientation) {
        super("VcamYuvPlayer-" + width + "x" + height);
        this.camera = camera;
        this.videoFile = file;
        this.dstW = width > 0 ? width : 1280;
        this.dstH = height > 0 ? height : 720;
        this.facing = facing;
        this.orientation = orientation;

        int totalBytes = this.dstW * this.dstH * 3 / 2;
        this.bufferA = new byte[totalBytes];
        this.bufferB = new byte[totalBytes];
    }

    public void stopPlayback() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        Log.i(TAG, "VcamYuvPlayer thread started for " + videoFile.getAbsolutePath());
        while (running) {
            MediaExtractor extractor = null;
            MediaCodec decoder = null;
            try {
                extractor = new MediaExtractor();
                extractor.setDataSource(videoFile.getAbsolutePath());

                int trackIdx = -1;
                MediaFormat format = null;
                int numTracks = extractor.getTrackCount();
                for (int i = 0; i < numTracks; i++) {
                    MediaFormat mf = extractor.getTrackFormat(i);
                    String mime = mf.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        trackIdx = i;
                        format = mf;
                        break;
                    }
                }

                if (trackIdx < 0 || format == null) {
                    Log.e(TAG, "No video track found in " + videoFile.getAbsolutePath());
                    return;
                }

                extractor.selectTrack(trackIdx);
                String mime = format.getString(MediaFormat.KEY_MIME);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(format, null, null, 0);
                decoder.start();

                int fps = 30;
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                        if (fps <= 0 || fps > 60) fps = 30;
                    } catch (Throwable ignored) {}
                }
                long frameDelayMs = 1000 / fps;

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long nextFrameTimeMs = System.currentTimeMillis();
                String lastResetTs = XposedSharedConfig.getResetTimestamp();

                while (running) {
                    String curResetTs = XposedSharedConfig.getResetTimestamp();
                    if (!curResetTs.isEmpty() && !curResetTs.equals(lastResetTs)) {
                        lastResetTs = curResetTs;
                        try {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            decoder.flush();
                            nextFrameTimeMs = System.currentTimeMillis();
                            Log.i(TAG, "YUV video rewound to start");
                        } catch (Throwable t) {
                            Log.w(TAG, "Error rewinding YUV video: " + t);
                        }
                    }

                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                        try {
                            Thread.sleep(40);
                        } catch (InterruptedException e) {
                            break;
                        }
                        continue;
                    }

                    // Feed input
                    int inIdx = decoder.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int sampleSize = extractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                // End of stream -> loop back to 0
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                sampleSize = extractor.readSampleData(inBuf, 0);
                            }
                            if (sampleSize > 0) {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                                extractor.advance();
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                    }

                    // Dequeue output
                    int outIdx = decoder.dequeueOutputBuffer(info, 10000);
                    if (outIdx >= 0) {
                        long now = System.currentTimeMillis();
                        if (now < nextFrameTimeMs) {
                            try {
                                Thread.sleep(nextFrameTimeMs - now);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        nextFrameTimeMs = System.currentTimeMillis() + frameDelayMs;

                        try {
                            Image image = decoder.getOutputImage(outIdx);
                            if (image != null) {
                                int baseRot = (360 - (orientation % 360)) % 360;
                                int rot = (baseRot + XposedSharedConfig.getRotation()) % 360;

                                byte[] targetBuf = useA ? bufferA : bufferB;
                                convertImageToNv21(image, targetBuf, dstW, dstH, rot);
                                currentNv21Frame = targetBuf;
                                sGlobalLatestNv21Frame = targetBuf;
                                useA = !useA;
                                image.close();
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Error converting Image to NV21: " + t);
                        }
                        decoder.releaseOutputBuffer(outIdx, false);
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    Log.e(TAG, "VcamYuvPlayer error: " + t, t);
                }
            } finally {
                if (decoder != null) {
                    try { decoder.stop(); decoder.release(); } catch (Throwable ignored) {}
                }
                if (extractor != null) {
                    try { extractor.release(); } catch (Throwable ignored) {}
                }
                if (running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }
        Log.i(TAG, "VcamYuvPlayer thread exited for " + videoFile.getAbsolutePath());
    }

    public static void convertImageToNv21(Image image, byte[] dstNv21, int dstW, int dstH, int rot) {
        Rect crop = image.getCropRect();
        int cropW = crop.width();
        int cropH = crop.height();
        int cropLeft = crop.left;
        int cropTop = crop.top;

        float zoom = XposedSharedConfig.getZoom();
        float panX = XposedSharedConfig.getPanX();
        float panY = XposedSharedConfig.getPanY();
        boolean mirror = XposedSharedConfig.isFlagActive("vcam_mirror");

        int srcW = (int) (cropW / Math.max(0.1f, zoom));
        int srcH = (int) (cropH / Math.max(0.1f, zoom));

        int offsetX = (int) (panX * cropW);
        int offsetY = (int) (panY * cropH);

        int baseLeft = cropLeft + (cropW - srcW) / 2 + offsetX;
        int baseTop = cropTop + (cropH - srcH) / 2 + offsetY;

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();

        ByteBuffer uBuf = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixStride = planes[1].getPixelStride();

        ByteBuffer vBuf = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixStride = planes[2].getPixelStride();

        int uvDstOffset = dstW * dstH;
        int dstUvW = dstW / 2;
        int dstUvH = dstH / 2;
        int srcUvW = srcW / 2;
        int srcUvH = srcH / 2;
        int baseUvLeft = baseLeft / 2;
        int baseUvTop = baseTop / 2;
        int maxUvW = cropW / 2;
        int maxUvH = cropH / 2;

        boolean kycFlash = XposedSharedConfig.isKycFlashActive();

        if (rot == 90) {
            // 90 deg clockwise
            int pos = 0;
            for (int y = 0; y < dstH; y++) {
                int curY = mirror ? (dstH - 1 - y) : y;
                int srcX = baseLeft + (curY * srcW / dstH);
                if (srcX < 0) srcX = 0;
                if (srcX >= cropW) srcX = cropW - 1;

                for (int x = 0; x < dstW; x++) {
                    int srcY = baseTop + ((dstW - 1 - x) * srcH / dstW);
                    if (srcY < 0) srcY = 0;
                    if (srcY >= cropH) srcY = cropH - 1;

                    byte yVal = yBuf.get(srcY * yRowStride + srcX * yPixStride);
                    if (kycFlash) yVal = (byte) Math.min(255, (yVal & 0xFF) + 60);
                    dstNv21[pos++] = yVal;
                }
            }

            int uvPos = uvDstOffset;
            for (int y = 0; y < dstUvH; y++) {
                int curY = mirror ? (dstUvH - 1 - y) : y;
                int srcUvX = baseUvLeft + (curY * srcUvW / dstUvH);
                if (srcUvX < 0) srcUvX = 0;
                if (srcUvX >= maxUvW) srcUvX = maxUvW - 1;

                for (int x = 0; x < dstUvW; x++) {
                    int srcUvY = baseUvTop + ((dstUvW - 1 - x) * srcUvH / dstUvW);
                    if (srcUvY < 0) srcUvY = 0;
                    if (srcUvY >= maxUvH) srcUvY = maxUvH - 1;

                    int vIdx = srcUvY * vRowStride + srcUvX * vPixStride;
                    int uIdx = srcUvY * uRowStride + srcUvX * uPixStride;
                    dstNv21[uvPos++] = vBuf.get(vIdx);
                    dstNv21[uvPos++] = uBuf.get(uIdx);
                }
            }
        } else if (rot == 270) {
            // 270 deg clockwise
            int pos = 0;
            for (int y = 0; y < dstH; y++) {
                int curY = mirror ? y : (dstH - 1 - y);
                int srcX = baseLeft + (curY * srcW / dstH);
                if (srcX < 0) srcX = 0;
                if (srcX >= cropW) srcX = cropW - 1;

                for (int x = 0; x < dstW; x++) {
                    int srcY = baseTop + (x * srcH / dstW);
                    if (srcY < 0) srcY = 0;
                    if (srcY >= cropH) srcY = cropH - 1;

                    byte yVal = yBuf.get(srcY * yRowStride + srcX * yPixStride);
                    if (kycFlash) yVal = (byte) Math.min(255, (yVal & 0xFF) + 60);
                    dstNv21[pos++] = yVal;
                }
            }

            int uvPos = uvDstOffset;
            for (int y = 0; y < dstUvH; y++) {
                int curY = mirror ? y : (dstUvH - 1 - y);
                int srcUvX = baseUvLeft + (curY * srcUvW / dstUvH);
                if (srcUvX < 0) srcUvX = 0;
                if (srcUvX >= maxUvW) srcUvX = maxUvW - 1;

                for (int x = 0; x < dstUvW; x++) {
                    int srcUvY = baseUvTop + (x * srcUvH / dstUvW);
                    if (srcUvY < 0) srcUvY = 0;
                    if (srcUvY >= maxUvH) srcUvY = maxUvH - 1;

                    int vIdx = srcUvY * vRowStride + srcUvX * vPixStride;
                    int uIdx = srcUvY * uRowStride + srcUvX * uPixStride;
                    dstNv21[uvPos++] = vBuf.get(vIdx);
                    dstNv21[uvPos++] = uBuf.get(uIdx);
                }
            }
        } else if (rot == 180) {
            // 180 deg
            int pos = 0;
            for (int y = 0; y < dstH; y++) {
                int srcY = baseTop + ((dstH - 1 - y) * srcH / dstH);
                if (srcY < 0) srcY = 0;
                if (srcY >= cropH) srcY = cropH - 1;
                int rowOffset = srcY * yRowStride;

                for (int x = 0; x < dstW; x++) {
                    int curX = mirror ? (dstW - 1 - x) : x;
                    int srcX = baseLeft + ((dstW - 1 - curX) * srcW / dstW);
                    if (srcX < 0) srcX = 0;
                    if (srcX >= cropW) srcX = cropW - 1;

                    byte yVal = yBuf.get(rowOffset + srcX * yPixStride);
                    if (kycFlash) yVal = (byte) Math.min(255, (yVal & 0xFF) + 60);
                    dstNv21[pos++] = yVal;
                }
            }

            int uvPos = uvDstOffset;
            for (int y = 0; y < dstUvH; y++) {
                int srcUvY = baseUvTop + ((dstUvH - 1 - y) * srcUvH / dstUvH);
                if (srcUvY < 0) srcUvY = 0;
                if (srcUvY >= maxUvH) srcUvY = maxUvH - 1;
                int vRowOffset = srcUvY * vRowStride;
                int uRowOffset = srcUvY * uRowStride;

                for (int x = 0; x < dstUvW; x++) {
                    int curX = mirror ? (dstUvW - 1 - x) : x;
                    int srcUvX = baseUvLeft + ((dstUvW - 1 - curX) * srcUvW / dstUvW);
                    if (srcUvX < 0) srcUvX = 0;
                    if (srcUvX >= maxUvW) srcUvX = maxUvW - 1;

                    dstNv21[uvPos++] = vBuf.get(vRowOffset + srcUvX * vPixStride);
                    dstNv21[uvPos++] = uBuf.get(uRowOffset + srcUvX * uPixStride);
                }
            }
        } else {
            // rot == 0
            int pos = 0;
            for (int y = 0; y < dstH; y++) {
                int srcY = baseTop + (y * srcH / dstH);
                if (srcY < 0) srcY = 0;
                if (srcY >= cropH) srcY = cropH - 1;
                int rowOffset = srcY * yRowStride;

                for (int x = 0; x < dstW; x++) {
                    int curX = mirror ? (dstW - 1 - x) : x;
                    int srcX = baseLeft + (curX * srcW / dstW);
                    if (srcX < 0) srcX = 0;
                    if (srcX >= cropW) srcX = cropW - 1;

                    byte yVal = yBuf.get(rowOffset + srcX * yPixStride);
                    if (kycFlash) yVal = (byte) Math.min(255, (yVal & 0xFF) + 60);
                    dstNv21[pos++] = yVal;
                }
            }

            int uvPos = uvDstOffset;
            for (int y = 0; y < dstUvH; y++) {
                int srcUvY = baseUvTop + (y * srcUvH / dstUvH);
                if (srcUvY < 0) srcUvY = 0;
                if (srcUvY >= maxUvH) srcUvY = maxUvH - 1;
                int vRowOffset = srcUvY * vRowStride;
                int uRowOffset = srcUvY * uRowStride;

                for (int x = 0; x < dstUvW; x++) {
                    int curX = mirror ? (dstUvW - 1 - x) : x;
                    int srcUvX = baseUvLeft + (curX * srcUvW / dstUvW);
                    if (srcUvX < 0) srcUvX = 0;
                    if (srcUvX >= maxUvW) srcUvX = maxUvW - 1;

                    dstNv21[uvPos++] = vBuf.get(vRowOffset + srcUvX * vPixStride);
                    dstNv21[uvPos++] = uBuf.get(uRowOffset + srcUvX * uPixStride);
                }
            }
        }
    }
}
