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

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean on = false;
    private boolean hot = false;
    private int errors = 0;
    private int savedSystemVol = -1;
    /** 폰 안 인식기(소리 없음)를 쓰는 중인지. 안 되면 구글 인식기로 바꾸고 기억함 */
    private boolean onDevice = false;
    private static boolean onDeviceFailed = false;
    private boolean onDeviceWorked = false;
    static volatile String status = "";

    WakeWord(Activity a) {
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
        errors = 0;
        muteBeep(true);
        h.postDelayed(this::listen, 1500);
        h.postDelayed(monitor, 1500);
    }

    void stop() {
        on = false;
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

    private void listen() {
        if (!on || act.isFinishing()) return;
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
        if (tmapTalking()) { // 티맵 안내 음성 중엔 '띵'이 겹치지 않게 끝날 때까지 기다림
            h.postDelayed(this::listen, 1000);
            return;
        }
        try {
            if (sr == null) {
                onDevice = false;
                if (!onDeviceFailed && android.os.Build.VERSION.SDK_INT >= 33
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
            h.removeCallbacks(unmuteMusic);
            if (musicMuted) h.postDelayed(unmuteMusic, 1200); // 시작 '띵'이 지나간 뒤 미디어 소리 되살림
        }
        @Override public void onBeginningOfSpeech() {
            h.removeCallbacks(quietRestart); // 말하는 중엔 끊지 않음 (대신 최대 10초)
            h.postDelayed(quietRestart, 10_000);
        }
        @Override public void onRmsChanged(float v) { }
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
            errors = 0;
            if (onDevice) onDeviceWorked = true;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && check(r)) return;
            again(200);
        }

        @Override
        public void onError(int e) {
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
                errors = 0;
                again(200);
                return;
            }
            errors++;
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

    /** "조아야"를 폰이 자주 이렇게 알아들음 → 뒤에 말이 이어질 때만 부르는 말로 인정 */
    private static final String[] JOA_ALIAS = {"좋아요", "좋아야", "조아요", "좋아", "조아", "하이좋아", "하이조아"};

    private boolean check(List<String> heard) {
        SharedPreferences p = act.getSharedPreferences("hud", Activity.MODE_PRIVATE);
        String w = word(p).replace(" ", "");
        // ① 이어서 대화하는 중: 부르는 말 없이 바로 명령·질문
        if (System.currentTimeMillis() < followUntil && !heard.isEmpty()) {
            String s0 = heard.get(0).trim();
            if (!s0.isEmpty() && after(s0.replace(" ", ""), w) == null) {
                NavListenerService.log("이어서 들음: \"" + s0 + "\"");
                if (handle(s0, true)) return true;
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
            NavListenerService.log("부르는 말 들음: \"" + s + "\"");
            if (!rest.isEmpty() && handle(rest, false)) return true;
            stop();
            Intent i = new Intent(act, DestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!rest.isEmpty()) i.putExtra("said", rest);
            else i.putExtra("cmd", true); // "조아야"만 부름 → "네, 말씀하세요" (명령·목적지 모두 받음)
            act.startActivity(i);
            return true;
        }
        return false;
    }

    /**
     * 부르는 말 뒤의 말 처리: ① 정해진 HUD 명령 ② AI 두뇌(키가 있으면) ③ 목적지.
     * follow = 이어서 듣는 중 (이때는 명령·AI만, 아무 말이나 목적지로 보지 않음). 처리했으면 true
     */
    private boolean handle(String said, boolean follow) {
        if (!(act instanceof HudActivity)) return false;
        HudActivity hud = (HudActivity) act;
        if (hud.voiceCommand(said)) {
            followUntil = System.currentTimeMillis() + 20_000L;
            again(1500);
            return true;
        }
        if (Brain.enabled(act)) {
            followUntil = System.currentTimeMillis() + 25_000L;
            hud.askBrain(said);
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
