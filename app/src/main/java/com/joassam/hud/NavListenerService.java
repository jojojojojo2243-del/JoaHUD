package com.joassam.hud;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Set;

/** 메인폰: 내비 앱 알림을 읽어 HUD폰으로 쏜다 */
public class NavListenerService extends NotificationListenerService {

    static final Set<String> DEFAULT_PKGS = new HashSet<>(Arrays.asList(
            "com.skt.tmap.ku",              // TMAP
            "com.skt.skaf.l001mtm091",      // TMAP (구버전)
            "com.locnall.KimGiSa",          // 카카오내비
            "com.nhn.android.nmap",         // 네이버지도
            "com.google.android.apps.maps", // 구글맵
            "com.waze"
    ));

    static final LinkedList<String> LOG = new LinkedList<>();
    static final String KAKAO = "com.locnall.KimGiSa";
    private static long lastKakao = 0;
    static volatile boolean connected = false;

    private static NavData last;
    private static String lastKey;
    private static NavListenerService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            NavData d = last;
            if (d != null) merge(d);
            boolean aaFresh = System.currentTimeMillis() - TmapA11yService.lastAaData < 3000
                    || TmapEdc.fresh();
            if (d != null && !aaFresh) UdpSender.send(d.toJson());
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onListenerConnected() {
        connected = true;
        instance = this;
        log("알림 접근 연결됨");
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification s : active) handle(s);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        instance = null;
        handler.removeCallbacks(heartbeat);
        log("알림 접근 끊김");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (lastKey != null && lastKey.equals(sbn.getKey()) && last != null) {
            last.active = false;
            last.time = System.currentTimeMillis();
            RouteGuide.clear();
            UdpSender.send(last.toJson());
            log("길안내 알림 종료: " + sbn.getPackageName());
            askNextDestination(sbn.getPackageName());
            return;
        }
        // 티맵 단독 모드: "경로주행" 알림이 사라짐 = 길안내 끝
        if (TmapA11yService.TMAP.equals(sbn.getPackageName()) && guideStartAt != 0) {
            try {
                NavData d = NavParser.parse(this, sbn);
                if (d.raw.contains("경로주행") || !d.dist.isEmpty() || !"NONE".equals(d.dir)) {
                    log("티맵 길안내 끝");
                    askNextDestination(sbn.getPackageName());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        boolean logAll = p.getBoolean("log_all", false);
        boolean watched = isWatched(pkg, p);
        if (!watched && !logAll) return;
        if (!watched && (pkg.equals("com.android.systemui") || pkg.equals("android"))) return; // 충전 알림 등 반복 기록 막기

        NavData d = NavParser.parse(this, sbn);
        log((watched ? "★ " : "") + pkg + "\n   → [" + d.dir + " " + d.dist + (d.icon.isEmpty() ? " 그림없음" : " 그림있음") + "] " + d.raw);
        if (!watched || d.raw.isEmpty()) return;

        // 카카오내비 우선: 최근 10초 안에 카카오내비 안내가 있었으면 티맵 알림은 무시
        boolean isKakao = pkg.equals(KAKAO);
        long now = System.currentTimeMillis();
        if (isKakao && (!d.dir.equals("NONE") || !d.dist.isEmpty())) lastKakao = now;
        if (!isKakao && p.getString("nav_pref", "tmap").equals("kakao") && now - lastKakao < 10000) return;

        // "실행 중" 같은 상태 알림이 진짜 안내를 덮어쓰지 않도록
        boolean useful = !d.dir.equals("NONE") || !d.dist.isEmpty();
        // 티맵에서 직접 목적지를 넣고 길안내가 시작돼도 → 몇 초 뒤 HUD로 자동 전환
        if (pkg.equals(TmapA11yService.TMAP) && !d.raw.contains("광고") && !d.raw.contains("Android Auto")
                && (useful || d.raw.contains("경로주행") || d.raw.contains("길안내"))) {
            guideSeen(p, d.raw);
        }
        // "TMAP이 Android Auto에 연결되었습니다" 같은 안내 아닌 알림은 HUD에 띄우지 않음
        if (!useful) return;

        // 목적지로 티맵 경로를 받아 회전 방향 계산
        if (d.sub != null && !d.sub.isEmpty()) RouteGuide.setDestination(this, d.sub);
        merge(d);

        last = d;
        lastKey = sbn.getKey();
        autoOpenHud(pkg, p);
        if (System.currentTimeMillis() - TmapA11yService.lastAaData > 3000 && !TmapEdc.fresh()) UdpSender.send(d.toJson());
    }

    /** 시동 꺼짐: 티맵 길안내 알림의 [안내 종료] 버튼을 대신 눌러 티맵을 끝냄 */
    static boolean stopTmap() {
        NavListenerService me = instance;
        if (me == null) {
            log("티맵 종료 못 함: 알림 접근이 꺼져 있음");
            return false;
        }
        boolean done = false;
        StringBuilder seen = new StringBuilder();
        try {
            for (StatusBarNotification sb : me.getActiveNotifications()) {
                if (!TmapA11yService.TMAP.equals(sb.getPackageName())) continue;
                android.app.Notification.Action[] acts = sb.getNotification().actions;
                if (acts == null) continue;
                for (android.app.Notification.Action a : acts) {
                    String t = a.title == null ? "" : a.title.toString();
                    seen.append('[').append(t).append(']');
                    if (!done && (t.contains("종료") || t.contains("끝") || t.contains("중지") || t.equalsIgnoreCase("exit") || t.equalsIgnoreCase("stop"))) {
                        a.actionIntent.send();
                        done = true;
                    }
                }
            }
        } catch (Throwable e) {
            log("티맵 종료 오류: " + e.getMessage());
        }
        if (done) log("시동 꺼짐 → 티맵 안내 종료 " + seen);
        else log("티맵 알림에 종료 버튼 없음 " + seen + " → 티맵 화면에서 종료 버튼 누르기");
        return done;
    }

    /** 시동 끄면서 티맵을 끈 시각 (이때는 '다음 목적지' 안 물음) */
    static volatile long stoppingTmapAt = 0;
    private static long guideStartAt = 0;

    /** 목적지 도착(티맵 길안내 끝) → "다음 목적지 있으세요?" */
    private void askNextDestination(String pkg) {
        long now = System.currentTimeMillis();
        long started = guideStartAt;
        guideStartAt = 0;
        if (!TmapA11yService.TMAP.equals(pkg)) return;
        if (!getSharedPreferences("hud", MODE_PRIVATE).getBoolean("ask_next", true)) return;
        if (now - stoppingTmapAt < 15_000L) return;                 // 시동 끄면서 끈 것
        if (!(AutoService.ignOn || AutoService.carState != 0)) return; // 운행 중이 아님
        if (started == 0 || now - started < 60_000L) return;          // 너무 짧은 안내(취소 등)
        handler.postDelayed(() -> {
            if (DestActivity.visible) return;
            try {
                startActivity(new android.content.Intent(this, DestActivity.class)
                        .putExtra("next", true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                log("목적지 도착 → 다음 목적지 묻기");
            } catch (Exception e) {
                log("다음 목적지 화면 실패: " + e.getMessage());
            }
        }, 3000);
    }

    private long lastAutoOpen = 0;
    private long lastGuideAt = 0;
    /** 마지막으로 티맵 길안내 알림을 본 시각 (미니 창 되살리기 판단용) */
    static volatile long lastGuideSeen = 0;

    private static String lastGuideDest = "";

    /** 티맵 길안내 알림을 봄: 새 길안내가 시작된 거면 4초 뒤 HUD 띄우기 (티맵 화면이 앞에 있어도) */
    private void guideSeen(SharedPreferences p, String raw) {
        long now = System.currentTimeMillis();
        // 새 길안내: 2분 넘게 쉬었다 시작 / 앞 안내가 끝난 뒤(도착) 다음 안내 / 알림의 목적지("… > 목적지")가 바뀜
        String dest = raw == null ? "" : raw.contains(">") ? raw.substring(raw.lastIndexOf('>') + 1).trim() : "";
        boolean destChanged = !dest.isEmpty() && !lastGuideDest.isEmpty() && !dest.equals(lastGuideDest);
        if (!dest.isEmpty()) lastGuideDest = dest;
        boolean newSession = now - lastGuideAt > 120_000L || guideStartAt == 0 || destChanged;
        if (destChanged) log("목적지 바뀜 → " + dest);
        lastGuideAt = now;
        lastGuideSeen = now;
        if (guideStartAt == 0) guideStartAt = now;
        if (!newSession || !p.getBoolean("tmap_auto_hud", true)) return;
        handler.postDelayed(() -> {
            if (HudActivity.visible || DestActivity.visible || MainActivity.visible) return;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
                    log("'다른 앱 위에 표시' 권한이 없어 HUD 자동 전환 못 함");
                    return;
                }
                lastAutoOpen = System.currentTimeMillis();
                openHudViaHome(this, "티맵 길안내 시작 감지 → HUD 자동 전환");
            } catch (Exception e) {
                log("HUD 자동 전환 실패: " + e.getMessage());
            }
        }, 4000);
    }
    /** 목적지 화면에서 티맵을 연 시각 (이때는 티맵 화면이 앞에 있어도 바로 HUD로 전환) */
    static volatile long destFlowAt = 0;

    /** 서브폰 티맵 단독 모드: 티맵 길안내 중 홈으로 나가면 HUD를 자동으로 띄움 */
    private void autoOpenHud(String pkg, SharedPreferences p) {
        if (!p.getBoolean("tmap_auto_hud", true)) return;
        if (!pkg.equals(TmapA11yService.TMAP)) return;
        if (HudActivity.visible || MainActivity.visible || DestActivity.visible) return;
        long now = System.currentTimeMillis();
        boolean fromDest = now - destFlowAt < 5 * 60_000L;
        if (!fromDest && TmapA11yService.TMAP.equals(TmapA11yService.fgPkg)) return; // 티맵 화면을 보고 있는 중
        if (!fromDest && now - lastAutoOpen < 60000) return;
        if (fromDest) destFlowAt = 0;
        lastAutoOpen = now;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;
            openHudViaHome(this, "티맵 길안내 감지 → HUD 자동 실행");
        } catch (Exception e) {
            log("HUD 자동 실행 실패: " + e.getMessage());
        }
    }

    /**
     * 티맵 미니 창은 '홈 버튼으로 나갈 때'만 생김 (HUD가 바로 덮으면 안 생김).
     * 그래서 티맵이 앞에 있으면 먼저 홈 버튼을 대신 누르고 1.2초 뒤 HUD를 띄움.
     */
    static void openHudViaHome(android.content.Context c, String why) {
        final android.content.Context app = c.getApplicationContext();
        Runnable open = () -> {
            try {
                app.startActivity(new android.content.Intent(app, HudActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                log(why);
            } catch (Exception e) {
                log("HUD 띄우기 실패: " + e.getMessage());
            }
        };
        TmapA11yService a = TmapA11yService.instance;
        if (a != null && TmapA11yService.TMAP.equals(TmapA11yService.fgPkg)) {
            a.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            new Handler(Looper.getMainLooper()).postDelayed(open, 1200);
        } else {
            open.run();
        }
    }

    /** 알림에 없는 회전 방향을 티맵 경로 계산 결과로 채움 */
    static void merge(NavData d) {
        if (!RouteGuide.fresh()) return;
        if (d.dir == null || d.dir.equals("NONE")) d.dir = RouteGuide.dir;
        if ((d.dist == null || d.dist.isEmpty()) && RouteGuide.distToNext > 0) {
            float m = RouteGuide.distToNext;
            d.dist = m >= 1000 ? String.format(java.util.Locale.KOREA, "%.1fkm", m / 1000f) : Math.round(m / 10f) * 10 + "m";
        }
        if (d.text == null || d.text.isEmpty()) d.text = RouteGuide.desc;
    }

    static boolean isWatched(String pkg, SharedPreferences p) {
        if (DEFAULT_PKGS.contains(pkg)) return true;
        String extra = p.getString("extra_pkgs", "");
        for (String s : extra.split("[,\\s]+")) {
            if (!s.isEmpty() && s.equals(pkg)) return true;
        }
        return false;
    }

    static synchronized void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        LOG.addFirst(t + " " + s);
        while (LOG.size() > 40) LOG.removeLast();
    }

    static synchronized String logText() {
        StringBuilder b = new StringBuilder();
        for (String s : LOG) b.append(s).append("\n\n");
        return b.length() == 0 ? "(아직 기록 없음 — 내비 길안내를 시작해 보세요)" : b.toString();
    }

    /** 테스트 버튼용 */
    static void sendTest(int step) {
        NavData d = new NavData();
        String[][] samples = {
                {"RIGHT", "300m", "300m 앞 우회전", "테스트로"},
                {"LEFT", "150m", "150m 앞 좌회전", "테스트로"},
                {"UTURN", "80m", "80m 앞 유턴", "테스트로"},
                {"KEEP_RIGHT", "1.2km", "1.2km 앞 오른쪽 방향", "경부고속도로"},
                {"STRAIGHT", "2km", "2km 직진", "테스트로"},
                {"ARRIVE", "50m", "목적지 도착", ""},
        };
        String[] s = samples[step % samples.length];
        d.dir = s[0];
        d.dist = s[1];
        d.text = s[2];
        d.sub = s[3];
        d.pkg = "test";
        last = d;
        lastKey = "test";
        UdpSender.send(d.toJson());
    }
}
