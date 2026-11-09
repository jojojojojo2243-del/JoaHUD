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
    static volatile long lastAaData = 0;   // 안드로이드 오토 화면에서 읽은 마지막 시각
    private long lastLog = 0;
    private static final java.util.regex.Pattern DIST_ONLY =
            java.util.regex.Pattern.compile("^\\s*\\d+(?:[.,]\\d+)?\\s*(m|km)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);

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
        try {
            if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getPackageName() != null) {
                String pk = event.getPackageName().toString();
                // 키보드·시스템UI·티맵 미니 안내 같은 작은 창은 '앞 화면'으로 치지 않음
                if (!pk.equals("com.android.systemui") && !pk.contains("inputmethod") && !pk.contains("honeyboard")) {
                    if (!(pk.equals(TMAP) && findTmapWidget() != null && !tmapFull())) fgPkg = pk;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 지금 맨 앞에 떠 있는 앱 (티맵 화면을 보고 있는지 확인용) */
    static volatile String fgPkg = "";

    /** 티맵 본 화면이 전체 화면으로 떠 있는지 */
    private boolean tmapFull() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            long screen = (long) dm.widthPixels * dm.heightPixels;
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !TMAP.contentEquals(root.getPackageName())) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                if ((long) b.width() * b.height() >= screen / 2) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        // 1순위: 안드로이드 오토(차량 화면)에 그려진 티맵 안내 카드
        if (scanAndroidAuto()) return;
        // 2순위: 폰 화면 위 티맵 미니 안내판
        final Rect r = findTmapWidget();
        if (r == null) {
            status = "티맵 미니 안내 창을 찾는 중 (서브폰 티맵 길안내 → 홈 버튼)";
            long now = System.currentTimeMillis();
            if (now - lastWidgetLog > 60000) {
                lastWidgetLog = now;
                NavListenerService.log("서브폰 화면에서 티맵 미니 안내 창 없음 " + windowDiag());
            }
            return;
        }
        if (!widgetLogged) {
            widgetLogged = true;
            NavListenerService.log("티맵 미니 안내 창 찾음 " + r.left + "," + r.top + "~" + r.right + "," + r.bottom + " → HUD에 표시");
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

    /**
     * 차량 화면(안드로이드 오토는 폰 안의 별도 디스플레이)에서
     * '720m' 같은 거리 글자를 찾아 그 안내 카드를 읽고, 가능하면 캡처까지.
     */
    private boolean scanAndroidAuto() {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            StringBuilder diag = new StringBuilder();
            int aaDisplay = -1;
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                if (displayId == Display.DEFAULT_DISPLAY) continue;
                for (AccessibilityWindowInfo w : all.valueAt(i)) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    Rect wb = new Rect();
                    w.getBoundsInScreen(wb);
                    diag.append("[화면").append(displayId).append(' ').append(root.getPackageName()).append("] ");
                    if (root.getPackageName() != null && root.getPackageName().toString().contains("gearhead")) aaDisplay = displayId;
                    AccessibilityNodeInfo distNode = findDist(root, diag, 0);
                    if (distNode == null) continue;
                    // 거리 글자를 감싸는 안내 카드 범위 찾기
                    long winArea = Math.max(1L, (long) wb.width() * wb.height());
                    AccessibilityNodeInfo card = distNode;
                    Rect cardB = new Rect();
                    distNode.getBoundsInScreen(cardB);
                    AccessibilityNodeInfo p = distNode.getParent();
                    while (p != null) {
                        Rect b = new Rect();
                        p.getBoundsInScreen(b);
                        if ((long) b.width() * b.height() > winArea * 0.35) break;
                        card = p;
                        cardB = b;
                        p = p.getParent();
                    }
                    StringBuilder txt = new StringBuilder();
                    collect(card, txt, 0);
                    NavData d = new NavData();
                    d.pkg = "aa";
                    d.dist = NavParser.detectDist(java.util.Collections.singletonList(distNode.getText().toString()));
                    d.dir = NavParser.detectDir(txt.toString());
                    String rest = txt.toString().replace(distNode.getText().toString(), "").replace("|", " ").trim();
                    d.text = rest.length() > 30 ? rest.substring(0, 30) : rest;
                    UdpSender.send(d.toJson());
                    lastAaData = System.currentTimeMillis();
                    status = "차량 화면 안내 읽는 중: " + d.dist + " " + d.text;
                    captureDisplay(displayId, cardB);
                    return true;
                }
            }
            // 글자가 없으면(그림으로 그려짐) → 차량 화면을 캡처해서 초록 안내 카드를 자동으로 찾아 자름
            if (aaDisplay >= 0 && (!aaShotFailed || System.currentTimeMillis() - failedAt > 60000)) {
                aaShotFailed = false;
                if (System.currentTimeMillis() - lastLog > 60000) {
                    lastLog = System.currentTimeMillis();
                    NavListenerService.log("차량 화면 찾음(화면 " + aaDisplay + ") → 캡처 시도");
                }
                captureDisplay(aaDisplay, null);
                status = "차량 화면 캡처로 안내 카드 찾는 중";
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastLog > 15000 && diag.length() > 0) {
                lastLog = now;
                NavListenerService.log("차량화면 진단: " + (diag.length() > 600 ? diag.substring(0, 600) : diag));
            }
        } catch (Throwable t) {
            status = "차량 화면 읽기 오류: " + t.getMessage();
        }
        return false;
    }

    private AccessibilityNodeInfo findDist(AccessibilityNodeInfo n, StringBuilder diag, int depth) {
        if (n == null || depth > 40) return null;
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        if (t != null) {
            if (diag.length() < 600) diag.append(t).append(" / ");
            if (DIST_ONLY.matcher(t).matches()) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findDist(n.getChild(i), diag, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder out, int depth) {
        if (n == null || depth > 40) return;
        if (n.getText() != null) out.append(n.getText()).append(" | ");
        if (n.getContentDescription() != null) out.append(n.getContentDescription()).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private volatile boolean shotLogged = false;
    private volatile long lastShotLog = 0;

    /**
     * 티맵 안드로이드 오토 화면의 회전 안내 카드(짙은 초록 상자)를 찾는다.
     * 화면 위쪽 절반에서 초록색 픽셀이 모인 영역의 테두리를 구함.
     */
    private Rect findGuideCard(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(2, w / 320);
        int limitY = h * 3 / 5;
        int minX = w, minY = h, maxX = -1, maxY = -1, count = 0;
        for (int y = 0; y < limitY; y += step) {
            for (int x = 0; x < w; x += step) {
                int v = b.getPixel(x, y);
                int r = (v >> 16) & 0xff, g = (v >> 8) & 0xff, bl = v & 0xff;
                if (g > 45 && g > r + 18 && g >= bl) {
                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        int bw = maxX - minX, bh = maxY - minY;
        if (count < 40 || bw < w / 10 || bh < h / 12) return null;
        Rect r = new Rect();
        r.left = minX;
        r.top = minY;
        r.right = Math.min(w, maxX + step);
        r.bottom = Math.min(h, maxY + step);
        return r;
    }

    /** 화면이 전부 검정(보안 차단)인지 */
    private boolean isBlank(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), lit = 0, n = 0;
        for (int y = 0; y < h; y += 8) for (int x = 0; x < w; x += 8) {
            int v = b.getPixel(x, y);
            n++;
            if (((v >> 16) & 0xff) + ((v >> 8) & 0xff) + (v & 0xff) > 60) lit++;
        }
        return lit < n / 50;
    }

    private String colorInfo(Bitmap b) {
        if (isBlank(b)) return "[화면이 검정 — 보안 차단 가능성]";
        int v = b.getPixel(b.getWidth() / 6, b.getHeight() / 6);
        return "[왼쪽위 색 " + ((v >> 16) & 0xff) + "," + ((v >> 8) & 0xff) + "," + (v & 0xff) + "]";
    }

    /** 티맵 안드로이드 오토 화면의 회전 카드 기본 위치 (왼쪽 위) */
    private Rect fixedCard(Bitmap b) {
        Rect r = new Rect();
        r.left = (int) (b.getWidth() * 0.02f);
        r.top = (int) (b.getHeight() * 0.03f);
        r.right = (int) (b.getWidth() * 0.47f);
        r.bottom = (int) (b.getHeight() * 0.46f);
        return r;
    }

    private long lastDebugSave = 0;

    /** 확인용: 캡처한 차량 화면을 사진첩(Pictures/JoaHUD)에 저장 (1분에 1번) */
    private void saveDebug(Bitmap b) {
        long now = System.currentTimeMillis();
        if (now - lastDebugSave < 60000 || Build.VERSION.SDK_INT < 29) return;
        lastDebugSave = now;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "joahud_car_" + now + ".png");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JoaHUD");
            android.net.Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (u == null) return;
            try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            NavListenerService.log("확인용 차량 화면 사진 저장: 갤러리 → JoaHUD 앨범");
        } catch (Throwable t) {
            NavListenerService.log("확인용 사진 저장 실패: " + t.getMessage());
        }
    }

    private volatile long lastAaShot = 0;
    private volatile boolean aaShotFailed = false;
    private volatile long failedAt = 0;
    private volatile int failCount = 0;

    /** 차량 화면의 안내 카드 부분 캡처 (기기에 따라 막혀 있으면 글자만 표시) */
    private void captureDisplay(int displayId, Rect r) {
        long now = System.currentTimeMillis();
        // 안드로이드는 캡처 간격이 너무 짧으면 거부하므로 1.1초 간격
        if (aaShotFailed || busy || now - lastAaShot < 1100) return;
        lastAaShot = now;
        busy = true;
        try {
            takeScreenshot(displayId, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult res) {
                    work.execute(() -> {
                        try {
                            HardwareBuffer hb = res.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                            if (hw != null) {
                                Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                                Rect area = r != null ? r : findGuideCard(full);
                                String how = "자동";
                                if (area == null && !isBlank(full)) {
                                    // 초록 카드를 못 찾으면 티맵 안내 카드 기본 위치(왼쪽 위)를 사용
                                    area = fixedCard(full);
                                    how = "기본 위치";
                                }
                                if (area != null) {
                                    Bitmap crop = process(full, area);
                                    if (crop != null) {
                                        UdpSender.sendBytes(jpeg(crop));
                                        lastAaData = System.currentTimeMillis();
                                        status = "차량 화면 안내 카드 표시 중";
                                    }
                                } else if (isBlank(full)) {
                                    // 이 폰은 차량 화면을 검게 막음 → 10분 동안 캡처 중지(배터리 절약)
                                    aaShotFailed = true;
                                    failedAt = System.currentTimeMillis() + 9 * 60000L;
                                    status = "차량 화면 캡처가 보안으로 막힘 — 서브폰 티맵 단독 모드를 쓰세요";
                                } else {
                                    status = "차량 화면에서 초록 안내 카드 없음 (길안내 중인지 확인)";
                                }
                                if (!shotLogged || System.currentTimeMillis() - lastShotLog > 60000) {
                                    shotLogged = true;
                                    lastShotLog = System.currentTimeMillis();
                                    NavListenerService.log("차량 화면 캡처 성공 " + full.getWidth() + "x" + full.getHeight()
                                            + " " + colorInfo(full)
                                            + (area != null ? " 카드(" + how + ") " + area.left + "," + area.top + "~" + area.right + "," + area.bottom : " 카드 못 찾음"));
                                    saveDebug(full);
                                }
                                full.recycle();
                            }
                            hb.close();
                        } catch (Throwable ignored) {
                        } finally {
                            busy = false;
                        }
                    });
                }

                @Override
                public void onFailure(int code) {
                    busy = false;
                    if (code == 3) return; // 간격이 짧아서 거부 → 다음에 다시
                    failCount++;
                    NavListenerService.log("차량 화면 캡처 불가(코드 " + code + ", 화면 " + displayId + ")");
                    if (failCount >= 5) {
                        aaShotFailed = true;
                        failedAt = System.currentTimeMillis();
                        failCount = 0;
                        status = "차량 화면 캡처가 막혀 있음(코드 " + code + ") — 1분 뒤 재시도";
                    }
                }
            });
        } catch (Throwable t) {
            busy = false;
            NavListenerService.log("차량 화면 캡처 오류: " + t.getClass().getSimpleName() + " " + t.getMessage());
            aaShotFailed = true;
            failedAt = System.currentTimeMillis();
        }
    }

    private long lastWidgetLog = 0;
    private boolean widgetLogged = false;

    /** 진단: 지금 폰 화면에 떠 있는 창 목록 */
    private String windowDiag() {
        StringBuilder b = new StringBuilder("[창: ");
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                b.append(root != null && root.getPackageName() != null ? root.getPackageName() : "?")
                        .append(' ').append(r.width()).append('x').append(r.height()).append(", ");
            }
        } catch (Throwable ignored) {
        }
        return b.append("]").toString();
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
