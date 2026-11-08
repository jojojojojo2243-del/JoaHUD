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
    // 티맵 공식 연동(EDC)에서만 오는 값
    public String nextDir = "";   // 다음다음 회전
    public int nextDist = 0;
    public int limit = 0;         // 제한속도
    public int sdiType = 0;       // 단속/안전운행 종류
    public int sdiDist = 0;       // 단속까지 거리
    public int remainDist = 0;    // 목적지까지 m
    public int remainTime = 0;    // 목적지까지 초
    public int navSpeed = 0;
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
            o.put("nextDir", nextDir);
            o.put("nextDist", nextDist);
            o.put("limit", limit);
            o.put("sdiType", sdiType);
            o.put("sdiDist", sdiDist);
            o.put("remainDist", remainDist);
            o.put("remainTime", remainTime);
            o.put("navSpeed", navSpeed);
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
            d.nextDir = o.optString("nextDir", "");
            d.nextDist = o.optInt("nextDist", 0);
            d.limit = o.optInt("limit", 0);
            d.sdiType = o.optInt("sdiType", 0);
            d.sdiDist = o.optInt("sdiDist", 0);
            d.remainDist = o.optInt("remainDist", 0);
            d.remainTime = o.optInt("remainTime", 0);
            d.navSpeed = o.optInt("navSpeed", 0);
        } catch (Exception e) {
            return null;
        }
        return d;
    }
}
