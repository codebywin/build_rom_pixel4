package org.lineageos.camera.assistant.xposed;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XposedCamera2Hook {
    private static final String TAG = "VcamCam2Hook";

    private static SurfaceTexture sVirtualTexture = null;
    private static Surface sVirtualSurface = null;

    private static volatile Surface sRealPreviewSurface = null;
    private static MediaPlayer sMediaPlayer = null;

    private static synchronized Surface getVirtualSurface() {
        if (sVirtualTexture == null) {
            sVirtualTexture = new SurfaceTexture(15);
        }
        if (sVirtualSurface == null || !sVirtualSurface.isValid()) {
            sVirtualSurface = new Surface(sVirtualTexture);
        }
        return sVirtualSurface;
    }

    public static void initHook(ClassLoader classLoader) {
        try {
            // 1. Hook CaptureRequest.Builder.addTarget(Surface)
            // Save real preview surface, but divert camera hardware output to dummy virtual surface
            Class<?> builderClass = XposedHelpers.findClass("android.hardware.camera2.CaptureRequest$Builder", classLoader);
            XposedBridge.hookAllMethods(builderClass, "addTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Surface)) return;

                    Surface target = (Surface) param.args[0];
                    Surface virtualSurface = getVirtualSurface();
                    if (target.equals(virtualSurface)) return;

                    String desc = target.toString();
                    if (desc.contains("SurfaceTexture") || !desc.contains("name=null")) {
                        sRealPreviewSurface = target;
                        Log.i(TAG, "Intercepted real preview Surface: " + target);
                    }

                    // Divert hardware stream to dummy surface so hardware doesn't lock real preview surface
                    param.args[0] = virtualSurface;
                }
            });

            // 2. Hook CaptureRequest.Builder.build() to trigger video playback on real preview surface
            XposedBridge.hookAllMethods(builderClass, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    startVirtualVideoFeed();
                }
            });

            // 3. Hook CameraDeviceImpl & CameraDevice createCaptureSession
            hookCameraDevice(classLoader, "android.hardware.camera2.impl.CameraDeviceImpl");
            hookCameraDevice(classLoader, "android.hardware.camera2.CameraDevice");

            // 4. Hook CameraCaptureSessionImpl & CameraCaptureSession
            hookCameraSession(classLoader, "android.hardware.camera2.impl.CameraCaptureSessionImpl");
            hookCameraSession(classLoader, "android.hardware.camera2.CameraCaptureSession");

            Log.i(TAG, "Camera 2 hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 2", t);
        }
    }

    private static void hookCameraDevice(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);

            // Hook createCaptureSession(List<Surface>, ...)
            XposedBridge.hookAllMethods(clazz, "createCaptureSession", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args == null || param.args.length == 0) return;

                    Object arg0 = param.args[0];
                    Surface virtualSurface = getVirtualSurface();

                    if (arg0 instanceof List) {
                        List<?> outputs = (List<?>) arg0;
                        for (Object o : outputs) {
                            if (o instanceof Surface) {
                                Surface s = (Surface) o;
                                String desc = s.toString();
                                if (desc.contains("SurfaceTexture") || !desc.contains("name=null")) {
                                    sRealPreviewSurface = s;
                                    Log.i(TAG, "Captured preview Surface from outputs: " + s);
                                }
                            }
                        }
                        // Divert real hardware outputs to dummy virtual surface
                        param.args[0] = Collections.singletonList(virtualSurface);
                        Log.i(TAG, "Diverted createCaptureSession(List) to virtual Surface");
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && arg0 instanceof SessionConfiguration) {
                        SessionConfiguration origConfig = (SessionConfiguration) arg0;
                        List<OutputConfiguration> outConfigs = origConfig.getOutputConfigurations();
                        if (outConfigs != null) {
                            for (OutputConfiguration out : outConfigs) {
                                Surface s = out.getSurface();
                                if (s != null) {
                                    String desc = s.toString();
                                    if (desc.contains("SurfaceTexture") || !desc.contains("name=null")) {
                                        sRealPreviewSurface = s;
                                        Log.i(TAG, "Captured preview Surface from SessionConfiguration: " + s);
                                    }
                                }
                            }
                        }
                        // Create fake SessionConfiguration with virtual surface
                        OutputConfiguration fakeOutput = new OutputConfiguration(virtualSurface);
                        SessionConfiguration fakeConfig = new SessionConfiguration(
                                origConfig.getSessionType(),
                                Collections.singletonList(fakeOutput),
                                origConfig.getExecutor(),
                                origConfig.getStateCallback()
                        );
                        param.args[0] = fakeConfig;
                        Log.i(TAG, "Diverted createCaptureSession(SessionConfiguration) to virtual Surface");
                    }
                }
            });

            // Hook createCaptureSessionByOutputConfigurations
            XposedBridge.hookAllMethods(clazz, "createCaptureSessionByOutputConfigurations", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof List) {
                        List<?> list = (List<?>) param.args[0];
                        for (Object item : list) {
                            if (item instanceof OutputConfiguration) {
                                Surface s = ((OutputConfiguration) item).getSurface();
                                if (s != null) {
                                    String desc = s.toString();
                                    if (desc.contains("SurfaceTexture") || !desc.contains("name=null")) {
                                        sRealPreviewSurface = s;
                                        Log.i(TAG, "Captured preview Surface from OutputConfiguration: " + s);
                                    }
                                }
                            }
                        }
                        OutputConfiguration fakeOutput = new OutputConfiguration(getVirtualSurface());
                        param.args[0] = Collections.singletonList(fakeOutput);
                        Log.i(TAG, "Diverted createCaptureSessionByOutputConfigurations to virtual Surface");
                    }
                }
            });

            // Hook close()
            XposedBridge.hookAllMethods(clazz, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                }
            });

        } catch (Throwable ignored) {}
    }

    private static void hookCameraSession(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);

            XC_MethodHook triggerPlayHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    startVirtualVideoFeed();
                }
            };

            XposedBridge.hookAllMethods(clazz, "setRepeatingRequest", triggerPlayHook);
            XposedBridge.hookAllMethods(clazz, "setRepeatingBurst", triggerPlayHook);
            XposedBridge.hookAllMethods(clazz, "setSingleRepeatingRequest", triggerPlayHook);

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

    private static synchronized void startVirtualVideoFeed() {
        if (sRealPreviewSurface == null || !sRealPreviewSurface.isValid()) {
            return;
        }

        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "No virtual video file found");
            return;
        }

        if (sMediaPlayer != null) {
            try {
                if (sMediaPlayer.isPlaying()) {
                    return; // Smooth continuous playback
                }
            } catch (Throwable ignored) {}
        }

        try {
            stopVirtualVideoFeed();

            sMediaPlayer = new MediaPlayer();
            sMediaPlayer.setDataSource(videoFile.getAbsolutePath());
            sMediaPlayer.setSurface(sRealPreviewSurface);
            sMediaPlayer.setLooping(true);
            sMediaPlayer.setVolume(0f, 0f);

            sMediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    Log.i(TAG, "Virtual video started successfully on real preview surface!");
                } catch (Throwable t) {
                    Log.e(TAG, "Error starting virtual video", t);
                }
            });

            sMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                return true;
            });

            sMediaPlayer.prepareAsync();
            Log.i(TAG, "MediaPlayer prepareAsync on real preview Surface: " + sRealPreviewSurface);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start virtual video", t);
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
        }
    }
}
