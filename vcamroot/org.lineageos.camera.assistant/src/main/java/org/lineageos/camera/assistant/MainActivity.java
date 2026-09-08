package org.lineageos.camera.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PICK_VIDEO = 101;
    private static final int REQ_PICK_AUDIO = 102;

    private static final String FLAG_DISABLE = "vcam_disable";
    private static final String FLAG_MIC_DISABLE = "vcam_mic_disable";
    private static final String FLAG_MIC_MIX = "vcam_mic_mix";

    private static final String TARGET_VIDEO = "vcam.mp4";
    private static final String TARGET_AUDIO = "vcam.wav";

    private TextView txtLicenseBadge;
    private TextView txtDeviceSerial;
    private TextView txtLicenseStatus;
    private EditText edtActiveKey;
    private Button btnActivateOnline;
    private View layoutActivationInput;
    private Button btnResetLicense;

    private Switch switchVcam;
    private Button btnOpenFloating;
    private TextView txtVideoInfo;
    private TextView txtAudioInfo;
    private RadioGroup rgAudioMode;
    private RadioButton rbAudioVirtual;
    private RadioButton rbAudioMix;
    private RadioButton rbAudioReal;

    private static final String FILE_MIC_BOOST = "vcam_mic_boost";
    private Button btnMainMicBoost;
    private int mCurrentBoostIndex = 2; // Default x3.0

    private static final String[] BOOST_LABELS = new String[] {
        "🎙️ KHUẾCH ĐẠI MIC: x1.0 (GỐC)",
        "🎙️ KHUẾCH ĐẠI MIC: x2.0 (VỪA)",
        "🎙️ KHUẾCH ĐẠI MIC: x3.0 (TO RÕ - KHUYÊN DÙNG)",
        "🎙️ KHUẾCH ĐẠI MIC: x4.5 (CỰC TO)",
        "🎙️ KHUẾCH ĐẠI MIC: x6.0 (TỐI ĐA)"
    };

    private static final String[] BOOST_VALS = new String[] {
        "1.0",
        "2.0",
        "3.0",
        "4.5",
        "6.0"
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LocaleManager.applyLocale(this);
        super.onCreate(savedInstanceState);
        LicenseManager.init(this);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        txtLicenseBadge = findViewById(R.id.txt_license_badge);
        txtDeviceSerial = findViewById(R.id.txt_device_serial);
        txtLicenseStatus = findViewById(R.id.txt_license_status);
        edtActiveKey = findViewById(R.id.edt_active_key);
        btnActivateOnline = findViewById(R.id.btn_activate_online);
        Button btnCopySerial = findViewById(R.id.btn_copy_serial);

        TextView btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showLanguageDialog());
        }

        switchVcam = findViewById(R.id.switch_vcam);
        btnOpenFloating = findViewById(R.id.btn_open_floating);
        txtVideoInfo = findViewById(R.id.txt_video_info);
        txtAudioInfo = findViewById(R.id.txt_audio_info);
        rgAudioMode = findViewById(R.id.rg_audio_mode);
        rbAudioVirtual = findViewById(R.id.rb_audio_virtual);
        rbAudioMix = findViewById(R.id.rb_audio_mix);
        rbAudioReal = findViewById(R.id.rb_audio_real);
        btnMainMicBoost = findViewById(R.id.btn_main_mic_boost);
        if (btnMainMicBoost != null) {
            btnMainMicBoost.setOnClickListener(v -> cycleMicBoost());
        }

        Button btnPickVideo = findViewById(R.id.btn_pick_video);
        Button btnPickAudio = findViewById(R.id.btn_pick_audio);
        Button btnApply = findViewById(R.id.btn_apply);

        // 0. Sao chép Serial
        final String serial = LicenseManager.getDeviceSerial();
        txtDeviceSerial.setText(serial);
        btnCopySerial.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("Pixel 4 Serial", serial);
            cm.setPrimaryClip(cd);
            Toast.makeText(this, getString(R.string.toast_copied) + serial, Toast.LENGTH_SHORT).show();
        });

        btnResetLicense = findViewById(R.id.btn_reset_license);
        layoutActivationInput = findViewById(R.id.layout_activation_input);
        btnResetLicense.setOnClickListener(v -> {
            LicenseManager.removeLicense();
            if (switchVcam != null && switchVcam.isChecked()) {
                switchVcam.setChecked(false);
            }
            writeFlag(FLAG_DISABLE, true);
            try {
                stopService(new Intent(this, FloatingControlService.class));
            } catch (Throwable ignored) {}
            updateLicenseUI();
            Toast.makeText(this, R.string.toast_key_removed, Toast.LENGTH_SHORT).show();
        });

        // 1. Kích hoạt Online
        btnActivateOnline.setOnClickListener(v -> {
            String key = edtActiveKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.toast_enter_key, Toast.LENGTH_SHORT).show();
                return;
            }
            btnActivateOnline.setEnabled(false);
            btnActivateOnline.setText("...");
            LicenseManager.activateOnlineAsync(key, (success, message) -> runOnUiThread(() -> {
                btnActivateOnline.setEnabled(true);
                btnActivateOnline.setText(R.string.btn_activate_online);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                updateLicenseUI();
            }));
        });

        // 2. Mở Cửa Sổ Nổi (Floating Overlay)
        btnOpenFloating.setOnClickListener(v -> {
            LicenseManager.LicenseInfo lic = LicenseManager.checkLicense();
            if (!lic.isValid) {
                Toast.makeText(this, R.string.license_status_default, Toast.LENGTH_LONG).show();
                return;
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, R.string.toast_overlay_perm, Toast.LENGTH_LONG).show();
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(new Intent(this, FloatingControlService.class));
                } else {
                    startService(new Intent(this, FloatingControlService.class));
                }
                Toast.makeText(this, R.string.toast_floating_opened, Toast.LENGTH_SHORT).show();
            }
        });

        // 3. Chọn Video / Audio
        btnPickVideo.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Toast.makeText(this, "Vui lòng bật quyền 'Cho phép quản lý tất cả tệp'!", Toast.LENGTH_LONG).show();
                    return;
                } catch (Throwable ignored) {}
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            startActivityForResult(intent, REQ_PICK_VIDEO);
        });

        btnPickAudio.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Toast.makeText(this, "Vui lòng bật quyền 'Cho phép quản lý tất cả tệp'!", Toast.LENGTH_LONG).show();
                    return;
                } catch (Throwable ignored) {}
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            startActivityForResult(intent, REQ_PICK_AUDIO);
        });

        // 4. Lưu cài đặt thủ công
        btnApply.setOnClickListener(v -> applySettings());

        loadCurrentState();
        updateLicenseUI();
    }

    private void setupAudioModeListener() {
        if (rgAudioMode == null) return;
        rgAudioMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_audio_real) {
                writeFlag(FLAG_MIC_DISABLE, true);
                writeFlag(FLAG_MIC_MIX, false);
                Toast.makeText(this, "🎙️ Chế độ Micro: Dùng Mic thật", Toast.LENGTH_SHORT).show();
            } else if (checkedId == R.id.rb_audio_mix) {
                writeFlag(FLAG_MIC_DISABLE, false);
                writeFlag(FLAG_MIC_MIX, true);
                Toast.makeText(this, "🎙️ Chế độ Micro: Trộn âm thanh Mic + Nhạc", Toast.LENGTH_SHORT).show();
            } else if (checkedId == R.id.rb_audio_virtual) {
                writeFlag(FLAG_MIC_DISABLE, false);
                writeFlag(FLAG_MIC_MIX, false);
                Toast.makeText(this, "🎙️ Chế độ Micro: Phát nhạc ảo", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCurrentState();
        updateLicenseUI();
        LicenseManager.checkOnlineAsync((isValid, message) -> runOnUiThread(() -> {
            if (!isValid) {
                updateLicenseUI();
                if (switchVcam != null && switchVcam.isChecked()) {
                    switchVcam.setChecked(false);
                }
                writeFlag(FLAG_DISABLE, true);
                try {
                    stopService(new Intent(this, FloatingControlService.class));
                } catch (Throwable ignored) {}
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }));
        FloatingControlService.setVcamStateListener(isEnabled -> runOnUiThread(() -> {
            if (switchVcam != null) {
                switchVcam.setOnCheckedChangeListener(null);
                switchVcam.setChecked(isEnabled);
                setupVcamSwitchListener();
            }
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
        FloatingControlService.setVcamStateListener(null);
    }

    private void showLanguageDialog() {
        Log.i("CameraAssistant", "showLanguageDialog invoked!");
        runOnUiThread(() -> {
            try {
                final String[] langs = new String[] {
                    getString(R.string.lang_vi),
                    getString(R.string.lang_en),
                    getString(R.string.lang_zh)
                };
                final String[] langCodes = new String[] {
                    LocaleManager.LANG_VI,
                    LocaleManager.LANG_EN,
                    LocaleManager.LANG_ZH
                };

                String currentLang = LocaleManager.getLanguage(this);
                int selectedIndex = 0;
                for (int i = 0; i < langCodes.length; i++) {
                    if (langCodes[i].equals(currentLang)) {
                        selectedIndex = i;
                        break;
                    }
                }

                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_settings_title)
                        .setSingleChoiceItems(langs, selectedIndex, (dialog, which) -> {
                            String chosenLang = langCodes[which];
                            Log.i("CameraAssistant", "Language selected: " + chosenLang);
                            if (!chosenLang.equals(currentLang)) {
                                LocaleManager.setLanguage(this, chosenLang);
                                dialog.dismiss();
                                Toast.makeText(this, R.string.toast_lang_changed, Toast.LENGTH_SHORT).show();
                                recreate();
                            } else {
                                dialog.dismiss();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } catch (Throwable t) {
                Log.e("CameraAssistant", "showLanguageDialog error: " + t.getMessage(), t);
            }
        });
    }

    private void setupVcamSwitchListener() {
        if (switchVcam == null) return;
        switchVcam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                LicenseManager.LicenseInfo lic = LicenseManager.checkLicense();
                if (!lic.isValid) {
                    switchVcam.setOnCheckedChangeListener(null);
                    switchVcam.setChecked(false);
                    setupVcamSwitchListener();
                    Toast.makeText(this, R.string.license_status_default, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            writeFlag(FLAG_DISABLE, !isChecked);
            FloatingControlService.syncVcamStateFromActivity(isChecked);
            if (isChecked) {
                Toast.makeText(this, R.string.toast_vcam_on, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_vcam_off, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateLicenseUI() {
        LicenseManager.LicenseInfo lic = LicenseManager.checkLicense();
        if (lic.isValid) {
            txtLicenseBadge.setText(R.string.license_active);
            txtLicenseBadge.setTextColor(0xFF00E676);
            txtLicenseStatus.setText(lic.message);
            txtLicenseStatus.setTextColor(0xFF80CBC4);
            btnOpenFloating.setEnabled(true);
            btnOpenFloating.setAlpha(1.0f);

            if (layoutActivationInput != null) {
                layoutActivationInput.setVisibility(View.GONE);
            }
            if (btnResetLicense != null) {
                btnResetLicense.setVisibility(View.VISIBLE);
            }
        } else {
            txtLicenseBadge.setText(R.string.license_inactive);
            txtLicenseBadge.setTextColor(0xFFFF5252);
            txtLicenseStatus.setText(lic.message != null ? lic.message : getString(R.string.license_status_default));
            txtLicenseStatus.setTextColor(0xFFFFAB91);
            btnOpenFloating.setEnabled(false);
            btnOpenFloating.setAlpha(0.5f);

            if (layoutActivationInput != null) {
                layoutActivationInput.setVisibility(View.VISIBLE);
            }
            if (btnResetLicense != null) {
                btnResetLicense.setVisibility(View.GONE);
            }
            if (edtActiveKey != null) {
                edtActiveKey.setText("");
            }
        }
    }

    private void loadCurrentState() {
        boolean isDisabled = isFlagActive(FLAG_DISABLE);
        switchVcam.setOnCheckedChangeListener(null);
        switchVcam.setChecked(!isDisabled);
        setupVcamSwitchListener();

        if (rgAudioMode != null) {
            rgAudioMode.setOnCheckedChangeListener(null);
        }
        boolean isMicDisabled = isFlagActive(FLAG_MIC_DISABLE);
        boolean isMicMix = isFlagActive(FLAG_MIC_MIX);
        if (isMicDisabled) {
            rbAudioReal.setChecked(true);
        } else if (isMicMix) {
            rbAudioMix.setChecked(true);
        } else {
            rbAudioVirtual.setChecked(true);
        }
        setupAudioModeListener();

        File videoFile = new File("/data/local/tmp/" + TARGET_VIDEO);
        if (!videoFile.exists()) videoFile = new File("/sdcard/" + TARGET_VIDEO);
        if (videoFile.exists()) {
            txtVideoInfo.setText("Video: " + videoFile.getAbsolutePath() + " (" + (videoFile.length() / 1024 / 1024) + " MB)");
        } else {
            txtVideoInfo.setText("Chưa có video, hệ thống dùng mặc định");
        }

        File audioFile = new File("/data/local/tmp/" + TARGET_AUDIO);
        if (!audioFile.exists()) audioFile = new File("/sdcard/" + TARGET_AUDIO);
        if (audioFile.exists()) {
            txtAudioInfo.setText("Audio: " + audioFile.getAbsolutePath() + " (" + (audioFile.length() / 1024 / 1024) + " MB)");
        } else {
            txtAudioInfo.setText("Chưa có audio, hệ thống dùng mặc định");
        }

        String savedBoost = readStringFile("/data/local/tmp/" + FILE_MIC_BOOST);
        if (savedBoost.isEmpty()) savedBoost = readStringFile("/sdcard/" + FILE_MIC_BOOST);
        for (int i = 0; i < BOOST_VALS.length; i++) {
            if (BOOST_VALS[i].equals(savedBoost)) {
                mCurrentBoostIndex = i;
                break;
            }
        }
        updateMicBoostUi();
    }

    private void applySettings() {
        try {
            boolean isChecked = switchVcam.isChecked();
            writeFlag(FLAG_DISABLE, !isChecked);
            FloatingControlService.syncVcamStateFromActivity(isChecked);

            if (rbAudioReal.isChecked()) {
                writeFlag(FLAG_MIC_DISABLE, true);
                writeFlag(FLAG_MIC_MIX, false);
            } else if (rbAudioMix.isChecked()) {
                writeFlag(FLAG_MIC_DISABLE, false);
                writeFlag(FLAG_MIC_MIX, true);
            } else {
                writeFlag(FLAG_MIC_DISABLE, false);
                writeFlag(FLAG_MIC_MIX, false);
            }

            writeBoostVal(BOOST_VALS[mCurrentBoostIndex]);

            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            loadCurrentState();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cycleMicBoost() {
        mCurrentBoostIndex = (mCurrentBoostIndex + 1) % BOOST_LABELS.length;
        updateMicBoostUi();
        writeBoostVal(BOOST_VALS[mCurrentBoostIndex]);
        Toast.makeText(this, BOOST_LABELS[mCurrentBoostIndex], Toast.LENGTH_SHORT).show();
    }

    private void updateMicBoostUi() {
        if (btnMainMicBoost != null) {
            btnMainMicBoost.setText(BOOST_LABELS[mCurrentBoostIndex]);
        }
    }

    private void writeBoostVal(String val) {
        writeStringFile("/data/local/tmp/" + FILE_MIC_BOOST, val);
        writeStringFile("/sdcard/" + FILE_MIC_BOOST, val);
        writeStringFile("/storage/emulated/0/" + FILE_MIC_BOOST, val);
    }

    private String readStringFile(String path) {
        File f = new File(path);
        if (!f.exists() || f.length() == 0) return "";
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int r = fis.read(b);
            fis.close();
            return new String(b, 0, r, "UTF-8").trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void writeStringFile(String path, String val) {
        try {
            File f = new File(path);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(val.getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_PICK_VIDEO) {
                copyUriToDualLocations(uri, TARGET_VIDEO, getString(R.string.toast_video_updated));
            } else if (requestCode == REQ_PICK_AUDIO) {
                copyUriToDualLocations(uri, TARGET_AUDIO, getString(R.string.toast_audio_updated));
            }
        }
    }

    private void copyUriToDualLocations(Uri srcUri, String filename, String successMsg) {
        File tmpFile = new File("/data/local/tmp/" + filename);
        File sdFile = new File("/sdcard/" + filename);

        boolean wroteTmp = false;
        boolean wroteSd = false;

        // 1. Luôn ghi vào /data/local/tmp/ (thư mục 777 không bị Scoped Storage chặn)
        try {
            InputStream in = getContentResolver().openInputStream(srcUri);
            if (in != null) {
                OutputStream out = new FileOutputStream(tmpFile);
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();
                tmpFile.setReadable(true, false);
                tmpFile.setWritable(true, false);
                wroteTmp = true;
            }
        } catch (Throwable t) {
            android.util.Log.e("CameraAssistant", "Ghi /data/local/tmp thất bại", t);
        }

        // 2. Đồng thời sao chép sang /sdcard/
        try {
            InputStream inSd = getContentResolver().openInputStream(srcUri);
            if (inSd != null) {
                OutputStream outSd = new FileOutputStream(sdFile);
                byte[] buf = new byte[16384];
                int len;
                while ((len = inSd.read(buf)) > 0) {
                    outSd.write(buf, 0, len);
                }
                inSd.close();
                outSd.close();
                sdFile.setReadable(true, false);
                sdFile.setWritable(true, false);
                wroteSd = true;
            }
        } catch (Throwable t) {
            android.util.Log.e("CameraAssistant", "Ghi /sdcard thất bại", t);
        }

        if (wroteTmp || wroteSd) {
            Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
            loadCurrentState();
        } else {
            Toast.makeText(this, "Không thể lưu tệp! Vui lòng cấp quyền Quản lý tệp.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isFlagActive(String name) {
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name),
            new File(Environment.getExternalStorageDirectory(), name)
        };
        for (File f : targets) {
            if (f.exists()) {
                if (f.length() == 0) return true; // File created by touch from adb shell
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

    private void writeFlag(String name, boolean active) {
        Log.i("CameraAssistant", "MainActivity writeFlag: " + name + " -> " + active);
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name),
            new File(Environment.getExternalStorageDirectory(), name)
        };
        for (File f : targets) {
            try {
                if (active) {
                    if (!f.exists()) {
                        f.createNewFile();
                    }
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write("1\n".getBytes("UTF-8"));
                    fos.close();
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                    Log.i("CameraAssistant", "MainActivity created flag: " + f.getAbsolutePath());
                } else {
                    deleteFileSafely(f);
                }
            } catch (Throwable t) {
                Log.w("CameraAssistant", "MainActivity writeFlag error for " + f.getAbsolutePath() + ": " + t.getMessage());
            }
        }
    }

    private void deleteFileSafely(File file) {
        if (file == null || !file.exists()) return;

        // 1. Standard File.delete
        boolean deleted = file.delete();
        if (deleted) {
            Log.i("CameraAssistant", "MainActivity deleteFileSafely: File.delete succeeded for " + file.getAbsolutePath());
            return;
        }

        // 2. Canonical delete
        try {
            if (file.getCanonicalFile().delete()) {
                Log.i("CameraAssistant", "MainActivity deleteFileSafely: CanonicalFile.delete succeeded for " + file.getAbsolutePath());
                return;
            }
        } catch (Throwable ignored) {}

        // 3. Java NIO deleteIfExists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (java.nio.file.Files.deleteIfExists(file.toPath())) {
                    Log.i("CameraAssistant", "MainActivity deleteFileSafely: NIO delete succeeded for " + file.getAbsolutePath());
                    return;
                }
            } catch (Throwable ignored) {}
        }

        // 4. MediaStore delete
        try {
            Uri contentUri = MediaStore.Files.getContentUri("external");
            int count = getContentResolver().delete(contentUri,
                    MediaStore.MediaColumns.DATA + "=?",
                    new String[]{file.getAbsolutePath()});
            if (count > 0) {
                Log.i("CameraAssistant", "MainActivity deleteFileSafely: MediaStore delete succeeded for " + file.getAbsolutePath());
                return;
            }
        } catch (Throwable ignored) {}

        // 5. Shell rm
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/rm", "-f", file.getAbsolutePath()});
            p.waitFor();
            if (!file.exists()) {
                Log.i("CameraAssistant", "MainActivity deleteFileSafely: /system/bin/rm succeeded for " + file.getAbsolutePath());
                return;
            }
        } catch (Throwable ignored) {}

        // 6. Overwrite with 0 if delete failed
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("0\n".getBytes("UTF-8"));
            fos.close();
            Log.i("CameraAssistant", "MainActivity deleteFileSafely: Overwritten with 0 for " + file.getAbsolutePath());
            return;
        } catch (Throwable ignored) {}

        Log.w("CameraAssistant", "MainActivity deleteFileSafely: Could NOT delete " + file.getAbsolutePath() + ", still exists=" + file.exists());
    }
}
