package com.lucida.watch;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 把前后摄像头两路画面（GL_TEXTURE_EXTERNAL_OES）合成「画中画 / 上下分屏」，画到：预览窗口、（录像时）编码器输入面、（拍照时）离屏缓冲。
 * 所有方法必须在同一个 GL 线程里调用。
 */
final class GlComposer {
    static final int BACK = 0, FRONT = 1;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface winSurf = EGL14.EGL_NO_SURFACE, encSurf = EGL14.EGL_NO_SURFACE, pbuf = EGL14.EGL_NO_SURFACE;
    private int prog, aLocal, uRect, uUv, uTex, uTexMat, uCorner, uBorder, uAspect;
    private final int[] tex = new int[2];
    final SurfaceTexture[] st = new SurfaceTexture[2];
    final Surface[] surf = new Surface[2];
    final float[][] texMat = {new float[16], new float[16]};
    final int[] bufW = {1280, 1280}, bufH = {720, 720};
    final int[] sensor = {90, 270};
    int dispDeg = 0;
    final DualState state;
    private FloatBuffer quad;
    int winW = 1, winH = 1;
    private int fbo, fboTex, fboW, fboH;
    private final boolean[] hasFrame = new boolean[2];

    GlComposer(DualState s) { state = s; }

    private static final String VS =
            "attribute vec2 aLocal; uniform vec4 uRect; uniform mat4 uUv; uniform mat4 uTexMat;\n" +
            "varying vec2 vTex; varying vec2 vLocal;\n" +
            "void main(){ vLocal=aLocal;\n" +
            " float x=uRect.x+aLocal.x*uRect.z; float y=uRect.y+(1.0-aLocal.y)*uRect.w;\n" +
            " gl_Position=vec4(2.0*x-1.0, 1.0-2.0*y, 0.0, 1.0);\n" +
            " vec4 t=uUv*vec4(aLocal,0.0,1.0); vTex=(uTexMat*t).xy; }\n";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vTex; varying vec2 vLocal; uniform samplerExternalOES uTex;\n" +
            "uniform float uCorner; uniform float uBorder; uniform float uAspect;\n" +   // uAspect = 宽/高
            "void main(){\n" +
            " if(uCorner>0.0){ vec2 p=(vLocal-0.5)*vec2(uAspect,1.0); vec2 h=vec2(uAspect,1.0)*0.5-vec2(uCorner);\n" +
            "  vec2 q=abs(p)-h; float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-uCorner;\n" +
            "  if(d>0.0) discard; if(d>-uBorder){ gl_FragColor=vec4(1.0,1.0,1.0,1.0); return; } }\n" +
            " gl_FragColor=texture2D(uTex,vTex); }\n";

    void init(Surface window) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, attr, 0, cs, 0, 1, n, 0);
        if (n[0] == 0) throw new RuntimeException("没有可用的 EGL 配置");
        cfg = cs[0];
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        winSurf = EGL14.eglCreateWindowSurface(dpy, cfg, window, new int[]{EGL14.EGL_NONE}, 0);
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, winSurf, winSurf, ctx);

        prog = link(VS, FS);
        aLocal = GLES20.glGetAttribLocation(prog, "aLocal");
        uRect = GLES20.glGetUniformLocation(prog, "uRect");
        uUv = GLES20.glGetUniformLocation(prog, "uUv");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uTexMat = GLES20.glGetUniformLocation(prog, "uTexMat");
        uCorner = GLES20.glGetUniformLocation(prog, "uCorner");
        uBorder = GLES20.glGetUniformLocation(prog, "uBorder");
        uAspect = GLES20.glGetUniformLocation(prog, "uAspect");
        float[] q = {0, 0, 1, 0, 0, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);

        GLES20.glGenTextures(2, tex, 0);
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            st[i] = new SurfaceTexture(tex[i]);
            Matrix.setIdentityM(texMat[i], 0);
        }
    }

    void setBufferSize(int cam, int w, int h) { bufW[cam] = w; bufH[cam] = h; st[cam].setDefaultBufferSize(w, h); }
    Surface surface(int cam) { if (surf[cam] == null) surf[cam] = new Surface(st[cam]); return surf[cam]; }

    void setEncoderSurface(Surface s) {
        if (encSurf != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(dpy, encSurf); encSurf = EGL14.EGL_NO_SURFACE; }
        if (s != null) encSurf = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[]{EGL14.EGL_NONE}, 0);
    }
    boolean hasEncoder() { return encSurf != EGL14.EGL_NO_SURFACE; }

    /** 取走最新一帧；返回两路是否都已经有画面。 */
    boolean update(int cam) {
        try {
            EGL14.eglMakeCurrent(dpy, winSurf, winSurf, ctx);
            st[cam].updateTexImage();
            st[cam].getTransformMatrix(texMat[cam]);
            hasFrame[cam] = true;
        } catch (Exception ignore) {}
        return hasFrame[0] && hasFrame[1];
    }

    // ---------------------------------------------------------------- 绘制

    void drawPreview() {
        if (winSurf == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(dpy, winSurf, winSurf, ctx);
        int[] w = new int[1], h = new int[1];
        EGL14.eglQuerySurface(dpy, winSurf, EGL14.EGL_WIDTH, w, 0);
        EGL14.eglQuerySurface(dpy, winSurf, EGL14.EGL_HEIGHT, h, 0);
        winW = Math.max(1, w[0]); winH = Math.max(1, h[0]);
        drawScene(winW, winH);
        EGL14.eglSwapBuffers(dpy, winSurf);
    }

    void drawEncoder(long ptsNs, int w, int h) {
        if (encSurf == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(dpy, encSurf, encSurf, ctx);
        drawScene(w, h);
        EGLExt.eglPresentationTimeANDROID(dpy, encSurf, ptsNs);
        EGL14.eglSwapBuffers(dpy, encSurf);
        EGL14.eglMakeCurrent(dpy, winSurf, winSurf, ctx);
    }

    /** 离屏画一张 w×h 的成品并读回来（拍照用）。 */
    Bitmap capture(int w, int h) {
        EGL14.eglMakeCurrent(dpy, winSurf, winSurf, ctx);
        if (fbo == 0 || fboW != w || fboH != h) {
            if (fbo != 0) { GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0); GLES20.glDeleteTextures(1, new int[]{fboTex}, 0); }
            int[] t = new int[1], f = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
            fbo = f[0]; fboTex = t[0]; fboW = w; fboH = h;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        drawScene(w, h);
        ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bb.rewind();
        bmp.copyPixelsFromBuffer(bb);
        android.graphics.Matrix flip = new android.graphics.Matrix();
        flip.preScale(1f, -1f);      // glReadPixels 的原点在左下角
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, w, h, flip, false);
        bmp.recycle();
        return out;
    }

    private void drawScene(int W, int H) {
        GLES20.glViewport(0, 0, W, H);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int mainCam = state.backIsMain ? BACK : FRONT, secCam = state.backIsMain ? FRONT : BACK;
        drawCam(mainCam, state.rect(0, W, H), W, H, false);
        drawCam(secCam, state.rect(1, W, H), W, H, state.style == DualState.STYLE_PIP);
    }

    private void drawCam(int cam, float[] r, int W, int H, boolean rounded) {
        GLES20.glUseProgram(prog);
        GLES20.glUniform4f(uRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniformMatrix4fv(uUv, 1, false, buildUvMatrix(cam, r[2] * W, r[3] * H), 0);
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat[cam], 0);
        float asp = (r[2] * W) / (r[3] * H);
        GLES20.glUniform1f(uCorner, rounded ? 0.16f : 0f);
        GLES20.glUniform1f(uBorder, rounded ? 0.012f * W / (r[3] * H) : 0f);
        GLES20.glUniform1f(uAspect, asp);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[cam]);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glEnableVertexAttribArray(aLocal);
        GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aLocal);
    }

    /** 与 PipelineTest 里验证过的 buildUv 一致：先居中裁切、再按需镜像、最后绕中心补足旋转。 */
    float[] buildUvMatrix(int cam, float dw, float dh) {
        int[] tr = DualState.matrixRotation(texMat[cam]);
        int extra = DualState.extraRotation(sensor[cam], dispDeg, cam == FRONT, tr[0], state.rotFix);
        int total = (tr[0] + extra) % 360;
        float uw = (total == 90 || total == 270) ? bufH[cam] : bufW[cam];
        float uh = (total == 90 || total == 270) ? bufW[cam] : bufH[cam];
        float sa = uw / uh, da = dw / dh, fw = 1f, fh = 1f;
        if (sa > da) fw = da / sa; else fh = sa / da;
        boolean mirror = (cam == FRONT && state.mirrorFront) ^ (tr[1] == 1);
        float[] t1 = new float[16], t2 = new float[16], tmp = new float[16], out = new float[16];
        Matrix.setIdentityM(t1, 0);
        Matrix.translateM(t1, 0, 0.5f - fw / 2f, 0.5f - fh / 2f, 0f);
        Matrix.scaleM(t1, 0, fw, fh, 1f);
        if (mirror) {
            Matrix.setIdentityM(t2, 0);
            Matrix.translateM(t2, 0, 0.5f, 0f, 0f);
            Matrix.scaleM(t2, 0, -1f, 1f, 1f);
            Matrix.translateM(t2, 0, -0.5f, 0f, 0f);
            Matrix.multiplyMM(tmp, 0, t2, 0, t1, 0);
            System.arraycopy(tmp, 0, t1, 0, 16);
        }
        Matrix.setIdentityM(t2, 0);
        Matrix.translateM(t2, 0, 0.5f, 0.5f, 0f);
        Matrix.rotateM(t2, 0, DualState.uvRotation(extra, tr[1] == 1), 0f, 0f, 1f);
        Matrix.translateM(t2, 0, -0.5f, -0.5f, 0f);
        Matrix.multiplyMM(out, 0, t2, 0, t1, 0);
        return out;
    }

    void release() {
        try {
            for (int i = 0; i < 2; i++) { if (surf[i] != null) surf[i].release(); if (st[i] != null) st[i].release(); }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (encSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, encSurf);
                if (winSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, winSurf);
                if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf);
                EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        } catch (Throwable ignore) {}
        dpy = EGL14.EGL_NO_DISPLAY;
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("着色器链接失败：" + GLES20.glGetProgramInfoLog(p));
        return p;
    }
    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("着色器编译失败：" + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
