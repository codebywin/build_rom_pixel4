package org.lineageos.camera.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.SeekBar;
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

    // Top action bar
    private Switch mSwitchVcam;
    private TextView mSwitchText;
    private LinearLayout mBtnTopPlay;
    private TextView mTxtTopPlayIcon;
    private TextView mTxtTopPlayLbl;
    private LinearLayout mBtnTopRotate;
    private TextView mTxtTopRotateLbl;
    private LinearLayout mBtnTopReset;
    private LinearLayout mBtnTopSwapUv;
    private TextView mTxtTopSwapUvIcon;
    private TextView mTxtTopSwapUvLbl;
    private boolean mIsSwapUv = false;

    // Tab panels
    private View mPanelNoise;
    private View mPanelZoom;
    private View mPanelPan;
    private View mPanelLight;
    private View mPanelBright;

    // Tab navigation buttons & labels
    private LinearLayout mTabNavNoise;
    private TextView mTabIconNoise;
    private TextView mTabTxtNoise;

    private LinearLayout mTabNavZoom;
    private TextView mTabIconZoom;
    private TextView mTabTxtZoom;

    private LinearLayout mTabNavPan;
    private TextView mTabIconPan;
    private TextView mTabTxtPan;

    private LinearLayout mTabNavLight;
    private TextView mTabIconLight;
    private TextView mTabTxtLight;

    private LinearLayout mTabNavBright;
    private TextView mTabIconBright;
    private TextView mTabTxtBright;

    private LinearLayout mTabNavFlash;
    private TextView mTabIconFlash;
    private TextView mTabTxtFlash;

    // Tab 6: KYC Flash Sync
    private View mPanelFlash;
    private Switch mSwitchFlashSync;
    private TextView mTxtFlashStatus;
    private TextView mBtnFlashModeDiff;
    private TextView mBtnFlashModeScreen;
    private SeekBar mSbFlashIntensity;
    private TextView mTxtFlashIntensityBadge;
    private FlashColorDetector mFlashDetector;
    private int mFlashDetectMode = FlashColorDetector.DETECT_FRAME_DIFF;
    private int mFlashIntensity = 35;

    // Live Feed SHM (0ms Latency Streamer)
    private Switch mSwitchLiveShm;
    private TextView mTxtShmStatus;

    // Header opacity button & seekbar
    private TextView mBtnFloatOpacity;
    private float mCurrentOpacity = 0.85f;
    private TextView mTxtTabOpacityBadge;
    private SeekBar mSbOpacity;

    // Tab 1: Noise views
    private TextView mTxtNoiseBadge;
    private SeekBar mSbNoise;
    private Button mBtnNoise0, mBtnNoise2, mBtnNoise5, mBtnNoise8, mBtnNoiseMax;

    // Tab 2: Zoom views
    private TextView mTxtZoomBadge;
    private TextView mTxtZoomStepperVal;
    private SeekBar mSbZoom;
    private Button mBtnZoom1, mBtnZoom15, mBtnZoom2, mBtnZoom3, mBtnZoom5;
    private Button mBtnZoomMinus, mBtnZoomPlus;

    // Tab 3: Pan views
    private TextView mTxtPanXBadge;
    private TextView mTxtPanYBadge;
    private SeekBar mSbPanX;
    private SeekBar mSbPanY;
    private Button mBtnPanUp, mBtnPanDown, mBtnPanLeft, mBtnPanRight, mBtnPanCenter;

    // Tab 4: Light views
    private TextView mTxtLightBadge;
    private SeekBar mSbLightInt;
    private TextView mBtnLightNw, mBtnLightN, mBtnLightNe;
    private TextView mBtnLightW, mBtnLightCenter, mBtnLightE;
    private TextView mBtnLightSw, mBtnLightS, mBtnLightSe;
    private TextView mTxtLightPreviewGlow;
    private TextView mTxtLightPreviewDesc;
    private TextView mBtnQuickLeft, mBtnQuickUp, mBtnQuickRight, mBtnQuickDown;
    private Button mBtnLightOff;
    private TextView[] mCompassButtons;

    // Tab 5: Brightness views
    private TextView mTxtBrightBadge;
    private SeekBar mSbRawBright;
    private Button mBtnBright50, mBtnBright65, mBtnBright80, mBtnBright100;
    private int mCurrentRawBright = 65;

    // Current states
    private int mCurrentTab = 0;
    private boolean mIsPaused = false;
    private int mCurrentRotation = 0;
    private int mCurrentNoise = 0;
    private float mCurrentZoom = 1.0f;
    private float mCurrentPanX = 0.0f;
    private float mCurrentPanY = 0.0f;
    private int mLightIntensity = 0;
    private float mLightPosX = 0.0f;
    private float mLightPosY = 0.0f;
    private String mLightDesc = "Giữa mặt";

    // Drag state
    private int mDragInitialX, mDragInitialY;
    private float mDragInitialTouchX, mDragInitialTouchY;

    // Bubble touch state
    private int mBubbleInitialX, mBubbleInitialY;
    private float mBubbleInitialTouchX, mBubbleInitialTouchY;
    private boolean mBubbleIsMoving = false;

    // Files
    public static final String SDCARD_DIR = "/sdcard/CameraAssistant/";
    private static final String FLAG_DISABLE = "vcam_disable";
    private static final String FLAG_PAUSE = "vcam_pause";
    private static final String FLAG_RESET = "vcam_reset";
    private static final String FILE_ROTATION = "vcam_rotation";
    private static final String FILE_ZOOM = "vcam_zoom";
    private static final String FILE_PAN_X = "vcam_pan_x";
    private static final String FILE_PAN_Y = "vcam_pan_y";
    private static final String FILE_NOISE = "vcam_noise";
    private static final String FILE_LIGHT = "vcam_light";
    private static final String FILE_SWAP_UV = "vcam_swap_uv";
    private static final String FILE_RAW_BRIGHT = "vcam_raw_bright";
    private static final String FILE_OPACITY = "vcam_floating_opacity";

    public interface VcamStateListener {
        void onVcamStateChanged(boolean isEnabled);
    }
    private static volatile VcamStateListener sListener;
    private static volatile FloatingControlService sInstance;
    public static FloatingControlService getInstance() {
        return sInstance;
    }

    public static void setVcamStateListener(VcamStateListener listener) {
        sListener = listener;
    }

    public static void syncVcamStateFromActivity(boolean isEnabled) {
        if (sInstance != null && sInstance.mSwitchVcam != null) {
            sInstance.mSwitchVcam.post(() -> {
                if (sInstance != null && sInstance.mSwitchVcam != null) {
                    sInstance.mSwitchVcam.setOnCheckedChangeListener(null);
                    sInstance.mSwitchVcam.setChecked(isEnabled);
                    if (sInstance.mSwitchText != null) {
                        sInstance.mSwitchText.setText(isEnabled ? "ON" : "OFF");
                    }
                    sInstance.setupVcamSwitchListener();
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static final String NOTIF_CHANNEL_ID   = "vcam_floating_channel_v3";

    public static final String ACTION_PLAY_PAUSE = "org.lineageos.camera.assistant.ACTION_PLAY_PAUSE";
    public static final String ACTION_ZOOM_IN     = "org.lineageos.camera.assistant.ACTION_ZOOM_IN";
    public static final String ACTION_ZOOM_OUT    = "org.lineageos.camera.assistant.ACTION_ZOOM_OUT";
    public static final String ACTION_PAN_UP      = "org.lineageos.camera.assistant.ACTION_PAN_UP";
    public static final String ACTION_PAN_DOWN    = "org.lineageos.camera.assistant.ACTION_PAN_DOWN";
    public static final String ACTION_PAN_LEFT    = "org.lineageos.camera.assistant.ACTION_PAN_LEFT";
    public static final String ACTION_PAN_RIGHT   = "org.lineageos.camera.assistant.ACTION_PAN_RIGHT";
    public static final String ACTION_PAN_CENTER  = "org.lineageos.camera.assistant.ACTION_PAN_CENTER";
    public static final String ACTION_ROTATE_90   = "org.lineageos.camera.assistant.ACTION_ROTATE_90";
    public static final String ACTION_TOGGLE_FLOAT_VIEW = "org.lineageos.camera.assistant.ACTION_TOGGLE_FLOAT_VIEW";
    public static final String ACTION_SHOW_FLOAT_VIEW   = "org.lineageos.camera.assistant.ACTION_SHOW_FLOAT_VIEW";
    public static final String ACTION_HIDE_FLOAT_VIEW   = "org.lineageos.camera.assistant.ACTION_HIDE_FLOAT_VIEW";
    public static final String ACTION_STOP_FLASH        = "org.lineageos.camera.assistant.ACTION_STOP_FLASH";

    public interface FloatingVisibilityListener {
        void onFloatingVisibilityChanged(boolean isHidden);
    }
    private static volatile FloatingVisibilityListener sVisibilityListener;

    public static void setFloatingVisibilityListener(FloatingVisibilityListener listener) {
        sVisibilityListener = listener;
    }

    private boolean mIsFloatingViewHidden = false;
    private Button mBtnHideFloatingForKyc;

    public boolean isFloatingViewHidden() {
        return mIsFloatingViewHidden;
    }

    public void hideFloatingView() {
        mIsFloatingViewHidden = true;
        if (mFloatingView != null) {
            mFloatingView.setVisibility(View.GONE);
            if (mWindowManager != null && mParams != null) {
                mParams.x = -2000;
                mParams.y = -2000;
                try {
                    mWindowManager.updateViewLayout(mFloatingView, mParams);
                } catch (Throwable ignored) {}
            }
        }
        updateNotification();
        if (sVisibilityListener != null) {
            try {
                sVisibilityListener.onFloatingVisibilityChanged(true);
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "⚡ Đã ẩn icon nổi. KYC Flash Sync (Màn hình) vẫn đang chạy ngầm!", Toast.LENGTH_SHORT).show();
    }

    public void showFloatingView() {
        mIsFloatingViewHidden = false;
        if (mFloatingView != null) {
            mFloatingView.setVisibility(View.VISIBLE);
            if (mLayoutExpanded != null) mLayoutExpanded.setVisibility(View.GONE);
            if (mLayoutBubble != null) mLayoutBubble.setVisibility(View.VISIBLE);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int bubbleWidth = (mLayoutBubble != null && mLayoutBubble.getWidth() > 0)
                    ? mLayoutBubble.getWidth()
                    : (int) (52 * getResources().getDisplayMetrics().density);
            mParams.x = screenWidth - (bubbleWidth / 2);
            mParams.y = Math.max(100, Math.min(mParams.y, screenHeight - 200));
            if (mWindowManager != null && mParams != null) {
                try {
                    mWindowManager.updateViewLayout(mFloatingView, mParams);
                } catch (Throwable ignored) {}
            }
        }
        updateNotification();
        if (sVisibilityListener != null) {
            try {
                sVisibilityListener.onFloatingVisibilityChanged(false);
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "✨ Đã mở lại icon nổi", Toast.LENGTH_SHORT).show();
    }

    private final BroadcastReceiver mNotifReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction() != null) {
                handleAction(intent.getAction());
            }
        }
    };

    public void handleAction(String action) {
        Log.i(TAG, "Notification action received: " + action);
        switch (action) {
            case ACTION_PLAY_PAUSE:
                togglePlayPause();
                break;

            case ACTION_TOGGLE_FLOAT_VIEW:
                if (mIsFloatingViewHidden) {
                    showFloatingView();
                } else {
                    hideFloatingView();
                }
                break;

            case ACTION_SHOW_FLOAT_VIEW:
                showFloatingView();
                break;

            case ACTION_HIDE_FLOAT_VIEW:
                hideFloatingView();
                break;

            case ACTION_STOP_FLASH:
                setFlashSyncEnabled(false);
                Toast.makeText(this, "Đã tắt KYC Flash Sync", Toast.LENGTH_SHORT).show();
                if (mIsFloatingViewHidden) {
                    stopSelf();
                }
                break;

            case ACTION_ZOOM_IN:
                setZoom(Math.round((mCurrentZoom + 0.1f) * 10.0f) / 10.0f);
                Toast.makeText(this, String.format(Locale.US, "🔍 Zoom: %.2f×", mCurrentZoom), Toast.LENGTH_SHORT).show();
                break;

            case ACTION_ZOOM_OUT:
                setZoom(Math.round((mCurrentZoom - 0.1f) * 10.0f) / 10.0f);
                Toast.makeText(this, String.format(Locale.US, "🔍 Zoom: %.2f×", mCurrentZoom), Toast.LENGTH_SHORT).show();
                break;

            case ACTION_PAN_UP:
                applyPan(0.0f, 0.05f);
                Toast.makeText(this, "▲ Dịch lên", Toast.LENGTH_SHORT).show();
                break;

            case ACTION_PAN_DOWN:
                applyPan(0.0f, -0.05f);
                Toast.makeText(this, "▼ Dịch xuống", Toast.LENGTH_SHORT).show();
                break;

            case ACTION_PAN_LEFT:
                applyPan(-0.05f, 0.0f);
                Toast.makeText(this, "◀ Dịch trái", Toast.LENGTH_SHORT).show();
                break;

            case ACTION_PAN_RIGHT:
                applyPan(0.05f, 0.0f);
                Toast.makeText(this, "▶ Dịch phải", Toast.LENGTH_SHORT).show();
                break;

            case ACTION_PAN_CENTER:
                resetPan();
                break;

            case ACTION_ROTATE_90:
                cycleRotation();
                break;
        }
    }

    private PendingIntent getNotifPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, requestCode, intent, flags);
    }

    private Notification buildControlNotification(String channelId) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_vcam_control);

        String playState = mIsPaused ? "⏸ ĐÃ DỪNG" : "▶ ĐANG PHÁT";
        String status;
        if (mIsFloatingViewHidden) {
            status = String.format(Locale.US, "%s | 🔍 %.2f× | %d° (Ẩn icon)",
                    playState, mCurrentZoom, mCurrentRotation);
        } else {
            status = String.format(Locale.US, "%s | 🔍 %.2f× | %d°",
                    playState, mCurrentZoom, mCurrentRotation);
        }
        views.setTextViewText(R.id.notif_txt_status, status);

        // Nút Play / Pause
        if (mIsPaused) {
            views.setTextViewText(R.id.notif_btn_play_pause, "▶ Tiếp tục");
            views.setTextColor(R.id.notif_btn_play_pause, 0xFFF59E0B);
            views.setInt(R.id.notif_btn_play_pause, "setBackgroundResource", R.drawable.bg_notif_btn_warning);
        } else {
            views.setTextViewText(R.id.notif_btn_play_pause, "⏸ Tạm dừng");
            views.setTextColor(R.id.notif_btn_play_pause, 0xFF38BDF8);
            views.setInt(R.id.notif_btn_play_pause, "setBackgroundResource", R.drawable.bg_notif_btn);
        }
        views.setOnClickPendingIntent(R.id.notif_btn_play_pause, getNotifPendingIntent(ACTION_PLAY_PAUSE, 100));

        // Nút Rotate
        views.setTextViewText(R.id.notif_btn_rotate, mCurrentRotation == 0 ? "🔄 90°" : String.format(Locale.US, "🔄 %d°", mCurrentRotation));
        views.setOnClickPendingIntent(R.id.notif_btn_rotate,   getNotifPendingIntent(ACTION_ROTATE_90, 103));

        // Nút Zoom In / Out
        views.setOnClickPendingIntent(R.id.notif_btn_zoom_out, getNotifPendingIntent(ACTION_ZOOM_OUT, 101));
        views.setOnClickPendingIntent(R.id.notif_btn_zoom_in,  getNotifPendingIntent(ACTION_ZOOM_IN, 102));

        // Nút Pan & Center
        views.setOnClickPendingIntent(R.id.notif_btn_center,   getNotifPendingIntent(ACTION_PAN_CENTER, 104));
        views.setOnClickPendingIntent(R.id.notif_btn_pan_left,  getNotifPendingIntent(ACTION_PAN_LEFT, 105));
        views.setOnClickPendingIntent(R.id.notif_btn_pan_up,    getNotifPendingIntent(ACTION_PAN_UP, 106));
        views.setOnClickPendingIntent(R.id.notif_btn_pan_down,  getNotifPendingIntent(ACTION_PAN_DOWN, 107));
        views.setOnClickPendingIntent(R.id.notif_btn_pan_right, getNotifPendingIntent(ACTION_PAN_RIGHT, 108));

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent toggleIntent = new Intent(ACTION_TOGGLE_FLOAT_VIEW);
        toggleIntent.setPackage(getPackageName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent togglePi = PendingIntent.getBroadcast(this, 199, toggleIntent, piFlags);

        builder.setSmallIcon(R.drawable.ic_launcher)
               .setContentIntent(togglePi)
               .setCustomContentView(views)
               .setCustomBigContentView(views)
               .setOnlyAlertOnce(true)
               .setOngoing(true);

        return builder.build();
    }

    public void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(1001, buildControlNotification(NOTIF_CHANNEL_ID));
            } catch (Exception e) {
                Log.w(TAG, "updateNotification error: " + e.getMessage());
            }
        }
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "VCAM Floating Controller",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Bảng điều khiển VCAM trên thanh thông báo");
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }

        Notification notification = buildControlNotification(NOTIF_CHANNEL_ID);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1001, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE_FLOAT_VIEW.equals(action)) {
                if (mIsFloatingViewHidden) {
                    showFloatingView();
                } else {
                    hideFloatingView();
                }
                return START_STICKY;
            } else if (ACTION_SHOW_FLOAT_VIEW.equals(action)) {
                showFloatingView();
                return START_STICKY;
            } else if (ACTION_HIDE_FLOAT_VIEW.equals(action)) {
                hideFloatingView();
                return START_STICKY;
            } else if (ACTION_STOP_FLASH.equals(action)) {
                setFlashSyncEnabled(false);
                Toast.makeText(this, "Đã tắt KYC Flash Sync", Toast.LENGTH_SHORT).show();
                if (mIsFloatingViewHidden) {
                    stopSelf();
                }
                return START_STICKY;
            }

            if (intent.hasExtra("flash_result_code") && intent.hasExtra("flash_data")) {
                int resCode = intent.getIntExtra("flash_result_code", 0);
                Intent data = intent.getParcelableExtra("flash_data");
                if (resCode == android.app.Activity.RESULT_OK && data != null) {
                    startFlashScreenCapMode(resCode, data);
                    if (intent.getBooleanExtra("hide_float_icon", false)) {
                        hideFloatingView();
                    }
                }
            } else if (intent.getBooleanExtra("hide_float_icon", false)) {
                hideFloatingView();
            }

            if (action != null) {
                handleAction(action);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LicenseManager.init(this);
        LicenseManager.LicenseInfo lic = LicenseManager.checkLicense();
        if (!lic.isValid) {
            Toast.makeText(this, "Chưa kích hoạt bản quyền! Vui lòng kích hoạt trong ứng dụng.", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        sInstance = this;
        startAsForeground();

        IntentFilter notifFilter = new IntentFilter();
        notifFilter.addAction(ACTION_PLAY_PAUSE);
        notifFilter.addAction(ACTION_TOGGLE_FLOAT_VIEW);
        notifFilter.addAction(ACTION_SHOW_FLOAT_VIEW);
        notifFilter.addAction(ACTION_HIDE_FLOAT_VIEW);
        notifFilter.addAction(ACTION_STOP_FLASH);
        notifFilter.addAction(ACTION_ZOOM_IN);
        notifFilter.addAction(ACTION_ZOOM_OUT);
        notifFilter.addAction(ACTION_PAN_UP);
        notifFilter.addAction(ACTION_PAN_DOWN);
        notifFilter.addAction(ACTION_PAN_LEFT);
        notifFilter.addAction(ACTION_PAN_RIGHT);
        notifFilter.addAction(ACTION_PAN_CENTER);
        notifFilter.addAction(ACTION_ROTATE_90);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mNotifReceiver, notifFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mNotifReceiver, notifFilter);
        }

        ensureAllConfigFiles();

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mFloatingView = LayoutInflater.from(this).inflate(R.layout.floating_control_layout, null);

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = 40;
        mParams.y = 260;
        mParams.alpha = mCurrentOpacity;

        initViews();
        setupListeners();
        loadState();
        ensureFlashDetector();

        mWindowManager.addView(mFloatingView, mParams);
    }

    private void initViews() {
        mLayoutBubble = mFloatingView.findViewById(R.id.layout_bubble);
        mLayoutExpanded = mFloatingView.findViewById(R.id.layout_expanded);

        // Top bar
        mSwitchText = mFloatingView.findViewById(R.id.switch_text);
        mSwitchVcam = mFloatingView.findViewById(R.id.switch_float_vcam);
        mBtnTopPlay = mFloatingView.findViewById(R.id.btn_top_play);
        mTxtTopPlayIcon = mFloatingView.findViewById(R.id.txt_top_play_icon);
        mTxtTopPlayLbl = mFloatingView.findViewById(R.id.txt_top_play_lbl);
        mBtnTopRotate = mFloatingView.findViewById(R.id.btn_top_rotate);
        mTxtTopRotateLbl = mFloatingView.findViewById(R.id.txt_top_rotate_lbl);
        mBtnTopReset = mFloatingView.findViewById(R.id.btn_top_reset);
        mBtnTopSwapUv = mFloatingView.findViewById(R.id.btn_top_swap_uv);
        mTxtTopSwapUvIcon = mFloatingView.findViewById(R.id.txt_top_swap_uv_icon);
        mTxtTopSwapUvLbl = mFloatingView.findViewById(R.id.txt_top_swap_uv_lbl);

        // Tab Panels
        mPanelNoise = mFloatingView.findViewById(R.id.panel_noise);
        mPanelZoom = mFloatingView.findViewById(R.id.panel_zoom);
        mPanelPan = mFloatingView.findViewById(R.id.panel_pan);
        mPanelLight = mFloatingView.findViewById(R.id.panel_light);
        mPanelBright = mFloatingView.findViewById(R.id.panel_bright);

        // Bottom Tab Navs
        mTabNavNoise = mFloatingView.findViewById(R.id.tab_nav_noise);
        mTabIconNoise = mFloatingView.findViewById(R.id.tab_icon_noise);
        mTabTxtNoise = mFloatingView.findViewById(R.id.tab_txt_noise);

        mTabNavZoom = mFloatingView.findViewById(R.id.tab_nav_zoom);
        mTabIconZoom = mFloatingView.findViewById(R.id.tab_icon_zoom);
        mTabTxtZoom = mFloatingView.findViewById(R.id.tab_txt_zoom);

        mTabNavPan = mFloatingView.findViewById(R.id.tab_nav_pan);
        mTabIconPan = mFloatingView.findViewById(R.id.tab_icon_pan);
        mTabTxtPan = mFloatingView.findViewById(R.id.tab_txt_pan);

        mTabNavLight = mFloatingView.findViewById(R.id.tab_nav_light);
        mTabIconLight = mFloatingView.findViewById(R.id.tab_icon_light);
        mTabTxtLight = mFloatingView.findViewById(R.id.tab_txt_light);

        mTabNavBright = mFloatingView.findViewById(R.id.tab_nav_bright);
        mTabIconBright = mFloatingView.findViewById(R.id.tab_icon_bright);
        mTabTxtBright = mFloatingView.findViewById(R.id.tab_txt_bright);

        // Header opacity
        mBtnFloatOpacity = mFloatingView.findViewById(R.id.btn_float_opacity);

        // Tab 1: Noise views
        mTxtNoiseBadge = mFloatingView.findViewById(R.id.txt_noise_badge);
        mSbNoise = mFloatingView.findViewById(R.id.sb_noise);
        mBtnNoise0 = mFloatingView.findViewById(R.id.btn_noise_0);
        mBtnNoise2 = mFloatingView.findViewById(R.id.btn_noise_2);
        mBtnNoise5 = mFloatingView.findViewById(R.id.btn_noise_5);
        mBtnNoise8 = mFloatingView.findViewById(R.id.btn_noise_8);
        mBtnNoiseMax = mFloatingView.findViewById(R.id.btn_noise_max);

        // Tab 2: Zoom views
        mTxtZoomBadge = mFloatingView.findViewById(R.id.txt_zoom_badge);
        mTxtZoomStepperVal = mFloatingView.findViewById(R.id.txt_zoom_stepper_val);
        mSbZoom = mFloatingView.findViewById(R.id.sb_zoom);
        mBtnZoom1 = mFloatingView.findViewById(R.id.btn_zoom_1);
        mBtnZoom15 = mFloatingView.findViewById(R.id.btn_zoom_15);
        mBtnZoom2 = mFloatingView.findViewById(R.id.btn_zoom_2);
        mBtnZoom3 = mFloatingView.findViewById(R.id.btn_zoom_3);
        mBtnZoom5 = mFloatingView.findViewById(R.id.btn_zoom_5);
        mBtnZoomMinus = mFloatingView.findViewById(R.id.btn_zoom_minus);
        mBtnZoomPlus = mFloatingView.findViewById(R.id.btn_zoom_plus);

        // Tab 3: Pan views
        mTxtPanXBadge = mFloatingView.findViewById(R.id.txt_pan_x_badge);
        mTxtPanYBadge = mFloatingView.findViewById(R.id.txt_pan_y_badge);
        mSbPanX = mFloatingView.findViewById(R.id.sb_pan_x);
        mSbPanY = mFloatingView.findViewById(R.id.sb_pan_y);
        mBtnPanUp = mFloatingView.findViewById(R.id.btn_pan_up);
        mBtnPanDown = mFloatingView.findViewById(R.id.btn_pan_down);
        mBtnPanLeft = mFloatingView.findViewById(R.id.btn_pan_left);
        mBtnPanRight = mFloatingView.findViewById(R.id.btn_pan_right);
        mBtnPanCenter = mFloatingView.findViewById(R.id.btn_pan_center);

        // Tab 4: Light views
        mTxtLightBadge = mFloatingView.findViewById(R.id.txt_light_badge);
        mSbLightInt = mFloatingView.findViewById(R.id.sb_light_int);
        mBtnLightNw = mFloatingView.findViewById(R.id.btn_light_nw);
        mBtnLightN = mFloatingView.findViewById(R.id.btn_light_n);
        mBtnLightNe = mFloatingView.findViewById(R.id.btn_light_ne);
        mBtnLightW = mFloatingView.findViewById(R.id.btn_light_w);
        mBtnLightCenter = mFloatingView.findViewById(R.id.btn_light_center);
        mBtnLightE = mFloatingView.findViewById(R.id.btn_light_e);
        mBtnLightSw = mFloatingView.findViewById(R.id.btn_light_sw);
        mBtnLightS = mFloatingView.findViewById(R.id.btn_light_s);
        mBtnLightSe = mFloatingView.findViewById(R.id.btn_light_se);
        mTxtLightPreviewGlow = mFloatingView.findViewById(R.id.txt_light_preview_glow);
        mTxtLightPreviewDesc = mFloatingView.findViewById(R.id.txt_light_preview_desc);
        mBtnQuickLeft = mFloatingView.findViewById(R.id.btn_quick_left);
        mBtnQuickUp = mFloatingView.findViewById(R.id.btn_quick_up);
        mBtnQuickRight = mFloatingView.findViewById(R.id.btn_quick_right);
        mBtnQuickDown = mFloatingView.findViewById(R.id.btn_quick_down);
        mBtnLightOff = mFloatingView.findViewById(R.id.btn_light_off);

        mCompassButtons = new TextView[] {
            mBtnLightNw, mBtnLightN, mBtnLightNe,
            mBtnLightW, mBtnLightCenter, mBtnLightE,
            mBtnLightSw, mBtnLightS, mBtnLightSe
        };

        // Tab 5: Brightness views
        mTxtBrightBadge = mFloatingView.findViewById(R.id.txt_bright_badge);
        mSbRawBright = mFloatingView.findViewById(R.id.sb_raw_bright);
        mBtnBright50 = mFloatingView.findViewById(R.id.btn_bright_50);
        mBtnBright65 = mFloatingView.findViewById(R.id.btn_bright_65);
        mBtnBright80 = mFloatingView.findViewById(R.id.btn_bright_80);
        mBtnBright100 = mFloatingView.findViewById(R.id.btn_bright_100);

        // Tab 5: Opacity presets (80%, 85%, 90%, 95%, 100%)
        mTxtTabOpacityBadge = mFloatingView.findViewById(R.id.txt_tab_opacity_badge);
        mSbOpacity = mFloatingView.findViewById(R.id.sb_opacity);

        // Tab nav 6: KYC Flash
        mTabNavFlash = mFloatingView.findViewById(R.id.tab_nav_flash);
        mTabIconFlash = mFloatingView.findViewById(R.id.tab_icon_flash);
        mTabTxtFlash  = mFloatingView.findViewById(R.id.tab_txt_flash);

        // Tab 6: Flash panel views
        mPanelFlash             = mFloatingView.findViewById(R.id.panel_flash);
        mSwitchFlashSync        = mFloatingView.findViewById(R.id.switch_flash_sync);
        mTxtFlashStatus         = mFloatingView.findViewById(R.id.txt_flash_status);
        mBtnFlashModeDiff       = mFloatingView.findViewById(R.id.btn_flash_mode_diff);
        mBtnFlashModeScreen     = mFloatingView.findViewById(R.id.btn_flash_mode_screen);
        mSbFlashIntensity       = mFloatingView.findViewById(R.id.sb_flash_intensity);
        mTxtFlashIntensityBadge = mFloatingView.findViewById(R.id.txt_flash_intensity_badge);
        mSwitchLiveShm          = mFloatingView.findViewById(R.id.switch_live_shm);
        mTxtShmStatus           = mFloatingView.findViewById(R.id.txt_shm_status);
        mBtnHideFloatingForKyc  = mFloatingView.findViewById(R.id.btn_hide_floating_for_kyc);
    }

    private void setupListeners() {
        // Drag header title & Bubble
        View layoutDragTitle = mFloatingView.findViewById(R.id.layout_drag_title);
        if (layoutDragTitle != null) layoutDragTitle.setOnTouchListener(this);
        View txtDragHandle = mFloatingView.findViewById(R.id.txt_drag_handle);
        if (txtDragHandle != null) txtDragHandle.setOnTouchListener(this);
        mLayoutBubble.setOnTouchListener(this);

        // Header actions
        View btnMinimize = mFloatingView.findViewById(R.id.btn_float_minimize);
        View btnClose = mFloatingView.findViewById(R.id.btn_float_close);
        btnMinimize.setOnClickListener(v -> {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int bubbleWidth = (mLayoutBubble != null && mLayoutBubble.getWidth() > 0)
                    ? mLayoutBubble.getWidth()
                    : (int) (52 * getResources().getDisplayMetrics().density);

            // Ghim mép phải: nửa ẩn ngoài mép (50%), nửa nhìn thấy
            mParams.x = screenWidth - (bubbleWidth / 2);
            mParams.y = Math.max(100, Math.min(mParams.y, screenHeight - 200));
            mWindowManager.updateViewLayout(mFloatingView, mParams);

            mLayoutExpanded.setVisibility(View.GONE);
            mLayoutBubble.setVisibility(View.VISIBLE);
        });
        btnClose.setOnClickListener(v -> hideFloatingView());
        if (mBtnHideFloatingForKyc != null) {
            mBtnHideFloatingForKyc.setOnClickListener(v -> hideFloatingView());
        }

        // Top Action Bar
        View.OnClickListener playListener = v -> togglePlayPause();
        mBtnTopPlay.setOnClickListener(playListener);
        mTxtTopPlayIcon.setOnClickListener(playListener);
        mTxtTopPlayLbl.setOnClickListener(playListener);

        View.OnClickListener rotateListener = v -> cycleRotation();
        mBtnTopRotate.setOnClickListener(rotateListener);
        mTxtTopRotateLbl.setOnClickListener(rotateListener);

        mBtnTopReset.setOnClickListener(v -> resetAll());
        View.OnClickListener swapUvListener = v -> toggleSwapUv();
        if (mBtnTopSwapUv != null) mBtnTopSwapUv.setOnClickListener(swapUvListener);
        if (mTxtTopSwapUvIcon != null) mTxtTopSwapUvIcon.setOnClickListener(swapUvListener);
        if (mTxtTopSwapUvLbl != null) mTxtTopSwapUvLbl.setOnClickListener(swapUvListener);

        // Tab Navigation
        View.OnClickListener tab0Listener = v -> selectTab(0);
        mTabNavNoise.setOnClickListener(tab0Listener);
        mTabIconNoise.setOnClickListener(tab0Listener);
        mTabTxtNoise.setOnClickListener(tab0Listener);

        View.OnClickListener tab1Listener = v -> selectTab(1);
        mTabNavZoom.setOnClickListener(tab1Listener);
        mTabIconZoom.setOnClickListener(tab1Listener);
        mTabTxtZoom.setOnClickListener(tab1Listener);

        View.OnClickListener tab2Listener = v -> selectTab(2);
        mTabNavPan.setOnClickListener(tab2Listener);
        mTabIconPan.setOnClickListener(tab2Listener);
        mTabTxtPan.setOnClickListener(tab2Listener);

        View.OnClickListener tab3Listener = v -> selectTab(3);
        mTabNavLight.setOnClickListener(tab3Listener);
        mTabIconLight.setOnClickListener(tab3Listener);
        mTabTxtLight.setOnClickListener(tab3Listener);

        View.OnClickListener tab4Listener = v -> selectTab(4);
        if (mTabNavBright != null) mTabNavBright.setOnClickListener(tab4Listener);
        if (mTabIconBright != null) mTabIconBright.setOnClickListener(tab4Listener);
        if (mTabTxtBright != null) mTabTxtBright.setOnClickListener(tab4Listener);

        View.OnClickListener tab5Listener = v -> selectTab(5);
        if (mTabNavFlash != null) mTabNavFlash.setOnClickListener(tab5Listener);
        if (mTabIconFlash != null) mTabIconFlash.setOnClickListener(tab5Listener);
        if (mTabTxtFlash  != null) mTabTxtFlash.setOnClickListener(tab5Listener);

        if (mBtnFloatOpacity != null) {
            mBtnFloatOpacity.setOnClickListener(v -> cycleOpacity());
        }

        // Opacity SeekBar (0–100%)
        if (mSbOpacity != null) {
            mSbOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser) {
                        float opacity = progress / 100.0f;
                        applyOpacity(opacity);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    float opacity = sb.getProgress() / 100.0f;
                    writeConfig(FILE_OPACITY, String.format(Locale.US, "%.2f", opacity));
                }
            });
        }

        // Tab 6: KYC Flash Sync listeners
        if (mSwitchFlashSync != null) {
            mSwitchFlashSync.setOnCheckedChangeListener((btn, checked) -> {
                setFlashSyncEnabled(checked);
            });
        }
        if (mSwitchLiveShm != null) {
            mSwitchLiveShm.setOnCheckedChangeListener((btn, checked) -> {
                setLiveShmEnabled(checked);
            });
        }
        if (mBtnFlashModeDiff != null) {
            mBtnFlashModeDiff.setOnClickListener(v -> setFlashDetectMode(FlashColorDetector.DETECT_FRAME_DIFF));
        }
        if (mBtnFlashModeScreen != null) {
            mBtnFlashModeScreen.setOnClickListener(v -> {
                setFlashDetectMode(FlashColorDetector.DETECT_SCREEN_CAP);
                // Gửi Intent cho MainActivity để request MediaProjection permission
                try {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("action", "request_media_projection");
                    startActivity(intent);
                    android.widget.Toast.makeText(this,
                        "📱 Mở MainActivity để cấp quyền Screen Capture...", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    android.widget.Toast.makeText(this,
                        "Lỗi mở MainActivity: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (mSbFlashIntensity != null) {
            mSbFlashIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    mFlashIntensity = progress;
                    if (mTxtFlashIntensityBadge != null)
                        mTxtFlashIntensityBadge.setText(String.valueOf(progress));
                    if (mFlashDetector != null) mFlashDetector.setIntensity(progress);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }


        // Tab 1: Noise
        mSbNoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mCurrentNoise = progress;
                mTxtNoiseBadge.setText(String.valueOf(progress));
                updateNoisePresetUi();
                if (fromUser) {
                    writeNoiseVal(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writeNoiseVal(mCurrentNoise);
            }
        });
        mBtnNoise0.setOnClickListener(v -> { mSbNoise.setProgress(0); writeNoiseVal(0); });
        mBtnNoise2.setOnClickListener(v -> { mSbNoise.setProgress(2); writeNoiseVal(2); });
        mBtnNoise5.setOnClickListener(v -> { mSbNoise.setProgress(5); writeNoiseVal(5); });
        mBtnNoise8.setOnClickListener(v -> { mSbNoise.setProgress(8); writeNoiseVal(8); });
        mBtnNoiseMax.setOnClickListener(v -> { mSbNoise.setProgress(10); writeNoiseVal(10); });

        // Tab 2: Zoom
        mSbZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mCurrentZoom = 1.0f + (progress / 100.0f);
                updateZoomUi();
                if (fromUser) {
                    writeZoomVal(mCurrentZoom);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writeZoomVal(mCurrentZoom);
            }
        });
        mBtnZoom1.setOnClickListener(v -> setZoom(1.0f));
        mBtnZoom15.setOnClickListener(v -> setZoom(1.5f));
        mBtnZoom2.setOnClickListener(v -> setZoom(2.0f));
        mBtnZoom3.setOnClickListener(v -> setZoom(3.0f));
        mBtnZoom5.setOnClickListener(v -> setZoom(5.0f));
        mBtnZoomMinus.setOnClickListener(v -> setZoom(mCurrentZoom - 0.05f));
        mBtnZoomPlus.setOnClickListener(v -> setZoom(mCurrentZoom + 0.05f));

        // Tab 3: Pan
        mSbPanX.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mCurrentPanX = (progress - 100) / 100.0f;
                updatePanUi();
                if (fromUser) {
                    writePanVal();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writePanVal();
            }
        });
        mSbPanY.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mCurrentPanY = (progress - 100) / 100.0f;
                updatePanUi();
                if (fromUser) {
                    writePanVal();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writePanVal();
            }
        });

        final float STEP = 0.05f;
        mBtnPanUp.setOnClickListener(v -> applyPan(0.0f, STEP));
        mBtnPanDown.setOnClickListener(v -> applyPan(0.0f, -STEP));
        mBtnPanLeft.setOnClickListener(v -> applyPan(-STEP, 0.0f));
        mBtnPanRight.setOnClickListener(v -> applyPan(STEP, 0.0f));
        mBtnPanCenter.setOnClickListener(v -> resetPan());

        // Tab 4: Light & Shadow
        mSbLightInt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mLightIntensity = progress - 100;
                updateLightUi();
                if (fromUser) {
                    writeLightVal();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writeLightVal();
            }
        });

        // Compass 8 directions + Center
        mBtnLightNw.setOnClickListener(v -> setLightDirection(-0.7f, -0.7f, "Trái trên", 0));
        mBtnLightN.setOnClickListener(v -> setLightDirection(0.0f, -1.0f, "Đỉnh đầu", 1));
        mBtnLightNe.setOnClickListener(v -> setLightDirection(0.7f, -0.7f, "Phải trên", 2));
        mBtnLightW.setOnClickListener(v -> setLightDirection(-1.0f, 0.0f, "Bên trái", 3));
        mBtnLightCenter.setOnClickListener(v -> setLightDirection(0.0f, 0.0f, "Giữa mặt", 4));
        mBtnLightE.setOnClickListener(v -> setLightDirection(1.0f, 0.0f, "Bên phải", 5));
        mBtnLightSw.setOnClickListener(v -> setLightDirection(-0.7f, 0.7f, "Trái dưới", 6));
        mBtnLightS.setOnClickListener(v -> setLightDirection(0.0f, 1.0f, "Bên dưới", 7));
        mBtnLightSe.setOnClickListener(v -> setLightDirection(0.7f, 0.7f, "Phải dưới", 8));

        // Quick directions
        mBtnQuickLeft.setOnClickListener(v -> setLightDirection(-1.0f, 0.0f, "Bên trái", 3));
        mBtnQuickUp.setOnClickListener(v -> setLightDirection(0.0f, -1.0f, "Đỉnh đầu", 1));
        mBtnQuickRight.setOnClickListener(v -> setLightDirection(1.0f, 0.0f, "Bên phải", 5));
        mBtnQuickDown.setOnClickListener(v -> setLightDirection(0.0f, 1.0f, "Bên dưới", 7));

        mBtnLightOff.setOnClickListener(v -> {
            mLightIntensity = 0;
            mSbLightInt.setProgress(100);
            updateLightUi();
            writeLightVal();
            Toast.makeText(this, "💡 Đã tắt ánh sáng ảo", Toast.LENGTH_SHORT).show();
        });

        // Tab 5: Brightness
        if (mSbRawBright != null) {
            mSbRawBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mCurrentRawBright = progress + 20;
                    if (mTxtBrightBadge != null) {
                        mTxtBrightBadge.setText(mCurrentRawBright + "%");
                    }
                    updateBrightPresetUi();
                    if (fromUser) {
                        writeRawBrightVal(mCurrentRawBright);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    writeRawBrightVal(mCurrentRawBright);
                }
            });
        }
        if (mBtnBright50 != null) mBtnBright50.setOnClickListener(v -> setRawBright(50));
        if (mBtnBright65 != null) mBtnBright65.setOnClickListener(v -> setRawBright(65));
        if (mBtnBright80 != null) mBtnBright80.setOnClickListener(v -> setRawBright(80));
        if (mBtnBright100 != null) mBtnBright100.setOnClickListener(v -> setRawBright(100));

    }

    private void selectTab(int index) {
        Log.i(TAG, "selectTab called: " + index);
        mCurrentTab = index;

        if (mTabNavNoise != null) {
            int[] loc = new int[2];
            mTabNavNoise.getLocationOnScreen(loc);
            Log.i(TAG, "TAB 0 (Noise) loc: (" + loc[0] + ", " + loc[1] + "), w=" + mTabNavNoise.getWidth() + ", h=" + mTabNavNoise.getHeight());
            if (mTabNavZoom != null) {
                mTabNavZoom.getLocationOnScreen(loc);
                Log.i(TAG, "TAB 1 (Zoom) loc: (" + loc[0] + ", " + loc[1] + "), w=" + mTabNavZoom.getWidth() + ", h=" + mTabNavZoom.getHeight());
            }
            if (mTabNavPan != null) {
                mTabNavPan.getLocationOnScreen(loc);
                Log.i(TAG, "TAB 2 (Pan) loc: (" + loc[0] + ", " + loc[1] + "), w=" + mTabNavPan.getWidth() + ", h=" + mTabNavPan.getHeight());
            }
            if (mTabNavLight != null) {
                mTabNavLight.getLocationOnScreen(loc);
                Log.i(TAG, "TAB 3 (Light) loc: (" + loc[0] + ", " + loc[1] + "), w=" + mTabNavLight.getWidth() + ", h=" + mTabNavLight.getHeight());
            }
        }

        mPanelNoise.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        mPanelZoom.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        mPanelPan.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        mPanelLight.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (mPanelBright != null) {
            mPanelBright.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        }
        if (mPanelFlash != null) {
            mPanelFlash.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
        }

        updateTabStyle(mTabNavNoise, mTabIconNoise, mTabTxtNoise, index == 0);
        updateTabStyle(mTabNavZoom, mTabIconZoom, mTabTxtZoom, index == 1);
        updateTabStyle(mTabNavPan, mTabIconPan, mTabTxtPan, index == 2);
        updateTabStyle(mTabNavLight, mTabIconLight, mTabTxtLight, index == 3);
        if (mTabNavBright != null) {
            updateTabStyle(mTabNavBright, mTabIconBright, mTabTxtBright, index == 4);
        }
        if (mTabNavFlash != null) {
            updateTabStyle(mTabNavFlash, mTabIconFlash, mTabTxtFlash, index == 5);
        }
    }

    private void updateTabStyle(LinearLayout tabNav, TextView icon, TextView txt, boolean isActive) {
        if (isActive) {
            tabNav.setBackgroundResource(R.drawable.bg_wcam_tab_active);
            icon.setTextColor(0xFFFFFFFF);
            txt.setTextColor(0xFFFFFFFF);
            txt.setTypeface(null, Typeface.BOLD);
        } else {
            tabNav.setBackgroundResource(R.drawable.bg_wcam_tab_inactive);
            icon.setTextColor(0xFF829AB1);
            txt.setTextColor(0xFF829AB1);
            txt.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void loadState() {
        // 1. Play / Pause
        mIsPaused = VcamConfigProvider.getBoolean("pause", isFlagActive(FLAG_PAUSE));
        updatePauseUi();

        // 2. Rotation
        mCurrentRotation = VcamConfigProvider.getInt("rotation", readIntValue(FILE_ROTATION, 0));
        updateRotationUi();

        // 2.5 Swap UV
        mIsSwapUv = VcamConfigProvider.getBoolean("swap_uv", isFlagActive(FILE_SWAP_UV));
        updateSwapUvUi();

        // 3. VCam Switch
        if (mSwitchVcam != null) {
            mSwitchVcam.setOnCheckedChangeListener(null);
            boolean isVcamOn = !VcamConfigProvider.getBoolean("disable", isFlagActive(FLAG_DISABLE));
            mSwitchVcam.setChecked(isVcamOn);
            if (mSwitchText != null) {
                mSwitchText.setText(isVcamOn ? "ON" : "OFF");
            }
            setupVcamSwitchListener();
        }

        // 4. Tab 1: Noise
        mCurrentNoise = VcamConfigProvider.getInt("noise", readIntValue(FILE_NOISE, 0));
        if (mCurrentNoise < 0) mCurrentNoise = 0;
        if (mCurrentNoise > 10) mCurrentNoise = 10;
        mSbNoise.setProgress(mCurrentNoise);
        mTxtNoiseBadge.setText(String.valueOf(mCurrentNoise));
        updateNoisePresetUi();

        // 5. Tab 2: Zoom
        mCurrentZoom = VcamConfigProvider.getFloat("zoom", readFloatValue(FILE_ZOOM, 1.0f));
        if (mCurrentZoom < 1.0f) mCurrentZoom = 1.0f;
        if (mCurrentZoom > 5.0f) mCurrentZoom = 5.0f;
        int zoomProg = Math.round((mCurrentZoom - 1.0f) * 100.0f);
        mSbZoom.setProgress(zoomProg);
        updateZoomUi();

        // 6. Tab 3: Pan
        mCurrentPanX = VcamConfigProvider.getFloat("pan_x", readFloatValue(FILE_PAN_X, 0.0f));
        mCurrentPanY = VcamConfigProvider.getFloat("pan_y", readFloatValue(FILE_PAN_Y, 0.0f));
        mSbPanX.setProgress(Math.round((mCurrentPanX * 100.0f) + 100));
        mSbPanY.setProgress(Math.round((mCurrentPanY * 100.0f) + 100));
        updatePanUi();

        // 7. Tab 4: Light
        parseCurrentLightConfig();
        mSbLightInt.setProgress(mLightIntensity + 100);
        updateLightUi();

        // 8. Tab 5: Brightness
        mCurrentRawBright = VcamConfigProvider.getInt("raw_bright", readIntValue(FILE_RAW_BRIGHT, 65));
        if (mCurrentRawBright < 20) mCurrentRawBright = 20;
        if (mCurrentRawBright > 150) mCurrentRawBright = 150;
        if (mSbRawBright != null) {
            mSbRawBright.setProgress(mCurrentRawBright - 20);
        }
        if (mTxtBrightBadge != null) {
            mTxtBrightBadge.setText(mCurrentRawBright + "%");
        }
        updateBrightPresetUi();

        // 9. Opacity (SeekBar 0–100%)
        mCurrentOpacity = readFloatValue(FILE_OPACITY, 0.0f);
        if (mCurrentOpacity < 0.0f || mCurrentOpacity > 1.0f) mCurrentOpacity = 0.0f;
        applyOpacity(mCurrentOpacity);
        if (mSbOpacity != null) {
            mSbOpacity.setProgress(Math.round(mCurrentOpacity * 100));
        }

        // 10. KYC Flash Sync
        if (mSwitchFlashSync != null) {
            mSwitchFlashSync.setChecked(mFlashDetector != null && mFlashDetector.isEnabled());
        }
        if (mTxtFlashStatus != null) {
            boolean en = mFlashDetector != null && mFlashDetector.isEnabled();
            mTxtFlashStatus.setText(en ? "BẬT" : "TẮT");
            mTxtFlashStatus.setTextColor(en ? 0xFF34D399 : 0xFF94A3B8);
        }
        if (mSbFlashIntensity != null) {
            mSbFlashIntensity.setProgress(mFlashIntensity);
        }
        if (mTxtFlashIntensityBadge != null) {
            mTxtFlashIntensityBadge.setText(String.valueOf(mFlashIntensity));
        }
        updateDetectModeButtons(mFlashDetectMode);

        // 11. Live Feed SHM
        if (mSwitchLiveShm != null) {
            mSwitchLiveShm.setChecked(VcamLiveShmWriter.getInstance().isRunning());
        }
        if (mTxtShmStatus != null) {
            boolean running = VcamLiveShmWriter.getInstance().isRunning();
            mTxtShmStatus.setText(running ? "BẬT" : "TẮT");
            mTxtShmStatus.setTextColor(running ? 0xFF34D399 : 0xFF94A3B8);
        }

        // Restore current tab
        selectTab(mCurrentTab);
    }

    private void parseCurrentLightConfig() {
        String s = readStringFile("/data/local/tmp/" + FILE_LIGHT);
        if (s.isEmpty()) s = readStringFile(SDCARD_DIR + FILE_LIGHT);
        if (s.isEmpty()) s = readStringFile("/sdcard/" + FILE_LIGHT);
        if (!s.isEmpty()) {
            try {
                String[] p = s.trim().split("\\s+");
                if (p.length >= 1) mLightIntensity = Integer.parseInt(p[0]);
                if (p.length >= 2) mLightPosX = Float.parseFloat(p[1]);
                if (p.length >= 3) mLightPosY = Float.parseFloat(p[2]);
            } catch (Throwable ignored) {}
        }
    }

    private void togglePlayPause() {
        mIsPaused = !mIsPaused;
        Log.i(TAG, "togglePlayPause: isPaused=" + mIsPaused);
        writeFlag(FLAG_PAUSE, mIsPaused);
        updatePauseUi();
        updateNotification();
        Toast.makeText(this, mIsPaused ? "⏸ Đã tạm dừng video & âm thanh" : "▶ Đang phát video & âm thanh", Toast.LENGTH_SHORT).show();
    }

    private void updatePauseUi() {
        if (mBtnTopPlay != null && mTxtTopPlayIcon != null && mTxtTopPlayLbl != null) {
            if (mIsPaused) {
                mTxtTopPlayIcon.setText("▶");
                mTxtTopPlayIcon.setTextColor(0xFFF59E0B); // Amber glow
                mTxtTopPlayLbl.setText("Play");
                mTxtTopPlayLbl.setTextColor(0xFFF59E0B);
            } else {
                mTxtTopPlayIcon.setText("⏸");
                mTxtTopPlayIcon.setTextColor(0xFF38BDF8); // Cyan
                mTxtTopPlayLbl.setText("Pause");
                mTxtTopPlayLbl.setTextColor(0xFF94A3B8);
            }
        }
    }

    private void cycleRotation() {
        mCurrentRotation = (mCurrentRotation + 90) % 360;
        VcamConfigProvider.setInt("rotation", mCurrentRotation);
        writeConfig(FILE_ROTATION, String.valueOf(mCurrentRotation));
        updateRotationUi();
        Toast.makeText(this, "🔄 Góc xoay: " + mCurrentRotation + "°", Toast.LENGTH_SHORT).show();
    }

    private void updateRotationUi() {
        if (mTxtTopRotateLbl != null) {
            mTxtTopRotateLbl.setText(mCurrentRotation == 0 ? "Xoay 90°" : (mCurrentRotation + "°"));
        }
        updateNotification();
    }

    private void toggleSwapUv() {
        mIsSwapUv = !mIsSwapUv;
        VcamConfigProvider.setBoolean("swap_uv", mIsSwapUv);
        writeFlag(FILE_SWAP_UV, mIsSwapUv);
        updateSwapUvUi();
        if (mIsSwapUv) {
            Toast.makeText(this, "🎨 Đã BẬT Đảo màu UV!\n(Sửa mặt xanh trên Chrome / WebRTC)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "🎨 Đã TẮT Đảo màu UV!\n(Màu chuẩn cho Firefox & Camera thường)", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSwapUvUi() {
        if (mTxtTopSwapUvLbl != null && mBtnTopSwapUv != null) {
            if (mIsSwapUv) {
                mTxtTopSwapUvLbl.setText("Màu UV");
                mTxtTopSwapUvLbl.setTextColor(0xFF38BDF8);
                mBtnTopSwapUv.setBackgroundResource(R.drawable.bg_wcam_btn_active);
            } else {
                mTxtTopSwapUvLbl.setText("Đổi màu");
                mTxtTopSwapUvLbl.setTextColor(0xFF94A3B8);
                mBtnTopSwapUv.setBackgroundResource(R.drawable.bg_wcam_btn);
            }
        }
    }

    private void resetAll() {
        mCurrentZoom = 1.0f;
        mCurrentPanX = 0.0f;
        mCurrentPanY = 0.0f;
        mCurrentRotation = 0;
        mCurrentNoise = 0;
        mLightIntensity = 0;
        mLightPosX = 0.0f;
        mLightPosY = 0.0f;
        mLightDesc = "Giữa mặt";
        mIsPaused = false;

        // Apply to Provider
        long tsNow = System.currentTimeMillis();
        VcamConfigProvider.setInt("rotation", 0);
        VcamConfigProvider.setFloat("zoom", 1.0f);
        VcamConfigProvider.setFloat("pan_x", 0.0f);
        VcamConfigProvider.setFloat("pan_y", 0.0f);
        VcamConfigProvider.setInt("noise", 0);
        VcamConfigProvider.setString("light", "0 0.00 0.00 1.20");
        VcamConfigProvider.setInt("light_intensity", 0);
        VcamConfigProvider.setInt("raw_bright", 65);
        VcamConfigProvider.setFloat("bright_factor", 1.0f);
        VcamConfigProvider.setBoolean("swap_uv", false);
        VcamConfigProvider.setBoolean("pause", false);
        VcamConfigProvider.setLong("reset_ts", tsNow);

        // Apply to UI
        mSbNoise.setProgress(0);
        mSbZoom.setProgress(0);
        mSbPanX.setProgress(100);
        mSbPanY.setProgress(100);
        mSbLightInt.setProgress(100);

        updatePauseUi();
        updateRotationUi();
        updateNoisePresetUi();
        updateZoomUi();
        updatePanUi();
        updateLightUi();

        // Write to native backend
        writeFlag(FLAG_PAUSE, false);
        writeConfig(FILE_ROTATION, "0");
        writeNoiseVal(0);
        writeZoomVal(1.0f);
        writePanVal();
        writeLightVal();

        String ts = String.valueOf(tsNow);
        writeConfig(FLAG_RESET, ts);
        writeConfig("vcam_replay", ts);

        setRawBright(65);
        Toast.makeText(this, "⟲ Đã đặt lại toàn bộ thông số gốc!", Toast.LENGTH_SHORT).show();
    }

    private void cycleOpacity() {
        // Chu kỳ tăng dần độ trong suốt: 0% (Đục rõ) → 40% → 70% → 90% (Trong suốt) → 0%
        int cur = Math.round(mCurrentOpacity * 100);
        int next;
        if (cur < 20) next = 40;
        else if (cur < 55) next = 70;
        else if (cur < 85) next = 90;
        else next = 0;
        float op = next / 100.0f;
        setOpacity(op);
        if (mSbOpacity != null) mSbOpacity.setProgress(next);
        Toast.makeText(this, "Độ trong suốt menu: " + next + "%", Toast.LENGTH_SHORT).show();
    }

    public void setOpacity(float transparency) {
        if (transparency < 0.0f) transparency = 0.0f;
        if (transparency > 1.00f) transparency = 1.00f;
        applyOpacity(transparency);
        writeConfig(FILE_OPACITY, String.format(Locale.US, "%.2f", transparency));
    }

    private void updateOpacityPresetUi() {
        int pct = Math.round(mCurrentOpacity * 100);
        if (mTxtTabOpacityBadge != null) {
            mTxtTabOpacityBadge.setText(pct + "%");
        }
        if (mSbOpacity != null && mSbOpacity.getProgress() != pct) {
            mSbOpacity.setProgress(pct);
        }
    }

    private void applyOpacity(float transparency) {
        mCurrentOpacity = transparency;
        int pct = Math.round(transparency * 100);
        if (mBtnFloatOpacity != null) {
            mBtnFloatOpacity.setText("👁️ " + pct + "%");
        }
        if (mTxtTabOpacityBadge != null) {
            mTxtTabOpacityBadge.setText(pct + "%");
        }
        if (mParams != null && mWindowManager != null && mFloatingView != null) {
            // transparency: 0.0 -> alpha 1.0 (0% trong suốt = đục hoàn toàn)
            //              1.0 -> alpha 0.15 (100% trong suốt = nhìn xuyên thấu tối đa qua video)
            float alpha = 1.0f - (transparency * 0.85f);
            mParams.alpha = Math.max(0.12f, Math.min(1.0f, alpha));
            try {
                mWindowManager.updateViewLayout(mFloatingView, mParams);
            } catch (Throwable ignored) {}
        }
    }

    private void setRawBright(int val) {
        mCurrentRawBright = val;
        if (mCurrentRawBright < 20) mCurrentRawBright = 20;
        if (mCurrentRawBright > 150) mCurrentRawBright = 150;
        if (mSbRawBright != null) {
            mSbRawBright.setProgress(mCurrentRawBright - 20);
        }
        if (mTxtBrightBadge != null) {
            mTxtBrightBadge.setText(mCurrentRawBright + "%");
        }
        updateBrightPresetUi();
        writeRawBrightVal(mCurrentRawBright);
    }

    private void updateBrightPresetUi() {
        setBtnActive(mBtnBright50, mCurrentRawBright == 50);
        setBtnActive(mBtnBright65, mCurrentRawBright == 65);
        setBtnActive(mBtnBright80, mCurrentRawBright == 80);
        setBtnActive(mBtnBright100, mCurrentRawBright == 100);
    }

    private void writeRawBrightVal(int val) {
        VcamConfigProvider.setInt("raw_bright", val);
        VcamConfigProvider.setFloat("bright_factor", val / 65.0f);
        writeConfig(FILE_RAW_BRIGHT, String.valueOf(val) + "\n");
    }

    // ── Tab 6: KYC Flash Sync helpers ────────────────────────────────────────

    private synchronized void ensureFlashDetector() {
        if (mFlashDetector == null) {
            mFlashDetector = new FlashColorDetector(this);
            mFlashDetector.setIntensity(mFlashIntensity);
            mFlashDetector.setDetectMode(mFlashDetectMode);
            if (mFlashDetectMode == FlashColorDetector.DETECT_FRAME_DIFF) {
                mFlashDetector.startFrameDiffMode();
            }
        }
    }

    public boolean isFlashSyncEnabled() {
        return mFlashDetector != null && mFlashDetector.isEnabled();
    }

    public boolean isScreenCapActive() {
        return mFlashDetector != null && mFlashDetector.isScreenCapActive();
    }

    public void setFlashSyncEnabled(boolean enabled) {
        ensureFlashDetector();
        mFlashDetector.setEnabled(enabled);
        writeFlag("vcam_color_sync", enabled);
        writeFlag("vcam_kyc_flash", enabled);
        if (mTxtFlashStatus != null) {
            mTxtFlashStatus.setText(enabled ? "BẬT" : "TẮT");
            mTxtFlashStatus.setTextColor(enabled ? 0xFF34D399 : 0xFF94A3B8);
        }
        if (mSwitchFlashSync != null) {
            mSwitchFlashSync.setChecked(enabled);
        }
        updateNotification();
    }

    private void updateDetectModeButtons(int mode) {
        if (mBtnFlashModeDiff != null) {
            mBtnFlashModeDiff.setBackgroundResource(
                mode == FlashColorDetector.DETECT_FRAME_DIFF
                    ? R.drawable.bg_wcam_btn_active : R.drawable.bg_wcam_btn);
        }
        if (mBtnFlashModeScreen != null) {
            mBtnFlashModeScreen.setBackgroundResource(
                mode == FlashColorDetector.DETECT_SCREEN_CAP
                    ? R.drawable.bg_wcam_btn_active : R.drawable.bg_wcam_btn);
        }
    }

    private void setFlashDetectMode(int mode) {
        mFlashDetectMode = mode;
        ensureFlashDetector();
        mFlashDetector.setDetectMode(mode);
        updateDetectModeButtons(mode);
    }

    /** Called by MainActivity when MediaProjection permission granted */
    public void startFlashScreenCapMode(int resultCode, android.content.Intent data) {
        ensureFlashDetector();
        // Android 10+: Service phải declare foregroundServiceType=mediaProjection và
        // gọi startForeground với type đó TRƯỚC khi tạo MediaProjection
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Notification notif = buildControlNotification(NOTIF_CHANNEL_ID);
                startForeground(1001, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
        } catch (Throwable e) {
            android.util.Log.w(TAG, "startForeground(mediaProjection) error: " + e.getMessage());
        }
        mFlashDetector.startScreenCapMode(resultCode, data);
        mFlashDetector.setDetectMode(FlashColorDetector.DETECT_SCREEN_CAP);
        mFlashDetectMode = FlashColorDetector.DETECT_SCREEN_CAP;
        mFlashDetector.setEnabled(true);
        if (mSwitchFlashSync != null) {
            mSwitchFlashSync.setChecked(true);
        }
        if (mTxtFlashStatus != null) {
            mTxtFlashStatus.setText("BẬT");
            mTxtFlashStatus.setTextColor(0xFF34D399);
        }
        updateDetectModeButtons(FlashColorDetector.DETECT_SCREEN_CAP);
        Toast.makeText(this, "⚡ Đã bật KYC Flash Sync (Màn hình)", Toast.LENGTH_SHORT).show();
    }

    private void setLiveShmEnabled(boolean enabled) {
        if (enabled) {
            File vid = new File(SDCARD_DIR + "vcam.mp4");
            if (!vid.exists() || !vid.canRead()) {
                vid = new File("/data/local/tmp/vcam.mp4");
            }
            if (!vid.exists() || !vid.canRead()) {
                Toast.makeText(this, "⚠️ Không tìm thấy file vcam.mp4 để stream SHM!", Toast.LENGTH_SHORT).show();
                if (mSwitchLiveShm != null) mSwitchLiveShm.setChecked(false);
                return;
            }
            VcamLiveShmWriter.getInstance().start(vid);
            if (mTxtShmStatus != null) {
                mTxtShmStatus.setText("BẬT");
                mTxtShmStatus.setTextColor(0xFF34D399);
            }
            Toast.makeText(this, "📡 Đã BẬT Live Feed SHM (0ms)!", Toast.LENGTH_SHORT).show();
        } else {
            VcamLiveShmWriter.getInstance().stop();
            if (mTxtShmStatus != null) {
                mTxtShmStatus.setText("TẮT");
                mTxtShmStatus.setTextColor(0xFF94A3B8);
            }
            Toast.makeText(this, "📡 Đã TẮT Live Feed SHM", Toast.LENGTH_SHORT).show();
        }
    }



    private void setupVcamSwitchListener() {
        if (mSwitchVcam == null) return;
        if (mSwitchText != null) {
            mSwitchText.setOnClickListener(v -> mSwitchVcam.toggle());
        }
        if (mSwitchVcam.getParent() instanceof View) {
            ((View) mSwitchVcam.getParent()).setOnClickListener(v -> mSwitchVcam.toggle());
        }
        mSwitchVcam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            writeFlag(FLAG_DISABLE, !isChecked);
            if (sListener != null) {
                try {
                    sListener.onVcamStateChanged(isChecked);
                } catch (Throwable ignored) {}
            }
            if (mSwitchText != null) {
                mSwitchText.setText(isChecked ? "ON" : "OFF");
            }
            if (isChecked) {
                Toast.makeText(this, "🟢 Đã BẬT Camera ảo!\n(Mở lại app để hiển thị video)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "🔴 Đã TẮT Camera ảo (Dùng Camera thật)!", Toast.LENGTH_LONG).show();
            }
        });
    }

    // Tab 1 helpers
    private void writeNoiseVal(int val) {
        mCurrentNoise = val;
        VcamConfigProvider.setInt("noise", val);
        writeConfig(FILE_NOISE, String.valueOf(val));
    }

    private void updateNoisePresetUi() {
        setBtnActive(mBtnNoise0, mCurrentNoise == 0);
        setBtnActive(mBtnNoise2, mCurrentNoise == 2);
        setBtnActive(mBtnNoise5, mCurrentNoise == 5);
        setBtnActive(mBtnNoise8, mCurrentNoise == 8);
        setBtnActive(mBtnNoiseMax, mCurrentNoise == 10);
    }

    // Tab 2 helpers
    private void setZoom(float zoom) {
        if (zoom < 1.0f) zoom = 1.0f;
        if (zoom > 5.0f) zoom = 5.0f;
        mCurrentZoom = zoom;
        int prog = Math.round((mCurrentZoom - 1.0f) * 100.0f);
        mSbZoom.setProgress(prog);
        updateZoomUi();
        writeZoomVal(mCurrentZoom);
    }

    private void updateZoomUi() {
        String s = String.format(Locale.US, "%.2f×", mCurrentZoom);
        mTxtZoomBadge.setText(s);
        mTxtZoomStepperVal.setText(s);

        setBtnActive(mBtnZoom1, Math.abs(mCurrentZoom - 1.0f) < 0.05f);
        setBtnActive(mBtnZoom15, Math.abs(mCurrentZoom - 1.5f) < 0.05f);
        setBtnActive(mBtnZoom2, Math.abs(mCurrentZoom - 2.0f) < 0.05f);
        setBtnActive(mBtnZoom3, Math.abs(mCurrentZoom - 3.0f) < 0.05f);
        setBtnActive(mBtnZoom5, Math.abs(mCurrentZoom - 5.0f) < 0.05f);
        updateNotification();
    }

    private void writeZoomVal(float zoom) {
        VcamConfigProvider.setFloat("zoom", zoom);
        String s = String.valueOf(zoom);
        writeConfig(FILE_ZOOM, s);
        writeConfig("vcam_zoom.cfg", s);
    }

    // Tab 3 helpers
    private void applyPan(float dx, float dy) {
        mCurrentPanX += dx;
        mCurrentPanY += dy;
        if (mCurrentPanX > 1.0f) mCurrentPanX = 1.0f;
        if (mCurrentPanX < -1.0f) mCurrentPanX = -1.0f;
        if (mCurrentPanY > 1.0f) mCurrentPanY = 1.0f;
        if (mCurrentPanY < -1.0f) mCurrentPanY = -1.0f;

        mSbPanX.setProgress(Math.round((mCurrentPanX * 100.0f) + 100));
        mSbPanY.setProgress(Math.round((mCurrentPanY * 100.0f) + 100));
        updatePanUi();
        writePanVal();
    }

    private void resetPan() {
        mCurrentPanX = 0.0f;
        mCurrentPanY = 0.0f;
        mSbPanX.setProgress(100);
        mSbPanY.setProgress(100);
        updatePanUi();
        writePanVal();
        Toast.makeText(this, "🎯 Đã căn giữa khung hình", Toast.LENGTH_SHORT).show();
    }

    private void updatePanUi() {
        mTxtPanXBadge.setText(String.format(Locale.US, "%+.2f", mCurrentPanX));
        mTxtPanYBadge.setText(String.format(Locale.US, "%+.2f", mCurrentPanY));
        updateNotification();
    }

    private void writePanVal() {
        VcamConfigProvider.setFloat("pan_x", mCurrentPanX);
        VcamConfigProvider.setFloat("pan_y", mCurrentPanY);
        writeConfig(FILE_PAN_X, String.valueOf(mCurrentPanX));
        writeConfig(FILE_PAN_Y, String.valueOf(mCurrentPanY));
        String panCfg = mCurrentPanX + "," + mCurrentPanY;
        writeConfig("vcam_pan.cfg", panCfg);
    }

    // Tab 4 helpers
    private void setLightDirection(float x, float y, String desc, int compassIndex) {
        mLightPosX = x;
        mLightPosY = y;
        mLightDesc = desc;

        // Automatically turn on subtle light if intensity is currently 0
        if (mLightIntensity == 0) {
            mLightIntensity = 40;
            mSbLightInt.setProgress(140);
        }

        updateLightUi();
        highlightCompass(compassIndex);
        writeLightVal();
    }

    private void highlightCompass(int activeIdx) {
        for (int i = 0; i < mCompassButtons.length; i++) {
            TextView btn = mCompassButtons[i];
            if (btn == null) continue;
            if (i == activeIdx) {
                btn.setBackgroundResource(R.drawable.bg_wcam_circle_active);
                btn.setTextColor(0xFF38BDF8);
            } else {
                btn.setBackgroundResource(R.drawable.bg_wcam_btn);
                btn.setTextColor(0xFF94A3B8);
            }
        }
    }

    private void updateLightUi() {
        if (mLightIntensity == 0) {
            mTxtLightBadge.setText("0% (Tắt)");
            mTxtLightPreviewDesc.setText("Tắt đèn");
            mTxtLightPreviewGlow.setText("🌑");
            mTxtLightPreviewGlow.setAlpha(0.35f);
        } else {
            String sign = mLightIntensity > 0 ? "+" : "";
            mTxtLightBadge.setText(sign + mLightIntensity + "%");
            mTxtLightPreviewDesc.setText(mLightDesc);
            if (mLightIntensity > 0) {
                mTxtLightPreviewGlow.setText("✨");
                mTxtLightPreviewGlow.setAlpha(Math.min(1.0f, 0.4f + (mLightIntensity / 140.0f)));
            } else {
                mTxtLightPreviewGlow.setText("🌒");
                mTxtLightPreviewGlow.setAlpha(Math.min(1.0f, 0.4f + (Math.abs(mLightIntensity) / 140.0f)));
            }
        }
    }

    private void writeLightVal() {
        String val = String.format(Locale.US, "%d %.2f %.2f 1.2\n", mLightIntensity, mLightPosX, mLightPosY);
        VcamConfigProvider.setString("light", val);
        VcamConfigProvider.setInt("light_intensity", mLightIntensity);
        writeConfig(FILE_LIGHT, val);
    }

    private void setBtnActive(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundResource(R.drawable.bg_wcam_btn_active);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            btn.setBackgroundResource(R.drawable.bg_wcam_btn);
            btn.setTextColor(0xFFE2E8F0);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int id = v.getId();
        if (id == R.id.layout_drag_title || id == R.id.txt_drag_handle) {
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
                    clampExpandedPosition();
                    mWindowManager.updateViewLayout(mFloatingView, mParams);
                    return true;
            }
        } else if (id == R.id.layout_bubble) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int bubbleWidth = (mLayoutBubble != null && mLayoutBubble.getWidth() > 0)
                    ? mLayoutBubble.getWidth()
                    : (int) (52 * getResources().getDisplayMetrics().density);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mBubbleInitialX = mParams.x;
                    mBubbleInitialY = mParams.y;
                    mBubbleInitialTouchX = event.getRawX();
                    mBubbleInitialTouchY = event.getRawY();
                    mBubbleIsMoving = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dy = (int) (event.getRawY() - mBubbleInitialTouchY);
                    if (Math.abs(dy) > 10) {
                        mBubbleIsMoving = true;
                        // 1. Ghim mép phải: chỉ để lộ một nửa (width / 2) trong màn hình
                        mParams.x = screenWidth - (bubbleWidth / 2);
                        // 2. BỎ QUA hoàn toàn trục X (dx = 0), CHỈ CẬP NHẬT trục Y (trượt dọc)
                        mParams.y = mBubbleInitialY + dy;
                        // 3. Giới hạn an toàn để không trôi ra ngoài mép trên và mép dưới
                        mParams.y = Math.max(100, Math.min(mParams.y, screenHeight - 200));
                        mWindowManager.updateViewLayout(mFloatingView, mParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mBubbleIsMoving) {
                        // Người dùng chạm nhẹ -> Mở rộng bảng điều khiển HUD
                        int cardWidth = (int) (340 * getResources().getDisplayMetrics().density);
                        mParams.x = Math.max(10, screenWidth - cardWidth - 16);
                        clampExpandedPosition();
                        mWindowManager.updateViewLayout(mFloatingView, mParams);
                        loadState();
                        mLayoutBubble.setVisibility(View.GONE);
                        mLayoutExpanded.setVisibility(View.VISIBLE);
                    } else {
                        // Nhả tay sau khi trượt: Đảm bảo ghim chuẩn mép phải
                        mParams.x = screenWidth - (bubbleWidth / 2);
                        mWindowManager.updateViewLayout(mFloatingView, mParams);
                    }
                    return true;
            }
        }
        return false;
    }

    private void clampExpandedPosition() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cardWidth = (int) (340 * getResources().getDisplayMetrics().density);

        if (mParams.x + cardWidth > screenWidth - 10) {
            mParams.x = Math.max(10, screenWidth - cardWidth - 10);
        }
        if (mParams.x < 10) {
            mParams.x = 10;
        }
        if (mParams.y < 80) {
            mParams.y = 80;
        }
        if (mParams.y > screenHeight - 450) {
            mParams.y = screenHeight - 450;
        }
    }

    private void writeConfig(String name, String val) {
        writeStringFile("/data/local/tmp/" + name, val);
        writeStringFile(SDCARD_DIR + name, val);
        deleteFileSafely(new File("/sdcard/" + name));
        deleteFileSafely(new File("/storage/emulated/0/" + name));
    }

    private void deleteFileSafely(File file) {
        if (file == null) return;
        try {
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignored) {}
    }

    private boolean isFlagActive(String name) {
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
        Log.i(TAG, "FloatingControlService writeFlag: " + name + " -> " + active);
        if (FLAG_DISABLE.equals(name)) {
            VcamConfigProvider.setBoolean("disable", active);
        } else if (FLAG_PAUSE.equals(name)) {
            VcamConfigProvider.setBoolean("pause", active);
        } else if (FILE_SWAP_UV.equals(name)) {
            VcamConfigProvider.setBoolean("swap_uv", active);
        } else if ("vcam_kyc_flash".equals(name)) {
            VcamConfigProvider.setBoolean("kyc_flash", active);
        } else if ("vcam_color_sync".equals(name)) {
            VcamConfigProvider.setBoolean("color_sync", active);
        }
        writeConfig(name, active ? "1\n" : "0\n");
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

    private void ensureAllConfigFiles() {
        new Thread(() -> {
            try {
                String cmd = "mkdir -p /sdcard/CameraAssistant 2>/dev/null; " +
                        "for f in vcam_pause vcam_disable vcam_reset vcam_replay vcam_noise vcam_light vcam_zoom vcam_pan_x vcam_pan_y vcam_rotation vcam_swap_uv vcam_raw_bright vcam_floating_opacity vcam_flash.shm vcam_flash.cfg; do " +
                        "[ ! -f /data/local/tmp/$f ] && touch /data/local/tmp/$f; done; " +
                        "[ ! -s /data/local/tmp/vcam_zoom ] && echo '1.0' > /data/local/tmp/vcam_zoom; " +
                        "[ ! -s /data/local/tmp/vcam_rotation ] && echo '0' > /data/local/tmp/vcam_rotation; " +
                        "[ ! -s /data/local/tmp/vcam_raw_bright ] && echo '65' > /data/local/tmp/vcam_raw_bright; " +
                        "[ ! -s /data/local/tmp/vcam_floating_opacity ] && echo '0.85' > /data/local/tmp/vcam_floating_opacity; " +
                        "chmod 666 /data/local/tmp/vcam*";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    private float readFloatValue(String name, float defVal) {
        String s1 = readStringFile("/data/local/tmp/" + name);
        if (!s1.isEmpty()) {
            try { return Float.parseFloat(s1); } catch (Throwable ignored) {}
        }
        String s2 = readStringFile(SDCARD_DIR + name);
        if (!s2.isEmpty()) {
            try { return Float.parseFloat(s2); } catch (Throwable ignored) {}
        }
        String s3 = readStringFile("/sdcard/" + name);
        if (!s3.isEmpty()) {
            try { return Float.parseFloat(s3); } catch (Throwable ignored) {}
        }
        return defVal;
    }

    private int readIntValue(String name, int defVal) {
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        try {
            VcamLiveShmWriter.getInstance().stop();
        } catch (Throwable ignored) {}
        if (mFlashDetector != null) {
            try {
                mFlashDetector.release();
            } catch (Throwable ignored) {}
            mFlashDetector = null;
        }
        try {
            unregisterReceiver(mNotifReceiver);
        } catch (Throwable ignored) {}
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        if (mFloatingView != null && mWindowManager != null) {
            mWindowManager.removeView(mFloatingView);
        }
    }
}
