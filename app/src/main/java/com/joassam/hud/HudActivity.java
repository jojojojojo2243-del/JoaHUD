package com.joassam.hud;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** 서브폰: 받은 길안내를 반전 화면으로 표시 */
public class HudActivity extends Activity {

    private static java.lang.ref.WeakReference<HudActivity> current;

    /** 안드로이드 오토 해제 시 자동 종료 */
    static void closeIfOpen() {
        HudActivity a = current != null ? current.get() : null;
        if (a != null) a.runOnUiThread(a::finish);
    }

    private HudView hud;
    private LinearLayout controls;
    private volatile boolean running = true;
    private DatagramSocket socket;
    private WifiManager.MulticastLock mlock;
    private LocationManager lm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            hud.invalidate();
            handler.postDelayed(this, 1000);
        }
    };

    static volatile boolean visible = false;
    private static final float[] SPEED_SCALES = {1.0f, 1.8f, 2.5f};
    private static final String[] SPEED_NAMES = {"보통", "크게", "아주 크게"};

    static final int[] COLORS = {
            Color.rgb(0, 255, 102), Color.WHITE, Color.rgb(0, 229, 255), Color.rgb(255, 214, 0), Color.rgb(255, 150, 30)
    };
    static final String[] COLOR_NAMES = {"초록", "흰색", "하늘색", "노란색", "주황"};

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            lastKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
            lastKmhAt = System.currentTimeMillis();
            hud.setSpeed(loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f);
            RouteGuide.onLocation(loc);
            try {
                Alerts.onLocation(HudActivity.this, loc);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("hud", MODE_PRIVATE);
        // HUD 화면은 무조건 하나: 먼저 떠 있던 HUD가 있으면 닫음 (겹치면 마이크·화면이 서로 간섭)
        HudActivity old = current != null ? current.get() : null;
        if (old != null && old != this && !old.isFinishing()) {
            old.finish();
            NavListenerService.log("겹쳐 뜬 HUD 화면 하나 닫음 (HUD는 하나만)");
        }
        current = new java.lang.ref.WeakReference<>(this);
        // HUD 밖에서 부른 명령으로 HUD가 켜진 경우: 뜬 뒤 그 명령 실행
        final String pendSay = getIntent() != null ? getIntent().getStringExtra("say") : null;
        if (pendSay != null) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) say(pendSay);
        }, 1200);
        final String pendVoice = getIntent() != null ? getIntent().getStringExtra("voice") : null;
        final String pendBrain = getIntent() != null ? getIntent().getStringExtra("brain") : null;
        if (pendVoice != null || pendBrain != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing()) return;
                if (pendVoice != null && !voiceCommand(pendVoice)) say("잘 못 알아들었어요");
                if (pendBrain != null) askBrain(pendBrain);
            }, 1200);
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // 폰 자동 밝기를 따름 (끄면 항상 최대 밝기)
        getWindow().setAttributes(lp);
        applyBrightness();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hud = new HudView(this);
        hud.setColor(COLORS[prefs.getInt("color", 0) % COLORS.length]);
        hud.setAlertsOn(prefs.getBoolean("alerts_on", true));
        hud.setSpeedScale(SPEED_SCALES[prefs.getInt("speed_size", 1) % SPEED_SCALES.length]);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        applyMirror();

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(Color.argb(210, 40, 40, 40));
        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        row2.setGravity(Gravity.CENTER);
        controls.addView(row1);
        controls.addView(row2);
        row1.addView(ctrl("좌우반전", v -> {
            prefs.edit().putBoolean("mirror_h", !prefs.getBoolean("mirror_h", true)).apply();
            applyMirror();
        }));
        row1.addView(ctrl("상하반전", v -> {
            prefs.edit().putBoolean("flip_v", !prefs.getBoolean("flip_v", false)).apply();
            applyMirror();
        }));
        row1.addView(ctrl("색상", v -> {
            int c = (prefs.getInt("color", 0) + 1) % COLORS.length;
            prefs.edit().putInt("color", c).apply();
            hud.setColor(COLORS[c]);
        }));
        row1.addView(ctrl("속도크기", v -> {
            int i = (prefs.getInt("speed_size", 1) + 1) % SPEED_SCALES.length;
            prefs.edit().putInt("speed_size", i).apply();
            hud.setSpeedScale(SPEED_SCALES[i]);
            if (!quiet) android.widget.Toast.makeText(this, "속도 크기: " + SPEED_NAMES[i], android.widget.Toast.LENGTH_SHORT).show();
        }));
        btnAuto = ctrl("🔄 자동회전", v -> setOrient(0));
        btnLand = ctrl("▭ 가로", v -> setOrient(1));
        btnPort = ctrl("▯ 세로", v -> setOrient(2));
        row2.addView(btnAuto);
        row2.addView(btnLand);
        row2.addView(btnPort);
        row2.addView(ctrl("닫기", v -> finish()));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, clp);
        controls.setVisibility(View.GONE);

        hud.setOnClickListener(v -> {
            controls.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, 4000);
        });

        setContentView(root);
        applyOrientation();
        startReceiver();
        startGps();
        handler.post(tick);
    }

    // 기본은 자동 회전(폰을 돌리면 화면도 돌아감), 필요할 때만 가로/세로 고정
    private static final String[] ORIENT_NAMES = {"자동 회전", "가로 고정", "세로 고정"};
    private static final int[] ORIENT_VALUES = {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
    };
    private Button btnAuto, btnLand, btnPort;

    /**
     * 가로/세로 고정: 누른 순간의 '정확한 방향'(정방향·거꾸로)까지 기억해서 껐다 켜도 똑같이 나옴.
     * 폰을 대시보드에 눕혀 두면 센서가 방향을 헷갈려 켤 때마다 뒤집히던 문제 해결.
     * 같은 고정 버튼을 한 번 더 누르면 180도 돌림 (충전선 방향 바꿔 꽂았을 때).
     */
    private void setOrient(int o) {
        int prev = prefs.getInt("orient_mode", 0);
        int exact = -1;
        String name = ORIENT_NAMES[o];
        if (o == 1 || o == 2) {
            int saved = prefs.getInt("orient_exact", -1);
            boolean savedFits = o == 1 ? (saved == LAND || saved == LAND_R) : (saved == PORT || saved == PORT_R);
            if (prev == o && savedFits) {
                exact = o == 1 ? (saved == LAND ? LAND_R : LAND) : (saved == PORT ? PORT_R : PORT);
                name += " (180도 돌림)";
            } else {
                int rot = 0;
                try {
                    rot = getWindowManager().getDefaultDisplay().getRotation();
                } catch (Throwable ignored) {
                }
                // rotation: 0 세로, 1 가로(왼쪽으로 눕힘), 2 세로 거꾸로, 3 가로 거꾸로
                exact = o == 1 ? (rot == 3 ? LAND_R : LAND) : (rot == 2 ? PORT_R : PORT);
            }
        }
        prefs.edit().putInt("orient_mode", o).putInt("orient_exact", exact).apply();
        applyOrientation();
        if (!quiet) android.widget.Toast.makeText(this, "화면 방향: " + name, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int LAND = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            LAND_R = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            PORT = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            PORT_R = 9; // SCREEN_ORIENTATION_REVERSE_PORTRAIT

    private void applyOrientation() {
        int o = prefs.getInt("orient_mode", 0) % ORIENT_VALUES.length;
        int exact = prefs.getInt("orient_exact", -1);
        boolean fits = (o == 1 && (exact == LAND || exact == LAND_R)) || (o == 2 && (exact == PORT || exact == PORT_R));
        setRequestedOrientation(fits ? exact : ORIENT_VALUES[o]);
        Button[] bs = {btnAuto, btnLand, btnPort};
        for (int i = 0; i < bs.length; i++) {
            if (bs[i] == null) continue;
            bs[i].setTextColor(i == o ? Color.rgb(0, 230, 110) : Color.WHITE);
        }
    }

    private void applyMirror() {
        hud.setScaleX(prefs.getBoolean("mirror_h", true) ? -1f : 1f);
        hud.setScaleY(prefs.getBoolean("flip_v", false) ? -1f : 1f);
    }

    private Button ctrl(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setBackgroundColor(Color.argb(0, 0, 0, 0));
        btn.setOnClickListener(v -> {
            l.onClick(v);
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, 4000);
        });
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return btn;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ───────── 음성 명령 ("조아야" + 명령) ─────────
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;

    /** 밝기: 폰 자동 / 음성으로 정한 값 / 폰이 뜨거우면 낮춤 */
    private void applyBrightness() {
        android.content.SharedPreferences sp = getSharedPreferences("hud", MODE_PRIVATE);
        float b = sp.getBoolean("auto_bright", true) ? -1f : sp.getFloat("bright_level", 1f);
        int heat = Thermal.level(this);
        float cap = heat >= 2 ? 0.45f : heat == 1 ? 0.7f : 1f;
        if (b < 0 && heat > 0) b = cap;           // 자동 밝기여도 뜨거우면 낮춤
        else if (b > cap) b = cap;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b;
        getWindow().setAttributes(lp);
    }

    private final Runnable heatCheck = new Runnable() {
        @Override
        public void run() {
            applyBrightness();
            handler.postDelayed(this, 30_000L);
        }
    };

    /** 정해진 명령이 아닌 말 → AI 두뇌에게 물어 실행·대답 */
    void askBrain(String said) {
        // "아", "음" 같은 한 글자 소리는 AI에 보내지 않음 (무료 한도 아끼기)
        if (said == null || said.replaceAll("[\\s.,!?~]", "").length() < 2) return;
        NavListenerService.log("AI 두뇌에 물어봄: \"" + said + "\"");
        StringBuilder sit = new StringBuilder();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        sit.append(String.format(java.util.Locale.ROOT, "%d시 %d분, ", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)));
        sit.append("속도 ").append(Math.round(Math.max(0, hud.speedNow()))).append("km/h, ");
        sit.append(NavListenerService.tmapGuiding() && !NavListenerService.lastGuideDest.isEmpty()
                ? "길안내 중(목적지 " + NavListenerService.lastGuideDest + "), " : "길안내 없음, ");
        if (Alerts.inSection) sit.append("구간단속 중(평균 ").append(Alerts.sectionAvg()).append(", 제한 ").append(Alerts.sectionLimit).append("), ");
        sit.append("글자색 ").append(COLOR_NAMES[prefs.getInt("color", 0) % COLORS.length]);
        Brain.ask(this, said, sit.toString(), a -> {
            if (isFinishing()) return;
            if (!a.error.isEmpty()) {
                say(a.error);
                return;
            }
            if (!a.command.isEmpty() && voiceCommand(a.command)) {
                NavListenerService.log("AI → 명령 '" + a.command + "'");
                return;
            }
            if (!a.navigate.isEmpty()) {
                NavListenerService.log("AI → 목적지 '" + a.navigate + "'");
                say(a.say.isEmpty() ? a.navigate + "로 안내할게요" : a.say);
                handler.postDelayed(() -> startActivity(new android.content.Intent(this, DestActivity.class)
                        .putExtra("said", a.navigate).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)), 1500);
                return;
            }
            if (!a.say.isEmpty()) {
                NavListenerService.log("AI → \"" + a.say + "\"");
                say(a.say);
            }
        });
    }

    static boolean alive() {
        HudActivity a = current != null ? current.get() : null;
        return a != null && !a.isFinishing();
    }

    /** HUD 밖에서 들은 자유로운 말 → AI 두뇌 (HUD가 없으면 띄우면서) */
    static void brain(android.content.Context c, String said) {
        HudActivity a = current != null ? current.get() : null;
        if (a != null && !a.isFinishing()) a.askBrain(said);
        else openWith(c, "brain", said);
    }

    /** HUD를 띄우고, 뜬 뒤 그 말을 명령(voice) 또는 AI(brain)로 처리 */
    static void openWith(android.content.Context c, String kind, String said) {
        try {
            c.startActivity(new android.content.Intent(c, HudActivity.class).putExtra(kind, said)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** 명령어처럼 들리는지 (HUD가 꺼져 있을 때 HUD를 띄울지 판단) */
    static boolean looksLikeCommand(String said) {
        String s = said.replace(" ", "");
        return colorByName(s) >= 0 || has(s, "뒤집", "가로", "세로", "반전", "밝게", "어둡게", "크게", "작게", "소리", "볼륨",
                "경로", "안내", "티맵", "HUD", "허드", "다시시작", "구간", "몇시", "속도", "도움말", "경고", "주차번호", "블루투스", "블투", "정리");
    }

    /** 목적지 화면 등 다른 화면에서 들은 말도 HUD 명령이면 처리 */
    static boolean command(String said) {
        HudActivity a = current != null ? current.get() : null;
        if (a == null || a.isFinishing()) return false;
        return a.voiceCommand(said);
    }

    /** HUD를 띄우거나(이미 떠 있으면 그대로) 소리로 알림 — 운전 중엔 화면을 못 보니 말로 */
    static void announce(android.content.Context c, String msg) {
        HudActivity h = current != null ? current.get() : null;
        if (h != null && !h.isFinishing()) {
            h.runOnUiThread(() -> h.say(msg));
            return;
        }
        openWith(c, "say", msg);
    }

    private void say(String msg) {
        // 음성 명령 결과는 소리로만 (앞유리에 글자 안 띄움)
        WakeWord.spoke(msg);
        try {
            if (tts == null) {
                tts = new android.speech.tts.TextToSpeech(this, st -> {
                    ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        tts.setLanguage(java.util.Locale.KOREAN);
                        tts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cmd");
                    }
                });
            } else if (ttsReady) {
                tts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cmd");
            }
        } catch (Exception ignored) {
        }
    }

    /** 티맵을 잠깐 열어 버튼을 누른 뒤 다시 HUD로 */
    private Runnable backToHud() {
        final android.content.Context app = getApplicationContext();
        return () -> {
            try {
                app.startActivity(new android.content.Intent(app, HudActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        };
    }

    /** "흰색으로", "초록색" 같은 말 → 색 번호 (없으면 -1) */
    private static int colorByName(String s) {
        if (has(s, "흰색", "하얀", "하양", "화이트", "백색")) return 1;
        if (has(s, "초록", "녹색", "그린", "연두")) return 0;
        if (has(s, "하늘", "파란", "파랑", "블루", "시안", "청색")) return 2;
        if (has(s, "노란", "노랑", "옐로", "황색")) return 3;
        if (has(s, "주황", "오렌지")) return 4;
        return -1;
    }

    /** 새로 늘린 음성 명령 (밝기·소리 크기·시간·속도·구간 평균·도움말·경고 켜고 끄기·지도 보기) */
    private String voiceCommandMore(String s) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        // 블루투스 켜고 끄기 ("블루" 가 하늘색으로 잘못 가지 않게 색보다 먼저)
        if (has(s, "블루투스", "블루투수", "블루트스", "부루투스", "블투")) {
            Boolean want = has(s, "꺼", "끄", "끊", "종료", "그만", "오프", "OFF") ? Boolean.FALSE
                    : has(s, "켜", "키", "연결", "온", "ON", "시작") ? Boolean.TRUE : null;
            return TmapA11yService.setBluetooth(want, m -> {
                if (m != null && !isFinishing()) say(m);
            });
        }
        if (has(s, "HUD켜", "HUD보여", "HUD로", "허드켜", "허드보여", "헤드업켜", "헤드업보여", "헤드업으로", "에이치유디켜")) {
            startActivity(new android.content.Intent(this, HudActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return "HUD를 보여 드릴게요";
        }
        if (has(s, "정리", "리셋", "초기화", "원래대로", "꼬였")) {
            TmapA11yService.cleanUp();
            return "화면 정리할게요";
        }
        if (has(s, "도움말", "뭐할수", "명령어", "사용법", "뭐라고말", "할수있는")) {
            return "말할 수 있는 명령은, 뒤집어, 가로로, 흰색으로, 크게, 밝게, 어둡게, 소리 크게, 경로 다시, 목적지 바꿔, "
                    + "안내 종료, 티맵 꺼, HUD 꺼, 다시 시작, 구간 끝, 몇 시야, 지금 속도, 경고 꺼, 경고 켜, 블루투스 켜, 블루투스 꺼, 정리해 예요. "
                    + "명령 뒤 20초 동안은 부르는 말 없이 바로 말해도 돼요"
                    + (Brain.enabled(this) ? ". 그 밖의 말은 AI가 알아들어요" : "");
        }
        if (has(s, "소리크게", "볼륨올", "볼륨크게", "소리키워", "크게말", "안내크게")) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0);
            return "소리를 키웠어요";
        }
        if (has(s, "소리작게", "볼륨내", "볼륨작게", "소리줄여", "작게말", "안내작게", "조용히")) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0);
            return "소리를 줄였어요";
        }
        if (has(s, "밝게", "밝기올", "환하게", "더밝")) {
            float b = Math.min(1f, prefs.getFloat("bright_level", 0.7f) + 0.2f);
            prefs.edit().putBoolean("auto_bright", false).putFloat("bright_level", b).apply();
            applyBrightness();
            return "밝게 했어요";
        }
        if (has(s, "어둡게", "밝기내", "밝기줄", "덜밝", "눈부")) {
            float b = Math.max(0.15f, prefs.getFloat("bright_level", 1f) - 0.2f);
            prefs.edit().putBoolean("auto_bright", false).putFloat("bright_level", b).apply();
            applyBrightness();
            return "어둡게 했어요";
        }
        if (has(s, "자동밝기", "밝기자동")) {
            prefs.edit().putBoolean("auto_bright", true).apply();
            applyBrightness();
            return "밝기를 자동으로 맞출게요";
        }
        if (has(s, "몇시", "지금시간", "시간알려")) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int hh = cal.get(java.util.Calendar.HOUR_OF_DAY), mm = cal.get(java.util.Calendar.MINUTE);
            return (hh < 12 ? "오전 " : "오후 ") + (hh % 12 == 0 ? 12 : hh % 12) + "시 " + mm + "분이에요";
        }
        if (has(s, "지금속도", "속도알려", "몇키로", "몇킬로")) {
            int v = Math.round(Math.max(0, hud.speedNow()));
            return "지금 시속 " + v + "킬로예요";
        }
        if (has(s, "구간평균", "평균속도", "평균몇")) {
            if (!Alerts.inSection) return "지금은 구간단속 중이 아니에요";
            int avg = Alerts.sectionAvg(), rem = Alerts.sectionRemain();
            return "구간 평균 " + Math.max(avg, 0) + "킬로" + (Alerts.sectionLimit > 0 ? ", 제한 " + Alerts.sectionLimit : "")
                    + (rem >= 0 ? ", 끝까지 " + (rem >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f킬로", rem / 1000f) : rem + "미터") : "") + "예요";
        }
        if (has(s, "경고꺼", "단속경고꺼", "카메라경고꺼", "경고끄", "경고그만")) {
            prefs.edit().putBoolean("alerts_on", false).apply();
            hud.setAlertsOn(false);
            return "단속 경고를 껐어요";
        }
        if (has(s, "경고켜", "단속경고켜", "카메라경고켜")) {
            prefs.edit().putBoolean("alerts_on", true).apply();
            hud.setAlertsOn(true);
            return "단속 경고를 켰어요";
        }
        if (has(s, "주차번호꺼", "전화번호꺼", "번호화면꺼")) {
            prefs.edit().putBoolean("park_show", false).apply();
            return "시동 끌 때 주차 번호를 안 띄울게요";
        }
        if (has(s, "주차번호켜", "전화번호켜", "번호화면켜")) {
            prefs.edit().putBoolean("park_show", true).apply();
            return "시동 끌 때 주차 번호를 띄울게요";
        }
        return null;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /**
     * "조아야" 다음에 한 말이 HUD 명령이면 실행하고 true.
     * 명령이 아니면 false → 목적지로 봄.
     */
    /** 음성으로 조작 중이면 화면 알림(Toast)을 띄우지 않음 */
    private boolean quiet = false;

    boolean voiceCommand(String said) {
        quiet = true;
        try {
            return voiceCommand0(said);
        } finally {
            quiet = false;
        }
    }

    /** 자주 잘못 알아듣는 말 → 원래 명령으로 (발음이 비슷하면 고침) */
    private static final String[] CANON = {
            "뒤집어", "가로로", "세로로", "좌우반전", "상하반전", "색바꿔", "흰색", "초록색", "하늘색", "노란색", "주황색",
            "크게", "작게", "밝게", "어둡게", "소리크게", "소리작게", "HUD꺼", "다시시작", "경로다시", "목적지바꿔",
            "안내종료", "티맵꺼", "구간끝", "몇시야", "지금속도", "구간평균", "도움말", "경고꺼", "경고켜",
            "블루투스켜", "블루투스꺼", "정리해"};

    private static String canon(String s) {
        if (s.length() > 8) return s;
        String best = s;
        double bs = 0;
        for (String c : CANON) {
            double v = VoicePick.similar(s, c);
            if (v > bs) {
                bs = v;
                best = c;
            }
        }
        return bs >= 0.8 && s.length() >= 2 ? best : s;
    }

    private boolean voiceCommand0(String said) {
        String s0 = said.replace(" ", "").replace(".", "").replace("요", "").toUpperCase(java.util.Locale.ROOT);
        String s = s0;
        String cmd = voiceCommandMore(s);
        if (cmd == null) {
            String c2 = canon(s0);
            if (!c2.equals(s0)) {
                s = c2;
                cmd = voiceCommandMore(s);
            }
        }
        if (cmd != null) {
            NavListenerService.log("음성 명령: \"" + said + "\" → " + cmd);
            say(cmd);
            return true;
        }
        // ── 구간단속 표시 끄기 ──
        if (has(s, "구간끝", "구간단속끝", "구간꺼", "구간단속꺼", "구간종료", "구간단속종료", "평균꺼", "구간지워")) {
            cmd = Alerts.cancelSection() ? "구간단속 표시를 껐어요" : "지금은 구간단속 중이 아니에요";
        }
        // ── 티맵 ──
        else if (has(s, "티맵꺼", "티맵종료", "티맵닫", "티맵끄", "내비꺼", "내비종료", "네비꺼", "네비종료")) {
            cmd = "티맵을 끌게요";
            TmapA11yService.endGuidance(backToHud(), true);
        } else if (has(s, "안내종료", "안내꺼", "안내끄", "안내그만", "길안내종료", "안내중지", "안내취소")) {
            cmd = "길안내를 끝낼게요";
            TmapA11yService.endGuidance(backToHud(), false);
        } else if (has(s, "경로다시", "재탐색", "다시탐색", "경로재검색", "경로재탐색", "길다시", "다시안내", "경로새로")) {
            if (DestActivity.reroute(this)) cmd = "경로를 다시 찾을게요";
            else cmd = "다시 안내할 목적지가 없어요";
        } else if (has(s, "목적지바꿔", "목적지변경", "목적지바꾸", "다른곳", "목적지다시", "어디로")) {
            cmd = "어디로 갈까요";
            startActivity(new android.content.Intent(this, DestActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            NavListenerService.log("음성 명령: " + said + " → 목적지 바꾸기");
            return true;
        }
        // ── HUD 켜고 끄기 ──
        else if (has(s, "다시시작", "재시작", "새로고침", "HUD다시", "허드다시", "에이치유디다시")) {
            cmd = "HUD를 다시 시작할게요";
            android.content.Intent i = new android.content.Intent(this, HudActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            finish();
            handler.postDelayed(() -> getApplicationContext().startActivity(i), 600);
        } else if (has(s, "HUD꺼", "HUD종료", "HUD끄", "HUD닫", "허드꺼", "허드종료", "에이치유디꺼", "에이치유디종료", "화면꺼", "화면종료", "화면닫")) {
            cmd = "HUD를 끌게요";
            handler.postDelayed(this::finish, 1200);
        }
        // ── 화면 조정 ──
        else if (has(s, "가로고정", "가로로", "가로화면", "가로모드")) {
            setOrient(1);
            cmd = "가로로 고정했어요";
        } else if (has(s, "세로고정", "세로로", "세로화면", "세로모드")) {
            setOrient(2);
            cmd = "세로로 고정했어요";
        } else if (has(s, "자동회전", "자동으로돌", "회전자동")) {
            setOrient(0);
            cmd = "자동 회전으로 바꿨어요";
        } else if (has(s, "뒤집어", "뒤집기", "거꾸로", "180도", "반대로돌", "돌려")) {
            int o = prefs.getInt("orient_mode", 0);
            if (o == 1 || o == 2) setOrient(o); // 고정 중이면 180도 돌림
            else {
                prefs.edit().putBoolean("flip_v", !prefs.getBoolean("flip_v", false))
                        .putBoolean("mirror_h", !prefs.getBoolean("mirror_h", true)).apply();
                applyMirror();
            }
            cmd = "화면을 뒤집었어요";
        } else if (has(s, "좌우반전", "좌우바꿔", "좌우뒤집", "거울")) {
            prefs.edit().putBoolean("mirror_h", !prefs.getBoolean("mirror_h", true)).apply();
            applyMirror();
            cmd = "좌우를 바꿨어요";
        } else if (has(s, "상하반전", "위아래바꿔", "위아래뒤집", "상하바꿔")) {
            prefs.edit().putBoolean("flip_v", !prefs.getBoolean("flip_v", false)).apply();
            applyMirror();
            cmd = "위아래를 바꿨어요";
        } else if (colorByName(s) >= 0) {
            int c = colorByName(s);
            prefs.edit().putInt("color", c).apply();
            hud.setColor(COLORS[c]);
            cmd = "글자 색을 " + COLOR_NAMES[c] + "으로 바꿨어요";
        } else if (has(s, "색바꿔", "색깔", "색상", "색변경", "다른색")) {
            int c = (prefs.getInt("color", 0) + 1) % COLORS.length;
            prefs.edit().putInt("color", c).apply();
            hud.setColor(COLORS[c]);
            cmd = "색을 바꿨어요";
        } else if (has(s, "크게", "키워")) {
            int i = Math.min(prefs.getInt("speed_size", 1) + 1, SPEED_SCALES.length - 1);
            prefs.edit().putInt("speed_size", i).apply();
            hud.setSpeedScale(SPEED_SCALES[i]);
            cmd = "속도 크기 " + SPEED_NAMES[i];
        } else if (has(s, "작게", "줄여")) {
            int i = Math.max(prefs.getInt("speed_size", 1) - 1, 0);
            prefs.edit().putInt("speed_size", i).apply();
            hud.setSpeedScale(SPEED_SCALES[i]);
            cmd = "속도 크기 " + SPEED_NAMES[i];
        }
        if (cmd == null) return false;
        NavListenerService.log("음성 명령: \"" + said + "\" → " + cmd);
        say(cmd);
        return true;
    }

    /** 같은 폰 안에서 찍은 티맵 안내판을 바로 넘겨받음 (UDP·JPEG 거치지 않아 끊김 없음) */
    static boolean deliverFrame(Bitmap b) {
        HudActivity a = current != null ? current.get() : null;
        if (a == null || a.isFinishing() || a.hud == null) return false;
        a.runOnUiThread(() -> a.hud.setFrame(b));
        return true;
    }

    private void startReceiver() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("joahud");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
        } catch (Exception ignored) {
        }

        Thread t = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (running) {
                try {
                    if (socket == null || socket.isClosed()) {
                        socket = new DatagramSocket(null);
                        socket.setReuseAddress(true);
                        socket.setBroadcast(true);
                        socket.bind(new InetSocketAddress(NavData.PORT));
                        socket.setSoTimeout(1000);
                    }
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    socket.receive(pk);
                    byte[] data = pk.getData();
                    int len = pk.getLength();
                    if (len > 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8) {
                        // 티맵 화면 조각 (JPEG)
                        final Bitmap frame = BitmapFactory.decodeByteArray(data, 0, len);
                        if (frame != null) runOnUiThread(() -> hud.setFrame(frame));
                        continue;
                    }
                    String s = new String(data, 0, len, StandardCharsets.UTF_8);
                    final NavData d = NavData.fromJson(s);
                    if (d != null) runOnUiThread(() -> hud.setNav(d));
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    try {
                        if (socket != null) socket.close();
                    } catch (Exception ignored) {
                    }
                    socket = null;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "hud-recv");
        t.setDaemon(true);
        t.start();
    }

    /** 최근 GPS 속도 (시동 꺼짐 판단용: 달리는 중이면 충전이 끊겨도 시동 꺼짐 아님) */
    static volatile float lastKmh = 0;
    static volatile long lastKmhAt = 0;

    /** 지금 확실히 달리는 중 (최근 5초 안 위치에서 시속 15km 넘음) */
    static boolean movingNow() {
        return System.currentTimeMillis() - lastKmhAt < 5_000L && lastKmh > 15f;
    }

    static boolean movingRecently() {
        return System.currentTimeMillis() - lastKmhAt < 15_000L && lastKmh > 8f;
    }

    private void startGps() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, locListener, Looper.getMainLooper());
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        // "조아야" 듣기: 가능하면 HUD 밖(티맵·홈 화면)에서도 듣는 서비스로, 안 되면 HUD 화면에서만
        if (AutoService.ignOnAt > 0) { // 시동 뒤 HUD가 뜨기까지 걸린 시간 (느리다는 말 확인용)
            NavListenerService.log(String.format(java.util.Locale.ROOT, "HUD 화면 뜸 (시동 후 %.1f초)",
                    (System.currentTimeMillis() - AutoService.ignOnAt) / 1000f));
            AutoService.ignOnAt = 0;
        }
        VoiceService.start(this);
        handler.postDelayed(() -> {
            if (!VoiceService.running && visible) {
                if (wake == null) wake = new WakeWord(this);
                wake.start();
            }
        }, 1500);
        handler.removeCallbacks(heatCheck);
        handler.post(heatCheck);
    }

    private WakeWord wake;

    @Override
    protected void onPause() {
        visible = false;
        if (wake != null) wake.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {
        }
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        try {
            if (lm != null) lm.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        try {
            if (mlock != null && mlock.isHeld()) mlock.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
