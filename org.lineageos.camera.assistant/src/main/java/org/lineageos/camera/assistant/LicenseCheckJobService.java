package org.lineageos.camera.assistant;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Quét định kỳ 60 phút để xác thực bản quyền với Cloudflare Server.
 * Hoạt động ngay cả khi app không mở hoặc điện thoại vừa khởi động lại.
 */
public class LicenseCheckJobService extends JobService {
    private static final String TAG = "CameraAssistant";
    private static final String FLAG_DISABLE = "vcam_disable";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "LicenseCheckJobService: Bat dau kiem tra ban quyen dinh ky (60 phut)...");

        LicenseManager.checkOnlineAsync((isValid, message) -> {
            if (!isValid) {
                Log.w(TAG, "LicenseCheckJobService: Ban quyen da bi khoa/xoa tren server: " + message);
                // 1. Xoa ban quyen tren may
                LicenseManager.removeLicense();

                // 2. Khoa VCAM ngay lap tuc
                writeDisableFlag();

                // 3. Dong bong bong noi neu dang mo
                try {
                    stopService(new Intent(this, FloatingControlService.class));
                } catch (Throwable ignored) {}
            } else {
                Log.i(TAG, "LicenseCheckJobService: Ban quyen hop le hoac offline (" + message + ")");
            }

            // Hoan tat job, khong can reschedule gap vi JobScheduler da set periodic 60 phut
            jobFinished(params, false);
        });

        return true; // Chay bat dong bo tren thread moi
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Cho phep he thong goi lai neu bi ngat giua chung
    }

    private void writeDisableFlag() {
        String[] paths = new String[]{
            "/data/local/tmp/" + FLAG_DISABLE,
            "/sdcard/" + FLAG_DISABLE,
            "/storage/emulated/0/" + FLAG_DISABLE
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write("1\n".getBytes("UTF-8"));
                fos.close();
                f.setReadable(true, false);
                f.setWritable(true, false);
            } catch (Throwable ignored) {}

            try {
                Process proc = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/sh", "-c", "echo '1' > " + p + " && chmod 666 " + p
                });
                proc.waitFor();
            } catch (Throwable ignored) {}
        }
    }
}

