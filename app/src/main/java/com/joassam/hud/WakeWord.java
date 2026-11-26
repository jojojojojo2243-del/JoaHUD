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
final class WakeWord {

    private final android.content.Context act; // HUD 화면 또는 VoiceService (HUD 밖에서도 들음)
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean on = false;
    private boolean hot = false;
    private int errors = 0;
    private int savedSystemVol = -1;
    /** 폰 안 인식기(소리 없음)를 쓰는 중인지. 안 되면 구글 인식기로 바꾸고 기억함 */
    private boolean onDevice = false;
    private static boolean onDeviceFailed = false;

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
    private int onDeviceBadStarts = 0;
    static volatile String status = "";

    WakeWord(android.content.Context a) {
        act = a;
    }

    static String word(SharedPreferences p) {
        String w = p.getString("wake_word", "조아야").trim();
        return w.isEmpty() ? "조아야" : w;
    }

    /** 예전 계속 듣기용 음성 모델(약 90MB)이 남아 있으면 지움 */
    private static boolean cleaned = false;

    private void cleanOldModel() {
        if (cleaned) return;
        cleaned = true;
        final java.io.File d = new java.io.File(act.getFilesDir(), "vosk-model-small-ko-0.22");
        if (!d.exists()) return;
        new Thread(() -> {
            del(d);
            NavListenerService.log("쓰지 않는 음성 모델 지움 (저장공간 약 90MB 확보)");
        }).start();
    }

    private static void del(java.io.File f) {
        java.io.File[] k = f.listFiles();
        if (k != null) for (java.io.File x : k) del(x);
        f.delete();
    }

    void start() {
        cleanOldModel();
        initTts();
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
        return on && !onDevice && act.getSharedPreferences("hud", Activity.MODE_PRIVATE).getBoolean("ding_block", true);
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
                    NavListenerService.log("부르는 말 듣기가 멈춰 있어서 다시 시작");
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
        // 앱이 말하는 중("네" 등)엔 끝날 때까지 기다렸다가 들음
        if (System.currentTimeMillis() < ttsUntil || (tts != null && ttsReady && tts.isSpeaking())) {
            again(250);
            return;
        }
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
            NavListenerService.log("폰 온도 " + t + "℃ → 부르는 말 다시 듣기");
        } else if (!hot && t >= 42f) {
            hot = true;
            NavListenerService.log("폰이 뜨거움 " + t + "℃ → 부르는 말 듣기 잠시 멈춤");
        }
        if (hot) {
            status = "폰이 뜨거워 쉬는 중 (" + t + "℃)";
            h.postDelayed(this::listen, 30_000);
            return;
        }
        // ── 계속 듣는 귀 (모델이 있으면): 끊기는 틈 없이 듣고, 티맵이 앞에 있어도 들음 ──

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
            if (!onDevice) musicMute(true);
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
            long nowE = System.currentTimeMillis();
            if (e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && nowE - lastErrLog > 60_000L) {
                lastErrLog = nowE;
                NavListenerService.log("부르는 말 인식 오류 " + e + (e == 3 || e == 8
                        ? " (마이크를 다른 앱이 쓰는 중 — 티맵 '아리아' 호출이 켜져 있으면 끄세요)" : ""));
            }
            // 폰 안 인식기가 한국어를 못 하거나 연결이 안 되면 → 구글 인식기로 바꿈
            // '띵' 소리 없는 폰 안 인식기를 최대한 지킴: 한국어 미지원(12·13·14)이거나, 한 번도 안 되면서 5번 연속 꼬일 때만 구글로
            if (onDevice && !onDeviceWorked && (e == 5 || e == 11)) onDeviceBadStarts++;
            if (onDevice && !onDeviceWorked && (e == 12 || e == 13 || e == 14 || onDeviceBadStarts >= 5)) {
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
                // 구글 인식기(띵 소리)로 넘기지 않고 폰 안 인식기를 새로 만들어 다시 들음 — 자주 꼬이면 조금씩 쉬었다가
                again(e == 8 ? 1500 : Math.min(3000, 600L * clientErrs));
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
    /** 미리 말할 준비 (처음 "네"가 늦거나 빠지지 않게) */
    private void initTts() {
        if (tts != null) return;
        try {
            tts = new android.speech.tts.TextToSpeech(act.getApplicationContext(), st -> {
                ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                if (ttsReady && tts != null) tts.setLanguage(java.util.Locale.KOREAN);
            });
        } catch (Exception ignored) {
        }
    }

    /** 운전 중엔 화면을 못 보니 소리로 대답: "네", "다시 말씀해 주세요" 등 */
    private void speak(String msg) {
        spoke(msg);
        musicMute(false); // '띵' 막으려 꺼 둔 미디어 소리를 먼저 켜야 대답이 들림
        if (sr != null) { // 대답하는 동안 인식기는 멈춤 (띠리링·자기 목소리 듣기 방지)
            try { sr.cancel(); } catch (Exception ignored) { }
        }
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

    /** 장소로 가자는 말인가 ("대전역 가자", "집으로", "○○ 안내해 줘") */
    static boolean destLike(String s) {
        String t = s.replace(" ", "");
        return t.matches(".*(가자|가줘|가주|가려|갈래|가고싶|안내|목적지|까지|으로|로가|집|회사|역|병원|마트|공항|휴게소|주유소|터미널|시청|학교|농장).*");
    }

    /** 부른 뒤 한 말을 못 알아들음 → 소리로 알리고 10초 더 기다림 */
    private void notUnderstood(String said) {
        NavListenerService.log("못 알아들음: \"" + said + "\" → 다시 말씀해 달라고 함");
        long now = System.currentTimeMillis();
        askedUntil = now + 12_000L;
        followUntil = Math.max(followUntil, now + 12_000L);
        speak("잘 못 알아들었어요. 다시 말씀해 주세요");
        again(500);
    }

    /** 목적지로 길안내 (이때만 목적지 화면을 띄움) */
    private void goDest(String said) {
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
    /**
     * 부르는 말로 인정하는 것: "하이 조아"(하이·아이·화이·헤이 + 조아·좋아) 또는 "조아야/좋아야".
     * "좋아" 한 마디는 일상 대화("그거 좋아 보이네")에 너무 흔해서 부르는 말이 아님 — 옆 사람과의 대화에 끼어들던 원인.
     */
    private static final String[] JOA_ALIAS = {"하이조아", "하이좋아", "아이조아", "아이좋아", "화이조아", "화이좋아",
            "헤이조아", "헤이좋아", "하이조와", "좋아야", "조와야"};
    /** 부르는 말은 말의 맨 앞(3글자 안)에 있어야 함 — 문장 중간에 섞인 소리로 반응하지 않게 */
    private static final int WAKE_MAX_POS = 3;


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
                if (asked) { // "조아야" → "네" → "OO 가자": 장소 말일 때만 목적지 화면
                    if (destLike(s0)) {
                        NavListenerService.log("목적지로 봄: \"" + s0 + "\"");
                        goDest(s0);
                    } else notUnderstood(s0);
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
            if (!rest.isEmpty()) { // 명령도 AI도 아닌 말: 장소 말이면 목적지, 아니면 다시 물음
                if (destLike(rest)) goDest(rest);
                else notUnderstood(rest);
                return true;
            }
            // "조아야"만 부름 → 화면은 그대로 두고 소리로만 "네" 하고 다음 말을 기다림
            NavListenerService.log("부르기만 함 → 화면 그대로, 다음 말 기다림");
            long now = System.currentTimeMillis();
            askedUntil = now + 10_000L; // "네" 하고 10초만 기다림
            followUntil = Math.max(followUntil, now + 10_000L);
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
            // 명령 뒤엔 이어 듣지 않음 (예전 20초 동안 아무 말이나 받아 옆 사람 대화에 끼어들었음)
            followUntil = 0;
            again(1500);
            return true;
        }
        if (Brain.enabled(act)) {
            // AI 대답 뒤 8초만 이어서 들음 (AI가 되물었을 때 바로 대답할 수 있게). 그 뒤엔 다시 "하이 조아"가 있어야 함
            followUntil = System.currentTimeMillis() + 8_000L + 4_000L; // 대답을 말하는 시간 약 4초 포함
            HudActivity.brain(act, said);
            again(2500);
            return true;
        }
        if (!HudActivity.alive() && HudActivity.looksLikeCommand(said)) {
            HudActivity.openWith(act, "voice", said); // HUD가 꺼져 있으면 띄운 뒤 명령 실행
            followUntil = 0;
            again(2500);
            return true;
        }
        return false; // follow 중이면 무시, 아니면 목적지로
    }

    /** said 안에서 부르는 말(발음이 비슷해도 됨)을 찾으면 그 뒤에 남은 말, 없으면 null */
    static String after(String said, String wake) {
        if (said.isEmpty() || wake.isEmpty()) return null;
        int at = said.indexOf(wake);
        if (at >= 0 && at <= WAKE_MAX_POS) return said.substring(at + wake.length()).trim();
        // 비슷한 발음 ("조아야" ↔ "좋아야", "조아 야") — 3글자 짜리 짧은 말은 엉뚱한 말과도 비슷해서 더 엄격하게
        int n = wake.length();
        if (n < 3) return null;
        for (int len = Math.max(2, n - 1); len <= n + 1; len++) {
            for (int i = 0; i + len <= said.length() && i <= WAKE_MAX_POS; i++) {
                String part = said.substring(i, i + len);
                // "조아야"처럼 '야'로 끝나는 부르는 말은 비슷한 말도 '야'로 끝나야 함 ("좋아요"는 대화)
                if (wake.endsWith("야") && !part.endsWith("야")) continue;
                if (VoicePick.similar(part, wake) >= 0.85) return said.substring(i + len).trim();
            }
        }
        return null;
    }
}
