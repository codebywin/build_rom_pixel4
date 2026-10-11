package org.lineageos.camera.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, zero-disk IPC Provider for VCam settings and media streaming.
 * Allows client camera apps (running with unprivileged user permissions under Android 13)
 * to instantly query live VCam adjustments via Binder IPC without being blocked by
 * Scoped Storage or SELinux file restrictions, and stream vcam.mp4 directly via ParcelFileDescriptor.
 */
public class VcamConfigProvider extends ContentProvider {
    private static final String TAG = "VcamConfigProvider";
    public static final String AUTHORITY = "org.lineageos.camera.assistant.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    private static final String PREF_NAME = "vcam_shared_config";

    // In-memory thread-safe state store
    private static final ConcurrentHashMap<String, Object> sState = new ConcurrentHashMap<>();
    private static volatile SharedPreferences sPrefs;
    private static volatile Context sStaticContext = null;

    static {
        // Default values
        sState.put("rotation", 0);
        sState.put("zoom", 1.0f);
        sState.put("pan_x", 0.0f);
        sState.put("pan_y", 0.0f);
        sState.put("noise", 0);
        sState.put("light", "0 0.00 0.00 1.20");
        sState.put("light_intensity", 0);
        sState.put("swap_uv", false);
        sState.put("raw_bright", 65);
        sState.put("bright_factor", 1.0f);
        sState.put("pause", false);
        sState.put("disable", false);
        sState.put("reset_ts", 0L);
        sState.put("kyc_flash", false);
        sState.put("color_sync", false);
        sState.put("live_shm", false);
        sState.put("floating_opacity", 0.85f);
        sState.put("bypass_hide_overlay", false);
        sState.put("notif_opacity", 100);
        sState.put("license_token", "");
        sState.put("vcam_uid", "");
        sState.put("video_ready", false);
        sState.put("audio_ready", false);
    }

    public static Context getStaticContext() {
        if (sStaticContext != null) return sStaticContext;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object app = atClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sStaticContext = (Context) app;
            }
        } catch (Throwable ignored) {}
        return sStaticContext;
    }

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            sStaticContext = ctx.getApplicationContext();
            sPrefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            loadPersisted();
        }
        Log.i(TAG, "VcamConfigProvider initialized successfully");
        return true;
    }

    private static void loadPersisted() {
        if (sPrefs != null) {
            try {
                sState.put("rotation", sPrefs.getInt("rotation", 0));
                sState.put("zoom", sPrefs.getFloat("zoom", 1.0f));
                sState.put("pan_x", sPrefs.getFloat("pan_x", 0.0f));
                sState.put("pan_y", sPrefs.getFloat("pan_y", 0.0f));
                sState.put("noise", sPrefs.getInt("noise", 0));
                sState.put("light", sPrefs.getString("light", "0 0.00 0.00 1.20"));
                sState.put("light_intensity", sPrefs.getInt("light_intensity", 0));
                sState.put("swap_uv", sPrefs.getBoolean("swap_uv", false));
                int bright = sPrefs.getInt("raw_bright", 65);
                sState.put("raw_bright", bright);
                sState.put("bright_factor", bright / 65.0f);
                sState.put("pause", sPrefs.getBoolean("pause", false));
                sState.put("disable", sPrefs.getBoolean("disable", false));
                sState.put("reset_ts", sPrefs.getLong("reset_ts", 0L));
                sState.put("kyc_flash", sPrefs.getBoolean("kyc_flash", false));
                sState.put("color_sync", sPrefs.getBoolean("color_sync", false));
                sState.put("live_shm", sPrefs.getBoolean("live_shm", false));
                sState.put("floating_opacity", sPrefs.getFloat("floating_opacity", 0.85f));
                String lic = sPrefs.getString("license_token", "");
                if (!lic.isEmpty()) sState.put("license_token", lic);
                String uid = sPrefs.getString("vcam_uid", "");
                if (!uid.isEmpty()) sState.put("vcam_uid", uid);
            } catch (Throwable t) {
                Log.w(TAG, "Error loading persisted config: " + t.getMessage());
            }
        }

        Context ctx = sStaticContext != null ? sStaticContext : getStaticContext();
        if (ctx != null) {
            try {
                SharedPreferences licPrefs = ctx.getSharedPreferences("vcam_license", Context.MODE_PRIVATE);
                if (licPrefs != null) {
                    String lic = licPrefs.getString("license_token", "");
                    if (!lic.isEmpty() && (!sState.containsKey("license_token") || ((String) sState.get("license_token")).isEmpty())) {
                        sState.put("license_token", lic);
                    }
                    String uid = licPrefs.getString("persistent_device_serial", "");
                    if (!uid.isEmpty() && (!sState.containsKey("vcam_uid") || ((String) sState.get("vcam_uid")).isEmpty())) {
                        sState.put("vcam_uid", uid);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static boolean hasValidVideo(Context ctx) {
        if (ctx == null) ctx = sStaticContext != null ? sStaticContext : getStaticContext();
        if (ctx != null) {
            File f1 = new File(ctx.getFilesDir(), "vcam.mp4");
            if (f1.exists() && f1.length() > 0) return true;
            File f2 = new File(ctx.getCacheDir(), "vcam.mp4");
            if (f2.exists() && f2.length() > 0) return true;
        }
        File f3 = new File("/sdcard/CameraAssistant/vcam.mp4");
        if (f3.exists() && f3.length() > 0) return true;
        File f4 = new File("/data/local/tmp/vcam.mp4");
        if (f4.exists() && f4.length() > 0) return true;
        return false;
    }

    public static boolean hasValidAudio(Context ctx) {
        if (ctx == null) ctx = sStaticContext != null ? sStaticContext : getStaticContext();
        if (ctx != null) {
            File f1 = new File(ctx.getFilesDir(), "vcam.wav");
            if (f1.exists() && f1.length() > 0) return true;
            File f2 = new File(ctx.getCacheDir(), "vcam.wav");
            if (f2.exists() && f2.length() > 0) return true;
        }
        File f3 = new File("/sdcard/CameraAssistant/vcam.wav");
        if (f3.exists() && f3.length() > 0) return true;
        File f4 = new File("/data/local/tmp/vcam.wav");
        if (f4.exists() && f4.length() > 0) return true;
        return false;
    }

    public static boolean hasValidImage(Context ctx) {
        if (ctx == null) ctx = sStaticContext != null ? sStaticContext : getStaticContext();
        if (ctx != null) {
            File f1 = new File(ctx.getFilesDir(), "vcam.jpg");
            if (f1.exists() && f1.length() > 0) return true;
            File f2 = new File(ctx.getCacheDir(), "vcam.jpg");
            if (f2.exists() && f2.length() > 0) return true;
        }
        File f3 = new File("/sdcard/CameraAssistant/vcam.jpg");
        if (f3.exists() && f3.length() > 0) return true;
        File f4 = new File("/data/local/tmp/vcam.jpg");
        if (f4.exists() && f4.length() > 0) return true;
        return false;
    }

    public static Bundle getAllBundle() {
        Bundle b = new Bundle();
        b.putInt("rotation", getInt("rotation", 0));
        b.putFloat("zoom", getFloat("zoom", 1.0f));
        b.putFloat("pan_x", getFloat("pan_x", 0.0f));
        b.putFloat("pan_y", getFloat("pan_y", 0.0f));
        b.putInt("noise", getInt("noise", 0));
        b.putString("light", getString("light", "0 0.00 0.00 1.20"));
        b.putInt("light_intensity", getInt("light_intensity", 0));
        b.putBoolean("swap_uv", getBoolean("swap_uv", false));
        b.putInt("raw_bright", getInt("raw_bright", 65));
        b.putFloat("bright_factor", getFloat("bright_factor", 1.0f));
        b.putBoolean("pause", getBoolean("pause", false));
        b.putBoolean("disable", getBoolean("disable", false));
        b.putLong("reset_ts", getLong("reset_ts", 0L));
        b.putBoolean("kyc_flash", getBoolean("kyc_flash", false));
        b.putBoolean("color_sync", getBoolean("color_sync", false));
        b.putBoolean("live_shm", getBoolean("live_shm", false));
        b.putFloat("floating_opacity", getFloat("floating_opacity", 0.85f));
        b.putString("license_token", getString("license_token", ""));
        b.putString("vcam_uid", getString("vcam_uid", ""));
        b.putBoolean("video_ready", hasValidVideo(sStaticContext));
        b.putBoolean("audio_ready", hasValidAudio(sStaticContext));
        b.putBoolean("image_ready", hasValidImage(sStaticContext));
        b.putString("media_mode", getString("media_mode", "auto"));
        return b;
    }

    public static void setInt(String key, int val) {
        sState.put(key, val);
        persistAsync(key, val);
    }

    public static void setFloat(String key, float val) {
        sState.put(key, val);
        persistAsync(key, val);
    }

    public static void setBoolean(String key, boolean val) {
        sState.put(key, val);
        persistAsync(key, val);
    }

    public static void setString(String key, String val) {
        if (val == null) val = "";
        sState.put(key, val);
        persistAsync(key, val);
    }

    public static void setLong(String key, long val) {
        sState.put(key, val);
        persistAsync(key, val);
    }

    public static int getInt(String key, int def) {
        Object v = sState.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (Throwable ignored) {}
        }
        return def;
    }

    public static float getFloat(String key, float def) {
        Object v = sState.get(key);
        if (v instanceof Number) return ((Number) v).floatValue();
        if (v instanceof String) {
            try { return Float.parseFloat(((String) v).trim()); } catch (Throwable ignored) {}
        }
        return def;
    }

    public static boolean getBoolean(String key, boolean def) {
        Object v = sState.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return def;
    }

    public static String getString(String key, String def) {
        Object v = sState.get(key);
        if (v != null) return v.toString();
        return def;
    }

    public static long getLong(String key, long def) {
        Object v = sState.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Throwable ignored) {}
        }
        return def;
    }

    private static void persistAsync(String key, Object val) {
        if (sPrefs == null) return;
        new Thread(() -> {
            try {
                SharedPreferences.Editor edit = sPrefs.edit();
                if (val instanceof Integer) edit.putInt(key, (Integer) val);
                else if (val instanceof Float) edit.putFloat(key, (Float) val);
                else if (val instanceof Boolean) edit.putBoolean(key, (Boolean) val);
                else if (val instanceof Long) edit.putLong(key, (Long) val);
                else if (val != null) edit.putString(key, val.toString());
                edit.apply();
            } catch (Throwable ignored) {}
        }).start();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) ctx = sStaticContext != null ? sStaticContext : getStaticContext();
        if (ctx == null) throw new FileNotFoundException("Context is null");
        String path = uri.getPath();
        if (path == null) path = "";

        File target = null;
        if (path.contains("audio") || path.contains("vcam.wav")) {
            File[] audioCandidates = new File[] {
                new File(ctx.getFilesDir(), "vcam.wav"),
                new File(ctx.getCacheDir(), "vcam.wav"),
                new File("/sdcard/CameraAssistant/vcam.wav"),
                new File("/data/local/tmp/vcam.wav")
            };
            for (File f : audioCandidates) {
                if (f.exists() && f.length() > 0) {
                    target = f;
                    break;
                }
            }
        } else if (path.contains("image") || path.contains("vcam.jpg")) {
            File[] imageCandidates = new File[] {
                new File(ctx.getFilesDir(), "vcam.jpg"),
                new File(ctx.getCacheDir(), "vcam.jpg"),
                new File("/sdcard/CameraAssistant/vcam.jpg"),
                new File("/data/local/tmp/vcam.jpg")
            };
            for (File f : imageCandidates) {
                if (f.exists() && f.length() > 0) {
                    target = f;
                    break;
                }
            }
        } else {
            File[] videoCandidates = new File[] {
                new File(ctx.getFilesDir(), "vcam.mp4"),
                new File(ctx.getCacheDir(), "vcam.mp4"),
                new File("/sdcard/CameraAssistant/vcam.mp4"),
                new File("/data/local/tmp/vcam.mp4")
            };
            for (File f : videoCandidates) {
                if (f.exists() && f.length() > 0) {
                    target = f;
                    break;
                }
            }
        }

        if (target != null && target.exists() && target.length() > 0) {
            Log.i(TAG, "openFile serving: " + target.getAbsolutePath() + " (" + target.length() + " bytes) for " + uri);
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        Log.w(TAG, "openFile: No media found for URI: " + uri);
        throw new FileNotFoundException("No media file available for URI: " + uri);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("getConfig".equals(method)) {
            return getAllBundle();
        } else if ("setConfig".equals(method) && extras != null) {
            for (String key : extras.keySet()) {
                Object val = extras.get(key);
                if (val != null) {
                    sState.put(key, val);
                    persistAsync(key, val);
                }
            }
            return getAllBundle();
        } else if ("setKey".equals(method) && arg != null && extras != null) {
            Object val = extras.get("value");
            if (val != null) {
                sState.put(arg, val);
                persistAsync(arg, val);
            }
            return getAllBundle();
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
