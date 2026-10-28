package com.example.evaoverlay;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraService extends Service {
    static final int PORT = 8081;
    private CameraDevice cam;
    private ImageReader reader;
    private HandlerThread ht;
    private ServerSocket server;
    private final Object lock = new Object();
    private byte[] latest;
    private long seq = 0;
    private volatile boolean alive = true;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("evacam", "EVA kamera",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "evacam")
                .setContentTitle("EVA kamera yayını açık")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(2, n);
        }
        ht = new HandlerThread("cam");
        ht.start();
        startServer();
        openCamera();
    }

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = null;
            for (String s : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(s).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) { id = s; break; }
            }
            if (id == null) return;

            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size sz : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (sz.getWidth() * 3 != sz.getHeight() * 4) continue;      // 4:3
                if (best == null || Math.abs(sz.getWidth() - 640) < Math.abs(best.getWidth() - 640))
                    best = sz;
            }
            if (best == null) best = map.getOutputSizes(ImageFormat.YUV_420_888)[0];

            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            Handler h = new Handler(ht.getLooper());
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    byte[] jpg = toJpeg(img);
                    synchronized (lock) { latest = jpg; seq++; lock.notifyAll(); }
                } finally { img.close(); }
            }, h);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    cam = d;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        d.createCaptureSession(Arrays.asList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try { s.setRepeatingRequest(b.build(), null, h); }
                                        catch (Exception ignored) { }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) { }
                                }, h);
                    } catch (Exception ignored) { }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); }
            }, h);
        } catch (SecurityException | CameraAccessException e) {
            stopSelf();
        }
    }

    private static byte[] toJpeg(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        byte[] nv = new byte[w * h * 3 / 2];
        ByteBuffer yb = p[0].getBuffer();
        int ys = p[0].getRowStride(), pos = 0;
        for (int r = 0; r < h; r++) { yb.position(r * ys); yb.get(nv, pos, w); pos += w; }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int us = p[1].getRowStride(), up = p[1].getPixelStride();
        int vs = p[2].getRowStride(), vp = p[2].getPixelStride();
        for (int r = 0; r < h / 2; r++)
            for (int c = 0; c < w / 2; c++) {
                nv[pos++] = vb.get(r * vs + c * vp);
                nv[pos++] = ub.get(r * us + c * up);
            }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new YuvImage(nv, ImageFormat.NV21, w, h, null)
                .compressToJpeg(new Rect(0, 0, w, h), 70, bo);
        return bo.toByteArray();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                while (alive) {
                    Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { }   // isteği oku
            OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; "
                    + "boundary=frame\r\nCache-Control: no-cache\r\n\r\n").getBytes());
            long last = -1;
            while (alive) {
                byte[] jpg;
                synchronized (lock) {
                    while (alive && seq == last) lock.wait(1000);
                    jpg = latest; last = seq;
                }
                if (jpg == null) continue;
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + jpg.length + "\r\n\r\n").getBytes());
                out.write(jpg);
                out.write("\r\n".getBytes());
                out.flush();
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        alive = false;
        synchronized (lock) { lock.notifyAll(); }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (cam != null) cam.close();
        if (reader != null) reader.close();
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }
}
