$camFile = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\test_patch\core\java\android\hardware\Camera.java"
$devFile = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\test_patch\core\java\android\hardware\camera2\impl\CameraDeviceImpl.java"

# Khôi phục file gốc trước khi sửa
Copy-Item "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\Camera_a16.java" $camFile -Force
Copy-Item "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\CameraDeviceImpl_a16.java" $devFile -Force

# Sửa Camera.java
$camContent = Get-Content $camFile -Raw

# 1. Hook Camera.open(int)
$camContent = $camContent.Replace(
"    public static Camera open(int cameraId) {
        Context context = ActivityThread.currentApplication().getApplicationContext();
        final CameraCompatibilityInfo compatInfo = CameraManager.getRotationOverride(context);
        return open(cameraId, context, compatInfo);
    }",
"    public static Camera open(int cameraId) {
        Context context = ActivityThread.currentApplication().getApplicationContext();
        final CameraCompatibilityInfo compatInfo = CameraManager.getRotationOverride(context);
        return VcamCamera.create(cameraId);
    }")

# 2. Bỏ final ở các hàm cần override cho VcamCamera
$camContent = $camContent.Replace("public final void release()", "public void release()")
$camContent = $camContent.Replace("public final void setPreviewDisplay(SurfaceHolder holder)", "public void setPreviewDisplay(SurfaceHolder holder)")
$camContent = $camContent.Replace("public final void setPreviewSurface(Surface surface)", "public void setPreviewSurface(Surface surface)")
$camContent = $camContent.Replace("public final void setPreviewTexture(SurfaceTexture surfaceTexture)", "public void setPreviewTexture(SurfaceTexture surfaceTexture)")
$camContent = $camContent.Replace("public final void setPreviewCallback(PreviewCallback cb)", "public void setPreviewCallback(PreviewCallback cb)")
$camContent = $camContent.Replace("public final void startPreview()", "public void startPreview()")
$camContent = $camContent.Replace("public final void stopPreview()", "public void stopPreview()")

[System.IO.File]::WriteAllText($camFile, $camContent, [System.Text.Encoding]::UTF8)

# Sửa CameraDeviceImpl.java
$devContent = Get-Content $devFile -Raw

# 1. prepareOutputs hook
$targetPrepare = "            try {
                // configure streams and then block until IDLE
                configureSuccess = configureStreamsChecked(inputConfig, outputConfigurations,"

$replacementPrepare = "            try {
                android.hardware.camera2.impl.VcamEngine.prepareOutputs(this, outputConfigurations);
                // configure streams and then block until IDLE
                configureSuccess = configureStreamsChecked(inputConfig, outputConfigurations,"

$devContent = $devContent.Replace($targetPrepare, $replacementPrepare)

# 2. onSessionConfigured hook
$targetSession = "            // TODO: wait until current session closes, then create the new session
            mCurrentSession = newSession;"

$replacementSession = "            android.hardware.camera2.impl.VcamEngine.onSessionConfigured(this, outputConfigurations);

            // TODO: wait until current session closes, then create the new session
            mCurrentSession = newSession;"

$devContent = $devContent.Replace($targetSession, $replacementSession)

# 3. filterCaptureRequest hook
$targetFilter = "            for (CaptureRequest request : requestArray) {
                request.convertSurfaceToStreamId(mConfiguredOutputs);
            }"

$replacementFilter = "            for (CaptureRequest request : requestArray) {
                android.hardware.camera2.impl.VcamEngine.filterCaptureRequest(request, repeating);
                request.convertSurfaceToStreamId(mConfiguredOutputs);
            }"

$devContent = $devContent.Replace($targetFilter, $replacementFilter)

# 4. onCameraClose hook
$targetClose = "    public void close() {
        synchronized (mInterfaceLock) {"

$replacementClose = "    public void close() {
        synchronized (mInterfaceLock) {
            android.hardware.camera2.impl.VcamEngine.onCameraClose(this);"

$devContent = $devContent.Replace($targetClose, $replacementClose)

[System.IO.File]::WriteAllText($devFile, $devContent, [System.Text.Encoding]::UTF8)

Write-Host "Modifications applied cleanly to Android 16 test files!"

