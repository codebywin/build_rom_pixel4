package org.lineageos.camera.assistant.xposed;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaPlayer;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class XposedCamera2Hook {
    private static final String TAG = "VcamCam2Hook";

    private static final List<Surface> sTargetSurfaces = new ArrayList<>();
    private static MediaPlayer sMediaPlayer = null;
    private static Surface sPlayingSurface = null;

    public static void initHook(ClassLoader classLoader) {
        try {
            // 1. Hook CaptureRequest.Builder.addTarget(Surface) to intercept every output surface
            try {
                Class<?> builderClass = XposedHelpers.findClass("android.hardware.camera2.CaptureRequest$Builder", classLoader);
                XposedBridge.hookAllMethods(builderClass, "addTarget", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Surface) {
                            Surface s = (Surface) param.args[0];
                            if (s.isValid()) {
                                addSurface(s, "CaptureRequest.Builder.addTarget");
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Could not hook CaptureRequest.Builder.addTarget", t);
            }

            // 2. Hook CameraDeviceImpl & CameraDevice createCaptureSession
            hookDevice(classLoader, "android.hardware.camera2.impl.CameraDeviceImpl");
            hookDevice(classLoader, "android.hardware.camera2.CameraDevice");

            // 3. Hook CameraCaptureSessionImpl & CameraCaptureSession
            hookSession(classLoader, "android.hardware.camera2.impl.CameraCaptureSessionImpl");
            hookSession(classLoader, "android.hardware.camera2.CameraCaptureSession");

            Log.i(TAG, "Camera 2 hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 2", t);
        }
    }

    private static void hookDevice(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);

            // Hook all createCaptureSession overloads
            XposedBridge.hookAllMethods(clazz, "createCaptureSession", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args == null || param.args.length == 0) return;

                    Object firstArg = param.args[0];
                    if (firstArg instanceof List) {
                        collectSurfaces((List<?>) firstArg);
                    } else if (firstArg != null) {
                        try {
                            List<?> outConfigs = (List<?>) XposedHelpers.callMethod(firstArg, "getOutputConfigurations");
                            if (outConfigs != null) {
                                for (Object outConfig : outConfigs) {
                                    Surface s = (Surface) XposedHelpers.callMethod(outConfig, "getSurface");
                                    if (s != null && s.isValid()) {
                                        addSurface(s, "SessionConfiguration");
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    startVirtualVideoFeed();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    startVirtualVideoFeed();
                }
            });

            // Hook all createCaptureSessionByOutputConfigurations overloads
            XposedBridge.hookAllMethods(clazz, "createCaptureSessionByOutputConfigurations", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof List) {
                        for (Object outConfig : (List<?>) param.args[0]) {
                            try {
                                Surface s = (Surface) XposedHelpers.callMethod(outConfig, "getSurface");
                                if (s != null && s.isValid()) {
                                    addSurface(s, "OutputConfiguration");
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    startVirtualVideoFeed();
                }
            });

            // Hook close()
            XposedBridge.hookAllMethods(clazz, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                    synchronized (sTargetSurfaces) {
                        sTargetSurfaces.clear();
                    }
                }
            });

        } catch (Throwable ignored) {}
    }

    private static void hookSession(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);

            XC_MethodHook repeatingHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args != null && param.args.length > 0) {
                        Object reqObj = param.args[0];
                        if (reqObj instanceof CaptureRequest) {
                            extractTargetsFromRequest((CaptureRequest) reqObj);
                        } else if (reqObj instanceof List) {
                            for (Object item : (List<?>) reqObj) {
                                if (item instanceof CaptureRequest) {
                                    extractTargetsFromRequest((CaptureRequest) item);
                                }
                            }
                        }
                    }
                    startVirtualVideoFeed();
                }
            };

            XposedBridge.hookAllMethods(clazz, "setRepeatingRequest", repeatingHook);
            XposedBridge.hookAllMethods(clazz, "setRepeatingBurst", repeatingHook);
            XposedBridge.hookAllMethods(clazz, "setSingleRepeatingRequest", repeatingHook);

            XposedBridge.hookAllMethods(clazz, "stopRepeating", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                }
            });

            XposedBridge.hookAllMethods(clazz, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                }
            });

        } catch (Throwable ignored) {}
    }

    private static void extractTargetsFromRequest(CaptureRequest req) {
        try {
            Collection<?> targets = (Collection<?>) XposedHelpers.callMethod(req, "getTargets");
            if (targets != null) {
                for (Object t : targets) {
                    if (t instanceof Surface && ((Surface) t).isValid()) {
                        addSurface((Surface) t, "CaptureRequest.getTargets");
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void addSurface(Surface s, String source) {
        synchronized (sTargetSurfaces) {
            if (!sTargetSurfaces.contains(s)) {
                // If it's a SurfaceTexture, insert at beginning so it has highest priority
                if (s.toString().contains("SurfaceTexture")) {
                    sTargetSurfaces.add(0, s);
                } else {
                    sTargetSurfaces.add(s);
                }
                Log.i(TAG, "Captured preview Surface via " + source + ": " + s);
            }
        }
    }

    private static void collectSurfaces(Collection<?> surfaces) {
        if (surfaces == null) return;
        for (Object obj : surfaces) {
            if (obj instanceof Surface && ((Surface) obj).isValid()) {
                addSurface((Surface) obj, "createCaptureSession");
            }
        }
    }

    private static synchronized void startVirtualVideoFeed() {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null) {
            Log.w(TAG, "No virtual video file found in /data/local/tmp or /sdcard");
            return;
        }

        Surface surfaceToUse = null;
        synchronized (sTargetSurfaces) {
            // First priority: Surface containing SurfaceTexture (viewfinder preview)
            for (Surface s : sTargetSurfaces) {
                if (s != null && s.isValid() && s.toString().contains("SurfaceTexture")) {
                    surfaceToUse = s;
                    break;
                }
            }
            // Fallback: any valid surface
            if (surfaceToUse == null) {
                for (Surface s : sTargetSurfaces) {
                    if (s != null && s.isValid()) {
                        surfaceToUse = s;
                        break;
                    }
                }
            }
        }

        if (surfaceToUse == null) {
            Log.w(TAG, "No valid target surface found for Camera 2 video");
            return;
        }

        if (sMediaPlayer != null && surfaceToUse.equals(sPlayingSurface)) {
            try {
                if (sMediaPlayer.isPlaying()) {
                    return; // Already playing smoothly
                }
            } catch (Throwable ignored) {}
        }

        try {
            stopVirtualVideoFeed();

            sPlayingSurface = surfaceToUse;
            sMediaPlayer = new MediaPlayer();
            sMediaPlayer.setDataSource(videoFile.getAbsolutePath());
            sMediaPlayer.setSurface(surfaceToUse);
            sMediaPlayer.setLooping(true);
            sMediaPlayer.setVolume(0f, 0f);

            sMediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    Log.i(TAG, "Virtual video playing successfully into surface: " + sPlayingSurface);
                } catch (Throwable t) {
                    Log.e(TAG, "Error starting Camera 2 virtual video", t);
                }
            });

            sMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                return true;
            });

            sMediaPlayer.prepareAsync();
            Log.i(TAG, "MediaPlayer prepareAsync initiated on: " + surfaceToUse);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Camera 2 virtual video", t);
        }
    }

    private static synchronized void stopVirtualVideoFeed() {
        if (sMediaPlayer != null) {
            try {
                if (sMediaPlayer.isPlaying()) {
                    sMediaPlayer.stop();
                }
                sMediaPlayer.release();
            } catch (Throwable ignored) {}
            sMediaPlayer = null;
            sPlayingSurface = null;
        }
    }
}
