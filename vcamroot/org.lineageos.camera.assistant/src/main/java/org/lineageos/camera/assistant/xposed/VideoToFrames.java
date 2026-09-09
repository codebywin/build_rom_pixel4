package org.lineageos.camera.assistant.xposed;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class VideoToFrames implements Runnable {
    private static final String TAG = "VcamVideoFrames";
    private static final long TIMEOUT_US = 10000;

    private volatile boolean mStopDecode = false;
    private String mVideoPath;
    private final List<Surface> mTargetSurfaces = new ArrayList<>();
    private Thread mWorkerThread;

    public void setSurface(Surface surface) {
        synchronized (mTargetSurfaces) {
            mTargetSurfaces.clear();
            if (surface != null && surface.isValid()) {
                mTargetSurfaces.add(surface);
            }
        }
    }

    public void setSurfaces(List<Surface> surfaces) {
        synchronized (mTargetSurfaces) {
            mTargetSurfaces.clear();
            if (surfaces != null) {
                for (Surface s : surfaces) {
                    if (s != null && s.isValid()) {
                        mTargetSurfaces.add(s);
                    }
                }
            }
        }
    }

    public Surface getSurface() {
        synchronized (mTargetSurfaces) {
            return mTargetSurfaces.isEmpty() ? null : mTargetSurfaces.get(0);
        }
    }

    public boolean isPlaying() {
        return !mStopDecode && mWorkerThread != null && mWorkerThread.isAlive();
    }

    public synchronized void decode(String videoPath) {
        mVideoPath = videoPath;
        mStopDecode = false;
        if (mWorkerThread == null || !mWorkerThread.isAlive()) {
            mWorkerThread = new Thread(this, "VcamDecodeThread");
            mWorkerThread.start();
        }
    }

    public synchronized void stopDecode() {
        mStopDecode = true;
        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            mWorkerThread = null;
        }
    }

    @Override
    public void run() {
        List<Surface> targets;
        synchronized (mTargetSurfaces) {
            targets = new ArrayList<>(mTargetSurfaces);
        }
        if (targets.isEmpty()) {
            Log.e(TAG, "Cannot decode: No valid target surfaces");
            return;
        }

        File videoFile = new File(mVideoPath);
        if (!videoFile.exists() || videoFile.length() == 0) {
            Log.e(TAG, "Video file does not exist: " + mVideoPath);
            return;
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        VcamRenderer renderer = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(mVideoPath);

            int trackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    trackIndex = i;
                    break;
                }
            }

            if (trackIndex < 0) {
                Log.e(TAG, "No video track found in " + mVideoPath);
                return;
            }

            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);

            int videoWidth = 1280;
            int videoHeight = 720;
            try {
                if (mediaFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                    videoWidth = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                }
                if (mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    videoHeight = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                }
            } catch (Throwable ignored) {}

            renderer = new VcamRenderer();
            boolean useRenderer = renderer.init(targets, videoWidth, videoHeight);
            Surface decodeSurface;

            int currentRotation = XposedSharedConfig.getRotation();
            if (useRenderer) {
                decodeSurface = renderer.getInputSurface();
                Log.i(TAG, "Hardware decoder rendering via OpenGL ES VcamRenderer (" + targets.size() + " targets)");
            } else {
                renderer = null;
                decodeSurface = targets.get(0);
                if (currentRotation != 0) {
                    mediaFormat.setInteger(MediaFormat.KEY_ROTATION, currentRotation);
                }
                Log.i(TAG, "Hardware decoder rendering directly to Surface (fallback)");
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(mediaFormat, decodeSurface, null, 0);
            decoder.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            long startWhen = System.currentTimeMillis();
            String lastResetTs = XposedSharedConfig.getResetTimestamp();

            while (!mStopDecode) {
                // 1. Kiểm tra VCAM bị tắt (disable)
                if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    continue;
                }

                // 2. Kiểm tra tua lại / Reset (Rewind to 00:00)
                String currentResetTs = XposedSharedConfig.getResetTimestamp();
                if (!currentResetTs.isEmpty() && !currentResetTs.equals(lastResetTs)) {
                    lastResetTs = currentResetTs;
                    Log.i(TAG, "Rewind triggered by timestamp: " + currentResetTs);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    try { decoder.flush(); } catch (Throwable ignored) {}
                    sawInputEOS = false;
                    sawOutputEOS = false;
                    startWhen = System.currentTimeMillis();
                }

                // 3. Kiểm tra xoay video động (Dynamic Rotation: 0, 90, 180, 270)
                int targetRotation = XposedSharedConfig.getRotation();
                if (targetRotation != currentRotation) {
                    currentRotation = targetRotation;
                    Log.i(TAG, "Rotation updated: " + currentRotation + "°");
                    if (renderer != null) {
                        // VcamRenderer handles rotation dynamically via Shader matrix!
                        renderer.renderFrame();
                    } else {
                        // Fallback codec re-init
                        long currentPosUs = extractor.getSampleTime();
                        try {
                            decoder.stop();
                            decoder.release();
                        } catch (Throwable ignored) {}

                        mediaFormat.setInteger(MediaFormat.KEY_ROTATION, currentRotation);
                        decoder = MediaCodec.createDecoderByType(mime);
                        decoder.configure(mediaFormat, decodeSurface, null, 0);
                        decoder.start();

                        if (currentPosUs >= 0) {
                            extractor.seekTo(currentPosUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            startWhen = System.currentTimeMillis() - (extractor.getSampleTime() / 1000);
                        } else {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            startWhen = System.currentTimeMillis();
                        }
                        sawInputEOS = false;
                        sawOutputEOS = false;
                        continue;
                    }
                }

                // 4. Kiểm tra tạm dừng (Pause) - Giữ nguyên vị trí, cập nhật frame nếu chỉnh zoom/pan/color/rotate
                if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                    long pauseStart = System.currentTimeMillis();
                    while (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE) && !mStopDecode) {
                        if (renderer != null) {
                            renderer.renderFrame();
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                    }
                    long pauseDuration = System.currentTimeMillis() - pauseStart;
                    startWhen += pauseDuration;
                    continue;
                }

                // 5. Nạp buffer giải mã (Decode Input)
                if (!sawInputEOS) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // 6. Xuất khung hình ra Surface (Render Output)
                int outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }

                    if (bufferInfo.size != 0) {
                        long presentationMs = bufferInfo.presentationTimeUs / 1000;
                        long elapsedMs = System.currentTimeMillis() - startWhen;
                        long sleepTime = presentationMs - elapsedMs;

                        if (sleepTime > 50) {
                            sleepTime = 33;
                        }
                        if (sleepTime > 0) {
                            try {
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException ignored) {}
                        } else if (sleepTime < -500) {
                            startWhen = System.currentTimeMillis() - presentationMs;
                        }

                        if (decodeSurface != null && decodeSurface.isValid()) {
                            decoder.releaseOutputBuffer(outIndex, true);
                            if (renderer != null) {
                                renderer.renderFrame();
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIndex, false);
                            break;
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false);
                    }
                }

                // 7. Tự động lặp lại video vô tận khi hết video
                if (sawOutputEOS) {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    try { decoder.flush(); } catch (Throwable ignored) {}
                    sawInputEOS = false;
                    sawOutputEOS = false;
                    startWhen = System.currentTimeMillis();
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "VideoToFrames execution error: " + t.getMessage(), t);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Throwable ignored) {}
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {}
            }
            if (renderer != null) {
                try {
                    renderer.release();
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "VideoToFrames decoder stopped and released");
        }
    }
}
