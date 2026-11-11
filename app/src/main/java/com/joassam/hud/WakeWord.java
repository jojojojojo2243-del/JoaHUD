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
    private final Runnable unmuteMusic = () -> musicMute(false);

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
        if (mute) h.postDelayed(unmuteMusic, 2500); // 무슨 일이 있어도 2.5초 뒤엔 다시 켬
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
        musicMute(true);
        try {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(act);
                sr.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 폰 안에서 처리(데이터·열 절약), 안 되면 자동으로 온라인
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            sr.cancel();
            sr.startListening(i);
            h.removeCallbacks(quietRestart);
            h.postDelayed(quietRestart, 4500);
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
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {
            h.removeCallbacks(unmuteMusic);
            h.postDelayed(unmuteMusic, 400); // 시작 '띵'이 지나간 뒤 미디어 소리 되살림
        }
        @Override public void onBeginningOfSpeech() {
            h.removeCallbacks(quietRestart); // 말하는 중엔 끊지 않음 (대신 최대 10초)
            h.postDelayed(quietRestart, 10_000);
        }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() {
            h.removeCallbacks(quietRestart);
            if (!tmapTalking()) musicMute(true); // 끝날 때 나는 '띵'도 막음
        }
        @Override public void onEvent(int t, Bundle b) { }

        @Override
        public void onPartialResults(Bundle b) {
            // 부르는 말이 들리는 순간 바로 반응하지 않고, 끝까지 들은 뒤(onResults) 판단
        }

        @Override
        public void onResults(Bundle b) {
            errors = 0;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && check(r)) return;
            again(200);
        }

        @Override
        public void onError(int e) {
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
    private boolean check(List<String> heard) {
        SharedPreferences p = act.getSharedPreferences("hud", Activity.MODE_PRIVATE);
        String w = word(p).replace(" ", "");
        for (String s : heard) {
            String rest = after(s.replace(" ", ""), w);
            if (rest == null) continue;
            NavListenerService.log("부르는 말 들음: \"" + s + "\"");
            stop();
            Intent i = new Intent(act, DestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!rest.isEmpty()) i.putExtra("said", rest);
            act.startActivity(i);
            return true;
        }
        return false;
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
