package com.example.evaoverlay;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Build;
import android.os.IBinder;
import android.view.*;
import java.net.*;

public class OverlayService extends Service {
    static final int PORT = 5005;
    private WindowManager wm;
    private PointerView view;
    private DatagramSocket sock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("eva", "EVA overlay",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "eva")
                .setContentTitle("EVA imleç çalışıyor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).build();
        startForeground(1, n);

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new PointerView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(view, lp);
        new Thread(this::listen).start();
    }

    private void listen() {
        try {
            sock = new DatagramSocket(PORT);          // 0.0.0.0: hem localhost hem ağ
            byte[] buf = new byte[64];
            while (!sock.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String[] a = new String(p.getData(), 0, p.getLength()).trim().split(",");
                float x = Float.parseFloat(a[0]);
                float y = Float.parseFloat(a[1]);
                float pr = a.length > 2 ? Float.parseFloat(a[2]) : 0f;
                view.update(x, y, pr);
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        if (sock != null) sock.close();
        if (view != null) wm.removeView(view);
        super.onDestroy();
    }

    static class PointerView extends View {
        volatile float x = -200, y = -200, pr = 0;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        PointerView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            fill.setColor(0x88FF3030);
            edge.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * d);
            ring.setColor(0xFF30FF60);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5 * d);
        }

        void update(float nx, float ny, float npr) {
            x = nx; y = ny; pr = npr;
            postInvalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);                 // pencere kayması varsa düzeltir
            float cx = x - loc[0], cy = y - loc[1];
            c.drawCircle(cx, cy, 14 * d, fill);
            c.drawCircle(cx, cy, 14 * d, edge);
            if (pr > 0f) {
                RectF r = new RectF(cx - 24 * d, cy - 24 * d, cx + 24 * d, cy + 24 * d);
                c.drawArc(r, -90, 360 * Math.min(pr, 1f), false, ring);
            }
        }
    }
}
