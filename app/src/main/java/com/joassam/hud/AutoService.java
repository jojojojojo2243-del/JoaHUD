package com.joassam.hud;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 차량용 서브폰 상주 서비스
 * - 안드로이드 오토 연결 감지 → HUD 자동 실행 / 해제 시 HUD 종료
 * - 인터넷이 없으면 등록된 가족 폰의 블루투스 테더링 연결 시도
 */
public class AutoService extends Service {

    private static final String CH = "auto";
    private static final int NOTI_ID = 9;
    private static final int NOTI_NET = 10;
    // androidx.car.app CarConnection 과 같은 규약 (안드로이드 오토가 제공)
    private static final Uri CAR_URI = Uri.parse("content://androidx.car.app.connection");
    private static final String CAR_ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";

    static volatile boolean running = false;
    static volatile int carState = 0; // 0 없음, 1 차량 내장, 2 안드로이드 오토
    static volatile String netStatus = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasConnected = false;
    private long lastNetTry = 0;
    private Object panProxy;
    private boolean panLogged = false;

    private final BroadcastReceiver carReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            check();
        }
    };

    // ───────── 시동 감지 (서브폰 충전 시작/끊김) ─────────
    static volatile boolean ignOn = false;
    private long lastPowerOn = 0;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
                lastPowerOn = System.currentTimeMillis();
                // 메인폰 핫스팟이 붙을 시간을 조금 기다렸다가 판단
                handler.postDelayed(AutoService.this::onPowerOn, 8000);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) {
                // 무선 충전에서 폰이 살짝 밀려 잠깐 끊긴 것일 수 있으니 6초 기다렸다 판단
                handler.removeCallbacks(confirmOff);
                handler.postDelayed(confirmOff, 6000);
            }
        }
    };

    private final Runnable confirmOff = () -> {
        if (isCharging()) {
            NavListenerService.log("충전이 잠깐 끊겼다 다시 연결됨 → 시동 유지");
            return;
        }
        onPowerOff();
    };

    private void onPowerOn() {
        if (ignOn) {
            handler.removeCallbacks(confirmOff); // 운행 중 잠깐 끊겼던 것 → 아무것도 안 함
            return;
        }
        ParkActivity.closeIfOpen();
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        if (queryCarState() != 0) return;          // 안드로이드 오토 방식이면 기존 흐름 사용
        if (!isCharging()) return;                 // 8초 사이에 빠졌으면 무시
        String ssid = p.getString("car_ssid", "");
        if (!ssid.isEmpty()) {
            String now = currentSsid();
            if (!ssid.equals(now)) {
                NavListenerService.log("충전 시작 — 차 와이파이(" + ssid + ") 아님(" + now + ") → 시동으로 안 봄");
                return;
            }
        }
        ignOn = true;
        NavListenerService.log("🔑 시동 켜짐(충전 시작)");
        updateNoti("🚗 운행 중");
        if (HudActivity.visible || DestActivity.visible) return;
        Intent i;
        if (p.getBoolean("ign_dest", true)) i = new Intent(this, DestActivity.class);
        else i = new Intent(this, HudActivity.class);
        startFromBackground(i);
    }

    private void onPowerOff() {
        if (!ignOn) return;
        ignOn = false;
        NavListenerService.log("🔑 시동 꺼짐(충전 끊김)");
        updateNoti("대기 중 — 시동을 기다립니다");
        showPark();
    }

    private void showPark() {
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        if (p.getBoolean("auto_close", true)) HudActivity.closeIfOpen();
        NavListenerService.stoppingTmapAt = System.currentTimeMillis();
        final boolean park = p.getBoolean("park_show", true) && !p.getString("park_phone", "").isEmpty();
        Runnable showParkScreen = () -> {
            NavListenerService.stoppingTmapAt = System.currentTimeMillis();
            if (park) startFromBackground(new Intent(this, ParkActivity.class));
        };
        if (p.getBoolean("stop_tmap", true) && System.currentTimeMillis() - NavListenerService.lastGuideSeen < 10 * 60_000L) {
            // 알림 버튼이 있으면 그걸로, 없으면 티맵 화면에서 [안내 종료]를 대신 누름 → 그다음 번호 화면
            if (NavListenerService.stopTmap()) handler.postDelayed(showParkScreen, 1500);
            else TmapA11yService.endGuidance(showParkScreen);
        } else {
            handler.postDelayed(showParkScreen, 1500);
        }
    }

    private boolean isCharging() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    @SuppressWarnings("deprecation")
    static String currentSsid(Context c) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            String s = wm.getConnectionInfo().getSSID();
            if (s == null || s.contains("unknown ssid")) return "";
            return s.replace("\"", "");
        } catch (Exception e) {
            return "";
        }
    }

    private String currentSsid() {
        return currentSsid(this);
    }

    private void startFromBackground(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                NavListenerService.log("'다른 앱 위에 표시' 권한이 없어 화면을 못 띄움");
                return;
            }
            startActivity(i);
        } catch (Exception e) {
            NavListenerService.log("화면 띄우기 실패: " + e.getMessage());
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            check();
            handler.postDelayed(this, 4000);
        }
    };

    public static void start(Context c) {
        // Android 14+: 블루투스 권한 없이 연결기기 서비스를 시작하면 앱이 강제 종료됨
        if (Build.VERSION.SDK_INT >= 34
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            netStatus = "블루투스 권한 필요";
            return;
        }
        Intent i = new Intent(c, AutoService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground("대기 중 — 안드로이드 오토 연결을 기다립니다");
        if (!running) {
            running = true;
            IntentFilter f = new IntentFilter(CAR_ACTION);
            f.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
            f.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(carReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(carReceiver, f);
            IntentFilter pf = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
            pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
            registerReceiver(powerReceiver, pf);
            initPan();
            handler.post(poll);
        }
        return START_STICKY;
    }

    private long lastUpdateCheck = 0;

    private void check() {
        A11yGuard.ensure(this);   // 안내판 읽기가 꺼지면 바로 다시 켬
        int st = queryCarState();
        // 주차 중(차량 미연결)이고 인터넷이 될 때 15분마다 업데이트 확인·설치
        // (운전 중에는 HUD가 꺼지지 않도록 설치하지 않음)
        long now = System.currentTimeMillis();
        if (st == 0 && !ignOn && now - lastUpdateCheck > 15 * 60_000L && hasInternet()
                && getSharedPreferences("hud", MODE_PRIVATE).getBoolean("auto_update", true)) {
            lastUpdateCheck = now;
            Updater.check(this, true, null);
        }
        // 단속카메라 공공데이터: 주차 중 인터넷 되면 일주일마다 자동 업데이트
        if (st == 0 && !ignOn && hasInternet()) Alerts.maybeUpdate(this);
        carState = st;
        boolean connected = st != 0;
        if (connected && !wasConnected) onCarConnected();
        if (!connected && wasConnected) onCarDisconnected();
        wasConnected = connected;
        ensureInternet(connected);
    }

    private int queryCarState() {
        // 1) 안드로이드 오토 공식 연결 상태
        try (Cursor c = getContentResolver().query(CAR_URI, new String[]{"CarConnectionState"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int v = c.getInt(c.getColumnIndexOrThrow("CarConnectionState"));
                if (v != 0) return v;
            }
        } catch (Exception ignored) {
        }
        // 2) 보조: 차량 모드
        try {
            UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR) return 2;
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void onCarConnected() {
        updateNoti("🚗 안드로이드 오토 연결됨 — HUD 실행");
        NavListenerService.log("안드로이드 오토 연결됨");
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        try {
            TmapEdc.start(this);
        } catch (Throwable t) {
            NavListenerService.log("EDC 시작 오류: " + t);
        }
        if (p.getBoolean("auto_hud", true)) {
            handler.postDelayed(this::launchHud, 1500);
        }
    }

    private void onCarDisconnected() {
        updateNoti("대기 중 — 안드로이드 오토 연결을 기다립니다");
        NavListenerService.log("안드로이드 오토 해제됨");
        SharedPreferences p = getSharedPreferences("hud", MODE_PRIVATE);
        if (p.getBoolean("auto_close", true)) HudActivity.closeIfOpen();
        ignOn = false;
        showPark();
        // 시동 끄면 1분 뒤 바로 업데이트 확인
        lastUpdateCheck = System.currentTimeMillis() - 14 * 60_000L;
    }

    private void launchHud() {
        Intent i = new Intent(this, HudActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (canOverlay) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        // 다른 앱 위에 표시 권한이 없으면 알림을 눌러 실행
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 3, i, fl);
        Notification n = builder()
                .setContentTitle("HUD 시작하려면 누르세요")
                .setContentText("'다른 앱 위에 표시'를 허용하면 자동으로 켜집니다")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(11, n);
    }

    // ───────── 인터넷 (블루투스 테더링) ─────────

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureInternet(boolean carConnected) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (hasInternet()) {
            netStatus = "✅ 인터넷 연결됨";
            nm.cancel(NOTI_NET);
            return;
        }
        netStatus = "❌ 인터넷 없음";
        if (!carConnected) return;
        long now = System.currentTimeMillis();
        if (now - lastNetTry < 20000) return;
        lastNetTry = now;

        boolean tried = tryPanConnect();
        if (!tried) {
            Intent bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 4, bt, fl);
            Notification n = builder()
                    .setContentTitle("인터넷 없음 — 가족 폰 블루투스 테더링 연결")
                    .setContentText("눌러서 블루투스 설정 → 폰 이름 옆 ⚙ → '인터넷 액세스' 켜기")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentIntent(pi)
                    .setOngoing(false)
                    .build();
            nm.notify(NOTI_NET, n);
        }
    }

    /** 숨은 API로 블루투스 PAN(테더링) 연결 시도. 기종에 따라 막혀 있을 수 있음 */
    private boolean tryPanConnect() {
        if (panProxy == null) return false;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        Set<String> family = getSharedPreferences("hud", MODE_PRIVATE).getStringSet("family", new HashSet<>());
        if (family.isEmpty()) return false;
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) return false;
        boolean any = false;
        for (BluetoothDevice d : ad.getBondedDevices()) {
            if (!family.contains(d.getAddress())) continue;
            try {
                Method m = panProxy.getClass().getMethod("connect", BluetoothDevice.class);
                Object r = m.invoke(panProxy, d);
                NavListenerService.log("테더링 연결 시도: " + d.getName() + " → " + r);
                if (Boolean.TRUE.equals(r)) any = true;
            } catch (Throwable t) {
                if (!panLogged) {
                    panLogged = true;
                    NavListenerService.log("테더링 자동연결 불가(기종 제한) → 메인폰 핫스팟(와이파이) 사용 권장");
                }
            }
        }
        return any;
    }

    private void initPan() {
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null) return;
            ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    panProxy = proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    panProxy = null;
                }
            }, 5 /* BluetoothProfile.PAN */);
        } catch (Throwable ignored) {
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    // ───────── 알림 ─────────

    private Notification.Builder builder() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "차량 자동 모드", NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(this, CH);
        }
        return new Notification.Builder(this);
    }

    private Notification mainNoti(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        return builder().setContentTitle("조아 HUD 자동 모드")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(PendingIntent.getActivity(this, 2, open, fl))
                .setOngoing(true)
                .build();
    }

    private void startAsForeground(String text) {
        Notification n = mainNoti(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Exception e) {
            // 권한 부족 시 일반 서비스로라도 동작
        }
    }

    private void updateNoti(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_ID, mainNoti(text));
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(carReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(powerReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
