package org.lineageos.camera.assistant.xposed;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;

public class XposedCamera1Hook {
    private static final String TAG = "VcamCam1Hook";

    private static SurfaceTexture sCurrentSurfaceTexture = null;
    private static SurfaceHolder sCurrentSurfaceHolder = null;
    private static VideoToFrames sDecoder = null;

    public static void initHook(ClassLoader classLoader) {
        try {
            Class<?> cameraClass = XposedHelpers.findClass("android.hardware.Camera", classLoader);

            // Hook: setPreviewTexture(SurfaceTexture)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    sCurrentSurfaceTexture = (SurfaceTexture) param.args[0];
                    Log.i(TAG, "Camera.setPreviewTexture captured");
                }
            });

            // Hook: setPreviewDisplay(SurfaceHolder)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    sCurrentSurfaceHolder = (SurfaceHolder) param.args[0];
                    Log.i(TAG, "Camera.setPreviewDisplay captured");
                }
            });

            // Hook: startPreview()
            XposedHelpers.findAndHookMethod(cameraClass, "startPreview", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    startVirtualVideoFeed();
                }
            });

            // Hook: stopPreview()
            XposedHelpers.findAndHookMethod(cameraClass, "stopPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                }
            });

            // Hook: release()
            XposedHelpers.findAndHookMethod(cameraClass, "release", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                    sCurrentSurfaceTexture = null;
                    sCurrentSurfaceHolder = null;
                }
            });

            Log.i(TAG, "Camera 1 hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 1", t);
        }
    }

    private static synchronized void startVirtualVideoFeed() {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null) {
            Log.w(TAG, "No virtual video file found");
            return;
        }

        try {
            stopVirtualVideoFeed();

            Surface surface = null;
            if (sCurrentSurfaceTexture != null) {
                surface = new Surface(sCurrentSurfaceTexture);
            } else if (sCurrentSurfaceHolder != null) {
                surface = sCurrentSurfaceHolder.getSurface();
            }

            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "No valid surface to render virtual video");
                return;
            }

            sDecoder = new VideoToFrames();
            sDecoder.setSurface(surface);
            sDecoder.decode(videoFile.getAbsolutePath());
            Log.i(TAG, "Virtual video started via VideoToFrames for Camera 1");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start virtual video feed", t);
        }
    }

    private static synchronized void stopVirtualVideoFeed() {
        if (sDecoder != null) {
            try {
                sDecoder.stopDecode();
            } catch (Throwable ignored) {}
            sDecoder = null;
        }
    }
}

