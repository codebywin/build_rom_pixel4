package org.lineageos.camera.assistant.xposed;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VcamRenderer {
    private static final String TAG = "VcamRenderer";

    private static final String VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTextureCoord;\n" +
        "varying vec2 vTextureCoord;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * aPosition;\n" +
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTextureCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform vec4 uFlashColor;\n" +
        "void main() {\n" +
        "  vec4 c = texture2D(sTexture, vTextureCoord);\n" +
        "  if (uFlashColor.a > 0.0) {\n" +
        "    gl_FragColor = mix(c, vec4(uFlashColor.rgb, 1.0), uFlashColor.a);\n" +
        "  } else {\n" +
        "    gl_FragColor = c;\n" +
        "  }\n" +
        "}\n";

    private static final float[] VERTICES = {
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    };

    private static final float[] TEX_COORDS = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    public static class RenderTarget {
        public final Surface surface;
        public final EGLSurface eglSurface;
        public final int width;
        public final int height;

        public RenderTarget(Surface s, EGLSurface egl, int w, int h) {
            this.surface = s;
            this.eglSurface = egl;
            this.width = w;
            this.height = h;
        }
    }

    private final FloatBuffer mVertexBuffer;
    private final FloatBuffer mTexCoordBuffer;

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private final List<RenderTarget> mTargets = new ArrayList<>();

    private int mProgram = 0;
    private int mTextureId = 0;
    private int muMVPMatrixHandle = 0;
    private int muSTMatrixHandle = 0;
    private int muFlashColorHandle = 0;
    private int maPositionHandle = 0;
    private int maTextureHandle = 0;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mSTMatrix = new float[16];

    private SurfaceTexture mSurfaceTexture;
    private Surface mInputSurface;
    private volatile boolean mInitialized = false;

    public VcamRenderer() {
        mVertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertexBuffer.put(VERTICES).position(0);

        mTexCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexCoordBuffer.put(TEX_COORDS).position(0);

        Matrix.setIdentityM(mMVPMatrix, 0);
    }

    public boolean init(Surface targetSurface, int videoWidth, int videoHeight) {
        if (targetSurface == null || !targetSurface.isValid()) return false;
        return init(Collections.singletonList(targetSurface), videoWidth, videoHeight);
    }

    public boolean init(List<Surface> targetSurfaces, int defaultWidth, int defaultHeight) {
        if (targetSurfaces == null || targetSurfaces.isEmpty()) {
            return false;
        }

        try {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "unable to get EGL14 display");
                return false;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                Log.e(TAG, "unable to initialize EGL14");
                return false;
            }

            int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
            if (numConfigs[0] <= 0) {
                int[] fallbackAttribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                };
                EGL14.eglChooseConfig(mEGLDisplay, fallbackAttribList, 0, configs, 0, configs.length, numConfigs, 0);
            }

            if (numConfigs[0] <= 0) {
                Log.e(TAG, "unable to find valid RGB8888 EGLConfig");
                return false;
            }

            int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (mEGLContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "failed to create EGL context");
                return false;
            }

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            mTargets.clear();

            for (int i = 0; i < targetSurfaces.size(); i++) {
                Surface s = targetSurfaces.get(i);
                if (s != null && s.isValid()) {
                    int w = defaultWidth > 0 ? defaultWidth : 1280;
                    int h = defaultHeight > 0 ? defaultHeight : 720;
                    try {
                        EGLSurface eglSurf = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], s, surfaceAttribs, 0);
                        if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                            int[] queryW = new int[1];
                            int[] queryH = new int[1];
                            EGL14.eglQuerySurface(mEGLDisplay, eglSurf, EGL14.EGL_WIDTH, queryW, 0);
                            EGL14.eglQuerySurface(mEGLDisplay, eglSurf, EGL14.EGL_HEIGHT, queryH, 0);
                            if (queryW[0] > 0) w = queryW[0];
                            if (queryH[0] > 0) h = queryH[0];

                            mTargets.add(new RenderTarget(s, eglSurf, w, h));
                            Log.i(TAG, "Target EGL Surface #" + (i + 1) + " created (" + w + "x" + h + ")");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed creating EGL surface #" + (i + 1) + ": " + t.getMessage());
                    }
                }
            }

            if (mTargets.isEmpty()) {
                Log.e(TAG, "No valid EGL targets could be created");
                release();
                return false;
            }

            RenderTarget first = mTargets.get(0);
            if (!EGL14.eglMakeCurrent(mEGLDisplay, first.eglSurface, first.eglSurface, mEGLContext)) {
                Log.e(TAG, "eglMakeCurrent failed");
                release();
                return false;
            }
            try {
                EGL14.eglSwapInterval(mEGLDisplay, 0);
            } catch (Throwable ignored) {}

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                Log.e(TAG, "failed creating GL program");
                release();
                return false;
            }

            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            muFlashColorHandle = GLES20.glGetUniformLocation(mProgram, "uFlashColor");

            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setDefaultBufferSize(first.width > 0 ? first.width : 1280, first.height > 0 ? first.height : 720);
            mInputSurface = new Surface(mSurfaceTexture);
            mInitialized = true;
            Log.i(TAG, "VcamRenderer EGL GLES20 initialized successfully with " + mTargets.size() + " targets!");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "init failed: " + t);
            release();
            return false;
        }
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public void renderFrame() {
        if (!mInitialized || mSurfaceTexture == null || mTargets.isEmpty()) return;
        try {
            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mSTMatrix);

            int rotation = XposedSharedConfig.getRotation();
            float zoom = XposedSharedConfig.getZoom();
            float panX = XposedSharedConfig.getPanX();
            float panY = XposedSharedConfig.getPanY();

            float maxPan = Math.max(0.40f, (zoom - 1.0f) / (2.0f * zoom));
            if (panX > maxPan) panX = maxPan;
            if (panX < -maxPan) panX = -maxPan;
            if (panY > maxPan) panY = maxPan;
            if (panY < -maxPan) panY = -maxPan;

            // Apply Pan and Zoom to Texture Coordinate matrix
            Matrix.translateM(mSTMatrix, 0, 0.5f - panX, 0.5f - panY, 0.0f);
            if (rotation != 0) {
                Matrix.rotateM(mSTMatrix, 0, rotation, 0.0f, 0.0f, 1.0f);
            }
            Matrix.scaleM(mSTMatrix, 0, 1.0f / zoom, 1.0f / zoom, 1.0f);
            Matrix.translateM(mSTMatrix, 0, -0.5f, -0.5f, 0.0f);

            // KYC Color Flash Blend
            float r = 0.0f, g = 0.0f, b = 0.0f, a = 0.0f;
            if (XposedSharedConfig.isKycFlashActive()) {
                String colorVal = XposedSharedConfig.getColorVal();
                if (colorVal != null && !colorVal.isEmpty() && !"auto".equalsIgnoreCase(colorVal)) {
                    try {
                        String[] parts = colorVal.split(",");
                        int colorInt = Color.parseColor(parts[0].trim());
                        r = Color.red(colorInt) / 255.0f;
                        g = Color.green(colorInt) / 255.0f;
                        b = Color.blue(colorInt) / 255.0f;
                        a = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 0.40f;
                    } catch (Throwable ignored) {
                        r = 1.0f; g = 1.0f; b = 1.0f; a = 0.40f;
                    }
                } else {
                    r = 1.0f; g = 1.0f; b = 1.0f; a = 0.40f;
                }
            }

            for (RenderTarget target : mTargets) {
                if (!target.surface.isValid()) continue;
                try {
                    if (EGL14.eglMakeCurrent(mEGLDisplay, target.eglSurface, target.eglSurface, mEGLContext)) {
                        GLES20.glViewport(0, 0, target.width, target.height);
                        GLES20.glUseProgram(mProgram);

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);

                        mVertexBuffer.position(0);
                        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 12, mVertexBuffer);
                        GLES20.glEnableVertexAttribArray(maPositionHandle);

                        mTexCoordBuffer.position(0);
                        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 8, mTexCoordBuffer);
                        GLES20.glEnableVertexAttribArray(maTextureHandle);

                        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
                        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
                        GLES20.glUniform4f(muFlashColorHandle, r, g, b, a);

                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                        EGL14.eglSwapBuffers(mEGLDisplay, target.eglSurface);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "renderFrame error on target: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "renderFrame error: " + t);
        }
    }

    public void release() {
        mInitialized = false;
        if (mSurfaceTexture != null) {
            try { mSurfaceTexture.release(); } catch (Throwable ignored) {}
            mSurfaceTexture = null;
        }
        if (mInputSurface != null) {
            try { mInputSurface.release(); } catch (Throwable ignored) {}
            mInputSurface = null;
        }
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            for (RenderTarget target : mTargets) {
                if (target.eglSurface != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(mEGLDisplay, target.eglSurface); } catch (Throwable ignored) {}
                }
            }
            mTargets.clear();
            if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                mEGLContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(mEGLDisplay);
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ": " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) return 0;
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, pixelShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }
}
