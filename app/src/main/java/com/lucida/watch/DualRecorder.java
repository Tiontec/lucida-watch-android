package com.lucida.watch;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/** H.264（Surface 输入）+ AAC（麦克风，可选）→ MP4。视频帧由 GlComposer 画到 inputSurface。 */
final class DualRecorder {
    final int w, h;
    private final File file;
    private final boolean wantAudio;
    private MediaCodec venc, aenc;
    private MediaMuxer mux;
    private Surface input;
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted, stopping, audioFailed;
    private final Object lock = new Object();
    private Thread vThread, aThread;
    private AudioRecord rec;
    long startNs;
    String error;
    boolean hasAudio;

    DualRecorder(File f, int w, int h, boolean audio) { file = f; this.w = w; this.h = h; wantAudio = audio; }

    Surface start() throws Exception {
        MediaFormat vf = MediaFormat.createVideoFormat("video/avc", w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, w * h * 5);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc = MediaCodec.createEncoderByType("video/avc");
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = venc.createInputSurface();
        venc.start();
        mux = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        if (wantAudio) {
            try {
                int rate = 44100;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("麦克风没有初始化");
                MediaFormat af = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                aenc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aenc.start();
                hasAudio = true;
            } catch (Throwable t) {
                hasAudio = false;
                if (rec != null) { try { rec.release(); } catch (Throwable ignore) {} rec = null; }
                if (aenc != null) { try { aenc.release(); } catch (Throwable ignore) {} aenc = null; }
            }
        }
        startNs = System.nanoTime();
        vThread = new Thread(new Runnable() { @Override public void run() { drain(venc, true); } }, "dual-venc");
        vThread.start();
        if (hasAudio) {
            rec.startRecording();
            aThread = new Thread(new Runnable() { @Override public void run() { audioLoop(); } }, "dual-aenc");
            aThread.start();
        }
        return input;
    }

    private void audioLoop() {
        ByteBuffer[] ins = aenc.getInputBuffers();
        byte[] buf = new byte[4096];
        long samples = 0;
        final int rate = 44100;
        while (!stopping) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) { if (n < 0) break; continue; }
            int idx = aenc.dequeueInputBuffer(10000);
            if (idx < 0) continue;
            ByteBuffer b = ins[idx];
            b.clear(); b.put(buf, 0, n);
            long pts = (startNs / 1000) + samples * 1000000L / rate;
            aenc.queueInputBuffer(idx, 0, n, pts - startNs / 1000, 0);
            samples += n / 2;
        }
        int idx;
        while ((idx = aenc.dequeueInputBuffer(20000)) < 0) { if (!stopping) break; }
        if (idx >= 0) aenc.queueInputBuffer(idx, 0, 0, samples * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(aenc, false);
    }

    private void drain(MediaCodec c, boolean video) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int i = c.dequeueOutputBuffer(bi, 10000);
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (stopping && video && stopSignalled) { /* 等 EOS */ }
                    continue;
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) {
                        int t = mux.addTrack(c.getOutputFormat());
                        if (video) vTrack = t; else aTrack = t;
                        if (vTrack >= 0 && (!hasAudio || aTrack >= 0)) { mux.start(); muxStarted = true; lock.notifyAll(); }
                        else while (!muxStarted && !abort) { try { lock.wait(200); } catch (InterruptedException e) { return; } }
                    }
                } else if (i >= 0) {
                    ByteBuffer out = c.getOutputBuffer(i);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && muxStarted) {
                        out.position(bi.offset); out.limit(bi.offset + bi.size);
                        synchronized (lock) { mux.writeSampleData(video ? vTrack : aTrack, out, bi); }
                    }
                    c.releaseOutputBuffer(i, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
        }
    }
    private volatile boolean stopSignalled, abort;

    /** 画完最后一帧后调用；阻塞到文件写完。返回 true = 文件可用。 */
    boolean stop() {
        stopping = true;
        stopSignalled = true;
        try { venc.signalEndOfInputStream(); } catch (Throwable t) { error = String.valueOf(t.getMessage()); }
        try { if (vThread != null) vThread.join(5000); } catch (InterruptedException ignore) {}
        try { if (aThread != null) aThread.join(5000); } catch (InterruptedException ignore) {}
        abort = true;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignore) {}
        boolean ok = muxStarted && error == null;
        try { if (muxStarted) mux.stop(); } catch (Throwable t) { ok = false; error = String.valueOf(t.getMessage()); }
        try { mux.release(); } catch (Throwable ignore) {}
        try { venc.stop(); venc.release(); } catch (Throwable ignore) {}
        try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Throwable ignore) {}
        try { if (input != null) input.release(); } catch (Throwable ignore) {}
        return ok && file.length() > 0;
    }
}
