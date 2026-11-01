package com.termuxbridge.audiobridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class CaptureService extends Service {
    private static final int PORT = 48210;
    private static final int LOGPORT = 48211;
    private volatile boolean running = true;
    private final StringBuffer log = new StringBuffer();

    private int lastCode = 0;
    private Intent lastData = null;
    private ServerSocket audioServer = null;
    private AudioRecord recorder = null;
    private MediaProjection projection = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            lastCode = intent.getIntExtra("code", 0);
            lastData = intent.getParcelableExtra("data");
        }
        startForeground(1, makeNotif(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        new Thread(this::logServer, "logdump").start();
        new Thread(this::boot, "bridge").start();
        return START_NOT_STICKY;
    }

    private void L(String s) {
        log.append(s).append('\n');
    }

    private void boot() {
        L("=== AudioBridge v2 ===");
        L("model=" + Build.MODEL + " brand=" + Build.BRAND + " sdk=" + Build.VERSION.SDK_INT);

        try { micSelfTest(); } catch (Throwable t) { L("MIC ERR " + t); }

        try {
            if (lastData == null) { L("PROJECTION NULL -> consent ilang, buka app lagi & Allow"); return; }
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(lastCode, lastData);
            if (projection == null) { L("PROJECTION NULL -> consent ilang, buka app lagi & Allow"); return; }
            L("projection OK");

            AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();

            int rate = 48000;
            int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            L("minBuf(stereo)=" + minBuf);

            recorder = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setBufferSizeInBytes(Math.max(Math.abs(minBuf) * 4, 131072))
                .build();

            L("rec.state=" + recorder.getState() + " (1=INITIALIZED)");
            recorder.startRecording();
            L("rec.recstate=" + recorder.getRecordingState() + " (2=RECORDING)");

            audioServer = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"));
            L("listening :" + PORT);

            byte[] buf = new byte[32768];
            int chunks = 0, peakAll = 0;

            while (running) {
                Socket client = audioServer.accept();
                L("client masuk");
                try {
                    OutputStream out = client.getOutputStream();
                    while (running) {
                        int n = recorder.read(buf, 0, buf.length);
                        if (n <= 0) { L("read=" + n + " -> putus, tunggu client baru"); break; }
                        int peak = 0;
                        for (int i = 0; i + 1 < n; i += 2) {
                            int v = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
                            if (v < 0) v = -v;
                            if (v > peak) peak = v;
                        }
                        if (peak > peakAll) peakAll = peak;
                        if (chunks < 8) L("chunk" + chunks + " n=" + n + " peak=" + peak);
                        chunks++;
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (IOException e) {
                    L("client io: " + e.getMessage());
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
            }
            L("END peakMax=" + peakAll + " chunks=" + chunks);
        } catch (Throwable t) {
            L("FATAL " + t);
            for (StackTraceElement e : t.getStackTrace()) L("   at " + e);
        } finally {
            cleanup();
        }
    }

    private void micSelfTest() {
        int rate = 48000;
        int minb = AudioRecord.getMinBufferSize(rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mr = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(Math.abs(minb) * 2, 16384));
        L("mic.state=" + mr.getState());
        mr.startRecording();
        byte[] b = new byte[8192];
        int max = 0;
        long t0 = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - t0 < 1200) {
            int n = mr.read(b, 0, b.length);
            if (n <= 0) break;
            for (int i = 0; i + 1 < n; i += 2) {
                int v = (short) ((b[i + 1] << 8) | (b[i] & 0xFF));
                if (v < 0) v = -v;
                if (v > max) max = v;
            }
        }
        try { mr.stop(); } catch (Exception ignored) {}
        mr.release();
        L("mic.peak=" + max + " (>0 = mic hidup, =0 = mic mati/blokir)");
    }

    private void logServer() {
        try (ServerSocket ds = new ServerSocket(LOGPORT, 4, InetAddress.getByName("127.0.0.1"))) {
            while (running) {
                try (Socket c = ds.accept()) {
                    c.getOutputStream().write(log.toString().getBytes());
                    c.getOutputStream().flush();
                }
            }
        } catch (Exception ignored) {}
    }

    private Notification makeNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bridge", "AudioBridge",
            NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "bridge")
            .setContentTitle("AudioBridge v2")
            .setContentText("port " + PORT + " audio | " + LOGPORT + " log")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build();
    }

    private void cleanup() {
        try { if (audioServer != null) audioServer.close(); } catch (Exception ignored) {}
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
