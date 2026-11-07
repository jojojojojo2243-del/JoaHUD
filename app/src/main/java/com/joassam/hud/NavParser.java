package com.joassam.hud;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 내비 앱 알림에서 방향/거리/문구를 뽑아낸다 */
public class NavParser {

    private static final Pattern DIST = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|킬로미터|m|미터)(?![a-zA-Z])",
            Pattern.CASE_INSENSITIVE);

    // 키워드 → 방향 (문장에서 가장 먼저 나오는 키워드를 채택)
    private static final String[][] KEYWORDS = {
            {"유턴", "UTURN"}, {"u턴", "UTURN"}, {"u-turn", "UTURN"}, {"make a u", "UTURN"},
            {"회전교차로", "ROUND"}, {"roundabout", "ROUND"},
            {"좌회전", "LEFT"}, {"turn left", "LEFT"}, {"왼쪽으로 회전", "LEFT"},
            {"우회전", "RIGHT"}, {"turn right", "RIGHT"}, {"오른쪽으로 회전", "RIGHT"},
            {"slight left", "KEEP_LEFT"}, {"keep left", "KEEP_LEFT"}, {"왼쪽 방향", "KEEP_LEFT"},
            {"좌측", "KEEP_LEFT"}, {"왼쪽", "KEEP_LEFT"},
            {"slight right", "KEEP_RIGHT"}, {"keep right", "KEEP_RIGHT"}, {"오른쪽 방향", "KEEP_RIGHT"},
            {"우측", "KEEP_RIGHT"}, {"오른쪽", "KEEP_RIGHT"},
            {"직진", "STRAIGHT"}, {"continue", "STRAIGHT"}, {"straight", "STRAIGHT"},
    };

    public static NavData parse(Context ctx, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        String title = str(e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null);
        String text = str(e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null);
        String big = str(e != null ? e.getCharSequence(Notification.EXTRA_BIG_TEXT) : null);
        String sub = str(e != null ? e.getCharSequence(Notification.EXTRA_SUB_TEXT) : null);
        String info = str(e != null ? e.getCharSequence(Notification.EXTRA_INFO_TEXT) : null);

        List<String> parts = new ArrayList<>();
        addIf(parts, title);
        addIf(parts, text);
        if (!big.equals(text)) addIf(parts, big);
        addIf(parts, sub);
        addIf(parts, info);

        // 안드로이드 오토용 길안내 알림(CarAppExtender) 내용도 읽기
        if (e != null) {
            for (String key : new String[]{"android.car.app.EXTENSIONS", "androidx.car.app.EXTENSIONS", "android.car.EXTENSIONS"}) {
                try {
                    Bundle cb = e.getBundle(key);
                    if (cb == null) continue;
                    for (String k : cb.keySet()) {
                        Object v = cb.get(k);
                        if (v instanceof CharSequence) addIf(parts, v.toString().trim());
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 커스텀 알림(티맵 등)은 extras가 비어 있을 수 있어 RemoteViews 안의 글자를 긁는다
        if (parts.isEmpty()) {
            parts.addAll(fromRemoteViews(ctx, n.bigContentView));
            if (parts.isEmpty()) parts.addAll(fromRemoteViews(ctx, n.contentView));
        }

        NavData d = new NavData();
        d.pkg = sbn.getPackageName();
        d.raw = join(parts);
        if (parts.isEmpty()) return d;

        d.dir = detectDir(d.raw);
        d.dist = detectDist(parts);
        d.text = parts.get(0);
        if (parts.size() > 1) d.sub = parts.get(1);
        d.icon = iconBase64(ctx, n);
        if (d.icon.isEmpty() && e != null) d.icon = carIconBase64(e);
        return d;
    }

    static String detectDir(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        int best = Integer.MAX_VALUE;
        String dir = "NONE";
        for (String[] kw : KEYWORDS) {
            int i = l.indexOf(kw[0]);
            if (i >= 0 && i < best) {
                best = i;
                dir = kw[1];
            }
        }
        if (dir.equals("NONE") && (l.contains("도착") || l.contains("destination") || l.contains("arrive"))) {
            dir = "ARRIVE";
        }
        return dir;
    }

    static String detectDist(List<String> parts) {
        for (String p : parts) {
            Matcher m = DIST.matcher(p);
            if (m.find()) {
                String num = m.group(1).replace(',', '.');
                String unit = m.group(2).toLowerCase(Locale.ROOT);
                if (unit.startsWith("k") || unit.equals("킬로미터")) unit = "km";
                else unit = "m";
                return num + unit;
            }
        }
        return "";
    }

    private static String iconBase64(Context ctx, Notification n) {
        try {
            Drawable dr = null;
            if (Build.VERSION.SDK_INT >= 23) {
                Icon ic = n.getLargeIcon();
                if (ic != null) dr = ic.loadDrawable(ctx);
            }
            Bitmap bmp = null;
            if (dr instanceof BitmapDrawable) {
                bmp = ((BitmapDrawable) dr).getBitmap();
            } else if (dr != null) {
                bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                dr.setBounds(0, 0, 128, 128);
                dr.draw(c);
            } else if (n.largeIcon != null) {
                bmp = n.largeIcon;
            }
            if (bmp == null) return "";
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, 128, 128, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String carIconBase64(Bundle e) {
        for (String key : new String[]{"android.car.app.EXTENSIONS", "androidx.car.app.EXTENSIONS"}) {
            try {
                Bundle cb = e.getBundle(key);
                if (cb == null) continue;
                Object v = cb.get("large_icon");
                if (v instanceof Bitmap) {
                    Bitmap scaled = Bitmap.createScaledBitmap((Bitmap) v, 128, 128, true);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
                    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static List<String> fromRemoteViews(Context ctx, RemoteViews rv) {
        List<String> out = new ArrayList<>();
        if (rv == null) return out;
        try {
            View v = rv.apply(ctx, new FrameLayout(ctx));
            collect(v, out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void collect(View v, List<String> out) {
        if (v instanceof TextView) {
            addIf(out, str(((TextView) v).getText()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static void addIf(List<String> list, String s) {
        if (s != null && !s.isEmpty() && !list.contains(s)) list.add(s);
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append(" | ");
            b.append(s);
        }
        return b.toString();
    }
}
