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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class XposedCamera1Hook {
    private static final String TAG = "VcamCam1Hook";

    private static final Map<Camera, Integer> sCameraIdMap = new ConcurrentHashMap<>();
    private static final Map<Camera, Boolean> sHasPreviewCallback = new ConcurrentHashMap<>();

    private static volatile SurfaceTexture sCurrentSurfaceTexture = null;
    private static volatile SurfaceHolder sCurrentSurfaceHolder = null;
    private static SurfaceTexture sDummyTexture = null;
    private static VideoToFrames sSurfaceDecoder = null;

    private static synchronized SurfaceTexture getDummySurfaceTexture() {
        if (sDummyTexture == null) {
            sDummyTexture = new SurfaceTexture(10);
        }
        return sDummyTexture;
    }

    private static class VcamPreviewCallbackWrapper implements Camera.PreviewCallback {
        final Camera targetCamera;
        final Camera.PreviewCallback realCallback;

        VcamPreviewCallbackWrapper(Camera cam, Camera.PreviewCallback cb) {
            this.targetCamera = cam;
            this.realCallback = cb;
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (data != null && !XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) {
                try {
                    byte[] vFrame = VcamYuvPlayer.getCurrentNv21Frame(camera != null ? camera : targetCamera);
                    if (vFrame != null) {
                        System.arraycopy(vFrame, 0, data, 0, Math.min(data.length, vFrame.length));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error supplying virtual NV21 frame: " + t);
                }
            }
            realCallback.onPreviewFrame(data, camera);
        }
    }

    private static Camera.PreviewCallback wrapCallback(Camera cam, Camera.PreviewCallback cb) {
        if (cb == null) return null;
        if (cb instanceof VcamPreviewCallbackWrapper) return cb;
        return new VcamPreviewCallbackWrapper(cam, cb);
    }

    public static void initHook(ClassLoader classLoader) {
        try {
            Class<?> cameraClass = XposedHelpers.findClass("android.hardware.Camera", classLoader);

            // Hook: open(int)
            XposedHelpers.findAndHookMethod(cameraClass, "open", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Camera cam = (Camera) param.getResult();
                    if (cam != null && param.args.length > 0) {
                        int camId = (Integer) param.args[0];
                        sCameraIdMap.put(cam, camId);
                        Log.i(TAG, "Camera.open(" + camId + ") captured");
                    }
                }
            });

            // Hook: open()
            XposedHelpers.findAndHookMethod(cameraClass, "open", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Camera cam = (Camera) param.getResult();
                    if (cam != null) {
                        sCameraIdMap.put(cam, 0);
                        Log.i(TAG, "Camera.open() default (0) captured");
                    }
                }
            });

            // Hook: openLegacy(int, int)
            try {
                XposedHelpers.findAndHookMethod(cameraClass, "openLegacy", int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Camera cam = (Camera) param.getResult();
                        if (cam != null && param.args.length > 0) {
                            int camId = (Integer) param.args[0];
                            sCameraIdMap.put(cam, camId);
                            Log.i(TAG, "Camera.openLegacy(" + camId + ") captured");
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook: setPreviewTexture(SurfaceTexture)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    SurfaceTexture appTexture = (SurfaceTexture) param.args[0];
                    sCurrentSurfaceTexture = appTexture;
                    if (appTexture != null) {
                        // Divert hardware camera to dummy texture so it does not lock the app's texture
                        param.args[0] = getDummySurfaceTexture();
                        Log.i(TAG, "Camera.setPreviewTexture captured & diverted to dummy texture");
                    }
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

            // Hook: setPreviewCallbackWithBuffer(PreviewCallback)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    Camera cam = (Camera) param.thisObject;
                    Camera.PreviewCallback cb = (Camera.PreviewCallback) param.args[0];
                    if (cb != null) {
                        sHasPreviewCallback.put(cam, true);
                        param.args[0] = wrapCallback(cam, cb);
                        Log.i(TAG, "Camera.setPreviewCallbackWithBuffer wrapped for " + cam);
                    } else {
                        sHasPreviewCallback.remove(cam);
                    }
                }
            });

            // Hook: setPreviewCallback(PreviewCallback)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    Camera cam = (Camera) param.thisObject;
                    Camera.PreviewCallback cb = (Camera.PreviewCallback) param.args[0];
                    if (cb != null) {
                        sHasPreviewCallback.put(cam, true);
                        param.args[0] = wrapCallback(cam, cb);
                        Log.i(TAG, "Camera.setPreviewCallback wrapped for " + cam);
                    } else {
                        sHasPreviewCallback.remove(cam);
                    }
                }
            });

            // Hook: setOneShotPreviewCallback(PreviewCallback)
            XposedHelpers.findAndHookMethod(cameraClass, "setOneShotPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    Camera cam = (Camera) param.thisObject;
                    Camera.PreviewCallback cb = (Camera.PreviewCallback) param.args[0];
                    if (cb != null) {
                        param.args[0] = wrapCallback(cam, cb);
                        Log.i(TAG, "Camera.setOneShotPreviewCallback wrapped for " + cam);
                    }
                }
            });

            // Hook: startPreview()
            XposedHelpers.findAndHookMethod(cameraClass, "startPreview", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                    Camera cam = (Camera) param.thisObject;
                    startVirtualVideoFeed(cam);
                }
            });

            // Hook: stopPreview()
            XposedHelpers.findAndHookMethod(cameraClass, "stopPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Camera cam = (Camera) param.thisObject;
                    VcamYuvPlayer.stopPlayer(cam);
                    stopSurfaceVideoFeed();
                }
            });

            // Hook: release()
            XposedHelpers.findAndHookMethod(cameraClass, "release", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Camera cam = (Camera) param.thisObject;
                    VcamYuvPlayer.stopPlayer(cam);
                    stopSurfaceVideoFeed();
                    sCameraIdMap.remove(cam);
                    sHasPreviewCallback.remove(cam);
                    sCurrentSurfaceTexture = null;
                    sCurrentSurfaceHolder = null;
                }
            });

            // Hook: takePicture(...)
            try {
                XposedHelpers.findAndHookMethod(cameraClass, "takePicture",
                        Camera.ShutterCallback.class,
                        Camera.PictureCallback.class,
                        Camera.PictureCallback.class,
                        Camera.PictureCallback.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                                if (param.args[3] != null) {
                                    final Camera.PictureCallback origJpeg = (Camera.PictureCallback) param.args[3];
                                    param.args[3] = new Camera.PictureCallback() {
                                        @Override
                                        public void onPictureTaken(byte[] data, Camera camera) {
                                            byte[] vJpeg = VcamRenderer.getLatestJpeg();
                                            if (vJpeg != null && !XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) {
                                                data = vJpeg;
                                            }
                                            origJpeg.onPictureTaken(data, camera);
                                        }
                                    };
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            Log.i(TAG, "Camera 1 hooks (callbacks & surfaces) installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Camera 1", t);
        }
    }

    private static synchronized void startVirtualVideoFeed(Camera cam) {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "No virtual video file found");
            return;
        }

        int width = 1280;
        int height = 720;
        try {
            Camera.Parameters params = cam.getParameters();
            if (params != null) {
                Camera.Size sz = params.getPreviewSize();
                if (sz != null) {
                    width = sz.width;
                    height = sz.height;
                }
            }
        } catch (Throwable ignored) {}

        int camId = sCameraIdMap.containsKey(cam) ? sCameraIdMap.get(cam) : 0;
        int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        int orientation = 90;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(camId, info);
            facing = info.facing;
            orientation = info.orientation;
        } catch (Throwable ignored) {}

        // Always start YUV player to supply NV21 frames for preview callbacks (e.g. WebRTC / Zalo)
        VcamYuvPlayer.startPlayer(cam, videoFile, width, height, facing, orientation);

        // Start Surface decoder for texture preview if surface is present
        startSurfaceVideoFeed(sCurrentSurfaceTexture, sCurrentSurfaceHolder);
    }

    private static synchronized void startSurfaceVideoFeed(SurfaceTexture st, SurfaceHolder sh) {
        File videoFile = XposedSharedConfig.getVideoFile();
        if (videoFile == null || !videoFile.exists()) return;

        try {
            stopSurfaceVideoFeed();

            Surface surface = null;
            if (st != null) {
                surface = new Surface(st);
            } else if (sh != null) {
                surface = sh.getSurface();
            }

            if (surface == null || !surface.isValid()) {
                return;
            }

            sSurfaceDecoder = new VideoToFrames();
            sSurfaceDecoder.setSurface(surface);
            sSurfaceDecoder.decode(videoFile.getAbsolutePath());
            Log.i(TAG, "Virtual video started via VideoToFrames on Surface for Camera 1");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start surface video feed", t);
        }
    }

    private static synchronized void stopSurfaceVideoFeed() {
        if (sSurfaceDecoder != null) {
            try {
                sSurfaceDecoder.stopDecode();
            } catch (Throwable ignored) {}
            sSurfaceDecoder = null;
        }
    }
}
