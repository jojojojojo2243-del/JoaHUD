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
    static final int F_SCHOOL = 1, F_SECTION = 2, F_SEC_START = 4, F_SEC_END = 8;
    /** 도로 종류: F_KND = 공공데이터에 도로종류가 적혀 있음, F_HWY = 고속도로·자동차전용도로 */
    static final int F_HWY = 16, F_KND = 32;
    /** 자료 형식 버전: 구간단속 시점/종점을 담기 시작한 2부터. 낮으면 새로 받음 */
    static final int FORMAT = 3; // 3: 도로 종류(고속도로 여부) 추가 → 새로 받기

    // 불러온 카메라 목록
    private static float[] lat = new float[0], lon = new float[0];
    private static short[] limit = new short[0];
    private static byte[] flags = new byte[0];
    private static int[] secLen = new int[0]; // 과속단속구간길이(m), 0 = 모름
    static volatile int sectionCount = 0;
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
        boolean secStart, secEnd; // 구간단속 시점 / 종점
        long time;
    }

    static volatile Hit current;

    private static double lastLat, lastLon;
    private static boolean hasLast = false;
    private static float heading = -1;
    private static int lockIdx = -1;
    private static float lockDist = 0;

    // ───────── 구간단속 평균속도 ─────────
    static volatile boolean inSection = false;
    static volatile int sectionLimit = 0;
    private static long secStart = 0;
    private static double secDist = 0;      // m
    private static int secStartIdx = -1;
    private static int secLength = 0;       // 이 구간 길이(m), 0 = 모름

    private static int secEndIdx = -1;      // 짝이 되는 종점 카메라 (못 찾으면 -1)
    private static float secEndMin = 1e9f;  // 종점 카메라까지 가장 가까웠던 거리

    /** 구간단속 종점까지 남은 거리(m), 모르면 -1 */
    static int sectionRemain() {
        if (!inSection) return -1;
        if (secLength > 0) return (int) Math.max(0, secLength - secDist);
        if (secEndIdx >= 0 && hasLast) {
            float[] r = new float[1];
            Location.distanceBetween(lastLat, lastLon, lat[secEndIdx], lon[secEndIdx], r);
            return Math.round(r[0]); // 직선거리라 실제보다 조금 짧을 수 있음
        }
        return -1;
    }

    /** 시점 카메라에서 진행 방향으로 짝이 되는 종점 카메라 찾기 */
    private static int findEnd(int start) {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        float[] r = new float[2];
        double box = 0.25; // 약 25km
        for (int i = 0; i < count; i++) {
            if ((flags[i] & F_SEC_END) == 0 || i == start) continue;
            if (Math.abs(lat[i] - lat[start]) > box || Math.abs(lon[i] - lon[start]) > box * 1.25) continue;
            Location.distanceBetween(lat[start], lon[start], lat[i], lon[i], r);
            if (r[0] < 300 || r[0] > 25000) continue;
            if (heading >= 0 && angle(heading, r[1]) > 70) continue; // 뒤쪽·옆쪽 종점은 아님
            if (limit[start] > 0 && limit[i] > 0 && limit[start] != limit[i]) continue;
            float score = secLength > 0 ? Math.abs(r[0] - secLength * 0.9f) : r[0];
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 구간단속 중 평균속도(km/h), 구간 밖이면 -1 */
    static int sectionAvg() {
        if (!inSection) return -1;
        double sec = (System.currentTimeMillis() - secStart) / 1000.0;
        if (sec < 5) return -1;
        return (int) Math.round(secDist / sec * 3.6);
    }

    /** 카메라를 지나쳤을 때: 구간단속 시작/끝 판단 */
    private static void passed(int idx) {
        int f = flags[idx];
        if ((f & F_SECTION) == 0) return;
        boolean isEnd = (f & F_SEC_END) != 0;
        boolean isStart = (f & F_SEC_START) != 0;
        if (!inSection) {
            if (isEnd && !isStart) return; // 종점 카메라만 지남 (반대 방향이거나 시작을 놓침)
            inSection = true;
            secStart = System.currentTimeMillis();
            secDist = 0;
            secStartIdx = idx;
            secLength = secLen[idx];
            sectionLimit = limit[idx];
            secEndIdx = findEnd(idx);
            secEndMin = 1e9f;
            NavListenerService.log("구간단속 시작 (제한 " + sectionLimit
                    + (secLength > 0 ? ", 길이 " + String.format(java.util.Locale.ROOT, "%.1f", secLength / 1000f) + "km" : "") + ")");
        } else if (idx != secStartIdx && secDist > 300 && (isEnd || !isStart)) {
            endSection("종점 통과");
        } else if (idx != secStartIdx && isStart && secDist > 300) {
            // 앞 구간이 끝나고 바로 다음 구간 시작
            endSection("다음 구간 시작");
            passed(idx);
        }
    }

    /** 음성 "조아야 구간 끝": 구간단속 표시 바로 끄기 */
    static boolean cancelSection() {
        if (!inSection) return false;
        endSection("음성으로 끔");
        return true;
    }

    private static void endSection(String why) {
        if (!inSection) return;
        NavListenerService.log("구간단속 끝(" + why + ") — 평균 " + sectionAvg() + "km/h");
        inSection = false;
    }

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
        int[] sl = new int[60000];
        int secN = 0;
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
                    sl[n] = p.length > 4 ? Integer.parseInt(p[4]) : 0;
                    if ((fl[n] & F_SECTION) != 0) secN++;
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
        secLen = java.util.Arrays.copyOf(sl, n);
        count = n;
        sectionCount = secN;
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        status = "단속카메라 " + n + "곳 · 구간단속 " + secN + "곳 (" + p.getString("pub_date", "?") + " 받음)";
    }

    static String key(Context c) {
        return c.getSharedPreferences("hud", Context.MODE_PRIVATE).getString("pub_key", "").trim();
    }

    // ───────── 일주일마다 자동 업데이트 ─────────

    static void maybeUpdate(Context c) {
        SharedPreferences p = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        if (key(c).isEmpty()) return;
        long last = p.getLong("pub_updated", 0);
        boolean oldFormat = p.getInt("pub_format", 1) < FORMAT; // 예전 자료엔 구간단속 표시가 없음 → 바로 새로 받기
        if (!oldFormat && System.currentTimeMillis() - last < WEEK && new File(c.getFilesDir(), FILE).exists()) return;
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
                    String t = body.trim();
                    String snip = t.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
                    if (snip.length() > 160) snip = snip.substring(0, 160);
                    if (!t.startsWith("{")) {
                        // 키 오류·서비스 오류는 XML이나 글자로 옴
                        throw new Exception(keyHint(snip));
                    }
                    JSONObject root = new JSONObject(t);
                    JSONObject res = root.optJSONObject("response");
                    if (res == null) res = root;
                    JSONObject head = res.optJSONObject("header");
                    String code = head != null ? head.optString("resultCode", "00") : res.optString("resultCode", "00");
                    if (!"00".equals(code) && !"0".equals(code) && !code.isEmpty()) {
                        String msg = head != null ? head.optString("resultMsg", "") : res.optString("resultMsg", "");
                        throw new Exception(keyHint(msg + " (" + code + ")"));
                    }
                    // odcloud 형식 {"code":-4,"msg":"등록되지 않은 인증키"} 오류
                    if (root.has("code") && root.has("msg") && !root.has("data")) {
                        throw new Exception(keyHint(root.optString("msg") + " (" + root.optString("code") + ")"));
                    }
                    JSONObject b = res.optJSONObject("body");
                    JSONArray items;
                    if (b != null) {
                        if (total < 0) total = b.optInt("totalCount", 0);
                        items = b.optJSONArray("items");
                    } else if (root.optJSONArray("data") != null) {
                        // odcloud 형식 {"totalCount":..,"data":[...]}
                        if (total < 0) total = root.optInt("totalCount", 0);
                        items = root.optJSONArray("data");
                        b = root;
                    } else {
                        throw new Exception(keyHint("예상 못한 응답: " + snip));
                    }
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
                    .putInt("pub_format", FORMAT)
                    .putString("pub_date", date).apply();
            synchronized (Alerts.class) {
                loaded = false;
            }
            load(c);
            NavListenerService.log("단속카메라 자료 업데이트 완료: " + count + "곳 (구간단속 " + sectionCount + "곳)");
            updating = false;
        }).start();
    }

    /** 서버 오류 글자를 알아듣기 쉽게 */
    private static String keyHint(String m) {
        String u = m.toUpperCase(java.util.Locale.ROOT);
        if (u.contains("NOT_REGISTERED") || m.contains("등록되지 않은") || u.contains("SERVICE KEY") || u.contains("SERVICE_KEY") || u.contains("UNAUTHORIZED") || u.contains("30)"))
            return "인증키가 아직 등록 전이거나 틀림 — 신청 후 1~2시간 뒤 다시 (" + m + ")";
        if (u.contains("LIMITED") || u.contains("22)")) return "오늘 사용량 초과 — 내일 자동 재시도 (" + m + ")";
        if (u.contains("NO_OPENAPI") || u.contains("API NOT FOUND") || u.contains("12)")) return "이 데이터에 활용신청이 안 된 키 (" + m + ")";
        return m;
    }

    /** 항목 하나 → "위도,경도,제한속도,표시,구간길이" (이름이 조금 달라도 찾아냄) */
    static String toRow(JSONObject it) {
        double la = 0, lo = 0;
        int lim = 0;
        int fl = 0;
        double len = 0;
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
            // 구간단속: '단속구간위치구분'이 1 = 시점, 2 = 종점 (숫자로 옴). 글자로 오는 경우도 대비
            if (kl.startsWith("regltsctn") || kl.contains("sctnlc") || kl.equals("intelocdiv") || k.equals("단속구간위치구분")) {
                String d = v.replaceAll("[^0-9가-힣]", "");
                if (d.equals("1") || d.equals("01") || d.contains("시점") || d.contains("시작")) fl |= F_SECTION | F_SEC_START;
                else if (d.equals("2") || d.equals("02") || d.contains("종점") || d.contains("끝")) fl |= F_SECTION | F_SEC_END;
            } else if (kl.startsWith("ovrspdregltsctn") || kl.equals("spdstoplen") || k.equals("과속단속구간길이")) {
                len = num(v);
            } else if (kl.startsWith("roadknd") || kl.equals("roadkind") || k.equals("도로종류")) {
                if (!v.isEmpty() && !v.matches("[0-9]+")) {
                    fl |= F_KND;
                    if (v.contains("고속") || v.contains("자동차전용") || v.contains("전용도로")) fl |= F_HWY;
                }
            } else if (kl.equals("itlpc") || k.equals("설치장소")) {
                if (v.contains("구간")) {
                    fl |= F_SECTION;
                    if (v.contains("시점") || v.contains("시작")) fl |= F_SEC_START;
                    if (v.contains("종점") || v.contains("끝")) fl |= F_SEC_END;
                }
            } else if (v.contains("구간단속")) {
                fl |= F_SECTION;
            }
        }
        // 구간길이: km로 적은 곳(예 3.5)과 m로 적은 곳(예 3500)이 섞여 있음
        int lenM = len <= 0 ? 0 : len < 100 ? (int) Math.round(len * 1000) : (int) Math.round(len);
        if (lenM > 50_000) lenM = 0;
        if (lenM > 0 && (fl & F_SECTION) == 0 && (fl & (F_SEC_START | F_SEC_END)) == 0) {
            // 길이만 있고 시점/종점 표시가 없으면 구간단속으로 봄 (시점으로 가정)
            fl |= F_SECTION | F_SEC_START;
        }
        if (la < 33 || la > 39 || lo < 124 || lo > 132) return null; // 한국 밖/오류 좌표
        if (lim < 0 || lim > 150) lim = 0;
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d,%d,%d", la, lo, lim, fl, lenM);
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
        if (hasLast && inSection) {
            float[] dd = new float[1];
            Location.distanceBetween(lastLat, lastLon, la, lo, dd);
            if (dd[0] < 200) secDist += dd[0]; // GPS 튐 무시
            // 짝 종점 카메라를 지나면 끝 (가장 가까웠다가 다시 멀어짐)
            if (secEndIdx >= 0) {
                float[] de = new float[1];
                Location.distanceBetween(la, lo, lat[secEndIdx], lon[secEndIdx], de);
                if (de[0] < secEndMin) secEndMin = de[0];
                // 종점 카메라에 가까이(400m 안) 갔다가 다시 멀어지면 끝 — 공공데이터 좌표가 조금 어긋나도 잡힘
                else if (secEndMin < 400 && de[0] > secEndMin + 120) endSection("종점 지남");
                // 종점 쪽으로 가다가 계속 멀어지기만 하면 구간을 벗어난 것 (다른 길로 빠짐)
                else if (de[0] > secEndMin + 800) endSection("구간 벗어남");
            }
            // 종점 카메라를 못 잡은 경우 안전장치: 구간 길이 + 1km, 길이를 모르면 30km 또는 40분
            if (secLength > 0 && secDist > secLength + 300) endSection("구간 길이 지남");
            // 길이도 종점도 모르면: 보통 구간단속은 10km 안쪽 → 12km 또는 15분 지나면 끔
            else if (secLength <= 0 && secEndIdx < 0 && (secDist > 12000 || System.currentTimeMillis() - secStart > 15 * 60_000L))
                endSection("길이 모름, 12km 지남");
            else if (secDist > 30000 || System.currentTimeMillis() - secStart > 40 * 60_000L) endSection("너무 오래 지남");
        }
        lastLat = la;
        lastLon = lo;
        hasLast = true;

        float[] res = new float[2];
        float kmh = spd * 3.6f;
        boolean fast = onFastRoad(kmh);
        // 이미 잡은 카메라: 지나칠 때까지 유지
        if (lockIdx >= 0 && lockIdx < count) {
            Location.distanceBetween(la, lo, lat[lockIdx], lon[lockIdx], res);
            float d = res[0];
            float diff = angle(heading, res[1]);
            // 구간단속 시점: 카메라 바로 앞(60m)에 오면 지나칠 때까지 기다리지 않고 바로 시작
            if (!inSection && d < 60 && (flags[lockIdx] & F_SEC_START) != 0) passed(lockIdx);
            // 옆 도로 카메라(학교 옆길 등): 가까워질수록 옆으로 벌어지면 경고 취소. 고가 밑 도로 카메라도 취소
            if ((d > 60 && diff < 90 && side(d, diff) > sideLimit(d)) || otherRoad(lockIdx, kmh, fast)) {
                lockIdx = -1;
                current = null;
                return;
            }
            if (d < 40 || (d > lockDist + 30 && diff > 90) || d > 1000) {
                if (lockDist < 150 && d <= 1000) passed(lockIdx); // 실제로 카메라 옆을 지나감
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
            // 진행 방향 앞쪽(±25°)이면서, 내 길에서 옆으로 많이 벗어나지 않은 것만 (옆 도로 카메라 제외)
            float df = angle(heading, res[1]);
            if (df > 25) continue;
            if (side(res[0], df) > sideLimit(res[0])) continue;
            if (otherRoad(i, kmh, fast)) continue; // 고가 위·아래 다른 길
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

    /** 빠르게(시속 85 이상) 달린 지 20초 넘으면 고속도로·고가도로 위로 봄 */
    private static long fastSince = 0, fastUntil = 0;

    private static boolean onFastRoad(float kmh) {
        long now = System.currentTimeMillis();
        if (kmh >= 85) {
            if (fastSince == 0) fastSince = now;
            if (now - fastSince > 20_000L) fastUntil = now + 90_000L; // 잠깐 막혀도 1분 30초는 유지
        } else if (kmh < 60) fastSince = 0;
        return now < fastUntil;
    }

    /**
     * 고가도로 위를 달리는데 밑 일반도로 카메라가 잡히는 것 거르기 (GPS는 높이를 모름).
     * - 시속 85 이상으로 20초 넘게 달리는 중일 때만, 제한 60 이하 카메라에 대해:
     *   도로 종류가 '고속도로'가 아니면 무시, 도로 종류를 모르면 내 속도가 제한보다 45 넘게 빠를 때 무시
     */
    private static boolean otherRoad(int i, float kmh, boolean fast) {
        if ((flags[i] & F_SECTION) != 0 && inSection) return false;
        int lim = limit[i];
        if (lim <= 0) return false;
        if (!fast || lim > 60) return false; // 천천히 달릴 땐 거르지 않음 (진짜 단속을 놓치면 안 됨)
        if ((flags[i] & F_KND) != 0) return (flags[i] & F_HWY) == 0;
        return kmh >= lim + 45;
    }

    /** 진행 방향에서 옆으로 벌어진 거리(m) */
    private static float side(float d, float diffDeg) {
        return (float) (d * Math.sin(Math.toRadians(Math.min(diffDeg, 90))));
    }

    /** 옆으로 이만큼까지는 같은 길로 봄: 가까이선 35m, 멀리선 길이 휘어 있을 수 있어 조금 더 */
    private static float sideLimit(float d) {
        return Math.max(35f, d * 0.10f);
    }

    private static Hit hit(int i, float d) {
        Hit h = new Hit();
        h.limit = limit[i];
        h.dist = Math.round(d / 10f) * 10;
        h.school = (flags[i] & F_SCHOOL) != 0;
        h.section = (flags[i] & F_SECTION) != 0;
        h.secStart = (flags[i] & F_SEC_START) != 0;
        h.secEnd = (flags[i] & F_SEC_END) != 0;
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
