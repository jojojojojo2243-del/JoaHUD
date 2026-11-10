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
            int dx = ((step % 5) - 2) * dp(4);
            int dy = (((step / 5) % 5) - 2) * dp(4);
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
        box.setPadding(dp(16), dp(20), dp(16), dp(20));

        TextView t1 = new TextView(this);
        t1.setText(msg);
        t1.setTextColor(0xFFDDDDDD);
        t1.setTextSize(22);
        t1.setGravity(Gravity.CENTER);
        box.addView(t1, new LinearLayout.LayoutParams(-1, -2));

        // 번호: 가로 화면은 한 줄(010-3527-9288), 세로 화면은 세 줄(010 / 3527 / 9288)
        // 화면 폭·높이에 맞춰 잘리지 않는 가장 큰 글씨로 직접 그림
        box.addView(new PhoneView(this, splitPhone(phone)), new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView t3 = new TextView(this);
        t3.setText("(화면을 두 번 누르면 닫힘)");
        t3.setTextColor(0xFF555555);
        t3.setTextSize(12);
        t3.setGravity(Gravity.CENTER);
        box.addView(t3, new LinearLayout.LayoutParams(-1, -2));

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

    /** 전화번호를 화면에 꽉 차게, 절대 잘리지 않게 그리는 뷰 */
    static final class PhoneView extends View {
        private final java.util.List<String> parts;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PhoneView(Context c, java.util.List<String> parts) {
            super(c);
            this.parts = parts;
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (w > h * 1.2f) lines.add(String.join("-", parts)); // 가로: 한 줄
            else lines.addAll(parts);                              // 세로: 줄 바꿈
            // 기준 크기 100으로 재서 폭·높이 모두 맞는 크기 계산
            paint.setTextSize(100f);
            float maxW = 1;
            for (String l : lines) maxW = Math.max(maxW, paint.measureText(l));
            float byW = 100f * (w * 0.94f) / maxW;
            float byH = h / (lines.size() * 1.12f) / 0.75f; // 숫자 높이 ≈ 글자크기의 0.75
            float size = Math.min(byW, byH);
            paint.setTextSize(size);
            float lineH = size * 0.75f * 1.12f;
            float top = (h - lineH * lines.size()) / 2f;
            for (int i = 0; i < lines.size(); i++) {
                float base = top + lineH * i + size * 0.75f + (lineH - size * 0.75f) / 2f;
                c.drawText(lines.get(i), w / 2f, base, paint);
            }
        }
    }

    /** 010-3527-9288 / 01035279288 / 010 3527 9288 → [010, 3527, 9288] */
    static java.util.List<String> splitPhone(String phone) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String p = phone == null ? "" : phone.trim();
        for (String s : p.split("[-\\s.]+")) if (!s.isEmpty()) out.add(s);
        if (out.size() == 1) {
            String d = out.get(0);
            out.clear();
            if (d.matches("\\d{11}")) {
                out.add(d.substring(0, 3)); out.add(d.substring(3, 7)); out.add(d.substring(7));
            } else if (d.matches("\\d{10}")) {
                out.add(d.substring(0, 3)); out.add(d.substring(3, 6)); out.add(d.substring(6));
            } else {
                out.add(d);
            }
        }
        if (out.isEmpty()) out.add("번호를 입력하세요");
        return out;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
