package com.lucida.watch;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 原生双摄：Camera2 同时打开前后摄像头 → GlComposer 合成画中画 / 上下分屏 → 预览、拍照（JPEG）、录像（H.264 + AAC，带声音）。
 * 拍到的文件放在 cache/dual/ 里，同时存进系统相册；关闭时把清单交回网页（MainActivity → window.__lucidaDualDone）。
 */
public class DualCameraActivity extends Activity implements SurfaceHolder.Callback {
    static File outDir(Context c) { File d = new File(c.getCacheDir(), "dual"); d.mkdirs(); return d; }

    private static final int REQ_PERM = 21;

    private final DualState state = new DualState();
    private GlComposer gl;
    private HandlerThread glThread, camThread;
    private Handler glH, camH, ui;
    private SurfaceView sv;
    private CameraManager cm;
    private final CameraDevice[] dev = new CameraDevice[2];
    private final CameraCaptureSession[] ses = new CameraCaptureSession[2];
    private String backId, frontId;
    private boolean glReady, camsOpened, finished, recording, videoMode, torchOn, failedReported;
    private DualRecorder recorder;
    private long recStartNs;
    private int recW = 720, recH = 1280;
    private final JSONArray items = new JSONArray();
    private int pending;
    private String failCode, failMsg;

    private TextView timer, toast, countBadge;
    private View shutterInner;
    private TextView tabPhoto, tabVideo;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui = new Handler(Looper.getMainLooper());
        videoMode = "video".equals(getIntent().getStringExtra("mode"));
        buildUi();
        immersive();
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (!pickCameras()) { fail("unsupported", "没有找到前置和后置摄像头"); return; }
        glThread = new HandlerThread("dual-gl"); glThread.start(); glH = new Handler(glThread.getLooper());
        camThread = new HandlerThread("dual-cam"); camThread.start(); camH = new Handler(camThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (c == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) fail("denied", "没有相机权限");
            else tryOpen();
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() { super.onResume(); immersive(); if (glReady) tryOpen(); }

    @Override
    protected void onPause() {
        if (recording) stopRecording(false);
        closeCameras();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCameras();
        if (glH != null) {
            final GlComposer g = gl;
            glH.post(new Runnable() { @Override public void run() { if (g != null) g.release(); } });
            glThread.quitSafely();
        }
        if (camThread != null) camThread.quitSafely();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView btn(String t, int sp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(Color.WHITE); v.setTextSize(sp); v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(0x66000000); g.setCornerRadius(dp(20));
        v.setBackground(g);
        v.setOnClickListener(l);
        return v;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        // 9:16 取景区，居中
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        touch.setOnTouchListener(new View.OnTouchListener() {
            float lx, ly, dx, dy; boolean drag, inPip;
            @Override public boolean onTouch(View v, MotionEvent e) {
                float W = v.getWidth(), H = v.getHeight();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        lx = e.getX(); ly = e.getY(); dx = dy = 0; drag = false;
                        float[] r = state.rect(1, W, H);
                        inPip = state.style == DualState.STYLE_PIP && e.getX() >= r[0] * W && e.getX() <= (r[0] + r[2]) * W && e.getY() >= r[1] * H && e.getY() <= (r[1] + r[3]) * H;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE:
                        if (inPip) {
                            dx += Math.abs(e.getX() - lx); dy += Math.abs(e.getY() - ly);
                            if (dx + dy > dp(8)) drag = true;
                            if (drag) {
                                float w = Math.round(W * state.pipW), h = Math.round(w * 4f / 3f), m = Math.round(W * 0.04f);
                                state.pipX = Math.min(1f, Math.max(0f, state.pipX + (e.getX() - lx) / Math.max(1f, W - w - 2 * m)));
                                state.pipY = Math.min(1f, Math.max(0f, state.pipY + (e.getY() - ly) / Math.max(1f, H - h - 2 * m)));
                            }
                            lx = e.getX(); ly = e.getY();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (inPip && !drag) state.backIsMain = !state.backIsMain;
                        return true;
                }
                return true;
            }
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int s = dp(42);
        String[] names = {"✕", "▤", "⇄", "◐", "⚡"};
        final View.OnClickListener[] ls = new View.OnClickListener[5];
        ls[0] = new View.OnClickListener() { @Override public void onClick(View v) { closeTapped(); } };
        ls[1] = new View.OnClickListener() { @Override public void onClick(View v) {
            if (recording) { say("录像中不能切换布局"); return; }
            state.style = state.style == DualState.STYLE_PIP ? DualState.STYLE_SPLIT : DualState.STYLE_PIP;
            say(state.style == DualState.STYLE_PIP ? "画中画" : "上下分屏"); } };
        ls[2] = new View.OnClickListener() { @Override public void onClick(View v) { state.backIsMain = !state.backIsMain; } };
        ls[3] = new View.OnClickListener() { @Override public void onClick(View v) { state.mirrorFront = !state.mirrorFront; say(state.mirrorFront ? "前置镜像：开" : "前置镜像：关"); } };
        ls[4] = new View.OnClickListener() { @Override public void onClick(View v) { torch(); } };
        for (int i = 0; i < 5; i++) {
            TextView t = btn(names[i], 18, ls[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.leftMargin = lp.rightMargin = dp(8);
            top.addView(t, lp);
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = dp(28);
        root.addView(top, tp);
        ((LinearLayout) top).setGravity(Gravity.CENTER_HORIZONTAL);

        timer = new TextView(this);
        timer.setTextColor(Color.WHITE); timer.setTextSize(14); timer.setGravity(Gravity.CENTER);
        GradientDrawable tg = new GradientDrawable(); tg.setColor(0xD9E61A1A); tg.setCornerRadius(dp(12));
        timer.setBackground(tg); timer.setVisibility(View.GONE);
        FrameLayout.LayoutParams tmp = new FrameLayout.LayoutParams(dp(92), dp(26), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tmp.topMargin = dp(84);
        root.addView(timer, tmp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabPhoto = new TextView(this); tabPhoto.setText("照片"); tabPhoto.setTextSize(15); tabPhoto.setPadding(dp(16), dp(4), dp(16), dp(4));
        tabVideo = new TextView(this); tabVideo.setText("视频"); tabVideo.setTextSize(15); tabVideo.setPadding(dp(16), dp(4), dp(16), dp(4));
        tabPhoto.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!recording) { videoMode = false; paint(); } } });
        tabVideo.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!recording) { videoMode = true; paint(); } } });
        tabs.addView(tabPhoto); tabs.addView(tabVideo);
        bottom.addView(tabs);
        FrameLayout row = new FrameLayout(this);
        FrameLayout ring = new FrameLayout(this);
        GradientDrawable rg = new GradientDrawable(); rg.setShape(GradientDrawable.OVAL); rg.setColor(Color.TRANSPARENT); rg.setStroke(dp(4), Color.WHITE);
        ring.setBackground(rg);
        shutterInner = new View(this);
        shutterInner.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shutterTapped(); } });
        ring.addView(shutterInner, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        row.addView(ring, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        countBadge = btn("0", 16, new View.OnClickListener() { @Override public void onClick(View v) { closeTapped(); } });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        cp.rightMargin = dp(36);
        row.addView(countBadge, cp);
        bottom.addView(row, new LinearLayout.LayoutParams(-1, dp(84)));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.bottomMargin = dp(28);
        root.addView(bottom, bp);

        toast = new TextView(this);
        toast.setTextColor(Color.WHITE); toast.setTextSize(14); toast.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable xg = new GradientDrawable(); xg.setColor(0x99000000); xg.setCornerRadius(dp(14));
        toast.setBackground(xg); toast.setAlpha(0f);
        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        xp.bottomMargin = dp(150);
        root.addView(toast, xp);
        setContentView(root);
        paint();
    }

    private void paint() {
        tabPhoto.setTextColor(!videoMode ? 0xFFFFD60A : 0xB3FFFFFF);
        tabVideo.setTextColor(videoMode ? 0xFFFFD60A : 0xB3FFFFFF);
        GradientDrawable g = new GradientDrawable(); g.setShape(videoMode ? GradientDrawable.OVAL : GradientDrawable.OVAL);
        g.setColor(recording ? 0xFFE63232 : videoMode ? 0xFFEB3333 : Color.WHITE);
        shutterInner.setBackground(g);
        shutterInner.setScaleX(recording ? 0.55f : 1f); shutterInner.setScaleY(recording ? 0.55f : 1f);
    }

    private void say(final String t) {
        ui.post(new Runnable() { @Override public void run() {
            toast.setText(t); toast.setAlpha(1f);
            toast.animate().cancel();
            toast.animate().alpha(0f).setStartDelay(2000).setDuration(300).start();
        } });
    }

    // ---------------------------------------------------------------- 摄像头

    private boolean pickCameras() {
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f == null) continue;
                if (f == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id;
                if (f == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id;
            }
        } catch (Exception e) { return false; }
        return backId != null && frontId != null;
    }

    @Override public void surfaceCreated(final SurfaceHolder h) {
        glH.post(new Runnable() { @Override public void run() {
            try {
                if (gl != null) gl.release();
                gl = new GlComposer(state);
                gl.init(h.getSurface());
                gl.dispDeg = 0;
                for (int cam = 0; cam < 2; cam++) {
                    String id = cam == 0 ? backId : frontId;
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    gl.sensor[cam] = so == null ? 90 : so;
                    Size sz = pickSize(c);
                    gl.setBufferSize(cam, sz.getWidth(), sz.getHeight());
                    final int which = cam;
                    gl.st[cam].setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                        @Override public void onFrameAvailable(SurfaceTexture t) { onFrame(which); }
                    }, glH);
                }
                glReady = true;
                ui.post(new Runnable() { @Override public void run() { tryOpen(); } });
            } catch (Throwable t) { fail("error", "初始化画面合成失败：" + t.getMessage()); }
        } });
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { glReady = false; closeCameras(); }

    private Size pickSize(CameraCharacteristics c) {
        StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] all = m == null ? null : m.getOutputSizes(SurfaceTexture.class);
        if (all == null || all.length == 0) return new Size(1280, 720);
        List<Size> ok = new ArrayList<>();
        for (Size s : all) if (s.getWidth() <= 1280 && s.getHeight() <= 720) ok.add(s);    // 双摄同时工作时系统只保证到 720p 左右
        if (ok.isEmpty()) { Size best = all[0]; for (Size s : all) if (s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s; return best; }
        java.util.Collections.sort(ok, new Comparator<Size>() { @Override public int compare(Size a, Size b) {
            boolean a169 = a.getWidth() * 9 == a.getHeight() * 16, b169 = b.getWidth() * 9 == b.getHeight() * 16;
            if (a169 != b169) return a169 ? -1 : 1;
            return b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight();
        } });
        return ok.get(0);
    }

    private int openCount;
    @SuppressWarnings("MissingPermission")
    private synchronized void tryOpen() {
        if (camsOpened || !glReady || finished) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        camsOpened = true;
        openCount = 0;
        for (int cam = 0; cam < 2; cam++) {
            final int which = cam;
            try {
                cm.openCamera(cam == 0 ? backId : frontId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) { dev[which] = d; startSession(which); }
                    @Override public void onDisconnected(CameraDevice d) { d.close(); dev[which] = null; if (!finished) say("摄像头被占用或断开"); }
                    @Override public void onError(CameraDevice d, int err) {
                        d.close(); dev[which] = null;
                        boolean busy = err == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE || err == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE;
                        fail(busy ? "unsupported" : "error", busy ? "系统不允许前后摄像头同时工作（硬件限制）" : "摄像头出错（代码 " + err + "）");
                    }
                }, camH);
            } catch (Exception e) {
                fail(e instanceof CameraAccessException ? "unsupported" : "error", "打开摄像头失败：" + e.getMessage());
                return;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession(final int cam) {
        try {
            final CameraDevice d = dev[cam];
            if (d == null || gl == null) return;
            final Surface s = gl.surface(cam);
            d.createCaptureSession(Arrays.asList(s), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    ses[cam] = cs;
                    try {
                        CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(s);
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        cs.setRepeatingRequest(rb.build(), null, camH);
                    } catch (Exception e) { fail("error", "开启预览失败：" + e.getMessage()); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) {
                    fail("unsupported", "这台设备配置不出前后摄像头同时输出（硬件限制）");
                }
            }, camH);
        } catch (Exception e) { fail("unsupported", "建立摄像头会话失败：" + e.getMessage()); }
    }

    private void closeCameras() {
        camsOpened = false;
        for (int i = 0; i < 2; i++) {
            try { if (ses[i] != null) ses[i].close(); } catch (Throwable ignore) {}
            try { if (dev[i] != null) dev[i].close(); } catch (Throwable ignore) {}
            ses[i] = null; dev[i] = null;
        }
    }

    private void torch() {
        torchOn = !torchOn;
        try { cm.setTorchMode(backId, torchOn); say(torchOn ? "手电筒：开" : "手电筒：关"); }
        catch (Throwable e) { torchOn = false; say("手电筒不可用（双摄时系统常占用闪光灯）"); }
    }

    // ---------------------------------------------------------------- 渲染（GL 线程）

    private void onFrame(int cam) {
        if (gl == null || finished) return;
        boolean both = gl.update(cam);
        if (!both) return;
        gl.drawPreview();
        if (recording && recorder != null && gl.hasEncoder()) gl.drawEncoder(System.nanoTime() - recStartNs, recW, recH);
    }

    // ---------------------------------------------------------------- 拍照 / 录像

    private File newFile(String ext) {
        return new File(outDir(this), "澄影双摄_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + (100 + new Random().nextInt(900)) + "." + ext);
    }

    private void shutterTapped() {
        if (!glReady || gl == null) return;
        if (!videoMode) takePhoto(); else if (recording) stopRecording(true); else startRecording();
    }

    private void takePhoto() {
        final File f = newFile("jpg");
        glH.post(new Runnable() { @Override public void run() {
            try {
                if (!gl.update(0) | !gl.update(1)) { /* 没拿到新帧也继续用上一帧 */ }
                Bitmap b = gl.capture(1080, 1920);
                try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 94, o); }
                b.recycle();
                store(f, false);
            } catch (Throwable t) { say("拍照失败：" + t.getMessage()); }
        } });
    }

    private void startRecording() {
        if (state.style == DualState.STYLE_SPLIT) { recW = 720; recH = 1280; }
        final File f = newFile("mp4");
        glH.post(new Runnable() { @Override public void run() {
            try {
                DualRecorder r = new DualRecorder(f, recW, recH, checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                Surface in = r.start();
                gl.setEncoderSurface(in);
                recStartNs = System.nanoTime();
                recorder = r;
                recording = true;
                final boolean audio = r.hasAudio;
                recFile = f;
                ui.post(new Runnable() { @Override public void run() {
                    paint(); timer.setVisibility(View.VISIBLE); tick();
                    say(audio ? "开始录像（带声音）" : "开始录像（没有麦克风权限，只录画面）");
                } });
            } catch (Throwable t) { recording = false; say("无法开始录像：" + t.getMessage()); }
        } });
    }
    private File recFile;

    private void tick() {
        if (!recording) { timer.setVisibility(View.GONE); return; }
        long s = (System.nanoTime() - recStartNs) / 1000000000L;
        timer.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 500);
    }

    private void stopRecording(final boolean saveIt) {
        if (!recording) return;
        recording = false;
        final DualRecorder r = recorder; final File f = recFile;
        recorder = null;
        ui.post(new Runnable() { @Override public void run() { paint(); timer.setVisibility(View.GONE); } });
        pending++;
        glH.post(new Runnable() { @Override public void run() {
            try { gl.setEncoderSurface(null); } catch (Throwable ignore) {}
            boolean ok = r != null && r.stop();
            if (ok) store(f, true); else { say("录像失败：" + (r == null ? "" : r.error)); f.delete(); }
            pending--;
        } });
    }

    /** 存进系统相册 + 记入清单（后台线程）。 */
    private void store(final File f, final boolean video) {
        pending++;
        new Thread(new Runnable() { @Override public void run() {
            MediaSaver.Result r = MediaSaver.save(DualCameraActivity.this, f, f.getName(), video ? "video/mp4" : "image/jpeg");
            try {
                JSONObject o = new JSONObject();
                o.put("kind", video ? "video" : "photo");
                o.put("name", f.getName());
                o.put("url", "https://" + MainActivity.HOST + "/__dual/" + java.net.URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20"));
                o.put("saved", r.ok);
                synchronized (items) { items.put(o); }
            } catch (Exception ignore) {}
            final int n; synchronized (items) { n = items.length(); }
            say(r.ok ? (video ? "视频已存入系统相册" : "照片已存入系统相册") : "没存进系统相册：" + r.error + "（仍会导入澄影相册）");
            ui.post(new Runnable() { @Override public void run() { countBadge.setText(String.valueOf(n)); } });
            pending--;
        } }).start();
    }

    // ---------------------------------------------------------------- 关闭 / 结果

    private void closeTapped() {
        if (recording) stopRecording(true);
        finishWhenIdle();
    }

    private void finishWhenIdle() {
        if (pending > 0) { ui.postDelayed(new Runnable() { @Override public void run() { finishWhenIdle(); } }, 200); return; }
        finishWith(null);
    }

    @Override public void onBackPressed() { closeTapped(); }

    private void fail(String code, String msg) {
        if (failedReported) return;
        failedReported = true;
        failCode = code; failMsg = msg;
        ui.post(new Runnable() { @Override public void run() { finishWhenIdle(); } });
    }

    private void finishWith(Object unused) {
        if (finished) return;
        finished = true;
        try {
            JSONObject o = new JSONObject();
            boolean none; synchronized (items) { none = items.length() == 0; }
            if (failCode != null && none) {
                o.put("ok", false); o.put("code", failCode); o.put("error", failMsg);
            } else {
                o.put("ok", true);
                synchronized (items) { o.put("items", items); }
            }
            if (torchOn) try { cm.setTorchMode(backId, false); } catch (Throwable ignore) {}
            Intent i = new Intent();
            i.putExtra("result", o.toString());
            setResult(RESULT_OK, i);
        } catch (Exception ignore) {}
        finish();
    }
}
