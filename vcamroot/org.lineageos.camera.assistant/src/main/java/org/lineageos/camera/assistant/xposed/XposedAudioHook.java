package org.lineageos.camera.assistant.xposed;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class XposedAudioHook {
    private static final String TAG = "VcamAudioHook";

    private static final ThreadLocal<Integer> sHookDepth = ThreadLocal.withInitial(() -> 0);
    private static FileInputStream sAudioStream = null;
    private static long sAudioPos = 0;
    private static long sAudioDataStart = 44; // Standard WAV header length
    private static String sLastResetTs = "";
    private static long sLastLogTime = 0;
    private static long sLastWebRtcLogTime = 0;

    private static final Set<Class<?>> sHookedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static XC_MethodHook returnConstant(final Object value) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(value);
            }
        };
    }

    public static void initHook(ClassLoader classLoader) {
        try {
            // 1. Hook AudioRecord constructors: Redirect VOICE_COMMUNICATION (7) -> MIC (1)
            hookAudioRecordConstructors();

            // 2. Disable hardware AcousticEchoCanceler and NoiseSuppressor
            hookAudioEffects();

            // 3. Hook WebRTC AudioRecord & AudioEffects
            hookWebRtc(classLoader);

            // 4. Hook ClassLoader.loadClass for dynamically loaded WebRTC classes
            hookClassLoader();

            // 5. Hook AudioRecord.read(byte[]) and read(short[]) for standard Android apps
            hookAudioRecordRead(classLoader);

            Log.i(TAG, "XposedAudioHook fully initialized (Hardware AEC/NS bypassed, MIC redirection active)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize XposedAudioHook", t);
        }
    }

    private static void hookAudioRecordConstructors() {
        try {
            for (Constructor<?> c : AudioRecord.class.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Class<?>[] pTypes = c.getParameterTypes();
                        if (pTypes.length > 0 && pTypes[0] == int.class) {
                            if (param.args[0] instanceof Integer && (Integer) param.args[0] == 7 /* VOICE_COMMUNICATION */) {
                                Log.i(TAG, "AudioRecord constructor: redirected audioSource 7 (VOICE_COMMUNICATION) -> 1 (MIC)");
                                param.args[0] = MediaRecorder.AudioSource.MIC;
                            }
                        } else if (pTypes.length > 0 && pTypes[0] == AudioAttributes.class) {
                            AudioAttributes attr = (AudioAttributes) param.args[0];
                            if (attr != null) {
                                boolean isVoiceComm = false;
                                try {
                                    Method m = AudioAttributes.class.getMethod("getCapturePreset");
                                    int preset = (Integer) m.invoke(attr);
                                    if (preset == 7) isVoiceComm = true;
                                } catch (Throwable ignored) {}

                                if (!isVoiceComm && attr.getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                                    isVoiceComm = true;
                                }

                                if (isVoiceComm) {
                                    Log.i(TAG, "AudioRecord constructor: redirected AudioAttributes preset 7 -> 1 (MIC)");
                                    AudioAttributes.Builder b = new AudioAttributes.Builder()
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
                                    try {
                                        Method setPreset = AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
                                        setPreset.invoke(b, MediaRecorder.AudioSource.MIC);
                                    } catch (Throwable ignored) {}
                                    param.args[0] = b.build();
                                }
                            }
                        }
                    }
                });
            }
            Log.i(TAG, "AudioRecord constructors hooked successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook AudioRecord constructors: " + t.getMessage());
        }
    }

    private static void hookAudioEffects() {
        try {
            XposedHelpers.findAndHookMethod(AcousticEchoCanceler.class, "isAvailable",
                    returnConstant(false));
            XposedHelpers.findAndHookMethod(AcousticEchoCanceler.class, "create", int.class,
                    returnConstant(null));
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook AcousticEchoCanceler: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(NoiseSuppressor.class, "isAvailable",
                    returnConstant(false));
            XposedHelpers.findAndHookMethod(NoiseSuppressor.class, "create", int.class,
                    returnConstant(null));
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook NoiseSuppressor: " + t.getMessage());
        }
    }

    private static void hookClassLoader() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String className = (String) param.args[0];
                    if (className != null) {
                        if (className.contains("WebRtcAudioEffects")) {
                            Class<?> cls = (Class<?>) param.getResult();
                            hookWebRtcEffects(cls);
                        } else if (className.contains("WebRtcAudioRecord")) {
                            Class<?> cls = (Class<?>) param.getResult();
                            hookWebRtcRecord(cls);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook ClassLoader.loadClass: " + t.getMessage());
        }
    }

    private static void hookWebRtc(ClassLoader classLoader) {
        String[] effectClasses = {
                "org.webrtc.voiceengine.WebRtcAudioEffects",
                "org.webrtc.audio.WebRtcAudioEffects",
                "org.webrtc.WebRtcAudioEffects"
        };
        for (String clsName : effectClasses) {
            Class<?> cls = XposedHelpers.findClassIfExists(clsName, classLoader);
            if (cls != null) {
                hookWebRtcEffects(cls);
            }
        }

        String[] recordClasses = {
                "org.webrtc.voiceengine.WebRtcAudioRecord",
                "org.webrtc.audio.WebRtcAudioRecord",
                "org.webrtc.WebRtcAudioRecord"
        };
        for (String clsName : recordClasses) {
            Class<?> cls = XposedHelpers.findClassIfExists(clsName, classLoader);
            if (cls != null) {
                hookWebRtcRecord(cls);
            }
        }
    }

    private static void hookWebRtcEffects(Class<?> effectsClass) {
        if (effectsClass == null || !sHookedClasses.add(effectsClass)) return;
        try {
            for (Method m : effectsClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("canUseAcousticEchoCanceler".equals(name) || "isAcousticEchoCancelerSupported".equals(name)
                        || "canUseNoiseSuppressor".equals(name) || "isNoiseSuppressorSupported".equals(name)) {
                    XposedBridge.hookMethod(m, returnConstant(false));
                    Log.i(TAG, "WebRtcAudioEffects." + name + " hooked -> return false");
                } else if ("setAEC".equals(name) || "setNS".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.args[0] = false;
                        }
                    });
                    Log.i(TAG, "WebRtcAudioEffects." + name + " hooked -> force false");
                } else if ("enable".equals(name)) {
                    XposedBridge.hookMethod(m, returnConstant(null));
                    Log.i(TAG, "WebRtcAudioEffects.enable hooked -> no-op");
                }
            }
            Log.i(TAG, "WebRtcAudioEffects hooks installed for " + effectsClass.getName());
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking WebRtcAudioEffects: " + t.getMessage());
        }
    }

    private static void hookWebRtcRecord(Class<?> recordClass) {
        if (recordClass == null || !sHookedClasses.add(recordClass)) return;
        try {
            for (Method m : recordClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("initRecording".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Log.i(TAG, "WebRtcAudioRecord.initRecording called: " + java.util.Arrays.toString(param.args));
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Integer && (Integer) param.args[i] == 7) {
                                    Log.i(TAG, "WebRtcAudioRecord.initRecording: redirecting audioSource 7 -> 1 (MIC)");
                                    param.args[i] = MediaRecorder.AudioSource.MIC;
                                }
                            }
                        }
                    });
                } else if ("setMicrophoneMute".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                                Log.w(TAG, "WebRtcAudioRecord.setMicrophoneMute(true) intercepted -> forcing false!");
                                param.args[0] = Boolean.FALSE;
                            }
                        }
                    });
                } else if ("enableBuiltInAEC".equals(name) || "enableBuiltInNS".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.args[0] = false;
                        }
                    });
                } else if ("nativeDataIsRecorded".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            processWebRtcAudio(param);
                        }
                    });
                    Log.i(TAG, "WebRtcAudioRecord.nativeDataIsRecorded hooked successfully");
                }
            }
            Log.i(TAG, "WebRtcAudioRecord hooks installed for " + recordClass.getName());
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking WebRtcAudioRecord: " + t.getMessage());
        }
    }

    private static void processWebRtcAudio(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) return;
        Object bytesObj = param.args[0];
        if (!(bytesObj instanceof Integer)) return;
        int bytesRecorded = (Integer) bytesObj;
        if (bytesRecorded <= 0) return;

        try {
            ByteBuffer buf = (ByteBuffer) XposedHelpers.getObjectField(param.thisObject, "byteBuffer");
            if (buf == null) return;

            boolean isMicDisable = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE);
            boolean isMicMix = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_MIX);
            float boost = XposedSharedConfig.getMicBoost();
            File wavFile = XposedSharedConfig.getAudioFile();

            int samples = Math.min(bytesRecorded / 2, buf.capacity() / 2);
            int maxAmp = 0;

            // 1. Chế độ dùng Mic thật (vcam_mic_disable = 1) hoặc không có file WAV ảo
            if (isMicDisable || wavFile == null) {
                if (boost > 1.05f) {
                    for (int i = 0; i < samples; i++) {
                        short s = buf.getShort(i * 2);
                        int abs = Math.abs(s);
                        if (abs > maxAmp) maxAmp = abs;
                        int val = Math.round(s * boost);
                        if (val > Short.MAX_VALUE) val = Short.MAX_VALUE;
                        if (val < Short.MIN_VALUE) val = Short.MIN_VALUE;
                        buf.putShort(i * 2, (short) val);
                    }
                } else {
                    for (int i = 0; i < samples; i++) {
                        short s = buf.getShort(i * 2);
                        int abs = Math.abs(s);
                        if (abs > maxAmp) maxAmp = abs;
                    }
                }
            } else if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                // Nhạc ảo đang pause
                if (!isMicMix) {
                    for (int i = 0; i < samples; i++) {
                        buf.putShort(i * 2, (short) 0);
                    }
                }
            } else {
                // Đọc luồng nhạc ảo từ WAV
                byte[] virtualBytes = new byte[bytesRecorded];
                int read = readWavChunk(wavFile, virtualBytes, bytesRecorded);
                if (read > 0) {
                    ByteBuffer vBuf = ByteBuffer.wrap(virtualBytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < samples; i++) {
                        short virt = vBuf.getShort(i * 2);
                        if (isMicMix) {
                            short orig = buf.getShort(i * 2);
                            int mixed = (int) (orig * boost + virt * 0.8f);
                            if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                            if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                            buf.putShort(i * 2, (short) mixed);
                        } else {
                            buf.putShort(i * 2, virt);
                        }
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (now - sLastWebRtcLogTime > 1500) {
                sLastWebRtcLogTime = now;
                Log.i(TAG, "WebRTC Mic Stream: bytes=" + bytesRecorded + " peakAmp=" + maxAmp + " boost=" + boost);
            }
        } catch (Throwable ignored) {}
    }

    private static void hookAudioRecordRead(ClassLoader classLoader) {
        try {
            Class<?> audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", classLoader);

            XC_MethodHook byteHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sHookDepth.set(sHookDepth.get() + 1);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int current = sHookDepth.get();
                    int depth = Math.max(0, current - 1);
                    sHookDepth.set(depth);
                    if (depth > 0) return;

                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE)) return;

                    byte[] audioData = (byte[]) param.args[0];
                    int offset = (Integer) param.args[1];
                    int size = (Integer) param.args[2];
                    Object res = param.getResult();
                    if (res instanceof Integer) {
                        int readBytes = (Integer) res;
                        if (readBytes > 0 && audioData != null) {
                            processAudioBytes(audioData, offset, readBytes);
                        }
                    }
                }
            };

            try {
                XposedHelpers.findAndHookMethod(audioRecordClass, "read", byte[].class, int.class, int.class, byteHook);
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(audioRecordClass, "read", byte[].class, int.class, int.class, int.class, byteHook);
            } catch (Throwable ignored) {}

            XC_MethodHook shortHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sHookDepth.set(sHookDepth.get() + 1);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int current = sHookDepth.get();
                    int depth = Math.max(0, current - 1);
                    sHookDepth.set(depth);
                    if (depth > 0) return;

                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE)) return;

                    short[] audioData = (short[]) param.args[0];
                    int offset = (Integer) param.args[1];
                    int size = (Integer) param.args[2];
                    Object res = param.getResult();
                    if (res instanceof Integer) {
                        int readShorts = (Integer) res;
                        if (readShorts > 0 && audioData != null) {
                            processAudioShorts(audioData, offset, readShorts);
                        }
                    }
                }
            };

            try {
                XposedHelpers.findAndHookMethod(audioRecordClass, "read", short[].class, int.class, int.class, shortHook);
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(audioRecordClass, "read", short[].class, int.class, int.class, int.class, shortHook);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AudioRecord read byte/short", t);
        }
    }

    private static synchronized void processAudioBytes(byte[] buffer, int offset, int length) {
        if (buffer == null || length <= 0) return;

        boolean isMicDisable = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE);
        boolean isMicMix = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_MIX);
        float boost = XposedSharedConfig.getMicBoost();
        File wavFile = XposedSharedConfig.getAudioFile();

        if (isMicDisable) {
            if (boost > 1.05f) {
                applyBoostBytes(buffer, offset, length, boost);
            }
            logAudioStatsBytes("Bytes[RealMic]", buffer, offset, length, boost);
            return;
        }

        if (wavFile == null) {
            if (boost > 1.05f) {
                applyBoostBytes(buffer, offset, length, boost);
            }
            logAudioStatsBytes("Bytes[NoWav-RealMic]", buffer, offset, length, boost);
            return;
        }

        if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
            if (!isMicMix) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
            }
            return;
        }

        byte[] virtualChunk = new byte[length];
        int bytesRead = readWavChunk(wavFile, virtualChunk, length);
        if (bytesRead <= 0) return;

        if (isMicMix) {
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
            logAudioStatsBytes("Bytes[Mix]", buffer, offset, length, boost);
        } else {
            System.arraycopy(virtualChunk, 0, buffer, offset, length);
            logAudioStatsBytes("Bytes[Virtual]", buffer, offset, length, 1.0f);
        }
    }

    private static synchronized void processAudioShorts(short[] buffer, int offset, int length) {
        if (buffer == null || length <= 0) return;

        boolean isMicDisable = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_DISABLE);
        boolean isMicMix = XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_MIC_MIX);
        float boost = XposedSharedConfig.getMicBoost();
        File wavFile = XposedSharedConfig.getAudioFile();

        if (isMicDisable) {
            if (boost > 1.05f) {
                applyBoostShorts(buffer, offset, length, boost);
            }
            logAudioStatsShorts("Shorts[RealMic]", buffer, offset, length, boost);
            return;
        }

        if (wavFile == null) {
            if (boost > 1.05f) {
                applyBoostShorts(buffer, offset, length, boost);
            }
            logAudioStatsShorts("Shorts[NoWav-RealMic]", buffer, offset, length, boost);
            return;
        }

        if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
            if (!isMicMix) {
                java.util.Arrays.fill(buffer, offset, offset + length, (short) 0);
            }
            return;
        }

        int byteLen = length * 2;
        byte[] raw = new byte[byteLen];
        int bytesRead = readWavChunk(wavFile, raw, byteLen);
        if (bytesRead <= 0) return;

        short[] virtShorts = new short[length];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(virtShorts);

        if (isMicMix) {
            for (int i = 0; i < length; i++) {
                int mixed = (int) (buffer[offset + i] * boost + virtShorts[i] * 0.8f);
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                buffer[offset + i] = (short) mixed;
            }
            logAudioStatsShorts("Shorts[Mix]", buffer, offset, length, boost);
        } else {
            System.arraycopy(virtShorts, 0, buffer, offset, length);
            logAudioStatsShorts("Shorts[Virtual]", buffer, offset, length, 1.0f);
        }
    }

    private static void applyBoostBytes(byte[] buffer, int offset, int length, float boost) {
        for (int i = 0; i < length - 1; i += 2) {
            int idx = offset + i;
            short val = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int boosted = Math.round(val * boost);
            if (boosted > Short.MAX_VALUE) boosted = Short.MAX_VALUE;
            if (boosted < Short.MIN_VALUE) boosted = Short.MIN_VALUE;
            buffer[idx] = (byte) (boosted & 0xFF);
            buffer[idx + 1] = (byte) ((boosted >> 8) & 0xFF);
        }
    }

    private static void applyBoostShorts(short[] buffer, int offset, int length, float boost) {
        for (int i = 0; i < length; i++) {
            int val = Math.round(buffer[offset + i] * boost);
            if (val > Short.MAX_VALUE) val = Short.MAX_VALUE;
            if (val < Short.MIN_VALUE) val = Short.MIN_VALUE;
            buffer[offset + i] = (short) val;
        }
    }

    private static void logAudioStatsBytes(String tag, byte[] buffer, int offset, int length, float boost) {
        long now = System.currentTimeMillis();
        if (now - sLastLogTime > 1500) {
            sLastLogTime = now;
            try {
                int maxAmp = 0;
                for (int i = 0; i < length - 1; i += 2) {
                    int idx = offset + i;
                    short val = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
                    int abs = Math.abs(val);
                    if (abs > maxAmp) maxAmp = abs;
                }
                Log.i(TAG, tag + " len=" + length + " peakAmp=" + maxAmp + " boost=" + boost);
            } catch (Throwable ignored) {}
        }
    }

    private static void logAudioStatsShorts(String tag, short[] buffer, int offset, int length, float boost) {
        long now = System.currentTimeMillis();
        if (now - sLastLogTime > 1500) {
            sLastLogTime = now;
            try {
                int maxAmp = 0;
                for (int i = 0; i < length; i++) {
                    int abs = Math.abs(buffer[offset + i]);
                    if (abs > maxAmp) maxAmp = abs;
                }
                Log.i(TAG, tag + " len=" + length + " peakAmp=" + maxAmp + " boost=" + boost);
            } catch (Throwable ignored) {}
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
