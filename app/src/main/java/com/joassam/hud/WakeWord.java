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

    /** 듣기 시작할 때마다 나는 '띵' 소리를 막음 (시스템 소리만, 티맵 안내 음성은 그대로) */
    private void muteBeep(boolean mute) {
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            if (mute) {
                if (savedSystemVol < 0) savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM);
                am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
            } else if (savedSystemVol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0);
                savedSystemVol = -1;
            }
        } catch (Exception ignored) {
        }
    }

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
            status = "'" + word(act.getSharedPreferences("hud", Activity.MODE_PRIVATE)) + "' 듣는 중";
        } catch (Exception e) {
            again(5000);
        }
    }

    private void again(long ms) {
        if (!on) return;
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::listen, ms);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
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
