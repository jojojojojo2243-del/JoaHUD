package com.joassam.hud;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 티맵이 화면 위에 띄우는 '미니 안내(초록 안내판)'를 찾아서
 * 그 부분만 캡처 → 흰 글씨만 남겨 HUD 색으로 바꿔 → HUD 화면에 크게 반전 표시.
 * (안드로이드 오토 중에도 동작, 매번 화면공유 허락 필요 없음)
 */
public class TmapA11yService extends AccessibilityService {

    static final String TMAP = "com.skt.tmap.ku";
    static volatile boolean connected = false;
    static volatile String status = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private volatile boolean busy = false;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onServiceConnected() {
        connected = true;
        status = "연결됨";
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        connected = false;
        handler.removeCallbacks(loop);
        super.onDestroy();
    }

    private void tick() {
        if (!HudActivity.visible || busy) return;
        if (Build.VERSION.SDK_INT < 30) {
            status = "안드로이드 11 이상 필요";
            return;
        }
        final Rect r = findTmapWidget();
        if (r == null) {
            status = "티맵 미니 안내가 화면에 없음";
            return;
        }
        busy = true;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult res) {
                    work.execute(() -> {
                        try {
                            HardwareBuffer hb = res.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                            Bitmap crop = null;
                            if (hw != null) {
                                Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                                crop = process(full, r);
                                full.recycle();
                            }
                            hb.close();
                            if (crop != null) {
                                UdpSender.sendBytes(jpeg(crop));
                                status = "티맵 안내판 표시 중";
                            }
                        } catch (Throwable t) {
                            status = "처리 오류: " + t.getMessage();
                        } finally {
                            busy = false;
                        }
                    });
                }

                @Override
                public void onFailure(int code) {
                    busy = false;
                    status = "캡처 실패(" + code + ")";
                }
            });
        } catch (Throwable t) {
            busy = false;
            status = "캡처 불가: " + t.getMessage();
        }
    }

    /** 티맵 패키지의 작은 창(미니 안내) 위치 */
    private Rect findTmapWidget() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            long screen = (long) dm.widthPixels * dm.heightPixels;
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !TMAP.contentEquals(pkg)) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                long area = (long) b.width() * b.height();
                if (area > 0 && area < screen / 2) return b;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 안내판만 잘라서 흰색(화살표·숫자)만 남기고 나머지는 검정, 흰색은 HUD 색으로 */
    private Bitmap process(Bitmap full, Rect r) {
        int x0 = Math.max(0, r.left), y0 = Math.max(0, r.top);
        int x1 = Math.min(full.getWidth(), r.right), y1 = Math.min(full.getHeight(), r.bottom);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;
        Bitmap c = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0);
        if (c.getWidth() > 720) {
            Bitmap s = Bitmap.createScaledBitmap(c, 720, c.getHeight() * 720 / c.getWidth(), true);
            c.recycle();
            c = s;
        }
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        if (p.getBoolean("white_only", true)) {
            int color = HudActivity.COLORS[p.getInt("color", 0) % HudActivity.COLORS.length];
            int w = c.getWidth(), h = c.getHeight();
            int[] px = new int[w * h];
            c.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                int rr = (v >> 16) & 0xff, gg = (v >> 8) & 0xff, bb = v & 0xff;
                int min = Math.min(rr, Math.min(gg, bb));
                px[i] = min > 170 ? color : 0xff000000;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            c.recycle();
            c = out;
        }
        return c;
    }

    private byte[] jpeg(Bitmap b) {
        int q = 85;
        byte[] out = null;
        while (q >= 30) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, q, o);
            out = o.toByteArray();
            if (out.length < 60000) break;
            q -= 15;
        }
        return out;
    }
}
