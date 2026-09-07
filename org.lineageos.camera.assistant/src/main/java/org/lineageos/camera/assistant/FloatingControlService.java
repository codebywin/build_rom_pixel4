package org.lineageos.camera.assistant;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

public class FloatingControlService extends Service {
    private WindowManager mWindowManager;
    private View mFloatingView;
    private WindowManager.LayoutParams mParams;

    private View mLayoutBubble;
    private View mLayoutExpanded;

    private TextView mTxtZoom;
    private Button mBtnPause;
    private Switch mSwitchKyc;

    private float mCurrentZoom = 1.0f;
    private float mCurrentPanX = 0.0f;
    private float mCurrentPanY = 0.0f;
    private boolean mIsPaused = false;

    private static final String FLAG_PAUSE = "vcam_pause";
    private static final String FLAG_KYC_FLASH = "vcam_kyc_flash";
    private static final String FILE_ZOOM = "vcam_zoom";
    private static final String FILE_PAN_X = "vcam_pan_x";
    private static final String FILE_PAN_Y = "vcam_pan_y";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

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

        // Load trạng thái ban đầu
        loadState();

        View txtDragHandle = mFloatingView.findViewById(R.id.txt_drag_handle);

        // 1. Kéo thả (Drag) qua thanh tiêu đề txtDragHandle
        txtDragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        mWindowManager.updateViewLayout(mFloatingView, mParams);
                        return true;
                }
                return false;
            }
        });

        // 2. Kéo thả qua Bong bóng (Bubble) + Chạm nhẹ để mở rộng
        mLayoutBubble.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                            mParams.x = initialX + dx;
                            mParams.y = initialY + dy;
                            mWindowManager.updateViewLayout(mFloatingView, mParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoving) {
                            mLayoutBubble.setVisibility(View.GONE);
                            mLayoutExpanded.setVisibility(View.VISIBLE);
                        }
                        return true;
                }
                return false;
            }
        });

        // 3. Thu nhỏ & Đóng
        btnMinimize.setOnClickListener(v -> {
            mLayoutExpanded.setVisibility(View.GONE);
            mLayoutBubble.setVisibility(View.VISIBLE);
        });

        btnClose.setOnClickListener(v -> stopSelf());

        // 4. Pause / Play
        mBtnPause.setOnClickListener(v -> {
            mIsPaused = !mIsPaused;
            writeFlag(FLAG_PAUSE, mIsPaused);
            updatePauseUi();
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

        // 8. KYC Flash
        mSwitchKyc.setOnCheckedChangeListener((buttonView, isChecked) -> {
            writeFlag(FLAG_KYC_FLASH, isChecked);
        });

        mWindowManager.addView(mFloatingView, mParams);
    }

    private void loadState() {
        mIsPaused = isFlagActive(FLAG_PAUSE);
        updatePauseUi();

        mCurrentZoom = readFloatValue(FILE_ZOOM, 1.0f);
        if (mCurrentZoom < 1.0f) mCurrentZoom = 1.0f;
        if (mCurrentZoom > 3.0f) mCurrentZoom = 3.0f;
        mTxtZoom.setText(String.format(Locale.US, "%.2fx", mCurrentZoom));

        mCurrentPanX = readFloatValue(FILE_PAN_X, 0.0f);
        mCurrentPanY = readFloatValue(FILE_PAN_Y, 0.0f);

        mSwitchKyc.setChecked(isFlagActive(FLAG_KYC_FLASH));
    }

    private void updatePauseUi() {
        if (mIsPaused) {
            mBtnPause.setText("▶ TIẾP TỤC (PLAY)");
            mBtnPause.setBackgroundColor(0xFF2E7D32);
        } else {
            mBtnPause.setText("⏸ TẠM DỪNG (FREEZE)");
            mBtnPause.setBackgroundColor(0xFF455A64);
        }
    }

    private void setZoom(float zoom) {
        if (zoom < 1.0f) zoom = 1.0f;
        if (zoom > 3.0f) zoom = 3.0f;
        mCurrentZoom = zoom;

        mTxtZoom.setText(String.format(Locale.US, "%.2fx", mCurrentZoom));
        writeFloatValue(FILE_ZOOM, mCurrentZoom);
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

        writeFloatValue(FILE_PAN_X, mCurrentPanX);
        writeFloatValue(FILE_PAN_Y, mCurrentPanY);
    }

    private void resetTransform() {
        mCurrentZoom = 1.0f;
        mCurrentPanX = 0.0f;
        mCurrentPanY = 0.0f;
        mIsPaused = false;

        writeFlag(FLAG_PAUSE, false);
        writeFloatValue(FILE_ZOOM, 1.0f);
        writeFloatValue(FILE_PAN_X, 0.0f);
        writeFloatValue(FILE_PAN_Y, 0.0f);

        mTxtZoom.setText("1.00x");
        updatePauseUi();
        Toast.makeText(this, "Đã đặt lại gốc!", Toast.LENGTH_SHORT).show();
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

    private void writeFloatValue(String name, float val) {
        String s = String.valueOf(val);
        writeStringFile("/data/local/tmp/" + name, s);
        writeStringFile("/sdcard/" + name, s);
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
        if (mFloatingView != null && mWindowManager != null) {
            mWindowManager.removeView(mFloatingView);
        }
    }
}
