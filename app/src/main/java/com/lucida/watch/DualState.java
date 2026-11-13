package com.lucida.watch;

/** 双摄的纯计算部分（不依赖 Android 类，可在电脑上单元测试）。 */
public class DualState {
    public static final int STYLE_PIP = 0, STYLE_SPLIT = 1;
    public int style = STYLE_PIP;
    public boolean backIsMain = true;
    public boolean mirrorFront = true;
    public int rotFix = 0;          // 手动方向修正（×90°），应对个别机型的传感器方向报错
    public float pipX = 1f, pipY = 0f, pipW = 0.32f;

    /** 返回 {x, y, w, h}（0…1，左上为原点）：index 0 = 主画面，1 = 副画面。 */
    public float[] rect(int which, float W, float H) {
        if (style == STYLE_SPLIT) return which == 0 ? new float[]{0, 0, 1, .5f} : new float[]{0, .5f, 1, .5f};
        if (which == 0) return new float[]{0, 0, 1, 1};
        float w = Math.round(W * pipW), h = Math.round(w * 4f / 3f), m = Math.round(W * 0.04f);
        float x = m + Math.max(0, W - w - 2 * m) * pipX, y = m + Math.max(0, H - h - 2 * m) * pipY;
        return new float[]{x / W, y / H, w / W, h / H};
    }

    /**
     * 把 SurfaceTexture 变换矩阵（列主序 4×4）拆成「顺时针转几度 + 是否左右镜像」。
     * 矩阵把 (s,t) 映射到纹理坐标；看单位向量的去向即可判断。返回 {rot, mirror}。
     */
    public static int[] matrixRotation(float[] m) {
        // 纹理坐标 (u,v) → (a,b)：a = m0*u + m4*v + m12，b = m1*u + m5*v + m13
        float ax = m[0], ay = m[1], bx = m[4], by = m[5];   // u 轴、v 轴在输出里的方向
        float det = ax * by - ay * bx;                        // 与基准（v 翻转）比较
        // 无旋转的基准矩阵是 diag(1,-1)（t = 1 - v），其行列式 = -1；行列式 > 0 说明多了一次镜像
        boolean mirror = det > 0;
        float rx = ax, ry = ay;                               // u 轴的方向
        if (mirror) { rx = -ax; ry = -ay; }                   // 先把镜像还原再判断旋转
        int rot;
        if (Math.abs(rx) >= Math.abs(ry)) rot = rx > 0 ? 0 : 180;
        else rot = ry > 0 ? 270 : 90;
        return new int[]{rot, mirror ? 1 : 0};
    }

    /** 相机图像需要顺时针转的总角度（不含矩阵自带的）：用来在着色器里补足。 */
    public static int extraRotation(int sensor, int dispDeg, boolean front, int matrixRot, int rotFix) {
        int need = front ? (sensor + dispDeg) % 360 : (sensor - dispDeg + 360) % 360;
        int total = (need + rotFix * 90) % 360;
        return ((total - matrixRot) % 360 + 360) % 360;
    }

    /** 矩阵自带镜像时，补足的旋转方向要反过来。 */
    public static int uvRotation(int extra, boolean matrixMirrored) {
        return matrixMirrored ? (360 - extra) % 360 : extra;
    }
}
