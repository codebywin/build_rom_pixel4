package org.lineageos.camera.assistant.xposed;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.lineageos.camera.assistant.LicenseManager;

public class XposedInit implements IXposedHookLoadPackage {
    private static final String TAG = "VcamXposed";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null) return;

        // Bỏ qua chính app Camera Assistant và các tiến trình nhạy cảm của hệ thống
        if ("org.lineageos.camera.assistant".equals(lpparam.packageName)) return;
        if ("com.android.systemui".equals(lpparam.packageName)) return;
        if ("com.android.settings".equals(lpparam.packageName)) return;

        // Kiểm tra xem VCAM có bị tắt toàn cục không
        if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) {
            return;
        }

        // Kiểm tra bản quyền RSA
        LicenseManager.LicenseInfo lic = LicenseManager.checkLicense();
        if (!lic.isValid) {
            Log.w(TAG, "VCAM Hook skipped for " + lpparam.packageName + ": " + lic.message);
            return;
        }

        Log.i(TAG, "Initializing VCAM Root Hooks for: " + lpparam.packageName);

        // Kích hoạt hook Camera 1
        XposedCamera1Hook.initHook(lpparam.classLoader);

        // Kích hoạt hook Camera 2
        XposedCamera2Hook.initHook(lpparam.classLoader);

        // Kích hoạt hook Microphone & AudioRecord
        XposedAudioHook.initHook(lpparam.classLoader);
    }
}

