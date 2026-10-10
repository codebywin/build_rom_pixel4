package org.lineageos.camera.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
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
import android.widget.SeekBar;
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
    private static final int REQ_MEDIA_PROJECTION = 103;
    private static final int REQ_PICK_IMAGE = 104;

    private static final String FLAG_DISABLE = "vcam_disable";
    private static final String FLAG_MIC_DISABLE = "vcam_mic_disable";
    private static final String FLAG_MIC_MIX = "vcam_mic_mix";

    private static final String TARGET_VIDEO = "vcam.mp4";
    private static final String TARGET_AUDIO = "vcam.wav";
    private static final String TARGET_IMAGE = "vcam.jpg";
    public static final String SDCARD_DIR = "/sdcard/CameraAssistant/";

    private TextView txtLicenseBadge;
    private TextView txtDeviceSerial;
    private TextView txtLicenseStatus;
    private EditText edtActiveKey;
    private Button btnActivateOnline;
    private View layoutActivationInput;
    private Button btnResetLicense;

    private Switch switchVcam;
    private Switch switchSwapUv;
    private Button btnOpenFloating;
    private TextView txtVideoInfo;
    private TextView txtImageInfo;
    private TextView txtAudioInfo;
    private RadioGroup rgAudioMode;
    private RadioButton rbAudioVirtual;
    private RadioButton rbAudioMix;
    private RadioButton rbAudioReal;

    private static final String FILE_RAW_BRIGHT = "vcam_raw_bright";
    private TextView txtMainBrightBadge;
    private SeekBar sbMainRawBright;
    private Button btnMainBright50, btnMainBright65, btnMainBright80, btnMainBright100;
    private int mCurrentRawBright = 65;

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
        if (LicenseManager.isAppTampered(this)) {
            Toast.makeText(this, "Cảnh báo: Phát hiện ứng dụng bị can thiệp trái phép!", Toast.LENGTH_LONG).show();
            finishAffinity();
            return;
        }
        LicenseManager.ensureLicenseSharedSync();
        migrateAndEnsureStorageDir();
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
        switchSwapUv = findViewById(R.id.switch_swap_uv);
        btnOpenFloating = findViewById(R.id.btn_open_floating);
        txtVideoInfo = findViewById(R.id.txt_video_info);
        txtImageInfo = findViewById(R.id.txt_image_info);
        txtAudioInfo = findViewById(R.id.txt_audio_info);
        rgAudioMode = findViewById(R.id.rg_audio_mode);
        rbAudioVirtual = findViewById(R.id.rb_audio_virtual);
        rbAudioMix = findViewById(R.id.rb_audio_mix);
        rbAudioReal = findViewById(R.id.rb_audio_real);
        btnMainMicBoost = findViewById(R.id.btn_main_mic_boost);
        if (btnMainMicBoost != null) {
            btnMainMicBoost.setOnClickListener(v -> cycleMicBoost());
        }

        txtMainBrightBadge = findViewById(R.id.txt_main_bright_badge);
        sbMainRawBright = findViewById(R.id.sb_main_raw_bright);
        btnMainBright50 = findViewById(R.id.btn_main_bright_50);
        btnMainBright65 = findViewById(R.id.btn_main_bright_65);
        btnMainBright80 = findViewById(R.id.btn_main_bright_80);
        btnMainBright100 = findViewById(R.id.btn_main_bright_100);

        if (sbMainRawBright != null) {
            sbMainRawBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mCurrentRawBright = progress + 20;
                    if (txtMainBrightBadge != null) {
                        txtMainBrightBadge.setText(mCurrentRawBright + "%");
                    }
                    updateMainBrightButtons();
                    if (fromUser) {
                        writeRawBright(mCurrentRawBright);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    writeRawBright(mCurrentRawBright);
                }
            });
        }
        if (btnMainBright50 != null) btnMainBright50.setOnClickListener(v -> setMainRawBright(50));
        if (btnMainBright65 != null) btnMainBright65.setOnClickListener(v -> setMainRawBright(65));
        if (btnMainBright80 != null) btnMainBright80.setOnClickListener(v -> setMainRawBright(80));
        if (btnMainBright100 != null) btnMainBright100.setOnClickListener(v -> setMainRawBright(100));

        Button btnPickVideo = findViewById(R.id.btn_pick_video);
        Button btnPickImage = findViewById(R.id.btn_pick_image);
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

        if (btnPickImage != null) {
            btnPickImage.setOnClickListener(v -> {
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
                intent.setType("image/*");
                startActivityForResult(intent, REQ_PICK_IMAGE);
            });
        }

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

        if (getIntent() != null && "request_media_projection".equals(getIntent().getStringExtra("action"))) {
            requestMediaProjectionPermission();
        }
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
        LicenseManager.ensureLicenseSharedSync();
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && "request_media_projection".equals(intent.getStringExtra("action"))) {
            requestMediaProjectionPermission();
        }
    }

    private void requestMediaProjectionPermission() {
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ Screen Capture", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } catch (Throwable e) {
            Toast.makeText(this, "Không thể yêu cầu quyền Screen Capture: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                FloatingControlService svc = FloatingControlService.getInstance();
                if (svc != null) {
                    svc.startFlashScreenCapMode(resultCode, data);
                    Toast.makeText(this, "✅ Đã cấp quyền Screen Capture cho KYC Flash!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Chưa mở Camera Assistant HUD, vui lòng mở trước",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "❌ Không cấp quyền Screen Capture", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // File picker (video / image / audio)
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_PICK_VIDEO) {
                copyUriToDualLocations(uri, TARGET_VIDEO, getString(R.string.toast_video_updated));
            } else if (requestCode == REQ_PICK_IMAGE) {
                copyUriToDualLocations(uri, TARGET_IMAGE, getString(R.string.toast_image_updated));
            } else if (requestCode == REQ_PICK_AUDIO) {
                copyUriToDualLocations(uri, TARGET_AUDIO, getString(R.string.toast_audio_updated));
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
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

    private void setupSwapUvListener() {
        if (switchSwapUv == null) return;
        switchSwapUv.setOnCheckedChangeListener((buttonView, isChecked) -> {
            writeFlag("vcam_swap_uv", isChecked);
            if (isChecked) {
                Toast.makeText(this, R.string.toast_swap_uv_on, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_swap_uv_off, Toast.LENGTH_SHORT).show();
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

        if (switchSwapUv != null) {
            boolean isSwapUv = isFlagActive("vcam_swap_uv");
            switchSwapUv.setOnCheckedChangeListener(null);
            switchSwapUv.setChecked(isSwapUv);
            setupSwapUvListener();
        }

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
        File intVideo = new File(getFilesDir(), TARGET_VIDEO);
        if (intVideo.exists() && intVideo.length() > 0) {
            videoFile = intVideo;
        } else {
            File sdVideo = new File(SDCARD_DIR + TARGET_VIDEO);
            if (!sdVideo.exists()) sdVideo = new File("/sdcard/" + TARGET_VIDEO);
            if (sdVideo.exists() && sdVideo.length() > 0 && (!videoFile.exists() || videoFile.length() != sdVideo.length())) {
                final File finalSdVideo = sdVideo;
                sIoExecutor.execute(() -> {
                    try {
                        String cmd = "cp '" + finalSdVideo.getAbsolutePath() + "' '/data/local/tmp/" + TARGET_VIDEO + "' && " +
                                     "chmod 666 '/data/local/tmp/" + TARGET_VIDEO + "' && " +
                                     "chown shell:shell '/data/local/tmp/" + TARGET_VIDEO + "' 2>/dev/null";
                        Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                    } catch (Throwable ignored) {}
                });
                videoFile = sdVideo;
            } else if (!videoFile.exists()) {
                videoFile = sdVideo;
            }
        }

        File imgFile = new File("/data/local/tmp/" + TARGET_IMAGE);
        File sdImg = new File(SDCARD_DIR + TARGET_IMAGE);
        if (!sdImg.exists()) sdImg = new File("/sdcard/" + TARGET_IMAGE);
        if (sdImg.exists() && sdImg.length() > 0 && (!imgFile.exists() || imgFile.length() != sdImg.length())) {
            final File finalSdImg = sdImg;
            sIoExecutor.execute(() -> {
                try {
                    String cmd = "cp '" + finalSdImg.getAbsolutePath() + "' '/data/local/tmp/" + TARGET_IMAGE + "' && " +
                                 "chmod 666 '/data/local/tmp/" + TARGET_IMAGE + "' && " +
                                 "chown shell:shell '/data/local/tmp/" + TARGET_IMAGE + "' 2>/dev/null";
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                } catch (Throwable ignored) {}
            });
            imgFile = sdImg;
        } else if (!imgFile.exists()) {
            imgFile = sdImg;
        }

        boolean hasVideo = videoFile.exists() && videoFile.length() > 0;
        boolean hasImg = imgFile.exists() && imgFile.length() > 0;
        boolean isImgActive = hasImg && (!hasVideo || imgFile.lastModified() > videoFile.lastModified());
        boolean isVideoActive = hasVideo && (!hasImg || videoFile.lastModified() >= imgFile.lastModified());

        if (hasVideo) {
            String status = isVideoActive ? "  [🟢 ĐANG DÙNG]" : "";
            txtVideoInfo.setText("Video: " + videoFile.getAbsolutePath() + " (" + (videoFile.length() / 1024 / 1024) + " MB)" + status);
        } else {
            txtVideoInfo.setText("Chưa có video, hệ thống dùng mặc định");
        }

        if (txtImageInfo != null) {
            if (hasImg) {
                String status = isImgActive ? "  [🟢 ĐANG DÙNG]" : "";
                txtImageInfo.setText("Hình ảnh: " + imgFile.getAbsolutePath() + " (" + (imgFile.length() / 1024) + " KB)" + status);
            } else {
                txtImageInfo.setText("Chưa có hình ảnh nào được chọn");
            }
        }

        File audioFile = new File("/data/local/tmp/" + TARGET_AUDIO);
        File sdAudio = new File(SDCARD_DIR + TARGET_AUDIO);
        if (!sdAudio.exists()) sdAudio = new File("/sdcard/" + TARGET_AUDIO);
        if (sdAudio.exists() && sdAudio.length() > 0 && (!audioFile.exists() || audioFile.length() != sdAudio.length())) {
            final File finalSdAudio = sdAudio;
            sIoExecutor.execute(() -> {
                try {
                    String cmd = "cp '" + finalSdAudio.getAbsolutePath() + "' '/data/local/tmp/" + TARGET_AUDIO + "' && " +
                                 "chmod 666 '/data/local/tmp/" + TARGET_AUDIO + "' && " +
                                 "chown shell:shell '/data/local/tmp/" + TARGET_AUDIO + "' 2>/dev/null";
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                } catch (Throwable ignored) {}
            });
            audioFile = sdAudio;
        } else if (!audioFile.exists()) {
            audioFile = sdAudio;
        }

        if (audioFile.exists() && audioFile.length() > 0) {
            txtAudioInfo.setText("Audio: " + audioFile.getAbsolutePath() + " (" + (audioFile.length() / 1024 / 1024) + " MB)");
        } else {
            txtAudioInfo.setText("Chưa có audio, hệ thống dùng mặc định");
        }

        String savedBoost = VcamConfigProvider.getString("mic_boost", "");
        if (savedBoost.isEmpty()) savedBoost = readStringFile("/data/local/tmp/" + FILE_MIC_BOOST);
        if (savedBoost.isEmpty()) savedBoost = readStringFile(SDCARD_DIR + FILE_MIC_BOOST);
        if (savedBoost.isEmpty()) savedBoost = readStringFile("/sdcard/" + FILE_MIC_BOOST);
        for (int i = 0; i < BOOST_VALS.length; i++) {
            if (BOOST_VALS[i].equals(savedBoost)) {
                mCurrentBoostIndex = i;
                break;
            }
        }
        updateMicBoostUi();

        mCurrentRawBright = readIntValue(FILE_RAW_BRIGHT, 65);
        if (mCurrentRawBright < 20) mCurrentRawBright = 20;
        if (mCurrentRawBright > 150) mCurrentRawBright = 150;
        if (sbMainRawBright != null) {
            sbMainRawBright.setProgress(mCurrentRawBright - 20);
        }
        if (txtMainBrightBadge != null) {
            txtMainBrightBadge.setText(mCurrentRawBright + "%");
        }
        updateMainBrightButtons();
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
            writeRawBright(mCurrentRawBright);

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
        VcamConfigProvider.setString("mic_boost", val);
        writeStringFile("/data/local/tmp/" + FILE_MIC_BOOST, val);
        writeStringFile(SDCARD_DIR + FILE_MIC_BOOST, val);
        deleteFileSafely(new File("/sdcard/" + FILE_MIC_BOOST));
        deleteFileSafely(new File("/storage/emulated/0/" + FILE_MIC_BOOST));
    }

    private void setMainRawBright(int val) {
        mCurrentRawBright = val;
        if (mCurrentRawBright < 20) mCurrentRawBright = 20;
        if (mCurrentRawBright > 150) mCurrentRawBright = 150;
        if (sbMainRawBright != null) {
            sbMainRawBright.setProgress(mCurrentRawBright - 20);
        }
        if (txtMainBrightBadge != null) {
            txtMainBrightBadge.setText(mCurrentRawBright + "%");
        }
        updateMainBrightButtons();
        writeRawBright(mCurrentRawBright);
    }

    private void updateMainBrightButtons() {
        if (btnMainBright50 != null) btnMainBright50.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mCurrentRawBright == 50 ? 0xFF00838F : 0xFF263238));
        if (btnMainBright65 != null) btnMainBright65.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mCurrentRawBright == 65 ? 0xFF00838F : 0xFF263238));
        if (btnMainBright80 != null) btnMainBright80.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mCurrentRawBright == 80 ? 0xFF00838F : 0xFF263238));
        if (btnMainBright100 != null) btnMainBright100.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mCurrentRawBright == 100 ? 0xFF00838F : 0xFF263238));
    }

    private void writeRawBright(int val) {
        VcamConfigProvider.setInt("raw_bright", val);
        VcamConfigProvider.setFloat("bright_factor", val / 65.0f);
        writeStringFile("/data/local/tmp/" + FILE_RAW_BRIGHT, String.valueOf(val) + "\n");
        writeStringFile(SDCARD_DIR + FILE_RAW_BRIGHT, String.valueOf(val) + "\n");
        deleteFileSafely(new File("/sdcard/" + FILE_RAW_BRIGHT));
        deleteFileSafely(new File("/storage/emulated/0/" + FILE_RAW_BRIGHT));
    }

    private int readIntValue(String name, int defVal) {
        if (FILE_RAW_BRIGHT.equals(name)) {
            return VcamConfigProvider.getInt("raw_bright", defVal);
        }
        String s1 = readStringFile("/data/local/tmp/" + name);
        if (!s1.isEmpty()) {
            try { return Integer.parseInt(s1); } catch (Throwable ignored) {}
        }
        String s2 = readStringFile(SDCARD_DIR + name);
        if (!s2.isEmpty()) {
            try { return Integer.parseInt(s2); } catch (Throwable ignored) {}
        }
        String s3 = readStringFile("/sdcard/" + name);
        if (!s3.isEmpty()) {
            try { return Integer.parseInt(s3); } catch (Throwable ignored) {}
        }
        return defVal;
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


    private void copyUriToDualLocations(Uri srcUri, String filename, String successMsg) {
        sIoExecutor.execute(() -> {
            File cacheFile = new File(getCacheDir(), filename);
            boolean copiedToCache = false;
            try (InputStream in = getContentResolver().openInputStream(srcUri);
                 OutputStream out = new FileOutputStream(cacheFile)) {
                if (in != null) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    copiedToCache = true;
                }
            } catch (Throwable t) {
                Log.e("CameraAssistant", "Lỗi lưu cache tệp " + filename, t);
            }

            if (!copiedToCache || cacheFile.length() == 0) {
                runOnUiThread(() -> Toast.makeText(this, "Không thể đọc tệp đã chọn!", Toast.LENGTH_LONG).show());
                return;
            }

            // 0. Lưu trực tiếp vào bộ nhớ riêng của App (Internal Storage) - 100% rootless & an toàn tuyệt đối
            try {
                File internalFile = new File(getFilesDir(), filename);
                try (InputStream inApp = new java.io.FileInputStream(cacheFile);
                     OutputStream outApp = new FileOutputStream(internalFile)) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = inApp.read(buf)) > 0) {
                        outApp.write(buf, 0, len);
                    }
                }
                internalFile.setReadable(true, false);
                if (TARGET_VIDEO.equals(filename)) {
                    VcamConfigProvider.setBoolean("video_ready", true);
                } else if (TARGET_AUDIO.equals(filename)) {
                    VcamConfigProvider.setBoolean("audio_ready", true);
                }
            } catch (Throwable t) {
                Log.e("CameraAssistant", "Lỗi lưu file bộ nhớ riêng: " + filename, t);
            }

            // 1. Sao chép sang /sdcard/CameraAssistant/
            try {
                File dir = new File(SDCARD_DIR);
                if (!dir.exists()) dir.mkdirs();
                File sdFile = new File(dir, filename);
                try (InputStream inSd = new java.io.FileInputStream(cacheFile);
                     OutputStream outSd = new FileOutputStream(sdFile)) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = inSd.read(buf)) > 0) {
                        outSd.write(buf, 0, len);
                    }
                }
                // Xóa tệp cũ ngoài thư mục gốc /sdcard/ nếu có
                deleteFileSafely(new File("/sdcard/" + filename));
            } catch (Throwable ignored) {}

            // 2. Sao chép trực tiếp qua Java Stream vào /data/local/tmp/ (nếu file đã tồn tại và có quyền ghi)
            try {
                File tmpFile = new File("/data/local/tmp/" + filename);
                try (InputStream inTmp = new java.io.FileInputStream(cacheFile);
                     OutputStream outTmp = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = inTmp.read(buf)) > 0) {
                        outTmp.write(buf, 0, len);
                    }
                }
                tmpFile.setReadable(true, false);
                tmpFile.setWritable(true, false);
            } catch (Throwable ignored) {}

            // 3. Dùng Root sao chép sang /data/local/tmp/ và phân quyền 666 nếu máy có root
            try {
                String cmd = "cp '" + cacheFile.getAbsolutePath() + "' '/data/local/tmp/" + filename + "' && chmod 666 '/data/local/tmp/" + filename + "'";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}

            runOnUiThread(() -> {
                Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
                loadCurrentState();
            });
        });
    }

    private boolean isFlagActive(String name) {
        if (FLAG_DISABLE.equals(name)) {
            return VcamConfigProvider.getBoolean("disable", false);
        }
        if ("vcam_swap_uv".equals(name)) {
            return VcamConfigProvider.getBoolean("swap_uv", false);
        }
        if (FLAG_MIC_DISABLE.equals(name)) {
            return VcamConfigProvider.getBoolean("mic_disable", false);
        }
        if (FLAG_MIC_MIX.equals(name)) {
            return VcamConfigProvider.getBoolean("mic_mix", false);
        }
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File(SDCARD_DIR + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name),
            new File(Environment.getExternalStorageDirectory(), name)
        };
        for (File f : targets) {
            if (f.exists()) {
                if (f.length() == 0) return true; // File created by touch from adb shell
                try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                    String line = reader.readLine();
                    if (line != null) {
                        String trimmed = line.trim();
                        if ("0".equals(trimmed)) return false;
                        if ("1".equals(trimmed)) return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private void writeFlag(String name, boolean active) {
        Log.i("CameraAssistant", "MainActivity writeFlag: " + name + " -> " + active);
        if (FLAG_DISABLE.equals(name)) {
            VcamConfigProvider.setBoolean("disable", active);
        } else if ("vcam_swap_uv".equals(name)) {
            VcamConfigProvider.setBoolean("swap_uv", active);
        } else if (FLAG_MIC_DISABLE.equals(name)) {
            VcamConfigProvider.setBoolean("mic_disable", active);
        } else if (FLAG_MIC_MIX.equals(name)) {
            VcamConfigProvider.setBoolean("mic_mix", active);
        }
        String val = active ? "1\n" : "0\n";
        writeStringFile("/data/local/tmp/" + name, val);
        writeStringFile(SDCARD_DIR + name, val);
        if (!active) {
            deleteFileSafely(new File("/data/local/tmp/" + name));
            deleteFileSafely(new File(SDCARD_DIR + name));
            deleteFileSafely(new File("/sdcard/" + name));
            deleteFileSafely(new File("/storage/emulated/0/" + name));
        }
    }

    private static final java.util.concurrent.ExecutorService sIoExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void writeStringFile(String path, String val) {
        sIoExecutor.execute(() -> {
            boolean ok = false;
            try {
                File f = new File(path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(val.getBytes("UTF-8"));
                fos.close();
                f.setReadable(true, false);
                f.setWritable(true, false);
                ok = true;
            } catch (Throwable ignored) {}

            if (!ok) {
                try {
                    String cmd = "mkdir -p '$(dirname \"" + path + "\")' 2>/dev/null; echo -n '" + val + "' > " + path + " && chmod 666 " + path;
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    p.waitFor();
                } catch (Throwable ignored2) {}
            }
        });
    }

    private void migrateAndEnsureStorageDir() {
        sIoExecutor.execute(() -> {
            try {
                File dir = new File(SDCARD_DIR);
                if (!dir.exists()) dir.mkdirs();
                String cmd = "mkdir -p /sdcard/CameraAssistant 2>/dev/null && " +
                             "for f in /sdcard/vcam* /sdcard/.vcam_uid; do " +
                             "  if [ -f \"$f\" ]; then mv -f \"$f\" /sdcard/CameraAssistant/ 2>/dev/null; fi; " +
                             "done; " +
                             "chmod -R 777 /sdcard/CameraAssistant 2>/dev/null";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}
        });
    }

    private void deleteFileSafely(File file) {
        if (file == null) return;
        try {
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignored) {}
    }
}
