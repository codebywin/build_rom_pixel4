package org.lineageos.camera.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.Locale;

public class FloatingControlService extends Service implements View.OnTouchListener {
    private static final String TAG = "CameraAssistant";

    private WindowManager mWindowManager;
    private View mFloatingView;
    private WindowManager.LayoutParams mParams;

    private View mLayoutBubble;
    private View mLayoutExpanded;

    private TextView mTxtZoom;
    private Button mBtnPause;
    private Button mBtnRotate;
    private Button mBtnColorMode;
    private Switch mSwitchKyc;
    private Switch mSwitchVcam;

    private float mCurrentZoom = 1.0f;
    private float mCurrentPanX = 0.0f;
    private float mCurrentPanY = 0.0f;
    private int mCurrentRotation = 0;
    private int mCurrentColorMode = 0;
    private boolean mIsPaused = false;

    private static final String[] COLOR_MODE_NAMES = new String[] {
        "🎨 MÀU: TỰ ĐỘNG CHỚP",
        "⚪ MÀU: TRẮNG",
        "🔵 MÀU: XANH DƯƠNG",
        "🟢 MÀU: XANH LÁ",
        "🔴 MÀU: ĐỎ",
        "🟡 MÀU: VÀNG"
    };

    private static final String[] COLOR_MODE_VALS = new String[] {
        "auto",
        "#FFFFFF,0.40",
        "#00E5FF,0.40",
        "#00E676,0.40",
        "#FF1744,0.40",
        "#FFEA00,0.40"
    };

    // KYC Color Overlay
    private View mKycOverlayView;
    private WindowManager.LayoutParams mKycParams;
    private final android.os.Handler mKycHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int mAutoColorIndex = 1;
    private final Runnable mAutoFlashRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSwitchKyc != null && mSwitchKyc.isChecked() && mCurrentColorMode == 0) {
                mAutoColorIndex = (mAutoColorIndex % (COLOR_MODE_VALS.length - 1)) + 1;
                applyKycOverlayColor(COLOR_MODE_VALS[mAutoColorIndex]);
                mKycHandler.postDelayed(this, 1200);
            }
        }
    };

    // Drag touch state
    private int mDragInitialX, mDragInitialY;
    private float mDragInitialTouchX, mDragInitialTouchY;

    // Bubble touch state
    private int mBubbleInitialX, mBubbleInitialY;
    private float mBubbleInitialTouchX, mBubbleInitialTouchY;
    private boolean mBubbleIsMoving = false;

    private static final String FLAG_DISABLE = "vcam_disable";
    private static final String FLAG_PAUSE = "vcam_pause";
    private static final String FLAG_KYC_FLASH = "vcam_kyc_flash";
    private static final String FLAG_RESET = "vcam_reset";
    private static final String FILE_ROTATION = "vcam_rotation";
    private static final String FILE_COLOR_VAL = "vcam_color_val";
    private static final String FILE_MIC_BOOST = "vcam_mic_boost";
    private static final String FILE_ZOOM = "vcam_zoom";
    private static final String FILE_PAN_X = "vcam_pan_x";
    private static final String FILE_PAN_Y = "vcam_pan_y";

    private Button mBtnMicBoost;
    private int mCurrentBoostIndex = 2; // Mặc định x3.0 (index 2)

    private static final String[] BOOST_LABELS = new String[] {
        "🎙️ MIC: x1.0 (GỐC)",
        "🎙️ MIC: x2.0 (VỪA)",
        "🎙️ MIC: x3.0 (TO RÕ)",
        "🎙️ MIC: x4.5 (CỰC TO)",
        "🎙️ MIC: x6.0 (TỐI ĐA)"
    };

    private static final String[] BOOST_VALS = new String[] {
        "1.0",
        "2.0",
        "3.0",
        "4.5",
        "6.0"
    };

    public interface VcamStateListener {
        void onVcamStateChanged(boolean isEnabled);
    }
    private static volatile VcamStateListener sListener;
    private static volatile FloatingControlService sInstance;

    public static void setVcamStateListener(VcamStateListener listener) {
        sListener = listener;
    }

    public static void syncVcamStateFromActivity(boolean isEnabled) {
        if (sInstance != null && sInstance.mSwitchVcam != null) {
            sInstance.mSwitchVcam.post(() -> {
                if (sInstance != null && sInstance.mSwitchVcam != null) {
                    sInstance.mSwitchVcam.setOnCheckedChangeListener(null);
                    sInstance.mSwitchVcam.setChecked(isEnabled);
                    sInstance.setupVcamSwitchListener();
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        String channelId = "vcam_floating_channel";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "VCAM Floating Controller",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Duy trì cửa sổ nổi VCAM Controller");
            nm.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("VCAM Controller đang chạy")
                    .setContentText("Cửa sổ nổi điều khiển camera ảo đang hiển thị")
                    .setOngoing(true)
                    .build();

            startForeground(1001, notification);
            Log.i(TAG, "FloatingControlService startForeground successful");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startAsForeground();
        ensureAllConfigFiles();

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mFloatingView = LayoutInflater.from(this).inflate(R.layout.floating_control_layout, null);

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = 40;
        mParams.y = 300;

        mLayoutBubble = mFloatingView.findViewById(R.id.layout_bubble);
        mLayoutExpanded = mFloatingView.findViewById(R.id.layout_expanded);

        View headerDrag = mFloatingView.findViewById(R.id.header_drag);
        View btnMinimize = mFloatingView.findViewById(R.id.btn_float_minimize);
        View btnClose = mFloatingView.findViewById(R.id.btn_float_close);

        mBtnPause = mFloatingView.findViewById(R.id.btn_float_pause);
        mTxtZoom = mFloatingView.findViewById(R.id.txt_float_zoom);
        Button btnZoomOut = mFloatingView.findViewById(R.id.btn_float_zoom_out);
        Button btnZoomIn = mFloatingView.findViewById(R.id.btn_float_zoom_in);

        Button btnUp = mFloatingView.findViewById(R.id.btn_float_up);
        Button btnDown = mFloatingView.findViewById(R.id.btn_float_down);
        Button btnLeft = mFloatingView.findViewById(R.id.btn_float_left);
        Button btnRight = mFloatingView.findViewById(R.id.btn_float_right);
        Button btnReset = mFloatingView.findViewById(R.id.btn_float_reset);
        mSwitchKyc = mFloatingView.findViewById(R.id.switch_float_kyc);
        mSwitchVcam = mFloatingView.findViewById(R.id.switch_float_vcam);

        // Load trạng thái ban đầu (đã khởi tạo mSwitchVcam)
        loadState();

        View txtDragHandle = mFloatingView.findViewById(R.id.txt_drag_handle);

        // 1 & 2. Gán OnTouchListener trực tiếp
        txtDragHandle.setOnTouchListener(this);
        mLayoutBubble.setOnTouchListener(this);

        // 3. Thu nhỏ & Đóng
        btnMinimize.setOnClickListener(v -> {
            mLayoutExpanded.setVisibility(View.GONE);
            mLayoutBubble.setVisibility(View.VISIBLE);
        });

        btnClose.setOnClickListener(v -> stopSelf());

        Button btnRewind = mFloatingView.findViewById(R.id.btn_float_rewind);
        if (btnRewind != null) {
            btnRewind.setOnClickListener(v -> {
                triggerRewind();
                Toast.makeText(this, "⏮ Đã tua lại từ đầu (00:00)", Toast.LENGTH_SHORT).show();
            });
        }

        mBtnRotate = mFloatingView.findViewById(R.id.btn_float_rotate);
        if (mBtnRotate != null) {
            mBtnRotate.setOnClickListener(v -> cycleRotation());
        }

        // 4. Pause / Play
        mBtnPause.setOnClickListener(v -> {
            mIsPaused = !mIsPaused;
            writeFlag(FLAG_PAUSE, mIsPaused);
            updatePauseUi();
            Toast.makeText(this, mIsPaused ? "⏸ Đã tạm dừng video & audio" : "▶ Đang phát video & audio", Toast.LENGTH_SHORT).show();
        });

        // 5. Zoom In / Zoom Out
        btnZoomOut.setOnClickListener(v -> setZoom(mCurrentZoom - 0.1f));
        btnZoomIn.setOnClickListener(v -> setZoom(mCurrentZoom + 0.1f));

        // 6. D-Pad Pan
        final float STEP = 0.05f;
        btnUp.setOnClickListener(v -> applyPan(0.0f, STEP));
        btnDown.setOnClickListener(v -> applyPan(0.0f, -STEP));
        btnLeft.setOnClickListener(v -> applyPan(-STEP, 0.0f));
        btnRight.setOnClickListener(v -> applyPan(STEP, 0.0f));

        // 7. Reset
        btnReset.setOnClickListener(v -> resetTransform());

        // 8. KYC Flash & Color Mode
        mBtnColorMode = mFloatingView.findViewById(R.id.btn_float_color_mode);
        if (mBtnColorMode != null) {
            mBtnColorMode.setOnClickListener(v -> cycleColorMode());
        }

        // 9. Mic Boost Button
        mBtnMicBoost = mFloatingView.findViewById(R.id.btn_float_mic_boost);
        if (mBtnMicBoost != null) {
            mBtnMicBoost.setOnClickListener(v -> cycleMicBoost());
        }

        mSwitchKyc.setOnCheckedChangeListener((buttonView, isChecked) -> {
            writeFlag(FLAG_KYC_FLASH, isChecked);
            writeFlag("vcam_color_sync", isChecked);
            if (isChecked) {
                writeColorVal(COLOR_MODE_VALS[mCurrentColorMode]);
                updateKycOverlayState(true);
                Toast.makeText(this, "🎨 Đã BẬT: " + COLOR_MODE_NAMES[mCurrentColorMode], Toast.LENGTH_SHORT).show();
            } else {
                updateKycOverlayState(false);
                Toast.makeText(this, "⚪ Đã TẮT Đổi màu", Toast.LENGTH_SHORT).show();
            }
        });

        mWindowManager.addView(mFloatingView, mParams);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        return START_STICKY;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int id = v.getId();
        if (id == R.id.txt_drag_handle) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDragInitialX = mParams.x;
                    mDragInitialY = mParams.y;
                    mDragInitialTouchX = event.getRawX();
                    mDragInitialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    mParams.x = mDragInitialX + (int) (event.getRawX() - mDragInitialTouchX);
                    mParams.y = mDragInitialY + (int) (event.getRawY() - mDragInitialTouchY);
                    mWindowManager.updateViewLayout(mFloatingView, mParams);
                    return true;
            }
        } else if (id == R.id.layout_bubble) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mBubbleInitialX = mParams.x;
                    mBubbleInitialY = mParams.y;
                    mBubbleInitialTouchX = event.getRawX();
                    mBubbleInitialTouchY = event.getRawY();
                    mBubbleIsMoving = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - mBubbleInitialTouchX);
                    int dy = (int) (event.getRawY() - mBubbleInitialTouchY);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        mBubbleIsMoving = true;
                        mParams.x = mBubbleInitialX + dx;
                        mParams.y = mBubbleInitialY + dy;
                        mWindowManager.updateViewLayout(mFloatingView, mParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mBubbleIsMoving) {
                        loadState();
                        mLayoutBubble.setVisibility(View.GONE);
                        mLayoutExpanded.setVisibility(View.VISIBLE);
                    }
                    return true;
            }
        }
        return false;
    }

    private void loadState() {
        mIsPaused = isFlagActive(FLAG_PAUSE);
        updatePauseUi();

        mCurrentRotation = readIntValue(FILE_ROTATION, 0);
        updateRotationUi();

        mCurrentZoom = readFloatValue(FILE_ZOOM, 1.0f);
        if (mCurrentZoom < 1.0f) mCurrentZoom = 1.0f;
        if (mCurrentZoom > 3.0f) mCurrentZoom = 3.0f;
        mTxtZoom.setText(String.format(Locale.US, "%.2fx", mCurrentZoom));

        mCurrentPanX = readFloatValue(FILE_PAN_X, 0.0f);
        mCurrentPanY = readFloatValue(FILE_PAN_Y, 0.0f);

        boolean kycActive = isFlagActive(FLAG_KYC_FLASH) || isFlagActive("vcam_color_sync");
        mSwitchKyc.setChecked(kycActive);

        String savedVal = readStringFile("/data/local/tmp/" + FILE_COLOR_VAL);
        if (savedVal.isEmpty()) savedVal = readStringFile("/sdcard/" + FILE_COLOR_VAL);
        for (int i = 0; i < COLOR_MODE_VALS.length; i++) {
            if (COLOR_MODE_VALS[i].equalsIgnoreCase(savedVal)) {
                mCurrentColorMode = i;
                break;
            }
        }
        updateColorModeUi();
        if (kycActive) {
            updateKycOverlayState(true);
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

        if (mSwitchVcam != null) {
            mSwitchVcam.setOnCheckedChangeListener(null);
            boolean isVcamOn = !isFlagActive(FLAG_DISABLE);
            mSwitchVcam.setChecked(isVcamOn);
            Log.i(TAG, "Floating HUD loadState: VCAM is " + (isVcamOn ? "ON" : "OFF"));
            setupVcamSwitchListener();
        }
    }

    private void cycleRotation() {
        mCurrentRotation = (mCurrentRotation + 90) % 360;
        writeStringFile("/data/local/tmp/" + FILE_ROTATION, String.valueOf(mCurrentRotation));
        writeStringFile("/sdcard/" + FILE_ROTATION, String.valueOf(mCurrentRotation));
        writeStringFile("/storage/emulated/0/" + FILE_ROTATION, String.valueOf(mCurrentRotation));
        updateRotationUi();
        Toast.makeText(this, "🔄 Góc xoay: " + mCurrentRotation + "°", Toast.LENGTH_SHORT).show();
    }

    private void updateRotationUi() {
        if (mBtnRotate != null) {
            mBtnRotate.setText("🔄");
        }
    }

    private void cycleColorMode() {
        mCurrentColorMode = (mCurrentColorMode + 1) % COLOR_MODE_NAMES.length;
        updateColorModeUi();
        writeColorVal(COLOR_MODE_VALS[mCurrentColorMode]);
        if (mSwitchKyc != null && mSwitchKyc.isChecked()) {
            updateKycOverlayState(true);
        } else if (mSwitchKyc != null) {
            mSwitchKyc.setChecked(true);
        } else {
            Toast.makeText(this, COLOR_MODE_NAMES[mCurrentColorMode], Toast.LENGTH_SHORT).show();
        }
    }

    private void updateColorModeUi() {
        if (mBtnColorMode != null) {
            mBtnColorMode.setText(COLOR_MODE_NAMES[mCurrentColorMode]);
        }
    }

    private void writeColorVal(String val) {
        writeStringFile("/data/local/tmp/" + FILE_COLOR_VAL, val);
        writeStringFile("/sdcard/" + FILE_COLOR_VAL, val);
        writeStringFile("/storage/emulated/0/" + FILE_COLOR_VAL, val);
    }

    private void cycleMicBoost() {
        mCurrentBoostIndex = (mCurrentBoostIndex + 1) % BOOST_LABELS.length;
        updateMicBoostUi();
        writeBoostVal(BOOST_VALS[mCurrentBoostIndex]);
        Toast.makeText(this, BOOST_LABELS[mCurrentBoostIndex], Toast.LENGTH_SHORT).show();
    }

    private void updateMicBoostUi() {
        if (mBtnMicBoost != null) {
            mBtnMicBoost.setText("🎙️");
            if (mCurrentBoostIndex == 0) {
                mBtnMicBoost.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF546E7A));
            } else {
                mBtnMicBoost.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00838F));
            }
        }
    }

    private void writeBoostVal(String val) {
        writeStringFile("/data/local/tmp/" + FILE_MIC_BOOST, val);
        writeStringFile("/sdcard/" + FILE_MIC_BOOST, val);
        writeStringFile("/storage/emulated/0/" + FILE_MIC_BOOST, val);
    }

    private void setupVcamSwitchListener() {
        if (mSwitchVcam == null) return;
        mSwitchVcam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Floating HUD Switch VCAM toggled to: " + isChecked);
            writeFlag(FLAG_DISABLE, !isChecked);
            if (sListener != null) {
                try {
                    sListener.onVcamStateChanged(isChecked);
                } catch (Throwable ignored) {}
            }
            if (isChecked) {
                Toast.makeText(this, "🟢 Đã BẬT Camera ảo!\n(Đổi camera trước/sau hoặc mở lại app để nhận video)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "🔴 Đã TẮT Camera ảo (Dùng Camera thật)!\n(Đổi camera trước/sau hoặc mở lại app để nhận camera thật)", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updatePauseUi() {
        if (mBtnPause != null) {
            if (mIsPaused) {
                mBtnPause.setText("▶");
                mBtnPause.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE65100));
            } else {
                mBtnPause.setText("⏸");
                mBtnPause.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF455A64));
            }
        }
    }

    private void setZoom(float zoom) {
        if (zoom < 1.0f) zoom = 1.0f;
        if (zoom > 3.0f) zoom = 3.0f;
        mCurrentZoom = zoom;

        mTxtZoom.setText(String.format(Locale.US, "%.2fx", mCurrentZoom));
        writeFloatValue("vcam_zoom", mCurrentZoom);
        writeFloatValue("vcam_zoom.cfg", mCurrentZoom);
        applyPan(0.0f, 0.0f);
    }

    private void applyPan(float dx, float dy) {
        mCurrentPanX += dx;
        mCurrentPanY += dy;

        float maxPan = (mCurrentZoom - 1.0f) / (2.0f * mCurrentZoom);
        if (mCurrentPanX > maxPan) mCurrentPanX = maxPan;
        if (mCurrentPanX < -maxPan) mCurrentPanX = -maxPan;
        if (mCurrentPanY > maxPan) mCurrentPanY = maxPan;
        if (mCurrentPanY < -maxPan) mCurrentPanY = -maxPan;

        writeFloatValue("vcam_pan_x", mCurrentPanX);
        writeFloatValue("vcam_pan_y", mCurrentPanY);
        String panCfg = mCurrentPanX + "," + mCurrentPanY;
        writeStringFile("/data/local/tmp/vcam_pan.cfg", panCfg);
        writeStringFile("/sdcard/vcam_pan.cfg", panCfg);
    }

    private void resetTransform() {
        mCurrentZoom = 1.0f;
        mCurrentPanX = 0.0f;
        mCurrentPanY = 0.0f;
        mCurrentRotation = 0;
        mIsPaused = false;

        writeFlag(FLAG_PAUSE, false);
        writeStringFile("/data/local/tmp/" + FILE_ROTATION, "0");
        writeStringFile("/sdcard/" + FILE_ROTATION, "0");
        writeStringFile("/storage/emulated/0/" + FILE_ROTATION, "0");
        writeFloatValue("vcam_zoom", 1.0f);
        writeFloatValue("vcam_zoom.cfg", 1.0f);
        writeFloatValue("vcam_pan_x", 0.0f);
        writeFloatValue("vcam_pan_y", 0.0f);
        writeStringFile("/data/local/tmp/vcam_pan.cfg", "0.0,0.0");
        writeStringFile("/sdcard/vcam_pan.cfg", "0.0,0.0");

        mTxtZoom.setText("1.00x");
        if (mSwitchKyc != null) mSwitchKyc.setChecked(false);
        updateKycOverlayState(false);
        writeFlag(FLAG_KYC_FLASH, false);
        writeFlag("vcam_color_sync", false);
        mCurrentColorMode = 0;
        writeColorVal("auto");
        updateColorModeUi();
        updatePauseUi();
        updateRotationUi();
        Toast.makeText(this, "Đã đặt lại gốc!", Toast.LENGTH_SHORT).show();
    }

    private void triggerRewind() {
        String ts = String.valueOf(System.currentTimeMillis());
        writeStringFile("/data/local/tmp/" + FLAG_RESET, ts);
        writeStringFile("/sdcard/" + FLAG_RESET, ts);
        writeStringFile("/storage/emulated/0/" + FLAG_RESET, ts);
        Toast.makeText(this, "⏮ Đã tua về 00:00 (Đồng bộ Video & Âm thanh)", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "triggerRewind: reset timestamp " + ts);
    }

    private boolean isFlagActive(String name) {
        File[] targets = new File[] {
            new File("/data/local/tmp/" + name),
            new File("/sdcard/" + name),
            new File("/storage/emulated/0/" + name),
            new File(Environment.getExternalStorageDirectory(), name)
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

    private void writeFlag(String name, boolean active) {
        Log.i(TAG, "writeFlag: " + name + " -> " + active);
        String val = active ? "1\n" : "0\n";
        writeStringFile("/data/local/tmp/" + name, val);
        writeStringFile("/sdcard/" + name, val);
        writeStringFile("/storage/emulated/0/" + name, val);
    }

    private void deleteFileSafely(File file) {
        if (file == null || !file.exists()) return;
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("0\n".getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
        try {
            file.delete();
        } catch (Throwable ignored) {}
    }

    private void writeFloatValue(String name, float val) {
        String s = String.valueOf(val);
        writeStringFile("/data/local/tmp/" + name, s);
        writeStringFile("/sdcard/" + name, s);
    }

    private static final java.util.concurrent.ExecutorService sIoExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void writeStringFile(String path, String val) {
        sIoExecutor.execute(() -> {
            boolean ok = false;
            try {
                File f = new File(path);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(val.getBytes("UTF-8"));
                fos.close();
                f.setReadable(true, false);
                f.setWritable(true, false);
                ok = true;
            } catch (Throwable ignored) {}

            if (!ok) {
                try {
                    String cmd = "echo -n '" + val + "' > " + path + " && chmod 666 " + path;
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    p.waitFor();
                } catch (Throwable ignored2) {}
            }
        });
    }

    private void ensureAllConfigFiles() {
        new Thread(() -> {
            try {
                String cmd = "for f in vcam_pause vcam_disable vcam_kyc_flash vcam_reset; do [ ! -s /data/local/tmp/$f ] && echo '0' > /data/local/tmp/$f; done; " +
                        "[ ! -s /data/local/tmp/vcam_rotation ] && echo '0' > /data/local/tmp/vcam_rotation; " +
                        "[ ! -s /data/local/tmp/vcam_zoom ] && echo '1.0' > /data/local/tmp/vcam_zoom; " +
                        "[ ! -s /data/local/tmp/vcam_pan_x ] && echo '0.0' > /data/local/tmp/vcam_pan_x; " +
                        "[ ! -s /data/local/tmp/vcam_pan_y ] && echo '0.0' > /data/local/tmp/vcam_pan_y; " +
                        "[ ! -s /data/local/tmp/vcam_mic_boost ] && echo '3.0' > /data/local/tmp/vcam_mic_boost; " +
                        "[ ! -s /data/local/tmp/vcam_color_val ] && echo 'auto' > /data/local/tmp/vcam_color_val; " +
                        "chmod 666 /data/local/tmp/vcam*";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    private void setupKycOverlay() {
        if (mKycOverlayView == null) {
            mKycOverlayView = new View(this);
            mKycParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
        }
    }

    private void updateKycOverlayState(boolean show) {
        setupKycOverlay();
        mKycHandler.removeCallbacks(mAutoFlashRunnable);

        if (!show) {
            if (mKycOverlayView != null && mKycOverlayView.getParent() != null) {
                try {
                    mWindowManager.removeView(mKycOverlayView);
                } catch (Throwable ignored) {}
            }
            return;
        }

        if (mCurrentColorMode == 0) {
            applyKycOverlayColor(COLOR_MODE_VALS[1]);
            mKycHandler.postDelayed(mAutoFlashRunnable, 1200);
        } else {
            applyKycOverlayColor(COLOR_MODE_VALS[mCurrentColorMode]);
        }

        if (mKycOverlayView.getParent() == null) {
            try {
                mWindowManager.addView(mKycOverlayView, mKycParams);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to add KYC overlay view: " + t.getMessage());
            }
        }
    }

    private void applyKycOverlayColor(String colorConfig) {
        if (mKycOverlayView == null) return;
        try {
            if ("auto".equalsIgnoreCase(colorConfig)) {
                colorConfig = "#FFFFFF,0.40";
            }
            String[] parts = colorConfig.split(",");
            String hex = parts[0].trim();
            float alpha = (parts.length > 1) ? Float.parseFloat(parts[1].trim()) : 0.40f;
            int baseColor = android.graphics.Color.parseColor(hex);
            int alphaInt = (int) (alpha * 255);
            int finalColor = (alphaInt << 24) | (baseColor & 0x00FFFFFF);
            mKycOverlayView.setBackgroundColor(finalColor);
        } catch (Throwable t) {
            mKycOverlayView.setBackgroundColor(0x66FFFFFF);
        }
    }

    private float readFloatValue(String name, float defVal) {
        String s1 = readStringFile("/data/local/tmp/" + name);
        if (!s1.isEmpty()) {
            try { return Float.parseFloat(s1); } catch (Throwable ignored) {}
        }
        String s2 = readStringFile("/sdcard/" + name);
        if (!s2.isEmpty()) {
            try { return Float.parseFloat(s2); } catch (Throwable ignored) {}
        }
        return defVal;
    }

    private int readIntValue(String name, int defVal) {
        String s1 = readStringFile("/data/local/tmp/" + name);
        if (!s1.isEmpty()) {
            try { return Integer.parseInt(s1); } catch (Throwable ignored) {}
        }
        String s2 = readStringFile("/sdcard/" + name);
        if (!s2.isEmpty()) {
            try { return Integer.parseInt(s2); } catch (Throwable ignored) {}
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        updateKycOverlayState(false);
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        if (mFloatingView != null && mWindowManager != null) {
            mWindowManager.removeView(mFloatingView);
        }
    }
}
