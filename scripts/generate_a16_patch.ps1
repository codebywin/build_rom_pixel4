$oldPatch = "c:\Users\admin\Desktop\codebywin\build_rom_pixel4\patches\vcam_pixel4.patch"
$targetPatch = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\patches\vcam_pixel4_a16.patch"

$content = Get-Content $oldPatch -Raw

# Camera.java hook cho Android 16
$cameraOld = @"
diff --git a/core/java/android/hardware/Camera.java b/core/java/android/hardware/Camera.java
index e986320..5213ab2 100644
--- a/core/java/android/hardware/Camera.java
+++ b/core/java/android/hardware/Camera.java
@@ -440,7 +440,7 @@ public class Camera {
      * @see android.app.admin.DevicePolicyManager#getCameraDisabled(android.content.ComponentName)
      */
     public static Camera open(int cameraId) {
-        return new Camera(cameraId);
+        return VcamCamera.create(cameraId);
     }
 
     /**
@@ -458,7 +458,7 @@ public class Camera {
         for (int i = 0; i < numberOfCameras; i++) {
             getCameraInfo(i, cameraInfo);
             if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
-                return new Camera(i);
+                return VcamCamera.create(i);
             }
         }
         return null;
@@ -630,7 +630,7 @@ public class Camera {
      *
      * <p>You must call this as soon as you're done with the Camera object.</p>
      */
-    public final void release() {
+    public void release() {
         native_release();
         mFaceDetectionRunning = false;
         releaseAppOps();
@@ -720,7 +720,7 @@ public class Camera {
      * @throws RuntimeException if release() has been called on this Camera
      *    instance.
      */
-    public final void setPreviewDisplay(SurfaceHolder holder) throws IOException {
+    public void setPreviewDisplay(SurfaceHolder holder) throws IOException {
         if (holder != null) {
             setPreviewSurface(holder.getSurface());
         } else {
"@

$cameraNew = @"
diff --git a/core/java/android/hardware/Camera.java b/core/java/android/hardware/Camera.java
--- a/core/java/android/hardware/Camera.java
+++ b/core/java/android/hardware/Camera.java
@@ -493,7 +493,7 @@ public class Camera {
     public static Camera open(int cameraId) {
         Context context = ActivityThread.currentApplication().getApplicationContext();
         final CameraCompatibilityInfo compatInfo = CameraManager.getRotationOverride(context);
-        return open(cameraId, context, compatInfo);
+        return VcamCamera.create(cameraId);
     }
 
     /**
@@ -711,7 +711,7 @@ public class Camera {
      *
      * <p>You must call this as soon as you're done with the Camera object.</p>
      */
-    public final void release() {
+    public void release() {
         native_release();
         mFaceDetectionRunning = false;
         releaseAppOps();
@@ -801,7 +801,7 @@ public class Camera {
      * @throws RuntimeException if release() has been called on this Camera
      *    instance.
      */
-    public final void setPreviewDisplay(SurfaceHolder holder) throws IOException {
+    public void setPreviewDisplay(SurfaceHolder holder) throws IOException {
         if (holder != null) {
             setPreviewSurface(holder.getSurface());
         } else {
"@

# CameraDeviceImpl hook cho Android 16
$devImplOld = @"
diff --git a/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java b/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
index a07e8d2..f7fdb85 100644
--- a/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
+++ b/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
@@ -727,6 +727,7 @@ public class CameraDeviceImpl extends CameraDevice
             CameraAccessException pendingException = null;
             Surface input = null;
             try {
+                android.hardware.camera2.impl.VcamEngine.prepareOutputs(this, outputConfigurations);
                 // configure streams and then block until IDLE
                 configureSuccess = configureStreamsChecked(inputConfig, outputConfigurations,
                         operatingMode, sessionParams, createSessionStartTime);
@@ -761,6 +762,8 @@ public class CameraDeviceImpl extends CameraDevice
                         callback, executor, this, mDeviceExecutor, configureSuccess);
             }
 
+            android.hardware.camera2.impl.VcamEngine.onSessionConfigured(this, outputConfigurations);
+
             // TODO: wait until current session closes, then create the new session
             mCurrentSession = newSession;
 
@@ -1264,6 +1267,7 @@ public class CameraDeviceImpl extends CameraDevice
             CaptureRequest[] requestArray = requestList.toArray(new CaptureRequest[requestList.size()]);
             // Convert Surface to streamIdx and surfaceIdx
             for (CaptureRequest request : requestArray) {
+                android.hardware.camera2.impl.VcamEngine.filterCaptureRequest(request, repeating);
                 request.convertSurfaceToStreamId(mConfiguredOutputs);
             }
 
@@ -1406,6 +1410,7 @@ public class CameraDeviceImpl extends CameraDevice
     @Override
     public void close() {
         synchronized (mInterfaceLock) {
+            android.hardware.camera2.impl.VcamEngine.onCameraClose(this);
             if (mClosing.getAndSet(true)) {
                 return;
             }
"@

$devImplNew = @"
diff --git a/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java b/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
--- a/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
+++ b/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java
@@ -985,6 +985,7 @@ public class CameraDeviceImpl extends CameraDevice
             CameraAccessException pendingException = null;
             Surface input = null;
             try {
+                android.hardware.camera2.impl.VcamEngine.prepareOutputs(this, outputConfigurations);
                 // configure streams and then block until IDLE
                 configureSuccess = configureStreamsChecked(inputConfig, outputConfigurations,
                         operatingMode, sessionParams, createSessionStartTime);
@@ -1026,6 +1027,8 @@ public class CameraDeviceImpl extends CameraDevice
                         callback, executor, this, mDeviceExecutor, configureSuccess);
             }
 
+            android.hardware.camera2.impl.VcamEngine.onSessionConfigured(this, outputConfigurations);
+
             // TODO: wait until current session closes, then create the new session
             mCurrentSession = newSession;
 
@@ -1544,6 +1547,7 @@ public class CameraDeviceImpl extends CameraDevice
             CaptureRequest[] requestArray = requestList.toArray(new CaptureRequest[requestList.size()]);
             // Convert Surface to streamIdx and surfaceIdx
             for (CaptureRequest request : requestArray) {
+                android.hardware.camera2.impl.VcamEngine.filterCaptureRequest(request, repeating);
                 request.convertSurfaceToStreamId(mConfiguredOutputs);
             }
 
@@ -1759,6 +1763,7 @@ public class CameraDeviceImpl extends CameraDevice
     @Override
     public void close() {
         synchronized (mInterfaceLock) {
+            android.hardware.camera2.impl.VcamEngine.onCameraClose(this);
             if (mClosing.getAndSet(true)) {
                 return;
             }
"@

$patchA16 = $content.Replace($cameraOld, $cameraNew).Replace($devImplOld, $devImplNew)

# Cắt bỏ AudioRecord.java
$regexAudioRecord = "(?s)diff --git a/media/java/android/media/AudioRecord\.java.*?diff --git a/core/java/android/hardware/VcamCamera\.java"
$patchA16 = [regex]::Replace($patchA16, $regexAudioRecord, "diff --git a/core/java/android/hardware/VcamCamera.java")

# Cắt bỏ VcamAudioEngine.java
$regexAudioEngine = "(?s)diff --git a/media/java/android/media/VcamAudioEngine\.java.*"
$patchA16 = [regex]::Replace($patchA16, $regexAudioEngine, "")

[System.IO.File]::WriteAllText($targetPatch, $patchA16, [System.Text.Encoding]::UTF8)
Write-Host "Re-generated vcam_pixel4_a16.patch successfully!"

