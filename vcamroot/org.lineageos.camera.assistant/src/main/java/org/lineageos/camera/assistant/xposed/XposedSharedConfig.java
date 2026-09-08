package org.lineageos.camera.assistant.xposed;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class XposedSharedConfig {
    public static final String FLAG_DISABLE = "vcam_disable";
    public static final String FLAG_PAUSE = "vcam_pause";
    public static final String FLAG_REWIND = "vcam_rewind";
    public static final String FLAG_ROTATE = "vcam_rotate";
    public static final String FLAG_MIC_DISABLE = "vcam_mic_disable";
    public static final String FLAG_MIC_MIX = "vcam_mic_mix";
    public static final String FILE_MIC_BOOST = "vcam_mic_boost";

    public static final String TARGET_VIDEO = "vcam.mp4";
    public static final String TARGET_AUDIO = "vcam.wav";

    public static boolean isFlagActive(String name) {
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name)
        };
        for (File f : targets) {
            if (f.exists()) {
                if (f.length() == 0) return true;
                try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                    String line = reader.readLine();
                    if (line != null && "0".equals(line.trim())) {
                        return false;
                    }
                } catch (Throwable ignored) {}
                return true;
            }
        }
        return false;
    }

    public static String getRotateValue() {
        return readString("vcam_rotate", "0");
    }

    public static float getMicBoost() {
        String val = readString(FILE_MIC_BOOST, "3.0");
        try {
            return Float.parseFloat(val);
        } catch (Throwable t) {
            return 3.0f;
        }
    }

    public static File getVideoFile() {
        File f1 = new File("/data/local/tmp/" + TARGET_VIDEO);
        if (f1.exists() && f1.length() > 0) return f1;
        File f2 = new File("/sdcard/" + TARGET_VIDEO);
        if (f2.exists() && f2.length() > 0) return f2;
        File f3 = new File("/storage/emulated/0/" + TARGET_VIDEO);
        if (f3.exists() && f3.length() > 0) return f3;
        return null;
    }

    public static File getAudioFile() {
        File f1 = new File("/data/local/tmp/" + TARGET_AUDIO);
        if (f1.exists() && f1.length() > 0) return f1;
        File f2 = new File("/sdcard/" + TARGET_AUDIO);
        if (f2.exists() && f2.length() > 0) return f2;
        File f3 = new File("/storage/emulated/0/" + TARGET_AUDIO);
        if (f3.exists() && f3.length() > 0) return f3;
        return null;
    }

    public static String readString(String name, String def) {
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name)
        };
        for (File f : targets) {
            if (f.exists() && f.length() > 0) {
                try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                } catch (Throwable ignored) {}
            }
        }
        return def;
    }
}
