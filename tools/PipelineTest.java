package com.lucida.watch;

/**
 * 把 GlComposer.buildUvMatrix + 着色器里的坐标变换用纯 Java 复刻一遍，检查：
 * 无论 SurfaceTexture 变换矩阵自己转了 0/90/180/270，也无论屏幕旋转、前后摄像头、手动方向修正、镜像开关，
 * 最终采样到的「原始相机图像坐标」都等于「把原始图像顺时针转 total 度（total = 应转角度 + 修正）、再居中裁切、再按需镜像」的结果。
 * （这只验证变换链自洽；真机上相机的 SENSOR_ORIENTATION / 矩阵是否如预期，要上真机才能确认。）
 */
public class PipelineTest {
    static int fails = 0, cases = 0;

    // ---- android.opengl.Matrix 的复刻（列主序）
    static void identity(float[] m) { for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1f : 0f; }
    static void translate(float[] m, float x, float y, float z) { for (int i = 0; i < 4; i++) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z; }
    static void scale(float[] m, float x, float y, float z) { for (int i = 0; i < 4; i++) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z; } }
    static void rotateZ(float[] m, float deg) {
        double r = Math.toRadians(deg); float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[] R = new float[16]; identity(R); R[0] = c; R[1] = s; R[4] = -s; R[5] = c;
        float[] o = new float[16]; mul(o, m, R); System.arraycopy(o, 0, m, 0, 16);
    }
    static void mul(float[] out, float[] a, float[] b) {
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { float s = 0; for (int k = 0; k < 4; k++) s += a[k * 4 + i] * b[j * 4 + k]; out[j * 4 + i] = s; }
    }
    static float[] apply(float[] m, float x, float y) { return new float[]{m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13]}; }

    // ---- GlComposer.buildUvMatrix 的复刻
    static float[] buildUv(DualState st, int cam, float[] tm, float dw, float dh, int[] sensor, int dispDeg, int[] bufW, int[] bufH) {
        int[] tr = DualState.matrixRotation(tm);
        int extra = DualState.extraRotation(sensor[cam], dispDeg, cam == 1, tr[0], st.rotFix);
        int total = (tr[0] + extra) % 360;
        float uw = (total == 90 || total == 270) ? bufH[cam] : bufW[cam];
        float uh = (total == 90 || total == 270) ? bufW[cam] : bufH[cam];
        float sa = uw / uh, da = dw / dh, fw = 1f, fh = 1f;
        if (sa > da) fw = da / sa; else fh = sa / da;
        boolean mirror = (cam == 1 && st.mirrorFront) ^ (tr[1] == 1);
        float[] t1 = new float[16], t2 = new float[16], id = new float[16], out = new float[16];
        identity(t1); translate(t1, 0.5f - fw / 2f, 0.5f - fh / 2f, 0f); scale(t1, fw, fh, 1f);
        if (mirror) {
            identity(t2); translate(t2, 0.5f, 0f, 0f); scale(t2, -1f, 1f, 1f); translate(t2, -0.5f, 0f, 0f);
            mul(id, t2, t1); System.arraycopy(id, 0, t1, 0, 16);
        }
        identity(t2); translate(t2, 0.5f, 0.5f, 0f); rotateZ(t2, extra); translate(t2, -0.5f, -0.5f, 0f);
        mul(out, t2, t1);
        return out;
    }

    static float[] osMatrix(int r) {   // 把原始图像顺时针转 r 度后「正着」显示的采样矩阵（含上下翻转），推导见注释
        float[] m = new float[16]; identity(m);
        switch (r) {
            case 0: m[0] = 1; m[5] = -1; m[13] = 1; break;                       // s=u, t=1-v
            case 90: m[0] = 0; m[4] = -1; m[12] = 1; m[1] = -1; m[5] = 0; m[13] = 1; break;   // s=1-v, t=1-u
            case 180: m[0] = -1; m[12] = 1; m[5] = 1; break;                     // s=1-u, t=v
            default: m[0] = 0; m[4] = 1; m[1] = 1; m[5] = 0; break;              // 270: s=v, t=u
        }
        return m;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    public static void main(String[] args) {
        int[][] sensors = {{90, 270}, {270, 90}, {90, 90}, {0, 0}, {180, 180}};
        int[] bw = {1280, 1280}, bh = {720, 720};
        float[][] rects = {{0, 0, 720, 1280}, {0, 0, 216, 288}, {0, 0, 1280, 720}, {0, 0, 360, 360}, {0, 0, 720, 640}};
        for (int[] sensor : sensors)
            for (int cam = 0; cam < 2; cam++)
                for (int disp = 0; disp < 360; disp += 90)
                    for (int osr = 0; osr < 360; osr += 90)
                        for (int fix = 0; fix < 4; fix++)
                            for (int mir = 0; mir < 2; mir++)
                                for (float[] r : rects) {
                                    DualState st = new DualState(); st.rotFix = fix; st.mirrorFront = mir == 1;
                                    float[] tm = osMatrix(osr);
                                    float[] uv = buildUv(st, cam, tm, r[2], r[3], sensor, disp, bw, bh);
                                    int need = cam == 1 ? (sensor[cam] + disp) % 360 : (sensor[cam] - disp + 360) % 360;
                                    int total = (need + fix * 90) % 360;
                                    float uw = (total == 90 || total == 270) ? bh[cam] : bw[cam], uh = (total == 90 || total == 270) ? bw[cam] : bh[cam];
                                    float sa = uw / uh, da = r[2] / r[3], fw = 1, fh = 1;
                                    if (sa > da) fw = da / sa; else fh = sa / da;
                                    boolean mirror = (cam == 1 && st.mirrorFront);
                                    for (float u = 0.05f; u < 1f; u += 0.3f)
                                        for (float v = 0.05f; v < 1f; v += 0.3f) {
                                            // 管线：aPos → uUv → uTexMat → 纹理坐标（t 从原始图像顶行算起）
                                            float[] p = apply(uv, u, v);
                                            float[] got = apply(tm, p[0], p[1]);
                                            // 期望：窗口 → 镜像 → 原始图像顺时针转 total 度的反变换
                                            float x1 = 0.5f - fw / 2f + fw * u, y1 = 0.5f - fh / 2f + fh * v;   // 窗口（v 向上）
                                            float x2 = mirror ? 1f - x1 : x1, yd = 1f - y1;                   // 转成 y 向下
                                            float a, b;
                                            switch (total) {
                                                case 0: a = x2; b = yd; break;
                                                case 90: a = yd; b = 1f - x2; break;
                                                case 180: a = 1f - x2; b = 1f - yd; break;
                                                default: a = 1f - yd; b = x2; break;
                                            }
                                            cases++;
                                            if (!near(got[0], a) || !near(got[1], b)) {
                                                fails++;
                                                if (fails <= 8) System.out.println("FAIL sensor=" + sensor[cam] + " cam=" + cam + " disp=" + disp + " os=" + osr + " fix=" + fix + " mir=" + mir
                                                        + " rect=" + r[2] + "x" + r[3] + " uv=(" + u + "," + v + ") got=(" + got[0] + "," + got[1] + ") want=(" + a + "," + b + ")");
                                            }
                                        }
                                }
        System.out.println(cases + " 个采样点，" + fails + " 个不一致");
        System.out.println(fails == 0 ? "ALL PASS" : "FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
