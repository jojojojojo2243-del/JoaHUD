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
        /** 말 한 마디가 끝남 (글자) */
        void onHeard(String text);

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
        org.vosk.Recognizer rz = null;
        try {
            rz = new org.vosk.Recognizer((org.vosk.Model) mdl, (float) RATE);
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
                if (paused || WakeWord.hotNow || AutoService.parked() || DestActivity.visible) {
                    if (gate) {
                        gate = false;
                        rz.reset();
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
                boolean loud = rms > floor * 2.0 + 120;
                if (!gate) {
                    ring[ringPos] = java.util.Arrays.copyOf(buf, n);
                    ringPos = (ringPos + 1) % ring.length;
                    if (!loud) continue;
                    gate = true;
                    quietSince = 0;
                    for (int k = 0; k < ring.length; k++) { // 앞 0.5초부터 넣기
                        byte[] b = ring[(ringPos + k) % ring.length];
                        if (b != null) rz.acceptWaveForm(b, b.length);
                        ring[(ringPos + k) % ring.length] = null;
                    }
                }
                boolean done = rz.acceptWaveForm(buf, n);
                if (done) {
                    deliver(text(rz.getResult(), "text"));
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
                    deliver(text(rz.getFinalResult(), "text"));
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

    private void deliver(String t) {
        if (t == null || t.trim().isEmpty()) return;
        final String s = t.trim();
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onHeard(s);
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
