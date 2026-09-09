package org.lineageos.camera.assistant.xposed;

import android.media.AudioRecord;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class XposedAudioHook {
    private static final String TAG = "VcamAudioHook";

    private static FileInputStream sAudioStream = null;
    private static long sAudioPos = 0;
    private static long sAudioDataStart = 44; // Standard WAV header length
    private static String sLastResetTs = "";

    public static void initHook(ClassLoader classLoader) {
        try {
            Class<?> audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", classLoader);

            // Hook: read(byte[] audioData, int offsetInBytes, int sizeInBytes)
            XposedHelpers.findAndHookMethod(audioRecordClass, "read", byte[].class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;

                    byte[] audioData = (byte[]) param.args[0];
                    int offset = (Integer) param.args[1];
                    int size = (Integer) param.args[2];
                    int readBytes = (Integer) param.getResult();

                    if (readBytes > 0 && audioData != null) {
                        processAudioBytes(audioData, offset, readBytes);
                    }
                }
            });

            // Hook: read(short[] audioData, int offsetInShorts, int sizeInShorts)
            XposedHelpers.findAndHookMethod(audioRecordClass, "read", short[].class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;

                    short[] audioData = (short[]) param.args[0];
                    int offset = (Integer) param.args[1];
                    int size = (Integer) param.args[2];
                    int readShorts = (Integer) param.getResult();

                    if (readShorts > 0 && audioData != null) {
                        processAudioShorts(audioData, offset, readShorts);
                    }
                }
            });

            Log.i(TAG, "AudioRecord hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AudioRecord", t);
        }
    }

    private static synchronized void processAudioBytes(byte[] buffer, int offset, int length) {
        boolean isMicDisable = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE);
        boolean isMicMix = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_MIX);
        float boost = XposedSharedConfig.getMicBoost();
        File wavFile = XposedSharedConfig.getAudioFile();

        // 1. Trường hợp mic bị tắt hoàn toàn
        if (isMicDisable && wavFile == null) {
            java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
            return;
        }

        // 2. Không có file nhạc ảo -> Áp dụng khuếch đại Mic thật
        if (wavFile == null) {
            if (boost > 1.0f) {
                applyBoostBytes(buffer, offset, length, boost);
            }
            return;
        }

        // 3. Có nhạc ảo nhưng đang tạm dừng
        if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
            if (!isMicMix) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
            }
            return;
        }

        // 4. Đọc luồng nhạc ảo
        byte[] virtualChunk = new byte[length];
        int bytesRead = readWavChunk(wavFile, virtualChunk, length);
        if (bytesRead <= 0) return;

        if (isMicMix && !isMicDisable) {
            // Trộn nhạc ảo với mic thật (Mic thật được khuếch đại theo boost)
            for (int i = 0; i < length - 1; i += 2) {
                int idx = offset + i;
                short orig = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
                short virt = (short) ((virtualChunk[i] & 0xFF) | (virtualChunk[i + 1] << 8));
                int mixed = (int) (orig * boost + virt * 0.8f);
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                buffer[idx] = (byte) (mixed & 0xFF);
                buffer[idx + 1] = (byte) ((mixed >> 8) & 0xFF);
            }
        } else {
            // 100% tiếng nhạc ảo
            System.arraycopy(virtualChunk, 0, buffer, offset, length);
        }
    }

    private static synchronized void processAudioShorts(short[] buffer, int offset, int length) {
        boolean isMicDisable = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE);
        boolean isMicMix = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_MIX);
        float boost = XposedSharedConfig.getMicBoost();
        File wavFile = XposedSharedConfig.getAudioFile();

        // 1. Trường hợp mic bị tắt hoàn toàn
        if (isMicDisable && wavFile == null) {
            java.util.Arrays.fill(buffer, offset, offset + length, (short) 0);
            return;
        }

        // 2. Không có file nhạc ảo -> Áp dụng khuếch đại Mic thật
        if (wavFile == null) {
            if (boost > 1.0f) {
                for (int i = 0; i < length; i++) {
                    int val = (int) (buffer[offset + i] * boost);
                    if (val > Short.MAX_VALUE) val = Short.MAX_VALUE;
                    if (val < Short.MIN_VALUE) val = Short.MIN_VALUE;
                    buffer[offset + i] = (short) val;
                }
            }
            return;
        }

        // 3. Có nhạc ảo nhưng đang tạm dừng
        if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
            if (!isMicMix) {
                java.util.Arrays.fill(buffer, offset, offset + length, (short) 0);
            }
            return;
        }

        // 4. Đọc luồng nhạc ảo
        int byteLen = length * 2;
        byte[] raw = new byte[byteLen];
        int bytesRead = readWavChunk(wavFile, raw, byteLen);
        if (bytesRead <= 0) return;

        short[] virtShorts = new short[length];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(virtShorts);

        if (isMicMix && !isMicDisable) {
            for (int i = 0; i < length; i++) {
                int mixed = (int) (buffer[offset + i] * boost + virtShorts[i] * 0.8f);
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                buffer[offset + i] = (short) mixed;
            }
        } else {
            System.arraycopy(virtShorts, 0, buffer, offset, length);
        }
    }

    private static void applyBoostBytes(byte[] buffer, int offset, int length, float boost) {
        for (int i = 0; i < length - 1; i += 2) {
            int idx = offset + i;
            short val = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int boosted = (int) (val * boost);
            if (boosted > Short.MAX_VALUE) boosted = Short.MAX_VALUE;
            if (boosted < Short.MIN_VALUE) boosted = Short.MIN_VALUE;
            buffer[idx] = (byte) (boosted & 0xFF);
            buffer[idx + 1] = (byte) ((boosted >> 8) & 0xFF);
        }
    }

    private static int readWavChunk(File wavFile, byte[] out, int len) {
        try {
            String currentResetTs = XposedSharedConfig.getResetTimestamp();
            if (!currentResetTs.isEmpty() && !currentResetTs.equals(sLastResetTs)) {
                sLastResetTs = currentResetTs;
                if (sAudioStream != null) {
                    try { sAudioStream.close(); } catch (Throwable ignored) {}
                    sAudioStream = null;
                }
            }

            if (sAudioStream == null) {
                sAudioStream = new FileInputStream(wavFile);
                sAudioStream.skip(sAudioDataStart);
                sAudioPos = sAudioDataStart;
            }

            int read = sAudioStream.read(out, 0, len);
            if (read < len) {
                // Hết file -> Lặp lại từ đầu
                sAudioStream.close();
                sAudioStream = new FileInputStream(wavFile);
                sAudioStream.skip(sAudioDataStart);
                sAudioPos = sAudioDataStart;
                if (read > 0) {
                    sAudioStream.read(out, read, len - read);
                } else {
                    read = sAudioStream.read(out, 0, len);
                }
            }
            return len;
        } catch (Throwable t) {
            return 0;
        }
    }
}
