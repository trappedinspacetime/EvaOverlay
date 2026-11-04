package com.example.evaoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;

public class PointerService extends AccessibilityService {
    static final int SOUND_PORT = 5007;
    private WindowManager wm;
    private DotView dot;
    private WindowManager.LayoutParams lp;
    private DatagramSocket sock;
    private DatagramSocket soundSock;
    private SoundPool pool;
    private int clickId;
    private volatile boolean clickReady = false;
    private volatile boolean alive = true;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private volatile float tx, ty, tp;
    private int half;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (64 * d);
        half = size / 2;
        dot = new DotView(this, d);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -size;
        lp.y = -size;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(dot, lp);

        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(aa).build();
        pool.setOnLoadCompleteListener((sp, id, status) -> { if (status == 0) clickReady = true; });
        clickId = pool.load(this, R.raw.click, 1);

        new Thread(this::listen).start();
        new Thread(this::listenSound).start();
    }

    private void listen() {
        while (alive) {
            try {
                sock = new DatagramSocket(OverlayService.PORT);
                byte[] buf = new byte[64];
                while (alive && !sock.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength()).trim();
                    if (msg.startsWith("ping")) {
                        byte[] r = "pong".getBytes();
                        sock.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
                        continue;
                    }
                    try {
                        String[] a = msg.split(",");
                        tx = Float.parseFloat(a[0]);
                        ty = Float.parseFloat(a[1]);
                        tp = a.length > 2 ? Float.parseFloat(a[2]) : 0f;
                    } catch (RuntimeException bad) {
                        continue;
                    }
                    push();
                }
            } catch (Exception e) {
                try { if (sock != null) sock.close(); } catch (Exception ignored) { }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void listenSound() {
        while (alive) {
            try {
                soundSock = new DatagramSocket(SOUND_PORT);
                byte[] buf = new byte[16];
                while (alive && !soundSock.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    soundSock.receive(p);
                    if (clickReady && pool != null) {
                        pool.play(clickId, 1f, 1f, 1, 0, 1f);
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void push() {
        if (!pending.compareAndSet(false, true)) return;
        ui.post(() -> {
            pending.set(false);
            if (dot == null) return;
            lp.x = (int) tx - half;
            lp.y = (int) ty - half;
            dot.setProgress(tp);
            try { wm.updateViewLayout(dot, lp); } catch (Exception ignored) { }
        });
    }

    private void cleanup() {
        alive = false;
        if (sock != null) sock.close();
        if (soundSock != null) soundSock.close();
        if (pool != null) { pool.release(); pool = null; }
        if (dot != null && wm != null) {
            try { wm.removeView(dot); } catch (Exception ignored) { }
            dot = null;
        }
    }

    @Override public boolean onUnbind(Intent i) { cleanup(); return super.onUnbind(i); }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }

    static class DotView extends View {
        float pr = 0f;
        final float d;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotView(Context c, float density) {
            super(c);
            d = density;
            fill.setColor(0x88FF3030);
            edge.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * d);
            ring.setColor(0xFF30FF60);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5 * d);
        }

        void setProgress(float p) {
            if (p != pr) { pr = p; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            c.drawCircle(cx, cy, 14 * d, fill);
            c.drawCircle(cx, cy, 14 * d, edge);
            if (pr > 0f) {
                RectF r = new RectF(cx - 24 * d, cy - 24 * d, cx + 24 * d, cy + 24 * d);
                c.drawArc(r, -90, 360 * Math.min(pr, 1f), false, ring);
            }
        }
    }
}
