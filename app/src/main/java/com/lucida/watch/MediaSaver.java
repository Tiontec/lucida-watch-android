package com.lucida.watch;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 把文件存进系统相册（图片 / 视频）或下载目录（其它）。Android 10+ 用 MediaStore，之前直接写公共目录并通知媒体库。 */
public final class MediaSaver {
    public static final class Result { public boolean ok; public String name, where, error; public Uri uri; }

    private MediaSaver() {}

    public static boolean isImage(String mime, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return (mime != null && mime.startsWith("image/")) || n.matches(".*\\.(jpe?g|png|webp|gif|heic)$");
    }
    public static boolean isVideo(String mime, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return (mime != null && mime.startsWith("video/")) || n.matches(".*\\.(mp4|mov|webm|mkv|3gp)$");
    }

    public static Result save(Context c, File src, String name, String mime) {
        Result r = new Result();
        r.name = name;
        try {
            if (mime == null || mime.isEmpty()) mime = guess(name);
            boolean img = isImage(mime, name), vid = isVideo(mime, name);
            String dir = img ? Environment.DIRECTORY_PICTURES : vid ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
            String sub = (img || vid) ? "/澄影" : "";
            r.where = img ? "系统相册（图片/澄影）" : vid ? "系统相册（影片/澄影）" : "下载目录";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + sub);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri base = img ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : vid ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri u = cr.insert(base, v);
                if (u == null) throw new Exception("系统拒绝创建文件");
                try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(u)) {
                    copy(in, out);
                } catch (Exception e) { cr.delete(u, null, null); throw e; }
                ContentValues d = new ContentValues();
                d.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, d, null, null);
                r.uri = u;
            } else {
                File folder = new File(Environment.getExternalStoragePublicDirectory(dir), sub.isEmpty() ? "" : "澄影");
                if (!folder.exists() && !folder.mkdirs()) throw new Exception("无法创建目录 " + folder);
                File dst = new File(folder, name);
                for (int i = 1; dst.exists(); i++) {
                    int dot = name.lastIndexOf('.');
                    dst = new File(folder, dot > 0 ? name.substring(0, dot) + "_" + i + name.substring(dot) : name + "_" + i);
                }
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { copy(in, out); }
                android.media.MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
                r.name = dst.getName();
            }
            r.ok = true;
        } catch (Exception e) {
            r.ok = false;
            r.error = String.valueOf(e.getMessage());
        }
        return r;
    }

    static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] b = new byte[64 * 1024];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        out.flush();
    }

    public static String guess(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
