package com.termuxbridge.audiobridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_PROJ = 1001;
    private static final int REQ_MIC = 99;
    private static final int REQ_NOTIF = 98;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (!hasMicPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        if (!hasNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } else {
                askProjection();
            }
            return;
        }

        askProjection();
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);

        boolean granted = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;

        if (req == REQ_MIC) {
            if (granted) {
                if (hasNotificationPermission()) {
                    askProjection();
                } else if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    askProjection();
                }
            } else {
                Toast.makeText(this, "Izin mikrofon ditolak. AudioBridge tidak bisa jalan.", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (req == REQ_NOTIF) {
            askProjection();
        }
    }

    private void askProjection() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_PROJ) {
            if (res == RESULT_OK && data != null) {
                Intent svc = new Intent(this, CaptureService.class);
                svc.putExtra("code", res);
                svc.putExtra("data", data);
                startForegroundService(svc);
                Toast.makeText(this, "AudioBridge dimulai. Cek notifikasi.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Izin capture ditolak/batal.", Toast.LENGTH_SHORT).show();
            }
        }

        finish();
    }
}
