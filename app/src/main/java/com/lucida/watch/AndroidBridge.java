package com.lucida.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网页 ↔ 原生接口（window.LucidaAndroid）。所有方法都在 WebView 的后台线程被调用，返回 "ok" 或 "err:原因"。契约见 BRIDGE.md。 */
public class AndroidBridge {
    private final MainActivity act;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, FileOutputStream> open = new HashMap<>();
    private final Map<String, File> files = new HashMap<>();
    private final Map<String, String[]> meta = new HashMap<>();
    private final Map<String, Integer> next = new HashMap<>();
    private String webview = "";

    AndroidBridge(MainActivity a) { act = a; }
    void setWebviewVersion(String v) { webview = v == null ? "" : v; }

    private File tmpDir() { File d = new File(act.getCacheDir(), "save"); d.mkdirs(); return d; }

    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("platform", "android");
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("webview", webview);
            o.put("dual", dualInfo());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** 有没有前后摄像头；系统（API 30+）能不能让它们同时工作。老系统没法提前问，只能等真正打开时才知道。 */
    private JSONObject dualInfo() throws Exception {
        JSONObject d = new JSONObject();
        boolean back = false, front = false;
        Boolean concurrent = null;
        try {
            CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
            String backId = null, frontId = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f == null) continue;
                if (f == CameraCharacteristics.LENS_FACING_BACK && backId == null) { back = true; backId = id; }
                if (f == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) { front = true; frontId = id; }
            }
            if (back && front && Build.VERSION.SDK_INT >= 30) {
                concurrent = false;
                for (Set<String> s : cm.getConcurrentCameraIds()) if (s.contains(backId) && s.contains(frontId)) concurrent = true;
            }
        } catch (Throwable ignore) {}
        d.put("ok", back && front);
        if (!(back && front)) d.put("why", "没有找到前置或后置摄像头");
        if (concurrent != null) d.put("concurrent", concurrent.booleanValue());
        return d;
    }

    // ---------------------------------------------------------------- 保存（分块）

    @JavascriptInterface
    public String beginSave(String id, String name, String mime) {
        try {
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) return "err:参数不对";
            abortSave(id);
            File f = new File(tmpDir(), "s_" + id.replaceAll("[^0-9a-zA-Z-]", "") + ".part");
            open.put(id, new FileOutputStream(f));
            files.put(id, f);
            meta.put(id, new String[]{name.replaceAll("[\\\\/:*?\"<>|]", "_"), mime == null ? "" : mime});
            next.put(id, 0);
            return "ok";
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }

    @JavascriptInterface
    public synchronized String chunk(String id, int index, String b64) {
        try {
            FileOutputStream o = open.get(id);
            if (o == null) return "err:没有这个保存任务";
            if (index != next.get(id)) return "err:分块顺序不对";
            o.write(Base64.decode(b64, Base64.DEFAULT));
            next.put(id, index + 1);
            return "ok";
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }

    @JavascriptInterface
    public String endSave(final String id) {
        final FileOutputStream o = open.remove(id);
        final File f = files.remove(id);
        final String[] m = meta.remove(id);
        next.remove(id);
        if (o == null || f == null || m == null) return "err:没有这个保存任务";
        try { o.close(); } catch (IOException e) { return "err:" + e.getMessage(); }
        io.execute(new Runnable() {
            @Override public void run() {
                MediaSaver.Result r = MediaSaver.save(act, f, m[0], m[1]);
                f.delete();
                try {
                    JSONObject j = new JSONObject();
                    j.put("ok", r.ok);
                    if (r.ok) { j.put("name", r.name); j.put("where", r.where); } else j.put("error", r.error);
                    act.js("window.__lucidaSaveDone&&window.__lucidaSaveDone(" + JSONObject.quote(id) + "," + j + ")");
                } catch (Exception ignore) {}
            }
        });
        return "ok";
    }

    @JavascriptInterface
    public String abortSave(String id) {
        try { FileOutputStream o = open.remove(id); if (o != null) o.close(); } catch (Exception ignore) {}
        File f = files.remove(id);
        if (f != null) f.delete();
        meta.remove(id); next.remove(id);
        return "ok";
    }

    // ---------------------------------------------------------------- 原生双摄

    @JavascriptInterface
    public String dualOpen(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String mode = o.optString("mode", "photo");
            return act.startDual(mode);
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }

    @JavascriptInterface
    public String dualClean(String json) {
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            File dir = DualCameraActivity.outDir(act);
            File[] all = dir.listFiles();
            if (all == null) return "ok";
            for (File f : all) {
                boolean hit = a.length() == 0;
                for (int i = 0; i < a.length() && !hit; i++) hit = f.getName().equals(a.optString(i));
                if (hit) f.delete();
            }
            return "ok";
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }
}
