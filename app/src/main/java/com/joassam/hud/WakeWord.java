package com.joassam.hud;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 운전 중 "조아야"(바꿀 수 있음) 하고 부르면 목적지 화면을 띄움.
 * HUD 화면이 떠 있는 동안만 듣고, 폰이 뜨거우면(42℃ 이상) 쉬었다가 39℃ 아래로 내려가면 다시 들음.
 * "조아야 집" 처럼 이어서 말하면 바로 그곳으로 안내.
 */
final class WakeWord implements VoskEar.Listener {

    private final android.content.Context act; // HUD 화면 또는 VoiceService (HUD 밖에서도 들음)
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean on = false;
    private boolean hot = false;
    /** 계속 듣는 귀(VoskEar)도 보는 '뜨거움' 상태 */
    static volatile boolean hotNow = false;
    /** 계속 듣는 귀를 쓰는 중 */
    private boolean usingVosk = false;
    /** 마지막으로 들은 말이 계속 듣는 귀에서 왔나 (목적지는 정확한 인식기로 다시 물음) */
    private boolean heardByVosk = false;
    private long lastIgnoredLog = 0;
    /** 마이크 소리 크기 (기록용) */
    static volatile int micLevel = 0;
    /** 앱이 말하는 중 "하이 조아"가 들리면 말을 끊음 (HUD가 등록) */
    static volatile Runnable shutUp;
    private int errors = 0;
    private int savedSystemVol = -1;
    /** 폰 안 인식기(소리 없음)를 쓰는 중인지. 안 되면 구글 인식기로 바꾸고 기억함 */
    private boolean onDevice = false;
    private static boolean onDeviceFailed = false;

    // ───── 티맵이 앞에 있을 때: 우리 앱이 직접 마이크를 열어 인식기에 소리를 넘겨줌 ─────
    // (티맵이 앞에 나오면 인식기가 스스로 마이크를 못 잡아 오류 5·무응답이 남.
    //  접근성 서비스가 있는 우리 앱이 녹음해서 파이프로 넘기면 인식기는 마이크를 안 열어도 됨)
    private android.media.AudioRecord rec;
    private volatile boolean recOn = false;
    private android.os.ParcelFileDescriptor recRead, recWrite;
    private static boolean extFailed = false, extLogged = false;
    private int extQuickErrs = 0;
    private long extStartAt = 0;
    private volatile int recPeak = 0;
    private long silentSince = 0, lastSilentLog = 0;
    private boolean usingExt = false;

    private boolean wantExt() {
        if (extFailed || android.os.Build.VERSION.SDK_INT < 33) return false;
        if (!act.getSharedPreferences("hud", Activity.MODE_PRIVATE).getBoolean("ext_mic", true)) return false;
        return !HudActivity.visible || TmapA11yService.mapMode; // HUD가 안 보일 때(티맵·지도·반반)
    }

    /** 녹음 시작 → 인식기에 넘길 읽는 쪽 파이프. 실패하면 null */
    private android.os.ParcelFileDescriptor startExt() {
        stopExt();
        try {
            int rate = 16000;
            int min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200) * 2);
            if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = null;
                return null;
            }
            android.os.ParcelFileDescriptor[] pp = android.os.ParcelFileDescriptor.createPipe();
            recRead = pp[0];
            recWrite = pp[1];
            final android.media.AudioRecord r = rec;
            final java.io.OutputStream out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(recWrite);
            r.startRecording();
            recOn = true;
            new Thread(() -> {
                byte[] buf = new byte[3200];
                long winStart = System.currentTimeMillis();
                int peak = 0;
                try {
                    while (recOn) {
                        int n = r.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        for (int k = 0; k + 1 < n; k += 2) {
                            int v = Math.abs((short) ((buf[k] & 0xff) | (buf[k + 1] << 8)));
                            if (v > peak) peak = v;
                        }
                        out.write(buf, 0, n);
                        long now = System.currentTimeMillis();
                        if (now - winStart > 1000) {
                            recPeak = peak;
                            if (peak > 0) micAliveAt = now;
                            checkSilence(peak, now);
                            peak = 0;
                            winStart = now;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { out.close(); } catch (Exception ignored) { }
                }
            }, "joa-mic").start();
            return recRead;
        } catch (Throwable t) {
            NavListenerService.log("직접 마이크 열기 실패: " + t.getMessage());
            stopExt();
            return null;
        }
    }

    /** 소리가 계속 0이면 다른 앱이 마이크를 쥐고 있는 것 → 기록에 알림 (2분에 한 번) */
    private void checkSilence(int peak, long now) {
        if (peak > 30) {
            silentSince = 0;
            return;
        }
        if (silentSince == 0) silentSince = now;
        if (now - silentSince > 8000 && now - lastSilentLog > 120_000L) {
            lastSilentLog = now;
            NavListenerService.log("마이크에 소리가 전혀 안 들어옴 → 다른 앱(티맵 음성 호출 '아리아' 등)이 마이크를 쓰는 중. 티맵 설정에서 음성 호출을 꺼 주세요");
        }
    }

    private void stopExt() {
        recOn = false;
        android.media.AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
            try { r.release(); } catch (Exception ignored) { }
        }
        try { if (recRead != null) recRead.close(); } catch (Exception ignored) { }
        recRead = null;
        recWrite = null;
    }
    /** 이 시각까지는 폰 안 인식기를 쉬고 구글 인식기로 (티맵이 앞에 있을 때 오류 5가 계속 나는 경우) */
    private static long onDeviceRestUntil = 0;
    private int clientErrs = 0;
    private long clientErrSince = 0;
    /** HUD의 마이크 표시용: 마지막으로 듣기가 살아 있던 시각 / 마지막 오류 시각 */
    static volatile long micAliveAt = 0, micErrAt = 0;

    /** HUD 마이크 표시: 지금 '조아야'를 듣고 있나 */
    static boolean micListening() {
        long now = System.currentTimeMillis();
        return micAliveAt > micErrAt && now - micAliveAt < 35_000L;
    }
    private boolean onDeviceWorked = false;
    static volatile String status = "";

    WakeWord(android.content.Context a) {
        act = a;
    }

    static String word(SharedPreferences p) {
        String w = p.getString("wake_word", "조아야").trim();
        return w.isEmpty() ? "조아야" : w;
    }

    void start() {
        SharedPreferences p = act.getSharedPreferences("hud", Activity.MODE_PRIVATE);
        if (!p.getBoolean("wake_on", true)) return;
        if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "마이크 권한 없음";
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            status = "음성 인식 없음";
            return;
        }
        restoreMusic(act);
        on = true;
        alive();
        stallH.removeCallbacks(stallCheck);
        stallH.postDelayed(stallCheck, 30_000L);
        errors = 0;
        muteBeep(true);
        h.postDelayed(this::listen, 1500);
        h.postDelayed(monitor, 1500);
    }

    void stop() {
        on = false;
        if (usingVosk) VoskEar.stopAll();
        usingVosk = false;
        stopExt();
        stallH.removeCallbacks(stallCheck);
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {
        }
        tts = null;
        ttsReady = false;
        h.removeCallbacksAndMessages(null);
        if (sr != null) {
            try {
                sr.cancel();
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
        muteBeep(false);
        musicMute(false);
    }

    /** '띵' 소리를 막음: 시스템·알림 소리는 듣는 동안 계속 끄고 */
    private void muteBeep(boolean mute) {
        AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
        try {
            if (mute) {
                if (savedSystemVol < 0) savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM);
                am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
            } else if (savedSystemVol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0);
                savedSystemVol = -1;
            }
        } catch (Exception ignored) {
        }
        try {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION,
                    mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {
        }
        if (!mute) musicMute(false);
    }

    /** 삼성 폰은 '띵'을 미디어 소리로 냄 → 듣기를 켜고 끄는 1초 남짓만 미디어 소리를 끔.
     *  티맵이 말하는 중이면 끄지 않음 (그때는 다시 듣기를 미룸). */
    private boolean musicMuted = false;
    private final Runnable unmuteMusic = () -> { if (!steady()) musicMute(false); };

    /**
     * '띵' 완전 차단: 구글 인식기를 쓸 때는 듣는 동안 미디어 소리를 계속 꺼 두고,
     * 티맵 안내 음성(0.3초 넘게 이어지는 소리)이나 앱 음성 답이 나올 때만 켬. 짧은 '띵'은 묻힘.
     */
    private boolean steady() {
        return on && !onDevice && !usingVosk && act.getSharedPreferences("hud", Activity.MODE_PRIVATE).getBoolean("ding_block", true);
    }

    /** 앱이 음성으로 답하는 동안(ms) 미디어 소리를 켜 둠 */
    static volatile long ttsUntil = 0;
    /** 앱이 마지막으로 소리 내어 한 말 (자기 목소리를 명령으로 다시 알아듣지 않게) */
    private static volatile String lastSaid = "";
    private static volatile long lastSaidAt = 0;

    /** 앱이 말하기 직전에 부름: 말하는 동안 + 끝난 뒤 잠깐은 듣지 않고, 같은 말이 들리면 버림 */
    static void spoke(String msg) {
        if (msg == null) return;
        long now = System.currentTimeMillis();
        ttsUntil = now + 1000 + msg.length() * 200L;
        lastSaid = msg;
        lastSaidAt = now;
    }

    private static String normEcho(String s) {
        return s == null ? "" : s.replaceAll("[\\s.,!?~'\"]", "");
    }

    private static java.util.HashSet<String> bigrams(String s) {
        java.util.HashSet<String> b = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) b.add(s.substring(i, i + 2));
        return b;
    }

    /** 들은 말이 방금 앱이 한 말과 거의 같으면 true */
    static boolean isEcho(String heard) {
        if (System.currentTimeMillis() - lastSaidAt > 20_000L) return false;
        String h = normEcho(heard), m = normEcho(lastSaid);
        if (h.length() < 3 || m.length() < 3) return false;
        if (m.contains(h) && h.length() >= m.length() * 0.6) return true;
        java.util.HashSet<String> bh = bigrams(h), bm = bigrams(m);
        int both = 0;
        for (String x : bh) if (bm.contains(x)) both++;
        // 양쪽 다 많이 겹쳐야 메아리 ("블루투스 켜" ↔ "블루투스를 켤게요"는 메아리 아님)
        return bh.size() > 0 && bm.size() > 0 && both >= bh.size() * 0.6 && both >= bm.size() * 0.6;
    }
    private int activeMs = 0;
    private long holdUntil = 0;
    private final Runnable monitor = new Runnable() {
        @Override
        public void run() {
            if (!on) return;
            if (steady()) {
                long now = System.currentTimeMillis();
                boolean active = false;
                try {
                    active = ((AudioManager) act.getSystemService(Activity.AUDIO_SERVICE)).isMusicActive();
                } catch (Exception ignored) {
                }
                activeMs = active ? activeMs + 150 : 0;
                if (now < ttsUntil || activeMs >= 300) {
                    if (musicMuted) musicMute(false);   // 티맵 안내·앱 답은 들리게
                    holdUntil = now + 1200;
                } else if (!active && now > holdUntil && !musicMuted) {
                    musicMute(true);                     // 조용해지면 다시 끔
                }
            }
            h.postDelayed(this, 150);
        }
    };

    private void musicMute(boolean mute) {
        if (mute == musicMuted) return;
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            // 구글 인식기의 '띵'은 폰에 따라 시스템 소리로도 나서 같이 막음 (티맵 안내 음성과는 상관없음)
            try {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                        mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception ignored) {
            }
            musicMuted = mute;
            act.getSharedPreferences("hud", Activity.MODE_PRIVATE).edit().putBoolean("music_muted", mute).apply();
        } catch (Exception ignored) {
        }
        h.removeCallbacks(unmuteMusic);
        if (mute && !steady()) h.postDelayed(unmuteMusic, 2500); // 무슨 일이 있어도 2.5초 뒤엔 다시 켬
    }

    private boolean tmapTalking() {
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            return !musicMuted && am.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }

    /** 혹시 앱이 꺼지면서 미디어 소리가 꺼진 채로 남았으면 되살림 */
    static void restoreMusic(android.content.Context c) {
        SharedPreferences p = c.getSharedPreferences("hud", Activity.MODE_PRIVATE);
        if (!p.getBoolean("music_muted", false)) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Activity.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {
        }
        p.edit().putBoolean("music_muted", false).apply();
    }

    /** 말이 없으면 기다리다 끝나며 '띵' 나기 전에 우리가 조용히 끊고 다시 시작 */
    private final Runnable quietRestart = () -> {
        if (!on) return;
        if (tmapTalking()) { h.postDelayed(this.quietRestartRef(), 1000); return; }
        musicMute(true);
        try { if (sr != null) sr.cancel(); } catch (Exception ignored) { }
        h.postDelayed(this::listen, 150);
    };

    private Runnable quietRestartRef() { return quietRestart; }

    /** 배터리 온도(℃) */
    private float temp() {
        Intent b = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
    }

    /** 듣기가 멈춘 채로 남는 것 막기: 마지막으로 듣기를 시작하거나 인식기가 답한 시각 */
    private volatile long lastAlive = 0;
    private long musicWaitSince = 0;
    private long lastErrLog = 0;
    private long lastStallLog = 0;

    private void alive() {
        lastAlive = System.currentTimeMillis();
    }

    /** 30초마다 검사: 45초 넘게 듣기가 안 돌면 인식기를 새로 만들어 다시 시작 */
    private final Runnable stallCheck = new Runnable() {
        @Override
        public void run() {
            if (!on) return;
            long now = System.currentTimeMillis();
            if (lastAlive > 0 && now - lastAlive > 45_000L && !DestActivity.visible && !AutoService.parked() && !hot) {
                if (now - lastStallLog > 120_000L) {
                    lastStallLog = now;
                    NavListenerService.log("부르는 말 듣기가 멈춰 있어서 다시 시작" + (TmapA11yService.mapMode ? " (지도 화면 중)" : ""));
                }
                if (sr != null) {
                    try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
                    sr = null;
                }
                musicWaitSince = 0;
                again(300);
            }
            stallH.postDelayed(this, 30_000L);
        }
    };
    private final Handler stallH = new Handler(android.os.Looper.getMainLooper());

    private void listen() {
        if (!on) return;
        alive();
        // 목적지 화면이 마이크를 쓰는 중이면 비켜 줌
        if (DestActivity.visible) {
            if (sr != null) {
                try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
                sr = null;
            }
            status = "목적지 화면이 듣는 중";
            h.postDelayed(this::listen, 1500);
            return;
        }
        // 시동 꺼짐(주차) 중엔 쉼
        // 시동 꺼진 뒤엔 쉼 — 단, HUD 화면을 직접 띄워 둔 동안은 들음 (집에서 시험할 때 등)
        if (AutoService.parked() && !HudActivity.visible) {
            status = "주차 중이라 쉼";
            h.postDelayed(this::listen, 10_000);
            return;
        }
        float t = temp();
        if (hot && t < 39f) {
            hot = false;
            hotNow = false;
            NavListenerService.log("폰 온도 " + t + "℃ → 부르는 말 다시 듣기");
        } else if (!hot && t >= 42f) {
            hot = true;
            hotNow = true;
            NavListenerService.log("폰이 뜨거움 " + t + "℃ → 부르는 말 듣기 잠시 멈춤");
        }
        if (hot) {
            status = "폰이 뜨거워 쉬는 중 (" + t + "℃)";
            h.postDelayed(this::listen, 30_000);
            return;
        }
        // ── 계속 듣는 귀 (모델이 있으면): 끊기는 틈 없이 듣고, 티맵이 앞에 있어도 들음 ──
        if (VoskEar.usable(act)) {
            if (!usingVosk) {
                usingVosk = true;
                if (sr != null) {
                    try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
                    sr = null;
                }
                stopExt();
                h.removeCallbacks(quietRestart);
                musicMute(false);
            }
            VoskEar.start(act, this);
            status = "'" + word(act.getSharedPreferences("hud", Activity.MODE_PRIVATE)) + "' 계속 듣는 중";
            h.postDelayed(this::listen, 10_000); // 10초마다 온도·상태만 확인
            return;
        }
        if (usingVosk) {
            usingVosk = false;
            VoskEar.stopAll();
        }
        if (tmapTalking()) { // 티맵 안내 음성 중엔 '띵'이 겹치지 않게 끝날 때까지 기다림
            long now = System.currentTimeMillis();
            if (musicWaitSince == 0) musicWaitSince = now;
            // 소리가 15초 넘게 계속 나면(티맵이 앞에 있을 때 등) 안내 음성이 아니라고 보고 그냥 들음
            if (now - musicWaitSince < 15_000L) {
                h.postDelayed(this::listen, 1000);
                return;
            }
        } else musicWaitSince = 0;
        // 구글 인식기로 쉬던 시간이 끝나면 다시 폰 안 인식기(띵 소리 없음)로
        if (sr != null && !onDevice && onDeviceRestUntil > 0 && System.currentTimeMillis() > onDeviceRestUntil) {
            onDeviceRestUntil = 0;
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
        try {
            if (sr == null) {
                onDevice = false;
                if (!onDeviceFailed && System.currentTimeMillis() > onDeviceRestUntil && android.os.Build.VERSION.SDK_INT >= 33
                        && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) {
                    try {
                        sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(act);
                        onDevice = true;
                        NavListenerService.log("부르는 말: 폰 안 인식기로 듣기 (띵 소리 없음)");
                    } catch (Exception e) {
                        sr = null;
                    }
                }
                if (sr == null) {
                    sr = SpeechRecognizer.createSpeechRecognizer(act);
                    NavListenerService.log("부르는 말: 구글 인식기로 듣기 (띵 소리를 미디어 음소거로 막음)");
                }
                sr.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 폰 안에서 처리(데이터·열 절약), 안 되면 자동으로 온라인
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            usingExt = false;
            if (wantExt()) {
                android.os.ParcelFileDescriptor src = startExt();
                if (src != null) {
                    i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, src)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
                    usingExt = true;
                    extStartAt = System.currentTimeMillis();
                    if (!extLogged) {
                        extLogged = true;
                        NavListenerService.log("HUD 밖(티맵·지도·반반)에선 앱이 직접 마이크를 열어 '조아야'를 들음");
                    }
                }
            } else stopExt();
            if (!onDevice && !usingExt) musicMute(true);
            sr.cancel();
            sr.startListening(i);
            h.removeCallbacks(quietRestart);
            h.postDelayed(quietRestart, onDevice ? 30_000 : 3500);
            status = "'" + word(act.getSharedPreferences("hud", Activity.MODE_PRIVATE)) + "' 듣는 중";
        } catch (Exception e) {
            again(5000);
        }
    }

    private void again(long ms) {
        if (!on) return;
        h.removeCallbacks(quietRestart);
        h.removeCallbacksAndMessages(null);
        if (musicMuted) h.postDelayed(unmuteMusic, Math.min(ms + 1500, 2500));
        h.postDelayed(this::listen, ms);
        h.postDelayed(monitor, 150);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {
            micAliveAt = System.currentTimeMillis();
            h.removeCallbacks(unmuteMusic);
            if (musicMuted) h.postDelayed(unmuteMusic, 1200); // 시작 '띵'이 지나간 뒤 미디어 소리 되살림
        }
        @Override public void onBeginningOfSpeech() {
            h.removeCallbacks(quietRestart); // 말하는 중엔 끊지 않음 (대신 최대 10초)
            h.postDelayed(quietRestart, 10_000);
        }
        @Override public void onRmsChanged(float v) {
            long n = System.currentTimeMillis();
            if (n - micAliveAt > 1000) micAliveAt = n;
        }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() {
            h.removeCallbacks(quietRestart);
            if (!onDevice && !tmapTalking()) musicMute(true); // 끝날 때 나는 '띵'도 막음
        }
        @Override public void onEvent(int t, Bundle b) { }

        @Override
        public void onPartialResults(Bundle b) {
            // 부르는 말이 들리는 순간 바로 반응하지 않고, 끝까지 들은 뒤(onResults) 판단
        }

        @Override
        public void onResults(Bundle b) {
            alive();
            if (usingExt) extQuickErrs = 0;
            micAliveAt = System.currentTimeMillis();
            clientErrs = 0;
            errors = 0;
            if (onDevice) onDeviceWorked = true;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            long nowR = System.currentTimeMillis();
            if (r != null && !r.isEmpty() && (nowR < ttsUntil + 1200 || isEcho(r.get(0)))) {
                // 앱이 방금 한 말(예: "블루투스가 안 켜졌어요")을 다시 듣고 명령으로 착각하지 않게 버림
                NavListenerService.log("앱 자기 목소리라 무시: \"" + r.get(0) + "\"");
                again(400);
                return;
            }
            if (r != null && check(r)) return;
            again(200);
        }

        @Override
        public void onError(int e) {
            alive();
            // 인식기가 '직접 넘긴 소리'를 못 받는 폰이면 곧바로 오류 → 두 번이면 이 방식 끄기
            if (usingExt && e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    && System.currentTimeMillis() - extStartAt < 1500) {
                if (++extQuickErrs >= 2) {
                    extFailed = true;
                    stopExt();
                    NavListenerService.log("이 폰 인식기는 앱이 넘긴 소리를 못 받음 (오류 " + e + ") → 예전 방식으로 듣기");
                }
            }
            long nowE = System.currentTimeMillis();
            if (e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && nowE - lastErrLog > 60_000L) {
                lastErrLog = nowE;
                NavListenerService.log("부르는 말 인식 오류 " + e + (e == 3 || e == 8
                        ? " (마이크를 다른 앱이 쓰는 중 — 티맵 '아리아' 호출이 켜져 있으면 끄세요)" : ""));
            }
            // 폰 안 인식기가 한국어를 못 하거나 연결이 안 되면 → 구글 인식기로 바꿈
            if (onDevice && !onDeviceWorked && (e == 5 || e == 11 || e == 12 || e == 13 || e == 14)) {
                onDeviceFailed = true;
                NavListenerService.log("폰 안 인식기 사용 불가(오류 " + e + ") → 구글 인식기로 바꿈");
                if ((e == 12 || e == 13) && android.os.Build.VERSION.SDK_INT >= 33) {
                    try {
                        Intent d = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
                        sr.triggerModelDownload(d); // 한국어 자료를 받아 두면 다음부터 소리 없이 들음
                    } catch (Exception ignored) {
                    }
                }
                try { sr.destroy(); } catch (Exception ignored) { }
                sr = null;
                again(500);
                return;
            }
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                micAliveAt = System.currentTimeMillis();
                clientErrs = 0;
                errors = 0;
                again(200);
                return;
            }
            errors++;
            micErrAt = System.currentTimeMillis();
            // 오류 5(인식기 꼬임)·7·8(바쁨)·11(연결 끊김): 같은 인식기로 다시 해 봐야 계속 실패 → 새로 만듦
            if (e == 5 || e == 7 || e == 8 || e == 11) {
                long n = System.currentTimeMillis();
                if (n - clientErrSince > 30_000L) {
                    clientErrSince = n;
                    clientErrs = 0;
                }
                clientErrs++;
                try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
                sr = null;
                // 폰 안 인식기가 30초 안에 3번 꼬이면(주로 티맵이 앞에 있을 때) 3분 동안 구글 인식기로
                if (onDevice && clientErrs >= 3) {
                    onDeviceRestUntil = n + 3 * 60_000L;
                    clientErrs = 0;
                    NavListenerService.log("폰 안 인식기가 계속 꼬임(오류 " + e + ") → 3분 동안 구글 인식기로 듣기 (띵 소리는 음소거)");
                }
                again(e == 8 ? 1500 : 600);
                return;
            }
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                status = "마이크 권한 없음";
                stop();
                return;
            }
            // 바쁨·네트워크 오류: 점점 길게 쉬었다 다시
            again(errors > 10 ? 30_000 : errors > 3 ? 5000 : 1000);
        }
    };

    /** 들은 말 안에 부르는 말이 있으면 목적지 화면 (뒤에 이어진 말은 목적지로 넘김) */
    /** 이어서 대화하기: 명령·대답 뒤 이 시각까지는 부르는 말 없이도 알아들음 */
    static volatile long followUntil = 0;
    /** "조아야"만 불렀을 때: 이 시각까지 다음 말은 명령·AI·목적지 무엇이든 받음 (화면은 HUD 그대로) */
    private long askedUntil = 0;

    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;

    /** 화면을 바꾸지 않고 소리로만 대답 */
    private void speak(String msg) {
        spoke(msg);
        try {
            if (tts == null) {
                tts = new android.speech.tts.TextToSpeech(act.getApplicationContext(), st -> {
                    ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                    if (ttsReady && tts != null) {
                        tts.setLanguage(java.util.Locale.KOREAN);
                        tts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "wake");
                    }
                });
            } else if (ttsReady) {
                tts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "wake");
            }
        } catch (Exception ignored) {
        }
    }

    /** 목적지로 길안내 (이때만 목적지 화면을 띄움) */
    private void goDest(String said) {
        if (heardByVosk) {
            // 계속 듣는 귀는 짧은 명령엔 충분하지만 장소 이름은 틀리기 쉬움 → 목적지 화면이 정확한 인식기로 다시 물음
            NavListenerService.log("장소 이름은 정확한 인식기로 다시 물어봄");
            said = null;
        }
        if (sr != null) {
            try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
        Intent di = new Intent(act, DestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (said != null) di.putExtra("said", said);
        else di.putExtra("where", true);
        act.startActivity(di);
        again(3000);
    }

    /** "조아야"를 폰이 자주 이렇게 알아들음 → 뒤에 말이 이어질 때만 부르는 말로 인정 */
    private static final String[] JOA_ALIAS = {"좋아요", "좋아야", "조아요", "좋아", "조아", "하이좋아", "하이조아"};

    // ───── 계속 듣는 귀에서 온 말 ─────

    @Override
    public void onHeard(String g, float conf, String free) {
        if (!on || !usingVosk) return;
        alive();
        long now = System.currentTimeMillis();
        micAliveAt = now;
        String w = word(act.getSharedPreferences("hud", Activity.MODE_PRIVATE)).replace(" ", "");
        boolean follow = now < followUntil;
        // 정해진 말 목록에서 고른 것: 확신도가 낮으면 아무 소리를 억지로 맞춘 것이라 버림
        boolean gOk = !g.isEmpty() && conf >= 0.55f;
        boolean gWake = gOk && strongWake(g, w) && after(g, w) != null;
        // 티맵 안내 음성("잠시 후 우회전입니다")은 명령이 아님
        if (!gWake && isNavVoice(free)) return;
        // 앱이 말하는 중·직후에 들린 건 자기 목소리 (부르는 말이 확실하면 끼어들기)
        if (!gWake && (now < ttsUntil + 800 || isEcho(free) || isEcho(g))) return;
        String use;
        if (gWake) use = g;                                      // "하이조아블루투스켜"
        else if (follow && gOk) use = g;                         // 부른 뒤 명령만 ("블루투스켜")
        else if (follow && !free.isEmpty()) use = free;          // 부른 뒤 자유 대화·목적지
        else if (strongWake(free, w) && after(free.replace(" ", ""), w) != null) use = free;
        else {
            if (now - lastIgnoredLog > 20_000L) { // 무엇을 듣고 있는지 기록 (20초에 한 줄)
                lastIgnoredLog = now;
                NavListenerService.log("들렸지만 부르는 말 없음: 고른 말 \"" + g + "\"(" + Math.round(conf * 100)
                        + "%) / 받아쓰기 \"" + free + "\"");
            }
            return;
        }
        NavListenerService.log("들음: \"" + use + "\"" + (use.equals(g) ? " (확신 " + Math.round(conf * 100) + "%)" : "")
                + (!free.isEmpty() && !use.equals(free) ? " / 받아쓰기 \"" + free + "\"" : ""));
        heardByVosk = true;
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        l.add(use);
        check(l);
        heardByVosk = false;
    }

    @Override
    public void onPartial(String text) {
        if (!on || !usingVosk) return;
        long now = System.currentTimeMillis();
        if (now >= ttsUntil) return;
        // 끼어들기: 앱이 말하는 중에 "하이 조아"가 들리면 바로 말을 멈춤
        String w = word(act.getSharedPreferences("hud", Activity.MODE_PRIVATE)).replace(" ", "");
        if (strongWake(text, w)) {
            ttsUntil = 0;
            try {
                if (tts != null) tts.stop();
                Runnable r = shutUp;
                if (r != null) r.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** 계속 듣는 귀가 멈춤(마이크·모델 문제) → 예전 방식으로 */
    void earStopped() {
        if (!on) return;
        usingVosk = false;
        again(800);
    }

    /** "하이 조아"(또는 설정한 부르는 말)가 확실히 들어 있나 — "좋아" 한 마디는 아님 */
    static boolean strongWake(String text, String w) {
        String s = text.replace(" ", "");
        if (!w.isEmpty() && !w.equals("조아야") && s.contains(w)) return true;
        if (s.contains("조아야")) return true;
        return s.matches(".*(하이|아이|화이|타이|헤이|하이요)(조아|좋아|조와|죠아|좋와|조아야|좋아야).*");
    }

    private static boolean isNavVoice(String t) {
        String s = t.replace(" ", "");
        return s.endsWith("입니다") || s.contains("잠시후") || s.contains("전방") || s.contains("미터앞") || s.contains("경로를");
    }

    private boolean check(List<String> heard) {
        SharedPreferences p = act.getSharedPreferences("hud", Activity.MODE_PRIVATE);
        String w = word(p).replace(" ", "");
        // ① 이어서 대화하는 중: 부르는 말 없이 바로 명령·질문
        if (System.currentTimeMillis() < followUntil && !heard.isEmpty()) {
            String s0 = heard.get(0).trim();
            if (!s0.isEmpty() && after(s0.replace(" ", ""), w) == null) {
                boolean asked = System.currentTimeMillis() < askedUntil;
                askedUntil = 0;
                NavListenerService.log("이어서 들음: \"" + s0 + "\"");
                if (handle(s0, !asked)) return true;
                if (asked) { // "조아야" → "네" → "OO 가자": 이때만 목적지 화면
                    NavListenerService.log("목적지로 봄: \"" + s0 + "\"");
                    goDest(s0);
                    return true;
                }
            }
        }
        for (String s : heard) {
            String rest = after(s.replace(" ", ""), w);
            if (rest == null && w.contains("조아")) {
                for (String a : JOA_ALIAS) {
                    String r = after(s.replace(" ", ""), a);
                    if (r != null && !r.isEmpty()) {
                        rest = r;
                        break;
                    }
                }
            }
            if (rest == null) continue;
            if (rest.replaceAll("[\\s.,!?~]", "").length() < 2) rest = ""; // "하이 좋아" → 남은 "아" 같은 찌꺼기는 버림
            NavListenerService.log("부르는 말 들음: \"" + s + "\"");
            if (!rest.isEmpty() && handle(rest, false)) return true;
            if (!rest.isEmpty()) { // 명령도 AI도 아닌 말 → 목적지
                goDest(rest);
                return true;
            }
            // "조아야"만 부름 → 화면은 그대로 두고 소리로만 "네" 하고 다음 말을 기다림
            NavListenerService.log("부르기만 함 → 화면 그대로, 다음 말 기다림");
            long now = System.currentTimeMillis();
            askedUntil = now + 12_000L;
            followUntil = Math.max(followUntil, now + 12_000L);
            speak("네");
            again(900);
            return true;
        }
        return false;
    }

    /**
     * 부르는 말 뒤의 말 처리: ① 정해진 HUD 명령 ② AI 두뇌(키가 있으면) ③ 목적지.
     * follow = 이어서 듣는 중 (이때는 명령·AI만, 아무 말이나 목적지로 보지 않음). 처리했으면 true
     */
    private boolean handle(String said, boolean follow) {
        // HUD가 뒤에 살아 있으면 바로, 없으면 HUD를 띄우면서 실행 (티맵·홈 화면에서 불러도 됨)
        if (HudActivity.command(said)) {
            followUntil = System.currentTimeMillis() + 20_000L;
            again(1500);
            return true;
        }
        if (Brain.enabled(act)) {
            followUntil = System.currentTimeMillis() + 25_000L;
            HudActivity.brain(act, said);
            again(2500);
            return true;
        }
        if (!HudActivity.alive() && HudActivity.looksLikeCommand(said)) {
            HudActivity.openWith(act, "voice", said); // HUD가 꺼져 있으면 띄운 뒤 명령 실행
            followUntil = System.currentTimeMillis() + 20_000L;
            again(2500);
            return true;
        }
        return false; // follow 중이면 무시, 아니면 목적지로
    }

    /** said 안에서 부르는 말(발음이 비슷해도 됨)을 찾으면 그 뒤에 남은 말, 없으면 null */
    static String after(String said, String wake) {
        if (said.isEmpty() || wake.isEmpty()) return null;
        int at = said.indexOf(wake);
        if (at >= 0) return said.substring(at + wake.length()).trim();
        // 비슷한 발음 ("조아야" ↔ "좋아야", "조아 야")
        int n = wake.length();
        for (int len = Math.max(1, n - 1); len <= n + 1; len++) {
            for (int i = 0; i + len <= said.length(); i++) {
                String part = said.substring(i, i + len);
                if (VoicePick.similar(part, wake) >= 0.75) return said.substring(i + len).trim();
            }
        }
        return null;
    }
}
