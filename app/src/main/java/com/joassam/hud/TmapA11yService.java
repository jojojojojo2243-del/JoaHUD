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

    private volatile long busySince = 0;
    private int windowShotFails = 0;
    private long lastStuckLog = 0;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            // 어떤 오류가 나도 반복은 절대 멈추지 않게 (멈추면 HUD 화면이 굳음)
            try {
                // 화면 찍기 응답이 3초 넘게 안 오면 기다리지 않고 다시 찍음
                if (busy && busySince > 0 && System.currentTimeMillis() - busySince > 3000) {
                    busy = false;
                    long now = System.currentTimeMillis();
                    if (now - lastStuckLog > 60_000L) {
                        lastStuckLog = now;
                        NavListenerService.log("티맵 화면 찍기 응답 없음 → 다시 찍음");
                    }
                }
                tick();
            } catch (Throwable t) {
                busy = false;
                long now = System.currentTimeMillis();
                if (now - lastStuckLog > 60_000L) {
                    lastStuckLog = now;
                    NavListenerService.log("HUD 갱신 오류 → 계속 진행: " + t);
                }
            } finally {
                handler.removeCallbacks(this);
                handler.postDelayed(this, Thermal.captureInterval(TmapA11yService.this));
            }
        }
    };

    /** 찍은 안내판을 HUD에 바로 넘김 (HUD가 없을 때만 예전처럼 UDP로) */
    private void deliver(Bitmap crop) {
        if (!HudActivity.deliverFrame(crop)) UdpSender.sendBytes(jpeg(crop));
    }

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
            if (event == null || event.getPackageName() == null) return;
            String pk = event.getPackageName().toString();
            int type = event.getEventType();
            // 티맵을 손으로 만지는 중 (누르기·밀기·글자 입력·창 바뀜)
            if (pk.equals(TMAP) && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) lastTmapTouch = System.currentTimeMillis();
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                lastAnyTouch = System.currentTimeMillis();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // 키보드·시스템UI·티맵 미니 안내 같은 작은 창은 '앞 화면'으로 치지 않음
                if (!pk.equals("com.android.systemui") && !pk.contains("inputmethod") && !pk.contains("honeyboard")) {
                    if (!(pk.equals(TMAP) && findTmapWidget() != null && !tmapFull())) {
                        String before = fgPkg;
                        fgPkg = pk;
                        if (TMAP.equals(before) && !pk.equals(TMAP)) leftTmap(pk);
                        if (pk.equals(TMAP) && !TMAP.equals(before)) tmapFgSince = System.currentTimeMillis();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 지금 맨 앞에 떠 있는 앱 (티맵 화면을 보고 있는지 확인용) */
    static volatile String fgPkg = "";

    // ── 티맵을 만지다가도 HUD로 돌아가기 ──
    private volatile long lastTmapTouch = 0, tmapFgSince = 0, lastAnyTouch = 0;
    private long oddSince = 0;
    private long notFoundFiredAt = 0;

    /** 음성 "조아야 정리해": 지도 덮개·검은 가림막·목적지 창을 다 치우고 HUD 하나만 */
    static void cleanUp() {
        TmapA11yService a = instance;
        if (a == null) return;
        a.handler.post(() -> {
            a.hideCover();
            DestActivity.closeIfOpen();
            NavListenerService.log("화면 정리 → HUD만 남김");
            a.handler.postDelayed(() -> {
                try {
                    a.startActivity(new android.content.Intent(a, HudActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (Throwable ignored) {
                }
            }, 700);
        });
    }

    /**
     * 운전 중(길안내 중) HUD도 티맵도 아닌 화면이 25초 넘게 아무도 안 만진 채 떠 있으면 → 꼬인 것으로 보고 정리해서 HUD.
     * 티맵이 "검색 결과 없음"을 띄우면 기다리지 않고 바로 목적지를 다시 물음.
     */
    private void checkOddScreen() {
        long now = System.currentTimeMillis();
        // ① 목적지 검색 결과 없음
        if (TMAP.equals(fgPkg) && now - NavListenerService.destFlowAt < 3 * 60_000L && now - notFoundFiredAt > 60_000L
                && (tmapTextShown("검색 결과가 없") || tmapTextShown("검색결과가 없") || tmapTextShown("결과가 없습니다")
                || tmapTextShown("찾을 수 없") || tmapTextShown("검색된 결과가"))) {
            notFoundFiredAt = now;
            NavListenerService.log("티맵: 목적지 검색 결과 없음 → 바로 다시 물어봄");
            DestActivity.fireWatchdogNow();
            return;
        }
        // ② 꼬인 화면 (단순 모드에선 저절로 안 함 — "조아야 정리해"로)
        if (AutoService.simple(this)) return;
        boolean odd = AutoService.ignOn && !AutoService.parked() && guiding()
                && !HudActivity.visible && !DestActivity.visible && !MainActivity.visible && !ParkActivity.visible
                && !TMAP.equals(fgPkg) && now > autoStartUntil && now > selfTmapUntil && AutoService.carState == 0;
        if (!odd) {
            oddSince = 0;
            return;
        }
        if (oddSince == 0) oddSince = now;
        if (now - oddSince < 25_000L || now - lastAnyTouch < 25_000L) return;
        oddSince = 0;
        lastAnyTouch = now;
        hideCover();
        NavListenerService.log("HUD도 티맵도 아닌 화면이 계속 떠 있음(" + fgPkg + ") → 정리하고 HUD로");
        try {
            startActivity(new android.content.Intent(this, HudActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Throwable ignored) {
        }
    }

    /** 사람이 최근 ms 안에 티맵 화면을 만졌는지 (티맵이 앞에 있을 때) */
    static boolean userTouching(long ms) {
        TmapA11yService a = instance;
        return a != null && TMAP.equals(fgPkg) && System.currentTimeMillis() - a.lastTmapTouch < ms
                && System.currentTimeMillis() > autoStartUntil;
    }

    static void pressHome() {
        TmapA11yService a = instance;
        if (a != null) a.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ───────── 블루투스 켜고 끄기 (음성) ─────────
    // 안드로이드 13부터 일반 앱은 블루투스를 직접 켜고 끌 수 없어서,
    // 빠른 설정 창을 내리고 [블루투스] 버튼을 대신 눌러 줌.

    static Boolean btOn(android.content.Context c) {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            return ad == null ? null : ad.isEnabled();
        } catch (Throwable t) {
            return null;
        }
    }

    /** want=true 켜기, false 끄기, null 바꾸기. 끝나면 done(말할 문장, 없으면 null) */
    static String setBluetooth(Boolean want, java.util.function.Consumer<String> done) {
        TmapA11yService a = instance;
        if (a == null) return "접근성 권한이 꺼져 있어서 블루투스를 못 바꿔요";
        Boolean now = btOn(a);
        if (want != null && now != null && want == now) return want ? "블루투스는 이미 켜져 있어요" : "블루투스는 이미 꺼져 있어요";
        final boolean target = want != null ? want : !(now != null && now);
        a.handler.post(() -> a.btStart(target, done));
        return target ? "블루투스를 켤게요" : "블루투스를 끌게요";
    }

    private void btStart(boolean target, java.util.function.Consumer<String> done) {
        NavListenerService.log("블루투스 " + (target ? "켜기" : "끄기") + " — 빠른 설정 창 내림");
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        handler.postDelayed(() -> btTry(target, done, 0), 900);
    }

    private void btTry(boolean target, java.util.function.Consumer<String> done, int step) {
        boolean clicked = false;
        try {
            clicked = btClickTile();
            if (!clicked && step < 3) {
                // 첫 페이지에 없으면 다음 페이지로 넘겨 봄
                if (btScroll()) {
                    handler.postDelayed(() -> btTry(target, done, step + 1), 700);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        NavListenerService.log(clicked ? "빠른 설정 [블루투스] 누름" : "빠른 설정에서 [블루투스] 버튼을 못 찾음");
        // ① 타일을 눌렀으면 1.8초 뒤 확인 → 안 바뀌었으면 ② 열린 상세 창의 스위치 → ③ 블루투스 설정 화면 스위치
        handler.postDelayed(() -> btStage2(target, done), clicked ? 1800 : 300);
    }

    private void btStage2(boolean target, java.util.function.Consumer<String> done) {
        Boolean st = btOn(this);
        if (st != null && st == target) {
            btClose();
            btFinish(target, done, "빠른 설정");
            return;
        }
        // 글자 쪽이 눌려 상세 창이 열린 경우: 그 안의 켜기/끄기 스위치
        boolean sw = false;
        try {
            sw = btClickSwitch(target, false);
        } catch (Throwable ignored) {
        }
        if (sw) NavListenerService.log("블루투스 상세 창 스위치 누름");
        handler.postDelayed(() -> {
            Boolean st2 = btOn(this);
            btClose();
            if (st2 != null && st2 == target) {
                btFinish(target, done, "상세 창");
                return;
            }
            btStage3(target, done);
        }, sw ? 1800 : 200);
    }

    /** 마지막 방법: 블루투스 설정 화면을 열어 맨 위 스위치를 누르고 HUD로 돌아옴 */
    private void btStage3(boolean target, java.util.function.Consumer<String> done) {
        final boolean hudWasUp = HudActivity.alive();
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            NavListenerService.log("블루투스 설정 화면 열어서 스위치 누르기");
        } catch (Throwable t) {
            btFinish(target, done, null);
            return;
        }
        handler.postDelayed(() -> {
            boolean sw = false;
            try {
                sw = btClickSwitch(target, true);
            } catch (Throwable ignored) {
            }
            if (sw) NavListenerService.log("블루투스 설정 스위치 누름");
            handler.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_BACK);
                if (hudWasUp) {
                    try {
                        startActivity(new android.content.Intent(this, HudActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    } catch (Throwable ignored) {
                    }
                }
                handler.postDelayed(() -> btFinish(target, done, "설정 화면"), 1200);
            }, sw ? 1500 : 300);
        }, 1600);
    }

    private void btClose() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void btFinish(boolean target, java.util.function.Consumer<String> done, String how) {
        Boolean st = btOn(this);
        String msg = null;
        if (st != null && st == target) {
            NavListenerService.log("블루투스 " + (target ? "켜짐" : "꺼짐") + " 확인" + (how != null ? " (" + how + ")" : ""));
            msg = target ? "블루투스 켰어요" : "블루투스 껐어요";
        } else {
            msg = "블루투스를 " + (target ? "못 켰어요" : "못 껐어요") + ". 기록을 보여 주세요";
            NavListenerService.log("블루투스 바꾸기 실패 (지금 " + (st == null ? "알 수 없음" : st ? "켜짐" : "꺼짐") + ")");
        }
        if (done != null) done.accept(msg);
    }

    /** 블루투스 켜기/끄기 스위치(체크 가능한 칸)를 찾아 원하는 상태가 아니면 누름. settings=true면 설정 앱 화면에서 */
    private boolean btClickSwitch(boolean target, boolean settings) {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String pk = root.getPackageName().toString();
            if (pk.equals(TMAP) || pk.equals(getPackageName())) continue;
            if (settings != pk.contains("settings")) continue;
            if (!settings) { // 빠른 설정 상세 창: 그 창에 '블루투스' 글자가 있어야 함
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText("블루투스");
                List<AccessibilityNodeInfo> l2 = root.findAccessibilityNodeInfosByText("Bluetooth");
                if ((l == null || l.isEmpty()) && (l2 == null || l2.isEmpty())) continue;
            }
            AccessibilityNodeInfo sw = findCheckable(root, 0);
            if (sw == null) continue;
            if (sw.isChecked() == target) return false; // 이미 원하는 상태로 보임
            AccessibilityNodeInfo c = sw;
            for (int i = 0; i < 4 && c != null && !c.isClickable(); i++) c = c.getParent();
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    /** 화면 위쪽부터 처음 나오는 체크 가능한 스위치 */
    private static AccessibilityNodeInfo findCheckable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 14) return null;
        if (n.isCheckable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findCheckable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean isBtLabel(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        return t.length() <= 30 && (t.startsWith("블루투스") || t.startsWith("bluetooth"));
    }

    /** 시스템 창(빠른 설정)에서 블루투스 타일을 찾아 누름 */
    private boolean btClickTile() {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String pk = root.getPackageName().toString();
            if (pk.equals(TMAP) || pk.equals(getPackageName())) continue;
            java.util.ArrayList<AccessibilityNodeInfo> hits = new java.util.ArrayList<>();
            for (String word : new String[]{"블루투스", "Bluetooth"}) {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(word);
                if (l != null) hits.addAll(l);
            }
            AccessibilityNodeInfo best = null;
            int bestScore = -1;
            for (AccessibilityNodeInfo n : hits) {
                if (!isBtLabel(n.getContentDescription()) && !isBtLabel(n.getText())) continue;
                AccessibilityNodeInfo c = n;
                for (int i = 0; i < 4 && c != null && !c.isClickable(); i++) c = c.getParent();
                if (c == null || !c.isVisibleToUser()) continue;
                int sc = 0;
                if (isBtLabel(c.getContentDescription())) sc += 2;   // 타일 전체에 붙은 이름
                if (c.isCheckable()) sc += 3;                         // 켜짐/꺼짐 스위치 (글자만 누르면 상세 창이 열림)
                CharSequence cl = c.getClassName();
                if (cl != null && (cl.toString().contains("Switch") || cl.toString().contains("Button"))) sc += 1;
                if (sc > bestScore) {
                    bestScore = sc;
                    best = c;
                }
            }
            if (best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private boolean btScroll() {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String pk = root.getPackageName().toString();
            if (pk.equals(TMAP) || pk.equals(getPackageName())) continue;
            AccessibilityNodeInfo sc = findScrollable(root, 0);
            if (sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 12) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private long lastSysPopup = 0;

    /** 운전 중 삼성 시스템 창 "○○에 연결할까요? (핫스팟)" → [취소] (HUD를 가리지 않게) */
    private void closeSystemPopups() {
        long now = System.currentTimeMillis();
        if (now - lastSysPopup < 2000 || !AutoService.ignOn) return;
        lastSysPopup = now;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                String pk = root.getPackageName().toString();
                if (pk.equals(TMAP) || pk.equals(getPackageName())) continue;
                List<AccessibilityNodeInfo> q = root.findAccessibilityNodeInfosByText("연결할까요");
                if (q == null || q.isEmpty()) continue;
                List<AccessibilityNodeInfo> hs = root.findAccessibilityNodeInfosByText("핫스팟");
                if (hs == null || hs.isEmpty()) continue;
                for (String word : new String[]{"취소", "닫기", "나중에"}) {
                    List<AccessibilityNodeInfo> b = root.findAccessibilityNodeInfosByText(word);
                    if (b == null) continue;
                    for (AccessibilityNodeInfo n : b) {
                        CharSequence t = n.getText();
                        if (t == null || !norm(t.toString()).equals(norm(word))) continue;
                        AccessibilityNodeInfo c = n;
                        for (int i = 0; i < 4 && c != null && !c.isClickable(); i++) c = c.getParent();
                        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            NavListenerService.log("운전 중 '핫스팟 연결' 창 [" + word + "] 누름");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
    /** 앱이 일부러 티맵을 여는 동안(미니 창 되살리기·시동 끌 때 종료)은 자동 전환 안 함 */
    static volatile long selfTmapUntil = 0;
    private String homePkg = null;

    private boolean guiding() {
        return NavListenerService.tmapGuiding();
    }

    private boolean mayReturnToHud() {
        long now = System.currentTimeMillis();
        return guiding() && !AutoService.parked() && now > autoStartUntil && now > selfTmapUntil
                && !HudActivity.visible && !DestActivity.visible && !MainActivity.visible && !ParkActivity.visible
                && AutoService.carState == 0 && !AutoService.simple(this)
                && getSharedPreferences("hud", MODE_PRIVATE).getBoolean("tmap_back_hud", true);
    }

    /** 길안내 중 티맵에서 홈으로 나감 → 바로 HUD (기다리는 시간 없이) */
    private void leftTmap(String to) {
        if (!mayReturnToHud()) return;
        if (homePkg == null) {
            try {
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                        new android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME), 0);
                homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            } catch (Throwable t) {
                homePkg = "";
            }
        }
        boolean home = to.equals(homePkg) || to.contains("launcher");
        if (!home) return; // 다른 앱으로 간 거면 방해하지 않음
        handler.postDelayed(() -> {
            if (!mayReturnToHud() || TMAP.equals(fgPkg)) return;
            NavListenerService.openHudViaHome(this, "티맵에서 홈으로 나감 → HUD로 돌아감");
        }, 1200); // 미니 창이 생길 시간
    }

    /** 길안내 중 티맵 화면을 10초 동안 안 만지면 → 홈 → HUD */
    private void checkTmapIdle() {
        long now = System.currentTimeMillis();
        if (!TMAP.equals(fgPkg)) return;
        if (!mayReturnToHud()) return;
        if (now - Math.max(lastTmapTouch, tmapFgSince) < 10_000L) return;
        if (!tmapFull()) return;
        lastTmapTouch = now; // 한 번만
        NavListenerService.openHudViaHome(this, "티맵 화면을 10초 동안 안 만짐 → HUD로 돌아감");
    }

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

    private long lastPopupCheck = 0, lastOddCheck = 0;

    /** 운전 중 티맵에 뜨는 오류·안내 창(인터넷 끊김, 지도 갱신 등)을 앱이 대신 닫음 → 손댈 일 없게 */
    /** 티맵 '업데이트 해주세요' 창 → [닫기] (운전 중 업데이트로 티맵이 꺼지면 안 됨). 눌렀으면 true */
    private boolean closeTmapUpdate() {
        if (!(tmapTextShown("업데이트 해") || tmapTextShown("업데이트해") || tmapTextShown("새 버전") || tmapTextShown("최신 버전"))) return false;
        String hit = clickWord(new String[]{"닫기", "나중에", "다음에", "취소"});
        if (hit == null) return false;
        NavListenerService.log("티맵 '업데이트' 창 [" + hit + "] 누름 (업데이트는 주차 중 직접)");
        return true;
    }

    private void closeTmapPopups() {
        long now = System.currentTimeMillis();
        if (now - lastPopupCheck < 2000) return;
        lastPopupCheck = now;
        if (now < selfTmapUntil) return; // 시동 끌 때 종료 중엔 건드리지 않음
        if (!HudActivity.visible && !guiding() && !AutoService.ignOn) return;
        if (closeTmapUpdate()) return; // 업데이트 창은 사람이 만지는 중이어도 운전 중이면 닫음
        if (TMAP.equals(fgPkg) && now - lastTmapTouch < 4000) return; // 사람이 티맵을 만지는 중이면 건드리지 않음
        String[] cause = {"네트워크", "인터넷", "통신", "연결 상태", "연결이", "서버", "리소스", "갱신", "일시적", "오류", "다시 시도"};
        String why = null;
        for (String w : cause) if (tmapTextShown(w)) { why = w; break; }
        if (why == null) return;
        String hit = clickWord(new String[]{"확인", "닫기", "재시도", "다시 시도", "다시시도"});
        if (hit != null) NavListenerService.log("티맵 알림 창 자동으로 닫음 (" + why + ", [" + hit + "])");
    }

    private void tick() {
        checkTmapIdle();
        closeTmapPopups();
        closeSystemPopups();
        if (System.currentTimeMillis() - lastOddCheck > 2000) {
            lastOddCheck = System.currentTimeMillis();
            try {
                checkOddScreen();
            } catch (Throwable ignored) {
            }
        }
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
        // HUD가 피해서 그릴 수 있게 미니 창 위치를 알려 줌 (검은 덮개로 가린 경우도 덮개 자리를 피함)
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
            if (now - lastWidgetLog > 600_000L) { // 10분에 한 번만 (1분마다 쌓여 기록을 밀어냈음)
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
            busySince = System.currentTimeMillis();
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
                                        deliver(crop);
                                        windowShotFails = 0;
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
                        // 티맵이 미니 창을 새로 만들면 한두 번 실패할 수 있음 → 창을 다시 찾아 재시도, 계속 실패할 때만 포기
                        widgetId = -1;
                        if (++windowShotFails < 8) return;
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
            busySince = System.currentTimeMillis();
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
                                deliver(crop);
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
            busySince = System.currentTimeMillis();
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
        if (closeTmapUpdate()) {
            autoStartUntil = Math.max(autoStartUntil, System.currentTimeMillis() + 20_000L);
            return false;
        }
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
                        // 티맵 새 버전은 버튼에 카운트다운 숫자가 붙음 ("⑮ 안내시작", "15 안내시작") → 숫자·기호 빼고 비교
                        String ts = norm(t.toString());
                        String want = norm(word);
                        boolean ok = ts.equals(want)
                                || (want.length() >= 4 && ts.contains(want) && ts.length() <= want.length() + 8);
                        if (!ok || ts.matches(".*(종료|취소|설정|끄기|중지).*")) continue;
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

    private boolean startFailLogged = false;

    private final Runnable startLoop = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (now > autoStartUntil) {
                // 시간 안에 못 눌렀으면 원인 확인용으로 티맵 버튼 글자를 한 번 남김
                if (!startFailLogged && autoStartUntil > 0 && now - autoStartUntil < 5000) {
                    startFailLogged = true;
                    NavListenerService.log("[안내시작]을 못 찾음 — 티맵 버튼: " + tmapButtons());
                }
                return;
            }
            startFailLogged = false;
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
        selfTmapUntil = now + 5000;
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
        endGuidance(then, true);
    }

    /** closeApp=false: 길안내만 끝내고 티맵은 남겨 둠 (음성 "안내 종료") */
    static void endGuidance(Runnable then, boolean closeApp) {
        TmapA11yService a = instance;
        if (a == null) {
            NavListenerService.log("티맵 종료 못 함: 접근성(안내판 읽기)이 꺼져 있음");
            if (then != null) then.run();
            return;
        }
        a.closeApp = closeApp;
        a.doEndGuidance(then);
    }

    private volatile boolean closeApp = true;
    private static final String[] GUIDE_END_WORDS = {"안내 종료", "길안내 종료", "경로 안내 종료", "주행 종료"};
    private static final String[] GUIDE_CONFIRM_WORDS = {"안내 종료", "종료", "확인", "예"};

    private void doEndGuidance(Runnable then) {
        android.content.Intent t = getPackageManager().getLaunchIntentForPackage(TMAP);
        if (t == null) {
            if (then != null) then.run();
            return;
        }
        selfTmapUntil = System.currentTimeMillis() + 20_000L;
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
                    String hit = clickWord(closeApp ? END_WORDS : GUIDE_END_WORDS);
                    if (hit != null) {
                        NavListenerService.log("티맵 종료 → [" + hit + "] 누름");
                        stage[0] = norm(hit).contains("앱") || norm(hit).contains("티맵") || norm(hit).contains("TMAP") ? 2 : 1;
                        stageAt[0] = now;
                    }
                } else if (stage[0] == 1) {
                    String hit = clickWord(closeApp ? CONFIRM_WORDS : GUIDE_CONFIRM_WORDS);
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
                    if (closeApp) handler.postDelayed(() -> {
                        try {
                            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(TMAP);
                        } catch (Throwable ignored) {
                        }
                    }, 1500);
                    closeApp = true;
                    if (then != null) handler.postDelayed(then, 900);
                    return;
                }
                handler.postDelayed(this, 500);
            }
        }, 1500);
    }

    /** 티맵 강제 종료: 홈으로 내보낸 뒤 뒤에 남은 티맵을 끔 (목적지 못 찾음·화면 멈춤 때) */
    static void forceCloseTmap(String why) {
        TmapA11yService a = instance;
        NavListenerService.stoppingTmapAt = System.currentTimeMillis(); // 이걸 '도착'으로 보지 않게
        if (a == null) return;
        a.handler.post(() -> {
            a.performGlobalAction(GLOBAL_ACTION_HOME);
            a.handler.postDelayed(() -> {
                try {
                    ((android.app.ActivityManager) a.getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(TMAP);
                } catch (Throwable ignored) {
                }
                NavListenerService.log("티맵 강제 종료 (" + why + ")");
            }, 1200);
        });
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
    /** 검은 덮개가 미니 창보다 사방으로 더 큰 만큼(픽셀) — HUD가 이만큼 더 피해서 그림 */
    static volatile int coverPad = 0;

    private int widgetId = -1;
    private volatile boolean windowShotFailed = false;
    private android.view.View cover;
    private final Rect coverRect = new Rect();

    /** 티맵 미니 창 위에 검은 덮개 (터치는 그대로 통과) */
    private void showCover(Rect r0) {
        try {
            // 미니 창 그림자·테두리까지 덮도록 사방으로 넉넉하게 키움
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            coverPad = pad;
            Rect r = new Rect(r0.left - pad, r0.top - pad, r0.right + pad, r0.bottom + pad);
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    r.width(), r.height(),
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            lp.flags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (Build.VERSION.SDK_INT >= 30) // 카메라 구멍·상태 표시줄 때문에 위치가 밀리지 않게
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
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
    private long widgetTextLoggedAt = 0;

    /**
     * 미니 창 안의 글자(거리·방향)를 접근성으로 읽을 수 있는지 확인용 — 10분에 한 번 기록.
     * 글자가 나오면 다음 단계로 '화면을 찍지 않고 글자로 HUD 그리기'를 할 수 있음.
     */
    private void logWidgetText(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - widgetTextLoggedAt < 10 * 60_000L) return;
        widgetTextLoggedAt = now;
        StringBuilder sb = new StringBuilder();
        collectText(root, sb, 0);
        NavListenerService.log("티맵 미니 창 글자: " + (sb.length() == 0 ? "(없음 — 그림으로만 그려짐)" : sb.toString()));
    }

    private void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 8 || sb.length() > 200) return;
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (t != null && t.toString().trim().length() > 0) sb.append('[').append(t.toString().trim()).append(']');
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
    }

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
                    logWidgetText(root);
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
        if (p.getBoolean("white_only", true)) {
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
