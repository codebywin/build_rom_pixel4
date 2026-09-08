package org.lineageos.camera.assistant.xposed;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(mediaFormat, mSurface, null, 0);
            decoder.start();

            Log.i(TAG, "Hardware decoder configured and started on Surface: " + mSurface);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            long startWhen = System.currentTimeMillis();

            while (!mStopDecode) {
                if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    continue;
                }

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
                            sleepTime = 33; // Cap excessive delays
                        }
                        if (sleepTime > 0) {
                            try {
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException ignored) {}
                        } else if (sleepTime < -500) {
                            // Falling behind significantly, resync reference clock
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

                // Check for end of stream or loop restart
                if (sawOutputEOS) {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();
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
