package org.lineageos.camera.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
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

    private Switch switchVcam;
    private TextView txtVideoInfo;
    private TextView txtAudioInfo;
    private RadioGroup rgAudioMode;
    private RadioButton rbAudioVirtual;
    private RadioButton rbAudioMix;
    private RadioButton rbAudioReal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        switchVcam = findViewById(R.id.switch_vcam);
        txtVideoInfo = findViewById(R.id.txt_video_info);
        txtAudioInfo = findViewById(R.id.txt_audio_info);
        rgAudioMode = findViewById(R.id.rg_audio_mode);
        rbAudioVirtual = findViewById(R.id.rb_audio_virtual);
        rbAudioMix = findViewById(R.id.rb_audio_mix);
        rbAudioReal = findViewById(R.id.rb_audio_real);

        Button btnOpenFloating = findViewById(R.id.btn_open_floating);
        Button btnPickVideo = findViewById(R.id.btn_pick_video);
        Button btnPickAudio = findViewById(R.id.btn_pick_audio);
        Button btnApply = findViewById(R.id.btn_apply);

        // 1. Mở Cửa Sổ Nổi (Floating Overlay)
        btnOpenFloating.setOnClickListener(v -> {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Vui lòng bật quyền 'Cho phép hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show();
            } else {
                startService(new Intent(this, FloatingControlService.class));
                Toast.makeText(this, "Đã mở cửa sổ nổi VCAM!", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. Chọn Video
        btnPickVideo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            startActivityForResult(intent, REQ_PICK_VIDEO);
        });

        // 3. Chọn Audio
        btnPickAudio.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            startActivityForResult(intent, REQ_PICK_AUDIO);
        });

        // 4. Lưu cài đặt
        btnApply.setOnClickListener(v -> applySettings());

        loadCurrentState();
    }

    private void loadCurrentState() {
        boolean isDisabled = isFlagActive(FLAG_DISABLE);
        switchVcam.setChecked(!isDisabled);

        boolean isMicDisabled = isFlagActive(FLAG_MIC_DISABLE);
        boolean isMicMix = isFlagActive(FLAG_MIC_MIX);
        if (isMicDisabled) {
            rbAudioReal.setChecked(true);
        } else if (isMicMix) {
            rbAudioMix.setChecked(true);
        } else {
            rbAudioVirtual.setChecked(true);
        }

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
    }

    private void applySettings() {
        try {
            writeFlag(FLAG_DISABLE, !switchVcam.isChecked());

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

            Toast.makeText(this, "Đã lưu và áp dụng cài đặt thành công!", Toast.LENGTH_SHORT).show();
            loadCurrentState();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi áp dụng: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_PICK_VIDEO) {
                copyUriToDualLocations(uri, TARGET_VIDEO, "Đã cập nhật video mới!");
            } else if (requestCode == REQ_PICK_AUDIO) {
                copyUriToDualLocations(uri, TARGET_AUDIO, "Đã cập nhật audio mới!");
            }
        }
    }

    private void copyUriToDualLocations(Uri srcUri, String filename, String successMsg) {
        File sdFile = new File("/sdcard/" + filename);
        File tmpFile = new File("/data/local/tmp/" + filename);

        try {
            InputStream in = getContentResolver().openInputStream(srcUri);
            OutputStream out = new FileOutputStream(sdFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();

            try {
                InputStream inTmp = new java.io.FileInputStream(sdFile);
                OutputStream outTmp = new FileOutputStream(tmpFile);
                while ((len = inTmp.read(buf)) > 0) {
                    outTmp.write(buf, 0, len);
                }
                inTmp.close();
                outTmp.close();
                tmpFile.setReadable(true, false);
                tmpFile.setWritable(true, false);
            } catch (Throwable ignored) {}

            sdFile.setReadable(true, false);
            sdFile.setWritable(true, false);

            Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
            loadCurrentState();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi sao chép tệp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isFlagActive(String name) {
        return new File("/data/local/tmp/" + name).exists() || new File("/sdcard/" + name).exists();
    }

    private void writeFlag(String name, boolean active) {
        File f1 = new File("/data/local/tmp/" + name);
        File f2 = new File("/sdcard/" + name);
        try {
            if (active) {
                if (!f1.exists()) { f1.createNewFile(); f1.setReadable(true, false); f1.setWritable(true, false); }
                if (!f2.exists()) { f2.createNewFile(); f2.setReadable(true, false); f2.setWritable(true, false); }
            } else {
                if (f1.exists()) f1.delete();
                if (f2.exists()) f2.delete();
            }
        } catch (Throwable ignored) {}
    }
}
