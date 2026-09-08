package org.lineageos.camera.assistant;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class LicenseManager {
    private static final String TAG = "VcamLicense";
    public static final String SERVER_URL = "https://api.0x0134w.workers.dev/api/v1/activate";
    public static final String CHECK_URL = "https://api.0x0134w.workers.dev/api/v1/check";

    private static volatile Context sContext = null;
    private static volatile String sCachedToken = "";

    public static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
    }

    private static Context getContext() {
        if (sContext != null) return sContext;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object app = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = ((Context) app).getApplicationContext();
                return sContext;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static final String PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxFvmY4n7o8MuGkcRiZf8\n76WKcucHqUcCzdp2QR44yCiZDeJsXfWgqQI+GLa0Vjcj/GxfXZTBCnXBvMVhTVrp\nVzNqs3E/3De7yhQbGwDziqkc+wZ0NMf/oFr46ALDhOLi9WMJEOe5cOWGF96X/+H8\n5Z0rqmbVFKGQiPpwGTcs6j6tb9MRkCfDda7/f96ldBujCw1oINF6yeFJ2ESSueHp\n0KcTZw6HAfo0jfeuSyatN60MNhEYJnkiB/XRTwnHh4U2gS55wylUPytwk8Iiz1ev\nhPqaHgFeom/K55Mky+kqSVNEyFnQq3ZmD5Ro1zVgxLpCjwe0GZVTD47/Y5vGiMkN\nawIDAQAB\n-----END PUBLIC KEY-----";

    private static final String LIC_FILE_TMP = "/data/local/tmp/vcam.lic";
    private static final String LIC_FILE_SD = "/sdcard/vcam.lic";

    public static class LicenseInfo {
        public boolean isValid = false;
        public String serial = "";
        public long expiresAt = 0;
        public boolean isLifetime = false;
        public String message = "Chưa kích hoạt";
    }

    public static String getDeviceSerial() {
        String s = readSystemProp("ro.boot.serialno");
        if (s == null || s.isEmpty()) {
            s = readSystemProp("ro.serialno");
        }
        if (s == null || s.isEmpty()) {
            try {
                s = Build.getSerial();
            } catch (Throwable ignored) {}
        }
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) {
            s = readSystemProp("sys.serialno");
        }
        return (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) ? "97291FFAZ0002N" : s.trim();
    }

    private static String readSystemProp(String key) {
        try {
            Process p = Runtime.getRuntime().exec("getprop " + key);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            return line != null ? line.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static LicenseInfo checkLicense() {
        LicenseInfo info = new LicenseInfo();
        String token = readLicenseToken();
        if (token == null || token.isEmpty()) {
            info.message = "Chưa có bản quyền";
            return info;
        }

        try {
            int pipeIdx = token.indexOf("|SIG=");
            if (pipeIdx == -1) {
                info.message = "License không hợp lệ (thiếu chữ ký)";
                return info;
            }

            String payload = token.substring(0, pipeIdx);
            String sigBase64 = token.substring(pipeIdx + 5);

            if (!verifyRsaSignature(payload, sigBase64, PUBLIC_KEY_PEM)) {
                info.message = "Chữ ký bản quyền không hợp lệ!";
                return info;
            }

            String[] parts = payload.split(";");
            String licSerial = "";
            long expiresAt = 0;
            for (String p : parts) {
                p = p.trim();
                if (p.startsWith("SERIAL=")) {
                    licSerial = p.substring(7).trim();
                } else if (p.startsWith("EXPIRES=")) {
                    try {
                        expiresAt = Long.parseLong(p.substring(8).trim());
                    } catch (Throwable ignored) {}
                }
            }

            String currentSerial = getDeviceSerial();
            if (!currentSerial.equalsIgnoreCase(licSerial)) {
                info.message = "License không khớp thiết bị này!";
                return info;
            }

            info.serial = licSerial;
            info.expiresAt = expiresAt;

            long nowSec = System.currentTimeMillis() / 1000;
            if (expiresAt > 0 && nowSec > expiresAt) {
                info.message = "Hết hạn vào " + formatDate(expiresAt);
                return info;
            }

            info.isValid = true;
            info.isLifetime = (expiresAt == 0);
            if (info.isLifetime) {
                info.message = "Bản quyền Vĩnh Viễn";
            } else {
                long diffDays = (expiresAt - nowSec) / 86400;
                info.message = "Hạn dùng: " + diffDays + " ngày (Đến " + formatDate(expiresAt) + ")";
            }
            return info;

        } catch (Throwable t) {
            Log.e(TAG, "checkLicense error", t);
            info.message = "Lỗi xác thực: " + t.getMessage();
            return info;
        }
    }

    private static String formatDate(long sec) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        return sdf.format(new Date(sec * 1000));
    }

    private static String readLicenseToken() {
        if (sCachedToken != null && !sCachedToken.isEmpty()) {
            return sCachedToken;
        }

        // 1. Check SharedPreferences
        Context ctx = getContext();
        if (ctx != null) {
            try {
                String token = ctx.getSharedPreferences("vcam_license", Context.MODE_PRIVATE)
                        .getString("license_token", "");
                if (token != null && !token.trim().isEmpty()) {
                    sCachedToken = token.trim();
                    return sCachedToken;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Check internal filesDir
        if (ctx != null) {
            try {
                File intFile = new File(ctx.getFilesDir(), "vcam.lic");
                if (intFile.exists() && intFile.length() > 0) {
                    String s = readFile(intFile);
                    if (!s.isEmpty()) {
                        sCachedToken = s;
                        return s;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Check direct app data dirs
        File directInt = new File("/data/data/org.lineageos.camera.assistant/files/vcam.lic");
        if (directInt.exists() && directInt.length() > 0) {
            String s = readFile(directInt);
            if (!s.isEmpty()) {
                sCachedToken = s;
                return s;
            }
        }
        File user0Int = new File("/data/user/0/org.lineageos.camera.assistant/files/vcam.lic");
        if (user0Int.exists() && user0Int.length() > 0) {
            String s = readFile(user0Int);
            if (!s.isEmpty()) {
                sCachedToken = s;
                return s;
            }
        }

        // 4. Check tmp & sdcard
        File f1 = new File(LIC_FILE_TMP);
        if (f1.exists() && f1.length() > 0) {
            String s = readFile(f1);
            if (!s.isEmpty()) {
                sCachedToken = s;
                return s;
            }
        }
        File f2 = new File(LIC_FILE_SD);
        if (f2.exists() && f2.length() > 0) {
            String s = readFile(f2);
            if (!s.isEmpty()) {
                sCachedToken = s;
                return s;
            }
        }
        File f3 = new File("/storage/emulated/0/vcam.lic");
        if (f3.exists() && f3.length() > 0) {
            String s = readFile(f3);
            if (!s.isEmpty()) {
                sCachedToken = s;
                return s;
            }
        }
        return "";
    }

    private static String readFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int r = fis.read(b);
            fis.close();
            return new String(b, 0, r, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void saveLicenseToken(String token) {
        if (token == null || token.trim().isEmpty()) return;
        token = token.trim();
        sCachedToken = token;

        // 1. SharedPreferences (private internal - 100% permission safe)
        Context ctx = getContext();
        if (ctx != null) {
            try {
                ctx.getSharedPreferences("vcam_license", Context.MODE_PRIVATE)
                        .edit()
                        .putString("license_token", token)
                        .commit();
            } catch (Throwable ignored) {}
        }

        // 2. App internal filesDir
        if (ctx != null) {
            try {
                File intFile = new File(ctx.getFilesDir(), "vcam.lic");
                writeFile(intFile.getAbsolutePath(), token);
                intFile.setReadable(true, false);
            } catch (Throwable ignored) {}
        }

        // 3. Fallback direct app storage
        try {
            File directInt = new File("/data/data/org.lineageos.camera.assistant/files/vcam.lic");
            File parent = directInt.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            writeFile(directInt.getAbsolutePath(), token);
            directInt.setReadable(true, false);
        } catch (Throwable ignored) {}

        // 4. Standard external & tmp files
        writeFile(LIC_FILE_TMP, token);
        writeFile(LIC_FILE_SD, token);
        writeFile("/storage/emulated/0/vcam.lic", token);

        // 5. Synchronous Root write so files exist before activate callback returns
        writeWithRootSync(token);
    }

    private static void writeWithRootSync(String data) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            String cmd = "echo '" + data + "' > /data/local/tmp/vcam.lic\n" +
                         "chmod 666 /data/local/tmp/vcam.lic\n" +
                         "echo '" + data + "' > /sdcard/vcam.lic\n" +
                         "chmod 666 /sdcard/vcam.lic\n" +
                         "echo '" + data + "' > /storage/emulated/0/vcam.lic\n" +
                         "chmod 666 /storage/emulated/0/vcam.lic\n" +
                         "mkdir -p /data/data/org.lineageos.camera.assistant/files\n" +
                         "echo '" + data + "' > /data/data/org.lineageos.camera.assistant/files/vcam.lic\n" +
                         "chmod 666 /data/data/org.lineageos.camera.assistant/files/vcam.lic\n" +
                         "exit\n";
            os.write(cmd.getBytes("UTF-8"));
            os.flush();
            os.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(3, TimeUnit.SECONDS);
            } else {
                p.waitFor();
            }
        } catch (Throwable ignored) {}
    }

    private static void writeFile(String path, String data) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Throwable ignored) {}
    }

    public static boolean removeLicense() {
        sCachedToken = "";
        boolean res = false;
        Context ctx = getContext();
        if (ctx != null) {
            try {
                ctx.getSharedPreferences("vcam_license", Context.MODE_PRIVATE)
                        .edit()
                        .remove("license_token")
                        .commit();
                File intFile = new File(ctx.getFilesDir(), "vcam.lic");
                writeFile(intFile.getAbsolutePath(), "");
                if (intFile.delete()) res = true;
            } catch (Throwable ignored) {}
        }
        String[] paths = new String[] {
            "/data/data/org.lineageos.camera.assistant/files/vcam.lic",
            "/data/user/0/org.lineageos.camera.assistant/files/vcam.lic",
            LIC_FILE_TMP,
            LIC_FILE_SD,
            "/storage/emulated/0/vcam.lic"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                if (f.exists()) {
                    writeFile(f.getAbsolutePath(), "");
                    if (f.delete()) res = true;
                }
            } catch (Throwable ignored) {}
        }
        try {
            Process p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            os.write("rm -f /data/local/tmp/vcam.lic /sdcard/vcam.lic /storage/emulated/0/vcam.lic /data/data/org.lineageos.camera.assistant/files/vcam.lic\nexit\n".getBytes("UTF-8"));
            os.flush();
            os.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.waitFor(3, TimeUnit.SECONDS);
            } else {
                p.waitFor();
            }
        } catch (Throwable ignored) {}
        return res;
    }

    public interface CheckCallback {
        void onCheckFinished(boolean isValid, String message);
    }

    public static void checkOnlineAsync(final CheckCallback callback) {
        new Thread(() -> {
            try {
                String serial = getDeviceSerial();
                URL url = new URL(CHECK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 4) VcamAssistant/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                JSONObject req = new JSONObject();
                req.put("serial", serial);

                OutputStream os = conn.getOutputStream();
                os.write(req.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseText = "";
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                    br.close();
                    responseText = sb.toString();
                }

                JSONObject res = new JSONObject(responseText);
                boolean ok = res.optBoolean("success", false);
                String status = res.optString("status", "");
                boolean isExpired = res.optBoolean("is_expired", false);

                if (!ok || "BANNED".equalsIgnoreCase(status) || "NOT_FOUND".equalsIgnoreCase(status) || isExpired) {
                    Log.w(TAG, "Device revoked or removed on server. Removing local license.");
                    removeLicense();
                    if (callback != null) callback.onCheckFinished(false, "Bản quyền đã bị xóa hoặc khóa trên máy chủ!");
                    return;
                }

                if (callback != null) callback.onCheckFinished(true, "Bản quyền hợp lệ");
            } catch (Throwable t) {
                if (callback != null) callback.onCheckFinished(true, "Offline");
            }
        }).start();
    }

    public static boolean verifyRsaSignature(String data, String base64Sig, String publicKeyPem) {
        try {
            String cleanKey = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            byte[] keyBytes = Base64.decode(cleanKey, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(spec);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            sig.update(data.getBytes("UTF-8"));
            return sig.verify(Base64.decode(base64Sig, Base64.DEFAULT));
        } catch (Throwable t) {
            Log.e(TAG, "RSA verify error", t);
            return false;
        }
    }

    public interface ActivationCallback {
        void onResult(boolean success, String message);
    }

    public static void activateOnlineAsync(final String key, final ActivationCallback callback) {
        new Thread(() -> {
            try {
                String serial = getDeviceSerial();
                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 4) VcamAssistant/1.0");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONObject req = new JSONObject();
                req.put("key", key.trim());
                req.put("serial", serial);

                OutputStream os = conn.getOutputStream();
                os.write(req.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseText = "";
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                    br.close();
                    responseText = sb.toString();
                }

                JSONObject res = new JSONObject(responseText);
                boolean ok = res.optBoolean("success", false);
                String msg = res.optString("message", "Lỗi không xác định");

                if (ok) {
                    String token = res.optString("license_token");
                    if (token == null || token.isEmpty()) {
                        token = res.optString("license");
                    }
                    if (token != null && !token.isEmpty()) {
                        saveLicenseToken(token);
                    }
                }
                callback.onResult(ok, msg);

            } catch (Throwable t) {
                callback.onResult(false, "Lỗi kết nối server: " + t.getMessage());
            }
        }).start();
    }
}
