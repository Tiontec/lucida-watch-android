package com.lucida.watch;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 澄影安卓壳：全屏 WebView，加载 assets/web/index.html（虚拟源 https://lucida.local，所以 getUserMedia 可用）。
 * 手表版页面按 602:494 比例居中放置（屏幕比例相差 6% 以内就铺满；手机上会居中留黑边，不会拉伸变形）。
 * 接口约定见 BRIDGE.md。
 */
public class MainActivity extends Activity {
    static final String HOST = "lucida.local";
    private static final int REQ_PERM = 11, REQ_FILE = 12, REQ_DUAL = 13;

    private FrameLayout root, holder;
    private WebView web;
    private AndroidBridge bridge;
    private ValueCallback<Uri[]> fileCb;
    private long lastBack;
    private boolean pageStarted, dualOpen;
    private int kbLast = -1;
    private int holderTop, holderBottomPx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 键盘弹出时窗口不被缩小、网页不被挤压（网页容器是固定大小）；键盘高度另外量出来告诉网页
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        holder = new FrameLayout(this);
        holder.setBackgroundColor(Color.BLACK);
        root.addView(holder);
        layoutHolder();
        immersive();
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("这台设备没有可用的 WebView（系统组件被停用或没有安装），澄影无法启动。\n\n" + t.getMessage());
            tv.setTextColor(Color.WHITE);
            tv.setPadding(40, 40, 40, 40);
            root.addView(tv);
            return;
        }
        holder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setupWebView();
        watchKeyboard();
        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= 28 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) loadPage(); else requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    private boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) loadPage();      // 不管给不给，都进页面；页面自己处理没权限的情况
    }

    private void loadPage() {
        if (pageStarted || web == null) return;
        pageStarted = true;
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override protected void onResume() { super.onResume(); if (web != null) { web.onResume(); web.resumeTimers(); } immersive(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            holder.removeView(web);
            web.removeJavascriptInterface("LucidaAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); layoutHolder(); immersive(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** 网页容器按「整块屏幕」算并固定不动：键盘弹出时网页不会被挤压或顶起。 */
    @SuppressWarnings("deprecation")
    private void layoutHolder() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels;
        final float target = 602f / 494f;
        float ar = (float) W / H;
        int w = W, h = H;
        if (Math.abs(ar - target) / target > 0.06f) {
            if (ar > target) w = Math.round(H * target); else h = Math.round(W / target);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = (W - w) / 2;
        lp.topMargin = (H - h) / 2;
        holder.setLayoutParams(lp);
        holderTop = lp.topMargin;
        holderBottomPx = lp.topMargin + h;
    }

    // ---------------------------------------------------------------- 键盘

    @SuppressWarnings("deprecation")
    private void watchKeyboard() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { reportKeyboard(); }
        });
    }

    @SuppressWarnings("deprecation")
    private void reportKeyboard() {
        if (web == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Rect vis = new Rect();
        root.getWindowVisibleDisplayFrame(vis);
        int kb = Math.max(0, dm.heightPixels - vis.bottom);
        if (kb < dm.heightPixels * 0.15f) kb = 0;                         // 小于 15% 当作不是键盘（状态栏 / 导航栏）
        int overlap = kb == 0 ? 0 : Math.max(0, holderBottomPx - (dm.heightPixels - kb));   // 键盘盖住网页容器底部多少像素
        if (overlap == kbLast) return;
        kbLast = overlap;
        final float css = overlap / dm.density;
        js("window.__lucidaKb&&window.__lucidaKb(" + Math.round(css) + ")");
    }

    // ---------------------------------------------------------------- WebView

    @SuppressWarnings({"SetJavaScriptEnabled", "deprecation"})
    private void setupWebView() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // 页面里用户自己的 http:// AI / 局域网服务
        bridge = new AndroidBridge(this);
        String ua = s.getUserAgentString(), ver = "";
        if (ua != null) {
            int i = ua.indexOf("Chrome/");
            if (i >= 0) { int j = ua.indexOf(' ', i); ver = ua.substring(i + 7, j < 0 ? ua.length() : j); }
        }
        bridge.setWebviewVersion(ver);
        web.addJavascriptInterface(bridge, "LucidaAndroid");
        web.setWebViewClient(new LucidaClient());
        web.setWebChromeClient(new LucidaChrome());
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String ua2, String cd, String mime, long len) { openExternal(url); }
        });
    }

    private final class LucidaClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return null;
            return serve(u);
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (HOST.equals(u.getHost())) return false;
            openExternal(u.toString());
            return true;
        }
    }

    private final class LucidaChrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest req) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (HOST.equals(req.getOrigin().getHost())) req.grant(req.getResources()); else req.deny();
                }
            });
        }
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                startActivityForResult(p.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCb = null;
                return false;
            }
            return true;
        }
    }

    /** 虚拟源：/ 下的文件来自 assets/web；/__dual/ 下的来自原生双摄的输出目录。支持 Range（视频要用）。 */
    private WebResourceResponse serve(Uri u) {
        String path = u.getPath() == null ? "/" : u.getPath();
        if (path.equals("/")) path = "/index.html";
        try {
            InputStream in;
            long len;
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (path.startsWith("/__dual/")) {
                File f = new File(DualCameraActivity.outDir(this), name);
                if (!f.isFile()) return notFound();
                in = new FileInputStream(f);
                len = f.length();
            } else {
                AssetManager am = getAssets();
                in = am.open("web" + path);
                len = -1;
            }
            WebResourceResponse r = new WebResourceResponse(mimeOf(name), "utf-8", in);
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "no-cache");
            if (len >= 0) h.put("Content-Length", String.valueOf(len));
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mimeOf(String n) {
        n = n.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "无法打开：" + url, Toast.LENGTH_SHORT).show(); }
    }

    void js(final String code) {
        ui.post(new Runnable() { @Override public void run() { if (web != null) web.evaluateJavascript(code, null); } });
    }

    // ---------------------------------------------------------------- 原生双摄

    String startDual(String mode) {
        if (dualOpen) return "err:原生双摄已经打开";
        if (!has(Manifest.permission.CAMERA)) return "err:没有相机权限";
        dualOpen = true;
        ui.post(new Runnable() { @Override public void run() { } });
        Intent i = new Intent(this, DualCameraActivity.class);
        i.putExtra("mode", mode);
        i.putExtra("sdkSave", true);
        try {
            startActivityForResult(i, REQ_DUAL);
        } catch (Exception e) { dualOpen = false; return "err:" + e.getMessage(); }
        return "ok";
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        if (code == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        } else if (code == REQ_DUAL) {
            dualOpen = false;
            String json = data != null ? data.getStringExtra("result") : null;
            if (json == null) json = "{\"ok\":true,\"items\":[]}";
            js("window.__lucidaDualDone&&window.__lucidaDualDone(" + json + ")");
        }
    }

    // ---------------------------------------------------------------- 返回键

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK && web != null) {
            web.evaluateJavascript("(function(){try{return window.__lucidaBack?window.__lucidaBack():false}catch(e){return false}})()", new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if ("true".equals(v)) return;
                    long now = System.currentTimeMillis();
                    if (now - lastBack < 2000) finish();
                    else { lastBack = now; Toast.makeText(MainActivity.this, "再按一次返回键退出澄影", Toast.LENGTH_SHORT).show(); }
                }
            });
            return true;
        }
        return super.onKeyDown(k, e);
    }
}
