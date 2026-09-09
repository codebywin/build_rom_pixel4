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
            if (f.exists() && f.length() > 0) {
                try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                    String line = reader.readLine();
                    if (line != null && "1".equals(line.trim())) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    public static int getRotation() {
        String val = readString("vcam_rotation", "");
        if (val.isEmpty()) val = readString("vcam_rotate", "0");
        try {
            int deg = Integer.parseInt(val);
            return (deg % 360 + 360) % 360;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String getRotateValue() {
        return String.valueOf(getRotation());
    }

    public static String getResetTimestamp() {
        String ts = readString("vcam_reset", "");
        if (ts.isEmpty()) ts = readString("vcam_rewind", "");
        return ts;
    }

    public static float getMicBoost() {
        String val = readString(FILE_MIC_BOOST, "3.0");
        try {
            return Float.parseFloat(val);
        } catch (Throwable t) {
            return 3.0f;
        }
    }

    public static float getZoom() {
        String val = readString("vcam_zoom", "1.0");
        try {
            float z = Float.parseFloat(val);
            return Math.max(1.0f, Math.min(z, 5.0f));
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    public static float getPanX() {
        String val = readString("vcam_pan_x", "0.0");
        try {
            return Float.parseFloat(val);
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    public static float getPanY() {
        String val = readString("vcam_pan_y", "0.0");
        try {
            return Float.parseFloat(val);
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    public static boolean isKycFlashActive() {
        return isFlagActive("vcam_kyc_flash") || isFlagActive("vcam_color_sync");
    }

    public static String getColorVal() {
        return readString("vcam_color_val", "auto");
    }

    public static File getVideoFile() {
        File[] candidates = new File[] {
            new File("/data/local/tmp/" + TARGET_VIDEO),
            new File("/sdcard/" + TARGET_VIDEO),
            new File("/storage/emulated/0/" + TARGET_VIDEO),
            new File("/sdcard/DCIM/Camera1/virtual.mp4"),
            new File("/storage/emulated/0/DCIM/Camera1/virtual.mp4")
        };
        for (File f : candidates) {
            if (f.exists() && f.length() > 0) return f;
        }
        return null;
    }

    public static File getAudioFile() {
        String[] audioNames = new String[] {
            TARGET_AUDIO, "vcam.mp3", "vcam.m4a", "vcam.aac", "virtual.wav", "virtual.mp3"
        };
        String[] dirs = new String[] {
            "/data/local/tmp/", "/sdcard/", "/storage/emulated/0/", "/sdcard/DCIM/Camera1/", "/storage/emulated/0/DCIM/Camera1/"
        };
        for (String dir : dirs) {
            for (String name : audioNames) {
                File f = new File(dir + name);
                if (f.exists() && f.length() > 0) return f;
            }
        }
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

