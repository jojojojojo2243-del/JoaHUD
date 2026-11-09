package com.joassam.hud;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 시동을 끄면 서브폰에 큰 글씨로 전화번호를 띄우는 '주차 번호판' 화면.
 * - 검은 바탕, 화면 밝기 낮게
 * - 화면 탈 방지를 위해 1분마다 글자 위치를 조금씩 옮김
 * - 배터리 20% 미만이면 화면을 끄고 종료, 다시 충전(시동)되면 자동 종료
 */
public class ParkActivity extends Activity {

    static volatile boolean visible = false;
    private static WeakReference<ParkActivity> current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private int step = 0;

    static void closeIfOpen() {
        ParkActivity a = current != null ? current.get() : null;
        if (a != null) a.runOnUiThread(a::finish);
    }

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged != 0) return; // 충전 중이면 그대로 (시동 켜짐은 AutoService가 처리)
            if (level >= 0 && level * 100 / Math.max(1, scale) < 20) {
                NavListenerService.log("배터리 20% 미만 → 주차 번호 화면 끔");
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                finish();
            }
        }
    };

    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            step++;
            int dx = ((step % 5) - 2) * dp(10);
            int dy = (((step / 5) % 5) - 2) * dp(10);
            box.setTranslationX(dx);
            box.setTranslationY(dy);
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = getSharedPreferences("hud", MODE_PRIVATE).getInt("park_bright", 35) / 100f;
        getWindow().setAttributes(lp);

        String phone = getSharedPreferences("hud", MODE_PRIVATE).getString("park_phone", "");
        String msg = getSharedPreferences("hud", MODE_PRIVATE).getString("park_msg", "주차 중 연락처");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView t1 = new TextView(this);
        t1.setText(msg);
        t1.setTextColor(0xFFDDDDDD);
        t1.setTextSize(26);
        t1.setGravity(Gravity.CENTER);
        box.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(phone);
        t2.setTextColor(Color.WHITE);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setGravity(Gravity.CENTER);
        t2.setSingleLine(true);
        t2.setAutoSizeTextTypeUniformWithConfiguration(24, 140, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(t2, new LinearLayout.LayoutParams(-1, dp(160)));

        TextView t3 = new TextView(this);
        t3.setText("(화면을 두 번 누르면 닫힘)");
        t3.setTextColor(0xFF555555);
        t3.setTextSize(12);
        t3.setGravity(Gravity.CENTER);
        box.addView(t3);

        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        final long[] lastTap = {0};
        root.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTap[0] < 400) finish();
            lastTap[0] = now;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.postDelayed(shift, 60_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        try {
            unregisterReceiver(battery);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(shift);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
