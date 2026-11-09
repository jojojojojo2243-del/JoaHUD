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
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        boolean logAll = p.getBoolean("log_all", false);
        boolean watched = isWatched(pkg, p);
        if (!watched && !logAll) return;

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

    private long lastAutoOpen = 0;

    /** 서브폰 티맵 단독 모드: 티맵 길안내 중 홈으로 나가면 HUD를 자동으로 띄움 */
    private void autoOpenHud(String pkg, SharedPreferences p) {
        if (!p.getBoolean("tmap_auto_hud", true)) return;
        if (!pkg.equals(TmapA11yService.TMAP)) return;
        if (HudActivity.visible || MainActivity.visible) return;
        if (TmapA11yService.TMAP.equals(TmapA11yService.fgPkg)) return; // 티맵 화면을 보고 있는 중
        long now = System.currentTimeMillis();
        if (now - lastAutoOpen < 60000) return;
        lastAutoOpen = now;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;
            startActivity(new android.content.Intent(this, HudActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
            log("티맵 길안내 감지 → HUD 자동 실행");
        } catch (Exception e) {
            log("HUD 자동 실행 실패: " + e.getMessage());
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
