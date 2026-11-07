package com.joassam.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** 메인폰: 화면에서 티맵 안내판 영역만 잘라 HUD폰으로 실시간 전송 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "stop";
    private static final String CH = "capture";
    private static final float SCALE = 0.6f; // 캡처 해상도 배율 (속도/화질 균형)

    static volatile boolean running = false;
    static volatile Bitmap preview;      // 설정 화면 미리보기용
    static volatile String status = "";
    static volatile Bitmap snapshot;     // 영역 설정용: 이 앱이 안 보일 때 찍은 전체 화면(축소)
    private long lastSnap = 0;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private long lastSent = 0;
    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs = getSharedPreferences("hud", MODE_PRIVATE);
        startAsForeground();

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            status = "화면 캡처 시작 실패: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null) {
            status = "화면 캡처 권한이 거부됨";
            stopSelf();
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("capture");
        thread.start();
        bg = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, bg);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(1, (int) (dm.widthPixels * SCALE));
        int h = Math.max(1, (int) (dm.heightPixels * SCALE));
        int dpi = Math.max(1, (int) (dm.densityDpi * SCALE));

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, bg);
        display = projection.createVirtualDisplay("joahud", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);

        running = true;
        status = "전송 중 (" + w + "x" + h + ")";
        return START_NOT_STICKY;
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            int fps = Math.max(1, prefs.getInt("fps", 8));
            long now = System.currentTimeMillis();
            if (now - lastSent < 1000 / fps) return;
            lastSent = now;

            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pixelStride = p.getPixelStride();
            int rowStride = p.getRowStride();
            int iw = img.getWidth(), ih = img.getHeight();
            int padded = rowStride / pixelStride;
            Bitmap full = Bitmap.createBitmap(padded, ih, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);

            if (!MainActivity.visible && now - lastSnap > 1000) {
                lastSnap = now;
                Bitmap whole = Bitmap.createBitmap(full, 0, 0, iw, ih);
                snapshot = Bitmap.createScaledBitmap(whole, 360, Math.max(1, ih * 360 / iw), true);
                if (whole != full) whole.recycle();
            }

            Bitmap crop = cropAndClean(full, iw, ih);
            full.recycle();
            if (crop == null) return;

            byte[] jpg = toJpeg(crop);
            preview = crop;
            if (jpg != null) UdpSender.sendBytes(jpg);
        } catch (Throwable t) {
            status = "캡처 오류: " + t.getMessage();
        } finally {
            if (img != null) img.close();
        }
    }

    /** 설정한 영역(%)만 자르고, 어두운 배경은 완전 검정으로 (앞유리 뿌옇게 비치는 것 방지) */
    private Bitmap cropAndClean(Bitmap full, int iw, int ih) {
        boolean land = iw > ih;
        String k = land ? "land_" : "port_";
        int[] def = land ? CropPresets.LAND_DEFAULT : CropPresets.PORT_DEFAULT;
        int l = prefs.getInt(k + "l", def[0]);
        int t = prefs.getInt(k + "t", def[1]);
        int rr = prefs.getInt(k + "r", def[2]);
        int b = prefs.getInt(k + "b", def[3]);
        int x0 = iw * Math.min(l, 99) / 100, y0 = ih * Math.min(t, 99) / 100;
        int x1 = Math.max(x0 + 1, iw * Math.max(rr, l + 1) / 100);
        int y1 = Math.max(y0 + 1, ih * Math.max(b, t + 1) / 100);
        x1 = Math.min(x1, iw);
        y1 = Math.min(y1, ih);
        int cw = x1 - x0, chh = y1 - y0;
        if (cw < 4 || chh < 4) return null;

        // 최대 가로 720px
        Bitmap c = Bitmap.createBitmap(full, x0, y0, cw, chh);
        if (cw > 720) {
            Bitmap s = Bitmap.createScaledBitmap(c, 720, chh * 720 / cw, true);
            c.recycle();
            c = s;
        } else if (!c.isMutable()) {
            Bitmap m = c.copy(Bitmap.Config.ARGB_8888, true);
            c.recycle();
            c = m;
        }

        int th = prefs.getInt("black", 70); // 0~255, 이보다 어두우면 검정
        if (th > 0) {
            int w = c.getWidth(), h = c.getHeight();
            int[] px = new int[w * h];
            c.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                int lum = (((v >> 16) & 0xff) * 3 + ((v >> 8) & 0xff) * 6 + (v & 0xff)) / 10;
                if (lum < th) px[i] = 0xff000000;
            }
            if (!c.isMutable()) {
                Bitmap m = c.copy(Bitmap.Config.ARGB_8888, true);
                c.recycle();
                c = m;
            }
            c.setPixels(px, 0, w, 0, 0, w, h);
        }
        return c;
    }

    private byte[] toJpeg(Bitmap b) {
        int q = 75;
        while (q >= 30) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, q, out);
            if (out.size() < 60000) return out.toByteArray();
            q -= 15;
        }
        return null;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "HUD 화면 전송", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getService(this, 1, stop, piFlags);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = nb.setContentTitle("조아 HUD — 티맵 화면 전송 중")
                .setContentText("눌러서 중지")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        status = "중지됨";
        try {
            if (display != null) display.release();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
