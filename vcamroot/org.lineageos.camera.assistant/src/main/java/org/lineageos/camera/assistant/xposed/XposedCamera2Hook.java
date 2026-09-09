package org.lineageos.camera.assistant.xposed;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class XposedCamera2Hook {
    private static final String TAG = "VcamCam2Hook";

    private static SurfaceTexture sVirtualTexture = null;
    private static Surface sVirtualSurface = null;

    // Reader surfaces (SurfaceView, ImageReader - Surface(name=null))
    private static volatile Surface sReaderSurface = null;
    private static volatile Surface sReaderSurface1 = null;
    private static VideoToFrames sHwDecoder = null;
    private static VideoToFrames sHwDecoder1 = null;

    // ImageReader surfaces tracked to prevent decoder format conflicts
    private static final java.util.Set<Surface> sImageReaderSurfaces = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // Preview surfaces (TextureView - SurfaceTexture)
    private static volatile Surface sPreviewSurface = null;
    private static volatile Surface sPreviewSurface1 = null;
    private static MediaPlayer sPlayer = null;
    private static MediaPlayer sPlayer1 = null;
    private static String sLastResetTs = "";

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
            // Track all ImageReader surfaces
            try {
                Class<?> imageReaderClass = XposedHelpers.findClass("android.media.ImageReader", classLoader);
                XposedBridge.hookAllMethods(imageReaderClass, "getSurface", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Surface s = (Surface) param.getResult();
                        if (s != null) {
                            sImageReaderSurfaces.add(s);
                            Log.i(TAG, "Tracked ImageReader Surface: " + s);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            Class<?> builderClass = XposedHelpers.findClass("android.hardware.camera2.CaptureRequest$Builder", classLoader);

            // 1. Hook CaptureRequest.Builder.addTarget(Surface)
            XposedBridge.hookAllMethods(builderClass, "addTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Surface)) return;

                    Surface target = (Surface) param.args[0];
                    Surface virtualSurface = getVirtualSurface();
                    if (target.equals(virtualSurface)) return;

                    registerTargetSurface(target);

                    // Divert hardware stream to dummy surface so hardware doesn't lock real preview surface
                    param.args[0] = virtualSurface;
                }
            });

            // 2. Hook CaptureRequest.Builder.removeTarget(Surface)
            XposedBridge.hookAllMethods(builderClass, "removeTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Surface)) return;
                    unregisterTargetSurface((Surface) param.args[0]);
                }
            });

            // 3. Hook CaptureRequest.Builder.build()
            XposedBridge.hookAllMethods(builderClass, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    startVirtualVideoFeeds();
                }
            });

            // 4. Hook CameraDeviceImpl & CameraDevice createCaptureSession
            hookCameraDevice(classLoader, "android.hardware.camera2.impl.CameraDeviceImpl");
            hookCameraDevice(classLoader, "android.hardware.camera2.CameraDevice");

            // 5. Hook CameraCaptureSessionImpl & CameraCaptureSession
            hookCameraSession(classLoader, "android.hardware.camera2.impl.CameraCaptureSessionImpl");
            hookCameraSession(classLoader, "android.hardware.camera2.CameraCaptureSession");

            Log.i(TAG, "Camera 2 hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 2", t);
        }
    }

    private static synchronized void registerTargetSurface(Surface target) {
        if (target == null || !target.isValid()) return;
        if (target.equals(sVirtualSurface)) return;
        if (sImageReaderSurfaces.contains(target)) {
            Log.i(TAG, "Skipping ImageReader Surface to avoid decoder format conflict: " + target);
            return;
        }

        String desc = target.toString();
        if (desc.contains("Surface(name=null)")) {
            // SurfaceView (preview)
            if (sReaderSurface == null || !sReaderSurface.isValid() || sReaderSurface.equals(target)) {
                sReaderSurface = target;
                Log.i(TAG, "Registered sReaderSurface (SurfaceView): " + target);
            } else if (sReaderSurface1 == null || !sReaderSurface1.isValid() || sReaderSurface1.equals(target)) {
                sReaderSurface1 = target;
                Log.i(TAG, "Registered sReaderSurface1 (SurfaceView #2): " + target);
            }
        } else {
            // TextureView or other named Surface
            if (sPreviewSurface == null || !sPreviewSurface.isValid() || sPreviewSurface.equals(target)) {
                sPreviewSurface = target;
                Log.i(TAG, "Registered sPreviewSurface (named): " + target);
            } else if (sPreviewSurface1 == null || !sPreviewSurface1.isValid() || sPreviewSurface1.equals(target)) {
                sPreviewSurface1 = target;
                Log.i(TAG, "Registered sPreviewSurface1 (named): " + target);
            }
        }
    }

    private static synchronized void unregisterTargetSurface(Surface target) {
        if (target == null) return;
        if (target.equals(sReaderSurface)) {
            Log.i(TAG, "Unregistered sReaderSurface: " + target);
            sReaderSurface = null;
            if (sHwDecoder != null) {
                sHwDecoder.stopDecode();
                sHwDecoder = null;
            }
        }
        if (target.equals(sReaderSurface1)) {
            Log.i(TAG, "Unregistered sReaderSurface1: " + target);
            sReaderSurface1 = null;
            if (sHwDecoder1 != null) {
                sHwDecoder1.stopDecode();
                sHwDecoder1 = null;
            }
        }
        if (target.equals(sPreviewSurface)) {
            Log.i(TAG, "Unregistered sPreviewSurface: " + target);
            sPreviewSurface = null;
            stopPlayer(0);
        }
        if (target.equals(sPreviewSurface1)) {
            Log.i(TAG, "Unregistered sPreviewSurface1: " + target);
            sPreviewSurface1 = null;
            stopPlayer(1);
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
                                registerTargetSurface((Surface) o);
                            }
                        }
                        param.args[0] = Collections.singletonList(virtualSurface);
                        Log.i(TAG, "Diverted createCaptureSession(List) to virtual Surface");
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && arg0 instanceof SessionConfiguration) {
                        SessionConfiguration origConfig = (SessionConfiguration) arg0;
                        List<OutputConfiguration> outConfigs = origConfig.getOutputConfigurations();
                        if (outConfigs != null) {
                            for (OutputConfiguration out : outConfigs) {
                                Surface s = out.getSurface();
                                if (s != null) {
                                    registerTargetSurface(s);
                                }
                            }
                        }
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
                                    registerTargetSurface(s);
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
                    stopAllFeeds();
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
                    startVirtualVideoFeeds();
                }
            };

            XposedBridge.hookAllMethods(clazz, "setRepeatingRequest", triggerPlayHook);
            XposedBridge.hookAllMethods(clazz, "setRepeatingBurst", triggerPlayHook);
            XposedBridge.hookAllMethods(clazz, "setSingleRepeatingRequest", triggerPlayHook);

            XposedBridge.hookAllMethods(clazz, "stopRepeating", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopAllFeeds();
                }
            });

            XposedBridge.hookAllMethods(clazz, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopAllFeeds();
                }
            });

        } catch (Throwable ignored) {}
    }

    private static synchronized void startVirtualVideoFeeds() {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null || !videoFile.exists()) {
            return;
        }
        String videoPath = videoFile.getAbsolutePath();

        // 1. Feed sReaderSurface (SurfaceView / ImageReader) via Hardware VideoToFrames
        if (sReaderSurface != null && sReaderSurface.isValid()) {
            if (sHwDecoder == null || !sHwDecoder.isPlaying() || !sReaderSurface.equals(sHwDecoder.getSurface())) {
                if (sHwDecoder != null) sHwDecoder.stopDecode();
                sHwDecoder = new VideoToFrames();
                sHwDecoder.setSurface(sReaderSurface);
                sHwDecoder.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sReaderSurface: " + sReaderSurface);
            }
        }

        // 2. Feed sReaderSurface1 (Secondary SurfaceView / ImageReader) via VideoToFrames
        if (sReaderSurface1 != null && sReaderSurface1.isValid()) {
            if (sHwDecoder1 == null || !sHwDecoder1.isPlaying() || !sReaderSurface1.equals(sHwDecoder1.getSurface())) {
                if (sHwDecoder1 != null) sHwDecoder1.stopDecode();
                sHwDecoder1 = new VideoToFrames();
                sHwDecoder1.setSurface(sReaderSurface1);
                sHwDecoder1.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sReaderSurface1: " + sReaderSurface1);
            }
        }

        // 3. Feed sPreviewSurface (TextureView) via MediaPlayer
        if (sPreviewSurface != null && sPreviewSurface.isValid()) {
            if (sPlayer == null) {
                startPlayer(sPreviewSurface, 0, videoPath);
            } else {
                try {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                        if (sPlayer.isPlaying()) sPlayer.pause();
                    } else if (!sPlayer.isPlaying()) {
                        sPlayer.start();
                    }
                } catch (Throwable ignored) {
                    startPlayer(sPreviewSurface, 0, videoPath);
                }
            }
        }

        // 4. Feed sPreviewSurface1 (Secondary named Surface) via MediaPlayer
        if (sPreviewSurface1 != null && sPreviewSurface1.isValid()) {
            if (sPlayer1 == null) {
                startPlayer(sPreviewSurface1, 1, videoPath);
            } else {
                try {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_PAUSE)) {
                        if (sPlayer1.isPlaying()) sPlayer1.pause();
                    } else if (!sPlayer1.isPlaying()) {
                        sPlayer1.start();
                    }
                } catch (Throwable ignored) {
                    startPlayer(sPreviewSurface1, 1, videoPath);
                }
            }
        }

        // 5. Kiểm tra tua lại / Reset trên MediaPlayer
        String currentResetTs = XposedSharedConfig.getResetTimestamp();
        if (!currentResetTs.isEmpty() && !currentResetTs.equals(sLastResetTs)) {
            sLastResetTs = currentResetTs;
            try {
                if (sPlayer != null) sPlayer.seekTo(0);
                if (sPlayer1 != null) sPlayer1.seekTo(0);
            } catch (Throwable ignored) {}
        }
    }

    private static synchronized void startPlayer(Surface surface, int index, String videoPath) {
        try {
            stopPlayer(index);

            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(videoPath);
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);

            mp.setOnPreparedListener(p -> {
                try {
                    p.start();
                    Log.i(TAG, "MediaPlayer[" + index + "] started on Surface: " + surface);
                } catch (Throwable t) {
                    Log.e(TAG, "Error starting MediaPlayer[" + index + "]", t);
                }
            });

            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "MediaPlayer[" + index + "] error: " + what + ", " + extra);
                return true;
            });

            mp.prepareAsync();
            if (index == 0) sPlayer = mp;
            else sPlayer1 = mp;

        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize MediaPlayer[" + index + "]", t);
        }
    }

    private static synchronized void stopPlayer(int index) {
        MediaPlayer mp = (index == 0) ? sPlayer : sPlayer1;
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.stop();
                mp.release();
            } catch (Throwable ignored) {}
            if (index == 0) sPlayer = null;
            else sPlayer1 = null;
        }
    }

    private static synchronized void stopAllFeeds() {
        if (sHwDecoder != null) {
            sHwDecoder.stopDecode();
            sHwDecoder = null;
        }
        if (sHwDecoder1 != null) {
            sHwDecoder1.stopDecode();
            sHwDecoder1 = null;
        }
        stopPlayer(0);
        stopPlayer(1);
    }
}
