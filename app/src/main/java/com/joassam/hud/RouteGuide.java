package com.joassam.hud;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 티맵 알림에는 회전 방향이 없으므로,
 * 알림에 나온 목적지로 '티맵 경로 API'에서 같은 경로를 받아
 * GPS로 다음 회전 지점을 따라가며 방향(좌/우회전 등)을 알아낸다.
 * 무료 키: openapi.sk.com → TMAP API 사용 신청 → appKey
 */
public class RouteGuide {

    private static final String API = "https://apis.openapi.sk.com/tmap";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    static class Turn {
        double lat, lon;
        int type;
        String desc;
        String dir;
    }

    private static final List<Turn> turns = new ArrayList<>();
    private static final List<double[]> line = new ArrayList<>();
    private static int idx = 0;
    private static float minDist = Float.MAX_VALUE;

    static volatile String dest = "";
    static volatile String dir = "NONE";
    static volatile float distToNext = -1;
    static volatile String desc = "";
    static volatile long updatedAt = 0;
    static volatile String status = "";
    static volatile Location lastLoc;

    private static volatile boolean busy = false;
    private static long lastRoute = 0;
    private static int offCount = 0;
    private static Context app;

    public static boolean fresh() {
        return !"NONE".equals(dir) && System.currentTimeMillis() - updatedAt < 6000;
    }

    static String key(Context c) {
        return c.getSharedPreferences("hud", Context.MODE_PRIVATE).getString("tmap_key", "").trim();
    }

    /** 티맵 알림에서 목적지를 받으면 호출 */
    public static void setDestination(Context c, String d) {
        if (d == null) return;
        int gt = d.lastIndexOf('>');
        if (gt >= 0) d = d.substring(gt + 1);
        d = d.trim();
        app = c.getApplicationContext();
        if (d.isEmpty() || d.equals(dest)) return;
        dest = d;
        clearRoute();
        status = "목적지 확인: " + d;
        maybeRoute(false);
    }

    public static void clear() {
        dest = "";
        clearRoute();
        status = "길안내 종료";
    }

    private static synchronized void clearRoute() {
        turns.clear();
        line.clear();
        idx = 0;
        minDist = Float.MAX_VALUE;
        dir = "NONE";
        distToNext = -1;
    }

    /** GPS 위치가 들어올 때마다 호출 (HUD 화면에서) */
    public static void onLocation(Location loc) {
        lastLoc = loc;
        if (dest.isEmpty()) return;
        boolean hasRoute;
        synchronized (RouteGuide.class) {
            hasRoute = !turns.isEmpty();
        }
        if (!hasRoute) {
            maybeRoute(false);
            return;
        }
        track(loc);
    }

    private static synchronized void track(Location loc) {
        // 경로 이탈 확인
        float off = nearestLine(loc);
        if (off > 120) {
            offCount++;
            if (offCount >= 4) {
                offCount = 0;
                status = "경로 이탈 → 다시 탐색";
                maybeRoute(true);
            }
        } else {
            offCount = 0;
        }

        while (idx < turns.size()) {
            Turn t = turns.get(idx);
            float d = dist(loc, t.lat, t.lon);
            if (d < minDist) minDist = d;
            boolean reached = d < 25;
            boolean passed = minDist < 70 && d > minDist + 35;
            if (reached || passed) {
                idx++;
                minDist = Float.MAX_VALUE;
                continue;
            }
            dir = t.dir;
            distToNext = d;
            desc = t.desc;
            updatedAt = System.currentTimeMillis();
            return;
        }
        dir = "NONE";
    }

    private static float nearestLine(Location loc) {
        if (line.isEmpty()) return 0;
        float best = Float.MAX_VALUE;
        int step = Math.max(1, line.size() / 400);
        for (int i = 0; i < line.size(); i += step) {
            double[] p = line.get(i);
            float d = dist(loc, p[0], p[1]);
            if (d < best) best = d;
        }
        return best;
    }

    private static float dist(Location a, double lat, double lon) {
        float[] r = new float[1];
        Location.distanceBetween(a.getLatitude(), a.getLongitude(), lat, lon, r);
        return r[0];
    }

    private static void maybeRoute(boolean force) {
        if (app == null || busy || dest.isEmpty()) return;
        if (TmapEdc.ok()) return; // 공식 연동이 되면 경로 API는 쓰지 않음(요금 절약)
        String k = key(app);
        if (k.isEmpty()) {
            status = "티맵 키가 없어 화살표 계산 불가";
            return;
        }
        Location loc = lastLoc;
        if (loc == null) {
            status = "GPS 기다리는 중";
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRoute < (force ? 30000 : 60000)) return;
        lastRoute = now;
        busy = true;
        final String target = dest;
        EXEC.execute(() -> {
            try {
                double[] end = geocode(k, target);
                if (end == null) {
                    status = "목적지 좌표를 못 찾음: " + target;
                    return;
                }
                fetchRoute(k, loc.getLatitude(), loc.getLongitude(), end[0], end[1]);
            } catch (Exception e) {
                status = "경로 받기 실패: " + e.getMessage();
                NavListenerService.log("티맵 경로 API 오류: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private static double[] geocode(String k, String q) throws Exception {
        // 1) 주소 → 좌표
        try {
            String u = API + "/geo/fullAddrGeo?version=1&format=json&coordType=WGS84GEO&fullAddr="
                    + URLEncoder.encode(q, "UTF-8");
            JSONObject o = new JSONObject(http("GET", u, k, null));
            JSONArray arr = o.getJSONObject("coordinateInfo").getJSONArray("coordinate");
            if (arr.length() > 0) {
                JSONObject c = arr.getJSONObject(0);
                String lat = c.optString("newLat", ""), lon = c.optString("newLon", "");
                if (lat.isEmpty()) {
                    lat = c.optString("lat", "");
                    lon = c.optString("lon", "");
                }
                if (!lat.isEmpty() && !lon.isEmpty()) return new double[]{Double.parseDouble(lat), Double.parseDouble(lon)};
            }
        } catch (Exception ignored) {
        }
        // 2) 장소 이름 → 좌표
        String u = API + "/pois?version=1&format=json&count=1&searchKeyword=" + URLEncoder.encode(q, "UTF-8");
        JSONObject o = new JSONObject(http("GET", u, k, null));
        JSONArray pois = o.getJSONObject("searchPoiInfo").getJSONObject("pois").getJSONArray("poi");
        if (pois.length() == 0) return null;
        JSONObject p = pois.getJSONObject(0);
        String lat = p.optString("frontLat", p.optString("noorLat", ""));
        String lon = p.optString("frontLon", p.optString("noorLon", ""));
        if (lat.isEmpty()) return null;
        return new double[]{Double.parseDouble(lat), Double.parseDouble(lon)};
    }

    private static void fetchRoute(String k, double sLat, double sLon, double eLat, double eLon) throws Exception {
        JSONObject body = new JSONObject();
        body.put("startX", String.valueOf(sLon));
        body.put("startY", String.valueOf(sLat));
        body.put("endX", String.valueOf(eLon));
        body.put("endY", String.valueOf(eLat));
        body.put("reqCoordType", "WGS84GEO");
        body.put("resCoordType", "WGS84GEO");
        body.put("searchOption", "0");
        JSONObject o = new JSONObject(http("POST", API + "/routes?version=1&format=json", k, body.toString()));
        JSONArray f = o.getJSONArray("features");
        List<Turn> ts = new ArrayList<>();
        List<double[]> ln = new ArrayList<>();
        for (int i = 0; i < f.length(); i++) {
            JSONObject feat = f.getJSONObject(i);
            JSONObject g = feat.getJSONObject("geometry");
            JSONObject pr = feat.optJSONObject("properties");
            String type = g.optString("type");
            if ("Point".equals(type) && pr != null) {
                int tt = pr.optInt("turnType", 0);
                String d = mapTurn(tt);
                if (d == null) continue;
                JSONArray c = g.getJSONArray("coordinates");
                Turn t = new Turn();
                t.lon = c.getDouble(0);
                t.lat = c.getDouble(1);
                t.type = tt;
                t.dir = d;
                t.desc = pr.optString("description", "");
                ts.add(t);
            } else if ("LineString".equals(type)) {
                JSONArray cs = g.getJSONArray("coordinates");
                for (int j = 0; j < cs.length(); j++) {
                    JSONArray c = cs.getJSONArray(j);
                    ln.add(new double[]{c.getDouble(1), c.getDouble(0)});
                }
            }
        }
        synchronized (RouteGuide.class) {
            turns.clear();
            turns.addAll(ts);
            line.clear();
            line.addAll(ln);
            idx = 0;
            minDist = Float.MAX_VALUE;
        }
        status = "티맵 경로 받음 (회전 " + ts.size() + "곳)";
        NavListenerService.log(status + " → " + dest);
        Location l = lastLoc;
        if (l != null) track(l);
    }

    /** 티맵 turnType → HUD 화살표. 방향과 상관없는 안내(터널·휴게소 등)는 null */
    static String mapTurn(int t) {
        if (t == 11 || t == 51) return "STRAIGHT";
        if (t == 12 || t == 16) return "LEFT";
        if (t == 17) return "KEEP_LEFT";
        if (t == 13 || t == 19) return "RIGHT";
        if (t == 18) return "KEEP_RIGHT";
        if (t == 14) return "UTURN";
        if (t == 43 || t == 101 || t == 104 || t == 111 || t == 114 || t == 117) return "KEEP_RIGHT";
        if (t == 44 || t == 102 || t == 105 || t == 112 || t == 115 || t == 118) return "KEEP_LEFT";
        if (t == 103 || t == 106 || t == 113 || t == 116) return "STRAIGHT";
        if (t >= 131 && t <= 142) return "ROUND";
        if (t == 201) return "ARRIVE";
        return null;
    }

    private static String http(String method, String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("appKey", key);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        String s = out.toString("UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + " " + (s.length() > 120 ? s.substring(0, 120) : s));
        return s;
    }
}
