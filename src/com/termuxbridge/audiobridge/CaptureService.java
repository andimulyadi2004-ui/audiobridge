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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class CaptureService extends Service {
    private static final int PORT = 48210;
    private volatile boolean running = true;
    private AudioRecord recorder;
    private MediaProjection projection;
    private ServerSocket server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");

        startForeground(1, makeNotif(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        new Thread(this::loop, "bridge").start();
        return START_NOT_STICKY;
    }

    private void loop() {
        try {
            AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            int rate = 48000;
            int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            recorder = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(Math.max(minBuf * 2, 65536))
                .build();

            recorder.startRecording();
            server = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"));
            byte[] buf = new byte[16384];

            while (running) {
                Socket client = server.accept();
                try {
                    OutputStream out = client.getOutputStream();
                    while (running) {
                        int n = recorder.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (IOException e) {
                    // ffmpeg putus -> tunggu koneksi berikutnya
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Notification makeNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bridge", "AudioBridge",
            NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "bridge")
            .setContentTitle("AudioBridge aktif")
            .setContentText("Nangkep internal audio di 127.0.0.1:" + PORT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
        }
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
