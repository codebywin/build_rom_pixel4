package org.lineageos.camera.assistant.xposed;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

public class VideoToFrames implements Runnable {
    private static final String TAG = "VcamVideoToFrames";
    private static final long TIMEOUT_US = 10000;

    private volatile boolean mStopDecode = false;
    private String mVideoPath;
    private Surface mSurface;
    private Thread mWorkerThread;

    public void setSurface(Surface surface) {
        mSurface = surface;
    }

    public Surface getSurface() {
        return mSurface;
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
        if (mSurface == null || !mSurface.isValid()) {
            Log.e(TAG, "Cannot decode: Surface is invalid or null");
            return;
        }

        File videoFile = new File(mVideoPath);
        if (!videoFile.exists() || videoFile.length() == 0) {
            Log.e(TAG, "Video file does not exist: " + mVideoPath);
            return;
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;

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

            int currentRotation = XposedSharedConfig.getRotation();
            if (currentRotation != 0) {
                mediaFormat.setInteger(MediaFormat.KEY_ROTATION, currentRotation);
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(mediaFormat, mSurface, null, 0);
            decoder.start();

            Log.i(TAG, "Hardware decoder started on Surface with rotation=" + currentRotation + "°");

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
                    Log.i(TAG, "Applying dynamic rotation: " + currentRotation + "°");
                    long currentPosUs = extractor.getSampleTime();

                    try {
                        decoder.stop();
                        decoder.release();
                    } catch (Throwable ignored) {}

                    mediaFormat.setInteger(MediaFormat.KEY_ROTATION, currentRotation);
                    decoder = MediaCodec.createDecoderByType(mime);
                    decoder.configure(mediaFormat, mSurface, null, 0);
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

                // 4. Kiểm tra tạm dừng (Pause) - Giữ nguyên vị trí, không tua nhanh khi tiếp tục
                if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                    long pauseStart = System.currentTimeMillis();
                    while (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE) && !mStopDecode) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                    }
                    long pauseDuration = System.currentTimeMillis() - pauseStart;
                    startWhen += pauseDuration; // Bù thời gian tạm dừng để không bị chạy vọt
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
                            sleepTime = 33; // Giới hạn độ trễ tối đa 33ms (tương đương 30fps)
                        }
                        if (sleepTime > 0) {
                            try {
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException ignored) {}
                        } else if (sleepTime < -500) {
                            // Bị chậm quá nhiều, đồng bộ lại đồng hồ chuẩn
                            startWhen = System.currentTimeMillis() - presentationMs;
                        }

                        if (mSurface != null && mSurface.isValid()) {
                            decoder.releaseOutputBuffer(outIndex, true);
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
            Log.i(TAG, "VideoToFrames decoder stopped and released");
        }
    }
}
