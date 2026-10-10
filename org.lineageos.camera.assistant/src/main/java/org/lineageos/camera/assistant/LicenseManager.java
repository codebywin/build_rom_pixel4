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
import java.net.NetworkInterface;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

public class LicenseManager {
    private static final String TAG = "VcamLicense";
    public static final String SERVER_URL = "https://vandroid.hothangtech.workers.dev/api/v1/activate";
    public static final String CHECK_URL = "https://vandroid.hothangtech.workers.dev/api/v1/check";

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
    private static final String LIC_FILE_SD = "/sdcard/CameraAssistant/vcam.lic";
    private static final String LIC_FILE_SD_LEGACY = "/sdcard/vcam.lic";

    public static class LicenseInfo {
        public boolean isValid = false;
        public String serial = "";
        public long expiresAt = 0;
        public boolean isLifetime = false;
        public String message = "Chưa kích hoạt";
    }

    private static final String EXPECTED_SIG_RELEASE = "61DE9A6ACDC03A7AFD62E7C186A919AAA6AFA01A23E0C4A74E59B2F4C6389B63";
    private static final String EXPECTED_SIG_RELEASE_OLD = "59711D4A6F9FEC87B6A19E8E355A4FEBCF4F75E72897228A1AD25A455F745B58";
    private static final String EXPECTED_SIG_DEBUG = "9A1A4266B8CF06954B0146A9867D3F72937C9968D1121BE850838F42E7A2AC57";
    private static final String EXPECTED_SIG_DEBUG_LOCAL = "DFD60C69BE68A451087447111346F5DF576F84442DA0683974440A7B143A0B8B";

    public static boolean isAppTampered(Context context) {
        if (context == null) return false;
        try {
            String pkg = context.getPackageName();
            if (!"org.lineageos.camera.assistant".equals(pkg)) {
                return false;
            }

            android.content.pm.PackageManager pm = context.getPackageManager();
            byte[] certBytes = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi != null && pi.signingInfo != null) {
                    android.content.pm.Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                    if (sigs != null && sigs.length > 0) {
                        certBytes = sigs[0].toByteArray();
                    }
                }
            } else {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES);
                if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                    certBytes = pi.signatures[0].toByteArray();
                }
            }

            if (certBytes == null || certBytes.length == 0) {
                return true;
            }

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            String currentHash = sb.toString();

            if (EXPECTED_SIG_RELEASE.equalsIgnoreCase(currentHash)
                    || EXPECTED_SIG_RELEASE_OLD.equalsIgnoreCase(currentHash)
                    || EXPECTED_SIG_DEBUG.equalsIgnoreCase(currentHash)
                    || EXPECTED_SIG_DEBUG_LOCAL.equalsIgnoreCase(currentHash)) {
                return false;
            }

            Log.e(TAG, "Security Alert: Signature mismatch! Detected: " + currentHash);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Tamper check failed: " + t.getMessage());
        }

        if (isFridaOrDebuggerAttached()) {
            Log.e(TAG, "Security Alert: Dynamic debugger or Frida hook detected!");
            return true;
        }
        return false;
    }

    public static boolean isFridaOrDebuggerAttached() {
        try {
            // 1. Android Debug check
            if (android.os.Debug.isDebuggerConnected()) return true;

            // 2. TracerPid check in /proc/self/status
            File status = new File("/proc/self/status");
            if (status.exists()) {
                BufferedReader br = new BufferedReader(new java.io.FileReader(status));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("TracerPid:")) {
                        int pid = Integer.parseInt(line.substring(10).trim());
                        if (pid > 0) {
                            br.close();
                            return true;
                        }
                        break;
                    }
                }
                br.close();
            }

            // 3. /proc/self/maps scan for Frida libraries
            File maps = new File("/proc/self/maps");
            if (maps.exists()) {
                BufferedReader br = new BufferedReader(new java.io.FileReader(maps));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("frida-agent") || line.contains("frida-gadget") ||
                        line.contains("gum-js-loop") || line.contains("linjector")) {
                        br.close();
                        return true;
                    }
                }
                br.close();
            }

            // 4. Frida default port check (27042, 27043)
            for (int port : new int[]{27042, 27043}) {
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 25);
                    s.close();
                    return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String getDeviceSerial() {
        // 1. Hardware Serial của thiết bị (Chuẩn ROM AOSP - Không cần Root)
        String s = readSystemProp("ro.boot.serialno");
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) {
            s = readSystemProp("ro.serialno");
        }
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) {
            try {
                s = Build.getSerial();
            } catch (Throwable ignored) {}
        }
        if (s != null && !s.isEmpty() && !"unknown".equalsIgnoreCase(s)) {
            return s.trim();
        }

        // 2. Persistent UID dự phòng
        String uid = getOrCreatePersistentUid();
        if (uid != null && !uid.isEmpty()) {
            return uid.trim();
        }

        // 3. Android ID
        String aid = readAndroidId();
        if (aid != null && !aid.isEmpty()) {
            return aid.trim();
        }

        return "DEVICE_UNKNOWN";
    }

    public static boolean matchesDeviceSerial(String licSerial) {
        if (licSerial == null || licSerial.trim().isEmpty()) return false;
        licSerial = licSerial.trim();

        // 1. So khớp với serial hiện thời
        if (licSerial.equalsIgnoreCase(getDeviceSerial())) return true;

        // 2. So khớp với Persistent UID
        String uid = getOrCreatePersistentUid();
        if (!uid.isEmpty() && licSerial.equalsIgnoreCase(uid)) return true;

        // 3. So khớp với Android ID
        String aid = readAndroidId();
        if (!aid.isEmpty() && licSerial.equalsIgnoreCase(aid)) return true;

        // 4. So khớp với các thuộc tính phần cứng
        String p1 = readSystemProp("ro.boot.serialno");
        if (!p1.isEmpty() && licSerial.equalsIgnoreCase(p1)) return true;
        String p2 = readSystemProp("ro.serialno");
        if (!p2.isEmpty() && licSerial.equalsIgnoreCase(p2)) return true;
        String p3 = readSystemProp("sys.serialno");
        if (!p3.isEmpty() && licSerial.equalsIgnoreCase(p3)) return true;

        try {
            String bs = Build.getSerial();
            if (bs != null && !bs.isEmpty() && licSerial.equalsIgnoreCase(bs)) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private static String readAndroidId() {
        Context ctx = getContext();
        if (ctx != null) {
            try {
                String aid = android.provider.Settings.Secure.getString(
                        ctx.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID
                );
                if (aid != null && !aid.trim().isEmpty() && !"null".equalsIgnoreCase(aid.trim())) {
                    return aid.trim();
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String getOrCreatePersistentUid() {
        // Kiểm tra các đường dẫn có thể chia sẻ giữa các tiến trình
        String[] paths = new String[]{
                "/data/local/tmp/.vcam_uid",
                "/sdcard/CameraAssistant/.vcam_uid",
                "/sdcard/.vcam_uid",
                "/storage/emulated/0/CameraAssistant/.vcam_uid",
                "/storage/emulated/0/.vcam_uid"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists() && f.canRead()) {
                try {
                    BufferedReader br = new BufferedReader(new java.io.FileReader(f));
                    String id = br.readLine();
                    br.close();
                    if (id != null && !id.trim().isEmpty()) {
                        return id.trim();
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Nếu chưa có, tạo UID cố định mới và đồng bộ ra các đường dẫn chung
        try {
            String newId = "ID" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
            for (String p : paths) {
                try {
                    File f = new File(p);
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(newId.getBytes("UTF-8"));
                    fos.close();
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable ignored) {}
            }
            // Root backup để đảm bảo quyền đọc 666 cho tất cả app bị hook
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", 
                    "echo -n '" + newId + "' > /data/local/tmp/.vcam_uid && chmod 666 /data/local/tmp/.vcam_uid ; " +
                    "mkdir -p /sdcard/CameraAssistant 2>/dev/null ; " +
                    "echo -n '" + newId + "' > /sdcard/CameraAssistant/.vcam_uid 2>/dev/null ; " +
                    "chmod 666 /sdcard/CameraAssistant/.vcam_uid 2>/dev/null ; " +
                    "rm -f /sdcard/.vcam_uid /storage/emulated/0/.vcam_uid 2>/dev/null"
                });
            } catch (Throwable ignored) {}
            return newId;
        } catch (Throwable ignored) {}
        return "";
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
        return checkLicenseOriginal();
    }

    public static LicenseInfo checkLicenseOriginal() {
        LicenseInfo info = new LicenseInfo();
        if (isFridaOrDebuggerAttached()) {
            info.message = "Phát hiện công cụ dịch ngược (Frida / Debugger)!";
            return info;
        }
        String token = readLicenseToken();
        if (token == null) token = "";
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

            if (!matchesDeviceSerial(licSerial)) {
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
        File f2Legacy = new File(LIC_FILE_SD_LEGACY);
        if (f2Legacy.exists() && f2Legacy.length() > 0) {
            String s = readFile(f2Legacy);
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

    public static void ensureLicenseSharedSync() {
        String token = readLicenseToken();
        if (token != null && !token.isEmpty()) {
            File sdLic = new File(LIC_FILE_SD);
            if (!sdLic.exists() || sdLic.length() == 0) {
                saveLicenseToken(token);
            }
        }
    }

    private static void writeWithRootSync(String data) {
        if (data == null || data.isEmpty()) return;
        // Ghi qua chuẩn File API - Hoạt động 100% không cần quyền root
        writeFile(LIC_FILE_SD, data);
        writeFile("/sdcard/CameraAssistant/vcam.lic", data);
        writeFile(LIC_FILE_TMP, data);
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
            LIC_FILE_SD_LEGACY,
            "/storage/emulated/0/CameraAssistant/vcam.lic",
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
        return res;
    }

    public interface CheckCallback {
        void onCheckFinished(boolean isValid, String message);
    }

    public static void checkOnlineAsync(final CheckCallback callback) {
        if (callback != null) {
            callback.onCheckFinished(true, "Bản quyền Vĩnh Viễn");
        }
    }

    public static boolean verifyRsaSignature(String data, String base64Sig, String publicKeyPem) {
        try {
            String cleanKey = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\n", "")
                .replace("\\r", "")
                .replaceAll("\\s", "")
                .trim();
            byte[] keyBytes = Base64.decode(cleanKey, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(spec);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            sig.update(data.getBytes("UTF-8"));
            return sig.verify(Base64.decode(base64Sig.trim(), Base64.DEFAULT));
        } catch (Throwable t) {
            Log.e(TAG, "RSA verify error", t);
            return false;
        }
    }

    public static boolean isNetworkInterceptionDetected(Context context) {
        try {
            // 1. Kiem tra System Proxy
            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            if (proxyHost != null && !proxyHost.isEmpty() && !"0.0.0.0".equals(proxyHost)) {
                Log.w(TAG, "Proxy detected via system properties: " + proxyHost + ":" + proxyPort);
                return true;
            }
            String httpsProxyHost = System.getProperty("https.proxyHost");
            if (httpsProxyHost != null && !httpsProxyHost.isEmpty()) {
                Log.w(TAG, "HTTPS Proxy detected: " + httpsProxyHost);
                return true;
            }

            if (context != null) {
                // Kiem tra Settings.Global Proxy
                try {
                    String globalProxy = Settings.Global.getString(context.getContentResolver(), Settings.Global.HTTP_PROXY);
                    if (globalProxy != null && !globalProxy.isEmpty()) {
                        Log.w(TAG, "Global HTTP proxy detected: " + globalProxy);
                        return true;
                    }
                } catch (Throwable ignored) {}

                // 2. Kiem tra VPN Active qua ConnectivityManager (HttpCanary, Reqable su dung VPN ao)
                try {
                    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Network activeNet = cm.getActiveNetwork();
                            if (activeNet != null) {
                                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                    Log.w(TAG, "Active VPN transport detected via NetworkCapabilities");
                                    return true;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Kiem tra Network Interfaces xem co interface tun/ppp/tap/p2p dang UP khong
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface nif = interfaces.nextElement();
                        if (nif.isUp()) {
                            String name = nif.getName().toLowerCase();
                            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("p2p") || name.startsWith("tap")) {
                                Log.w(TAG, "Suspicious tunnel interface detected: " + name);
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "Error checking network interception", t);
        }
        return false;
    }

    private static void setupAntiSniffSSL(HttpsURLConnection conn) {
        conn.setHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                if (hostname == null) return false;
                if (!hostname.equalsIgnoreCase("vandroid.hothangtech.workers.dev") &&
                    !hostname.endsWith(".workers.dev")) {
                    Log.w(TAG, "Rejected untrusted hostname: " + hostname);
                    return false;
                }
                try {
                    java.security.cert.Certificate[] certs = session.getPeerCertificates();
                    if (certs == null || certs.length == 0) return false;

                    String[] MITM_KEYWORDS = {
                        "charles", "burp", "portswigger", "httpcanary", "reqable",
                        "fiddler", "mitmproxy", "proxyman", "packetcapture", "sniff",
                        "wireshark", "storm", "anyproxy", "paros"
                    };

                    for (java.security.cert.Certificate c : certs) {
                        if (c instanceof X509Certificate) {
                            X509Certificate xc = (X509Certificate) c;
                            String sub = (xc.getSubjectDN() != null ? xc.getSubjectDN().getName() : "").toLowerCase();
                            String iss = (xc.getIssuerDN() != null ? xc.getIssuerDN().getName() : "").toLowerCase();
                            for (String kw : MITM_KEYWORDS) {
                                if (sub.contains(kw) || iss.contains(kw)) {
                                    Log.e(TAG, "Blocked MITM Certificate: " + kw + " in " + iss);
                                    return false;
                                }
                            }
                        }
                    }
                    return true;
                } catch (Throwable t) {
                    Log.e(TAG, "SSL verify error", t);
                    return false;
                }
            }
        });
    }

    public interface ActivationCallback {
        void onResult(boolean success, String message);
    }

    public static void activateOnlineAsync(final String key, final ActivationCallback callback) {
        new Thread(() -> {
            try {
                Context ctx = getContext();
                if (isNetworkInterceptionDetected(ctx)) {
                    callback.onResult(false, "CẢNH BÁO: Phát hiện môi trường can thiệp mạng / VPN / Proxy (HttpCanary/Reqable/Charles)! Vui lòng tắt trước khi kích hoạt.");
                    return;
                }

                String serial = getDeviceSerial();
                URL url = new URL(SERVER_URL);
                HttpURLConnection rawConn = (HttpURLConnection) url.openConnection();
                if (rawConn instanceof HttpsURLConnection) {
                    setupAntiSniffSSL((HttpsURLConnection) rawConn);
                }
                HttpURLConnection conn = rawConn;
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
