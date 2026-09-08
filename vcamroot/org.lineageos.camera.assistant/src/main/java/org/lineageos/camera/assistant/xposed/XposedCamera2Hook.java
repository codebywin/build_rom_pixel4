package org.lineageos.camera.assistant.xposed;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
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
import java.util.List;

public class XposedCamera2Hook {
    private static final String TAG = "VcamCam2Hook";

    private static List<Surface> sTargetSurfaces = new ArrayList<>();
    private static MediaPlayer sMediaPlayer = null;

    public static void initHook(ClassLoader classLoader) {
        try {
            Class<?> cameraDeviceClass = XposedHelpers.findClass("android.hardware.camera2.CameraDevice", classLoader);

            // Hook: createCaptureSession(List<Surface>, StateCallback, Handler)
            XposedHelpers.findAndHookMethod(cameraDeviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;

                    List<?> surfaces = (List<?>) param.args[0];
                    if (surfaces != null && !surfaces.isEmpty()) {
                        synchronized (sTargetSurfaces) {
                            sTargetSurfaces.clear();
                            for (Object obj : surfaces) {
                                if (obj instanceof Surface && ((Surface) obj).isValid()) {
                                    sTargetSurfaces.add((Surface) obj);
                                    Log.i(TAG, "Captured target preview Surface: " + obj);
                                }
                            }
                        }
                    }
                }
            });

            // Hook: CameraCaptureSession.setRepeatingRequest
            Class<?> sessionClass = XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession", classLoader);
            XposedHelpers.findAndHookMethod(sessionClass, "setRepeatingRequest", CaptureRequest.class, CameraCaptureSession.CaptureCallback.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    startVirtualVideoFeed();
                }
            });

            // Hook: CameraCaptureSession.close()
            XposedHelpers.findAndHookMethod(sessionClass, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                }
            });

            // Hook: CameraDevice.close()
            XposedHelpers.findAndHookMethod(cameraDeviceClass, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    stopVirtualVideoFeed();
                    synchronized (sTargetSurfaces) {
                        sTargetSurfaces.clear();
                    }
                }
            });

            Log.i(TAG, "Camera 2 hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 2", t);
        }
    }

    private static synchronized void startVirtualVideoFeed() {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null) {
            Log.w(TAG, "No virtual video file found");
            return;
        }

        Surface surfaceToUse = null;
        synchronized (sTargetSurfaces) {
            for (Surface s : sTargetSurfaces) {
                if (s != null && s.isValid()) {
                    surfaceToUse = s;
                    break;
                }
            }
        }

        if (surfaceToUse == null) {
            Log.w(TAG, "No valid target surface found for Camera 2 video");
            return;
        }

        try {
            stopVirtualVideoFeed();

            sMediaPlayer = new MediaPlayer();
            sMediaPlayer.setDataSource(videoFile.getAbsolutePath());
            sMediaPlayer.setSurface(surfaceToUse);
            sMediaPlayer.setLooping(true);
            sMediaPlayer.setVolume(0f, 0f);

            sMediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    Log.i(TAG, "Virtual video started playing into Camera 2 surface");
                } catch (Throwable t) {
                    Log.e(TAG, "Error starting Camera 2 virtual video", t);
                }
            });

            sMediaPlayer.prepareAsync();
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
        }
    }
}

