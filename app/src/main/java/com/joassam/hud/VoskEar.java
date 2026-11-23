package com.joassam.hud;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 끊기지 않고 계속 듣는 귀 (Vosk 한국어 모델, 폰 안에서만 처리 · 무료 · 띵 소리 없음).
 *
 * 예전 방식(안드로이드 인식기)은 몇 초 듣고 끄고 다시 켜기를 반복해서 그 틈에 부르면 못 듣고,
 * 티맵이 앞에 나오면 인식기가 마이크를 못 잡아(오류 5) 멈췄음.
 * 이 귀는 앱이 직접 마이크를 계속 열어 두고(티맵이 앞에 있어도 소리는 들어오는 것을 확인함),
 * 폰 안의 Vosk가 바로 글자로 바꿈. 말소리가 날 때만 Vosk를 돌려서 열·배터리를 아낌.
 *
 * 모델(약 82MB)은 처음 한 번만 내려받음. 받기 전·실패 시엔 예전 방식으로 들음.
 */
final class VoskEar {

    interface Listener {
        /**
         * 말 한 마디가 끝남.
         * @param picked 정해진 말 목록(부르는 말·명령)에서 고른 것 (띄어쓰기 없음, 없으면 "")
         * @param conf   고른 말의 확신도 0~1 (낮으면 비슷한 말을 억지로 고른 것)
         * @param free   받아쓰기 글자 (대화·목적지용, 정확도 낮음)
         */
        void onHeard(java.util.List<String> picks, java.util.List<Float> confs, String free);

        /** 말하는 중간 글자 (부르는 말을 빨리 잡거나 앱 말을 끊을 때) */
        void onPartial(String text);
    }

    static final String MODEL_NAME = "vosk-model-small-ko-0.22";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    private static final int RATE = 16000;

    private static volatile boolean downloading = false;
    private static volatile Object model; // org.vosk.Model (클래스가 없을 때도 앱이 죽지 않게 Object로)
    private static volatile boolean broken = false;

    private static VoskEar running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile boolean on = false;
    private volatile boolean paused = false;
    private Thread thread;
    private long statAt = System.currentTimeMillis();
    private int statMax = 0, statOpens = 0, statHeard = 0;

    /** 시동 켜질 때 미리 모델을 불러 둠 → HUD가 뜨자마자 바로 들을 수 있게 */
    static void preload(Context c) {
        if (model != null || broken || !modelReady(c)) return;
        if (!c.getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("vosk_on", true)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> loadModel(app), "joa-model-load").start();
    }

    // ───────── 모델 ─────────

    static File modelDir(Context c) {
        return new File(c.getFilesDir(), MODEL_NAME);
    }

    static boolean modelReady(Context c) {
        File d = modelDir(c);
        return new File(d, "am/final.mdl").exists() && new File(d, ".ok").exists();
    }

    /** 쓸 수 있나 (설정 켜짐 + 모델 있음 + 고장 안 남) */
    static boolean usable(Context c) {
        if (broken) return false;
        if (!c.getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("vosk_on", true)) return false;
        if (!modelReady(c)) {
            download(c);
            return false;
        }
        return true;
    }

    /** 모델 내려받기 (한 번만, 뒤에서) */
    static void download(Context c) {
        if (downloading || modelReady(c)) return;
        if (!c.getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("vosk_on", true)) return;
        downloading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File zip = new File(app.getCacheDir(), MODEL_NAME + ".zip");
            try {
                NavListenerService.log("계속 듣는 음성 모델 내려받는 중 (약 82MB, 처음 한 번만)");
                HttpURLConnection con = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                int code = con.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = con.getContentLength();
                try (InputStream in = new BufferedInputStream(con.getInputStream());
                     OutputStream out = new FileOutputStream(zip)) {
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int lastPct = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total) / 25 * 25;
                            if (pct != lastPct && pct > 0 && pct < 100) {
                                lastPct = pct;
                                NavListenerService.log("음성 모델 받는 중 " + pct + "%");
                            }
                        }
                    }
                }
                File dir = modelDir(app);
                deleteTree(dir);
                unzip(zip, app.getFilesDir());
                if (!new File(dir, "am/final.mdl").exists()) throw new Exception("모델 파일이 이상함");
                new File(dir, ".ok").createNewFile();
                NavListenerService.log("음성 모델 준비 끝 → 이제 끊기지 않고 계속 들음 (띵 소리 없음)");
            } catch (Throwable t) {
                NavListenerService.log("음성 모델 받기 실패 (" + t.getMessage() + ") → 예전 방식으로 듣고, 다음에 다시 받음");
            } finally {
                zip.delete();
                downloading = false;
            }
        }, "joa-model").start();
    }

    private static void unzip(File zip, File into) throws Exception {
        String root = into.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(into, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) continue; // 이상한 경로 막기
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        f.delete();
    }

    private static Object loadModel(Context c) {
        if (model != null) return model;
        synchronized (VoskEar.class) {
            if (model != null) return model;
            try {
                org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                model = new org.vosk.Model(modelDir(c).getAbsolutePath());
                NavListenerService.log("계속 듣는 음성 모델 불러옴");
            } catch (Throwable t) {
                broken = true;
                NavListenerService.log("음성 모델을 못 불러옴 (" + t.getMessage() + ") → 예전 방식으로 들음");
            }
            return model;
        }
    }

    // ───────── 정해진 말 목록 (부르는 말 + 명령) ─────────
    // 받아쓰기는 "하이 조아"를 "아바이 주 와 유희"처럼 엉뚱하게 적어서, 정해진 말 중에서 고르게 함 → 훨씬 정확

    static final String[] WAKES = {"하이 조아", "하이 좋아", "아이 좋아", "조아야", "좋아야"};
    static final String[] COMMANDS = {
            "헤드업 보여", "헤드업 켜", "허드 보여", "티맵 보여", "티맵 켜", "티맵 꺼", "지도 보여", "반반", "반반 화면",
            "정리해", "밝게", "더 밝게", "어둡게", "소리 크게", "소리 작게", "볼륨 올려", "볼륨 내려",
            "초록색", "흰색", "하늘색", "노란색", "주황색", "색 바꿔", "몇 시야", "지금 속도", "구간 평균", "구간 끝",
            "경고 꺼", "경고 켜", "안내 종료", "경로 다시", "다시 시작", "목적지 바꿔", "블루투스 켜", "블루투스 꺼",
            "하이패스", "요금소", "도움말", "뒤집어", "돌려", "가로로", "세로로", "좌우 반전", "상하 반전",
            "자리 바꿔", "주차 번호", "응", "아니", "네", "아니요", "그래"};

    private static String grammarJson;

    /** 모델 낱말장(words.txt)으로 각 말을 모델이 아는 조각들로 나눔. 못 나누는 말은 뺌 */
    static String buildGrammar(java.util.Set<String> vocab, java.util.List<String> dropped) {
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.LinkedHashSet<String> phrases = new java.util.LinkedHashSet<>();
        for (String w : WAKES) {
            phrases.add(w);
            for (String c : COMMANDS) phrases.add(w + " " + c);
        }
        java.util.Collections.addAll(phrases, COMMANDS);
        for (String ph : phrases) {
            String seg = vocab == null ? ph : segment(ph, vocab);
            if (seg == null) {
                if (dropped != null) dropped.add(ph);
                continue;
            }
            arr.put(seg);
        }
        arr.put("[unk]");
        return arr.toString();
    }

    /** "블루투스 켜" → "블루투스 켜" 또는 "블루 투스 켜" (모델이 아는 조각으로). 안 되면 null */
    static String segment(String phrase, java.util.Set<String> vocab) {
        StringBuilder out = new StringBuilder();
        for (String chunk : phrase.split(" ")) {
            if (chunk.isEmpty()) continue;
            int n = chunk.length();
            int[] best = new int[n + 1];
            int[] prev = new int[n + 1];
            java.util.Arrays.fill(best, Integer.MAX_VALUE);
            best[0] = 0;
            for (int i = 0; i < n; i++) {
                if (best[i] == Integer.MAX_VALUE) continue;
                for (int j = i + 1; j <= Math.min(n, i + 12); j++) {
                    if (vocab.contains(chunk.substring(i, j)) && best[i] + 1 < best[j]) {
                        best[j] = best[i] + 1;
                        prev[j] = i;
                    }
                }
            }
            if (best[n] == Integer.MAX_VALUE) return null;
            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
            for (int j = n; j > 0; j = prev[j]) parts.add(0, chunk.substring(prev[j], j));
            for (String p : parts) {
                if (out.length() > 0) out.append(' ');
                out.append(p);
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static java.util.Set<String> loadVocab(File modelDir) {
        for (String rel : new String[]{"graph/words.txt", "words.txt"}) {
            File f = new File(modelDir, rel);
            if (!f.exists()) continue;
            java.util.HashSet<String> v = new java.util.HashSet<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    v.add(sp > 0 ? line.substring(0, sp) : line.trim());
                }
                return v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String grammar(Context c) {
        if (grammarJson != null) return grammarJson;
        java.util.Set<String> vocab = loadVocab(modelDir(c));
        java.util.ArrayList<String> dropped = new java.util.ArrayList<>();
        grammarJson = buildGrammar(vocab, dropped);
        int n = 0;
        try {
            n = new org.json.JSONArray(grammarJson).length() - 1;
        } catch (Throwable ignored) {
        }
        NavListenerService.log("정해진 말 " + n + "개로 듣기 준비" + (vocab == null ? " (낱말장 없음)" : "")
                + (dropped.isEmpty() ? "" : " — 모델이 모르는 말: " + String.join(", ", dropped.subList(0, Math.min(8, dropped.size())))));
        return grammarJson;
    }

    /** 고른 말 결과 JSON → (띄어쓰기 없는 글자, 가장 낮은 확신도) */
    private static Object[] picked(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("text", "").trim();
            if (t.isEmpty() || t.contains("[unk]")) return new Object[]{"", 0f};
            float minConf = 1f;
            org.json.JSONArray r = o.optJSONArray("result");
            if (r != null) for (int i = 0; i < r.length(); i++)
                minConf = Math.min(minConf, (float) r.getJSONObject(i).optDouble("conf", 1.0));
            return new Object[]{t.replace(" ", ""), minConf};
        } catch (Throwable e) {
            return new Object[]{"", 0f};
        }
    }

    // ───────── 듣기 ─────────

    /** 지금 계속 듣는 중인 귀를 이 리스너로 (하나만 돌림) */
    static synchronized VoskEar start(Context c, Listener l) {
        if (running != null) {
            running.listener = l;
            running.paused = false;
            return running;
        }
        VoskEar e = new VoskEar();
        e.listener = l;
        e.on = true;
        final Context app = c.getApplicationContext();
        e.thread = new Thread(() -> e.loop(app), "joa-ear");
        e.thread.start();
        running = e;
        return e;
    }

    static synchronized void stopAll() {
        if (running != null) {
            running.on = false;
            running = null;
        }
    }

    static boolean isRunning() {
        return running != null;
    }

    /** 잠깐 쉬기 (목적지 화면이 자기 인식기를 쓸 때·주차·뜨거울 때) */
    static void pause(boolean p) {
        VoskEar e = running;
        if (e != null) e.paused = p;
    }

    private void loop(Context c) {
        Object mdl = loadModel(c);
        if (mdl == null) {
            on = false;
            synchronized (VoskEar.class) {
                if (running == this) running = null;
            }
            return;
        }
        AudioRecord rec = null;
        org.vosk.Recognizer rz = null, rg = null;
        try {
            rz = new org.vosk.Recognizer((org.vosk.Model) mdl, (float) RATE);
            try {
                rg = new org.vosk.Recognizer((org.vosk.Model) mdl, (float) RATE, grammar(c));
                rg.setWords(true); // 낱말마다 확신도
            } catch (Throwable t) {
                rg = null;
                NavListenerService.log("정해진 말 듣기를 못 켬 (" + t.getMessage() + ") → 받아쓰기로만");
            }
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 6400) * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("마이크를 못 엶");
            attachEffects(rec.getAudioSessionId());
            rec.startRecording();
            NavListenerService.log("부르는 말: 끊기지 않고 계속 듣기 시작 (폰 안 음성 모델)");

            byte[] buf = new byte[3200];          // 0.1초
            byte[][] ring = new byte[5][];        // 말 시작 직전 0.5초 (첫 글자 안 잘리게)
            int ringPos = 0;
            double floor = 300;                   // 주변 소음 크기 (차 소음에 맞춰 천천히 따라감)
            boolean gate = false;
            long quietSince = 0;
            String lastPartial = "";
            long lastStat = 0;
            int peak = 0;
            while (on) {
                if (paused || WakeWord.hotNow || (AutoService.parked() && !HudActivity.visible) || DestActivity.visible) {
                    if (gate) {
                        gate = false;
                        rz.reset();
                        if (rg != null) rg.reset();
                        gList.clear();
                        gConfs.clear();
                        fBuf.setLength(0);
                    }
                    Thread.sleep(300);
                    rec.read(buf, 0, buf.length); // 버퍼 비우기
                    continue;
                }
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    Thread.sleep(50);
                    continue;
                }
                long now = System.currentTimeMillis();
                double rms = rms(buf, n);
                if (rms > peak) peak = (int) rms;
                if (now - lastStat > 1000) {
                    WakeWord.micAliveAt = now;
                    WakeWord.micLevel = peak;
                    peak = 0;
                    lastStat = now;
                }
                // 소음 바닥은 조용할 때만 천천히 따라감
                if (!gate) floor = floor * 0.97 + Math.min(rms, floor * 3) * 0.03;
                boolean loud = rms > floor * 1.6 + 60; // 대시보드 위 폰이라 말소리가 작게 들어와도 열리게
                statMax = Math.max(statMax, (int) rms);
                if (loud && !gate) statOpens++;
                if (now - statAt > 60_000L) { // 1분마다 듣는 상태 한 줄 (문제 찾기용)
                    NavListenerService.log("듣는 중: 소리 최대 " + statMax + ", 주변 소음 " + (int) floor
                            + ", 말소리 감지 " + statOpens + "번, 알아들은 말 " + statHeard + "개");
                    statAt = now;
                    statMax = 0;
                    statOpens = 0;
                    statHeard = 0;
                }
                if (!gate) {
                    ring[ringPos] = java.util.Arrays.copyOf(buf, n);
                    ringPos = (ringPos + 1) % ring.length;
                    if (!loud) continue;
                    gate = true;
                    quietSince = 0;
                    for (int k = 0; k < ring.length; k++) { // 앞 0.5초부터 넣기
                        byte[] b = ring[(ringPos + k) % ring.length];
                        if (b != null) {
                            if (rz.acceptWaveForm(b, b.length)) add(fBuf, text(rz.getResult(), "text"));
                            if (rg != null && rg.acceptWaveForm(b, b.length)) addPick(rg.getResult());
                        }
                        ring[(ringPos + k) % ring.length] = null;
                    }
                }
                if (rg != null && rg.acceptWaveForm(buf, n)) addPick(rg.getResult());
                boolean done = rz.acceptWaveForm(buf, n);
                if (done) {
                    add(fBuf, text(rz.getResult(), "text"));
                    lastPartial = "";
                } else {
                    String p = text(rz.getPartialResult(), "partial");
                    if (!p.isEmpty() && !p.equals(lastPartial)) {
                        lastPartial = p;
                        final String pp = p;
                        main.post(() -> {
                            Listener l = listener;
                            if (l != null) l.onPartial(pp);
                        });
                    }
                }
                if (loud) quietSince = 0;
                else if (quietSince == 0) quietSince = now;
                // 1.5초 조용하면 문을 닫고 마무리 (Vosk는 쉬게 해서 열 줄이기)
                if (quietSince > 0 && now - quietSince > 1500) {
                    add(fBuf, text(rz.getFinalResult(), "text"));
                    if (rg != null) addPick(rg.getFinalResult());
                    deliver(gList, gConfs, fBuf.toString());
                    gList.clear();
                    gConfs.clear();
                    fBuf.setLength(0);
                    lastPartial = "";
                    gate = false;
                }
            }
        } catch (Throwable t) {
            NavListenerService.log("계속 듣기 멈춤 (" + t.getMessage() + ") → 예전 방식으로");
            broken = t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError;
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
            }
            if (rz != null) {
                try { rz.close(); } catch (Throwable ignored) { }
            }
            if (rg != null) {
                try { rg.close(); } catch (Throwable ignored) { }
            }
            synchronized (VoskEar.class) {
                if (running == this) running = null;
            }
            final boolean wasOn = on;
            on = false;
            if (wasOn) main.post(() -> {
                Listener l = listener;
                if (l instanceof WakeWord) ((WakeWord) l).earStopped();
            });
        }
    }

    // 문이 열려 있는 동안 모은 결과 (한 번 말한 것 전체)
    // 고른 말은 조각마다 따로 (이어 붙이면 "하이 조아"가 가운데 묻혀 못 알아봄 — v61 실수)
    private final java.util.ArrayList<String> gList = new java.util.ArrayList<>();
    private final java.util.ArrayList<Float> gConfs = new java.util.ArrayList<>();
    private final StringBuilder fBuf = new StringBuilder();

    private static void add(StringBuilder b, String t) {
        if (t == null || t.isEmpty()) return;
        if (b.length() > 0) b.append(' ');
        b.append(t);
    }

    private void addPick(String json) {
        Object[] p = picked(json);
        String t = (String) p[0];
        if (t.isEmpty()) return;
        gList.add(t);
        gConfs.add((Float) p[1]);
    }

    private void deliver(java.util.List<String> picks, java.util.List<Float> confs, String free) {
        final java.util.ArrayList<String> g = new java.util.ArrayList<>(picks);
        final java.util.ArrayList<Float> c = new java.util.ArrayList<>(confs);
        final String f = free == null ? "" : free.trim();
        if (g.isEmpty() && f.isEmpty()) return;
        statHeard++;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onHeard(g, c, f);
        });
    }

    private static String text(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static double rms(byte[] b, int n) {
        long sum = 0;
        int cnt = 0;
        for (int k = 0; k + 1 < n; k += 2) {
            int v = (short) ((b[k] & 0xff) | (b[k + 1] << 8));
            sum += (long) v * v;
            cnt++;
        }
        return cnt == 0 ? 0 : Math.sqrt(sum / (double) cnt);
    }

    /** 폰 스피커 소리(앱 대답·티맵 안내)를 마이크에서 덜어내고 잡음 줄이기 (되는 폰만) */
    private static void attachEffects(int session) {
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler a = android.media.audiofx.AcousticEchoCanceler.create(session);
                if (a != null) a.setEnabled(true);
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
