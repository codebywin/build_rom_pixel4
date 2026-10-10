package org.lineageos.camera.assistant.xposed;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.util.ArrayList;
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
    private static VideoToFrames sPreviewDecoder = null;
    private static VideoToFrames sPreviewDecoder1 = null;

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
                            try {
                                Object reader = param.thisObject;
                                int w = (int) XposedHelpers.callMethod(reader, "getWidth");
                                int h = (int) XposedHelpers.callMethod(reader, "getHeight");
                                int fmt = (int) XposedHelpers.callMethod(reader, "getImageFormat");
                                int maxImages = (int) XposedHelpers.callMethod(reader, "getMaxImages");
                                Log.i(TAG, "Tracked ImageReader Surface: " + s + " (" + w + "x" + h + ", format=0x" + Integer.toHexString(fmt) + ", max=" + maxImages + ")");
                            } catch (Throwable t) {
                                Log.i(TAG, "Tracked ImageReader Surface: " + s);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Dynamic class loading hook for face recognition plugins (e.g. LoginFaceCamera2Fragment)
            try {
                XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Class<?> clazz = (Class<?>) param.getResult();
                        if (clazz != null && clazz.getName().contains("FaceCamera")) {
                            hookFaceFragment(clazz);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> fragClass = XposedHelpers.findClass("com.facecoll.plugin.acbnew.login.camera.LoginFaceCamera2Fragment", classLoader);
                hookFaceFragment(fragClass);
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

                    // Non-destructive: do NOT divert to virtualSurface
                    // Hardware capture request keeps real surfaces so CameraX session stays alive
                    registerTargetSurface(target);
                    Log.i(TAG, "addTarget registered (non-divert): " + target);
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

    private static final java.util.Set<String> sHookedClasses = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void hookFaceFragment(Class<?> clazz) {
        if (clazz == null) return;
        String className = clazz.getName();
        if (sHookedClasses.contains(className)) return;
        sHookedClasses.add(className);

        try {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if ("handleCapturedImage".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (XposedSharedConfig.isFlagActive(XposedSharedConfig.FLAG_DISABLE)) return;
                            byte[] jpeg = VcamRenderer.getLatestJpeg();
                            if (jpeg == null || jpeg.length == 0) return;

                            Object fragment = param.thisObject;
                            try {
                                android.graphics.Bitmap bmp = (android.graphics.Bitmap) XposedHelpers.callMethod(fragment, "buildAcbPreviewBitmap", (Object) jpeg);
                                if (bmp != null) {
                                    try {
                                        android.content.res.Resources res = (android.content.res.Resources) XposedHelpers.callMethod(fragment, "pluginResources");
                                        bmp.setDensity(res.getDisplayMetrics().densityDpi);
                                    } catch (Throwable ignored) {}
                                }
                                XposedHelpers.callMethod(fragment, "saveNativeJpeg", (Object) jpeg);
                                Log.i(TAG, "Virtual JPEG fed directly to saveNativeJpeg (" + jpeg.length + " bytes)");
                            } catch (Throwable t) {
                                Log.e(TAG, "Error in virtual handleCapturedImage", t);
                            }
                            try {
                                XposedHelpers.callMethod(fragment, "resumePreview");
                            } catch (Throwable ignored) {}
                            param.setResult(null);
                        }
                    });
                    Log.i(TAG, "Successfully hooked handleCapturedImage in " + className);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed hooking face fragment: " + className, t);
        }
    }

    public static boolean isImageReaderSurface(Surface s) {
        if (s == null) return false;
        if (sImageReaderSurfaces.contains(s)) return true;
        for (Surface r : sImageReaderSurfaces) {
            if (isSameSurface(r, s)) return true;
        }
        return false;
    }

    public static boolean isSameSurface(Surface a, Surface b) {
        if (a == null || b == null) return false;
        if (a == b || a.equals(b)) return true;
        String strA = a.toString();
        String strB = b.toString();
        int nameStartA = strA.indexOf("name=");
        int nameEndA = strA.indexOf(")/@");
        int nameStartB = strB.indexOf("name=");
        int nameEndB = strB.indexOf(")/@");
        if (nameStartA != -1 && nameEndA != -1 && nameStartB != -1 && nameEndB != -1) {
            String nameA = strA.substring(nameStartA + 5, nameEndA);
            String nameB = strB.substring(nameStartB + 5, nameEndB);
            if (!"null".equals(nameA) && nameA.equals(nameB)) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void registerTargetSurface(Surface target) {
        if (target == null || !target.isValid()) return;
        if (target.equals(sVirtualSurface)) return;

        // CRITICAL FIX: Never feed ImageReader surfaces with MediaCodec video!
        // ImageReader surfaces (format 0x23 YUV_420_888, JPEG, etc.) are meant for CPU processing.
        // Direct hardware decoding into ImageReader causes Snapdragon gralloc UBWC incompatibility,
        // triggering fatal ART SIGABRT in ImageReader$SurfaceImage.nativeCreatePlanes (NewDirectByteBuffer nullptr).
        if (isImageReaderSurface(target)) {
            Log.i(TAG, "Ignoring ImageReader surface for video decoder: " + target);
            return;
        }

        String desc = target.toString();
        boolean isSurfaceView = desc.contains("Surface(name=null)");

        if (isSurfaceView) {
            // SurfaceView (display surface with Surface(name=null))
            if (sReaderSurface == null || !sReaderSurface.isValid() || isSameSurface(sReaderSurface, target)) {
                sReaderSurface = target;
                Log.i(TAG, "Registered sReaderSurface (SurfaceView): " + target);
            } else if (sReaderSurface1 == null || !sReaderSurface1.isValid() || isSameSurface(sReaderSurface1, target)) {
                sReaderSurface1 = target;
                Log.i(TAG, "Registered sReaderSurface1 (SurfaceView #2): " + target);
            }
        } else {
            // TextureView or other named Surface (SurfaceTexture)
            if (sPreviewSurface == null || !sPreviewSurface.isValid() || isSameSurface(sPreviewSurface, target)) {
                sPreviewSurface = target;
                Log.i(TAG, "Registered sPreviewSurface (named): " + target);
            } else if (sPreviewSurface1 == null || !sPreviewSurface1.isValid() || isSameSurface(sPreviewSurface1, target)) {
                sPreviewSurface1 = target;
                Log.i(TAG, "Registered sPreviewSurface1 (named): " + target);
            }
        }
    }

    private static synchronized void unregisterTargetSurface(Surface target) {
        if (target == null) return;
        if (isSameSurface(sReaderSurface, target)) {
            Log.i(TAG, "Unregistered sReaderSurface: " + target);
            sReaderSurface = null;
            if (sHwDecoder != null) {
                sHwDecoder.stopDecode();
                sHwDecoder = null;
            }
        }
        if (isSameSurface(sReaderSurface1, target)) {
            Log.i(TAG, "Unregistered sReaderSurface1: " + target);
            sReaderSurface1 = null;
            if (sHwDecoder1 != null) {
                sHwDecoder1.stopDecode();
                sHwDecoder1 = null;
            }
        }
        if (isSameSurface(sPreviewSurface, target)) {
            Log.i(TAG, "Unregistered sPreviewSurface: " + target);
            sPreviewSurface = null;
            if (sPreviewDecoder != null) {
                sPreviewDecoder.stopDecode();
                sPreviewDecoder = null;
            }
        }
        if (isSameSurface(sPreviewSurface1, target)) {
            Log.i(TAG, "Unregistered sPreviewSurface1: " + target);
            sPreviewSurface1 = null;
            if (sPreviewDecoder1 != null) {
                sPreviewDecoder1.stopDecode();
                sPreviewDecoder1 = null;
            }
        }
    }

    private static synchronized void updateSessionSurfaces(List<Surface> newSurfaces) {
        if (newSurfaces == null) return;

        // 1. Check existing preview surfaces: if not in newSurfaces or invalid, unregister them
        if (sPreviewSurface != null) {
            boolean keep = false;
            for (Surface s : newSurfaces) {
                if (isSameSurface(sPreviewSurface, s)) { keep = true; break; }
            }
            if (!keep) {
                if (sPreviewDecoder != null) { sPreviewDecoder.stopDecode(); sPreviewDecoder = null; }
                sPreviewSurface = null;
            }
        }
        if (sPreviewSurface1 != null) {
            boolean keep = false;
            for (Surface s : newSurfaces) {
                if (isSameSurface(sPreviewSurface1, s)) { keep = true; break; }
            }
            if (!keep) {
                if (sPreviewDecoder1 != null) { sPreviewDecoder1.stopDecode(); sPreviewDecoder1 = null; }
                sPreviewSurface1 = null;
            }
        }
        if (sReaderSurface != null) {
            boolean keep = false;
            for (Surface s : newSurfaces) {
                if (isSameSurface(sReaderSurface, s)) { keep = true; break; }
            }
            if (!keep) {
                if (sHwDecoder != null) { sHwDecoder.stopDecode(); sHwDecoder = null; }
                sReaderSurface = null;
            }
        }
        if (sReaderSurface1 != null) {
            boolean keep = false;
            for (Surface s : newSurfaces) {
                if (isSameSurface(sReaderSurface1, s)) { keep = true; break; }
            }
            if (!keep) {
                if (sHwDecoder1 != null) { sHwDecoder1.stopDecode(); sHwDecoder1 = null; }
                sReaderSurface1 = null;
            }
        }

        // 2. Register all current surfaces
        for (Surface s : newSurfaces) {
            registerTargetSurface(s);
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

                    if (arg0 instanceof List) {
                        List<?> outputs = (List<?>) arg0;
                        List<Surface> newSurfaces = new ArrayList<>();
                        for (Object o : outputs) {
                            if (o instanceof Surface) {
                                newSurfaces.add((Surface) o);
                            }
                        }
                        updateSessionSurfaces(newSurfaces);
                        // Non-destructive: keep original session surfaces so CameraX lifecycle is intact
                        Log.i(TAG, "Registered createCaptureSession(List) surfaces (non-destructive): " + newSurfaces.size());
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && arg0 instanceof SessionConfiguration) {
                        SessionConfiguration origConfig = (SessionConfiguration) arg0;
                        List<OutputConfiguration> outConfigs = origConfig.getOutputConfigurations();
                        List<Surface> newSurfaces = new ArrayList<>();
                        if (outConfigs != null) {
                            for (OutputConfiguration out : outConfigs) {
                                Surface s = out.getSurface();
                                if (s != null) {
                                    newSurfaces.add(s);
                                }
                            }
                        }
                        updateSessionSurfaces(newSurfaces);
                        // Non-destructive: keep original SessionConfiguration so CameraX lifecycle is intact
                        Log.i(TAG, "Registered createCaptureSession(SessionConfig) surfaces (non-destructive): " + newSurfaces.size());
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
                        List<Surface> newSurfaces = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof OutputConfiguration) {
                                Surface s = ((OutputConfiguration) item).getSurface();
                                if (s != null) {
                                    newSurfaces.add(s);
                                }
                            }
                        }
                        updateSessionSurfaces(newSurfaces);
                        // Non-destructive: keep original OutputConfigurations
                        Log.i(TAG, "Registered createCaptureSessionByOutputConfigurations surfaces (non-destructive): " + newSurfaces.size());
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

            // Do NOT stop feeds on stopRepeating because photo captures call stopRepeating temporarily
            // Only close() should stop the feeds.

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

        // 1. Feed sReaderSurface (SurfaceView / ImageReader)
        if (sReaderSurface != null && sReaderSurface.isValid()) {
            if (sHwDecoder == null || !sHwDecoder.isPlaying() || !isSameSurface(sReaderSurface, sHwDecoder.getSurface())) {
                if (sHwDecoder != null) sHwDecoder.stopDecode();
                sHwDecoder = new VideoToFrames();
                sHwDecoder.setSurface(sReaderSurface);
                sHwDecoder.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sReaderSurface: " + sReaderSurface);
            }
        }

        // 2. Feed sReaderSurface1 (Secondary SurfaceView / ImageReader)
        if (sReaderSurface1 != null && sReaderSurface1.isValid()) {
            if (sHwDecoder1 == null || !sHwDecoder1.isPlaying() || !isSameSurface(sReaderSurface1, sHwDecoder1.getSurface())) {
                if (sHwDecoder1 != null) sHwDecoder1.stopDecode();
                sHwDecoder1 = new VideoToFrames();
                sHwDecoder1.setSurface(sReaderSurface1);
                sHwDecoder1.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sReaderSurface1: " + sReaderSurface1);
            }
        }

        // 3. Feed sPreviewSurface (TextureView) via VideoToFrames + VcamRenderer
        if (sPreviewSurface != null && sPreviewSurface.isValid()) {
            if (sPreviewDecoder == null || !sPreviewDecoder.isPlaying() || !isSameSurface(sPreviewSurface, sPreviewDecoder.getSurface())) {
                if (sPreviewDecoder != null) sPreviewDecoder.stopDecode();
                sPreviewDecoder = new VideoToFrames();
                sPreviewDecoder.setSurface(sPreviewSurface);
                sPreviewDecoder.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sPreviewSurface: " + sPreviewSurface);
            }
        }

        // 4. Feed sPreviewSurface1 (Secondary named Surface) via VideoToFrames + VcamRenderer
        if (sPreviewSurface1 != null && sPreviewSurface1.isValid()) {
            if (sPreviewDecoder1 == null || !sPreviewDecoder1.isPlaying() || !isSameSurface(sPreviewSurface1, sPreviewDecoder1.getSurface())) {
                if (sPreviewDecoder1 != null) sPreviewDecoder1.stopDecode();
                sPreviewDecoder1 = new VideoToFrames();
                sPreviewDecoder1.setSurface(sPreviewSurface1);
                sPreviewDecoder1.decode(videoPath);
                Log.i(TAG, "Started VideoToFrames on sPreviewSurface1: " + sPreviewSurface1);
            }
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
        if (sPreviewDecoder != null) {
            sPreviewDecoder.stopDecode();
            sPreviewDecoder = null;
        }
        if (sPreviewDecoder1 != null) {
            sPreviewDecoder1.stopDecode();
            sPreviewDecoder1 = null;
        }
    }
}
