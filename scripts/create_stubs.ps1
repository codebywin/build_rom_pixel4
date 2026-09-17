$stubDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\stubs\android\hardware"
New-Item -ItemType Directory -Force -Path $stubDir | Out-Null

$cameraStubCode = @"
package android.hardware;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.io.IOException;

public class Camera {
    public Camera(int cameraId) {}
    public Camera() {}
    public void release() {}
    public void setPreviewDisplay(SurfaceHolder holder) throws IOException {}
    public void setPreviewSurface(Surface surface) throws IOException {}
    public void setPreviewTexture(SurfaceTexture surfaceTexture) throws IOException {}
    public void setPreviewCallback(PreviewCallback cb) {}
    public void startPreview() {}
    public void stopPreview() {}
    public interface PreviewCallback {
        void onPreviewFrame(byte[] data, Camera camera);
    }
}
"@

[System.IO.File]::WriteAllText("$stubDir\Camera.java", $cameraStubCode, [System.Text.Encoding]::UTF8)
Write-Host "Created Camera.java stub successfully!"
