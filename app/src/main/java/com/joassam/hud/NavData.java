package com.joassam.hud;

import org.json.JSONObject;

/** 메인폰 → HUD폰으로 보내는 길안내 한 건 */
public class NavData {
    public static final int PORT = 47820;

    public String dir = "NONE";   // LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, STRAIGHT, UTURN, ROUND, ARRIVE, NONE
    public String dist = "";      // "300m", "1.2km"
    public String text = "";      // 안내 문구 1줄
    public String sub = "";       // 보조 문구 (도로명 등)
    public String pkg = "";
    public String icon = "";      // 내비 앱 아이콘(화살표) PNG base64, 없으면 ""
    public String raw = "";       // 디버그용 원문
    public boolean active = true; // false = 길안내 종료
    public long time = System.currentTimeMillis();

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("dir", dir);
            o.put("dist", dist);
            o.put("text", text);
            o.put("sub", sub);
            o.put("pkg", pkg);
            o.put("icon", icon);
            o.put("active", active);
            o.put("time", time);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static NavData fromJson(String s) {
        NavData d = new NavData();
        try {
            JSONObject o = new JSONObject(s);
            d.dir = o.optString("dir", "NONE");
            d.dist = o.optString("dist", "");
            d.text = o.optString("text", "");
            d.sub = o.optString("sub", "");
            d.pkg = o.optString("pkg", "");
            d.icon = o.optString("icon", "");
            d.active = o.optBoolean("active", true);
            d.time = o.optLong("time", System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        }
        return d;
    }
}
