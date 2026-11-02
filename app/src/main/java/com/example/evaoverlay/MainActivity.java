package com.example.evaoverlay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 80, 40, 40);

        Button start = new Button(this);
        start.setText("Başlat");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                startForegroundService(new Intent(this, OverlayService.class));
            }
        });

        Button stop = new Button(this);
        stop.setText("Durdur");
        stop.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));

        Button camOn = new Button(this);
        camOn.setText("Kamera başlat");
        camOn.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
            } else {
                startForegroundService(new Intent(this, CameraService.class));
            }
        });

        Button camOff = new Button(this);
        camOff.setText("Kamera durdur");
        camOff.setOnClickListener(v -> stopService(new Intent(this, CameraService.class)));

        l.addView(start);
        l.addView(stop);
        l.addView(camOn);
        l.addView(camOff);
        setContentView(l);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    // adb: am start -n .../.MainActivity --ez autostart true --es mode a11y
    private void handle(Intent in) {
        if (in == null || !in.getBooleanExtra("autostart", false)) return;
        in.removeExtra("autostart");
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, CameraService.class));
        }
        if ("overlay".equals(in.getStringExtra("mode")) && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, OverlayService.class));
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> moveTaskToBack(true), 800);
    }
}
