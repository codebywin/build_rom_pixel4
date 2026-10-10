package org.lineageos.camera.assistant;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VcamLiveShmWriter: High-performance Shared Memory Video Streamer.
 * Decodes video frames into NV12 format and writes them directly into
 * double-buffered shared memory (/data/local/tmp/vcam_live.shm).
 *
 * Compatible with:
 *  - Native Hook (cameraserver libvcam_hook.so / video_decoder.cpp)
 *  - ROM Camera2 Framework (VcamLiveFeedShm.java)
 */
public class VcamLiveShmWriter {
    private static final String TAG = "VcamLiveShmWriter";

    public static final String SHM_FILE = "/data/local/tmp/vcam_live.shm";
    public static final int VCAM_SHM_MAGIC = 0x5643414D; // 'VCAM'
    public static final int VCAM_SHM_VERSION = 1;

    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1920;
    public static final int MAX_FRAME_SIZE = MAX_WIDTH * MAX_HEIGHT * 3 / 2; // 5,529,600 bytes
    public static final int HEADER_SIZE = 112;
    public static final int TOTAL_SHM_SIZE = HEADER_SIZE + 2 * MAX_FRAME_SIZE; // 11,059,312 bytes

    private static final int BUFFER_0_OFFSET = HEADER_SIZE;
    private static final int BUFFER_1_OFFSET = HEADER_SIZE + MAX_FRAME_SIZE;

    private static VcamLiveShmWriter sInstance = null;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private Thread mWorkerThread = null;
    private File mCurrentVideoFile = null;

    public static synchronized VcamLiveShmWriter getInstance() {
        if (sInstance == null) {
            sInstance = new VcamLiveShmWriter();
        }
        return sInstance;
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    public synchronized void start(File videoFile) {
        if (mRunning.get()) {
            stop();
        }
        if (videoFile == null || !videoFile.exists() || !videoFile.canRead()) {
            Log.e(TAG, "Invalid video file: " + videoFile);
            return;
        }

        mCurrentVideoFile = videoFile;
        mRunning.set(true);

        mWorkerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runStreamLoop();
            }
        }, "VcamLiveShmStreamer");
        mWorkerThread.start();
        Log.i(TAG, "Live Feed SHM streamer started for: " + videoFile.getAbsolutePath());
    }

    public synchronized void stop() {
        mRunning.set(false);
        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            try {
                mWorkerThread.join(1000);
            } catch (InterruptedException ignored) {}
            mWorkerThread = null;
        }
        clearShmTimestamp();
        Log.i(TAG, "Live Feed SHM streamer stopped.");
    }

    private void clearShmTimestamp() {
        try {
            File file = new File(SHM_FILE);
            if (file.exists() && file.length() >= HEADER_SIZE) {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.seek(40); // timestamp_ms offset
                raf.writeLong(0L);
                raf.close();
            }
        } catch (Throwable ignored) {}
    }

    private void runStreamLoop() {
        RandomAccessFile raf = null;
        FileChannel channel = null;
        MappedByteBuffer shm = null;

        try {
            File shmFile = new File(SHM_FILE);
            File parent = shmFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            raf = new RandomAccessFile(shmFile, "rw");
            raf.setLength(TOTAL_SHM_SIZE);
            channel = raf.getChannel();
            shm = channel.map(FileChannel.MapMode.READ_WRITE, 0, TOTAL_SHM_SIZE);
            shm.order(ByteOrder.LITTLE_ENDIAN);

            // Initialize header
            shm.putInt(0, VCAM_SHM_MAGIC);
            shm.putInt(4, VCAM_SHM_VERSION);

            int frameIndex = 0;
            int activeBuf = 0;

            while (mRunning.get()) {
                MediaExtractor extractor = null;
                MediaCodec decoder = null;

                try {
                    extractor = new MediaExtractor();
                    extractor.setDataSource(mCurrentVideoFile.getAbsolutePath());

                    int trackIdx = -1;
                    MediaFormat format = null;
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat mf = extractor.getTrackFormat(i);
                        String mime = mf.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) {
                            trackIdx = i;
                            format = mf;
                            break;
                        }
                    }

                    if (trackIdx < 0 || format == null) {
                        Log.e(TAG, "No video track found in " + mCurrentVideoFile);
                        Thread.sleep(1000);
                        continue;
                    }

                    extractor.selectTrack(trackIdx);
                    String mime = format.getString(MediaFormat.KEY_MIME);

                    // Prefer YUV420SemiPlanar (NV12)
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

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

                    byte[] nv12Buffer = null;

                    while (mRunning.get()) {
                        int inIdx = decoder.dequeueInputBuffer(5000);
                        if (inIdx >= 0) {
                            ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                            if (inBuf != null) {
                                int sampleSize = extractor.readSampleData(inBuf, 0);
                                if (sampleSize < 0) {
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

                        int outIdx = decoder.dequeueOutputBuffer(info, 5000);
                        if (outIdx >= 0) {
                            long now = System.currentTimeMillis();
                            if (now < nextFrameTimeMs) {
                                try {
                                    Thread.sleep(Math.max(1, nextFrameTimeMs - now));
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                            nextFrameTimeMs = System.currentTimeMillis() + frameDelayMs;

                            Image image = decoder.getOutputImage(outIdx);
                            if (image != null) {
                                int width = image.getWidth();
                                int height = image.getHeight();
                                int frameSize = width * height * 3 / 2;

                                if (nv12Buffer == null || nv12Buffer.length != frameSize) {
                                    nv12Buffer = new byte[frameSize];
                                }

                                convertImageToNv12(image, nv12Buffer, width, height);
                                image.close();

                                // Write to inactive buffer
                                int writeBuf = 1 - activeBuf;
                                int destOffset = (writeBuf == 1) ? BUFFER_1_OFFSET : BUFFER_0_OFFSET;

                                shm.position(destOffset);
                                shm.put(nv12Buffer, 0, frameSize);

                                // Update header
                                frameIndex++;
                                shm.putInt(8, width);                // width
                                shm.putInt(12, height);              // height
                                shm.putInt(16, width);               // stride
                                shm.putInt(20, height);              // slice_height
                                shm.putInt(24, 21);                  // color_format (NV12)
                                shm.putInt(28, frameSize);           // frame_size
                                shm.putInt(32, frameIndex);          // frame_index
                                shm.putInt(36, writeBuf);            // active_buf
                                shm.putLong(40, System.currentTimeMillis()); // timestamp_ms

                                activeBuf = writeBuf;
                            }
                            decoder.releaseOutputBuffer(outIdx, false);
                        }
                    }
                } catch (Throwable t) {
                    if (mRunning.get()) {
                        Log.e(TAG, "Stream loop exception: " + t.getMessage(), t);
                    }
                } finally {
                    if (decoder != null) {
                        try { decoder.stop(); decoder.release(); } catch (Throwable ignored) {}
                    }
                    if (extractor != null) {
                        try { extractor.release(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal SHM error: " + t.getMessage(), t);
        } finally {
            clearShmTimestamp();
            if (channel != null) {
                try { channel.close(); } catch (Throwable ignored) {}
            }
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void convertImageToNv12(Image image, byte[] nv12, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();

        ByteBuffer uBuf = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixStride = planes[1].getPixelStride();

        ByteBuffer vBuf = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixStride = planes[2].getPixelStride();

        // 1. Copy Y plane
        int pos = 0;
        for (int r = 0; r < height; r++) {
            yBuf.position(r * yRowStride);
            yBuf.get(nv12, pos, width);
            pos += width;
        }

        // 2. Copy UV interleaved (NV12: U then V)
        int uvH = height / 2;
        int uvW = width / 2;
        int uvStartPos = width * height;

        for (int r = 0; r < uvH; r++) {
            for (int c = 0; c < uvW; c++) {
                int uIdx = r * uRowStride + c * uPixStride;
                int vIdx = r * vRowStride + c * vPixStride;
                nv12[uvStartPos++] = uBuf.get(uIdx); // U
                nv12[uvStartPos++] = vBuf.get(vIdx); // V
            }
        }
    }
}

