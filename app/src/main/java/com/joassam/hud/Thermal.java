package com.joassam.hud;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * 폰 과열 대책: 배터리 온도로 단계를 정함.
 * 0 = 보통(40℃ 미만), 1 = 따뜻함(40℃~), 2 = 뜨거움(44℃~).
 * 단계가 오르면 화면 밝기를 낮추고 티맵 화면 찍는 간격을 늘려 열을 줄임.
 */
final class Thermal {
    private static long checkedAt = 0;
    private static int level = 0;

    private Thermal() {
    }

    static int level(Context c) {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 20_000L) return level;
        checkedAt = now;
        float t = 0;
        try {
            Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        } catch (Exception ignored) {
        }
        int nl;
        // 오르내릴 때 들쭉날쭉하지 않게 1℃ 여유
        if (level == 2) nl = t >= 42 ? 2 : t >= 39 ? 1 : 0;
        else if (level == 1) nl = t >= 44 ? 2 : t >= 39 ? 1 : 0;
        else nl = t >= 44 ? 2 : t >= 40 ? 1 : 0;
        if (nl != level) {
            NavListenerService.log("폰 온도 " + Math.round(t) + "℃ → "
                    + (nl == 2 ? "뜨거움: 밝기·화면 찍기 크게 줄임" : nl == 1 ? "따뜻함: 밝기·화면 찍기 조금 줄임" : "보통으로 돌아옴"));
            level = nl;
        }
        return level;
    }

    /** 티맵 화면 찍는 간격(ms) */
    static long captureInterval(Context c) {
        int l = level(c);
        return l >= 2 ? 2000 : l == 1 ? 1200 : 700;
    }
}
