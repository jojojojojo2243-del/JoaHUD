package com.joassam.hud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * '티맵 안내판 읽기'(접근성) 가 꺼지면 스스로 다시 켠다.
 * PC에서 한 번만 아래 명령으로 권한을 주면, 업데이트·재부팅 후에도 계속 유지됨:
 *   adb shell pm grant com.joassam.hud android.permission.WRITE_SECURE_SETTINGS
 */
public class A11yGuard {

    static final String COMPONENT = "com.joassam.hud/com.joassam.hud.TmapA11yService";

    public static boolean canAutoEnable(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains("com.joassam.hud/");
    }

    /** 꺼져 있으면 다시 켜기. 켰으면 true */
    public static boolean ensure(Context c) {
        try {
            if (isEnabled(c) || !canAutoEnable(c)) return false;
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String v = (s == null || s.isEmpty()) ? COMPONENT : s + ":" + COMPONENT;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, v);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            NavListenerService.log("안내판 읽기가 꺼져 있어서 자동으로 다시 켰습니다");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
