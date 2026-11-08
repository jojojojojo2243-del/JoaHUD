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

    static final int[] COLORS = {
            Color.rgb(0, 255, 102), Color.WHITE, Color.rgb(0, 229, 255), Color.rgb(255, 214, 0)
    };

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            hud.setSpeed(loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f);
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
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hud = new HudView(this);
        hud.setColor(COLORS[prefs.getInt("color", 0) % COLORS.length]);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        applyMirror();

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setBackgroundColor(Color.argb(200, 40, 40, 40));
        controls.setGravity(Gravity.CENTER);
        controls.addView(ctrl("좌우반전", v -> {
            prefs.edit().putBoolean("mirror_h", !prefs.getBoolean("mirror_h", true)).apply();
            applyMirror();
        }));
        controls.addView(ctrl("상하반전", v -> {
            prefs.edit().putBoolean("flip_v", !prefs.getBoolean("flip_v", false)).apply();
            applyMirror();
        }));
        controls.addView(ctrl("색상", v -> {
            int c = (prefs.getInt("color", 0) + 1) % COLORS.length;
            prefs.edit().putInt("color", c).apply();
            hud.setColor(COLORS[c]);
        }));
        controls.addView(ctrl("닫기", v -> finish()));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, clp);
        controls.setVisibility(View.GONE);

        hud.setOnClickListener(v -> {
            controls.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideControls);
            handler.postDelayed(hideControls, 4000);
        });

        setContentView(root);
        startReceiver();
        startGps();
        handler.post(tick);
    }

    private void applyMirror() {
        hud.setScaleX(prefs.getBoolean("mirror_h", true) ? -1f : 1f);
        hud.setScaleY(prefs.getBoolean("flip_v", false) ? -1f : 1f);
    }

    private Button ctrl(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
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
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
