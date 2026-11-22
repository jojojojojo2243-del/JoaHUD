package com.joassam.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * "조아야" 듣기를 HUD 화면 밖(티맵 지도·홈 화면 등)에서도 계속 하는 서비스.
 * 안드로이드 규칙상 마이크 서비스는 앱 화면이 보일 때 시작해야 해서 HUD가 뜰 때 시작하고,
 * 그다음엔 다른 화면으로 가도 계속 들음. 시동을 끄면 멈춤.
 */
public class VoiceService extends Service {
    private static final String CH = "joa_voice";
    private static final int NOTI_ID = 7301;
    static volatile boolean running = false;
    private WakeWord ww;

    /** HUD 화면이 보일 때 부름 (그때만 시작이 허용됨) */
    static void start(Context c) {
        if (running) return;
        if (!c.getSharedPreferences("hud", MODE_PRIVATE).getBoolean("wake_on", true)) return;
        try {
            Intent i = new Intent(c, VoiceService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            NavListenerService.log("부르는 말 서비스 시작 못 함: " + e.getMessage());
        }
    }

    static void stop(Context c) {
        if (!running) return;
        try {
            c.stopService(new Intent(c, VoiceService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = noti();
            if (Build.VERSION.SDK_INT >= 30) {
                // 위치도 같이: HUD가 뒤에 있을 때(반반·지도 화면)도 속도·단속 경고가 계속 바뀌게
                boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                try {
                    startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | (loc ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0));
                } catch (Exception e1) {
                    startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                }
            } else startForeground(NOTI_ID, n);
        } catch (Exception e) {
            NavListenerService.log("부르는 말 서비스: 마이크 사용 허락 안 됨 → HUD 화면에서만 들음 (" + e.getClass().getSimpleName() + ")");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            ww = new WakeWord(this);
            ww.start();
            NavListenerService.log("부르는 말: HUD 밖(티맵·홈 화면)에서도 듣기 시작");
        }
        return START_NOT_STICKY;
    }

    private Notification noti() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "부르는 말 듣기", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        return b.setContentTitle("조아 HUD")
                .setContentText("'" + WakeWord.word(getSharedPreferences("hud", MODE_PRIVATE)) + "' 듣는 중 (어느 화면에서나)")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(PendingIntent.getActivity(this, 3, new Intent(this, HudActivity.class), fl))
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (ww != null) ww.stop();
        ww = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
