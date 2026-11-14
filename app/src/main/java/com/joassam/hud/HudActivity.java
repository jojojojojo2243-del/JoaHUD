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
            Color.rgb(0, 255, 102), Color.WHITE, Color.rgb(0, 229, 255), Color.rgb(255, 214, 0)
    };

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
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
        current = new java.lang.ref.WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // 폰 자동 밝기를 따름 (끄면 항상 최대 밝기)
        lp.screenBrightness = getSharedPreferences("hud", MODE_PRIVATE).getBoolean("auto_bright", true)
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : 1f;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hud = new HudView(this);
        hud.setColor(COLORS[prefs.getInt("color", 0) % COLORS.length]);
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
            android.widget.Toast.makeText(this, "속도 크기: " + SPEED_NAMES[i], android.widget.Toast.LENGTH_SHORT).show();
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
        android.widget.Toast.makeText(this, "화면 방향: " + name, android.widget.Toast.LENGTH_SHORT).show();
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

    private void say(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
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

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /**
     * "조아야" 다음에 한 말이 HUD 명령이면 실행하고 true.
     * 명령이 아니면 false → 목적지로 봄.
     */
    boolean voiceCommand(String said) {
        String s = said.replace(" ", "").replace(".", "").toUpperCase(java.util.Locale.ROOT);
        String cmd = null;
        // ── 티맵 ──
        if (has(s, "티맵꺼", "티맵종료", "티맵닫", "티맵끄", "내비꺼", "내비종료", "네비꺼", "네비종료")) {
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
        // 운전 중 "조아야" 부르면 목적지 바꾸기 (HUD가 떠 있을 때만 들음)
        if (wake == null) wake = new WakeWord(this);
        wake.start();
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
