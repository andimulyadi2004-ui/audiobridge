package com.termuxbridge.audiobridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    private static final int PORT = 48210;
    private static final int LOGPORT = 48211;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    private volatile boolean running = true;
    private final StringBuffer log = new StringBuffer();

    private int lastCode = 0;
    private Intent lastData = null;

    private ServerSocket audioServer = null;
    private ServerSocket logServerSocket = null;

    private AudioRecord recorder = null;
    private MediaProjection projection = null;

    private boolean captureStereo = false;
    private byte[] buf = new byte[32768];
    private byte[] monoBuf = new byte[65536];

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            lastCode = intent.getIntExtra("code", 0);
            lastData = intent.getParcelableExtra("data");
        }

        if (!ACTIVE.compareAndSet(false, true)) {
            L("service already active; start ignored. If stuck, Force Stop AudioBridge then open again.");
            return START_NOT_STICKY;
        }

        running = true;

        try {
            startForeground(
                1,
                makeNotif(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION |
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } catch (Throwable t) {
            L("startForeground typed failed: " + t);
            try {
                startForeground(1, makeNotif());
            } catch (Throwable t2) {
                L("startForeground fallback failed: " + t2);
            }
        }

        new Thread(this::logServer, "logdump").start();
        new Thread(this::boot, "bridge").start();

        return START_NOT_STICKY;
    }

    private void L(String s) {
        synchronized (log) {
            log.append(s).append('\n');
        }
    }

    private void boot() {
        try {
            L("=== AudioBridge v3.1 ===");
            L("model=" + Build.MODEL + " brand=" + Build.BRAND + " sdk=" + Build.VERSION.SDK_INT);

            boolean micPerm = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            L("RECORD_AUDIO=" + (micPerm ? "GRANTED" : "DENIED"));

            if (!micPerm) {
                L("Mic permission denied. Allow microphone for AudioBridge in Settings, then open app again.");
                stopSelf();
                return;
            }

            boolean micOk = micSelfTest();
            L("micOverall=" + (micOk ? "OK" : "ZERO/BLOCKED/NO_SOUND_DURING_TEST"));

            if (lastData == null) {
                L("No projection data. Open app and Allow capture.");
                stopSelf();
                return;
            }

            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

            projection = mpm.getMediaProjection(lastCode, lastData);
            if (projection == null) {
                L("MediaProjection null. Consent expired. Open app and Allow again.");
                stopSelf();
                return;
            }
            L("projection OK");

            try {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        L("MediaProjection onStop: consent revoked/stopped");
                        running = false;
                    }
                }, null);
            } catch (Throwable t) {
                L("registerCallback failed: " + t);
            }

            recorder = startPlaybackCapture(true);
            if (recorder == null) {
                L("stereo capture failed, trying mono...");
                recorder = startPlaybackCapture(false);
            }

            if (recorder == null) {
                L("Playback capture could not start.");
                stopSelf();
                return;
            }

            L("capture mode=" + (captureStereo ? "stereo downmixed to mono" : "mono"));

            audioServer = new ServerSocket();
            audioServer.setReuseAddress(true);

            try {
                audioServer.bind(
                    new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT),
                    5
                );
            } catch (BindException be) {
                L("PORT " + PORT + " already in use. Force stop AudioBridge, kill ffmpeg/rec2.sh, then open app again.");
                stopSelf();
                return;
            }

            L("listening :" + PORT);

            int chunks = 0;
            int peakAll = 0;
            int zeroStreak = 0;

            while (running) {
                Socket client;
                try {
                    client = audioServer.accept();
                } catch (java.net.SocketException se) {
                    if (!running) break;
                    throw se;
                }

                L("client connected");

                try {
                    OutputStream out = client.getOutputStream();

                    while (running) {
                        int n = recorder.read(buf, 0, buf.length);

                        if (n <= 0) {
                            L("read returned " + n + " (negative = AudioRecord error code)");
                            break;
                        }

                        byte[] writeBuf;
                        int writeLen;
                        int peak;

                        if (captureStereo) {
                            int frames = n / 4;
                            int need = frames * 2;

                            if (monoBuf.length < need) {
                                monoBuf = new byte[Math.max(need, monoBuf.length * 2)];
                            }

                            for (int i = 0, j = 0; i + 3 < n; i += 4, j += 2) {
                                int l = (short) (((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
                                int r = (short) (((buf[i + 3] & 0xFF) << 8) | (buf[i + 2] & 0xFF));
                                int m = (l + r) / 2;

                                monoBuf[j] = (byte) (m & 0xFF);
                                monoBuf[j + 1] = (byte) ((m >> 8) & 0xFF);
                            }

                            writeBuf = monoBuf;
                            writeLen = need;
                        } else {
                            writeBuf = buf;
                            writeLen = n;
                        }

                        peak = peak16(writeBuf, writeLen);

                        if (peak > peakAll) peakAll = peak;

                        if (peak == 0) {
                            zeroStreak++;
                        } else {
                            zeroStreak = 0;
                        }

                        if (chunks < 12 || chunks % 50 == 0) {
                            L("chunk" + chunks + " bytes=" + writeLen + " peak=" + peak + " zeroStreak=" + zeroStreak);
                        }

                        chunks++;
                        out.write(writeBuf, 0, writeLen);

                        if (zeroStreak > 25) {
                            L("WARNING: 25 consecutive silent chunks. Either no audible media, app blocks capture, or OEM blocks AudioPlaybackCapture.");
                            zeroStreak = 0;
                        }
                    }

                    out.flush();
                } catch (IOException e) {
                    L("client io: " + e.getMessage());
                } finally {
                    try {
                        client.close();
                    } catch (IOException ignored) {}
                }
            }

            L("END chunks=" + chunks + " peakMax=" + peakAll);
        } catch (Throwable t) {
            L("FATAL " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                L("   at " + e);
            }
        } finally {
            cleanup();
            ACTIVE.set(false);
        }
    }

    private int peak16(byte[] b, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int raw = ((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF);
            int v = (short) raw;
            if (v < 0) v = -v;
            if (v > peak) peak = v;
        }
        return peak;
    }

    private AudioRecord startPlaybackCapture(boolean wantStereo) {
        captureStereo = false;

        try {
            AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            int rate = 48000;
            int channel = wantStereo
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;

            int minBuf = AudioRecord.getMinBufferSize(
                rate,
                channel,
                AudioFormat.ENCODING_PCM_16BIT
            );

            L("try capture " + (wantStereo ? "stereo" : "mono") + " minBuf=" + minBuf);

            if (minBuf <= 0) {
                L("getMinBufferSize invalid for " + (wantStereo ? "stereo" : "mono"));
                return null;
            }

            AudioRecord r = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channel)
                    .build())
                .setBufferSizeInBytes(Math.max(minBuf * 4, 131072))
                .build();

            L("capture state=" + r.getState() + " (1=INITIALIZED)");

            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }

            r.startRecording();

            int rs = r.getRecordingState();
            L("capture recstate=" + rs + " (2=RECORDING, some OEM may use 3)");

            if (rs != AudioRecord.RECORDSTATE_RECORDING && rs != 3) {
                SystemClock.sleep(500);
                rs = r.getRecordingState();
                L("capture recstate after wait=" + rs);

                if (rs != AudioRecord.RECORDSTATE_RECORDING && rs != 3) {
                    try {
                        r.stop();
                    } catch (Exception ignored) {}
                    r.release();
                    return null;
                }
            }

            captureStereo = wantStereo;
            return r;
        } catch (Throwable t) {
            L("capture start error: " + t);
            return null;
        }
    }

    private boolean micSelfTest() {
        boolean any = false;

        int[] sources = new int[] {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        };

        String[] names = new String[] {
            "MIC",
            "VOICE_RECOGNITION",
            "CAMCORDER",
            "VOICE_COMMUNICATION"
        };

        for (int i = 0; i < sources.length; i++) {
            try {
                int rate = 48000;
                int minb = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                );

                AudioRecord mr = new AudioRecord(
                    sources[i],
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(Math.abs(minb) * 2, 16384)
                );

                int st = mr.getState();
                mr.startRecording();

                byte[] b = new byte[8192];
                int max = 0;
                long t0 = SystemClock.elapsedRealtime();

                while (SystemClock.elapsedRealtime() - t0 < 900) {
                    int n = mr.read(b, 0, b.length);
                    if (n <= 0) break;

                    int p = peak16(b, n);
                    if (p > max) max = p;
                }

                try {
                    mr.stop();
                } catch (Exception ignored) {}

                mr.release();

                L("mic[" + names[i] + "] state=" + st + " peak=" + max);

                if (max > 0) any = true;
            } catch (Throwable t) {
                L("mic[" + names[i] + "] error " + t);
            }
        }

        return any;
    }

    private void logServer() {
        try {
            logServerSocket = new ServerSocket();
            logServerSocket.setReuseAddress(true);
            logServerSocket.bind(
                new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOGPORT),
                4
            );

            while (running) {
                try (Socket c = logServerSocket.accept()) {
                    byte[] payload;
                    synchronized (log) {
                        payload = log.toString().getBytes();
                    }
                    c.getOutputStream().write(payload);
                    c.getOutputStream().flush();
                }
            }
        } catch (Exception ignored) {}
    }

    private Notification makeNotif() {
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.createNotificationChannel(
            new NotificationChannel("bridge", "AudioBridge", NotificationManager.IMPORTANCE_LOW)
        );

        return new Notification.Builder(this, "bridge")
            .setContentTitle("AudioBridge v3.1")
            .setContentText("port " + PORT + " audio | " + LOGPORT + " log")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();
    }

    private void cleanup() {
        try {
            if (audioServer != null) audioServer.close();
        } catch (Exception ignored) {}

        try {
            if (logServerSocket != null) logServerSocket.close();
        } catch (Exception ignored) {}

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }

        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {}
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        cleanup();
        ACTIVE.set(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
