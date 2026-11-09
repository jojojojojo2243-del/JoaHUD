package com.joassam.hud;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 공공데이터(전국무인교통단속카메라표준데이터)로 티맵과 상관없이
 * 단속카메라·제한속도·어린이보호구역 경고를 만든다.
 * - 자료는 폰에 저장 → 인터넷이 없어도 GPS만으로 동작
 * - 일주일마다 자동으로 새 자료 받기
 */
public final class Alerts {

    private Alerts() {
    }

    static final String API = "https://api.data.go.kr/openapi/tn_pubr_public_unmanned_traffic_camera_api";
    static final String FILE = "cameras.csv";
    static final long WEEK = 7L * 24 * 3600 * 1000;
    static final int F_SCHOOL = 1, F_SECTION = 2;

    // 불러온 카메라 목록
    private static float[] lat = new float[0], lon = new float[0];
    private static short[] limit = new short[0];
    private static byte[] flags = new byte[0];
    private static boolean loaded = false;
    static volatile int count = 0;
    static volatile String status = "";
    private static volatile boolean updating = false;

    /** 지금 앞에 있는 경고 (없으면 null) */
    static final class Hit {
        int limit;      // 제한속도 (0 = 모름)
        int dist;       // 카메라까지 m
        boolean school; // 어린이보호구역
        boolean section;// 구간단속
        long time;
    }

    static volatile Hit current;

    private static double lastLat, lastLon;
    private static boolean hasLast = false;
    private static float heading = -1;
    private static int lockIdx = -1;
    private static float lockDist = 0;

    // ───────── 자료 불러오기 ─────────

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) {
            status = "자료 없음";
            return;
        }
        int n = 0;
        float[] la = new float[60000], lo = new float[60000];
        short[] li = new short[60000];
        byte[] fl = new byte[60000];
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && n < la.length) {
                String[] p = line.split(",");
                if (p.length < 4) continue;
                try {
                    la[n] = Float.parseFloat(p[0]);
                    lo[n] = Float.parseFloat(p[1]);
                    li[n] = Short.parseShort(p[2]);
                    fl[n] = Byte.parseByte(p[3]);
                    n++;
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            status = "자료 읽기 오류: " + e.getMessage();
        }
        lat = java.util.Arrays.copyOf(la, n);
        lon = java.util.Arrays.copyOf(lo, n);
        limit = java.util.Arrays.copyOf(li, n);
        flags = java.util.Arrays.copyOf(fl, n);
        count = n;
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        status = "단속카메라 " + n + "곳 (" + p.getString("pub_date", "?") + " 받음)";
    }

    static String key(Context c) {
        return c.getSharedPreferences("hud", Context.MODE_PRIVATE).getString("pub_key", "").trim();
    }

    // ───────── 일주일마다 자동 업데이트 ─────────

    static void maybeUpdate(Context c) {
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        if (key(c).isEmpty()) return;
        long last = p.getLong("pub_updated", 0);
        if (System.currentTimeMillis() - last < WEEK && new File(c.getFilesDir(), FILE).exists()) return;
        if (System.currentTimeMillis() - lastAttempt < 3600_000L) return; // 실패하면 1시간 뒤 재시도
        update(c);
    }

    private static volatile long lastAttempt = 0;

    static void update(Context ctx) {
        if (updating) return;
        lastAttempt = System.currentTimeMillis();
        final Context c = ctx.getApplicationContext();
        final String k = key(c);
        if (k.isEmpty()) {
            status = "공공데이터 키 없음";
            return;
        }
        updating = true;
        status = "단속카메라 자료 받는 중…";
        new Thread(() -> {
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            int saved = 0;
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                String enc = k.contains("%") ? k : URLEncoder.encode(k, "UTF-8");
                int total = -1;
                boolean logged = false;
                for (int page = 1; page <= 120; page++) {
                    String url = API + "?serviceKey=" + enc + "&pageNo=" + page + "&numOfRows=1000&type=json";
                    String body = get(url);
                    if (body.trim().startsWith("<")) {
                        // 키 오류 등은 XML로 옴
                        throw new Exception("서버 응답 오류: " + body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                    }
                    JSONObject res = new JSONObject(body).getJSONObject("response");
                    JSONObject head = res.optJSONObject("header");
                    if (head != null && !"00".equals(head.optString("resultCode", "00"))) {
                        throw new Exception(head.optString("resultMsg", "오류") + " (" + head.optString("resultCode") + ")");
                    }
                    JSONObject b = res.getJSONObject("body");
                    if (total < 0) total = b.optInt("totalCount", 0);
                    JSONArray items = b.optJSONArray("items");
                    if (items == null) {
                        JSONObject io = b.optJSONObject("items");
                        if (io != null) items = io.optJSONArray("item");
                    }
                    if (items == null || items.length() == 0) break;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        if (!logged) {
                            logged = true;
                            String s = it.toString();
                            NavListenerService.log("공공데이터 항목 예: " + (s.length() > 400 ? s.substring(0, 400) : s));
                        }
                        String row = toRow(it);
                        if (row != null) {
                            w.write(row);
                            w.write('\n');
                            saved++;
                        }
                    }
                    status = "단속카메라 자료 받는 중… " + saved + (total > 0 ? "/" + total : "");
                    if (total > 0 && page * 1000 >= total) break;
                }
            } catch (Throwable t) {
                status = "자료 받기 실패: " + t.getMessage();
                NavListenerService.log(status);
                tmp.delete();
                updating = false;
                return;
            }
            if (saved < 100) {
                status = "자료가 너무 적음(" + saved + ") — 기존 자료 유지";
                NavListenerService.log(status);
                tmp.delete();
                updating = false;
                return;
            }
            File f = new File(c.getFilesDir(), FILE);
            f.delete();
            tmp.renameTo(f);
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(new java.util.Date());
            c.getSharedPreferences("hud", Context.MODE_PRIVATE).edit()
                    .putLong("pub_updated", System.currentTimeMillis())
                    .putString("pub_date", date).apply();
            synchronized (Alerts.class) {
                loaded = false;
            }
            load(c);
            NavListenerService.log("단속카메라 자료 업데이트 완료: " + count + "곳");
            updating = false;
        }).start();
    }

    /** 항목 하나 → "위도,경도,제한속도,표시" (이름이 조금 달라도 찾아냄) */
    private static String toRow(JSONObject it) {
        double la = 0, lo = 0;
        int lim = 0;
        int fl = 0;
        Iterator<String> keys = it.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            String v = it.optString(k, "").trim();
            String kl = k.toLowerCase(java.util.Locale.ROOT);
            if (kl.equals("latitude") || kl.equals("lat") || k.equals("위도")) la = num(v);
            else if (kl.equals("longitude") || kl.equals("lot") || kl.equals("lon") || k.equals("경도")) lo = num(v);
            else if (kl.startsWith("lmtt") || k.equals("제한속도")) lim = (int) num(v);
            if (v.contains("어린이")) fl |= F_SCHOOL;
            if (kl.startsWith("prtcarea") && (v.equals("1") || v.equals("01"))) fl |= F_SCHOOL;
            if (v.contains("구간")) fl |= F_SECTION;
        }
        if (la < 33 || la > 39 || lo < 124 || lo > 132) return null; // 한국 밖/오류 좌표
        if (lim < 0 || lim > 150) lim = 0;
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d,%d", la, lo, lim, fl);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        try (InputStream in = h.getResponseCode() >= 400 ? h.getErrorStream() : h.getInputStream()) {
            if (in == null) throw new Exception("응답 없음 (" + h.getResponseCode() + ")");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            h.disconnect();
        }
    }

    // ───────── 주행 중 판단 ─────────

    /** GPS 위치가 들어올 때마다: 진행 방향 앞쪽 가까운 카메라 찾기 */
    static void onLocation(Context c, Location loc) {
        if (!loaded) load(c);
        if (count == 0) {
            current = null;
            return;
        }
        double la = loc.getLatitude(), lo = loc.getLongitude();
        float spd = loc.hasSpeed() ? loc.getSpeed() : 0;
        if (loc.hasBearing() && spd > 2.5f) {
            heading = loc.getBearing();
        } else if (hasLast) {
            float[] r = new float[2];
            Location.distanceBetween(lastLat, lastLon, la, lo, r);
            if (r[0] > 8) heading = r[1] < 0 ? r[1] + 360 : r[1];
        }
        lastLat = la;
        lastLon = lo;
        hasLast = true;

        float[] res = new float[2];
        // 이미 잡은 카메라: 지나칠 때까지 유지
        if (lockIdx >= 0 && lockIdx < count) {
            Location.distanceBetween(la, lo, lat[lockIdx], lon[lockIdx], res);
            float d = res[0];
            float diff = angle(heading, res[1]);
            if (d < 40 || (d > lockDist + 30 && diff > 90) || d > 1000) {
                lockIdx = -1; // 지나침
            } else {
                lockDist = Math.min(lockDist, d);
                current = hit(lockIdx, d);
                return;
            }
        }
        if (heading < 0 || spd < 1.5f) {
            current = null;
            return;
        }
        double box = 0.009; // 약 1km
        int best = -1;
        float bestD = 801;
        for (int i = 0; i < count; i++) {
            if (Math.abs(lat[i] - la) > box || Math.abs(lon[i] - lo) > box * 1.25) continue;
            Location.distanceBetween(la, lo, lat[i], lon[i], res);
            if (res[0] > 800 || res[0] < 30) continue;
            // 진행 방향 앞쪽(±25°)에 있는 것만
            if (angle(heading, res[1]) > 25) continue;
            if (res[0] < bestD) {
                bestD = res[0];
                best = i;
            }
        }
        if (best >= 0) {
            lockIdx = best;
            lockDist = bestD;
            current = hit(best, bestD);
        } else {
            current = null;
        }
    }

    private static Hit hit(int i, float d) {
        Hit h = new Hit();
        h.limit = limit[i];
        h.dist = Math.round(d / 10f) * 10;
        h.school = (flags[i] & F_SCHOOL) != 0;
        h.section = (flags[i] & F_SECTION) != 0;
        h.time = System.currentTimeMillis();
        return h;
    }

    private static float angle(float a, float b) {
        if (a < 0) return 180;
        if (b < 0) b += 360;
        float d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** 화면용: 최근 3초 안의 경고만 */
    static Hit now() {
        Hit h = current;
        if (h == null || System.currentTimeMillis() - h.time > 3000) return null;
        return h;
    }
}
