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
        instance = this;
        connected = true;
        status = "연결됨";
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (System.currentTimeMillis() < autoStartUntil) {
            handler.removeCallbacks(startLoop);
            handler.postDelayed(startLoop, 600);
        }
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
        instance = null;
        hideCover();
        connected = false;
        handler.removeCallbacks(loop);
        super.onDestroy();
    }

    private void tick() {
        if (!HudActivity.visible) {
            hideCover();
            return;
        }
        if (busy) return;
        if (Build.VERSION.SDK_INT < 30) {
            status = "안드로이드 11 이상 필요";
            return;
        }
        // 1순위: 안드로이드 오토(차량 화면)에 그려진 티맵 안내 카드
        if (scanAndroidAuto()) return;
        // 2순위: 폰 화면 위 티맵 미니 안내판
        final Rect r = findTmapWidget();
        // HUD가 피해서 그릴 수 있게 미니 창 위치를 알려 줌 (가려서 숨긴 경우는 피할 필요 없음)
        if (r != null) {
            miniL = r.left; miniT = r.top; miniR = r.right; miniB = r.bottom;
            miniAt = System.currentTimeMillis();
        }
        if (r == null) hideCover();
        if (r != null) widgetMissingSince = 0;
        else maybeKickMini();
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
        // 안드로이드 14 이상: 티맵 미니 창만 따로 찍고, 화면에서는 검은 덮개로 가림
        //  → 앞유리에 거꾸로 된 작은 창이 비치지 않음
        if (Build.VERSION.SDK_INT >= 34 && widgetId >= 0 && !windowShotFailed
                && getSharedPreferences("hud", MODE_PRIVATE).getBoolean("hide_mini", true)) {
            busy = true;
            showCover(r);
            try {
                takeScreenshotOfWindow(widgetId, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult res) {
                        work.execute(() -> {
                            try {
                                HardwareBuffer hb = res.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                if (hw != null) {
                                    Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    Rect all = new Rect();
                                    all.left = 0;
                                    all.top = 0;
                                    all.right = full.getWidth();
                                    all.bottom = full.getHeight();
                                    Bitmap crop = process(full, all);
                                    full.recycle();
                                    if (crop != null) {
                                        UdpSender.sendBytes(jpeg(crop));
                                        status = "티맵 안내판 표시 중 (미니 창 숨김)";
                                    }
                                }
                                hb.close();
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
                        if (code == 3) return; // 간격 짧음
                        windowShotFailed = true;
                        hideCover();
                        NavListenerService.log("미니 창 따로 찍기 불가(코드 " + code + ") → 기존 방식(미니 창 보임)");
                    }
                });
            } catch (Throwable t) {
                busy = false;
                windowShotFailed = true;
                hideCover();
                NavListenerService.log("미니 창 따로 찍기 오류: " + t.getMessage());
            }
            return;
        }
        hideCover();
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

    static volatile TmapA11yService instance;
    /** 이 시각까지 티맵 화면의 [안내 시작] 버튼을 찾아서 대신 누름 */
    static volatile long autoStartUntil = 0;
    private static final String[] START_WORDS = {"안내 시작", "안내시작", "길안내 시작", "경로 안내", "안내"};

    /** 티맵 경로 화면에서 [안내 시작] 버튼을 찾아 누름 (목적지 화면에서 보낸 경우만, 20초 동안) */
    private boolean tryAutoStart() {
        if (System.currentTimeMillis() > autoStartUntil) return false;
        // 티맵을 새로 켰을 때 뜨는 "지도 화면 제공을 위해 리소스를 갱신합니다" → [확인] 대신 누름
        if (tmapTextShown("리소스") && clickWord(new String[]{"확인"}) != null) {
            NavListenerService.log("티맵 '리소스 갱신' 창 [확인] 누름");
            autoStartUntil = Math.max(autoStartUntil, System.currentTimeMillis() + 40_000L); // 받는 동안 더 기다림
            return false;
        }
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !TMAP.contentEquals(root.getPackageName())) continue;
                for (String word : START_WORDS) {
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(word);
                    if (found == null) continue;
                    for (AccessibilityNodeInfo n : found) {
                        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                        if (t == null) continue;
                        String ts = t.toString().replace(" ", "");
                        if (!ts.equals(word.replace(" ", ""))) continue; // 정확히 그 글자인 버튼만
                        AccessibilityNodeInfo c = n;
                        for (int i = 0; i < 4 && c != null && !c.isClickable(); i++) c = c.getParent();
                        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            autoStartUntil = 0;
                            NavListenerService.log("티맵 [" + t + "] 버튼 자동으로 누름 → 길안내 시작");
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private final Runnable startLoop = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() > autoStartUntil) return;
            if (!tryAutoStart()) handler.postDelayed(this, 700);
        }
    };
    private long widgetMissingSince = 0, lastKick = 0;

    /**
     * HUD는 떠 있는데 티맵 미니 창이 없으면(티맵이 홈 버튼 없이 가려진 경우)
     * 티맵을 잠깐 앞으로 → 홈 버튼 → HUD 다시 띄우기로 미니 창을 되살림 (1분에 한 번까지)
     */
    private void maybeKickMini() {
        long now = System.currentTimeMillis();
        if (widgetMissingSince == 0) widgetMissingSince = now;
        if (now - widgetMissingSince < 6000 || now - lastKick < 60_000L) return;
        if (now - NavListenerService.lastGuideSeen > 30_000L) return; // 길안내 중이 아님
        if (AutoService.carState != 0) return; // 안드로이드 오토 방식에서는 안 함
        if (!getSharedPreferences("hud", MODE_PRIVATE).getBoolean("kick_mini", true)) return;
        android.content.Intent t = getPackageManager().getLaunchIntentForPackage(TMAP);
        if (t == null) return;
        lastKick = now;
        NavListenerService.log("티맵 미니 창이 없어서 되살리는 중 (티맵 → 홈 → HUD)");
        try {
            startActivity(t.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 1500);
            handler.postDelayed(() -> startActivity(new android.content.Intent(this, HudActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)), 2800);
        } catch (Exception e) {
            NavListenerService.log("미니 창 되살리기 실패: " + e.getMessage());
        }
    }

    // 1단계: 길안내 화면의 [안내종료] (확인 창이 바로 떠 있으면 [앱 종료]부터)
    private static final String[] END_WORDS = {"앱 종료", "티맵 종료", "TMAP 종료", "안내 종료", "길안내 종료", "경로 안내 종료", "주행 종료"};
    // 2단계: "종료하시겠습니까?" 창 → [앱 종료]가 있으면 그것(티맵까지 완전히 끔), 없으면 [안내 종료 ①]
    private static final String[] CONFIRM_WORDS = {"앱 종료", "티맵 종료", "TMAP 종료", "앱종료", "안내 종료", "종료", "확인", "예"};

    /**
     * 시동 꺼짐: 티맵 알림에 종료 버튼이 없어서, 티맵 화면을 잠깐 열고 [안내종료] → [앱 종료]를 대신 누름.
     * 티맵이 실제로 꺼질 때까지 기다린 뒤 홈 → then 실행 (최대 15초)
     */
    static void endGuidance(Runnable then) {
        TmapA11yService a = instance;
        if (a == null) {
            NavListenerService.log("티맵 종료 못 함: 접근성(안내판 읽기)이 꺼져 있음");
            if (then != null) then.run();
            return;
        }
        a.doEndGuidance(then);
    }

    private void doEndGuidance(Runnable then) {
        android.content.Intent t = getPackageManager().getLaunchIntentForPackage(TMAP);
        if (t == null) {
            if (then != null) then.run();
            return;
        }
        try {
            startActivity(t.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            if (then != null) then.run();
            return;
        }
        final long until = System.currentTimeMillis() + 15_000L;
        final int[] stage = {0}; // 0 = 안내종료 찾기, 1 = 확인 창, 2 = 티맵이 꺼지길 기다림, 3 = 끝
        final long[] stageAt = {System.currentTimeMillis()};
        final int[] gone = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (stage[0] == 0) {
                    String hit = clickWord(END_WORDS);
                    if (hit != null) {
                        NavListenerService.log("시동 꺼짐 → 티맵 [" + hit + "] 누름");
                        stage[0] = norm(hit).contains("앱") || norm(hit).contains("티맵") || norm(hit).contains("TMAP") ? 2 : 1;
                        stageAt[0] = now;
                    }
                } else if (stage[0] == 1) {
                    String hit = clickWord(CONFIRM_WORDS);
                    if (hit != null) {
                        NavListenerService.log("티맵 확인 창 [" + hit + "] 누름");
                        stage[0] = 2;
                        stageAt[0] = now;
                    } else if (now - stageAt[0] > 4000) {
                        stage[0] = 2; // 확인 창 없이 바로 끝난 경우
                        stageAt[0] = now;
                    }
                } else if (stage[0] == 2) {
                    // 티맵 창이 사라지거나(앱 종료) 길안내 알림이 끝날 때까지 기다림 — 카운트다운 중에 홈으로 나가면 취소됨
                    if (!tmapOnScreen()) gone[0]++;
                    else gone[0] = 0;
                    if (gone[0] >= 2 || now - stageAt[0] > 6000) stage[0] = 3;
                }
                if (stage[0] == 3 || now > until) {
                    if (stage[0] == 0) NavListenerService.log("티맵 화면에서 종료 버튼 못 찾음 " + tmapButtons());
                    else if (gone[0] >= 2) NavListenerService.log("티맵 완전히 꺼짐");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    // 길안내만 끝나고 티맵이 남아 있으면 뒤에서 정리
                    handler.postDelayed(() -> {
                        try {
                            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(TMAP);
                        } catch (Throwable ignored) {
                        }
                    }, 1500);
                    if (then != null) handler.postDelayed(then, 900);
                    return;
                }
                handler.postDelayed(this, 500);
            }
        }, 1500);
    }

    /** 글자 비교용: 띄어쓰기·숫자·동그라미 숫자·괄호 빼기 ("안내 종료 ①" → "안내종료") */
    static String norm(String t) {
        return t.replaceAll("[\\s0-9\u2460-\u2473\u24EA\u2776-\u277F()\\[\\]]", "");
    }

    /** 티맵 창이 아직 화면에 있는지 */
    private boolean tmapOnScreen() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null && TMAP.contentEquals(root.getPackageName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 티맵 창에서 글자가 같은 버튼(숫자·띄어쓰기 무시)을 찾아 누름 — words 순서가 우선순위 */
    private String clickWord(String[] words) {
        try {
            java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null && TMAP.contentEquals(root.getPackageName())) roots.add(root);
            }
            for (String word : words) {
                String want = norm(word);
                String core = want.length() > 2 ? want.substring(want.length() - 2) : want; // "종료"·"확인"
                for (AccessibilityNodeInfo root : roots) {
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(core);
                    if (found == null) continue;
                    for (AccessibilityNodeInfo n : found) {
                        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                        if (t == null || !norm(t.toString()).equals(want)) continue;
                        AccessibilityNodeInfo c = n;
                        for (int i = 0; i < 4 && c != null && !c.isClickable(); i++) c = c.getParent();
                        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return t.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 티맵 화면에 이 글자가 들어간 문구가 보이는지 */
    private boolean tmapTextShown(String part) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !TMAP.contentEquals(root.getPackageName())) continue;
                List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByText(part);
                if (f != null && !f.isEmpty()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 진단용: 티맵 화면에 있는 버튼 글자들 */
    private String tmapButtons() {
        StringBuilder b = new StringBuilder("[");
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !TMAP.contentEquals(root.getPackageName())) continue;
                collectButtons(root, b, 0);
            }
        } catch (Throwable ignored) {
        }
        String r = b.append("]").toString();
        return r.length() > 300 ? r.substring(0, 300) + "…]" : r;
    }

    private void collectButtons(AccessibilityNodeInfo n, StringBuilder b, int depth) {
        if (n == null || depth > 25 || b.length() > 320) return;
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (n.isClickable() && t != null && t.length() > 0 && t.length() < 15) b.append(t).append(", ");
        for (int i = 0; i < n.getChildCount(); i++) collectButtons(n.getChild(i), b, depth + 1);
    }

    /** 티맵 미니 창 화면 위치 (HUD가 피해서 그림) */
    static volatile int miniL, miniT, miniR, miniB;
    static volatile long miniAt = 0;

    /** 미니 창을 덮개로 가렸는지 (가렸으면 피할 필요 없음) */
    static volatile boolean miniHidden = false;

    private int widgetId = -1;
    private volatile boolean windowShotFailed = false;
    private android.view.View cover;
    private final Rect coverRect = new Rect();

    /** 티맵 미니 창 위에 검은 덮개 (터치는 그대로 통과) */
    private void showCover(Rect r) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    r.width(), r.height(),
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            lp.x = r.left;
            lp.y = r.top;
            if (cover == null) {
                cover = new android.view.View(this);
                cover.setBackgroundColor(android.graphics.Color.BLACK);
                wm.addView(cover, lp);
            } else if (!r.equals(coverRect)) {
                wm.updateViewLayout(cover, lp);
            }
            coverRect.set(r);
            miniHidden = true;
        } catch (Throwable t) {
            windowShotFailed = true;
            cover = null;
            NavListenerService.log("미니 창 덮개 오류: " + t.getMessage());
        }
    }

    private void hideCover() {
        miniHidden = false;
        if (cover == null) return;
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (Throwable ignored) {
        }
        cover = null;
        coverRect.setEmpty();
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
                if (area > 0 && area < screen / 2) {
                    widgetId = w.getId();
                    return b;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 안내판만 잘라서 흰색(화살표·숫자)만 남기고 나머지는 검정, 흰색은 HUD 색으로 */
    private Bitmap process(Bitmap full, Rect r) {
        // 미니 안내 창의 테두리(흰 선)가 같이 잘리지 않게 가장자리를 조금 안쪽으로
        int ix = Math.max(2, r.width() * 3 / 100), iy = Math.max(2, r.height() * 4 / 100);
        int x0 = Math.max(0, r.left + ix), y0 = Math.max(0, r.top + iy);
        int x1 = Math.min(full.getWidth(), r.right - ix), y1 = Math.min(full.getHeight(), r.bottom - iy);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;
        Bitmap c = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0);
        if (c.getWidth() > 720) {
            Bitmap s = Bitmap.createScaledBitmap(c, 720, c.getHeight() * 720 / c.getWidth(), true);
            c.recycle();
            c = s;
        }
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        if (p.getBoolean("white_only", false)) {
            int color = HudActivity.COLORS[p.getInt("color", 0) % HudActivity.COLORS.length];
            int w = c.getWidth(), h = c.getHeight();
            int[] px = new int[w * h];
            c.getPixels(px, 0, w, 0, 0, w, h);
            // 빨간 테두리 표지판(카메라·제한속도)을 찾아서 그 원 안은 티맵 원래 색 그대로 둠
            int rx0 = w, ry0 = h, rx1 = -1, ry1 = -1, reds = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = px[y * w + x];
                    int rr = (v >> 16) & 0xff, gg = (v >> 8) & 0xff, bb = v & 0xff;
                    if (rr > 150 && gg < 110 && bb < 110 && rr > gg + 70) {
                        reds++;
                        if (x < rx0) rx0 = x;
                        if (x > rx1) rx1 = x;
                        if (y < ry0) ry0 = y;
                        if (y > ry1) ry1 = y;
                    }
                }
            }
            boolean sign = reds > 25 && rx1 - rx0 > 8 && ry1 - ry0 > 8
                    && (rx1 - rx0) < w * 0.6f && (ry1 - ry0) < h * 0.9f;
            float scx = (rx0 + rx1) / 2f, scy = (ry0 + ry1) / 2f;
            float sr = Math.max(rx1 - rx0, ry1 - ry0) / 2f + 2f;
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                if (sign) {
                    float dx = (i % w) - scx, dy = (i / w) - scy;
                    if (dx * dx + dy * dy <= sr * sr) continue; // 표지판 원 안: 원래 색 유지
                }
                int rr = (v >> 16) & 0xff, gg = (v >> 8) & 0xff, bb = v & 0xff;
                int min = Math.min(rr, Math.min(gg, bb));
                // 초록 바탕은 빨강 성분이 낮음 → 빨강·초록이 모두 밝으면 글씨/화살표(흰색·연회색)
                px[i] = (rr > 105 && gg > 105) || min > 140 ? color : 0xff000000;
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
